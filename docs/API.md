# API 约定 v1

Base URL：`https://YOUR_SERVER_IP`（本地调试 `http://127.0.0.1:18080`）  
生产环境直接填 HTTPS，不要走 `http://`（会被 308 跳转，多一跳明文）。

## 鉴权：只需要一个「授权码」

**最简单的方式**：客户端只填一个授权码（就是 `ENROLL_SECRET`），所有接口都用它。
本仓库的 MultiScan App 采用第 2 节的设备注册流程获取独立 token；其他客户端可直接用授权码：

```http
Authorization: Bearer <授权码>
```

- 授权码在服务器上查看：`cat server/data/enroll_secret.txt`（`run.sh` 自动生成），或 `.env` 中的 `ENROLL_SECRET=`
- **不需要注册流程，也不需要保存任何 token**
- 用授权码上传的扫描，在服务端归属到内部设备 `__shared__`，不会占用"设备数"

> 设备注册（第 2 节）仍然保留：MultiScan App 使用它获取独立设备 token；
> 其他客户端也可以直接用授权码接入，无需注册、无需保存 token。

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

> 以上为契约示例值（已脱敏），真实 token 只在注册响应中出现一次。

- `token` 只在这一次返回，App 必须存进 DataStore（加密）
- 注册口令错误返回 `403`
- 服务端会把 token 明文留档到 `data/credentials.txt`（权限 600），用于运维找回
- token 被吊销或轮换后，旧 token 立即返回 `401`

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
- 同一个 `batch_id` 不能被其他设备使用，否则返回 `409`
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

## 6. 清空所有条码（管理员，危险）

```http
POST /v1/barcodes/clear
Content-Type: application/json

{ "confirm": "清除全部条码" }
```

```json
{
  "ok": true,
  "deleted_barcodes": 35,
  "deleted_events": 35,
  "deleted_batches": 6,
  "backup_path": "/path/to/data/backups/pre-clear-20261024T084923Z.db"
}
```

约定：

- **只有管理员会话可用**（控制台 Cookie 或管理员 Bearer）；设备 token / 授权码调用返回 `403`
- `confirm` 必须与 `清除全部条码` **完全一致**，否则返回 `422`
- 删除前会自动备份数据库到 `data/backups/pre-clear-<UTC时间>.db`
- 同时清空 `barcodes`、`scan_events`、`batches`；**设备与授权码不受影响**
- 清空后可以重新上传相同的 `batch_id`（批次记录也一起清了）
- 操作不可撤销，只能用备份恢复

## 7. 回收站（管理员）

「清空所有条码」时，被删的数据会整体存入回收站，可一键整批恢复。
默认最多保留最近 20 批（`MAX_TRASH_BATCHES`），超出自动丢弃最旧的。

### 列出回收站

```http
GET /v1/trash
```

```json
{
  "total": 1,
  "items": [
    {
      "id": "tr_1477544d71a49743",
      "created_at": "2026-10-24T08:55:50+00:00",
      "barcode_count": 3,
      "event_count": 3,
      "batch_count": 2
    }
  ]
}
```

### 一键恢复一批

```http
POST /v1/trash/{id}/restore
```

```json
{
  "ok": true,
  "trash_id": "tr_1477544d71a49743",
  "restored_barcodes": 3,
  "restored_events": 3,
  "restored_batches": 2
}
```

- 恢复是**合并**而非覆盖：已存在的条码取更早的 `first_seen`、更晚的 `last_seen`，
  `scan_count` 相加（清空前后的扫描事件互不重叠，相加不会重复计数）
- **恢复后该快照立即出站**，重复恢复返回 `404`，避免计数翻倍
- 快照不存在 / 已恢复 / 已删除 → `404`

### 永久删除

```http
DELETE /v1/trash/{id}     # 删除一批
DELETE /v1/trash          # 清空回收站
```

```json
{ "ok": true, "deleted": 1 }
```

> 以上接口**仅管理员会话可用**；设备 token / 授权码调用返回 `403`。

## 8. 统计

```http
GET /v1/stats
```

```json
{ "barcodes": 3, "events": 4, "devices": 2, "last_received_at": "..." }
```

## 错误码

| 状态 | 含义 | App 处理 |
|---|---|---|
| 401 | token 缺失/失效/被吊销 | 提示重新注册，不要无限重试 |
| 403 | 注册口令错 | 设置页提示 |
| 409 | `batch_id` 被其他设备占用 | 生成新 `batch_id` 重发本批 |
| 413 | 单批太大 | 拆分重发 |
| 422 | 字段不合法 | 标记失败，人工看 |
| 429 | 触发限流 | 退避后重试（读取 `Retry-After` 无则按 5s 起） |
| 5xx | 服务端错误 | 保留队列，退避重试 |
