# MultiScan 本地联调服务（DEV ONLY）

`tools/dev-server/server.py` 用 Python 标准库（无第三方依赖）复刻了
[`docs/API.md`](../../docs/API.md) 的 v1 契约，供 App 在模拟器/真机上做
**注册 → 上传批次 → 合并表查询 → CSV 导出** 的端到端联调。

> ⚠️ 它不是仓库里的 `server/`，也不修改/替代服务端实现；仅用于本地验收与调试。

## 启动

```powershell
# Python 3.9+
python tools/dev-server/server.py --port 8000 --enroll-secret change-me

# 可选参数
#   --host 0.0.0.0              监听地址（默认 0.0.0.0）
#   --enroll-secret <secret>    注册口令（默认环境变量 ENROLL_SECRET 或 change-me）
#   --data <path.json>          进程内数据持久化到 JSON（默认纯内存）
#   --reset                     启动前清空持久化文件
```

默认地址：

- 管理页： <http://127.0.0.1:8000/admin>
- 健康检查：<http://127.0.0.1:8000/health>

## 让 App（模拟器）连上本机服务

```powershell
# 1) 端口反向映射：App 里的 127.0.0.1:8000 -> 本机 8000
adb reverse tcp:8000 tcp:8000

# 2) App 设置页填：http://127.0.0.1:8000，注册口令 change-me
#    （默认值 http://10.0.2.2:8000 适用于标准 Android 模拟器；MuMu/真机用上面的 reverse 方案更稳）
```

## 端点

与 `docs/API.md` 完全一致：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | `{ok, version, server_time}` |
| POST | `/v1/devices/register` | `{name, enroll_secret}` → `{device_id, token, server_time}`；口令错 403 |
| POST | `/v1/scan-batches` | Bearer token；`{batch_id, app_version, captured_at, items[]}`；批量 1–500，超限 413，字段非法 422，token 失效 401；**同 `batch_id` 重传幂等**（直接重放首次响应） |
| GET | `/v1/barcodes?q=&limit=&offset=` | 合并表查询（服务端按 code 合并，`scan_count` 累计、`device_count` 去重） |
| GET | `/v1/barcodes/export.csv?q=` | UTF-8 带 BOM 的 CSV，Excel 直接打开不乱码 |
| GET | `/v1/stats` | `{barcodes, events, devices, last_received_at}` |

附加（仅 dev）：`GET /admin` 合并表可视化页面、`GET /v1/devices` 已注册设备列表。

## 快速自测（PowerShell）

```powershell
$reg = Invoke-RestMethod -Method Post http://127.0.0.1:8000/v1/devices/register `
  -ContentType application/json -Body '{"name":"dev-pc","enroll_secret":"change-me"}'
$h = @{ Authorization = "Bearer $($reg.token)" }
Invoke-RestMethod -Method Post http://127.0.0.1:8000/v1/scan-batches -Headers $h `
  -ContentType application/json -Body (@{
    batch_id = "11111111-2222-3333-4444-555555555555"
    app_version = "0.1.0"; captured_at = "2026-10-23T05:00:00Z"
    items = @(@{ item_id = "a"; raw_text = "0123456789"; format = "Code128"; line_count = 7 })
  } | ConvertTo-Json -Depth 5)
Invoke-RestMethod "http://127.0.0.1:8000/v1/barcodes?q=0123" | ConvertTo-Json -Depth 5
Invoke-WebRequest "http://127.0.0.1:8000/admin" -UseBasicParsing | Select-Object StatusCode
```
