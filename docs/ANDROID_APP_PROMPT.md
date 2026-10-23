# Android App 开发提示词（可直接交给另一个 AI）

> 用法：把下面整段内容复制给编码 AI，并附上本仓库的 `docs/API.md`。  
> 前提：`server/` 已完成，不要改服务端。

---

## 角色

你是一名资深 Android 工程师，负责从零生成一个**可编译、可运行**的 Android 工程。  
不要写伪代码，不要留 `TODO`，不要省略文件。所有代码必须完整。

## 任务

实现一个「多条形码实时扫描 + 结果同步」的 App：

- 打开 App → 进入内置相机 → 实时识别画面中的多个 Code 128 条码
- 每个条码上叠加视觉锚点：**绿色 = 首次扫到（已入库）**，**灰色 = 之前扫过（不重复入库）**
- 退出扫描 → 本地记录 → 自动/手动同步到服务器
- 设置页填写服务器地址、注册设备、测试连通性
- 服务端已完成，接口见 `docs/API.md`，**不要修改接口**

## 硬性约束（违反即失败）

1. **只做实时相机识别**，不做相册选图、不做照片导入。
2. **不做红色“疑似未识别”框**，只有绿、灰两种状态。
3. **不引入 ML Kit、Google Code Scanner、Dynamsoft、Scandit 或任何闭源识别 SDK**。
4. **识别只在手机本地完成**，服务端只收结果，不做识别。
5. **不要改服务端代码和 API 契约**。
6. **不要用 Hilt / Koin**，用手写单例容器，减少构建失败点。
7. **不要用已废弃的 CameraX API**（不要用 `setTargetResolution` 之外的旧 `Preview` 用法）。
8. **不要在主线程解码**。
9. 包名使用 `com.example.multiscan`，可全局替换。

## 技术栈

| 项 | 选型 |
|---|---|
| 语言 | Kotlin 2.x |
| 构建 | Gradle Kotlin DSL，AGP 稳定版 |
| UI | Jetpack Compose + Material 3 |
| 相机 | CameraX 1.6.x（camera-core / camera2 / lifecycle / view） |
| 识别 | `io.github.zxing-cpp:android:3.1.1` |
| 本地库 | Room |
| 后台上传 | WorkManager |
| 配置存储 | DataStore（token 用 EncryptedSharedPreferences 或 DataStore + Android Keystore） |
| 网络 | Retrofit + OkHttp + kotlinx.serialization |
| 最低版本 | minSdk 26，targetSdk 36，compileSdk 36 |

## 页面与流程

### 1. 首页
- 今日已扫描数、本地总记录数、待同步数、失败数
- 大按钮「开始扫描」
- 入口：记录、设置
- 顶部显示服务器连接状态（最近一次同步时间）

### 2. 扫描页（核心）
- `PreviewView` 全屏，`scaleType = FILL_CENTER`
- 覆盖层用 Compose `Canvas` 画四边形（不是矩形，`Position` 是四个角）
- 右上角：手电筒开关、完成按钮
- 底部：本次会话已扫数量 / 新扫数量
- 相机权限未授权时显示引导页 + 「去授权」
- 返回键 = 结束会话（弹确认）

### 3. 记录页
- 按会话分组：时间、条码数、同步状态
- 单条记录可复制
- 失败批次可手动重试
- 支持清空已同步记录

### 4. 设置页
- 服务器地址（如 `http://192.168.1.10:8000`）
- 注册口令（enroll secret）
- 「测试连通性」→ `GET /health`，显示版本和服务器时间
- 「注册本机」→ `POST /v1/devices/register`，保存 `device_id` + `token`，token 打码显示
- 显示设备型号、Android 版本、App 版本
- 「调试面板」入口

### 5. 调试面板
- 实时 FPS、单帧解码耗时（ms）、当前 track 数、丢帧数
- 「显示/隐藏锚点」开关
- 「导出诊断」：生成 zip（最近 200 条日志 + 设备信息 + 当前参数 JSON），用 `FileProvider` 分享

## 识别实现（重点，逐条照做）

### 初始化

```kotlin
val reader = BarcodeReader().apply {
    options = BarcodeReader.Options(
        formats = setOf(BarcodeReader.Format.CODE_128),
        tryHarder = true,
        tryRotate = true,
        tryInvert = false,
        tryDownscale = false,
        minLineCount = 3,
        maxNumberOfSymbols = 64,
        textMode = BarcodeReader.TextMode.PLAIN,
    )
}
```

注意：**AAR 的默认值和 C++ 默认值不同**，`tryHarder` / `tryRotate` / `tryDownscale` 在 AAR 里默认都是 `false`，必须显式写。

### 每帧

```kotlin
analysis.setAnalyzer(cameraExecutor) { image ->
    image.use { proxy ->
        val results = reader.read(proxy)          // 只读 Y 平面，不要转 Bitmap
            .filter { it.error == null && !it.text.isNullOrBlank() }
        // 回调到主线程更新 UI
    }
}
```

- `ImageAnalysis.Builder()` 设置 1280×720、`STRATEGY_KEEP_ONLY_LATEST`
- 相机输出必须是 `YUV_420_888`
- 解码在单线程 executor 上，禁止主线程
- 全局复用一个 `BarcodeReader` 实例（线程安全）
- 目标 15–20 次分析/秒，忙时丢帧，不要排队

### 坐标映射（必须写单元测试）

`Result.position` 的坐标系需要**先实测确认**（原图坐标还是旋转后坐标）。不要假设。

映射要求：
- 输入：`ImageProxy.width/height`、`imageInfo.rotationDegrees`、`PreviewView.width/height`
- `FILL_CENTER`：`scale = max(viewW / rotatedW, viewH / rotatedH)`，再居中偏移
- 旋转 0/90/180/270 分别处理
- 写 `CoordinateMapperTest`，对 4 个角度、4 个角点做断言
- 后置摄像头不镜像

