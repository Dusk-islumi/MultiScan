# MultiScan — 多条形码实时扫描 + 结果同步（Android）

从零实现的可编译、可运行 Android App：内置相机实时识别画面中的**多个 Code 128** 条码，
用**绿 / 灰两种视觉锚点**区分「首次扫到（已入库）」与「之前扫过（不重复入库）」，
退出扫描后本地记录、通过 WorkManager 自动/手动同步到服务器（契约见 [`docs/API.md`](docs/API.md)）。

- 包名：`com.example.multiscan`
- 语言 / 构建：Kotlin 2.3.21 · Gradle Kotlin DSL · AGP 8.13.2（稳定版）· Gradle 8.14.6
- UI：Jetpack Compose BOM 2026.06.01 + Material 3
- 相机 / 识别：CameraX 1.6.2（camera-core/camera2/lifecycle/view） + `io.github.zxing-cpp:android:3.1.1`（本地识别，无 ML Kit / 扫描 SDK）
- 存储 / 同步：Room 2.8.5（KSP 2.3.12）+ DataStore 1.3.0 + Android Keystore（token 加密）+ WorkManager 2.12.0 + Retrofit 3.0.0 / OkHttp 5.4.0 / kotlinx.serialization 1.12.0
- 最低 / 目标：minSdk 26 · targetSdk 36 · compileSdk 36 · JDK 17
- 许可证：[MIT](LICENSE)（第三方依赖声明见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)）
- CI：`assembleDebug + lintDebug + testDebugUnitTest`（[.github/workflows/android.yml](.github/workflows/android.yml)）

> 这是一个**完整的参考实现/脚手架**，不是即插即用的 SDK：Code 128 码制、服务端契约、包名与中文文案是按当前需求固定的。
> 复用到其他项目时的改动点与工作量见 [docs/REUSING.md](docs/REUSING.md)。

## 目录结构

```
OCR/
├── LICENSE                     # MIT
├── THIRD-PARTY-NOTICES.md      # 第三方依赖许可
├── docs/
│   ├── API.md                  # 服务端接口契约（只读，禁止修改）
│   ├── ANDROID_APP_PROMPT.md   # 原始需求
│   ├── CONTRACT.md             # 内部实现契约（模块边界/数据模型/同步语义）
│   ├── REUSING.md              # 复用/改包名/换码制/抽模块指南
│   └── WINDOWS_NOTES.md        # Windows 中文路径构建问题与修复
├── app/                        # 独立 Gradle 工程（root project = application）
│   ├── build.gradle.kts / settings.gradle.kts / gradle.properties
│   ├── gradle/libs.versions.toml
│   └── src/
│       ├── main/AndroidManifest.xml
│       ├── main/java/com/example/multiscan/
│       │   ├── MultiScanApplication.kt   # Application，创建 AppContainer
│       │   ├── AppContainer.kt           # 手写单例容器（不用 Hilt/Koin）
│       │   ├── MainActivity.kt           # 单 Activity + 手写 Compose 导航
│       │   ├── core/                     # Utc / DiagLog / Code128C 校验 / ScanSessionStore 契约
│       │   ├── data/                     # Room / DataStore+Keystore / Retrofit / 同步引擎
│       │   ├── scan/                     # CameraX / zxing-cpp / 跟踪 / 坐标映射 / 去重
│       │   └── ui/                       # Home / Scan / Records / Settings / Debug + 导出诊断
│       ├── main/res/                     # 126 条中文字符串、主题、自适应图标、FileProvider 路径
│       └── test/java/...                 # JVM 单元测试（坐标映射/跟踪/去重/同步/Code128C）
├── tools/
│   ├── dev-server/server.py    # DEV ONLY：复刻 docs/API.md 的本地联调服务（含 /admin、CSV）
│   └── verify/check_server.py  # 验收脚本：检查合并表/scan_count/CSV BOM//admin
└── .github/workflows/android.yml  # CI：assembleDebug + lintDebug + testDebugUnitTest
```

