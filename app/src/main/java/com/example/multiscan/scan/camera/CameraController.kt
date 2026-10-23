package com.example.multiscan.scan.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.multiscan.core.diag.DiagLog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX 绑定/解绑的唯一入口（Preview + ImageAnalysis，后置主摄）。
 *
 * 实机识别率优化（`docs/CONTRACT.md` §12 v2）：
 *  - `Preview` / `ImageAnalysis` 统一目标 **1920x1080（16:9）**，
 *    `ResolutionStrategy(Size(1920,1080), FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)`；
 *    设备不支持 1080p 时自动回退到「最近的更低分辨率，其次更高」，避免 bind 失败；
 *  - 显式 `AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY`，保证预览面与分析帧宽高比一致，
 *    `CoordinateMapper` 的 FILL_CENTER 映射才能与预览画面严格对齐；
 *  - ImageAnalysis：`STRATEGY_KEEP_ONLY_LATEST` + `OUTPUT_IMAGE_FORMAT_YUV_420_888`
 *    （zxing-cpp `read(ImageProxy)` 只接受 YUV，且只读 Y plane）；
 *  - 新增交互：点击对焦 [tapToFocus]、变焦 [setZoomRatio] / [zoomBy] / [toggleZoom]，
 *    以及实际分析分辨率 / 当前变焦倍率对外暴露。
 *
 * 其它：
 *  - `ProcessCameraProvider.getInstance(context)` + `addListener(..., mainExecutor)` 异步绑定；
 *  - 单线程分析 executor；`unbind()` 幂等：clearAnalyzer → unbindAll → shutdown executor，
 *    确保不泄漏相机与线程；
 *  - `Preview.setSurfaceProvider(SurfaceProvider)` 在 CameraX 1.6.2 中仍是稳定非废弃 API
 *    （`Preview.getSurfaceProvider()` 是 @RestrictTo(LIBRARY_GROUP)，不能当 Kotlin 属性用；
 *    deprecated 的是 `setTargetResolution` / `setTargetAspectRatio`，本类均未使用）。
 *
 * 客户端只允许从**主线程**调用 [bind] / [unbind] / [setTorch] / [tapToFocus] /
 * [setZoomRatio] / [zoomBy] / [toggleZoom]（CameraX CameraControl/MeteringPointFactory 要求主线程）。
 * 回调：
 *  - [onFrame] 在分析线程执行，**由回调实现负责关闭 ImageProxy**（分析回调内部有兜底 close）；
 *  - 其余回调（含 [onZoomChanged]）在主线程执行。
 */
