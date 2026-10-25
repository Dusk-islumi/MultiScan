# MultiScan Android App — 内部实现契约

> 本文件是模块间的**接口契约**。所有模块按此实现；改动跨模块签名时先更新本文件。
> 需求原文：`docs/ANDROID_APP_PROMPT.md`＋ `docs/API.md`（服务端契约，不可改）。
> 依赖版本矩阵以 `app/gradle/libs.versions.toml` 为准。
> zxing-cpp 3.1.1 API 约定（package `zxingcpp`、`read(ImageProxy)`、位置在旋转 crop 空间）见 `scan/decode/BarcodeDecoder.kt` 注释。

## 0. 目标与硬约束（摘要，违反即失败）

- 从零生成**可编译、可运行**的 Android 工程；包名 `com.example.multiscan`；单模块；独立 Gradle 工程位于 `app/`（`app/gradlew` 可执行，APK 产物 `app/build/outputs/apk/debug/*.apk`，与 CI 一致）。
- 只做**实时相机**识别：无相册选图、无图片导入、无红色"疑似"框（只有绿/灰两种锚点，未确认 track 不画）。
- 识别只用 `io.github.zxing-cpp:android:3.1.1`；禁止 ML Kit / Google Code Scanner / Dynamsoft / Scandit 等。
- 不用 Hilt / Koin；手写单例容器 `AppContainer`。
- 不用已废弃 CameraX API；相机输出 `YUV_420_888`；解码不得在主线程；`ImageProxy` 必须 `use {}`。
- 不改 `docs/API.md` 契约；不申请存储权限；`minSdk 26 / targetSdk 36 / compileSdk 36`；Kotlin 2.x + Compose M3 + Room + WorkManager + DataStore + Retrofit/OkHttp/kotlinx.serialization。
- 时间全部 UTC ISO-8601 字符串；token 不打日志（`DiagLog` 调用处必须打码）。
- `scan_items.code` UNIQUE；同一码绝不入库两次（DB + 内存双保险）。

## 1. 仓库布局与写作用域（WRITE SCOPES，必须严格遵守）

```
OCR/
├── docs/API.md                     # 只读（服务端契约）
├── .github/workflows/android.yml   ├── .gitignore / README.md          ├── tools/dev-server/server.py      # 本地联调用 mock server（绝不替代服务端）
└── app/                            # 独立 Gradle 工程（root project = application module）
    ├── settings.gradle.kts         
    ├── build.gradle.kts            
    ├── gradle.properties           
    ├── gradlew(.bat) / gradle/wrapper/**   
    ├── local.properties            # 本机生成，不提交
    └── src/
        ├── main/AndroidManifest.xml                               ├── main/java/com/example/multiscan/
        │   ├── MultiScanApplication.kt                            │   ├── AppContainer.kt                            # 手写单例容器（集成期组装）
        │   ├── MainActivity.kt                            # ui（Compose 页面与导航）
        │   ├── core/**                                            │   │   ├── contracts/ScanSessionStore.kt
        │   │   ├── diag/DiagLog.kt
        │   │   ├── time/Utc.kt
        │   │   └── code128/Code128CValidator.kt
        │   ├── data/**                                    # data-sync
        │   ├── scan/**                                    # scanner
        │   └── ui/**                                      # ui
        ├── main/res/**                                    # ui（字符串/主题/图标/FileProvider 路径）
        └── test/java/com/example/multiscan/
            ├── core/**                                    # 核心工具的单测（Code128C 等）
            ├── scan/**                                    # scanner（坐标映射/跟踪/去重）
            └── data/**                                    # data-sync（退避/同步状态机/去重）
```

**改动纪律**：`app/build.gradle.kts`、`AndroidManifest.xml`、`AppContainer.kt` 是高风险共享文件，改动前先确认影响面并跑完整 CI。
**构建验证**：统一用 `cd app && ./gradlew assembleDebug testDebugUnitTest lintDebug` 验证，避免多人同时修改构建产物。

## 2. 构建与技术栈

- 独立工程：`app/build.gradle.kts` 顶层 apply `com.android.application` + `org.jetbrains.kotlin.android` + `org.jetbrains.kotlin.plugin.compose` + `org.jetbrains.kotlin.plugin.serialization` + `com.google.devtools.ksp`；`compileSdk=36, targetSdk=36, minSdk=26`；Java/Kotlin target 17。
- 依赖（含精确版本见 `app/build.gradle.kts`）：camera-core/camera2/camera-lifecycle/camera-view 1.6.x；`io.github.zxing-cpp:android:3.1.1`；Room（runtime/ktx/compiler via KSP）；WorkManager（work-runtime-ktx）；DataStore（datastore-preferences）；Retrofit + OkHttp + kotlinx-serialization + converter-kotlinx-serialization；Compose BOM + material3 + ui + ui-tooling-preview(debug) + activity-compose + lifecycle-runtime-compose + lifecycle-viewmodel-compose；core-ktx。**不引入** navigation-compose（自写 4 页导航即可）、material-icons-extended（用 material-icons-core 自带图标或手绘 `Icons.Default.*` 已有集合）。
- 权限：`android.permission.CAMERA`、`android.permission.INTERNET`；不申请存储权限。`FileProvider` 用于诊断 zip 分享。
- `lintDebug` 必须通过（`lint { abortOnError = true }`，修掉真实 error 而不是关掉）。
- 单元测试：JVM `testDebugUnitTest` 必须全绿；不依赖 Robolectric。

## 3. 分层与依赖方向

