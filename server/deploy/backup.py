#!/usr/bin/env python3
"""每日备份 SQLite + 凭据文件，保留最近 14 天。

用 sqlite3 backup API，服务运行中也安全。

路径解析优先级：
  1. 环境变量 DB_PATH / CREDENTIALS_PATH
  2. 相对于本文件推导出的 server/data 目录（不依赖部署位置）

因此只要整个 server/ 目录放在任何位置都能直接用，不需要改代码。
"""

import datetime
import os
import shutil
import sqlite3
import sys
from pathlib import Path

# deploy/ -> server/ -> server/data
_SERVER_ROOT = Path(__file__).resolve().parent.parent
_DEFAULT_DATA_DIR = _SERVER_ROOT / "data"

DB_PATH = os.getenv("DB_PATH", str(_DEFAULT_DATA_DIR / "scan.db"))
CREDENTIALS = os.getenv("CREDENTIALS_PATH", str(_DEFAULT_DATA_DIR / "credentials.txt"))
DST_DIR = os.path.join(os.path.dirname(DB_PATH), "backups")
KEEP_DAYS = int(os.getenv("BACKUP_KEEP_DAYS", "14"))

if not os.path.exists(DB_PATH):
    print(f"备份跳过：数据库不存在 {DB_PATH}", file=sys.stderr)
    sys.exit(0)

os.makedirs(DST_DIR, exist_ok=True)

stamp = datetime.date.today().isoformat()
dst_db = os.path.join(DST_DIR, f"scan-{stamp}.db")
with sqlite3.connect(DB_PATH) as src, sqlite3.connect(dst_db) as out:
    src.backup(out)
os.chmod(dst_db, 0o600)

if os.path.exists(CREDENTIALS):
    dst_cred = os.path.join(DST_DIR, f"credentials-{stamp}.txt")
    shutil.copyfile(CREDENTIALS, dst_cred)
    os.chmod(dst_cred, 0o600)

cutoff = datetime.date.today() - datetime.timedelta(days=KEEP_DAYS)
for name in os.listdir(DST_DIR):
    if name.startswith("scan-") and name.endswith(".db"):
        raw = name[5:15]
    elif name.startswith("credentials-") and name.endswith(".txt"):
        raw = name[12:22]
    else:
        continue
    try:
        day = datetime.date.fromisoformat(raw)
    except ValueError:
        continue
    if day < cutoff:
        os.remove(os.path.join(DST_DIR, name))

print("backup ok:", dst_db)
