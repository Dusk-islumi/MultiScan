package com.example.multiscan.scan

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.example.multiscan.core.contracts.ScanSessionStore
import com.example.multiscan.core.diag.DiagLog
import com.example.multiscan.core.time.Utc
import com.example.multiscan.scan.camera.CameraController
import com.example.multiscan.scan.decode.BarcodeDecoder
import com.example.multiscan.scan.decode.DecodeResult
import com.example.multiscan.scan.dedup.SessionCodeIndex
import com.example.multiscan.scan.model.FrameGeometry
import com.example.multiscan.scan.model.PointF
import com.example.multiscan.scan.model.ScanAnchor
import com.example.multiscan.scan.model.ScanDebugStats
import com.example.multiscan.scan.model.ScanSessionSummary
import com.example.multiscan.scan.model.ScanUiState
import com.example.multiscan.scan.track.TrackManager
import com.example.multiscan.scan.track.TrackUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 扫描页核心控制器（对 UI 的稳定公共 API，严格对应 `docs/CONTRACT.md` §5）。
 *
 * 逐帧流程（CameraX 单线程分析 executor）：
 *  1. 主动丢帧：距上次分析开始 `< 50ms` 的帧直接 `close()` 并计 `droppedFrames`；
 *  2. [BarcodeDecoder.decode]：关闭 ImageProxy、逆旋转角点到 buffer 空间、统计解码耗时；
 *  3. [TrackManager.update]：IoU/中心距匹配 → 连续 2 帧同文本才 confirmed → 500ms 过期；
 *  4. 对**新确认** track 逐个做去重分类：内存命中 → 立即上色；未命中 → 投递到单一 actor，
 *     actor 在 `Dispatchers.IO` 上串行调用 `store.registerCode`（INSERT OR IGNORE），保证写库顺序
 *     确定且无并发 Room 写；
 *  5. `uiState` / `debugStats` 节流到约 15Hz 发布（66ms），禁止每帧刷新 Compose 状态。
 *
 * 线程/协程：
 *  - 相机分析回调在 CameraX 单线程 executor；`frameLock` 保证 bind/unbind/finish 与分析帧之间互斥；
 *  - DB 调用全部在 `Dispatchers.IO`；`scanScope` 是 controller 自带的协程作用域，[release] 取消；
 *  - [uiState]/[debugStats] 是线程安全 StateFlow，可从任意线程更新。
 *
 * 注意：查询 token/secret 一律不记录；错误日志只记异常类名/简短消息。
 */
