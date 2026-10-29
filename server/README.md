# MultiScan Server — 多设备条码同步服务

本目录是 [MultiScan](../README.md) 原版仓库的 FastAPI 服务端：手机/扫码枪扫到的条码批量上传，
服务端自动去重合并，可在网页控制台查看汇总并导出 CSV。
配套 Android 客户端见 [`../app/`](../app/README.md)。

```
server/                          FastAPI + SQLite 同步服务（本目录）
../app/                          Android 客户端
../docs/API.md                   客户端 / 服务端接口约定
../docs/ANDROID_APP_PROMPT.md    Android 客户端开发提示词
```

## 特性

- **统一授权码**：客户端只填一个字符串就能读写，无需注册流程、无需保存 token
- **幂等上传**：同一 `batch_id` 重传不会重复入库，断网重发安全
- **跨设备合并**：同一条码被多台设备扫到时自动累计次数与设备数
- **网页控制台**：`/admin` 查看汇总、搜索、导出 CSV（管理员口令启动时自动生成）
- **一键清空 + 回收站**：清空需手动输入确认短语，数据整体入回收站可一键整批恢复，
  同时自动备份数据库；恢复采用合并策略，不会覆盖清空后的新数据
- **安全默认**：设备 token 只存 SHA-256、常量时间比较、按 IP 防爆破限速、CSV 公式注入防护
- **开箱部署**：附 systemd + Nginx + Let's Encrypt IP 证书的完整配置

## 快速开始

以下命令在**仓库根目录**执行。

```bash
cd server

# 方式一：pip 安装（推荐）
pip install -e .
barcode-sync                      # 默认 127.0.0.1:18080

# 方式二：直接跑，不需要安装
python3 -m venv ../.venv && ../.venv/bin/pip install -r requirements.txt
bash run.sh                       # 口令会自动生成
```

启动后控制台会提示管理员口令位置：

```bash
cat data/admin_token.txt          # 第 3 行；用它登录 http://127.0.0.1:18080/admin
cat data/enroll_secret.txt        # 这是客户端要填的授权码
```

```bash
# 健康检查
curl http://127.0.0.1:18080/health

# 上传一批扫描结果（<授权码> 就是上面的 ENROLL_SECRET）
curl -X POST http://127.0.0.1:18080/v1/scan-batches \
  -H "Authorization: Bearer <授权码>" \
  -H 'Content-Type: application/json' \
  -d '{"batch_id":"batch-0001","items":[{"raw_text":"123456"},{"raw_text":"654321"}]}'

# 查询合并表
curl "http://127.0.0.1:18080/v1/barcodes?q=123" -H "Authorization: Bearer <授权码>"

# 导出 CSV（带 BOM，Excel 直接打开）
curl -o barcodes.csv "http://127.0.0.1:18080/v1/barcodes/export.csv" \
  -H "Authorization: Bearer <授权码>"
```

> 早期版本需要先调 `/v1/devices/register` 注册设备拿 token。
> 那个接口仍然保留（兼容老客户端），但新客户端**不需要**了，直接用授权码即可。
>
> `/docs` 默认关闭（`ENABLE_DOCS=1` 可临时打开）。接口契约以 [`../docs/API.md`](../docs/API.md) 为准。

## 端口约定

| 端口 | 用途 |
|---|---|
| `18080` | uvicorn 内部端口，**只绑 127.0.0.1** |
| `80` | 仅用于 ACME 续期 + 308 跳转 HTTPS |
| `443` | Nginx 公网入口，App 填 `https://YOUR_SERVER_IP` |