> 本仓库未随附 `server/`（需求描述中的服务端不在本工作区，且接口契约已固定）。
> 因此提供了 `tools/dev-server/` 作为**只读契约的复刻实现**用于本地端到端联调，详见下文；它不修改、不替代服务端。

## 构建

前置：JDK 17、Android SDK（platform 36 + build-tools 36.0.0）。Gradle 由 wrapper 提供，无需本机安装。

```powershell
cd app
# Windows: 首次运行会下载 Gradle 8.14.6（约 150MB）与依赖
.\gradlew.bat assembleDebug
# Linux/macOS: ./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

```powershell
# 单元测试 + Lint（CI 同款命令）
.\gradlew.bat testDebugUnitTest lintDebug
```

> **Windows 中文/非 ASCII 路径**：默认配置（UTF-8）在 Linux CI 与纯 ASCII 路径的 Windows 上可直接构建。
> 如果本机工作区路径含中文（如 `桌面`），JDK 17 的 `@argfile` 编码与 AGP 路径检查需要两项**本机级**覆盖，
> 具体做法见 [docs/WINDOWS_NOTES.md](docs/WINDOWS_NOTES.md)（不要改仓库默认配置）。
> 放到纯 ASCII 路径下构建则无此限制（Linux CI 也不受影响）。

安装到手机 / 模拟器：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.example.multiscan/.MainActivity
```

## 使用流程

1. **设置页**：填写服务器地址（如 `http://192.168.1.10:8000`）与注册口令（enroll secret）→ 「测试连通性」应显示服务器版本与时间 → 「注册本机」保存 `device_id` 与 token（token 加密存储、界面打码展示）。
2. **扫描页**：授予相机权限后实时识别多个 Code 128：
   - **绿框** = 本次会话首次扫到，已写入本地（`scan_items`，`code` UNIQUE）；
   - **灰框** = 之前扫过，不入库；
   - 未确认（连续 2 帧同文本）的 track 不显示框，避免误识别永久变灰；框随镜头移动不闪烁。
   - 右上角手电筒 / 完成；返回键结束会话需确认；底部显示「本次已扫 / 新扫」。
3. **记录页**：按会话分组展示时间、条码数、同步状态；单条可复制；失败批次可重试；可清空已同步记录。
4. **同步**：完成会话时在同一事务写 outbox 并触发 WorkManager 唯一任务 `sync-scans`（`NetworkType.CONNECTED`）；失败按 30s → 2min → 10min → 30min 退避；401 停止重试并提示重新注册；413 自动拆批；422 标记失败保留人工处理。
5. **调试面板**：实时 FPS、单帧解码耗时、track 数、丢帧数；锚点显示开关；「导出诊断」生成 zip（最近 200 条日志 + 设备信息 + 参数 JSON）并通过 FileProvider 分享。

## 本地联调（无需真实服务端）

```powershell
# 1) 启动 dev server（Python 3.9+ 标准库，无第三方依赖）
python tools/dev-server/server.py --port 8000 --enroll-secret change-me

# 2) 模拟器/设备连接本机 8000 端口
adb reverse tcp:8000 tcp:8000

# 3) App 设置页填 http://127.0.0.1:8000，口令 change-me，测试连通性 + 注册本机
# 4) 扫描并完成会话后，dev server 管理页查看合并表与 scan_count：
#    http://127.0.0.1:8000/admin

# 5) 验收脚本（检查条码合并 / scan_count / CSV BOM / admin）
python tools/verify/check_server.py --base http://127.0.0.1:8000 --code 0123456789 --expect-scan-count 0123456789=2
```

## 已验证结果（本机复现，2026-10-23）

构建门禁（本机执行，完整日志不入库）：

| 门禁 | 结果 |
|---|---|
| `cd app && ./gradlew assembleDebug` | ✅ 零错误；`app/build/outputs/apk/debug/app-debug.apk`（约 25.4 MB） |
| `./gradlew testDebugUnitTest` | ✅ **81/81 通过**（11 个测试类） |
| `./gradlew lintDebug` | ✅ 0 errors / 16 warnings（均为版本固定、锁定竖屏等良性提示） |
| `clean assembleDebug testDebugUnitTest lintDebug` | ✅ BUILD SUCCESSFUL (1m05s, v2) |
| APK 内容 | ✅ `lib/{arm64-v8a,armeabi-v7a,x86,x86_64}/libzxingcpp_android.so` 全部打包；manifest `usesCleartextTraffic=true` |