```
ui ──> data（Repository/flow）＋ scan（ScanController）
scan ──> core.contracts.ScanSessionStore（由 data.ScanRepository 实现）＋ core.diag/time
data ──> core（Utc/DiagLog/ScanSessionStore 实现）＋ Room/Retrofit/WorkManager/DataStore
ui ──> core（Utc 展示/DiagLog/Code128C 校验展示）
```
- `AppContainer` 是唯一单例容器（Application 创建），`SyncWorker`/`MainActivity` 通过 `(application as MultiScanApplication).container` 取依赖。
- 禁止全局可变单例（除 `AppContainer` 持有的实例）；`BarcodeReader` 作为 `ScanController` 内部全局唯一实例复用。
- 无协程作用域泄漏：Repository 用 `suspend`；`ScanController` 内部自带 `scanScope`；WorkManager 用 `CoroutineWorker`。

## 4. 共享内核（core，先于其他模块）

### 4.1 `core/time/Utc.kt`
```kotlin
package com.example.multiscan.core.time
object Utc {
    fun nowIso(): String                       // Instant.now().toString()
    fun startOfTodayIso(zone: ZoneId = ZoneId.systemDefault()): String
    fun parseOrNull(iso: String?): Instant?
    fun formatDisplay(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String  // "MM-dd HH:mm:ss"，null→"—"
}
```

### 4.2 `core/code128/Code128CValidator.kt`
```kotlin
package com.example.multiscan.core.code128
object Code128CValidator {
    /** Code128C 仅编码纯数字且长度为偶数（非空）。 */
    fun isValid(value: String): Boolean
}
```

### 4.3 `core/diag/DiagLog.kt`
```kotlin
package com.example.multiscan.core.diag
class DiagLog {
    fun log(tag: String, message: String)      // 线程安全；追加 UTC 时间戳；环形缓冲最近 200 条
    fun snapshot(): List<String>               // 最近 200 条，旧→新
    fun clear()
}
```
- 用途：调试面板「导出诊断」zip 内容之一；**绝不记录 token / enroll_secret 明文**（调用方负责打码，`DiagLog` 另做 `token=`/`secret=` 二次掩码兜底）。

### 4.4 `core/contracts/ScanSessionStore.kt`（scan → data 的唯一边界）
```kotlin
package com.example.multiscan.core.contracts

/** 由 data.ScanRepository 实现；scan 只依赖此接口。 */
interface ScanSessionStore {
    /** 进入扫描页即调用；创建 sessions(state=open)，返回 session id（= 未来 batch_id, UUID 字符串）。 */
    suspend fun startSession(startedAtUtc: String): String

    /**
     * 确认码入库：INSERT OR IGNORE 语义 + code UNIQUE 兜底。
     * @return true=本次新插入（绿框）；false=已存在（灰框，不重复入库）。
     */
    suspend fun registerCode(
        sessionId: String,
        code: String,
        format: String,           // "Code128"
        symbologyId: String?,     // 可空，如 "]C0"
        lineCount: Int,           // >=1，来自识别结果
        firstSeenAtUtc: String,
    ): Boolean

    /**
     * 结束会话（同一事务）：sessions.state=finished、finished_at；若该 session 有 >=1 条 scan_items 则插入 outbox(pending)；
     * 若 0 条则直接删除空 session（不产生 outbox）。随后调用方（data 实现内部）触发 SyncScheduler.enqueueNow()。
     */
    suspend fun finishSession(sessionId: String, finishedAtUtc: String)
}
```

## 5. SCANNER 模块公共 API（`com.example.multiscan.scan`）

> 以下为扫描模块对 UI 的稳定接口，实现细节可调整，但公开签名不应随意变。

```kotlin
package com.example.multiscan.scan

class ScanController(
    context: Context,                  // 用 applicationContext
    private val store: ScanSessionStore,
    private val diagLog: DiagLog,
) {
    val uiState: StateFlow<ScanUiState>
    val debugStats: StateFlow<ScanDebugStats>

    /** 扫描页 onStart：创建 DB session（suspend，内部切 IO）。可重复调用（已开则 no-op）。 */
    suspend fun startSession()

    /** 主线程调用：绑定 CameraX Preview + ImageAnalysis（1280x720 YUV_420_888, KEEP_ONLY_LATEST）。 */
    fun bind(previewView: PreviewView, lifecycleOwner: LifecycleOwner)

    /** 主线程调用：cameraProvider.unbindAll()，关闭 executor/reader。离开页面必调。 */
    fun unbind()

    fun setTorch(enabled: Boolean)

    /** 结束会话：停止分析 → 等待已确认码写入排空 → store.finishSession（写 outbox 并触发同步）。 */
    suspend fun finishSession(): ScanSessionSummary

    /** 页面销毁兜底：unbind + 关闭资源；不结束会话（未 finish 的 open 会话由 App 启动 sweeper 收尾）。 */
    fun release()
}

data class ScanUiState(
    val sessionId: String? = null,
    val anchors: List<ScanAnchor> = emptyList(),   // 只含 confirmed；绿=新入库，灰=已存在
    val totalDetected: Int = 0,                    // 本次会话确认过的不同码数量
    val newDetected: Int = 0,                      // 本次会话新入库数量
    val torchOn: Boolean = false,
    val torchAvailable: Boolean = false,
    val geometry: FrameGeometry? = null,           // 最近一帧的分析图几何信息
    val cameraReady: Boolean = false,
    val error: String? = null,
)

data class ScanAnchor(
    val text: String,
    val quad: List<PointF>,          // 4 点，分析图原始像素坐标（未旋转），顺序 TL,TR,BR,BL 由 zxing 提供
    val isNew: Boolean,              // true=绿（首次扫到并入库）, false=灰（之前扫过）
)

data class FrameGeometry(val imageWidth: Int, val imageHeight: Int, val rotationDegrees: Int)
data class PointF(val x: Float, val y: Float)

data class ScanDebugStats(
    val fps: Float = 0f,
    val lastDecodeMs: Long = 0,
    val avgDecodeMs: Long = 0,
    val activeTracks: Int = 0,
    val droppedFrames: Long = 0,
    val analyzedFrames: Long = 0,
    val cameraResolution: String = "",
)

data class ScanSessionSummary(val sessionId: String, val total: Int, val newCount: Int)
```

