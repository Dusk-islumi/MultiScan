# MultiScan — 多条形码实时扫描与同步

一套完整的条码采集方案：Android App 用相机实时识别画面中的多个 **Code 128**，本地去重入库后
通过 WorkManager 单向同步到 **FastAPI + SQLite** 服务端；服务端跨设备合并条码，提供网页控制台
查看汇总、导出 CSV，并支持一键清空与回收站恢复。

| 端 | 技术栈 |
|---|---|
| Android | Kotlin 2.3 · Jetpack Compose Material 3 · CameraX · zxing-cpp · Room · WorkManager · DataStore |
| Server | Python 3.10+ · FastAPI · SQLite · 服务端渲染控制台（无前端构建链） |
| 构建 / CI | JDK 17 · Android SDK 36；GitHub Actions（Android + Python 3.10 / 3.11 / 3.12） |

> 这是**原版完整方案**（Android App + 同步服务端）。
> 如果只需要「手机当多码扫码枪，电脑自动逐条输入」，见变体仓库
> [MultiScan-PC](https://github.com/Dusk-islumi/MultiScan-PC)。

## 功能

- **多码实时识别**：同一画面可同时识别多个 Code 128，绿色锚点表示本次首次入库，灰色表示本会话已扫过。
- **本地优先**：扫描结果先写入 Room，断网也能继续扫；每批结束后可自动或手动同步。
- **单向同步**：只上传本地记录，不会从服务器下载、覆盖或删除本地数据。
- **幂等合并**：同一 `batch_id` 重传不会重复入库；同一条码被多台设备扫到时累计 `scan_count` 与设备数。
- **网页控制台**：`/admin` 查看汇总、搜索、导出带 BOM 的 CSV（Excel 直接打开）。
- **安全默认**：设备 token 只存 SHA-256；注册口令常量时间比较；按 IP 防爆破限速；CSV 公式注入防护。
- **开箱部署**：附 systemd + Nginx + Let's Encrypt IP 证书配置，以及一键清空 / 回收站 / 数据库备份。

## 仓库结构

```
MultiScan/
├── app/                  Android 客户端（独立 Gradle 工程，详见 app/README.md）
├── server/               FastAPI + SQLite 同步服务（详见 server/README.md）
├── docs/
│   ├── API.md            客户端 / 服务端接口契约
│   ├── ANDROID_APP_PROMPT.md
│   ├── CONTRACT.md       App 内部实现契约
│   ├── REUSING.md        复用 / 改包名 / 换码制指南
│   └── WINDOWS_NOTES.md  Windows 中文路径构建说明
├── tools/
│   ├── dev-server/       本地联调用的契约复刻（DEV ONLY）
│   └── verify/           服务端验收脚本
├── releases/             预编译 APK 与服务端源码归档
├── .github/workflows/    android.yml + server.yml
├── LICENSE               MIT
└── README.md
```

## 快速开始

### 1. 启动同步服务

要求 Python 3.10+。以下命令在**仓库根目录**执行（Windows 建议用 Git Bash）：

```bash
cd server
python3 -m venv ../.venv
../.venv/bin/pip install -r requirements.txt
HOST=0.0.0.0 bash run.sh       # 局域网内手机需要访问时用 0.0.0.0
```

`run.sh` 会自动生成注册口令和管理员口令：

```bash
cat server/data/enroll_secret.txt   # App 设置页要填的「注册口令」
cat server/data/admin_token.txt     # 打开 http://电脑IP:18080/admin 时要填
```

> 默认只监听 `127.0.0.1:18080`（仅本机）。让手机访问时使用 `HOST=0.0.0.0`，
> 并在 Windows 防火墙放行 18080。公网部署请按 [server/README.md](server/README.md) 配置 Nginx + HTTPS。
> Windows 下也可以 `pip install -e .` 后设置 `ENROLL_SECRET` 再运行 `barcode-sync`，详见服务端 README。

### 2. 安装 Android App

直接安装预编译包：

```bash
adb install -r releases/MultiScan-v3-debug.apk
```

或从源码构建（要求 JDK 17 + Android SDK 36）：

```bash
cd app
./gradlew assembleDebug          # Windows: .\gradlew.bat assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk（相对仓库根目录）
```

### 3. 连接服务并开始扫描

1. 打开 App → 设置页，填写服务器地址：
   - 局域网：`http://电脑局域网IP:18080`
   - 生产：`https://YOUR_SERVER_IP`（Nginx + Let's Encrypt）
2. 填写 `server/data/enroll_secret.txt` 中的注册口令 →「测试连通性」→「注册本机」。
3. 回首页开始扫描：绿色锚点 = 首次入库，灰色 = 本次已扫过；结束后进入确认页。
4. 在设置页或首页手动同步，或保持「自动同步」开启。记录页可查看同步状态、重试失败批次。

## 发布物

| 文件 | 说明 |
|---|---|
| [`releases/MultiScan-v3-debug.apk`](releases/MultiScan-v3-debug.apk) | 最新 Android 调试包（v3，debug 签名，可直接安装） |
| [`releases/history/MultiScan-v2-debug.apk`](releases/history/MultiScan-v2-debug.apk) | 历史版本 v2 归档 |
| [`releases/barcode-sync-server-20261025.zip`](releases/barcode-sync-server-20261025.zip) | 服务端源码归档（2026-10-25） |

## 文档

- [app/README.md](app/README.md)：Android 端的构建、使用、测试与识别率调优记录
- [server/README.md](server/README.md)：服务端的接口、环境变量、部署与运维
- [docs/API.md](docs/API.md)：客户端 / 服务端接口契约
- [docs/CONTRACT.md](docs/CONTRACT.md)：App 内部模块边界与同步语义
- [docs/REUSING.md](docs/REUSING.md)：复用、改包名、换码制指南
- [docs/WINDOWS_NOTES.md](docs/WINDOWS_NOTES.md)：Windows 中文路径构建问题与修复
- [app/CHANGELOG.md](app/CHANGELOG.md)：App 迭代记录
- [app/THIRD-PARTY-NOTICES.md](app/THIRD-PARTY-NOTICES.md)：Android 第三方依赖许可

## 测试与 CI

- Android：`cd app && ./gradlew assembleDebug testDebugUnitTest lintDebug`；当前 JVM 单测 **81 项全绿**，lint 0 error。
- Server：`cd server && ../.venv/bin/python -m pytest -q`；覆盖并发注册/上传、跨设备合并、幂等重放、鉴权/限速、CSV 注入等 51 项。
- GitHub Actions：`.github/workflows/android.yml` 与 `.github/workflows/server.yml`。

## 安全说明

- `server/data/`、`server/.env`、`local.properties`、签名文件均已加入 `.gitignore`，不要提交。
- 生产环境必须使用 HTTPS；默认只监听回环地址，公网入口走 Nginx。
- App 的设备 token 只以 Android Keystore 加密后的密文保存，界面与日志只显示打码值。
- `releases/` 中的 APK 为 **debug 签名**，仅用于测试与内部使用，正式分发请自行配置 release 签名。

## 相关仓库

| 仓库 | 场景 |
|---|---|
| **MultiScan**（本仓库） | 相机多码采集 + 服务端汇总 / CSV 导出 |
| [MultiScan-PC](https://github.com/Dusk-islumi/MultiScan-PC) | 手机当多码扫码枪 + Windows 托盘接收器，自动逐条键盘输入 |

## 许可证

[MIT](LICENSE) © 2026 MultiScan contributors / health-server contributors
