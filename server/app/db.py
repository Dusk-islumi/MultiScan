from __future__ import annotations

import sqlite3
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS devices (
    id           TEXT PRIMARY KEY,
    name         TEXT NOT NULL,
    token_hash   TEXT NOT NULL UNIQUE,
    created_at   TEXT NOT NULL,
    last_seen_at TEXT,
    revoked_at   TEXT
);

CREATE TABLE IF NOT EXISTS batches (
    id          TEXT PRIMARY KEY,
    device_id   TEXT NOT NULL REFERENCES devices(id),
    app_version TEXT,
    captured_at TEXT NOT NULL,
    received_at TEXT NOT NULL,
    item_count  INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS barcodes (
    code            TEXT PRIMARY KEY,
    first_seen_at   TEXT NOT NULL,
    last_seen_at    TEXT NOT NULL,
    scan_count      INTEGER NOT NULL DEFAULT 0,
    first_device_id TEXT NOT NULL REFERENCES devices(id)
);

CREATE TABLE IF NOT EXISTS scan_events (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    barcode       TEXT NOT NULL REFERENCES barcodes(code),
    device_id     TEXT NOT NULL REFERENCES devices(id),
    batch_id      TEXT NOT NULL REFERENCES batches(id),
    captured_at   TEXT NOT NULL,
    received_at   TEXT NOT NULL,
    format        TEXT,
    symbology_id  TEXT,
    line_count    INTEGER,
    UNIQUE (batch_id, barcode)
);

-- 回收站：每次「清空条码」把删掉的内容整体存一份快照，
-- 支持一键整批恢复。payload 是 JSON，恢复后该行即被删除（防止重复恢复导致计数翻倍）。
CREATE TABLE IF NOT EXISTS trash_batches (
    id            TEXT PRIMARY KEY,
    created_at    TEXT NOT NULL,
    barcode_count INTEGER NOT NULL DEFAULT 0,
    event_count   INTEGER NOT NULL DEFAULT 0,
    batch_count   INTEGER NOT NULL DEFAULT 0,
    payload       TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_events_device  ON scan_events(device_id, captured_at);
CREATE INDEX IF NOT EXISTS idx_events_barcode ON scan_events(barcode);
CREATE INDEX IF NOT EXISTS idx_events_batch   ON scan_events(batch_id);
CREATE INDEX IF NOT EXISTS idx_batches_device ON batches(device_id, received_at);
CREATE INDEX IF NOT EXISTS idx_barcodes_last  ON barcodes(last_seen_at DESC);
"""


class Store:
    """Thin SQLite wrapper. One connection per request, WAL mode."""

    def __init__(self, path: str | Path):
        self.path = Path(path)

    def connect(self) -> sqlite3.Connection:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        # FastAPI 的同步依赖和端点可能跑在不同线程，连接只在单请求内使用，
        # 因此关闭同线程检查是安全的。
        conn = sqlite3.connect(self.path, timeout=10, check_same_thread=False)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys=ON")
        conn.execute("PRAGMA busy_timeout=5000")
        conn.execute("PRAGMA synchronous=NORMAL")
        return conn

    def init(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as conn:
            # journal_mode 是持久化设置，只在初始化时设置一次；
            # 每个连接都设置会在并发时争抢排他锁。
            conn.execute("PRAGMA journal_mode=WAL")
            conn.executescript(SCHEMA)
            # 轻量迁移：老库补 revoked_at 字段
            cols = {row["name"] for row in conn.execute("PRAGMA table_info(devices)")}
            if "revoked_at" not in cols:
                conn.execute("ALTER TABLE devices ADD COLUMN revoked_at TEXT")
            # 数据库文件只允许属主读写
            try:
                self.path.chmod(0o600)
            except OSError:
                pass
