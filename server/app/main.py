from __future__ import annotations

import csv
import io
import json
import logging
import os
import secrets
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Iterator, Optional

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import (
    HTMLResponse,
    JSONResponse,
    RedirectResponse,
    StreamingResponse,
)

from . import config
from .auth import (
    client_ip,
    configure_trusted_proxies,
    create_device,
    current_device,
    load_or_create_admin_token,
    set_current_admin_token,
    utcnow,
    verify_admin_token,
    verify_enroll_secret,
)
from .credentials import CredentialLedger
from .db import Store
from .throttle import LoginThrottle
from .schemas import (
    CLEAR_CONFIRM_PHRASE,
    AdminLoginIn,
    BarcodeOut,
    BarcodePage,
    BatchIn,
    BatchOut,
    ClearIn,
    ClearOut,
    RegisterIn,
    RegisterOut,
    RestoreOut,
    StatsOut,
    TrashItem,
    TrashListOut,
)

API_VERSION = "1.0.0"

logger = logging.getLogger("scan_server")

ADMIN_HTML = (Path(__file__).parent / "admin.html").read_text(encoding="utf-8")
ADMIN_LOGIN_HTML = (Path(__file__).parent / "admin_login.html").read_text(encoding="utf-8")

ADMIN_CSP = (
    "default-src 'self'; "
    "script-src 'self' 'unsafe-inline'; "
    "style-src 'self' 'unsafe-inline'; "
    "img-src 'self' data:; "
    "connect-src 'self'; "
    "frame-ancestors 'none'; "
    "base-uri 'none'; "
    "form-action 'self'"
)


def normalize_captured_at(value: Optional[datetime]) -> str:
    """客户端时间统一成 UTC；明显超前的时钟按服务器时间处理。"""
    now = datetime.now(timezone.utc)
    if value is None:
        return now.isoformat(timespec="seconds")
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    value = value.astimezone(timezone.utc)
    if value > now + timedelta(seconds=config.MAX_CLOCK_SKEW_SECONDS):
        value = now
    return value.isoformat(timespec="seconds")


def escape_like(value: str) -> str:
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def csv_cell(value: object) -> str:
    """防 CSV 公式注入：Excel 会把 = + - @ 开头的单元格当公式执行。"""
    text = "" if value is None else str(value)
    if text[:1] in ("=", "+", "-", "@", "\t", "\r"):
        return "'" + text
    return text


def get_db(request: Request) -> Iterator[sqlite3.Connection]:
    conn: sqlite3.Connection = request.app.state.store.connect()
    try:
        yield conn
    finally:
        conn.close()


def make_pre_clear_backup(store: Store) -> Optional[str]:
    """清除数据前自动备份数据库，误操作后还能找回。

    用 sqlite3 backup API（服务运行中也安全），写到 data/backups/。
    备份失败不阻断清除，但会记 error 日志并返回 None。
    """
    src_path = Path(store.path)
    if not src_path.exists():
        return None

    backup_dir = src_path.parent / "backups"
    try:
        backup_dir.mkdir(parents=True, exist_ok=True)
        os.chmod(backup_dir, 0o700)
    except OSError:
        pass

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    dst_path = backup_dir / f"pre-clear-{stamp}.db"
    try:
        with sqlite3.connect(src_path) as src, sqlite3.connect(dst_path) as dst:
            src.backup(dst)
        os.chmod(dst_path, 0o600)
        return str(dst_path)
    except (OSError, sqlite3.Error) as exc:
        logger.error("清除前备份失败：%s", exc)
        return None


# --------------------------------------------------------------- 回收站
#
# 设计取舍：不用软删除（给三张表都加 deleted_at 会让所有查询变复杂），
# 而是「清空时把整批数据序列化存一份 JSON 快照」。
# 好处是恢复是原子的、可整批一键还原，且不污染正常查询路径。

TRASH_TABLES_SQL = {
    "barcodes": (
        "SELECT code, first_seen_at, last_seen_at, scan_count, first_device_id "
        "FROM barcodes"
    ),
    "batches": (
        "SELECT id, device_id, app_version, captured_at, received_at, item_count "
        "FROM batches"
    ),
    "scan_events": (
        "SELECT barcode, device_id, batch_id, captured_at, received_at, "
        "format, symbology_id, line_count FROM scan_events"
    ),
}