单元测试分布（v3）：坐标映射 11 + RotationMath 8 + TrackManager 11 + SessionCodeIndex 5 + Code128C 5 + Room/仓库去重 10 + 同步状态矩阵 10 + 退避 3 + 批次 payload 6 + 手动同步 6 + 同步调度 6 = 81。

模拟器（MuMu，Android 15 / x86_64 / 1440×2560）上的人工端到端验证（在设备 ADB 掉线前完成）：

- 安装、冷启动进入首页；首页统计卡/连接状态/入口渲染正常；
- 进入扫描页触发相机权限弹窗，授权后 `PreviewView(FILL_CENTER)` 正常绑定（系统相机 HAL 显示本 App 持有相机客户端），无崩溃；退出页面后相机客户端释放；
- 手电筒开关切换成功（系统 HAL 报告有闪光灯）；
- 调试面板读到真实数据：分析分辨率 `1280×720 (旋转 90°)`、FPS≈15、单帧解码 3–4 ms、丢帧/已分析帧计数正常；「导出诊断」生成的 zip 含 `logs.txt` + `device.json` + `params.json`（FileProvider 分享路径可用，模拟器无分享目标属环境限制）；
- 记录页空状态正常；设置页「测试连通性」对 `tools/dev-server` 返回 `连接成功 · 服务端版本 1.0.0 · 服务器时间 …`；「注册本机」成功，服务端可见新设备，App 内 device_id / 打码 token 正确显示，token 仅以加密密文落盘；
- 同步上传链路的客户端实现由 `SyncEngineStatusMatrixTest`（200/201、401 不重试、413 拆分、422、5xx 退避）与 dev server 契约脚本双重覆盖；**最后一次真机/模拟器在线联合上传验证因模拟器 ADB 通道掉线未完成**（应用与服务器两侧实现均已独立验证，见「已知限制」）。

## 同步（单向上传，2026-10-23 v3 增强）

**同步方向：单向 —— 只把本地扫描记录上传到服务器；不会从服务器下载、覆盖或删除本地数据。**
服务端按 `batch_id` 幂等合并条码并累计 `scan_count`（见 [docs/API.md](docs/API.md)）。

- **首页**：顶部状态卡显示服务器地址、最近同步时间、同步状态（从未同步 / 同步中 / 同步成功 / 同步失败 / 登录失效）、待同步条数，并提供**「立即同步」**按钮；结果用 Snackbar 提示（已开始同步 N 条 / 没有待同步记录 / 请先注册本机 / 错误信息）。
- **设置 → 同步设置**：
  - 单向说明文案（同上）；
  - **自动同步**开关（默认开）：扫描完成后自动上传；关闭后只在手动点「立即同步」时上传；
  - **仅 Wi-Fi 同步**开关（默认关）：开启后 WorkManager 约束改为 `NetworkType.UNMETERED`，移动网络下等待 Wi-Fi；
  - 待同步条数 / 最近同步时间 / 状态行 + 「立即同步」按钮。
- **立即同步**会清掉 pending 批次的退避时间（`next_retry_at=NULL`），让退避中的批次立刻尝试上传；**不会**自动重试已标记 failed / auth_error 的批次（记录页有「重试」）；未注册（无 device_id/token）时提示先到设置页注册。
- 技术实现：唯一 WorkManager 任务 `sync-scans`（`ExistingWorkPolicy.KEEP`）、`NetworkType.CONNECTED`/`UNMETERED`、退避 30s→2min→10min→30min、每批 ≤500、401 不重试、413 拆批、422 标记失败；同步中状态写 `last_sync_status=syncing`，完成后写 success/failed/auth_error。

## 实机识别率优化（v2，2026-10-23）