不要用 60000 以上的高位端口：Linux 临时端口范围是 `32768–60999`，会和出站连接抢端口。

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `DB_PATH` | `server/data/scan.db` | SQLite 文件路径 |
| `CREDENTIALS_PATH` | `server/data/credentials.txt` | 设备 token / 注册口令明文留档 |
| `ENROLL_SECRET` | 无（`run.sh` 自动生成） | 设备注册口令；设为 `change-me` 会**拒绝启动** |
| `ADMIN_TOKEN` | 无（启动时自动生成） | 控制台口令；不设则自动生成到 `data/admin_token.txt` |
| `ADMIN_TOKEN_PATH` | `server/data/admin_token.txt` | 自动生成的控制台口令存放位置 |
| `STRICT_SECRETS` | `1` | 为 `1` 时拒绝使用默认注册口令启动（测试才设 `0`） |
| `TRUSTED_PROXIES` | `127.0.0.1,::1` | 可信反向代理网段，决定是否采信 `X-Forwarded-For` |
| `ENROLL_MAX_FAILURES` | `5` | 同一 IP 注册失败达到该次数即锁定 |
| `ENROLL_LOCKOUT_SECONDS` | `300` | 触发后的锁定时长（秒） |
| `ENROLL_FAILURE_WINDOW_SECONDS` | `900` | 失败计数的时间窗口 |
| `MAX_BATCH_ITEMS` | `500` | 单批最大条码数 |
| `ENABLE_DOCS` | `0` | 是否开放 `/docs`、`/openapi.json` |
| `LAST_SEEN_THROTTLE_SECONDS` | `60` | 设备活跃时间写库节流 |
| `MAX_CLOCK_SKEW_SECONDS` | `300` | 客户端时间允许超前的秒数 |

## 管理员控制台口令（自动生成）

控制台 `/admin` 需要管理员口令。**不需要手工配置**：

- 启动时若没有 `ADMIN_TOKEN` 环境变量，服务会自动生成一个
  `secrets.token_urlsafe(32)`（256 位随机）口令，写入
  `server/data/admin_token.txt`（权限 `600`，所在目录 `700`）
- 重启不会重新生成——只要文件还在，口令保持不变
- 查看口令：

```bash
cat server/data/admin_token.txt      # 第二行起是口令（# 开头是注释）
```

登录后使用 HttpOnly + Secure + SameSite=Strict 的 Cookie 维持会话，
前端不接触也不存储任何 token。想固定口令就设 `ADMIN_TOKEN` 环境变量
（它的优先级高于文件）。

> 忘记口令 = 删除 `data/admin_token.txt` 后重启，会生成新的。

## 凭据文件（重要）

注册成功后，服务端把以下内容追加写入 `server/data/credentials.txt`（权限 `600`）：

- 注册口令（`ENROLL_SECRET`）
- `device_id`、设备名、**token 明文**、创建时间

数据库里只存 token 的 SHA-256，所以这个文件是 token 唯一可找回的地方。

```bash
# 格式化查看
python3 server/deploy/show_credentials.py

# 或直接看
cat server/data/credentials.txt
```

> 泄露该文件 = 泄露所有设备权限。不要提交到 git（`server/data/` 已忽略），
> 备份时和 `scan.db` 一起加密保管。

## 设备管理

```bash
python3 server/deploy/manage_devices.py list
python3 server/deploy/manage_devices.py revoke dev_xxxxxxxx   # 吊销（token 立即失效）
python3 server/deploy/manage_devices.py unrevoke dev_xxxxxxxx # 恢复
python3 server/deploy/manage_devices.py rotate dev_xxxxxxxx   # 换新 token，旧 token 立即失效
```

## 测试

```bash
cd server && ../.venv/bin/python -m pytest -q
```

多设备并发 / 一致性测试（建议对着临时实例跑）：

```bash
# 先起一个临时实例
cd server
DB_PATH=/tmp/scan-test.db ENROLL_SECRET=test-secret CREDENTIALS_PATH=/tmp/scan-creds.txt \
  ../.venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 18081 &

# 12 台设备 × 8 批 × 20 码，32 并发
../.venv/bin/python tests/load_multi_device.py \
  --base http://127.0.0.1:18081 --secret test-secret \
  --devices 12 --batches 8 --per-batch 20 --concurrency 32
```

覆盖：并发注册/上传、跨设备同码合并、同批次并发重放幂等、batch_id 抢占、
错误 token/口令、超大 batch、分页、CSV 导出、stats 一致性。

## 部署（systemd + Nginx + HTTPS）

公网地址：`https://YOUR_SERVER_IP`

### 为什么是 IP 证书

