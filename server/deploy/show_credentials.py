#!/usr/bin/env python3
"""格式化查看 data/credentials.txt（注册口令 + 设备 token）。"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app import config  # noqa: E402
from app.credentials import parse_credentials  # noqa: E402


def main() -> int:
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else config.DEFAULT_CREDENTIALS_PATH
    if not path.exists():
        print(f"凭据文件不存在：{path}")
        return 1

    lines = path.read_text(encoding="utf-8").splitlines()
    enroll = None
    for line in reversed(lines):
        if " [enroll] " in line:
            for part in line.split():
                if part.startswith("secret="):
                    enroll = part[7:]

    print(f"文件：{path}\n")
    print("注册口令（ENROLL_SECRET）")
    print(f"  {enroll or '(未记录)'}\n")

    devices = parse_credentials(path)
    print(f"设备 token（共 {len(devices)} 台）")
    if not devices:
        print("  (无)")
        return 0
    print(f"  {'DEVICE_ID':<22} {'TOKEN':<44} 名称")
    for d in devices:
        print(f"  {d['device_id']:<22} {d['token']:<44} {d.get('name', '')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