用户在真机上扫笔记本屏幕上的 Code128（`89678946375895468436`）时反馈识别率低。用这两张真实照片做了离线
解码实验（Python zxing-cpp，同 C++ 内核）：把整图缩小到当前 720p 分析流里条码的等效尺寸（约 260px 宽）后，
在轻微模糊/抖动/噪声下解码成功率为 0/30；条码约 390px（1080p 等效）时 8/30，约 520px 时 17/30，约 700px 时 28/30。
结合现有"连续 2 帧都解出同一文本才确认、漏一帧清零"的逻辑，间歇性单帧解码几乎无法确认 → 很少出框/入库。

v2 改动（`docs/CONTRACT.md` §12）：

1. **分析/预览分辨率 1280×720 → 1920×1080**（16:9，`FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER` 兜底）；
2. **双 pass 解码**：pass1 保持规格参数（`minLineCount=3` 等）；仅当 pass1 无结果才用 pass2
   （`minLineCount=2`、`tryDenoise=true`、`tryInvert=true`、`GLOBAL_HISTOGRAM`），提升模糊/反光弱码命中率；
3. **确认逻辑容忍丢帧**：同一 track 在 500ms 内累计 **2 次同文本证据**即可确认，漏帧不再清零
   （仅文本变化/过期清零）；
4. **点击对焦 + 手势变焦**：单击画面 `startFocusAndMetering` 对焦；双击在 1x/2x 间切换；双指捏合连续缩放
   （扫描页显示当前倍率与「点击对焦 · 双指缩放」提示）；
5. **调试面板**新增：解码命中率、已解码帧、最近解码 pass、实际分析分辨率，便于真机复测定位。

按离线单帧命中率估算（500ms≈7 次分析机会，2 次命中即确认）：原 720p 约 4% → 1080p+pass2 约 84%，
再配合 2x 变焦约 97%+（详见 `.coord/notes/recognition-analysis.md`）。

用「App 等效多帧管线」在真实照片上做 200 次 × 7 帧仿真（逐帧随机模糊/抖动/噪声，pass1 失败走 pass2）：

| 场景 | 条码在分析图宽度 | 平均单帧命中 | 旧「连续 2 帧」确认 | v2「2 证据/500ms」确认 |
|---|---|---|---|---|
| 720p（v1 现状） | 260px | 0.0% | 0.0% | 0.0% |
| 1080p（v2） | 390px | 34.1% | 48.5% | **76.0%** |
| 1080p + 2x 变焦（v2） | 600px | 78.9% | 99.0% | **100.0%** |

即：普通距离下 v2 预期 ~76% 在 0.5s 内出框，双击/双指放到 2x 后接近稳定出框。

**真机复测建议**：安装新 APK 后对准屏幕条码，保持条码占画面约 1/3 宽度；必要时双击放大到 2x、单击画面对焦。
若仍偏低，请到「设置 → 调试面板」截图/记录：分析分辨率、解码命中率、已解码帧、最近解码 pass，连同手机型号反馈。

## 测试

- JVM 单元测试（`app/src/test`，无需模拟器）：
  - `CoordinateMapperTest` / `RotationMathTest`：4 个旋转角度 × 4 个角点的坐标映射断言（FILL_CENTER 居中偏移、90/270 宽高互换、180 翻转）；
  - `TrackManagerTest`：IoU/中心距匹配、连续 2 帧确认、未确认不显示、文本变化重置、500ms 过期；
  - `SessionCodeIndexTest`：内存去重（首次绿 / 再次灰 / 每码最多查一次库）；
  - `Code128CValidatorTest`：纯数字 + 偶数长度；
  - `SyncBackoffTest`：30s→2min→10min→30min（上限）；
  - `SyncEngineStatusMatrixTest`：200/201、401 不重试、422 失败、5xx 退避、413 拆批与确定性 batch_id；
  - `ScanRepositoryDedupTest` / `ScanBatchPayloadTest`：数据库去重、outbox 事务、payload JSON 字段与 UTC 时间格式。
- 运行：`cd app; .\gradlew.bat testDebugUnitTest`
- CI：`.github/workflows/android.yml`，在 `app/` 下执行 `./gradlew assembleDebug lintDebug testDebugUnitTest` 并上传 APK。

## 验收对照

