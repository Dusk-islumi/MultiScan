# API 约定 v1

Base URL：`http://<host>:8000`  
所有 `/v1/*` 接口（除注册）都需要 `Authorization: Bearer <token>`。

## 1. 健康检查

```http
GET /health
```

```json
{ "ok": true, "version": "1.0.0", "server_time": "2026-10-23T04:09:02+00:00" }
```

App 设置页「测试连通性」：能拿到 `ok: true` 就算通。

## 2. 设备注册

```http
POST /v1/devices/register
Content-Type: application/json

{ "name": "PDA-001", "enroll_secret": "change-me" }
```

```json
{
  "device_id": "dev_example00000000",
  "token": "<redacted-example-token>",
  "server_time": "2026-10-23T04:09:02.049898Z"
}
```

- `token` 只在这一次返回，App 必须存进 DataStore（加密）
- 注册口令错误返回 `403`

## 3. 上传扫描批次

```http
POST /v1/scan-batches
```

```json
{
  "batch_id": "9f1c2d3e-....",
  "app_version": "0.1.0",
  "captured_at": "2026-10-23T08:00:00+08:00",
  "items": [
    {
      "item_id": "uuid",
      "raw_text": "01234567890123",
      "format": "Code128",
      "symbology_id": "]C0",
      "line_count": 7
    }
  ]
}
```

```json
{ "batch_id": "9f1c...", "accepted": 2, "duplicated": 0, "barcodes_total": 3 }
```

约定：

- `batch_id` 由 App 生成（UUID），**重传必须用同一个**，服务端据此幂等
- 一批 1–500 条
- 同一批内重复的码只记一次，计入 `duplicated`
- `raw_text` 必填；`format` / `symbology_id` / `line_count` 选填
- `captured_at` 建议带时区；不传则用服务端时间

## 4. 查询合并表

```http
GET /v1/barcodes?q=123&limit=100&offset=0
```

```json
{
  "total": 3,
  "limit": 100,
  "offset": 0,
  "items": [
    {
      "code": "123456",
      "first_seen_at": "2026-10-23T00:00:00+00:00",
      "last_seen_at": "2026-10-23T04:09:02+00:00",
      "scan_count": 2,
      "device_count": 2
    }
  ]
}
```

## 5. 导出

```http
GET /v1/barcodes/export.csv?q=
```

列：`barcode, first_seen_at, last_seen_at, scan_count, device_count`  
UTF-8 带 BOM，Excel 直接打开不乱码。

## 6. 统计

```http
GET /v1/stats
```

```json
{ "barcodes": 3, "events": 4, "devices": 2, "last_received_at": "..." }
```

## 错误码

| 状态 | 含义 | App 处理 |
|---|---|---|
| 401 | token 缺失/失效 | 提示重新注册，不要无限重试 |
| 403 | 注册口令错 | 设置页提示 |
| 413 | 单批太大 | 拆分重发 |
| 422 | 字段不合法 | 标记失败，人工看 |
| 5xx | 服务端错误 | 保留队列，退避重试 |
