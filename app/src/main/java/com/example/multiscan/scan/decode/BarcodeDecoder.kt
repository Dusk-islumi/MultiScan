package com.example.multiscan.scan.decode

import android.graphics.Point
import android.graphics.Rect
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.example.multiscan.scan.mapper.RotationMath
import com.example.multiscan.scan.model.Detection
import com.example.multiscan.scan.model.FrameGeometry
import com.example.multiscan.scan.model.PointF
import zxingcpp.BarcodeReader
import zxingcpp.BarcodeReader.Binarizer
import zxingcpp.BarcodeReader.Format
import zxingcpp.BarcodeReader.Options
import zxingcpp.BarcodeReader.TextMode

/**
 * 单帧解码结果（scan 模块内部类型）。
 *
 * @param geometry    本帧分析图几何信息（buffer 宽高 + rotationDegrees）
 * @param bufferWidth ImageProxy buffer 宽（未旋转，便于调试面板显示实际分析分辨率）
 * @param decodeMs    本帧总解码耗时（pass1 + 可能触发的 pass2 + 坐标换算）
 * @param passUsed    本帧命中出处：1=规格参数 pass1，2=宽松回退 pass2，0=两 pass 均无结果
 */
internal data class DecodeResult(
    val detections: List<Detection>,
    val geometry: FrameGeometry,
    val bufferWidth: Int,
    val bufferHeight: Int,
    val decodeMs: Long,
    val passUsed: Int,
)

/**
 * zxing-cpp（`io.github.zxing-cpp:android:3.1.1`，package `zxingcpp`）的薄封装。
 *
 * 实机识别率优化（`docs/CONTRACT.md` §12）：采用 **双 pass 解码**，提高屏幕条码
 * （摩尔纹/轻微模糊/抖动场景）的单帧命中率：
 *  - **pass1（规格参数）**：`minLineCount=3, tryHarder, tryRotate, tryInvert=false,
 *    tryDownscale=false, LOCAL_AVERAGE` —— 误识别率最低，优先使用；
 *  - **pass2（宽松回退，仅当 pass1 无任何有效结果时）**：`minLineCount=2, tryHarder,
 *    tryRotate, tryInvert=true, tryDenoise=true, GLOBAL_HISTOGRAM` —— 代价更高但更容易
 *    解出模糊/反光/低对比度的小条码。
 *
 * 两个 [BarcodeReader] 实例都在本类构造时创建一次（构造函数会执行
 * `System.loadLibrary("zxingcpp_android")`），之后 **绝不修改 options**：
 * JNI 按字段名读取 options，运行中改 options 会造成撕裂读取（zxing-cpp 3.1.1：JNI 按字段名读取 options）。
 * 两个 pass 都在同一个分析线程上串行执行；`ImageProxy.use {}` 不变。
 *
 * 其它关键约束（同 v1）：
 *  - `read(ImageProxy)` 内部只读 Y plane，且 **不会关闭 ImageProxy**，调用方必须 `use {}`；
 *  - `Result.position` 角点位于「**旋转后的 crop 空间**」，必须用 [RotationMath.inverse]
 *    逆旋转回 ImageProxy buffer 像素坐标（cropRect 默认全图；若为 ROI 再补 offset）；
 *  - `Result.error` 仅在 `returnErrors = true` 时非空，这里保持 false，过滤只作防御。
 *
 * 线程模型：只允许在 CameraX 单线程分析 executor 上调用（内部加锁兜底）。
 */
internal class BarcodeDecoder {

    private val lock = Any()

    /** pass1：规格参数（CONTRACT §12 固定值）。 */
    private val primaryReader: BarcodeReader = BarcodeReader(
        Options(
            formats = setOf(Format.CODE_128),
            tryHarder = true,
            tryRotate = true,
            tryInvert = false,
            tryDownscale = false,
            minLineCount = PRIMARY_MIN_LINE_COUNT,
            maxNumberOfSymbols = MAX_NUMBER_OF_SYMBOLS,
            // returnErrors = false（默认值）：只有显式开启时 native 才会返回 error 条目；
            // 本 App 只要成功解出的码，因此保持关闭，避免误把 error 结果入库。
            returnErrors = false,
            binarizer = Binarizer.LOCAL_AVERAGE,
            textMode = TextMode.PLAIN,
        ),
    )

    /** pass2：仅当 pass1 无结果时使用的宽松回退（CONTRACT §12 固定值）。 */
    private val fallbackReader: BarcodeReader = BarcodeReader(
        Options(
            formats = setOf(Format.CODE_128),
            tryHarder = true,
            tryRotate = true,
            tryInvert = true,
            tryDownscale = false,
            tryDenoise = true,
            minLineCount = FALLBACK_MIN_LINE_COUNT,
            maxNumberOfSymbols = MAX_NUMBER_OF_SYMBOLS,
            returnErrors = false,
            binarizer = Binarizer.GLOBAL_HISTOGRAM,
            textMode = TextMode.PLAIN,
        ),
    )

