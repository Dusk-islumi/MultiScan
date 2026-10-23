package com.example.multiscan.scan.mapper

import com.example.multiscan.scan.model.PointF

/**
 * 旋转坐标换算（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 坐标空间：
 *  - **buffer 空间**：`ImageProxy` 原始像素坐标，尺寸 `(bufferWidth, bufferHeight)`，未旋转；
 *  - **rotated 空间**：把 buffer **顺时针**旋转 `rotationDegrees` 后的「竖直画面」坐标，
 *    90/270 时宽高互换。
 *
 * 公式来自 `docs/CONTRACT.md` §5.1（像素索引，`w/h` = buffer 宽高，顺时针 CW）：
 *  - 正旋转（buffer→rotated，`q` 为 buffer 坐标）：
 *    `0:(q.x,q.y)`；`90:(h-1-q.y,q.x)`；`180:(w-1-q.x,h-1-q.y)`；`270:(q.y,w-1-q.x)`。
 *  - 逆旋转（rotated→buffer，`p` 为 rotated 坐标）：
 *    `0:(p.x,p.y)`；`90:(p.y,h-1-p.x)`；`180:(w-1-p.x,h-1-p.y)`；`270:(w-1-p.y,p.x)`。
 *  - rotated 尺寸：`0/180=(w,h)`；`90/270=(h,w)`。
 *
 * `BarcodeDecoder` 用 [inverse] 把 zxing-cpp 的「旋转后 crop 空间」角点转回 buffer 空间；
 * `CoordinateMapper` 用 [forward] 把 buffer 坐标转成「竖直画面」坐标后再做 FILL_CENTER 缩放。
 */
object RotationMath {

    /** 归一化到 [0,360)，负数/超范围角度也能正确映射（CameraX 只会给 0/90/180/270）。 */
    fun normalizeDegrees(rotationDegrees: Int): Int {
        val mod = rotationDegrees % 360
        return if (mod < 0) mod + 360 else mod
    }

    /** buffer 尺寸 → rotated 尺寸；90/270 交换宽高，其余原样。 */
    fun rotatedSize(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> =
        when (normalizeDegrees(rotationDegrees)) {
            90, 270 -> height to width
            else -> width to height
        }

    /**
     * 正旋转：buffer 坐标 → rotated 坐标。
     *
     * @param bufferWidth  原始 buffer 宽（未旋转）
     * @param bufferHeight 原始 buffer 高（未旋转）
     */
    fun forward(point: PointF, bufferWidth: Int, bufferHeight: Int, rotationDegrees: Int): PointF =
        when (normalizeDegrees(rotationDegrees)) {
            90 -> PointF(bufferHeight - 1f - point.y, point.x)
            180 -> PointF(bufferWidth - 1f - point.x, bufferHeight - 1f - point.y)
            270 -> PointF(point.y, bufferWidth - 1f - point.x)
            else -> point
        }

    /**
     * 逆旋转：rotated 坐标 → buffer 坐标。
     *
     * @param bufferWidth  原始 buffer 宽（未旋转；公式中的 `w`）
     * @param bufferHeight 原始 buffer 高（未旋转；公式中的 `h`）
     */
    fun inverse(point: PointF, bufferWidth: Int, bufferHeight: Int, rotationDegrees: Int): PointF =
        when (normalizeDegrees(rotationDegrees)) {
            90 -> PointF(point.y, bufferHeight - 1f - point.x)
            180 -> PointF(bufferWidth - 1f - point.x, bufferHeight - 1f - point.y)
            270 -> PointF(bufferWidth - 1f - point.y, point.x)
            else -> point
        }
}