### 5.1 UI 坐标系映射（scanner 提供纯函数，UI 每帧调用，必须单测）
```kotlin
package com.example.multiscan.scan.mapper
object CoordinateMapper {
    fun rotatedSize(geometry: FrameGeometry): Pair<Int, Int>
    /** [point] 必须是 **ImageProxy 原始 buffer 像素坐标（未旋转）**。 */
    fun mapPoint(geometry: FrameGeometry, viewWidth: Int, viewHeight: Int, point: PointF): PointF
    fun mapQuad(geometry: FrameGeometry, viewWidth: Int, viewHeight: Int, quad: List<PointF>): List<PointF>
}
```
- 语义：输入分析图**原始像素坐标**（未旋转，尺寸 = `ImageProxy.width/height`）+ `rotationDegrees`（0/90/180/270，CameraX `ImageInfo.rotationDegrees`），先顺时针旋转该角度得到"竖直画面"坐标（90/270 交换宽高），再按 `PreviewView` FILL_CENTER：`scale=max(viewW/rotW, viewH/rotH)`，居中偏移；后置相机不镜像。
- **为什么 ScanAnchor 存未旋转坐标**：zxing-cpp 的 `Result.position` 实测返回的是**旋转后 crop 空间**坐标（zxing-cpp 3.1.1 内部先 crop 再按 rotationDegrees 旋转）。`BarcodeDecoder` 必须先用逆旋转把它转回 ImageProxy buffer 坐标（cropRect 为全图），再交给 mapper 统一做旋转+缩放，避免两处各转一次导致错位。
- 旋转公式（像素索引，w/h = 原始 buffer 宽高；顺时针 CW）：
  - 逆旋转（decoder：rotated→buffer，p 为 zxing 坐标）：`0:(p.x,p.y)`；`90:(p.y, h-1-p.x)`；`180:(w-1-p.x, h-1-p.y)`；`270:(w-1-p.y, p.x)`。
  - 正旋转（mapper：buffer→rotated，q 为 buffer 坐标）：`0:(q.x,q.y)`；`90:(h-1-q.y, q.x)`；`180:(w-1-q.x, h-1-q.y)`；`270:(q.y, w-1-q.x)`。
  - rotated 尺寸：`0/180=(w,h)`；`90/270=(h,w)`。
- 必须 `CoordinateMapperTest`：4 个角度 × 4 个角点断言（含 90/270 宽高互换、180 翻转、中心点附近不变、非正方形 view 的居中偏移）。

### 5.2 scanner 内部文件建议（可调整）
`scan/camera/CameraController.kt`、`scan/decode/BarcodeDecoder.kt`（zxing-cpp 封装，单线程 executor，全局 1 个 BarcodeReader 实例）、`scan/track/TrackManager.kt`（IoU>0.3 或中心距<半框高匹配；连续 2 帧同 text 确认；500ms 未出现移除）、`scan/dedup/SessionCodeIndex.kt`（内存 Set + DB 查询去重，纯逻辑可测）、`scan/ScanController.kt`。
- 逐帧流程：`ImageProxy.use{}` → `reader.read(proxy)` → 过滤 `error!=null || text.isNullOrBlank()` → TrackManager.update(now) → 对**新确认**track 查 `SessionCodeIndex` → 需要查库时 `store.registerCode(...)`（放进单一 actor/Channel 串行消费，避免并发写库）→ 更新 `ScanUiState`（节流 10–15Hz；禁止每帧刷 Compose 状态）。
- 相机：`ProcessCameraProvider` 绑定 `DEFAULT_BACK_CAMERA`；`ImageAnalysis.Builder().setBackpressureStrategy(STRATEGY_KEEP_ONLY_LATEST).setOutputImageFormat(OUTPUT_IMAGE_FORMAT_YUV_420_888).setResolutionSelector(ResolutionSelector + ResolutionStrategy(Size(1280,720), FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))`（不得用已废弃 `setTargetResolution` 如果会触发 deprecated error；优先 ResolutionSelector）。`PreviewView.scaleType=FILL_CENTER` 由 UI 设置。
- 手电筒：`camera.cameraControl.enableTorch`；`hasFlashUnit` 判断可用性。
- 节流/丢帧：分析器记录 `droppedFrames`（距上次分析 <~50ms 主动丢帧；忙时 KEEP_ONLY_LATEST 自然丢帧）。FPS/耗时常量进 `debugStats`。
- 坐标语义实测：以 zxing-cpp 3.1.1 的坐标语义为准（旋转 crop 空间）；若 position 是"旋转后坐标"，mapper 相应调整并在单测注释说明。**不得假设，必须写进代码注释与测试**。

## 6. DATA-SYNC 模块公共 API（`com.example.multiscan.data`）

