from __future__ import annotations

import hashlib
import hmac
import ipaddress
import logging
import os
import secrets
import sqlite3
import threading
import time
from datetime import datetime, timezone
from typing import Optional

from fastapi import Header, HTTPException, Request

from . import config
from .db import Store

logger = logging.getLogger("scan_server")


def utcnow() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


_touch_cache: dict[str, float] = {}
_touch_lock = threading.Lock()


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def new_token() -> str:
    return secrets.token_urlsafe(32)


def verify_enroll_secret(provided: str, expected: str) -> bool:
    """常量时间比较，避免通过响应时间逐字符猜解注册口令。"""
    return hmac.compare_digest(
        provided.encode("utf-8", "ignore"), expected.encode("utf-8", "ignore")
    )


def create_device(conn: sqlite3.Connection, name: str) -> tuple[str, str]:
    device_id = "dev_" + secrets.token_hex(8)
    token = new_token()
    conn.execute(
        "INSERT INTO devices(id, name, token_hash, created_at) VALUES (?,?,?,?)",
        (device_id, name, hash_token(token), utcnow()),
    )
    return device_id, token


# --------------------------------------------------------------- admin token


def load_or_create_admin_token(
    explicit: Optional[str] = None, path: Optional[str | os.PathLike] = None
) -> str:
    """确定管理员口令。

    优先级：
      1. 显式传入（测试用）
      2. 环境变量 ADMIN_TOKEN
      3. 文件 authZ 里已有的口令（重启后保持不变）
      4. 都没有 -> 随机生成并写盘

    第 4 种是默认路径：服务启动时自动生成一个 256 位随机口令，
    因此不存在"默认口令"这种东西，也不需要人工配置。
    """
    if explicit:
        return explicit
    if config.ADMIN_TOKEN:
        return config.ADMIN_TOKEN

    token_path = os.fspath(path) if path else os.fspath(config.ADMIN_TOKEN_PATH)

    existing = _read_admin_token_file(token_path)
    if existing:
        return existing

    token = new_token()
    _write_admin_token_file(token_path, token)
    logger.warning(
        "已生成新的管理员口令并写入 %s（权限 600）。"
        "查看：cat %s；如需自定义请设置环境变量 ADMIN_TOKEN",
        token_path,
        token_path,
    )
    return token


def _read_admin_token_file(path: str) -> Optional[str]:
    try:
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                return line
    except FileNotFoundError:
        return None
    except OSError as exc:
        logger.error("读取管理员口令文件失败 %s: %s", path, exc)
    return None


def _write_admin_token_file(path: str, token: str) -> None:
    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, exist_ok=True)
        try:
            os.chmod(parent, 0o700)
        except OSError:
            pass

    # 先写临时文件再原子替换，避免并发启动或读到半个文件
    tmp = f"{path}.tmp.{os.getpid()}"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        os.write(
            fd,
            (
                "# 管理员控制台口令（自动生成，请勿提交到 git）\n"
                "# 用途：访问 /admin 控制台；可通过环境变量 ADMIN_TOKEN 覆盖\n"
                f"{token}\n"
            ).encode("utf-8"),
        )
    finally:
        os.close(fd)
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass


def verify_admin_token(provided: Optional[str]) -> bool:
    """常量时间比较管理员口令。"""
    if not provided:
        return False
    expected = current_admin_token()
    if not expected:
        return False
    return hmac.compare_digest(
        provided.encode("utf-8", "ignore"), expected.encode("utf-8", "ignore")
    )


# 由 create_app 写入，避免把口令挂在 app.state 之外到处传递
_admin_token: Optional[str] = None


def set_current_admin_token(token: str) -> None:
    global _admin_token
    _admin_token = token


def current_admin_token() -> Optional[str]:
    return _admin_token


# --------------------------------------------------------------- client ip

_TRUSTED_NETWORKS: list = []


def configure_trusted_proxies(networks) -> None:
    global _TRUSTED_NETWORKS
    parsed = []
    for item in networks:
        try:
            parsed.append(ipaddress.ip_network(item, strict=False))
        except ValueError:
            logger.warning("忽略无效的 TRUSTED_PROXIES 条目：%s", item)
    _TRUSTED_NETWORKS = parsed


def client_ip(request: Request) -> str:
    """取真实客户端 IP。

    X-Forwarded-For 可以伪造，只有在直连对端是可信代理时才采信，
    并且取**最右侧**由可信代理追加的那一跳，而不是最左侧（可被客户端伪造）。
    """
    peer = request.client.host if request.client else "unknown"
    if not _TRUSTED_NETWORKS:
        return peer

    try:
        peer_addr = ipaddress.ip_address(peer)
    except ValueError:
        return peer

    if not any(peer_addr in net for net in _TRUSTED_NETWORKS):
        return peer

    forwarded = request.headers.get("x-forwarded-for")
    if not forwarded:
        return peer

    hops = [h.strip() for h in forwarded.split(",") if h.strip()]
    # 从右往左跳过可信代理，第一个不可信的即真实客户端
    for hop in reversed(hops):
        try:
            hop_addr = ipaddress.ip_address(hop)
        except ValueError:
            return peer
        if not any(hop_addr in net for net in _TRUSTED_NETWORKS):
            return str(hop_addr)
    return peer