腾讯云会对「解析到大陆 IP 但未备案」的域名做拦截（HTTP 返回 302 到
`dnspod.qcloud.com/webblock`，状态 566）。`sslip.io` 这类免费域名也会被拦。

Let's Encrypt 从 2026 年起支持 **IP 地址证书**，所以直接给公网 IP 上 HTTPS，
不依赖域名、不需要备案。代价是证书只有 **6 天** 有效期，必须自动续期。

### 一次性签发

```bash
# 需要新版 certbot（系统自带 2.9 不支持 IP 证书）
sudo python3 -m venv /opt/certbot
sudo /opt/certbot/bin/pip install certbot

sudo mkdir -p /var/www/certbot
sudo /opt/certbot/bin/certbot certonly --webroot -w /var/www/certbot \
  --ip-address YOUR_SERVER_IP --preferred-profile shortlived \
  --deploy-hook "systemctl reload nginx"
```

### 服务与续期

```bash
sudo cp server/deploy/scan-server.service /etc/systemd/system/
sudo cp server/deploy/nginx.conf /etc/nginx/conf.d/scan-server.conf
sudo cp server/deploy/certbot-ip.service /etc/systemd/system/
sudo cp server/deploy/certbot-ip.timer /etc/systemd/system/

sudo systemctl daemon-reload
sudo systemctl enable --now scan-server certbot-ip.timer
sudo systemctl disable --now certbot.timer   # 系统自带版本不认 IP 证书
sudo nginx -t && sudo systemctl reload nginx

# 每日备份（03:17，保留 14 天）
# 在 server/ 目录下执行（自动填入当前绝对路径）
(crontab -l 2>/dev/null; \
 echo "17 3 * * * $PWD/../.venv/bin/python $PWD/deploy/backup.py >/dev/null 2>&1") | crontab -
```

### 证书检查

```bash
sudo /opt/certbot/bin/certbot certificates
sudo /opt/certbot/bin/certbot renew --dry-run --no-random-sleep-on-renew
sudo systemctl status certbot-ip.timer
```

> 续期失败会在 6 天内导致 HTTPS 不可用，建议把
> `certbot-ip.timer` 的状态接入监控。端口 80 必须保持放行（ACME 验证用）。

### 上线检查清单

- [ ] `ENROLL_SECRET` 已改成随机强口令（默认值会导致启动失败）
- [ ] `data/admin_token.txt` 已生成且权限 `600`，口令已妥善保管
- [ ] Nginx 的 `/admin` 是否要加 IP 白名单（见 `deploy/nginx.conf` 注释）
- [ ] 安全组只开放 `22/80/443`，不要开放 `18080`
- [ ] `server/.env` 权限 `600`，`server/data/` 权限 `700`
- [ ] `curl https://YOUR_SERVER_IP/health` 返回 200
- [ ] `certbot-ip.timer` 为 active，`certbot renew --dry-run` 成功
- [ ] 备份任务存在，`data/backups/` 有当天的 db 和凭据

## 安全设计

- **设备 token**：32 字节随机串，服务端只存 SHA-256；支持吊销 / 轮换
- **注册口令**：常量时间比较，防时序猜测；错误口令返回 403
- **幂等**：`batch_id` 主键；同一批次并发重放只入库一次；`batch_id` 不能被其他设备抢占（409）
- **合并**：`barcodes` 按条码 upsert，`scan_count` / `device_count` 自动累计
- **审计**：`scan_events` 保留每次扫描（设备、批次、时间）
- **时间**：客户端时间转 UTC；明显超前的时钟按服务器时间处理
- **SQLite**：WAL + `busy_timeout`，单文件；`PRAGMA synchronous=NORMAL`
- **CSV**：防公式注入（`= + - @` 开头加 `'`）
- **HTTP 头**：`nosniff`、`X-Frame-Options: DENY`、CSP、`no-store`、HSTS
- **HTTPS**：Let's Encrypt IP 证书，TLS 1.2/1.3，HTTP 308 跳转 HTTPS
- **Nginx**：注册接口单独限速、`/admin` 单独限速、全局限流/限连接、隐藏版本号、请求体 1MB
- **管理员口令**：启动时自动生成 256 位随机串并落盘（`600`），无默认值；控制台需登录，
  会话用 HttpOnly + Secure + SameSite=Strict Cookie