### 6.1 Room（字段严格按提示词，允许追加少量辅助列，但三张核心表与名字不可改）
```kotlin
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name="started_at") val startedAt: String,      // UTC ISO
    @ColumnInfo(name="finished_at") val finishedAt: String?,   // UTC ISO
    val state: String,                                          // open|finished|synced|failed
)

@Entity(tableName = "scan_items", indices = [Index(value=["code"], unique=true)])
data class ScanItemEntity(
    @PrimaryKey val id: String,                                 // UUID
    @ColumnInfo(name="session_id") val sessionId: String,
    val code: String,                                           // UNIQUE
    @ColumnInfo(name="first_seen_at") val firstSeenAt: String,  // UTC ISO
    val format: String = "Code128",
    @ColumnInfo(name="symbology_id") val symbologyId: String? = null,
    @ColumnInfo(name="line_count") val lineCount: Int = 1,
)

@Entity(tableName = "outbox", indices = [Index(value=["session_id"], unique=true)])
data class OutboxEntity(
    @PrimaryKey val id: String,                                 // UUID
    @ColumnInfo(name="session_id") val sessionId: String,
    val state: String,     // pending|uploading|failed|auth_error|synced
    val attempts: Int = 0,
    @ColumnInfo(name="next_retry_at") val nextRetryAt: String? = null, // UTC ISO
    @ColumnInfo(name="last_error") val lastError: String? = null,      // 追加列：失败原因（脱敏）
)
```
- `sessions.state`：`open | finished | synced | failed`；`finishSession` 成功后 session 保持 finished，outbox 同步成功 → session=synced + outbox=synced（保留用于记录页）；422 → session=failed + outbox=failed；401 → outbox=auth_error + session=failed。
- DAO：`SessionDao`、`ScanItemDao`、`OutboxDao`（suspend + Flow）。事务抽象：
```kotlin
fun interface TransactionRunner { suspend fun <T> run(block: suspend () -> T): T }
```
  Room 实现用 `database.withTransaction`；单测用 fake 直通。**Repository/同步逻辑必须依赖 DAO 接口 + TransactionRunner，而不是 AppDatabase 具体类**，便于 JVM 单测。
- `AppDatabase.getInstance(context)`（fallbackToDestructiveMigrationOnDowngrade 不可用；v1 无迁移）。

### 6.2 DataStore + Keystore（`settings`）
`SettingsStore(context)` 内部 DataStore Preferences（文件名 `settings`）：
- 存：`server_url`（默认 `http://10.0.2.2:8000`）、`enroll_secret`、`device_id`、`token_cipher`（Keystore AES/GCM 加密后的 Base64(iv+ciphertext)）、`overlay_visible`（调试面板锚点开关，默认 true）、`last_sync_at`、`last_sync_status`（never|success|failed|auth_error）。
- 对外：
```kotlin
data class SettingsSnapshot(
    val serverUrl: String, val enrollSecret: String,
    val deviceId: String?, val hasToken: Boolean, val maskedToken: String?, // 前4+****+后4
    val overlayVisible: Boolean,
    val lastSyncAt: String?, val lastSyncStatus: String,
)
class SettingsStore(context: Context) {
    val snapshot: StateFlow<SettingsSnapshot>
    suspend fun current(): SettingsSnapshot
    suspend fun setServerUrl(url: String)              // trim；设备地址变化时清 token/deviceId（提示重新注册）——实现里做
    suspend fun setEnrollSecret(secret: String)
    suspend fun saveRegistration(deviceId: String, token: String)  // token 加密后存；旧 token 覆盖
    suspend fun setOverlayVisible(visible: Boolean)
    suspend fun setLastSync(atUtcIso: String?, status: String)
    suspend fun token(): String?                       // 解密失败返回 null（视为未注册）
    suspend fun clearRegistration()
}
```
- Keystore：`data/prefs/TokenCipher.kt`，`AndroidKeyStore` AES/GCM 256，key alias `multiscan_token_key`，`setRandomizedEncryptionRequired(true)`；加密结果 `iv + ciphertext`，Base64 NO_WRAP。异常（key 失效）→ 返回 null + DiagLog 记录（不打 token）。

### 6.3 网络（`data/remote`）
```kotlin
interface MultiScanApi {
    @GET("health") suspend fun health(): HealthResponse
    @POST("v1/devices/register") suspend fun register(@Body body: RegisterRequest): RegisterResponse
    @POST("v1/scan-batches") suspend fun uploadBatch(@Body body: ScanBatchRequest): ScanBatchResponse
}
data class HealthResponse(val ok: Boolean, val version: String, @SerialName("server_time") val serverTime: String)
data class RegisterRequest(val name: String, @SerialName("enroll_secret") val enrollSecret: String)
data class RegisterResponse(@SerialName("device_id") val deviceId: String, val token: String, @SerialName("server_time") val serverTime: String? = null)
data class ScanBatchRequest(
    @SerialName("batch_id") val batchId: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("captured_at") val capturedAt: String,
    val items: List<ScanItemPayload>,
)
data class ScanItemPayload(
    @SerialName("item_id") val itemId: String,
    @SerialName("raw_text") val rawText: String,
    val format: String = "Code128",
    @SerialName("symbology_id") val symbologyId: String? = null,
    @SerialName("line_count") val lineCount: Int? = null,
)
data class ScanBatchResponse(@SerialName("batch_id") val batchId: String, val accepted: Int, val duplicated: Int, @SerialName("barcodes_total") val barcodesTotal: Int)
```
- `NetworkFactory(settingsStore)`：`suspend fun createApi(baseUrlOverride: String? = null): MultiScanApi`（Retrofit 每次按当前 serverUrl 构建/轻量缓存；OkHttp 超时 connect 10s / read 30s / write 30s；`Authorization: Bearer <token>` 由 interceptor 注入，token 为空则不带头；**禁止 BODY 级 HttpLoggingInterceptor（避免 token 泄漏）**，如需日志只记录 method+url+status）。
- `ServerRepository(settingsStore, networkFactory)`：
```kotlin
sealed interface HealthCheckResult { data class Ok(val version: String, val serverTime: String): HealthCheckResult; data class Error(val message: String): HealthCheckResult }
sealed interface RegisterResult { data class Ok(val deviceId: String, val maskedToken: String): RegisterResult; data class Error(val message: String, val httpCode: Int? = null): RegisterResult }
class ServerRepository(...) {
    val config: StateFlow<SettingsSnapshot>
    suspend fun saveServerUrl(url: String)   // 校验 http(s):// 基本格式，返回 Boolean 或抛 IllegalArgumentException（UI 提示）
    suspend fun saveEnrollSecret(secret: String)
    suspend fun testHealth(): HealthCheckResult                  // GET {url}/health, 5s 超时
    suspend fun registerDevice(deviceName: String): RegisterResult  // POST /v1/devices/register，成功保存 deviceId+token
}
```