    /**
     * 解码一帧（pass1 → 必要时 pass2）。**本方法负责关闭 [proxy]**（成功/异常均关闭）。
     *
     * @throws RuntimeException zxing 原生层异常（由调用方捕获并记录 diagLog，继续下一帧）
     */
    internal fun decode(proxy: ImageProxy): DecodeResult = synchronized(lock) {
        val startedNanos = SystemClock.elapsedRealtimeNanos()
        proxy.use { image ->
            // 必须在 close 前读完所有 ImageProxy 元数据。
            val crop: Rect = image.cropRect
            val cropWidth = crop.width()
            val cropHeight = crop.height()
            val rotation = RotationMath.normalizeDegrees(image.imageInfo.rotationDegrees)

            // pass1：规格参数，优先保证准确率。
            var detections = decodeWith(primaryReader, image, crop, cropWidth, cropHeight, rotation)
            var passUsed = if (detections.isNotEmpty()) PASS_PRIMARY else PASS_NONE

            // pass2：只有 pass1 完全没有有效结果时才放宽参数再试一次（更慢，但提升弱码命中率）。
            if (detections.isEmpty()) {
                detections = decodeWith(fallbackReader, image, crop, cropWidth, cropHeight, rotation)
                if (detections.isNotEmpty()) passUsed = PASS_FALLBACK
            }

            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedNanos) / NANOS_PER_MILLI
            DecodeResult(
                detections = detections,
                geometry = FrameGeometry(
                    imageWidth = image.width,
                    imageHeight = image.height,
                    rotationDegrees = rotation,
                ),
                bufferWidth = image.width,
                bufferHeight = image.height,
                decodeMs = elapsedMs,
                passUsed = passUsed,
            )
        }
    }

    /** 用指定 reader 跑一次识别并把角点逆旋转回 buffer 空间。 */
    private fun decodeWith(
        reader: BarcodeReader,
        image: ImageProxy,
        crop: Rect,
        cropWidth: Int,
        cropHeight: Int,
        rotationDegrees: Int,
    ): List<Detection> {
        val rawResults = reader.read(image)
        val detections = ArrayList<Detection>(rawResults.size)
        for (result in rawResults) {
            val text = result.text
            // 防御：text 可能为 null（非有效结果），空串同样丢弃。
            if (text.isNullOrBlank()) continue
            if (result.error != null) continue
            val position = result.position
            detections += Detection(
                text = text,
                format = FORMAT_CODE128,
                symbologyIdentifier = result.symbologyIdentifier,
                // DB 契约要求 line_count >= 1。
                lineCount = result.lineCount.coerceAtLeast(1),
                cornersInBufferSpace = listOf(
                    toBufferSpace(position.topLeft, crop, cropWidth, cropHeight, rotationDegrees),
                    toBufferSpace(position.topRight, crop, cropWidth, cropHeight, rotationDegrees),
                    toBufferSpace(position.bottomRight, crop, cropWidth, cropHeight, rotationDegrees),
                    toBufferSpace(position.bottomLeft, crop, cropWidth, cropHeight, rotationDegrees),
                ),
            )
        }
        return detections
    }

    /** rotated-crop 整数角点 → buffer 像素坐标（crop 局部逆旋转后再加 crop 偏移）。 */
    private fun toBufferSpace(
        point: Point,
        crop: Rect,
        cropWidth: Int,
        cropHeight: Int,
        rotationDegrees: Int,
    ): PointF {
        val local = RotationMath.inverse(
            point = PointF(point.x.toFloat(), point.y.toFloat()),
            bufferWidth = cropWidth,
            bufferHeight = cropHeight,
            rotationDegrees = rotationDegrees,
        )
        return PointF(local.x + crop.left, local.y + crop.top)
    }

    companion object {
        /** DB / 上传契约使用的格式名（固定 Code128）。 */
        const val FORMAT_CODE128: String = "Code128"

        /** 本帧命中 pass1（规格参数）。 */
        const val PASS_PRIMARY: Int = 1

        /** 本帧命中 pass2（宽松回退）。 */
        const val PASS_FALLBACK: Int = 2

        /** 两个 pass 都没有有效结果。 */
        const val PASS_NONE: Int = 0

        private const val PRIMARY_MIN_LINE_COUNT = 3
        private const val FALLBACK_MIN_LINE_COUNT = 2
        private const val MAX_NUMBER_OF_SYMBOLS = 64
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
