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
import zxingcpp.BarcodeReader.Format
import zxingcpp.BarcodeReader.Options
import zxingcpp.BarcodeReader.TextMode

/**
 * 单帧解码结果（scan 模块内部类型）。
 *
 * @param geometry    本帧分析图几何信息（buffer 宽高 + rotationDegrees）
 * @param bufferWidth ImageProxy buffer 宽（未旋转，便于调试面板显示相机分辨率）
 */
internal data class DecodeResult(
    val detections: List<Detection>,
    val geometry: FrameGeometry,
    val bufferWidth: Int,
    val bufferHeight: Int,
    val decodeMs: Long,
)

/**
 * zxing-cpp（`io.github.zxing-cpp:android:3.1.1`，package `zxingcpp`）的薄封装。
 *
 * 关键约束（见 `.coord/research/zxing-api.md`）：
 *  - [BarcodeReader] 构造函数里会执行 `System.loadLibrary("zxingcpp_android")`，**全程只允许创建一次**；
 *    本类由 `ScanController` 在构造时创建并复用，绝不能每帧 new。
 *  - `read(ImageProxy)` 内部只读 Y plane，且 **不会关闭 ImageProxy**，调用方必须 `use {}`；
 *    这里用 `proxy.use { reader.read(it) }` 保证任何异常路径都会释放 buffer，否则分析流会被永久卡住。
 *  - `Result.position` 的 4 个角点是 **android.graphics.Point（整数）**，位于
 *    「**旋转后的 crop 空间**」：zxing 内部先按 `image.cropRect` 裁剪，再按
 *    `imageInfo.rotationDegrees` 顺时针旋转后才识别（`ZXingCpp.cpp` / `ImageView.h` 实测结论）。
 *    因此必须用 [RotationMath.inverse] 逆旋转回 ImageProxy buffer 像素坐标，
 *    UI 侧的 `CoordinateMapper` 只做一次正旋转，避免双重旋转导致锚点错位。
 *  - `Result.error` 仅在 `Options.returnErrors = true` 时非空；这里保持 false，
 *    下面的 `error == null` 过滤只是防御性兜底。
 *  - 坐标公式按「cropRect 为全图」实现；若上层设置了 ROI，则先算 crop 局部坐标再加
 *    `cropRect.left/top` 偏移（`ImageProxy.cropRect` 默认就是全图）。
 *
 * 线程模型：只允许在 CameraX 单线程分析 executor 上调用（内部再加锁兜底，防止 bind/unbind
 * 交替瞬间出现并发调用；`reader.options` 构造后不再修改）。
 */
internal class BarcodeDecoder {

    private val lock = Any()

    private val reader: BarcodeReader = BarcodeReader(
        Options(
            formats = setOf(Format.CODE_128),
            tryHarder = true,
            tryRotate = true,
            tryInvert = false,
            tryDownscale = false,
            minLineCount = 3,
            maxNumberOfSymbols = 64,
            // returnErrors = false（默认值）：只有显式开启时 native 才会返回 error 条目；
            // 本 App 只要成功解出的码，因此保持关闭，避免误把 error 结果入库。
            returnErrors = false,
            textMode = TextMode.PLAIN,
        ),
    )

    /**
     * 解码一帧。**本方法负责关闭 [proxy]**（成功/异常均关闭）。
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
                        toBufferSpace(position.topLeft, crop, cropWidth, cropHeight, rotation),
                        toBufferSpace(position.topRight, crop, cropWidth, cropHeight, rotation),
                        toBufferSpace(position.bottomRight, crop, cropWidth, cropHeight, rotation),
                        toBufferSpace(position.bottomLeft, crop, cropWidth, cropHeight, rotation),
                    ),
                )
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
            )
        }
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

        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