### 跟踪与确认

每帧结果要和上一帧合并，否则框会闪：

1. 用 IoU > 0.3 或中心点距离 < 半个框高，把本帧的框匹配到已有 track
2. 同一 track 连续 2 帧解出相同 `text` 才算**确认**（约 100ms）
3. 未确认的 track **不要画绿框**，否则误识别一次就永久变灰
4. track 超过 500ms 没再出现就移除

### 绿 / 灰状态机

```
确认的 track:
  查 session 内存集合
    ├─ 不在 → 查 Room 是否有该 code
    │        ├─ 没有 → 绿框，插入 Room，加入 session 集合
    │        └─ 有   → 灰框，不入库
    └─ 在   → 灰框
```

- `scan_items.code` 加 **UNIQUE 索引**，从数据库层兜底“永不重复存”
- 灰色框保留显示，但不要每帧重复查库（内存缓存 `Set<String>`）
- 同一码离开画面再回来，仍然是灰

### 相机控制

- 手电筒：`camera.cameraControl.enableTorch(bool)`
- 对焦：优先连续对焦；点击对焦可后置
- 变焦：可选，双指缩放
- 退出页面必须 `cameraProvider.unbindAll()`，避免占着摄像头

## 本地存储（Room）

```sql
sessions(id TEXT PRIMARY KEY, started_at TEXT, finished_at TEXT, state TEXT)
scan_items(id TEXT PRIMARY KEY, session_id TEXT, code TEXT UNIQUE, first_seen_at TEXT)
outbox(id TEXT PRIMARY KEY, session_id TEXT, state TEXT, attempts INTEGER, next_retry_at TEXT)
```

- `sessions.state`: `open | finished | synced | failed`
- 进入扫描页就创建 `session`（UUID 作为未来的 `batch_id`）
- 每扫到**新的**绿码立即插入 `scan_items`，不要等退出
- 点「完成」：设置 `finished_at`，写 `outbox`（同一事务），触发同步
- App 被杀：下次启动把 `open` 会话自动收尾并入队
- 所有时间存 UTC ISO8601

## 同步

- WorkManager 唯一任务 `sync-scans`，`ExistingWorkPolicy.KEEP`
- 约束：`NetworkType.CONNECTED`
- 失败退避：30s → 2min → 10min → 30min（上限）
- 每批最多 500 条
- 请求体：

```json
{
  "batch_id": "<session id>",
  "app_version": "0.1.0",
  "captured_at": "2026-10-23T08:00:00Z",
  "items": [
    { "item_id": "<uuid>", "raw_text": "0123456789", "format": "Code128", "line_count": 7 }
  ]
}
```

- 201/200：标记 `synced`，删除或归档 outbox
- 401：标记 `auth_error`，通知用户重新注册，**不要无限重试**
- 413：拆批重发
- 422：标记 `failed`，保留记录供人工处理
- 5xx / 超时：保留，按退避重试

## 服务端 API

完整契约见 `docs/API.md`，摘要：

```
GET  /health
POST /v1/devices/register     {name, enroll_secret} -> {device_id, token}
POST /v1/scan-batches         Bearer token
GET  /v1/barcodes?q=&limit=&offset=
GET  /v1/barcodes/export.csv
GET  /v1/stats
```

## 权限

- `android.permission.CAMERA`（运行时申请）
- `android.permission.INTERNET`
- 不申请存储权限

拒绝相机权限时：显示说明 + 「去设置」，不要崩溃。

## 构建与 CI

生成 `.github/workflows/android.yml`：

```yaml
name: android
on:
  push:
    paths: ["app/**", ".github/workflows/android.yml"]
  pull_request:
    paths: ["app/**"]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
      - uses: android-actions/setup-android@v3
      - name: Build
        working-directory: app
        run: ./gradlew assembleDebug lintDebug testDebugUnitTest
      - uses: actions/upload-artifact@v4
        with:
          name: app-debug
          path: app/build/outputs/apk/debug/*.apk
```

要求：

- `./gradlew assembleDebug` 必须零错误
- 单元测试至少覆盖：坐标映射、Code128C 校验（纯数字 + 偶数长度）、去重逻辑
- 不要提交 `local.properties`、`*.keystore`、`build/`

## 验收标准

1. 编译通过，安装后能打开
2. 相机预览正常，手电筒可用
3. 画面里同时放 5 个 Code128，1 秒内全部出框
4. 新码绿框、已扫过的码灰框，框跟随不闪烁
5. 同一个码不会在 `scan_items` 里出现两次
6. 杀进程重启，已扫记录仍在
7. 飞行模式下扫 3 个码，恢复网络后自动上传
8. 服务器 `/admin` 能看到合并后的条码和 `scan_count`
9. 批量导出 CSV 正常
10. 扫描页在骁龙 870 上稳定 ≥15 FPS，无明显发热

## 输出要求

- 输出完整文件树
- 输出每个文件的完整内容
- 不要解释性废话，直接给代码
- 最后列出：如何本地构建、如何装 APK、已知限制

## 常见坑（务必检查）

1. `ImageProxy` 必须 `use {}`，否则分析器只跑一帧
2. 相机格式必须是 `YUV_420_888`
3. AAR 默认参数和 C++ 不同，见上文
4. 不要在 `analyze` 里更新 Compose 状态（节流到 10–15Hz）
5. 叠框坐标必须处理旋转，写单测
6. `scan_items.code` 要 UNIQUE
7. token 不要明文写进日志
8. 时间统一 UTC
9. 16KB page size 已由 AAR 处理，不要自己加 `jniLibs`
10. 不要在模拟器上判断相机效果
