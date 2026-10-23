package com.example.multiscan.scan.mapper

import com.example.multiscan.scan.model.FrameGeometry
import com.example.multiscan.scan.model.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 坐标映射单测（JVM，纯 Kotlin，无 Android 依赖）。
 *
 * 覆盖 `docs/CONTRACT.md` §5.1 的要求：
 *  - 4 个旋转角度 × 4 个角点，期望值全部为手工推导后硬编码的常量；
 *  - 90/270 宽高互换、180 翻转、中心点附近不变；
 *  - 非正方形 View 的 FILL_CENTER 居中偏移（负偏移/裁切）；
 *  - 非 16:9（4:3 相机图）在 9:16 View 上的缩放；
 *  - 无镜像（只做旋转/缩放/平移）。
 *
 * 约定：输入 quad 是 **ImageProxy buffer 未旋转坐标**；视图 = 竖直 9:16 手机屏
 * （`PreviewView` FILL_CENTER，Activity 竖屏锁定）。
 */
class CoordinateMapperTest {

    @Test
    fun rotatedSize_swapsWidthAndHeightFor90And270() {
        val geometry = FrameGeometry(imageWidth = 1920, imageHeight = 1080, rotationDegrees = 0)
        assertEquals(1920 to 1080, CoordinateMapper.rotatedSize(geometry))
        assertEquals(
            1080 to 1920,
            CoordinateMapper.rotatedSize(geometry.copy(rotationDegrees = 90)),
        )
        assertEquals(
            1920 to 1080,
            CoordinateMapper.rotatedSize(geometry.copy(rotationDegrees = 180)),
        )
        assertEquals(
            1080 to 1920,
            CoordinateMapper.rotatedSize(geometry.copy(rotationDegrees = 270)),
        )
        // 4:3 图 90° 后同样交换。
        val fourThree = FrameGeometry(imageWidth = 1440, imageHeight = 1080, rotationDegrees = 90)
        assertEquals(1080 to 1440, CoordinateMapper.rotatedSize(fourThree))
    }

    @Test
    fun mapQuad_rotation0_scalesToFillTallView() {
        val geometry = FrameGeometry(1920, 1080, 0)
        val mapped = CoordinateMapper.mapQuad(geometry, 1080, 1920, bufferCorners(1920, 1080))

        // FILL_CENTER scale = max(1080/1920, 1920/1080) = 16/9；水平居中 → offsetX = -3500/3。
        assertQuad(
            mapped,
            expected = listOf(
                -1166.6667f to 0f,
                2244.8889f to 0f,
                2244.8889f to 1918.2222f,
                -1166.6667f to 1918.2222f,
            ),
        )
    }

    @Test
    fun mapQuad_rotation90_usesSwappedDimensions() {
        val geometry = FrameGeometry(1920, 1080, 90)
        val mapped = CoordinateMapper.mapQuad(geometry, 1080, 1920, bufferCorners(1920, 1080))

        // rotated 尺寸 1080x1920 = view 尺寸 → scale=1、offset=0；
        // buffer→rotated (x,y) -> (h-1-y, x)。
        assertQuad(
            mapped,
            expected = listOf(
                1079f to 0f,
                1079f to 1919f,
                0f to 1919f,
                0f to 0f,
            ),
        )
    }

    @Test
    fun mapQuad_rotation180_flipsBothAxes() {
        val geometry = FrameGeometry(1920, 1080, 180)
        val mapped = CoordinateMapper.mapQuad(geometry, 1080, 1920, bufferCorners(1920, 1080))

        // rotated 尺寸 1920x1080；scale=16/9；buffer→rotated (x,y) -> (w-1-x, h-1-y)。
        assertQuad(
            mapped,
            expected = listOf(
                2244.8889f to 1918.2222f,
                -1166.6667f to 1918.2222f,
                -1166.6667f to 0f,
                2244.8889f to 0f,
            ),
        )
    }

    @Test
    fun mapQuad_rotation270_usesSwappedDimensions() {
        val geometry = FrameGeometry(1920, 1080, 270)
        val mapped = CoordinateMapper.mapQuad(geometry, 1080, 1920, bufferCorners(1920, 1080))

        // rotated 尺寸 1080x1920；buffer→rotated (x,y) -> (y, w-1-x)。
        assertQuad(
            mapped,
            expected = listOf(
                0f to 1919f,
                0f to 0f,
                1079f to 0f,
                1079f to 1919f,
            ),
        )
    }

