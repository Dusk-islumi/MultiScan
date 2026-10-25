package com.example.multiscan.scan.mapper

import com.example.multiscan.scan.model.PointF
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 旋转数学单测：验证 CONTRACT §5.1 的逆旋转公式（decoder 用：rotated-crop → buffer）
 * 与正旋转公式（mapper 用：buffer → rotated）严格互逆，并硬编码 4 个角度的期望坐标。
 *
 * 背景：zxing-cpp `Result.position` 位于「旋转后 crop 空间」（zxing-cpp 3.1.1 内部先 crop 再按 rotationDegrees 旋转），
 * 90/270 时坐标空间宽高互换，必须用这里的 [RotationMath.inverse] 转回 buffer 空间。
 */
class RotationMathTest {

    private val width = 1920
    private val height = 1080

    @Test
    fun rotatedSize_90And270Swap() {
        assertEquals(1920 to 1080, RotationMath.rotatedSize(width, height, 0))
        assertEquals(1080 to 1920, RotationMath.rotatedSize(width, height, 90))
        assertEquals(1920 to 1080, RotationMath.rotatedSize(width, height, 180))
        assertEquals(1080 to 1920, RotationMath.rotatedSize(width, height, 270))
    }

    @Test
    fun inverse_rotation0_isIdentity() {
        assertPoint(0f, 0f, RotationMath.inverse(PointF(0f, 0f), width, height, 0))
        assertPoint(1919f, 1079f, RotationMath.inverse(PointF(1919f, 1079f), width, height, 0))
        assertPoint(12.5f, 34.25f, RotationMath.inverse(PointF(12.5f, 34.25f), width, height, 0))
    }

    @Test
    fun inverse_rotation90_matchesContractFormula() {
        // 90: (p.x,p.y) -> (p.y, h-1-p.x)，rotated 尺寸 1080x1920。
        assertPoint(0f, 1079f, RotationMath.inverse(PointF(0f, 0f), width, height, 90))
        assertPoint(0f, 0f, RotationMath.inverse(PointF(1079f, 0f), width, height, 90))
        assertPoint(1919f, 0f, RotationMath.inverse(PointF(1079f, 1919f), width, height, 90))
        assertPoint(1919f, 1079f, RotationMath.inverse(PointF(0f, 1919f), width, height, 90))
    }

    @Test
    fun inverse_rotation180_flipsBothAxes() {
        // 180: (p.x,p.y) -> (w-1-p.x, h-1-p.y)。
        assertPoint(1919f, 1079f, RotationMath.inverse(PointF(0f, 0f), width, height, 180))
        assertPoint(0f, 0f, RotationMath.inverse(PointF(1919f, 1079f), width, height, 180))
        assertPoint(1419f, 479f, RotationMath.inverse(PointF(500f, 600f), width, height, 180))
    }

    @Test
    fun inverse_rotation270_matchesContractFormula() {
        // 270: (p.x,p.y) -> (w-1-p.y, p.x)，rotated 尺寸 1080x1920。
        assertPoint(1919f, 0f, RotationMath.inverse(PointF(0f, 0f), width, height, 270))
        assertPoint(0f, 0f, RotationMath.inverse(PointF(0f, 1919f), width, height, 270))
        assertPoint(0f, 1079f, RotationMath.inverse(PointF(1079f, 1919f), width, height, 270))
        assertPoint(1919f, 1079f, RotationMath.inverse(PointF(1079f, 0f), width, height, 270))
    }

    @Test
    fun forwardAndInverse_areMutuallyInverseForAllRotations() {
        val samples = listOf(
            PointF(0f, 0f),
            PointF(1919f, 0f),
            PointF(0f, 1079f),
            PointF(1919f, 1079f),
            PointF(123.5f, 456.25f),
        )
        for (rotation in listOf(0, 90, 180, 270)) {
            for (point in samples) {
                val rotated = RotationMath.forward(point, width, height, rotation)
                val back = RotationMath.inverse(rotated, width, height, rotation)
                assertEquals("rot=$rotation x", point.x, back.x, 0.001f)
                assertEquals("rot=$rotation y", point.y, back.y, 0.001f)

                val inverseFirst = RotationMath.inverse(point, width, height, rotation)
                val forwardBack = RotationMath.forward(inverseFirst, width, height, rotation)
                assertEquals("inverse/forward rot=$rotation x", point.x, forwardBack.x, 0.001f)
                assertEquals("inverse/forward rot=$rotation y", point.y, forwardBack.y, 0.001f)
            }
        }
    }

    @Test
    fun normalizeDegrees_handlesNegativeAndOutOfRange() {
        assertEquals(0, RotationMath.normalizeDegrees(0))
        assertEquals(90, RotationMath.normalizeDegrees(90))
        assertEquals(270, RotationMath.normalizeDegrees(-90))
        assertEquals(90, RotationMath.normalizeDegrees(450))
        assertEquals(0, RotationMath.normalizeDegrees(360))
        assertEquals(0, RotationMath.normalizeDegrees(-360))
    }

    @Test
    fun inverse_unknownRotationFallsBackToIdentity() {
        // CameraX 只会给 0/90/180/270；这里验证异常值不会抛异常/产生 NaN。
        assertPoint(5f, 6f, RotationMath.inverse(PointF(5f, 6f), width, height, 45))
    }

    private fun assertPoint(expectedX: Float, expectedY: Float, actual: PointF) {
        assertEquals("x", expectedX, actual.x, 0.001f)
        assertEquals("y", expectedY, actual.y, 0.001f)
    }
}