def lookup_device(conn: sqlite3.Connection, token: str) -> Optional[sqlite3.Row]:
    return conn.execute(
        "SELECT * FROM devices WHERE token_hash = ? AND revoked_at IS NULL",
        (hash_token(token),),
    ).fetchone()


def touch_device(
    conn: sqlite3.Connection, device_id: str, last_seen_at: Optional[str]
) -> None:
    """更新 last_seen_at，但不让这个非关键写入拖垮读接口。

    - 进程内缓存 + 数据库时间双重节流，60 秒内不重复写
    - 写失败（例如瞬时锁冲突）直接忽略
    """
    mono = time.monotonic()
    with _touch_lock:
        if mono - _touch_cache.get(device_id, 0.0) < config.LAST_SEEN_THROTTLE_SECONDS:
            return

    now = datetime.now(timezone.utc)
    if last_seen_at:
        try:
            prev = datetime.fromisoformat(last_seen_at)
            if prev.tzinfo is None:
                prev = prev.replace(tzinfo=timezone.utc)
            if (now - prev).total_seconds() < config.LAST_SEEN_THROTTLE_SECONDS:
                with _touch_lock:
                    _touch_cache[device_id] = mono
                return
        except (TypeError, ValueError):
            pass

    try:
        conn.execute(
            "UPDATE devices SET last_seen_at = ? WHERE id = ?",
            (utcnow(), device_id),
        )
        conn.commit()
    except sqlite3.Error:
        return

    with _touch_lock:
        _touch_cache[device_id] = mono


def current_device(
    request: Request,
    authorization: Optional[str] = Header(default=None),
) -> sqlite3.Row:
    """FastAPI dependency: Bearer token -> device row.

    支持两种凭据，二选一即可：

    1. **统一授权码**（推荐，客户端只需这一个）——即 ENROLL_SECRET。
       用它访问时，服务端自动归属到一台内部设备 ``__shared__``，
       这样客户端不需要注册流程、也不需要保存任何 token。
    2. **设备 token**——早期已注册的 PDA/App 继续可用，不破坏兼容性。
       设备被吊销则立即 401。
    """
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail="missing bearer token")
    token = authorization.split(" ", 1)[1].strip()
    if not token:
        raise HTTPException(status_code=401, detail="empty token")

    store: Store = request.app.state.store

    # ---- 1) 统一授权码：优先判断，命中即用共享设备身份 ----
    shared_secret = getattr(request.app.state, "enroll_secret", None)
    if shared_secret and hmac.compare_digest(
        token.encode("utf-8", "ignore"), shared_secret.encode("utf-8", "ignore")
    ):
        conn = store.connect()
        try:
            row = ensure_shared_device(conn)
        finally:
            conn.close()
        setattr(request.state, "auth_mode", "shared")
        return row

    # ---- 2) 回退到设备 token ----
    conn = store.connect()
    try:
        row = lookup_device(conn, token)
        if row is None:
            raise HTTPException(status_code=401, detail="invalid token")
        touch_device(conn, row["id"], row["last_seen_at"])
        setattr(request.state, "auth_mode", "device")
        return row
    finally:
        conn.close()


SHARED_DEVICE_ID = "__shared__"


def ensure_shared_device(conn: sqlite3.Connection) -> sqlite3.Row:
    """确保共享设备记录存在，并返回它。

    用固定 id 而不是新建随机设备，保证：
    - 反复使用授权码不会产生一堆重复设备
    - 统计里的"设备数"不会被撑大
    """
    row = conn.execute(
        "SELECT * FROM devices WHERE id = ?", (SHARED_DEVICE_ID,)
    ).fetchone()
    if row is not None:
        if row["revoked_at"] is not None:
            raise HTTPException(
                status_code=403, detail="shared access via enroll secret is revoked"
            )
        return row

    conn.execute(
        "INSERT OR IGNORE INTO devices(id, name, token_hash, created_at) VALUES (?,?,?,?)",
        (
            SHARED_DEVICE_ID,
            "统一授权码",
            # 该行的 token_hash 不对应任何可用 token：它是授权码的哈希，
            # 而授权码走上面的专用分支，不会被 lookup_device 命中。
            hash_token("shared-enroll-secret-device"),
            utcnow(),
        ),
    )
    conn.commit()
    return conn.execute(
        "SELECT * FROM devices WHERE id = ?", (SHARED_DEVICE_ID,)
    ).fetchone()