    @Test
    fun mapQuad_nonSquareView_horizontalCenteringOffsetOnly() {
        val geometry = FrameGeometry(1920, 1080, 0)
        val mapped = CoordinateMapper.mapQuad(geometry, 1000, 1500, bufferCorners(1920, 1080))

        // scale = max(1000/1920, 1500/1080) = 25/18；offsetX = (1000-1920*25/18)/2 = -2500/3；offsetY=0。
        assertQuad(
            mapped,
            expected = listOf(
                -833.3333f to 0f,
                1831.9444f to 0f,
                1831.9444f to 1498.6111f,
                -833.3333f to 1498.6111f,
            ),
        )
    }

    @Test
    fun mapQuad_rotation90_nonSquareView_verticalCenteringOffset() {
        val geometry = FrameGeometry(1920, 1080, 90)
        val mapped = CoordinateMapper.mapQuad(geometry, 1000, 1500, bufferCorners(1920, 1080))

        // rotated 1080x1920；scale = max(1000/1080, 1500/1920) = 25/27；
        // offsetX=0；offsetY = (1500-1920*25/27)/2 = -1250/9。
        assertQuad(
            mapped,
            expected = listOf(
                999.0741f to -138.8889f,
                999.0741f to 1637.9630f,
                0f to 1637.9630f,
                0f to -138.8889f,
            ),
        )
    }

    @Test
    fun mapQuad_fourThreeImage_onTallView_scalesByHeight() {
        val geometry = FrameGeometry(1440, 1080, 0)
        val mapped = CoordinateMapper.mapQuad(geometry, 1080, 1920, bufferCorners(1440, 1080))

        // scale = max(1080/1440=0.75, 1920/1080=16/9) = 16/9；offsetX = (1080-1440*16/9)/2 = -740。
        assertQuad(
            mapped,
            expected = listOf(
                -740f to 0f,
                1818.2222f to 0f,
                1818.2222f to 1918.2222f,
                -740f to 1918.2222f,
            ),
        )
    }

    @Test
    fun mapPoint_bufferCenter_staysNearViewCenterForAllRotations() {
        val viewWidth = 1080
        val viewHeight = 1920
        val center = PointF(960f, 540f) // 1920x1080 buffer 的中心点

        val expectedCenter = mapOf(
            0 to PointF(540f, 960f),
            90 to PointF(539f, 960f),
            180 to PointF(538.2222f, 958.2222f),
            270 to PointF(540f, 959f),
        )
        val geometryBase = FrameGeometry(1920, 1080, 0)
        for ((rotation, expected) in expectedCenter) {
            val actual = CoordinateMapper.mapPoint(
                geometryBase.copy(rotationDegrees = rotation),
                viewWidth,
                viewHeight,
                center,
            )
            assertEquals("rotation=$rotation center x", expected.x, actual.x, 0.05f)
            assertEquals("rotation=$rotation center y", expected.y, actual.y, 0.05f)
        }
    }

    @Test
    fun mapQuad_preservesCornerOrderAndCount() {
        val quad = bufferCorners(1920, 1080)
        val mapped = CoordinateMapper.mapQuad(FrameGeometry(1920, 1080, 90), 1080, 1920, quad)
        assertEquals(4, mapped.size)
        // 顺序保持 TL,TR,BR,BL（旋转后 TL 仍对应输入 quad[0]，不重排）。
        assertEquals(1079f, mapped[0].x, 0.001f)
        assertTrue(mapped[0].y == 0f)
    }

    @Test
    fun mapPoint_degenerateViewOrGeometry_returnsInputUnchanged() {
        val point = PointF(12f, 34f)
        val geometry = FrameGeometry(1920, 1080, 90)
        assertEquals(point, CoordinateMapper.mapPoint(geometry, 0, 1920, point))
        assertEquals(point, CoordinateMapper.mapPoint(geometry, 1080, 0, point))
        assertEquals(point, CoordinateMapper.mapPoint(FrameGeometry(0, 0, 90), 1080, 1920, point))
    }

    // ---------------------------------------------------------------- helpers

    private fun bufferCorners(width: Int, height: Int): List<PointF> = listOf(
        PointF(0f, 0f),                    // TL
        PointF((width - 1).toFloat(), 0f), // TR
        PointF((width - 1).toFloat(), (height - 1).toFloat()), // BR
        PointF(0f, (height - 1).toFloat()),                    // BL
    )

    private fun assertQuad(actual: List<PointF>, expected: List<Pair<Float, Float>>) {
        assertEquals("quad size", expected.size, actual.size)
        for (index in expected.indices) {
            assertEquals("quad[$index].x", expected[index].first, actual[index].x, DELTA)
            assertEquals("quad[$index].y", expected[index].second, actual[index].y, DELTA)
        }
    }

    private companion object {
        /** 期望值为 4 位小数的手工推导常量，允许浮点舍入误差。 */
        const val DELTA = 0.02f
    }
}