class ScanController(
    context: Context,
    private val store: ScanSessionStore,
    private val diagLog: DiagLog,
) {

    private val appContext: Context = context.applicationContext

    /** 全局唯一 reader（构造时执行 System.loadLibrary）；整个 controller 生命周期复用。 */
    private val decoder = BarcodeDecoder()

    private val scanScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    private val _debugStats = MutableStateFlow(ScanDebugStats())
    val debugStats: StateFlow<ScanDebugStats> = _debugStats.asStateFlow()

    // ---------------------------------------------------------------- 会话

    private val sessionLock = Any()
    private val sessionStartMutex = Mutex()
    private val finishMutex = Mutex()

    private var sessionId: String? = null
    private var sessionDeferred: CompletableDeferred<String>? = null
    private var lastSummary: ScanSessionSummary? = null

    // ---------------------------------------------------------------- 去重/统计

    private var codeIndex = SessionCodeIndex()
    private val totalDetected = AtomicInteger()
    private val newDetected = AtomicInteger()

    /** 已确认 track 的叠加信息；`isNew == null` 表示分类尚未完成（暂不上屏）。 */
    private data class AnchorEntry(
        val text: String,
        val quad: List<PointF>,
        val isNew: Boolean?,
    )

    private val anchorEntries = ConcurrentHashMap<Long, AnchorEntry>()

    // ---------------------------------------------------------------- 帧处理

    private val frameLock = Any()

    @Volatile
    private var acceptingFrames = false

    @Volatile
    private var released = false

    private var lastAnalysisStartMs = 0L
    private var droppedFrames = 0L
    private var analyzedFrames = 0L

    private var lastDecodeMs = 0L
    private var decodeSumMs = 0L
    private var decodeCount = 0L

    private var fps = 0f
    private var fpsWindowStartMs = 0L
    private var fpsWindowFrames = 0

    @Volatile
    private var latestGeometry: FrameGeometry? = null
    private var cameraResolution = ""

    /** v2 调试统计：解出 ≥1 个码的帧数 / 实际分析分辨率 / 最近一帧的解码 pass。 */
    private var decodedFrames = 0L
    private var analysisResolution = ""
    private var lastDecodePass = BarcodeDecoder.PASS_NONE

    private var lastUiPublishMs = 0L

    private var cameraController: CameraController? = null
    private var trackManager = TrackManager(clock = { SystemClock.elapsedRealtime() })

    // ---------------------------------------------------------------- 串行写库 actor

    private data class ConfirmationRequest(
        val trackId: Long,
        val code: String,
        val symbologyIdentifier: String?,
        val lineCount: Int,
    )

    private val actorLock = Any()

    @Volatile
    private var actorChannel: Channel<ConfirmationRequest>? = null

    @Volatile
    private var actorJob: Job? = null

    // ---------------------------------------------------------------- 公共 API

    /**
     * 扫描页 onStart：创建 DB session（内部切 IO）。可重复调用（已开则 no-op）。
     */
    suspend fun startSession() {
        sessionStartMutex.withLock {
            if (synchronized(sessionLock) { sessionId } != null || released) return@withLock

            val deferred = CompletableDeferred<String>()
            synchronized(sessionLock) {
                sessionDeferred = deferred
                lastSummary = null
            }
            codeIndex = SessionCodeIndex()
            anchorEntries.clear()
            totalDetected.set(0)
            newDetected.set(0)
            ensureActor()

            val started = try {
                withContext(Dispatchers.IO) { store.startSession(Utc.nowIso()) }
            } catch (ce: CancellationException) {
                deferred.completeExceptionally(ce)
                throw ce
            } catch (t: Exception) {
                // 会话创建失败向上抛：UI 的 runCatching 会显示错误并允许重试（sessionId 仍为 null，
                // 下次 startSession 会重新创建；actor 会因 deferred 异常而丢弃本次确认）。
                diagLog.log(TAG, "创建会话失败: ${t.javaClass.simpleName}")
                _uiState.update { it.copy(error = ERROR_SESSION_START) }
                deferred.completeExceptionally(t)
                throw t
            }

            synchronized(sessionLock) { sessionId = started }
            deferred.complete(started)
            _uiState.update {
                it.copy(
                    sessionId = started,
                    anchors = emptyList(),
                    totalDetected = 0,
                    newDetected = 0,
                    error = null,
                )
            }
            diagLog.log(TAG, "扫描会话开始")
        }
    }

    /**
     * 主线程调用：绑定 CameraX Preview + ImageAnalysis。
     * 重复调用会先解绑旧相机（幂等）。
     */
    fun bind(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        if (released) return
        unbind()
        // 等待上一轮分析帧彻底退出，避免新 trackManager 与旧帧竞争。
        synchronized(frameLock) { /* barrier */ }

        acceptingFrames = true
        latestGeometry = null
        resetFrameStats()
        trackManager = TrackManager(clock = { SystemClock.elapsedRealtime() })

        _uiState.update { it.copy(cameraReady = false, error = null, torchOn = false, zoomRatio = 1f) }

        val controller = CameraController(
            context = appContext,
            diagLog = diagLog,
            onFrame = ::analyzeFrame,
            onCameraReady = {
                _uiState.update { it.copy(cameraReady = true, error = null) }
                diagLog.log(TAG, "相机就绪")
            },
            onTorchAvailability = { available ->
                _uiState.update { it.copy(torchAvailable = available, torchOn = if (available) it.torchOn else false) }
            },
            onTorchState = { on ->
                _uiState.update { it.copy(torchOn = on) }
            },
            onZoomChanged = { ratio ->
                _uiState.update { it.copy(zoomRatio = ratio) }
            },
            onError = { message ->
                _uiState.update { it.copy(cameraReady = false, error = message) }
            },
        )
        cameraController = controller
        controller.bind(previewView, lifecycleOwner)
    }

    /**
     * 主线程调用：解绑相机（离开页面必调），保留会话/actor 以便 finishSession 排空写库。
     */
    fun unbind() {
        acceptingFrames = false
        val controller = cameraController
        cameraController = null
        controller?.unbind()
        _uiState.update {
            it.copy(cameraReady = false, torchOn = false, torchAvailable = false, zoomRatio = 1f)
        }
    }

    /** 手电筒开关；不可用时保持关闭并由 uiState.torchAvailable 反映。 */
    fun setTorch(enabled: Boolean) {
        if (released) return
        val controller = cameraController
        if (controller == null || !_uiState.value.torchAvailable) {
            _uiState.update { it.copy(torchOn = false) }
            return
        }
        // 先乐观更新，CameraControl 回调会校正失败场景。
        _uiState.update { it.copy(torchOn = enabled) }
        controller.setTorch(enabled)
    }

    /**
     * 主线程调用：点击对焦。坐标是 **PreviewView 内的视图像素坐标**（相对 PreviewView 左上角），
     * 由 UI 的 pointerInput 点击位置传入。
     */
    fun tapToFocus(viewX: Float, viewY: Float) {
        if (released) return
        cameraController?.tapToFocus(viewX, viewY)
    }

    /** 主线程调用：双指缩放（相对倍率，内部 clamp 到设备 min/max 变焦范围）。 */
    fun zoomBy(factor: Float) {
        if (released) return
        cameraController?.zoomBy(factor)
    }

    /** 主线程调用：双击切换 1x ↔ min(2.0x, maxZoomRatio)。 */
    fun toggleZoom() {
        if (released) return
        cameraController?.toggleZoom()
    }

    /**
     * 结束会话：停止分析 → 等待已确认码写库排空 → `store.finishSession`（写 outbox + 触发同步）。
     * 幂等：重复调用返回首次的 summary。
     */
    suspend fun finishSession(): ScanSessionSummary = finishMutex.withLock {
        acceptingFrames = false
        // 等待分析线程上正在处理的一帧结束（不丢最后一批评分）。
        withContext(Dispatchers.IO) { synchronized(frameLock) { /* barrier */ } }

        val currentId = synchronized(sessionLock) { sessionId }
        if (currentId == null) {
            val cached = lastSummary
            if (cached != null) {
                return@withLock cached
            }
            diagLog.log(TAG, "结束会话时未找到 session")
            return@withLock ScanSessionSummary("", totalDetected.get(), newDetected.get())
        }
        val id: String = currentId

        // 关闭 actor 并等待其把队列中的确认码全部写完（此时 sessionId 仍在，actor 才能拿到）。
        val channelAndJob = synchronized(actorLock) {
            val channel = actorChannel
            val job = actorJob
            actorChannel = null
            actorJob = null
            channel?.close()
            channel to job
        }
        channelAndJob.second?.join()

        try {
            withContext(Dispatchers.IO) { store.finishSession(id, Utc.nowIso()) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Exception) {
            diagLog.log(TAG, "结束会话失败: ${t.javaClass.simpleName}")
            _uiState.update { it.copy(error = ERROR_SESSION_FINISH) }
        }

        val summary = ScanSessionSummary(id, totalDetected.get(), newDetected.get())
        synchronized(sessionLock) {
            sessionId = null
            sessionDeferred = null
            lastSummary = summary
        }
        diagLog.log(TAG, "会话结束: total=${summary.total}, new=${summary.newCount}")
        summary
    }

    /**
     * 页面销毁兜底：unbind + 关闭协程作用域/分析线程；**不结束会话**
     * （未 finish 的 open 会话由 App 启动 sweeper 收尾）。幂等。
     */
    fun release() {
        if (released) return
        released = true
        acceptingFrames = false

        val controller = cameraController
        cameraController = null
        controller?.unbind()

        val job = synchronized(actorLock) {
            val running = actorJob
            actorJob = null
            actorChannel?.close()
            actorChannel = null
            running
        }
        job?.cancel()
        scanScope.cancel()
        diagLog.log(TAG, "扫描资源已释放")
    }

    // ---------------------------------------------------------------- 帧处理（分析线程）

    /** CameraX 分析线程回调；本方法负责关闭 [ImageProxy]。 */
    private fun analyzeFrame(proxy: ImageProxy) {
        if (released) {
            runCatching { proxy.close() }
            return
        }
        synchronized(frameLock) {
            if (!acceptingFrames || released) {
                runCatching { proxy.close() }
                return
            }
            val now = SystemClock.elapsedRealtime()
            val lastStart = lastAnalysisStartMs
            if (lastStart != 0L && now - lastStart < MIN_ANALYSIS_INTERVAL_MS) {
                // 距上次分析开始不足 50ms：主动丢帧（相机侧 KEEP_ONLY_LATEST 也会自然丢帧）。
                droppedFrames += 1
                runCatching { proxy.close() }
                publishUi(now)
                return
            }
            lastAnalysisStartMs = now
            analyzedFrames += 1

            val result: DecodeResult? = try {
                decoder.decode(proxy) // 内部 proxy.use{}，成功/异常都会 close
            } catch (t: Exception) {
                diagLog.log(TAG, "解码异常: ${t.javaClass.simpleName}")
                null
            }
            if (result != null) {
                processDecodedFrame(now, result)
            }
            publishUi(now)
        }
    }

    private fun processDecodedFrame(nowMs: Long, result: DecodeResult) {
        lastDecodeMs = result.decodeMs
        decodeSumMs += result.decodeMs
        decodeCount += 1
        latestGeometry = result.geometry
        lastDecodePass = result.passUsed
        if (result.detections.isNotEmpty()) decodedFrames += 1
        if (cameraResolution.isEmpty()) {
            cameraResolution = "${result.bufferWidth}x${result.bufferHeight}"
        }
        if (analysisResolution.isEmpty()) {
            // 首帧 buffer 尺寸即 CameraX 实际选中的分析分辨率（如 1920x1080）。
            analysisResolution = "${result.bufferWidth}x${result.bufferHeight}"
        }
        updateFps(nowMs)

        val update = trackManager.update(result.detections)
        syncAnchors(update)
        handleNewlyConfirmed(update)
    }

    private fun updateFps(nowMs: Long) {
        if (fpsWindowStartMs == 0L) fpsWindowStartMs = nowMs
        fpsWindowFrames += 1
        val elapsed = nowMs - fpsWindowStartMs
        if (elapsed >= FPS_WINDOW_MS) {
            fps = fpsWindowFrames * 1000f / elapsed
            fpsWindowStartMs = nowMs
            fpsWindowFrames = 0
        }
    }

    /** 每次 bind 新相机时清零帧统计（调试面板显示本轮扫描的实时数据）。 */
    private fun resetFrameStats() {
        lastAnalysisStartMs = 0L
        droppedFrames = 0L
        analyzedFrames = 0L
        decodedFrames = 0L
        lastDecodeMs = 0L
        decodeSumMs = 0L
        decodeCount = 0L
        fps = 0f
        fpsWindowStartMs = 0L
        fpsWindowFrames = 0
        cameraResolution = ""
        analysisResolution = ""
        lastDecodePass = BarcodeDecoder.PASS_NONE
    }

    /** 确认 track → 锚点条目（四边形跟随最新一帧；保留已完成的分类颜色）。 */
    private fun syncAnchors(update: TrackUpdate) {
        val confirmedIds = HashSet<Long>()
        for (track in update.tracks) {
            if (!track.confirmed) continue
            confirmedIds += track.id
            // ConcurrentHashMap.compute 对单个 key 原子：actor 线程设置颜色不会被本帧覆盖。
            anchorEntries.compute(track.id) { _, old ->
                AnchorEntry(text = track.text, quad = track.quad, isNew = old?.isNew)
            }
        }
        if (confirmedIds.isEmpty()) {
            anchorEntries.clear()
        } else {
            anchorEntries.keys.retainAll(confirmedIds)
        }
    }

    private fun handleNewlyConfirmed(update: TrackUpdate) {
        for (track in update.newlyConfirmed) {
            val cached = codeIndex.cachedIsNew(track.text)
            if (cached != null) {
                // 本会话已登记过该码（例如 track 过期后重新确认）：再次出现一律灰框，不重复计数。
                anchorEntries.computeIfPresent(track.id) { _, entry -> entry.copy(isNew = false) }
                continue
            }
            val channel = actorChannel
            val sent = channel?.trySend(
                ConfirmationRequest(
                    trackId = track.id,
                    code = track.text,
                    symbologyIdentifier = track.symbologyIdentifier,
                    lineCount = track.lineCount,
                ),
            )?.isSuccess == true
            if (!sent) {
                // finish/release 已经关闭队列：这一帧的确认来不及入库，丢弃即可（会话已结束）。
                diagLog.log(TAG, "分类队列已关闭，跳过确认码")
            }
        }
    }

    // ---------------------------------------------------------------- 串行写库 actor

    private fun ensureActor() {
        synchronized(actorLock) {
            if (actorJob?.isActive == true) return
            val channel = Channel<ConfirmationRequest>(Channel.UNLIMITED)
            actorChannel = channel
            actorJob = scanScope.launch(Dispatchers.IO) {
                // receiveCatching：channel.close() 后先把队列里剩余请求消费完再退出。
                while (true) {
                    val next = channel.receiveCatching()
                    if (next.isClosed) break
                    val request = next.getOrNull() ?: continue
                    try {
                        handleConfirmation(request)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        // 单条确认失败不影响后续；DB 层已由 INSERT OR IGNORE 兜底。
                        diagLog.log(TAG, "确认码处理失败: ${t.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    private suspend fun handleConfirmation(request: ConfirmationRequest) {
        val sid = awaitSessionId() ?: return

        // 内存优先：同一 code 全会话只查库一次；本会话已登记过的码再次出现一律灰框。
        val cached = codeIndex.cachedIsNew(request.code)
        if (cached != null) {
            applyClassification(request, isNew = false, firstSeenInSession = false)
            return
        }

        val classification = codeIndex.classify(request.code) { code ->
            store.registerCode(
                sessionId = sid,
                code = code,
                format = BarcodeDecoder.FORMAT_CODE128,
                symbologyId = request.symbologyIdentifier,
                lineCount = request.lineCount,
                firstSeenAtUtc = Utc.nowIso(),
            )
        }
        applyClassification(
            request = request,
            isNew = classification.isNew,
            firstSeenInSession = !classification.fromCache,
        )
    }

    private suspend fun awaitSessionId(): String? {
        synchronized(sessionLock) { sessionId }?.let { return it }
        val deferred = synchronized(sessionLock) { sessionDeferred } ?: return null
        return withTimeoutOrNull(SESSION_WAIT_TIMEOUT_MS) { deferred.await() }
    }

    private fun applyClassification(
        request: ConfirmationRequest,
        isNew: Boolean,
        firstSeenInSession: Boolean,
    ) {
        anchorEntries.computeIfPresent(request.trackId) { _, entry -> entry.copy(isNew = isNew) }
        if (firstSeenInSession) {
            totalDetected.incrementAndGet()
            if (isNew) newDetected.incrementAndGet()
        }
        // 下一次分析帧的 publishUi（≤66ms 内）会把颜色变化带上屏，这里不做额外刷新。
    }

    // ---------------------------------------------------------------- UI 发布（节流）

    private fun publishUi(nowMs: Long) {
        if (nowMs - lastUiPublishMs < UI_PUBLISH_INTERVAL_MS) return
        lastUiPublishMs = nowMs

        val anchors = ArrayList<ScanAnchor>(anchorEntries.size)
        for (entry in anchorEntries.values) {
            val isNew = entry.isNew ?: continue // 分类未完成：先不上屏（只有绿/灰两种锚点）。
            anchors += ScanAnchor(text = entry.text, quad = entry.quad, isNew = isNew)
        }
        val geometry = latestGeometry
        _uiState.update {
            it.copy(
                anchors = anchors,
                totalDetected = totalDetected.get(),
                newDetected = newDetected.get(),
                geometry = geometry,
            )
        }

        val avgDecodeMs = if (decodeCount > 0L) decodeSumMs / decodeCount else 0L
        val hitRate = if (analyzedFrames > 0L) decodedFrames.toFloat() / analyzedFrames else 0f
        _debugStats.update {
            it.copy(
                fps = fps,
                lastDecodeMs = lastDecodeMs,
                avgDecodeMs = avgDecodeMs,
                activeTracks = trackManager.activeTrackCount(),
                droppedFrames = droppedFrames,
                analyzedFrames = analyzedFrames,
                cameraResolution = cameraResolution,
                decodedFrames = decodedFrames,
                decodeHitRate = hitRate,
                analysisResolution = analysisResolution,
                lastDecodePass = lastDecodePass,
            )
        }
    }

    companion object {
        private const val TAG = "Scan"

        /** 主动丢帧阈值：距上次分析开始 < 50ms 的帧跳过。 */
        private const val MIN_ANALYSIS_INTERVAL_MS = 50L

        /** UI/调试状态发布间隔：约 15Hz（契约要求 10–15Hz）。 */
        private const val UI_PUBLISH_INTERVAL_MS = 66L

        /** FPS 统计窗口。 */
        private const val FPS_WINDOW_MS = 1_000L

        /** actor 等待 sessionId 的上限；正常 startSession 在毫秒级完成。 */
        private const val SESSION_WAIT_TIMEOUT_MS = 3_000L

        const val ERROR_SESSION_START = "会话初始化失败"
        const val ERROR_SESSION_FINISH = "结束会话失败"
    }
}