| 需求验收项 | 实现 / 验证方式 |
|---|---|
| 1 编译通过、可打开 | `assembleDebug` 通过；APK 安装到模拟器 `am start` 后首页正常渲染（见 README 末尾“已知限制”说明模拟器相机差异） |
| 2 相机预览 / 手电筒 | ScanScreen 全屏 PreviewView(FILL_CENTER) + `cameraControl.enableTorch`；真实效果需真机（模拟器相机为合成画面） |
| 3 同时 5 个 Code128 ≤1s 出框 | 分析分辨率 1280×720、KEEP_ONLY_LATEST、全局单 BarcodeReader、单线程解码、多 symbol（`maxNumberOfSymbols=64`） |
| 4 新码绿 / 旧码灰、不闪 | TrackManager 跨帧合并（IoU / 中心距） + 连续 2 帧确认 + 内存去重索引 |
| 5 同码不重复入库 | `scan_items.code` UNIQUE + `INSERT OR IGNORE`，仓库单测 verified |
| 6 杀进程重启记录仍在 | Room 持久化 + 启动 `sweepOpenSessions` 收尾 open 会话并入队 |
| 7 飞行模式扫描、恢复网络自动上传 | outbox + WorkManager `NetworkType.CONNECTED` + 退避重试（单测覆盖状态机；联调见 dev server） |
| 8 服务端合并表 / scan_count | `POST /v1/scan-batches` 幂等上传；可用 `tools/dev-server` + `/admin` 验证 |
| 9 CSV 批量导出 | 服务端接口 `/v1/barcodes/export.csv`（dev server 已实现并通过验收脚本检查 BOM/表头） |
| 10 骁龙 870 ≥15 FPS | 本地识别（不转 Bitmap，只读 Y 平面）、解码单线程、UI 更新节流 10–15Hz；FPS 可在调试面板观察，需真机实测 |

## 已知限制

1. **真实相机验收需要真机**：模拟器（如 MuMu / AVD）的相机是合成画面，无法用来判断识别率、FPS、发热与手电筒实际效果；App 在模拟器上可安装、启动、走设置/注册/同步流程，但扫描识别效果请在真机验证。
2. **本仓库未包含 `server/`**：按需求「服务端已完成、不要修改接口」，仓库仅带 `docs/API.md` 契约；`tools/dev-server/` 为本地联调用的契约复刻（含 `/admin` 与 CSV），非生产实现。
3. **compileSdk 36 的版本钉死**：Compose 1.12+ / core-ktx 1.19+ / lifecycle 2.11+ / OkHttp 5.5+ 的 AAR 要求 compileSdk 37，故按 `app/gradle/libs.versions.toml` 钉在兼容版本；升级 compileSdk 后可放开。
4. **非 ASCII 路径**：Windows 下路径含中文时需本机级覆盖（`android.overridePathCheck=true` +
   `-Dfile.encoding=GBK`），说明见 [docs/WINDOWS_NOTES.md](docs/WINDOWS_NOTES.md)；纯 ASCII 路径与 Linux CI 无需配置。
5. 未实现点击对焦与双指变焦（需求标注为可选）；相机默认连续对焦。
6. 服务端返回 401 时 App 停止自动重试并提示重新注册（避免无限重试），需到设置页重新注册。
7. **本轮验收尾声模拟器（MuMu）ADB 通道掉线**：设备在线期间已完成安装/启动/相机/手电筒/调试统计/诊断导出/设置连通性/设备注册等人工验证；掉线后无法再安装最终 APK 或执行在线同步联合验证（代码与单测均已绿）。重启模拟器/换真机后按上文「使用流程」即可继续验证同步与识别。

## 安全说明

- token 只以 Android Keystore AES/GCM 加密后的密文存于 DataStore，UI / 日志只暴露打码值（前 4 + `****` + 后 4）。
- 诊断日志 `DiagLog` 对 `token=...` / `Authorization: Bearer ...` / `enroll_secret` 做二次掩码；网络层不启用 BODY 日志。
- 不申请存储权限；诊断 zip 只写 `cacheDir` 并通过 FileProvider 分享。