def build_trash_payload(conn: sqlite3.Connection) -> dict:
    """把当前所有条码相关数据读成可序列化的快照。"""
    return {
        table: [dict(row) for row in conn.execute(sql)]
        for table, sql in TRASH_TABLES_SQL.items()
    }


def push_to_trash(conn: sqlite3.Connection, payload: dict) -> Optional[str]:
    """把快照存入回收站，返回 trash id；超限或为空则不入站。"""
    barcodes = payload.get("barcodes") or []
    batches = payload.get("batches") or []
    events = payload.get("scan_events") or []
    if not (barcodes or events or batches):
        return None

    raw = json.dumps(payload, ensure_ascii=False)
    if len(raw.encode("utf-8")) > config.MAX_TRASH_PAYLOAD_BYTES:
        logger.warning(
            "快照超过 %s 字节，跳过回收站（本条数据只能靠备份文件恢复）",
            config.MAX_TRASH_PAYLOAD_BYTES,
        )
        return None

    trash_id = "tr_" + secrets.token_hex(8)
    conn.execute(
        "INSERT INTO trash_batches"
        "(id, created_at, barcode_count, event_count, batch_count, payload) "
        "VALUES (?,?,?,?,?,?)",
        (
            trash_id,
            utcnow(),
            len(barcodes),
            len(events),
            len(batches),
            raw,
        ),
    )

    # 只保留最近 N 批，避免库无限增长
    conn.execute(
        """
        DELETE FROM trash_batches WHERE id NOT IN (
            SELECT id FROM trash_batches
            ORDER BY created_at DESC, rowid DESC
            LIMIT ?
        )
        """,
        (max(1, config.MAX_TRASH_BATCHES),),
    )
    return trash_id


def restore_from_trash(conn: sqlite3.Connection, trash_id: str) -> Optional[dict]:
    """把某个回收站快照合并回库。返回统计；快照不存在返回 None。

    合并规则（与正常上传一致）：
    - batches: 主键冲突则跳过
    - barcodes: 已存在则合并—— earliest first_seen、latest last_seen、scan_count 相加
      （清空后的新扫描与快照里的旧扫描是两批互不重叠的事件，相加不会重复计数）
    - scan_events: 靠 UNIQUE(batch_id, barcode) 去重
    """
    row = conn.execute(
        "SELECT payload FROM trash_batches WHERE id = ?", (trash_id,)
    ).fetchone()
    if row is None:
        return None

    try:
        payload = json.loads(row["payload"])
    except (TypeError, ValueError):
        raise HTTPException(status_code=422, detail="trash payload corrupted")

    restored_batches = 0
    for b in payload.get("batches") or []:
        cur = conn.execute(
            "INSERT OR IGNORE INTO batches"
            "(id, device_id, app_version, captured_at, received_at, item_count) "
            "VALUES (?,?,?,?,?,?)",
            (
                b.get("id"),
                b.get("device_id"),
                b.get("app_version"),
                b.get("captured_at"),
                b.get("received_at"),
                b.get("item_count") or 0,
            ),
        )
        restored_batches += max(0, cur.rowcount)

    barcode_rows = payload.get("barcodes") or []
    for bc in barcode_rows:
        conn.execute(
            """
            INSERT INTO barcodes
                (code, first_seen_at, last_seen_at, scan_count, first_device_id)
            VALUES (?,?,?,?,?)
            ON CONFLICT(code) DO UPDATE SET
                first_seen_at = MIN(barcodes.first_seen_at, excluded.first_seen_at),
                last_seen_at  = MAX(barcodes.last_seen_at,  excluded.last_seen_at),
                scan_count    = barcodes.scan_count + excluded.scan_count
            """,
            (
                bc.get("code"),
                bc.get("first_seen_at"),
                bc.get("last_seen_at"),
                bc.get("scan_count") or 0,
                bc.get("first_device_id"),
            ),
        )
    # 合并进去的也算"恢复"，否则界面上会少报（快照里的条码确实都回来了）
    restored_barcodes = len(barcode_rows)

    restored_events = 0
    for ev in payload.get("scan_events") or []:
        cur = conn.execute(
            "INSERT OR IGNORE INTO scan_events"
            "(barcode, device_id, batch_id, captured_at, received_at, "
            " format, symbology_id, line_count) VALUES (?,?,?,?,?,?,?,?)",
            (
                ev.get("barcode"),
                ev.get("device_id"),
                ev.get("batch_id"),
                ev.get("captured_at"),
                ev.get("received_at"),
                ev.get("format"),
                ev.get("symbology_id"),
                ev.get("line_count"),
            ),
        )
        restored_events += max(0, cur.rowcount)

    # 恢复成功后立刻出站，避免重复恢复导致计数翻倍
    conn.execute("DELETE FROM trash_batches WHERE id = ?", (trash_id,))

    return {
        "trash_id": trash_id,
        "restored_barcodes": restored_barcodes,
        "restored_events": restored_events,
        "restored_batches": restored_batches,
    }


