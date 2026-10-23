package com.example.multiscan.scan.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
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
 * 相机配置（全部使用非废弃 API）：
 *  - `Preview` / `ImageAnalysis` 均使用 `ResolutionSelector` + `ResolutionStrategy(1280x720,
 *    FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)`，并显式 16:9（`AspectRatioStrategy`），保证预览面
 *    与分析帧**宽高比一致**，`CoordinateMapper` 的 FILL_CENTER 映射才能与预览画面严格对齐；
 *  - ImageAnalysis：`STRATEGY_KEEP_ONLY_LATEST` + `OUTPUT_IMAGE_FORMAT_YUV_420_888`（zxing-cpp
 *    `read(ImageProxy)` 只接受 YUV，且只读 Y plane）；
 *  - `ProcessCameraProvider.getInstance(context)` + `addListener(..., mainExecutor)` 异步绑定；
 *  - 单线程分析 executor；`unbind()` 幂等：clearAnalyzer → unbindAll → shutdown executor，
 *    确保不泄漏相机与线程。
 *
 * `Preview.setSurfaceProvider(SurfaceProvider)` 在 CameraX 1.6.2 中仍是稳定非废弃 API
 * （`Preview.getSurfaceProvider()` 是 @RestrictTo(LIBRARY_GROUP)，不能当 Kotlin 属性用；
 * deprecated 的是 `setTargetResolution` / `setTargetAspectRatio`，本类均未使用）。
 *
 * 客户端只允许从主线程调用 [bind] / [unbind] / [setTorch]；回调：
 *  - [onFrame] 在分析线程执行，**由回调实现负责关闭 ImageProxy**（分析回调内部有兜底 close）；
 *  - [onCameraReady] / [onTorchAvailability] / [onTorchState] / [onError] 在主线程执行。
 */
class CameraController(
    context: Context,
    private val diagLog: DiagLog,
    private val onFrame: (ImageProxy) -> Unit,
    private val onCameraReady: () -> Unit,
    private val onTorchAvailability: (Boolean) -> Unit,
    private val onTorchState: (Boolean) -> Unit,
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

    /** 主线程调用：绑定后置主摄；同一实例只允许绑定一次（重绑请新建实例）。 */
    fun bind(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        if (stopped || analysis != null) return
        val generation = ++bindGeneration

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
                    onTorchAvailability(boundCamera.cameraInfo.hasFlashUnit())
                    onCameraReady()
                    diagLog.log(TAG, "相机绑定成功")
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
     * 统一分辨率策略：16:9 优先 + 1280x720 目标（不可用时按「最近的更高分辨率→更低」回退）。
     * Preview 与 ImageAnalysis 使用同一策略，避免两者宽高比不同导致锚点叠加错位。
     */
    private fun cameraResolutionSelector(): ResolutionSelector =
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(TARGET_WIDTH, TARGET_HEIGHT),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()

    companion object {
        private const val TAG = "Camera"

        /** 契约要求的目标分析分辨率。 */
        const val TARGET_WIDTH = 1280
        const val TARGET_HEIGHT = 720

        /** 相机初始化失败的简短 UI 文案。 */
        const val ERROR_CAMERA_INIT = "相机初始化失败"
    }
}
