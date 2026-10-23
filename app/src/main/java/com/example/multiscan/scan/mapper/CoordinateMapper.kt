package com.example.multiscan.scan.mapper

import com.example.multiscan.scan.model.FrameGeometry
import com.example.multiscan.scan.model.PointF

/**
 * 分析图像素坐标 → `PreviewView` 视图坐标的纯函数映射（UI 每帧/每次锚点更新时调用）。
 *
 * 语义（严格按 `docs/CONTRACT.md` §5.1）：
 *  1. 输入是 **ImageProxy 原始 buffer 像素坐标（未旋转）**，尺寸 = `geometry.imageWidth/Height`；
 *  2. 先按 `rotationDegrees` **顺时针**旋转，得到「竖直画面」rotated 坐标（90/270 宽高互换）；
 *  3. 再按 `PreviewView` 的 **FILL_CENTER** 变换（`scale = max(viewW/rotW, viewH/rotH)`，
 *     内容居中、超出部分裁掉），**后置相机不镜像**。
 *
 * 本文件是纯 Kotlin（只依赖 `scan.model`），因此可以在 JVM 单测里直接断言 4 个旋转角度。
 */
object CoordinateMapper {

    /** rotated 尺寸：`0/180=(w,h)`；`90/270=(h,w)`。 */
    fun rotatedSize(geometry: FrameGeometry): Pair<Int, Int> =
        RotationMath.rotatedSize(geometry.imageWidth, geometry.imageHeight, geometry.rotationDegrees)

    /**
     * 单点映射。返回 `PreviewView` 视图像素坐标（可能超出视图范围，UI 的 Canvas 会自然裁掉）。
     *
     * 退化输入（宽/高 <= 0）时原样返回，避免除零/NaN 污染 Canvas。
     */
    fun mapPoint(
        geometry: FrameGeometry,
        viewWidth: Int,
        viewHeight: Int,
        point: PointF,
    ): PointF {
        if (geometry.imageWidth <= 0 || geometry.imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return point
        }
        val (rotatedWidth, rotatedHeight) = rotatedSize(geometry)
        if (rotatedWidth <= 0 || rotatedHeight <= 0) return point

        // PreviewView FILL_CENTER：等比放大到覆盖整个 View，再居中（不镜像）。
        val scale = maxOf(
            viewWidth.toFloat() / rotatedWidth,
            viewHeight.toFloat() / rotatedHeight,
        )
        val offsetX = (viewWidth - rotatedWidth * scale) / 2f
        val offsetY = (viewHeight - rotatedHeight * scale) / 2f

        val rotated = RotationMath.forward(
            point = point,
            bufferWidth = geometry.imageWidth,
            bufferHeight = geometry.imageHeight,
            rotationDegrees = geometry.rotationDegrees,
        )
        return PointF(
            x = offsetX + rotated.x * scale,
            y = offsetY + rotated.y * scale,
        )
    }

    /** 四边形映射：逐点调用 [mapPoint]，保持原顺序（zxing 的 TL,TR,BR,BL）。 */
    fun mapQuad(
        geometry: FrameGeometry,
        viewWidth: Int,
        viewHeight: Int,
        quad: List<PointF>,
    ): List<PointF> = quad.map { mapPoint(geometry, viewWidth, viewHeight, it) }
}