### 6.4 Repository 聚合（`data/repository/ScanRepository.kt`，实现 `ScanSessionStore`）
```kotlin
data class HomeStats(
    val todayScanned: Int, val totalRecords: Int, val pendingSync: Int, val failed: Int,
    val lastSyncAt: String?, val lastSyncStatus: String,
)
data class ScanItemRecord(val id: String, val code: String, val firstSeenAt: String, val format: String, val lineCount: Int)
enum class SessionState { OPEN, FINISHED, SYNCED, FAILED }
data class SessionRecord(
    val sessionId: String, val startedAt: String, val finishedAt: String?,
    val state: SessionState, val outboxState: String?, val itemCount: Int, val items: List<ScanItemRecord>,
)

class ScanRepository(
    private val sessionDao: SessionDao,
    private val scanItemDao: ScanItemDao,
    private val outboxDao: OutboxDao,
    private val tx: TransactionRunner,
    private val syncScheduler: SyncScheduler,
    private val diagLog: DiagLog,
    private val clock: () -> Long = { System.currentTimeMillis() },   // 测试注入
) : ScanSessionStore {
    val homeStats: Flow<HomeStats>
    val sessions: Flow<List<SessionRecord>>
    override suspend fun startSession(startedAtUtc: String): String
    override suspend fun registerCode(sessionId: String, code: String, format: String, symbologyId: String?, lineCount: Int, firstSeenAtUtc: String): Boolean
    override suspend fun finishSession(sessionId: String, finishedAtUtc: String)
    suspend fun sweepOpenSessions()                  // App 启动：open → finished(+outbox) → enqueueNow
    suspend fun retryOutbox(outboxId: String)        // 手动重试失败批次：state=pending, attempts=0, nextRetryAt=null → enqueueNow
    suspend fun retryAllFailed()
    suspend fun clearSyncedData()                    // 删除 synced sessions + 其 scan_items + synced outbox
}
```
- `registerCode` 去重实现：`INSERT OR IGNORE`（Room `@Insert(onConflict = IGNORE)` 返回 -1）判断是否新插入；捕获 `SQLiteConstraintException` 兜底；返回 boolean。
- `homeStats` 由 DAO 聚合查询 + SettingsStore 快照组合；`todayScanned` 按本地时区今天 00:00 转 UTC ISO 后比较。

### 6.5 同步（`data/sync`）
```kotlin
object SyncConfig {
    const val UNIQUE_WORK_NAME = "sync-scans"
    const val MAX_ITEMS_PER_BATCH = 500
    const val APP_VERSION = "0.1.0"        // 与 build.gradle versionName 一致
}
object SyncBackoff {                        // 30s → 2min → 10min → 30min(上限)
    fun delayMillis(attempts: Int): Long
}
class SyncScheduler(private val context: Context) {
    fun enqueueNow()                        // OneTimeWorkRequest + NetworkType.CONNECTED + ExistingWorkPolicy.KEEP
    fun enqueueWithDelay(delayMillis: Long) // 同一 unique name；用于自定义退避
}
class SyncEngine(                           // 纯逻辑，JVM 可测：依赖 OutboxDao/SessionDao/ScanItemDao/TransactionRunner/apiProvider/clock
    ...
) {
    suspend fun runOnce(): SyncRunSummary    // 处理所有 due outbox 行，按 200/201/401/413/422/5xx/IO 分支
}
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result    // catch-all → Result.retry() 兜底；内部调 SyncEngine.runOnce()
}
```
行为矩阵（必须实现）：
| 情况 | 处理 |
|---|---|
| 200/201 | outbox=synced，session=synced，记录 last_sync_at/status=success |
| 401 | outbox=auth_error，session=failed，`last_sync_status=auth_error`，不安排重试，UI 提示重新注册 |
| 403（注册） | 设置页显示"口令错误"，不保存 token |
| 413 | 递归对半拆分 items 重发；batch_id 用确定性派生（`UUID.nameUUIDFromBytes("$sessionId|$from|$to")`，首段可用 sessionId），保证重试幂等；全部成功才 marked synced |
| 422 | outbox=failed，session=failed，保留记录，记录 last_error（脱敏） |
| 5xx / IOException / 超时 | attempts+1，next_retry_at=now+SyncBackoff.delayMillis(attempts)，保持 pending；worker 返回 retry 或调度下一次 delayed work（同一 unique name）；上限 30min |
| 无 due 任务 | 不做事，若有 pending 未到期 → 调度 next_retry_at 对应的 delayed work |
- 每批最多 500；payload：batch_id=sessionId、app_version="0.1.0"、captured_at=session.finished_at（UTC ISO，带 Z）、items=[item_id, raw_text=code, format="Code128", symbology_id?, line_count]。
- 同步并发：单 WorkManager 任务串行；`outbox.state=uploading` 防重入（进程崩溃残留 uploading 在下次 sweep 时重置 pending）。

### 6.6 data-sync 必写单测（JVM，fake DAO/TransactionRunner/api/clock）
- `SyncBackoffTest`：attempts 1..5 精确等于 30_000/120_000/600_000/1_800_000/1_800_000。
- `SyncEngineStatusMatrixTest`：200/201、401、422、500、413 拆分行为（413 断言拆分批次数、请求顺序、派生 batch_id 稳定）。
- `ScanRepositoryDedupTest`：fake DAO 下 registerCode 同码两次 → true/false；不同码 → true；finishSession 写 outbox（有 items）与删空 session；sweepOpenSessions 收尾 open 会话。
- `ScanBatchPayloadTest`（可选）：payload 字段/UTC 格式/≤500 截断。

