#!/usr/bin/env python3
"""MultiScan 本地联调服务（DEV ONLY）。

用途：在本机复刻 docs/API.md 的 v1 契约，供 Android App 在模拟器/真机上做
注册、上传、合并表查询、CSV 导出的端到端联调。它**不是**仓库里的 server/，
不修改也不替代服务端；仅用于开发验收。

依赖：Python 3.9+ 标准库，无第三方包。

启动：
    python tools/dev-server/server.py --port 8000 --enroll-secret change-me
    可选：--host 0.0.0.0  --data tools/dev-server/data.json（持久化）  --reset

端点（与 docs/API.md 一致）：
    GET  /health
    POST /v1/devices/register      {name, enroll_secret}
    POST /v1/scan-batches          Bearer token, {batch_id, app_version, captured_at, items[]}
    GET  /v1/barcodes?q=&limit=&offset=
    GET  /v1/barcodes/export.csv?q=
    GET  /v1/stats
附加（仅 dev）：GET /admin 合并表可视化页面；GET / 跳转 /admin。
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import os
import secrets
import sys
import threading
import time
import uuid
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

VERSION = "1.0.0-dev"
API_VERSION = "1.0.0"


# --------------------------------------------------------------------------- #
# 数据层（进程内，可选 JSON 持久化）
# --------------------------------------------------------------------------- #
class Store:
    def __init__(self, data_path: str | None = None) -> None:
        self.lock = threading.RLock()
        self.data_path = data_path
        self.devices: dict[str, dict] = {}       # token -> {device_id, name, created_at}
        self.batches: dict[str, dict] = {}       # batch_id -> response payload（幂等重放）
        self.barcodes: dict[str, dict] = {}      # code -> {first_seen_at,last_seen_at,scan_count,devices:set}
        self.events = 0
        self.last_received_at: str | None = None
        self.started_at = self._now()
        if data_path and os.path.exists(data_path):
            self._load()

    @staticmethod
    def _now() -> str:
        return datetime.now(timezone.utc).isoformat(timespec="microseconds")

    def _load(self) -> None:
        try:
            with open(self.data_path, "r", encoding="utf-8") as fh:  # type: ignore[arg-type]
                raw = json.load(fh)
            self.devices = raw.get("devices", {})
            self.batches = raw.get("batches", {})
            self.events = raw.get("events", 0)
            self.last_received_at = raw.get("last_received_at")
            for code, rec in raw.get("barcodes", {}).items():
                rec["devices"] = set(rec.get("devices", []))
                self.barcodes[code] = rec
            self.started_at = raw.get("started_at", self.started_at)
            print(f"[store] loaded {len(self.barcodes)} barcodes from {self.data_path}")
        except Exception as exc:  # noqa: BLE001 - dev server, keep running
            print(f"[store] WARN load failed: {exc}")

    def save(self) -> None:
        if not self.data_path:
            return
        raw = {
            "devices": self.devices,
            "batches": self.batches,
            "events": self.events,
            "last_received_at": self.last_received_at,
            "started_at": self.started_at,
            "barcodes": {
                code: {**rec, "devices": sorted(rec["devices"])}
                for code, rec in self.barcodes.items()
            },
        }
        tmp = self.data_path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(raw, fh, ensure_ascii=False, indent=2)
        os.replace(tmp, self.data_path)


STORE: Store


# --------------------------------------------------------------------------- #
# HTTP 处理
# --------------------------------------------------------------------------- #
class Handler(BaseHTTPRequestHandler):
    server_version = f"MultiScanDevServer/{VERSION}"
    protocol_version = "HTTP/1.1"

    # -------- helpers -------- #
    def log_message(self, fmt: str, *args) -> None:  # noqa: A003 - stdlib signature
        ts = datetime.now().strftime("%H:%M:%S")
        sys.stdout.write(f"[{ts}] {self.address_string()} {fmt % args}\n")

    def _send(self, status: int, body: bytes, content_type: str = "application/json; charset=utf-8",
              extra_headers: dict[str, str] | None = None) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        for key, value in (extra_headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _json(self, status: int, payload: dict | list) -> None:
        self._send(status, json.dumps(payload, ensure_ascii=False).encode("utf-8"))

    def _error(self, status: int, detail: str) -> None:
        self._json(status, {"detail": detail})

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        try:
            payload = json.loads(raw.decode("utf-8")) if raw else {}
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ValueError(f"invalid JSON: {exc}") from exc
        if not isinstance(payload, dict):
            raise ValueError("JSON body must be an object")
        return payload

    def _bearer_token(self) -> str | None:
        header = self.headers.get("Authorization", "")
        if header.startswith("Bearer "):
            token = header[7:].strip()
            return token or None
        return None

    def _require_device(self) -> dict | None:
        token = self._bearer_token()
        if not token or token not in STORE.devices:
            self._error(HTTPStatus.UNAUTHORIZED, "invalid or missing token")
            return None
        return STORE.devices[token]

    # -------- routing -------- #
    def do_OPTIONS(self) -> None:  # noqa: N802
        self._send(HTTPStatus.NO_CONTENT, b"")

    def do_HEAD(self) -> None:  # noqa: N802
        self.do_GET()

    def do_GET(self) -> None:  # noqa: N802
        url = urlparse(self.path)
        path = url.path.rstrip("/") or "/"
        query = parse_qs(url.query)
        try:
            if path == "/":
                self._redirect("/admin")
            elif path == "/health":
                self._json(HTTPStatus.OK, {
                    "ok": True,
                    "version": API_VERSION,
                    "server_time": datetime.now(timezone.utc).isoformat(timespec="microseconds"),
                })
            elif path == "/v1/barcodes":
                self._list_barcodes(query)
            elif path == "/v1/barcodes/export.csv":
                self._export_csv(query)
            elif path == "/v1/stats":
                with STORE.lock:
                    self._json(HTTPStatus.OK, {
                        "barcodes": len(STORE.barcodes),
                        "events": STORE.events,
                        "devices": len(STORE.devices),
                        "last_received_at": STORE.last_received_at,
                    })
            elif path == "/admin":
                self._admin_page(query)
            elif path == "/v1/devices":
                with STORE.lock:
                    self._json(HTTPStatus.OK, {
                        "items": [
                            {"device_id": d["device_id"], "name": d["name"], "created_at": d["created_at"]}
                            for d in STORE.devices.values()
                        ]
                    })
            else:
                self._error(HTTPStatus.NOT_FOUND, f"no route for GET {path}")
        except Exception as exc:  # noqa: BLE001 - dev server must answer with a JSON error
            self.log_message("ERROR %s", exc)
            self._error(HTTPStatus.INTERNAL_SERVER_ERROR, str(exc))

    def do_POST(self) -> None:  # noqa: N802
        url = urlparse(self.path)
        path = url.path.rstrip("/") or "/"
        try:
            if path == "/v1/devices/register":
                self._register_device()
            elif path == "/v1/scan-batches":
                self._scan_batches()
            else:
                self._error(HTTPStatus.NOT_FOUND, f"no route for POST {path}")
        except ValueError as exc:
            self._error(HTTPStatus.UNPROCESSABLE_ENTITY, str(exc))
        except Exception as exc:  # noqa: BLE001
            self.log_message("ERROR %s", exc)
            self._error(HTTPStatus.INTERNAL_SERVER_ERROR, str(exc))

    def _redirect(self, location: str) -> None:
        self.send_response(HTTPStatus.FOUND)
        self.send_header("Location", location)
        self.send_header("Content-Length", "0")
        self.end_headers()

    # -------- handlers -------- #
    def _register_device(self) -> None:
        payload = self._read_json()
        name = str(payload.get("name") or "").strip()
        enroll_secret = str(payload.get("enroll_secret") or "")
        if not name:
            raise ValueError("name is required")
        if not enroll_secret:
            raise ValueError("enroll_secret is required")
        if not secrets.compare_digest(enroll_secret, ENROLL_SECRET):
            self._error(HTTPStatus.FORBIDDEN, "invalid enroll_secret")
            return
        device_id = "dev_" + secrets.token_hex(8)
        token = secrets.token_urlsafe(32)
        now = Store._now()
        with STORE.lock:
            STORE.devices[token] = {"device_id": device_id, "name": name, "created_at": now}
            STORE.save()
        self.log_message("registered device %s (%s)", device_id, name)
        self._json(HTTPStatus.CREATED, {
            "device_id": device_id,
            "token": token,
            "server_time": now,
        })

    def _scan_batches(self) -> None:
        device = self._require_device()
        if device is None:
            return
        payload = self._read_json()
        batch_id = str(payload.get("batch_id") or "").strip()
        if not batch_id:
            raise ValueError("batch_id is required")
        raw_items = payload.get("items")
        if not isinstance(raw_items, list) or not raw_items:
            raise ValueError("items must be a non-empty array")
        if len(raw_items) > 500:
            self._error(HTTPStatus.REQUEST_ENTITY_TOO_LARGE,
                        "batch too large: at most 500 items per batch")
            return
        captured_at = payload.get("captured_at")
        if captured_at is not None and not isinstance(captured_at, str):
            raise ValueError("captured_at must be an ISO-8601 string")
        app_version = str(payload.get("app_version") or "")

        with STORE.lock:
            # 幂等：同一 batch_id 重传直接重放第一次的响应
            if batch_id in STORE.batches:
                replay = dict(STORE.batches[batch_id])
                replay["replayed"] = True
                self.log_message("batch %s replay (idempotent)", batch_id)
                self._json(HTTPStatus.OK, replay)
                return

            now = Store._now()
            seen_in_batch: set[str] = set()
            accepted = 0
            duplicated = 0
            for index, item in enumerate(raw_items):
                if not isinstance(item, dict):
                    raise ValueError(f"items[{index}] must be an object")
                raw_text = item.get("raw_text")
                if not isinstance(raw_text, str) or not raw_text.strip():
                    raise ValueError(f"items[{index}].raw_text is required")
                item_id = item.get("item_id")
                if item_id is not None and not isinstance(item_id, str):
                    raise ValueError(f"items[{index}].item_id must be a string")
                code = raw_text.strip()
                if code in seen_in_batch:
                    duplicated += 1
                    continue
                seen_in_batch.add(code)
                accepted += 1
                record = STORE.barcodes.get(code)
                if record is None:
                    record = {
                        "first_seen_at": now,
                        "last_seen_at": now,
                        "scan_count": 0,
                        "devices": set(),
                    }
                    STORE.barcodes[code] = record
                record["last_seen_at"] = now
                record["scan_count"] += 1
                record["devices"].add(device["device_id"])
                STORE.events += 1
            STORE.last_received_at = now
            response = {
                "batch_id": batch_id,
                "accepted": accepted,
                "duplicated": duplicated,
                "barcodes_total": len(STORE.barcodes),
            }
            STORE.batches[batch_id] = dict(response)
            STORE.save()
        self.log_message(
            "batch %s from %s: accepted=%d duplicated=%d items=%d app=%s captured_at=%s",
            batch_id, device["device_id"], accepted, duplicated, len(raw_items), app_version or "-", captured_at or "-",
        )
        self._json(HTTPStatus.CREATED, response)

    def _list_barcodes(self, query: dict[str, list[str]]) -> None:
        q = (query.get("q") or [""])[0].strip().lower()
        try:
            limit = int((query.get("limit") or ["100"])[0])
        except ValueError:
            raise ValueError("limit must be an integer")
        try:
            offset = int((query.get("offset") or ["0"])[0])
        except ValueError:
            raise ValueError("offset must be an integer")
        limit = max(1, min(limit, 1000))
        offset = max(0, offset)
        with STORE.lock:
            items = []
            for code, rec in STORE.barcodes.items():
                if q and q not in code.lower():
                    continue
                items.append({
                    "code": code,
                    "first_seen_at": rec["first_seen_at"],
                    "last_seen_at": rec["last_seen_at"],
                    "scan_count": rec["scan_count"],
                    "device_count": len(rec["devices"]),
                })
        items.sort(key=lambda row: row["last_seen_at"], reverse=True)
        total = len(items)
        page = items[offset:offset + limit]
        self._json(HTTPStatus.OK, {
            "total": total,
            "limit": limit,
            "offset": offset,
            "items": page,
        })

    def _export_csv(self, query: dict[str, list[str]]) -> None:
        q = (query.get("q") or [""])[0].strip().lower()
        buffer = io.StringIO()
        writer = csv.writer(buffer, lineterminator="\r\n")
        writer.writerow(["barcode", "first_seen_at", "last_seen_at", "scan_count", "device_count"])
        with STORE.lock:
            rows = []
            for code, rec in STORE.barcodes.items():
                if q and q not in code.lower():
                    continue
                rows.append([
                    code, rec["first_seen_at"], rec["last_seen_at"],
                    rec["scan_count"], len(rec["devices"]),
                ])
        rows.sort(key=lambda row: row[2], reverse=True)
        writer.writerows(rows)
        payload = ("\ufeff" + buffer.getvalue()).encode("utf-8")
        self._send(HTTPStatus.OK, payload, content_type="text/csv; charset=utf-8",
                   extra_headers={"Content-Disposition": 'attachment; filename="barcodes.csv"'})

    def _admin_page(self, query: dict[str, list[str]]) -> None:
        q = (query.get("q") or [""])[0].strip().lower()
        with STORE.lock:
            barcodes = []
            for code, rec in STORE.barcodes.items():
                if q and q not in code.lower():
                    continue
                barcodes.append((code, rec["first_seen_at"], rec["last_seen_at"],
                                 rec["scan_count"], len(rec["devices"])))
            barcodes.sort(key=lambda row: row[2], reverse=True)
            devices = [(d["name"], d["device_id"], d["created_at"]) for d in STORE.devices.values()]
            stats = {
                "barcodes": len(STORE.barcodes),
                "events": STORE.events,
                "devices": len(STORE.devices),
                "last_received_at": STORE.last_received_at,
            }
        rows = "\n".join(
            f"<tr><td><code>{_html(code)}</code></td><td>{_html(first)}</td>"
            f"<td>{_html(last)}</td><td class='num'>{count}</td><td class='num'>{devs}</td></tr>"
            for code, first, last, count, devs in barcodes
        )
        device_rows = "\n".join(
            f"<tr><td>{_html(name)}</td><td><code>{_html(dev_id)}</code></td><td>{_html(created)}</td></tr>"
            for name, dev_id, created in devices
        )
        page = f"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>MultiScan dev server</title>
<style>
 body{{font-family:system-ui,-apple-system,'Segoe UI',sans-serif;margin:24px;background:#0f172a;color:#e2e8f0}}
 h1{{font-size:20px;margin:0 0 4px}} .sub{{color:#94a3b8;margin-bottom:20px;font-size:13px}}
 .cards{{display:flex;gap:12px;flex-wrap:wrap;margin-bottom:20px}}
 .card{{background:#1e293b;border:1px solid #334155;border-radius:10px;padding:12px 16px;min-width:130px}}
 .card b{{display:block;font-size:22px;margin-top:4px;color:#4ade80}}
 table{{width:100%;border-collapse:collapse;background:#1e293b;border-radius:10px;overflow:hidden;margin-bottom:20px}}
 th,td{{padding:8px 10px;text-align:left;border-bottom:1px solid #334155;font-size:13px}}
 th{{background:#273449;color:#94a3b8;font-weight:600}} td.num{{text-align:right}}
 form{{margin-bottom:12px}} input{{background:#1e293b;border:1px solid #334155;color:#e2e8f0;border-radius:8px;padding:8px 10px}}
 button{{background:#16a34a;border:0;color:#fff;border-radius:8px;padding:8px 14px;cursor:pointer}}
 .empty{{color:#64748b;padding:16px}} code{{color:#7dd3fc}}
</style></head><body>
<h1>MultiScan 本地联调服务 <span style="color:#64748b;font-size:13px">v{API_VERSION}</span></h1>
<div class="sub">DEV ONLY · 模拟 docs/API.md 契约 · 启动于 {_html(STORE.started_at)} · 最近接收 {_html(stats["last_received_at"] or "—")}</div>
<div class="cards">
 <div class="card">合并条码数<b>{stats["barcodes"]}</b></div>
 <div class="card">事件总数<b>{stats["events"]}</b></div>
 <div class="card">设备数<b>{stats["devices"]}</b></div>
</div>
<form method="get" action="/admin"><input name="q" value="{_html(q)}" placeholder="按条码过滤">
<button type="submit">过滤</button></form>
<table><thead><tr><th>条码</th><th>首次出现 (UTC)</th><th>最近出现 (UTC)</th><th>scan_count</th><th>device_count</th></tr></thead>
<tbody>{rows or '<tr><td class="empty" colspan="5">暂无条码，先在 App 里扫描并同步</td></tr>'}</tbody></table>
<h2 style="font-size:16px">已注册设备</h2>
<table><thead><tr><th>名称</th><th>device_id</th><th>注册时间</th></tr></thead>
<tbody>{device_rows or '<tr><td class="empty" colspan="3">暂无设备</td></tr>'}</tbody></table>
</body></html>"""
        self._send(HTTPStatus.OK, page.encode("utf-8"), content_type="text/html; charset=utf-8")


