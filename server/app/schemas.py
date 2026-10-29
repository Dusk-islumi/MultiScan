from __future__ import annotations

from datetime import datetime
from typing import Optional

from pydantic import AliasChoices, BaseModel, Field


class RegisterIn(BaseModel):
    name: str = Field(min_length=1, max_length=64)
    enroll_secret: str = Field(min_length=1)


class AdminLoginIn(BaseModel):
    token: str = Field(min_length=1, max_length=256)


class RegisterOut(BaseModel):
    device_id: str
    token: str
    server_time: datetime


class ItemIn(BaseModel):
    # 兼容客户端可能的字段名
    raw_text: str = Field(
        min_length=1,
        max_length=512,
        validation_alias=AliasChoices("raw_text", "text", "code"),
    )
    item_id: Optional[str] = Field(default=None, max_length=64)
    format: Optional[str] = Field(default=None, max_length=32)
    symbology_id: Optional[str] = Field(default=None, max_length=16)
    line_count: Optional[int] = Field(default=None, ge=0, le=255)


class BatchIn(BaseModel):
    batch_id: str = Field(min_length=8, max_length=64)
    app_version: Optional[str] = Field(default=None, max_length=32)
    captured_at: Optional[datetime] = None
    items: list[ItemIn] = Field(min_length=1, max_length=500)


class BatchOut(BaseModel):
    batch_id: str
    accepted: int
    duplicated: int
    barcodes_total: int


class BarcodeOut(BaseModel):
    code: str
    first_seen_at: str
    last_seen_at: str
    scan_count: int
    device_count: int


class BarcodePage(BaseModel):
    total: int
    limit: int
    offset: int
    items: list[BarcodeOut]


class StatsOut(BaseModel):
    barcodes: int
    events: int
    devices: int
    last_received_at: Optional[str] = None


# 清除操作必须原样提交这个短语，防止误触
CLEAR_CONFIRM_PHRASE = "清除全部条码"


class ClearIn(BaseModel):
    confirm: str = Field(min_length=1, max_length=64)


class ClearOut(BaseModel):
    ok: bool
    deleted_barcodes: int
    deleted_events: int
    deleted_batches: int
    backup_path: Optional[str] = None
    # 存入回收站后的快照 id；可据此一键恢复
    trash_id: Optional[str] = None


class TrashItem(BaseModel):
    id: str
    created_at: str
    barcode_count: int
    event_count: int
    batch_count: int


class TrashListOut(BaseModel):
    total: int
    items: list[TrashItem]


class RestoreOut(BaseModel):
    ok: bool
    trash_id: str
    restored_barcodes: int
    restored_events: int
    restored_batches: int