## 7. UI 模块（`com.example.multiscan.ui`）

- `MainActivity`：单 Activity + Compose；`setContent { MultiScanTheme { AppRoot() } }`；edge-to-edge；`BackHandler` 由各页面处理。
- 自写导航：`sealed interface Route { Home, Scan, Records, Settings, Debug }` + `remember { mutableStateOf<Route>(Home) }` + 手写返回栈；Scan 页返回/完成走确认对话框。
- `ui/theme/`：Material3 主题（深色/浅色跟随系统，dynamicColor 可关；主色绿系）。
- `ui/home/HomeScreen.kt`：顶部连接状态卡（最近同步时间/状态、服务器地址）；4 个统计卡（今日已扫/本地总数/待同步/失败）；大按钮「开始扫描」；入口「记录」「设置」。
- `ui/scan/ScanScreen.kt`：
  - `AndroidView { PreviewView(context).apply { scaleType=FILL_CENTER } }` 全屏；`ScanViewModel` 持有 `ScanController`；`DisposableEffect` bind/unbind；权限未授权 → 引导页（说明＋「去授权」/「去设置」），不崩溃。
  - 覆盖层 `Canvas`：对 `uiState.anchors` 调 `CoordinateMapper.mapQuad(...)` 画**四边形** path；绿色（`Color(0xFF22C55E)`）＝新入库，灰色（`Color(0xFF9CA3AF)`）＝之前扫过；线宽 ≈3dp，带 1px 黑描边提高对比；灰色可半透明；**未确认 track 不画**。
  - 右上角：手电筒开关（可用性来自 uiState）、完成按钮；底部：本次会话已扫数 / 新扫数；返回键 `BackHandler` 弹"结束本次扫描？"确认。
  - 事件：`finish` → `viewModel.finish { summary -> nav back + Toast/Snackbar }`；页面销毁 release。Compose 状态更新频率已由 controller 节流。
- `ui/records/RecordsScreen.kt`：按 session 分组卡片（时间、条码数、同步状态徽标：待同步/同步中/已同步/失败/登录失效）；展开显示 items；单条点击复制 code 到剪贴板 + Toast；失败批次「重试」；顶部「清空已同步记录」（确认对话框）。
- `ui/settings/SettingsScreen.kt`：服务器地址输入（保存）、注册口令（密码样式）、「测试连通性」→ 显示 ok/version/server_time 或错误；「注册本机」→ 显示 device_id + 打码 token；设备信息区（Build.MODEL、Android 版本、App versionName）；「调试面板」入口。保存/注册用 ViewModel + loading/错误提示，禁止主线程网络。
- `ui/debug/DebugScreen.kt`：实时 FPS、单帧解码耗时、当前 track 数、丢帧数、相机分辨率；「显示/隐藏锚点」Switch（SettingsStore.overlayVisible，ScanScreen 读取）；「导出诊断」→ `ui/diag/DiagExporter.kt` 生成 zip（`logs/last200.txt` + `device.json` + `params.json`）到 `cacheDir/diagnostics/`，`FileProvider`（authority `${applicationId}.fileprovider`，`res/xml/file_paths.xml` 配 cache-path）用 `Intent.ACTION_SEND` type=`application/zip` 分享（`FLAG_GRANT_READ_URI_PERMISSION`）。
- `MainActivity`/res 属于 UI 层；`themes.xml` parent 用 `android:Theme.Material.NoActionBar`（不引入 appcompat）；strings 全部放 `res/values/strings.xml`（中文 UI 文案）；图标用 `res/mipmap-anydpi-v26` 自适应图标 + `res/drawable` vector（不要 PNG）。
- UI 必须极简可靠、可读；不要引入额外依赖；所有文案中文字符串资源；深色模式可读。

## 8. 线程/协程/性能规则

- 解码：单线程 `Executors.newSingleThreadExecutor()`（或 `Dispatchers.Default.limitedParallelism(1)`），禁止主线程；`ImageProxy.use{}`。
- 每帧结果合并进 `TrackManager`；UI 状态更新节流 10–15Hz（`uiState` 用 `MutableStateFlow` + 时间戳节流或 66ms 采样）；禁止在 analyze 回调里直接改 Compose state。
- DB：Room suspend/Flow 在 IO；分析线程不直接阻塞等 DB；通过 Channel/actor 串行处理确认码。
- 网络：Retrofit suspend + OkHttp IO；token 不打印；UTC。
- 相机：退出页面 `unbindAll()`；`ScanController.release()` 幂等。

## 9. 验收与验证

1. `cd app; ./gradlew assembleDebug lintDebug testDebugUnitTest` 零错误（CI 同命令）。
2. APK 安装到 emulator-5554：`adb install -r app/build/outputs/apk/debug/app-debug.apk`；`am start` 后 `uiautomator dump`/截图确认首页渲染、无崩溃（`logcat -b crash` 为空）。
3. 单元测试清单：坐标映射（4 角度×4 角点）、Code128C 校验（纯数字+偶数长度）、去重逻辑、TrackManager、SyncBackoff、SyncEngine 状态矩阵、Room/仓库去重。
4. 本地联调：`tools/dev-server/server.py`（实现 docs/API.md 全端点 + `/admin` + CSV 导出）跑在 host 8000；`adb reverse tcp:8000 tcp:8000`；模拟器设置页 `http://127.0.0.1:8000` → 测试连通性 → 注册 →（扫描逻辑以仿真/单测覆盖，模拟器无真实后摄）→ 同步出站数据 → dev server `/admin` 可见合并条码与 scan_count。诊断导出 zip 可分享。
5. 不提交 `local.properties/*.keystore/build/`（已在 .gitignore 校验）。
6. 已知限制写入 README：真实相机无法在模拟器验收（需真机）、服务端仓库未随附（用 tools/dev-server 做契约联调）。