def _html(value: object) -> str:
    return (str(value)
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace('"', "&quot;"))


# --------------------------------------------------------------------------- #
# 入口
# --------------------------------------------------------------------------- #
ENROLL_SECRET = "change-me"


def main(argv: list[str] | None = None) -> int:
    global ENROLL_SECRET, STORE
    parser = argparse.ArgumentParser(description="MultiScan dev-only API server (docs/API.md)")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--enroll-secret", default=os.environ.get("ENROLL_SECRET", "change-me"))
    parser.add_argument("--data", default=os.environ.get("MULTISCAN_DATA"), help="JSON 持久化路径（可选）")
    parser.add_argument("--reset", action="store_true", help="清空持久化数据后启动")
    args = parser.parse_args(argv)

    ENROLL_SECRET = args.enroll_secret
    data_path = args.data
    if data_path and args.reset and os.path.exists(data_path):
        os.remove(data_path)
        print(f"[store] reset {data_path}")
    STORE = Store(data_path)

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.daemon_threads = True
    print(f"MultiScan dev server v{VERSION} listening on http://{args.host}:{args.port}")
    print(f"  enroll_secret = {ENROLL_SECRET!r}")
    print(f"  admin         = http://127.0.0.1:{args.port}/admin")
    print(f"  health        = http://127.0.0.1:{args.port}/health")
    if data_path:
        print(f"  persistence   = {data_path}")
    if args.host in ("0.0.0.0", "::"):
        print("  Android emulator: adb reverse tcp:%d tcp:%d  ->  App 里填 http://127.0.0.1:%d"
              % (args.port, args.port, args.port))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[server] bye")
    finally:
        server.server_close()
        if data_path:
            STORE.save()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