class CameraController(
    context: Context,
    private val diagLog: DiagLog,
    private val onFrame: (ImageProxy) -> Unit,
    private val onCameraReady: () -> Unit,
    private val onTorchAvailability: (Boolean) -> Unit,
    private val onTorchState: (Boolean) -> Unit,
    private val onZoomChanged: (Float) -> Unit,
    private val onError: (String) -> Unit,
) {

    private val appContext: Context = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)

    private val analysisExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "multiscan-analysis") }

    @Volatile
    private var stopped = false

    @Volatile
    private var bindGeneration = 0

    @Volatile
    private var cameraProvider: ProcessCameraProvider? = null

    @Volatile
    private var camera: Camera? = null

    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null

    /** 绑定时的 PreviewView（[tapToFocus] 需要其 MeteringPointFactory）。 */
    @Volatile
    private var previewView: PreviewView? = null

    /** 绑定后 CameraX 实际选中的分析流分辨率（如 `1920x1080`）；未绑定/读取失败为 null。 */
    @Volatile
    var actualAnalysisResolution: String? = null
        private set

    /** 当前变焦倍率（主线程读取；未就绪时为 1f）。 */
    val zoomRatio: Float
        get() = currentZoomState()?.zoomRatio ?: 1f

    /** 相机支持的最小变焦倍率（主线程读取；未就绪时为 1f）。 */
    val minZoomRatio: Float
        get() = currentZoomState()?.minZoomRatio ?: 1f

    /** 相机支持的最大变焦倍率（主线程读取；未就绪时为 1f）。 */
    val maxZoomRatio: Float
        get() = currentZoomState()?.maxZoomRatio ?: 1f

    /** 主线程调用：绑定后置主摄；同一实例只允许绑定一次（重绑请新建实例）。 */
    fun bind(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        if (stopped || analysis != null) return
        val generation = ++bindGeneration
        this.previewView = previewView

        val previewUseCase = Preview.Builder()
            .setResolutionSelector(cameraResolutionSelector())
            .build()
        // 稳定 API（1.6.2 未废弃）；PreviewView 侧提供 SurfaceProvider。
        previewUseCase.setSurfaceProvider(previewView.surfaceProvider)

        val analysisUseCase = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setResolutionSelector(cameraResolutionSelector())
            .build()
        analysisUseCase.setAnalyzer(analysisExecutor) { proxy ->
            try {
                onFrame(proxy)
            } catch (t: Throwable) {
                // 兜底：回调异常时也必须关闭 ImageProxy，否则分析管线永久停摆。
                diagLog.log(TAG, "分析回调异常: ${t.javaClass.simpleName}")
                runCatching { proxy.close() }
            }
        }

        preview = previewUseCase
        analysis = analysisUseCase

        val providerFuture = ProcessCameraProvider.getInstance(appContext)
        providerFuture.addListener(
            {
                if (stopped || generation != bindGeneration) return@addListener
                try {
                    val provider = providerFuture.get()
                    cameraProvider = provider
                    val boundCamera = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        previewUseCase,
                        analysisUseCase,
                    )
                    camera = boundCamera
                    actualAnalysisResolution = analysisUseCase.resolutionInfo?.resolution?.let { size ->
                        "${size.width}x${size.height}"
                    }
                    onTorchAvailability(boundCamera.cameraInfo.hasFlashUnit())
                    notifyZoomRatio()
                    onCameraReady()
                    diagLog.log(
                        TAG,
                        "相机绑定成功: analysis=${actualAnalysisResolution ?: "?"}, zoom=${zoomRatio}",
                    )
                } catch (t: Throwable) {
                    // 无后摄 / 被占用 / 权限缺失等都会走到这里。
                    diagLog.log(TAG, "相机初始化失败: ${t.javaClass.simpleName}: ${t.message?.take(80) ?: ""}")
                    onError(ERROR_CAMERA_INIT)
                    unbind()
                }
            },
            mainExecutor,
        )
    }

    /** 主线程调用：解绑相机并释放分析线程；幂等。 */
    fun unbind() {
        if (stopped) return
        stopped = true
        bindGeneration++

        val analysisUseCase = analysis
        analysis = null
        runCatching { analysisUseCase?.clearAnalyzer() }

        val provider = cameraProvider
        cameraProvider = null
        camera = null
        runCatching { provider?.unbindAll() }

        preview = null
        previewView = null
        actualAnalysisResolution = null
        analysisExecutor.shutdown()
        diagLog.log(TAG, "相机已解绑")
    }

    /** 手电筒开关（无闪光灯/相机未就绪时通过 [onTorchState] 回传 false）。 */
    fun setTorch(enabled: Boolean) {
        val boundCamera = camera
        if (boundCamera == null) {
            onTorchState(false)
            return
        }
        if (!boundCamera.cameraInfo.hasFlashUnit()) {
            onTorchState(false)
            return
        }
        try {
            val future = boundCamera.cameraControl.enableTorch(enabled)
            future.addListener(
                {
                    runCatching { future.get() }
                        .onSuccess { onTorchState(enabled) }
                        .onFailure { t ->
                            diagLog.log(TAG, "手电筒切换失败: ${t.javaClass.simpleName}")
                            onTorchState(!enabled)
                        }
                },
                mainExecutor,
            )
        } catch (t: Exception) {
            diagLog.log(TAG, "手电筒切换异常: ${t.javaClass.simpleName}")
            onTorchState(!enabled)
        }
    }

    /**
     * 点击对焦：把 PreviewView 视图坐标（像素，相对 PreviewView 左上角）转成
     * MeteringPoint 并触发 AF。聚焦动作默认 5s 后自动取消，随后回到 CameraX 默认的
     * 连续自动对焦（IsContinuousAutoFocus）。
     */
    fun tapToFocus(viewX: Float, viewY: Float) {
        val boundCamera = camera ?: return
        val view = previewView ?: return
        if (stopped) return
        try {
            val point = view.meteringPointFactory.createPoint(viewX, viewY)
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).build()
            boundCamera.cameraControl.startFocusAndMetering(action)
            diagLog.log(TAG, "点击对焦 (${viewX.toInt()}, ${viewY.toInt()})")
        } catch (t: Exception) {
            diagLog.log(TAG, "点击对焦失败: ${t.javaClass.simpleName}")
        }
    }

    /** 设置绝对变焦倍率（自动 clamp 到 [minZoomRatio, maxZoomRatio]）。 */
    fun setZoomRatio(ratio: Float) {
        if (ratio.isNaN() || ratio.isInfinite()) return
        val boundCamera = camera ?: return
        val state = currentZoomState() ?: return
        val target = ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        try {
            val future = boundCamera.cameraControl.setZoomRatio(target)
            future.addListener(
                {
                    runCatching { future.get() }
                        .onSuccess { notifyZoomRatio() }
                        .onFailure { t -> diagLog.log(TAG, "变焦失败: ${t.javaClass.simpleName}") }
                },
                mainExecutor,
            )
        } catch (t: Exception) {
            diagLog.log(TAG, "变焦异常: ${t.javaClass.simpleName}")
        }
    }

    /** 相对变焦（双指缩放）：`current * factor`，自动 clamp。 */
    fun zoomBy(factor: Float) {
        if (factor <= 0f || factor.isNaN() || factor.isInfinite()) return
        val state = currentZoomState() ?: return
        setZoomRatio(state.zoomRatio * factor)
    }

    /** 双击切换：1x ↔ min(2.0x, maxZoomRatio)。 */
    fun toggleZoom() {
        val state = currentZoomState() ?: return
        val target = if (state.zoomRatio > ZOOM_TOGGLE_EPSILON) {
            1f
        } else {
            minOf(TOGGLE_ZOOM_TARGET, state.maxZoomRatio)
        }
        setZoomRatio(target)
    }

    /**
     * 统一分辨率策略：16:9 优先 + **1920x1080** 目标；
     * 不可用时按「最近的更低分辨率 → 更高」回退（FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER），
     * 从而在低端设备上也能成功 bind。Preview 与 ImageAnalysis 使用同一策略，
     * 避免两者宽高比不同导致锚点叠加错位。
     */
    private fun cameraResolutionSelector(): ResolutionSelector =
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(TARGET_WIDTH, TARGET_HEIGHT),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                ),
            )
            .build()

    /** 主线程读取 CameraX 的 ZoomState（LiveData.value 只能在主线程访问）。 */
    private fun currentZoomState() = camera?.cameraInfo?.zoomState?.value

    private fun notifyZoomRatio() {
        onZoomChanged(zoomRatio)
    }

    companion object {
        private const val TAG = "Camera"

        /** CONTRACT §12：目标分析/预览分辨率 1920x1080（16:9）。 */
        const val TARGET_WIDTH = 1920
        const val TARGET_HEIGHT = 1080

        /** 双击变焦目标（上限受设备 maxZoomRatio 约束）。 */
        private const val TOGGLE_ZOOM_TARGET = 2.0f

        /** 判断「当前是否已在 1x」的容差；大于该值双击回 1x。 */
        private const val ZOOM_TOGGLE_EPSILON = 1.05f

        /** 相机初始化失败的简短 UI 文案。 */
        const val ERROR_CAMERA_INIT = "相机初始化失败"
    }
}