def create_app(
    db_path: Optional[str | Path] = None,
    enroll_secret: Optional[str] = None,
    credentials_path: Optional[str | Path] = None,
    admin_token: Optional[str] = None,
    admin_token_path: Optional[str | Path] = None,
    throttle: Optional[LoginThrottle] = None,
) -> FastAPI:
    store = Store(db_path or os.getenv("DB_PATH", str(config.DEFAULT_DB_PATH)))
    store.init()

    app = FastAPI(
        title="Barcode Sync Server",
        version=API_VERSION,
        docs_url="/docs" if config.DOCS_ENABLED else None,
        redoc_url="/redoc" if config.DOCS_ENABLED else None,
        openapi_url="/openapi.json" if config.DOCS_ENABLED else None,
    )
    app.state.store = store
    app.state.enroll_secret = enroll_secret or os.getenv(
        "ENROLL_SECRET", config.DEFAULT_ENROLL_SECRET
    )

    # ---- 管理员口令：未显式配置则启动时自动生成（落盘 0600） ----
    resolved_admin_token = load_or_create_admin_token(admin_token, admin_token_path)
    set_current_admin_token(resolved_admin_token)
    app.state.admin_token = resolved_admin_token

    configure_trusted_proxies(config.TRUSTED_PROXIES)

    # ---- 注册限速：按 IP 统计失败，超过阈值临时锁定 ----
    app.state.enroll_throttle = throttle or LoginThrottle(
        max_failures=config.ENROLL_MAX_FAILURES,
        window_seconds=config.ENROLL_FAILURE_WINDOW_SECONDS,
        lockout_seconds=config.ENROLL_LOCKOUT_SECONDS,
    )

    # ---- 管理台登录限速：同一 IP 连续失败即锁定 ----
    app.state.admin_throttle = LoginThrottle(
        max_failures=config.ENROLL_MAX_FAILURES,
        window_seconds=config.ENROLL_FAILURE_WINDOW_SECONDS,
        lockout_seconds=config.ENROLL_LOCKOUT_SECONDS,
    )

    ledger = CredentialLedger(
        credentials_path
        or os.getenv("CREDENTIALS_PATH")
        or (store.path.parent / "credentials.txt")
    )
    app.state.credentials_path = ledger.path
    ledger.record_enroll(app.state.enroll_secret)

    if app.state.enroll_secret == config.DEFAULT_ENROLL_SECRET:
        message = (
            "ENROLL_SECRET 仍是默认值 change-me。这个值会出现在仓库和教程里，"
            "任何人都能凭它注册设备，因此拒绝启动。请设置强随机口令："
            "python3 -c 'import secrets; print(secrets.token_urlsafe(32))'"
        )
        if config.STRICT_SECRETS:
            raise RuntimeError(message)
        logger.error(message)

    def _health_payload():
        try:
            conn = store.connect()
            try:
                conn.execute("SELECT 1").fetchone()
            finally:
                conn.close()
        except sqlite3.Error as exc:
            logger.error("health db check failed: %s", exc)
            return JSONResponse(
                status_code=503,
                content={
                    "ok": False,
                    "version": API_VERSION,
                    "error": "database unavailable",
                },
            )
        return {"ok": True, "version": API_VERSION, "server_time": utcnow()}

    # ------------------------------------------------------- security headers
    @app.middleware("http")
    async def security_headers(request: Request, call_next):
        response = await call_next(request)
        headers = response.headers
        headers["X-Content-Type-Options"] = "nosniff"
        headers["X-Frame-Options"] = "DENY"
        headers["Referrer-Policy"] = "no-referrer"
        headers["Permissions-Policy"] = "camera=(), microphone=(), geolocation=()"

        path = request.url.path
        if path == "/admin" or path == "/admin/login":
            headers["Content-Security-Policy"] = ADMIN_CSP
            headers["Cache-Control"] = "no-store"
        elif path.startswith("/v1/"):
            headers["Content-Security-Policy"] = "default-src 'none'"
            headers["Cache-Control"] = "no-store"
        return response

    # ----------------------------------------------------------- admin gate
    #
    # 控制台本身不含数据，但页面里的"设备 Token 输入框"等于把内部接口
    # 暴露给了全网。这里要求先通过管理员口令验证（Bearer），并用 HttpOnly
    # Cookie 记住登录态，避免口令停留在 localStorage。

    ADMIN_COOKIE = "admin_session"

    def _admin_authed(request: Request) -> bool:
        header = request.headers.get("authorization") or ""
        if header.lower().startswith("bearer "):
            if verify_admin_token(header.split(" ", 1)[1].strip()):
                return True
        return verify_admin_token(request.cookies.get(ADMIN_COOKIE))

    def require_read_access(
        request: Request,
        authorization: Optional[str] = Header(default=None),
    ):
        """读接口的鉴权：管理员会话（Cookie）或 Bearer（授权码/设备 token）均可。

        控制台的浏览器只能带 Cookie，客户端只能带 Bearer，这里统一放行。
        """
        if _admin_authed(request):
            setattr(request.state, "auth_mode", "admin")
            return {"id": "admin", "name": "admin"}
        return current_device(request, authorization)

    # ---------------------------------------------------------------- health

    @app.get("/health")
    def health():
        return _health_payload()

    @app.head("/health", include_in_schema=False)
    def health_head():
        return _health_payload()

    @app.get("/", include_in_schema=False)
    def index():
        return RedirectResponse("/admin")

    @app.get("/admin/login", response_class=HTMLResponse, include_in_schema=False)
    def admin_login_page():
        return ADMIN_LOGIN_HTML

    @app.post("/admin/login", include_in_schema=False)
    def admin_login(request: Request, body: AdminLoginIn):
        ip = client_ip(request)
        throttle: LoginThrottle = request.app.state.admin_throttle
        wait = throttle.retry_after(ip)
        if wait > 0:
            raise HTTPException(
                status_code=429,
                detail=f"too many failed attempts, retry in {wait}s",
                headers={"Retry-After": str(wait)},
            )

        if not verify_admin_token(body.token):
            throttle.record_failure(ip)
            raise HTTPException(status_code=401, detail="invalid admin token")

        throttle.reset(ip)
        response = JSONResponse({"ok": True})
        response.set_cookie(
            ADMIN_COOKIE,
            body.token,
            httponly=True,
            secure=True,       # 只经 HTTPS 传输
            samesite="strict",
            max_age=8 * 3600,
            # 必须是 /：控制台页面要调用 /v1/* 读取数据。
            # 之前写成 /admin 导致浏览器不把 Cookie 发给 /v1/*，
            # 登录成功后立刻 401，表现为"登录不上"。
            path="/",
        )
        return response

    @app.post("/admin/logout", include_in_schema=False)
    def admin_logout():
        response = JSONResponse({"ok": True})
        response.delete_cookie(ADMIN_COOKIE, path="/")
        return response

    @app.get("/admin", response_class=HTMLResponse, include_in_schema=False)
    def admin_page(request: Request):
        if not _admin_authed(request):
            return RedirectResponse("/admin/login", status_code=303)
        return ADMIN_HTML

    # --------------------------------------------------------------- devices

    @app.post("/v1/devices/register", response_model=RegisterOut)
    def register(body: RegisterIn, request: Request, conn=Depends(get_db)):
        ip = client_ip(request)
        throttle: LoginThrottle = request.app.state.enroll_throttle

        wait = throttle.retry_after(ip)
        if wait > 0:
            # 锁定期间直接拒绝，不再做常量时间比较，也不消耗尝试次数
            raise HTTPException(
                status_code=429,
                detail=f"too many failed attempts, retry in {wait}s",
                headers={"Retry-After": str(wait)},
            )

        if not verify_enroll_secret(body.enroll_secret, request.app.state.enroll_secret):
            throttle.record_failure(ip)
            raise HTTPException(status_code=403, detail="bad enroll secret")

        throttle.reset(ip)

        name = "".join(ch for ch in body.name if ch.isprintable()).strip()
        if not name:
            raise HTTPException(status_code=422, detail="invalid device name")

        with conn:
            device_id, token = create_device(conn, name)

        created_at = utcnow()
        try:
            ledger.record_device(device_id, name, token, created_at)
        except OSError:
            # 注册已经成功，不能因为留档失败让客户端丢掉 token
            logger.exception("failed to write credentials file for %s", device_id)

        return RegisterOut(
            device_id=device_id,
            token=token,
            server_time=datetime.now(timezone.utc),
        )

    # --------------------------------------------------------------- ingest

    @app.post("/v1/scan-batches", response_model=BatchOut, status_code=201)
    def ingest(
        batch: BatchIn,
        device=Depends(current_device),
        conn: sqlite3.Connection = Depends(get_db),
    ):
        if len(batch.items) > config.MAX_BATCH_ITEMS:
            raise HTTPException(
                status_code=413,
                detail=f"too many items (max {config.MAX_BATCH_ITEMS})",
            )

        received_at = utcnow()
        captured_at = normalize_captured_at(batch.captured_at)

        with conn:
            cur = conn.execute(
                """
                INSERT OR IGNORE INTO batches
                    (id, device_id, app_version, captured_at, received_at, item_count)
                VALUES (?,?,?,?,?,0)
                """,
                (batch.batch_id, device["id"], batch.app_version, captured_at, received_at),
            )

            # 幂等：这个 batch 已经收过，直接返回，不重复计数
            if cur.rowcount == 0:
                row = conn.execute(
                    "SELECT device_id, item_count FROM batches WHERE id = ?",
                    (batch.batch_id,),
                ).fetchone()
                if row is None:
                    raise HTTPException(
                        status_code=409,
                        detail="batch id conflict, retry with a new batch_id",
                    )
                if row["device_id"] != device["id"]:
                    # 防止别的设备抢用同一个 batch_id，导致数据被静默丢弃
                    raise HTTPException(
                        status_code=409,
                        detail="batch_id already used by another device",
                    )
                total = conn.execute("SELECT COUNT(*) AS c FROM barcodes").fetchone()["c"]
                return BatchOut(
                    batch_id=batch.batch_id,
                    accepted=0,
                    duplicated=int(row["item_count"]),
                    barcodes_total=total,
                )

            accepted = 0
            duplicated = 0
            seen: set[str] = set()

            for item in batch.items:
                code = item.raw_text.strip()
                if not code:
                    duplicated += 1
                    continue
                if len(code) > config.MAX_CODE_LEN:
                    raise HTTPException(status_code=422, detail="barcode too long")
                if "\n" in code or "\r" in code or "\x00" in code:
                    raise HTTPException(status_code=422, detail="barcode contains control characters")
                if code in seen:
                    duplicated += 1
                    continue
                seen.add(code)

                conn.execute(
                    """
                    INSERT INTO barcodes
                        (code, first_seen_at, last_seen_at, scan_count, first_device_id)
                    VALUES (?,?,?,1,?)
                    ON CONFLICT(code) DO UPDATE SET
                        first_seen_at = CASE
                            WHEN excluded.first_seen_at < barcodes.first_seen_at
                            THEN excluded.first_seen_at ELSE barcodes.first_seen_at END,
                        last_seen_at = CASE
                            WHEN excluded.last_seen_at > barcodes.last_seen_at
                            THEN excluded.last_seen_at ELSE barcodes.last_seen_at END,
                        scan_count = barcodes.scan_count + 1
                    """,
                    (code, captured_at, captured_at, device["id"]),
                )
                conn.execute(
                    """
                    INSERT INTO scan_events
                        (barcode, device_id, batch_id, captured_at, received_at,
                         format, symbology_id, line_count)
                    VALUES (?,?,?,?,?,?,?,?)
                    """,
                    (
                        code,
                        device["id"],
                        batch.batch_id,
                        captured_at,
                        received_at,
                        item.format,
                        item.symbology_id,
                        item.line_count,
                    ),
                )
                accepted += 1

            conn.execute(
                "UPDATE batches SET item_count = ? WHERE id = ?",
                (accepted, batch.batch_id),
            )
            total = conn.execute("SELECT COUNT(*) AS c FROM barcodes").fetchone()["c"]

        return BatchOut(
            batch_id=batch.batch_id,
            accepted=accepted,
            duplicated=duplicated,
            barcodes_total=total,
        )

    # ---------------------------------------------------------------- query

    @app.get("/v1/barcodes", response_model=BarcodePage)
    def list_barcodes(
        q: str = Query(default="", max_length=200),
        limit: int = Query(default=100, ge=1, le=500),
        offset: int = Query(default=0, ge=0),
        device=Depends(require_read_access),
        conn: sqlite3.Connection = Depends(get_db),
    ):
        where, params = "", []
        if q:
            where = "WHERE b.code LIKE ? ESCAPE '\\'"
            params.append(f"%{escape_like(q)}%")

        total = conn.execute(
            f"SELECT COUNT(*) AS c FROM barcodes b {where}", params
        ).fetchone()["c"]

        rows = conn.execute(
            f"""
            SELECT b.code, b.first_seen_at, b.last_seen_at, b.scan_count,
                   (SELECT COUNT(DISTINCT e.device_id)
                      FROM scan_events e WHERE e.barcode = b.code) AS device_count
            FROM barcodes b
            {where}
            ORDER BY b.last_seen_at DESC, b.code
            LIMIT ? OFFSET ?
            """,
            [*params, limit, offset],
        ).fetchall()

        return BarcodePage(
            total=int(total),
            limit=limit,
            offset=offset,
            items=[
                BarcodeOut(
                    code=r["code"],
                    first_seen_at=r["first_seen_at"],
                    last_seen_at=r["last_seen_at"],
                    scan_count=int(r["scan_count"]),
                    device_count=int(r["device_count"]),
                )
                for r in rows
            ],
        )

    @app.get("/v1/barcodes/export.csv")
    def export_csv(
        request: Request,
        q: str = Query(default="", max_length=200),
        device=Depends(require_read_access),
    ):
        where, params = "", []
        if q:
            where = "WHERE b.code LIKE ? ESCAPE '\\'"
            params.append(f"%{escape_like(q)}%")

        def rows() -> Iterator[str]:
            conn: sqlite3.Connection = request.app.state.store.connect()
            try:
                cur = conn.execute(
                    f"""
                    SELECT b.code, b.first_seen_at, b.last_seen_at, b.scan_count,
                           (SELECT COUNT(DISTINCT e.device_id)
                              FROM scan_events e WHERE e.barcode = b.code) AS device_count
                    FROM barcodes b
                    {where}
                    ORDER BY b.last_seen_at DESC, b.code
                    """,
                    params,
                )
                buf = io.StringIO()
                writer = csv.writer(buf)
                yield "\ufeff"  # Excel 识别 UTF-8
                writer.writerow(
                    ["barcode", "first_seen_at", "last_seen_at", "scan_count", "device_count"]
                )
                yield buf.getvalue()
                for r in cur:
                    buf.seek(0)
                    buf.truncate(0)
                    writer.writerow(
                        [
                            csv_cell(r["code"]),
                            r["first_seen_at"],
                            r["last_seen_at"],
                            r["scan_count"],
                            r["device_count"],
                        ]
                    )
                    yield buf.getvalue()
            finally:
                conn.close()

        filename = "barcodes.csv"
        return StreamingResponse(
            rows(),
            media_type="text/csv; charset=utf-8",
            headers={"Content-Disposition": f'attachment; filename="{filename}"'},
        )

    # ---------------------------------------------------------------- clear
    #
    # 危险操作：一键清空所有条码记录。
    # 防误触与防滥用的三重设计：
    #   1. 只有管理员能调用（设备 token / 普通客户端一律拒绝）
    #   2. 必须原样提交确认短语，前端还要手动输入一遍
    #   3. 删除前自动备份数据库到 data/backups/

    @app.post("/v1/barcodes/clear", response_model=ClearOut)
    def clear_barcodes(
        request: Request,
        body: ClearIn,
        conn: sqlite3.Connection = Depends(get_db),
    ):
        if not _admin_authed(request):
            raise HTTPException(
                status_code=403, detail="admin only: clear requires console login"
            )

        if body.confirm.strip() != CLEAR_CONFIRM_PHRASE:
            raise HTTPException(
                status_code=422,
                detail=f"confirmation mismatch, expected exactly: {CLEAR_CONFIRM_PHRASE}",
            )

        store: Store = request.app.state.store
        backup_path = make_pre_clear_backup(store)

        with conn:
            # 先整体快照进回收站，再删除；两步在同一事务里，保证不会丢数据。
            payload = build_trash_payload(conn)
            trash_id = push_to_trash(conn, payload)

            # 删除顺序必须是 events -> barcodes -> batches：
            # scan_events 对 barcodes 和 batches 都有外键，反过来会触发约束错误。
            deleted_events = conn.execute("DELETE FROM scan_events").rowcount
            deleted_barcodes = conn.execute("DELETE FROM barcodes").rowcount
            deleted_batches = conn.execute("DELETE FROM batches").rowcount

        logger.warning(
            "管理员清空了全部条码记录：barcodes=%s events=%s batches=%s "
            "trash=%s backup=%s",
            deleted_barcodes,
            deleted_events,
            deleted_batches,
            trash_id,
            backup_path,
        )

        return ClearOut(
            ok=True,
            deleted_barcodes=max(0, deleted_barcodes),
            deleted_events=max(0, deleted_events),
            deleted_batches=max(0, deleted_batches),
            backup_path=backup_path,
            trash_id=trash_id,
        )

    # ------------------------------------------------------------- trash
    # 回收站：查看 / 一键整批恢复 / 永久删除。全部仅管理员可用。

    @app.get("/v1/trash", response_model=TrashListOut)
    def list_trash(
        request: Request,
        conn: sqlite3.Connection = Depends(get_db),
    ):
        if not _admin_authed(request):
            raise HTTPException(status_code=403, detail="admin only")

        rows = conn.execute(
            "SELECT id, created_at, barcode_count, event_count, batch_count "
            "FROM trash_batches ORDER BY created_at DESC, rowid DESC"
        ).fetchall()
        return TrashListOut(
            total=len(rows),
            items=[TrashItem(**dict(r)) for r in rows],
        )

    @app.post("/v1/trash/{trash_id}/restore", response_model=RestoreOut)
    def restore_trash(
        trash_id: str,
        request: Request,
        conn: sqlite3.Connection = Depends(get_db),
    ):
        if not _admin_authed(request):
            raise HTTPException(status_code=403, detail="admin only")

        try:
            with conn:
                result = restore_from_trash(conn, trash_id)
        except HTTPException:
            raise
        except sqlite3.Error as exc:
            logger.exception("恢复回收站失败 %s", trash_id)
            raise HTTPException(status_code=500, detail=f"restore failed: {exc}")

        if result is None:
            raise HTTPException(status_code=404, detail="trash item not found")

        logger.warning("管理员恢复了一批条码：%s", result)
        return RestoreOut(ok=True, **result)

    @app.delete("/v1/trash/{trash_id}")
    def delete_trash_item(
        trash_id: str,
        request: Request,
        conn: sqlite3.Connection = Depends(get_db),
    ):
        if not _admin_authed(request):
            raise HTTPException(status_code=403, detail="admin only")

        with conn:
            cur = conn.execute("DELETE FROM trash_batches WHERE id = ?", (trash_id,))
        if cur.rowcount == 0:
            raise HTTPException(status_code=404, detail="trash item not found")
        return {"ok": True, "deleted": 1}

    @app.delete("/v1/trash")
    def clear_trash(
        request: Request,
        conn: sqlite3.Connection = Depends(get_db),
    ):
        if not _admin_authed(request):
            raise HTTPException(status_code=403, detail="admin only")

        with conn:
            cur = conn.execute("DELETE FROM trash_batches")
        logger.warning("管理员清空了回收站：%s 批", cur.rowcount)
        return {"ok": True, "deleted": max(0, cur.rowcount)}

    @app.get("/v1/stats", response_model=StatsOut)
    def stats(
        device=Depends(require_read_access),
        conn: sqlite3.Connection = Depends(get_db),
    ):
        barcodes = conn.execute("SELECT COUNT(*) AS c FROM barcodes").fetchone()["c"]
        events = conn.execute("SELECT COUNT(*) AS c FROM scan_events").fetchone()["c"]
        devices = conn.execute(
            "SELECT COUNT(*) AS c FROM devices WHERE revoked_at IS NULL"
        ).fetchone()["c"]
        last = conn.execute("SELECT MAX(received_at) AS t FROM scan_events").fetchone()["t"]
        return StatsOut(
            barcodes=int(barcodes),
            events=int(events),
            devices=int(devices),
            last_received_at=last,
        )

    return app


app = create_app()