## 10. 模块协作约定

- 模块完成后记录：文件清单、公共 API 与契约差异、编译注意事项、测试清单。
- 编译错误按模块归属修复；构建配置变更需单独说明。
- 任何跨模块 API 改动必须先更新本文件并说明影响面。
- 禁止在 repo 里放临时脚本/日志/依赖缓存；研究资料不入库（可放仓库外的工作目录）。

## 11. 最终实施状态（交付时快照，2026-10-23）

- 构建配置以 `app/gradle/libs.versions.toml` / `app/build.gradle.kts` / `app/gradle.properties` 为准；本文件中的版本描述若与之一致则无需改动：
  - Gradle 8.14.6 · AGP 8.13.2 · Kotlin 2.3.21 · KSP 2.3.12 · Compose BOM 2026.06.01 · CameraX 1.6.2 · zxing-cpp 3.1.1 · Room 2.8.5 · work-runtime 2.12.0 · DataStore 1.3.0 · Retrofit 3.0.0 + converter-kotlinx-serialization · OkHttp 5.4.0（5.5+ 需要 compileSdk 37）· kotlinx-serialization-json 1.12.0 · coroutines 1.11.0。
  - `rootProject.name = "app"`，产物固定为 `app/build/outputs/apk/debug/app-debug.apk`；工程根即 application module（无 `:app` 子模块）。
- Windows 非 ASCII 路径的三项环境参数（`android.overridePathCheck=true`、`org.gradle.jvmargs=-Dfile.encoding=GBK`、JavaCompile/Test 的 UTF-8 设置）已固化在构建文件中，原因见 `app/gradle.properties` 注释与 README。
- 实现偏差（已与实际代码对齐）：
  1. `TransactionRunner` 为普通 interface（泛型 suspend 方法无法 SAM）；`SettingsAccess` / `SyncTrigger` 为新增可测抽象，生产由 `SettingsStore` / `SyncScheduler` 实现；
  2. scan 数据模型位于 `com.example.multiscan.scan.model`（UI 已按该包名适配）；
  3. `ScanRepository.retryOutbox(id)` 同时接受 sessionId 与 outbox id；
  4. `sessions` Flow 不含 open 会话；`HomeStats.pendingSync` 含 pending+uploading，`failed` 含 failed+auth_error；
  5. 413 拆批的派生 batch_id 规则：整批首段用 sessionId，拆分段用 `UUID.nameUUIDFromBytes("$sessionId|$from|$to")`（单测锁定）；
  6. API 明文 HTTP 需 `android:usesCleartextTraffic="true"`（局域网 `http://<host>:8000` 契约要求）；
  7. UI 额外增加进程级只读 `ScanDebugBus`（调试统计镜像，无 Context 泄漏）。
- 最终 CI 命令：`cd app && ./gradlew assembleDebug testDebugUnitTest lintDebug`（本地 Windows 需先安装 JDK 17 与 Android SDK 36；CI 用 setup-java + setup-android）。

## 13. 同步按钮 + 同步设置（v3，2026-10-23；data-sync + ui 必须实现）

> 用户反馈「同步不明不白」，要求：① 明确的「立即同步」按钮；② 同步设置；③ 明确同步是**单向上传（手机→服务器）**：
> 只上传本地扫描记录，**不下载、不覆盖、不删除**本地数据。服务端契约（docs/API.md）不变。

### 13.1 data-sync（`data/**` + data 单测）

1. **同步偏好**：`SettingsSnapshot` 增加 `autoSync: Boolean = true`、`syncWifiOnly: Boolean = false`；
   `SettingsAccess` 增加 `suspend fun setAutoSync(enabled: Boolean)`、`suspend fun setSyncWifiOnly(enabled: Boolean)`；
   `SettingsStore` 增加 DataStore key `auto_sync`（默认 true）/`sync_wifi_only`（默认 false）。切换仅 Wi-Fi 不清数据、不重注册。
2. **SyncTrigger / SyncScheduler** 接口扩展（旧调用点全部适配）：
   - `fun enqueueNow(wifiOnly: Boolean = false)`
   - `fun enqueueWithDelay(delayMillis: Long, wifiOnly: Boolean = false)`
   - `fun reapplyConstraints(wifiOnly: Boolean)`：取消同名唯一任务并按新约束重新入队（用于切换「仅 Wi-Fi」后立即生效；文档注明运行中任务会被取消）
   - 约束：`wifiOnly=true → NetworkType.UNMETERED`，否则 `NetworkType.CONNECTED`；唯一任务名 `sync-scans` 不变。
3. **自动同步开关语义**：`finishSession` / `sweepOpenSessions` / `AppContainer.initialize` 仅在
   `settings.current().autoSync == true` 时调度同步；手动同步不受 autoSync 限制。`SyncWorker` 延迟重试也按当前 `syncWifiOnly` 约束调度。
4. **手动同步 API**（放 `ScanRepository`，UI 直接调用）：
   ```kotlin
   sealed interface ManualSyncResult {
       data class Started(val pendingCount: Int) : ManualSyncResult
       data object UpToDate : ManualSyncResult
       data object NeedsRegistration : ManualSyncResult
       data class Error(val message: String) : ManualSyncResult
   }
   suspend fun requestManualSync(): ManualSyncResult
   ```
   实现：读 settings；无 token 或 deviceId 为空 → `NeedsRegistration`；`OutboxDao.resetPendingSchedule()`
   （`UPDATE outbox SET next_retry_at = NULL WHERE state = 'pending'`，让退避中的 pending 立即到期）；
   统计 pending 数；0 → `UpToDate`（不入队）；>0 → `syncScheduler.enqueueNow(wifiOnly = snapshot.syncWifiOnly)` → `Started(n)`。
   **不**自动重置 failed/auth_error（记录页已有「重试」）。异常 → `Error(中文消息)`。
