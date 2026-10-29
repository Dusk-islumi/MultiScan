#!/usr/bin/env python3
"""设备管理：查看、吊销、恢复、轮换 token。

用法：
  python3 server/deploy/manage_devices.py list
  python3 server/deploy/manage_devices.py revoke dev_xxxxxxxx
  python3 server/deploy/manage_devices.py unrevoke dev_xxxxxxxx
  python3 server/deploy/manage_devices.py rotate dev_xxxxxxxx
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app import config  # noqa: E402
from app.auth import hash_token, new_token, utcnow  # noqa: E402
from app.credentials import CredentialLedger  # noqa: E402
from app.db import Store  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--db", default=os.getenv("DB_PATH", str(config.DEFAULT_DB_PATH))
    )
    ap.add_argument(
        "--credentials",
        default=os.getenv(
            "CREDENTIALS_PATH", str(Path(config.DEFAULT_DB_PATH).parent / "credentials.txt")
        ),
    )
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("list", help="列出设备")
    for name, help_text in (
        ("revoke", "吊销设备 token"),
        ("unrevoke", "恢复设备"),
        ("rotate", "生成新 token（旧 token 立即失效）"),
    ):
        p = sub.add_parser(name, help=help_text)
        p.add_argument("device_id")

    args = ap.parse_args()

    store = Store(args.db)
    store.init()
    conn = store.connect()

    if args.cmd == "list":
        rows = conn.execute(
            """
            SELECT d.id, d.name, d.created_at, d.last_seen_at, d.revoked_at,
                   (SELECT COUNT(*) FROM scan_events e WHERE e.device_id = d.id) AS events
            FROM devices d
            ORDER BY d.created_at
            """
        ).fetchall()
        if not rows:
            print("(无设备)")
            return 0
        print(f"{'DEVICE_ID':<22} {'状态':<6} {'扫描事件':>8}  {'名称':<24} {'创建时间':<20} 最后活跃")
        for r in rows:
            state = "已吊销" if r["revoked_at"] else "正常"
            print(
                f"{r['id']:<22} {state:<6} {r['events']:>8}  {r['name']:<24} "
                f"{r['created_at']:<20} {r['last_seen_at'] or '-'}"
            )
        print("\n备注：token 原文只在 data/credentials.txt 里。")
        return 0

    row = conn.execute(
        "SELECT id, name FROM devices WHERE id = ?", (args.device_id,)
    ).fetchone()
    if row is None:
        print(f"设备不存在：{args.device_id}", file=sys.stderr)
        return 1

    if args.cmd == "revoke":
        with conn:
            conn.execute(
                "UPDATE devices SET revoked_at = ? WHERE id = ?",
                (utcnow(), args.device_id),
            )
        print(f"已吊销 {args.device_id}（{row['name']}）")

    elif args.cmd == "unrevoke":
        with conn:
            conn.execute(
                "UPDATE devices SET revoked_at = NULL WHERE id = ?", (args.device_id,)
            )
        print(f"已恢复 {args.device_id}（{row['name']}）")

    elif args.cmd == "rotate":
        token = new_token()
        with conn:
            conn.execute(
                "UPDATE devices SET token_hash = ?, revoked_at = NULL WHERE id = ?",
                (hash_token(token), args.device_id),
            )
        CredentialLedger(args.credentials).record_device(
            args.device_id, row["name"], token, utcnow()
        )
        print(f"已轮换 {args.device_id}（{row['name']}）")
        print(f"新 token：{token}")
        print("旧 token 已立即失效；请把新 token 填回 App。")

    return 0


if __name__ == "__main__":
    sys.exit(main())