- **防爆破**：注册接口与管理员登录都按来源 IP 统计失败，达到阈值锁定（默认 5 次 / 锁 300 秒），
  锁定期间即使口令正确也拒绝；成功即清零
- **代理信任**：只有当直连对端位于 `TRUSTED_PROXIES` 内才采信 `X-Forwarded-For`，
  且取最右侧不可信跳，防止伪造 IP 绕过限速
- **弱口令防护**：`ENROLL_SECRET` 为默认值时**拒绝启动**（`STRICT_SECRETS=1`）

## 清空条码与回收站

控制台右上角「清空条码」可清空全部条码、扫描事件与上传批次记录（设备与授权码保留）。

三重防护：

1. 只有管理员登录可用，设备 token / 授权码调用返回 `403`
2. 需手动输入确认短语 `清除全部条码`，前端禁用确认按钮 + 服务端二次校验
3. **数据整体存入回收站**，并额外自动备份数据库到 `data/backups/pre-clear-<UTC>.db`（权限 600）

### 回收站

每次清空都会在回收站留下**一批**快照，点「回收站」可：

| 操作 | 说明 |
|---|---|
| 恢复 | 一键把这批数据整体合并回库 |
| 永久删除 | 丢弃该快照 |
| 清空回收站 | 丢弃全部快照 |

恢复是**合并**不是覆盖：如果清空后已经扫了新码，两者会共存，
相同条码的 `scan_count` 相加、时间取并集。恢复后该快照立即从回收站移除，
因此重复点恢复不会导致计数翻倍。

回收站默认保留最近 20 批（`MAX_TRASH_BATCHES` 可调），超出自动丢弃最旧的。

极端情况下（如误清空后想回到清空前的精确状态），用文件备份恢复：

```bash
sudo cp server/data/backups/pre-clear-20261024T084923Z.db server/data/scan.db
sudo systemctl restart scan-server
```

## 已知边界

- 授权码与所有设备 token 权限相同，均可读写全部条码；控制台管理员口令只用于 `/admin` 页面
  （且只能读，不能写）
- 登录限速是**进程内**状态，重启后清零；多 worker 部署时各 worker 独立计数
- `scan_events` 会持续增长；数据量大时按 `received_at` 归档（当前规模无需处理）
- IP 证书只有 6 天，必须依赖 `certbot-ip.timer`；续期失败 HTTPS 会中断
- 公网 IP 变化后证书会失效，需要重新签发

## 部署说明

`server/deploy/` 下的文件是**模板**，部署前需替换占位符：

| 占位符 | 含义 | 出现位置 |
|---|---|---|
| `YOUR_SERVER_IP` | 你的公网 IP 或域名 | `deploy/nginx.conf` |
| `YOUR_USER` | 运行用户 | `deploy/scan-server.service` |
| `/path/to/server` | 本仓库 `server/` 的绝对路径 | `deploy/scan-server.service` |
| `/path/to/venv` | 虚拟环境绝对路径 | `deploy/scan-server.service` |

```bash
# 快速替换（在 server/ 下执行）
sed -i "s/YOUR_SERVER_IP/你的IP/g" deploy/nginx.conf
sed -e "s#YOUR_USER#$USER#g" \
    -e "s#/path/to/server#$PWD#g" \
    -e "s#/path/to/venv#$PWD/../.venv#g" \
    deploy/scan-server.service > /tmp/scan-server.service
```

`deploy/backup.py` 已自动按脚本位置推导路径，无需修改。

## 开发

```bash
cd server
pip install -e '.[dev]'
pytest -q
```

测试覆盖：并发注册/上传、跨设备合并、批次幂等重放、batch_id 抢占、
鉴权与吊销、防爆破限速、CSV 注入、时间钳制、安全响应头等 51 项。

## 许可证

[MIT](../LICENSE) —— 可自由使用、修改、商用，保留版权声明即可。

## 贡献

欢迎提 Issue / PR。提交前请确保 `pytest -q` 全绿。
涉及安全的改动请优先说明威胁模型。