5. **同步状态**：`SettingsSnapshot` 增加 `SYNC_RUNNING = "syncing"` 常量；`SettingsAccess` 增加 `suspend fun setSyncRunning()`
   （只把 status 置为 syncing）；`SyncWorker.doWork()` 开始时调用，SyncEngine 结束时仍写 success/failed/auth_error。
6. **单测（继续纯 JVM + fakes）**：`requestManualSync` 四种结果；`autoSync=false` 时 finishSession 不调度；
   `syncWifiOnly` 正确传给 trigger；现有 69 项测试保持全绿。更新 Fakes 以适配 SettingsAccess/SyncTrigger 新方法。

### 13.2 ui（`ui/**` + `MainActivity.kt` + `res/**`）

1. **Home 顶部连接/同步状态卡升级**：服务器地址、最近同步时间、状态文案（从未同步 / 同步中 / 同步成功 / 同步失败 / 登录失效）；
   有待同步批次时显示「待同步 N 条」；新增**「立即同步」按钮**（同步中禁用/显示"同步中…"），点击调用
   `container.scanRepository.requestManualSync()`（viewModelScope + 中文 Snackbar）：
   - Started(n) → 「已开始同步 n 条」
   - UpToDate → 「没有待同步记录」
   - NeedsRegistration → 「请先在设置页注册本机」
   - Error → 错误文案
2. **Settings 新增「同步设置」卡片**（服务器配置之后）：
   - 单向说明文案：「同步为单向：仅将本地扫描记录上传到服务器；不会从服务器下载、覆盖或删除本地数据。」
   - `Switch` 自动同步（扫描完成自动上传）→ `settingsStore.setAutoSync`
   - `Switch` 仅 Wi-Fi 同步 → `settingsStore.setSyncWifiOnly` + `container.syncScheduler.reapplyConstraints(wifiOnly)`
   - 状态行：待同步 N 条 / 最近同步时间 / 状态文案
   - 「立即同步」按钮（与 Home 同一套逻辑与结果提示）
3. 全部新增文案放 `res/values/strings.xml`；不新增依赖；lint 0 errors；不影响现有扫描/记录/调试功能。
4. 单向语义只做 UI 明示，不实现任何从服务器拉取/合并到本地的逻辑。



## 12. 实机识别率优化增补（2026-10-23，v2；scanner + ui 必须实现）

> 背景：用户在真机上扫屏幕 Code128 时识别率低。真实照片离线实验（项目内部识别率实验记录）
> 表明 1280×720 分析图里条码仅约 260px 宽，处于“轻微模糊即失败”临界；且现确认逻辑按连续帧计数，
> 漏一帧就清零，导致很少能确认出框。以下为 v2 增补，**不改变绿/灰语义、UNIQUE 去重与既有公开字段**。

1. **分辨率**：`Preview` 与 `ImageAnalysis` 统一目标 `1920×1080`（16:9），
   `ResolutionStrategy(Size(1920,1080), FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)`；
   保持 YUV_420_888 + KEEP_ONLY_LATEST；实际分析分辨率写入 debugStats。
2. **多 pass 解码**（`BarcodeDecoder`）：
   - pass1（规格参数）：`minLineCount=3, tryHarder=true, tryRotate=true, tryInvert=false, tryDownscale=false, LOCAL_AVERAGE`；
   - pass2（仅当 pass1 无有效结果）：`minLineCount=2, tryHarder=true, tryRotate=true, tryInvert=true, tryDenoise=true, GLOBAL_HISTOGRAM`；
   - reader 实例构造一次后**绝不修改 options**；两 pass 都在分析线程串行执行；`ImageProxy.use{}` 不变；
   - `DecodeResult` 增加 `passUsed: Int`（1/2/0=无结果）、`decodeMs` 为总耗时。
3. **确认逻辑容忍丢帧**（`TrackManager`）：同一 track 累计 **2 次同文本检测证据**即可确认；
   **未匹配帧不清零计数**（仅文本变化、track 过期时清零/新建）；TTL 仍为 500ms。
   测试需覆盖：漏帧后仍能确认、超过 TTL 不确认、文本变化重置。
4. **相机控制新增**（`CameraController` + `ScanController` 公开 API，UI 调用）：
   - `fun tapToFocus(viewX: Float, viewY: Float)`：用 `previewView.meteringPointFactory` +
     `cameraControl.startFocusAndMetering(FocusMeteringAction)`（IsContinuousAutoFocus 默认）；
   - `fun zoomBy(factor: Float)`：`setZoomRatio(current * factor)` 并 clamp 到 `[minZoomRatio, maxZoomRatio]`；
   - `fun toggleZoom()`：1x ↔ `min(2.0f, maxZoomRatio)`；
   - `ScanUiState` 增加 `zoomRatio: Float = 1f`；
   - UI：`pointerInput` 单击→`tapToFocus`；双击→`toggleZoom`；捏合（`detectTransformGestures`）→`zoomBy`；
     扫描页显示当前倍率（如 `2.0x`）与小字提示「点击对焦 · 双指缩放」。
5. **调试面板新增**：`ScanDebugStats` 增加 `decodedFrames: Long`（≥1 个 detections 的帧数）、
   `decodeHitRate: Float`（decodedFrames/analyzedFrames）、`analysisResolution: String`、`lastDecodePass: Int`；
   调试页展示「解码命中率 / 已解码帧 / 最近解码 pass」。
6. 约束：本轮**不改** data/**、Room/同步语义；不新增第三方依赖；不引入图像上采样/水印等额外处理。


