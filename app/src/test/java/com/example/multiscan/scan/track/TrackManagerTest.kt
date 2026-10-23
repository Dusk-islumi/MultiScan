package com.example.multiscan.scan.track

import com.example.multiscan.scan.dedup.CodeClassification
import com.example.multiscan.scan.dedup.SessionCodeIndex
import com.example.multiscan.scan.model.Detection
import com.example.multiscan.scan.model.PointF
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧间跟踪单测（JVM，假时钟注入）—— v2 确认逻辑（CONTRACT §12）：
 *  - 累计 **2 次同文本证据** 即确认（不要求连续帧；漏帧不清零，文本变化才重置）；
 *  - 两次证据必须落在 500ms TTL 内：间隔超过 TTL 时旧 track 已过期，重新出现是新 track（需重新累计 2 次）；
 *  - 单帧不确认；轻微抖动（IoU 匹配 / 中心距匹配）保持同一 track；
 *  - 500ms 无匹配后过期；同码重新出现是新 track，但去重索引给灰。
 */
class TrackManagerTest {

    private var nowMs = 0L
    private val manager = TrackManager(clock = { nowMs })

    @Test
    fun singleFrame_isNeverConfirmed() {
        nowMs = 0L
        val update = manager.update(listOf(detection("111", 100f, 100f, 300f, 200f)))

        assertEquals(1, update.tracks.size)
        assertFalse(update.tracks.single().confirmed)
        assertTrue(update.newlyConfirmed.isEmpty())
        assertEquals(0, confirmedCount(update))
    }

    @Test
    fun twoConsecutiveFrames_sameText_confirmsOnlyOnce() {
        nowMs = 0L
        val first = manager.update(listOf(detection("111", 100f, 100f, 300f, 200f)))
        val trackId = first.tracks.single().id
        assertFalse(first.tracks.single().confirmed)

        nowMs = 100L
        val second = manager.update(listOf(detection("111", 105f, 103f, 305f, 203f)))
        assertTrue(second.tracks.single().confirmed)
        assertEquals(1, second.newlyConfirmed.size)
        assertEquals("111", second.newlyConfirmed.single().text)
        assertEquals(trackId, second.newlyConfirmed.single().id)

        nowMs = 200L
        val third = manager.update(listOf(detection("111", 108f, 105f, 308f, 205f)))
        assertTrue(third.tracks.single().confirmed)
        assertTrue("confirmed 只上报一次", third.newlyConfirmed.isEmpty())
        assertEquals(trackId, third.tracks.single().id)
    }

    @Test
    fun missedFrameBetweenEvidences_stillConfirms() {
        // v2：漏一帧（该帧没解出）不清零证据，两次证据之间允许丢帧。
        nowMs = 0L
        val first = manager.update(listOf(detection("333", 100f, 100f, 300f, 200f)))
        assertFalse(first.tracks.single().confirmed)

        nowMs = 100L
        val missed = manager.update(emptyList())
        assertEquals("漏帧但未超过 500ms TTL，track 仍存活", 1, missed.tracks.size)
        assertFalse(missed.tracks.single().confirmed)
        assertTrue(missed.newlyConfirmed.isEmpty())

        nowMs = 200L
        val confirmed = manager.update(listOf(detection("333", 100f, 100f, 300f, 200f)))
        assertTrue("漏帧后第二次证据仍应确认", confirmed.tracks.single().confirmed)
        assertEquals(1, confirmed.newlyConfirmed.size)
        assertEquals("333", confirmed.newlyConfirmed.single().text)
    }

    @Test
    fun evidenceAtExactly500ms_stillConfirms() {
        // TTL 边界：两次证据间隔恰好 500ms（严格 >500ms 才过期），仍应确认。
        nowMs = 0L
        manager.update(listOf(detection("555", 100f, 100f, 300f, 200f)))

        nowMs = 500L
        val confirmed = manager.update(listOf(detection("555", 100f, 100f, 300f, 200f)))
        assertTrue(confirmed.tracks.single().confirmed)
        assertEquals(1, confirmed.newlyConfirmed.size)
    }

    @Test
    fun secondEvidenceAfterTtl_startsNewTrackAndDoesNotConfirm() {
        // v2：第二次证据超过 500ms TTL 时旧 track 已过期，不能复用旧证据确认。
        nowMs = 0L
        val firstId = manager.update(listOf(detection("444", 100f, 100f, 300f, 200f))).tracks.single().id

        nowMs = 600L // 距 firstEvidence 600ms > TTL → 旧 track 先过期移除
        val reappear = manager.update(listOf(detection("444", 100f, 100f, 300f, 200f)))
        assertEquals(1, reappear.tracks.size)
        assertNotEquals("过期后重新出现必须是新 track id", firstId, reappear.tracks.single().id)
        assertFalse("新 track 只有 1 次证据，不能确认", reappear.tracks.single().confirmed)
        assertTrue(reappear.newlyConfirmed.isEmpty())

        nowMs = 700L
        val confirmed = manager.update(listOf(detection("444", 100f, 100f, 300f, 200f)))
        assertTrue("新 track 第 2 次证据后确认", confirmed.tracks.single().confirmed)
        assertEquals(1, confirmed.newlyConfirmed.size)
    }

    @Test
    fun textChange_resetsEvidence() {
        nowMs = 0L
        manager.update(listOf(detection("AAA", 100f, 100f, 300f, 200f)))

        nowMs = 100L
        val changed = manager.update(listOf(detection("BBB", 100f, 100f, 300f, 200f)))
        assertFalse("文本变化后不能直接确认", changed.tracks.single().confirmed)
        assertTrue(changed.newlyConfirmed.isEmpty())
        assertEquals("BBB", changed.tracks.single().text)

        nowMs = 200L
        val confirmed = manager.update(listOf(detection("BBB", 100f, 100f, 300f, 200f)))
        assertTrue(confirmed.tracks.single().confirmed)
        assertEquals("BBB", confirmed.newlyConfirmed.single().text)
    }

    @Test
    fun jitteredBox_iouMatch_keepsSameTrack() {
        nowMs = 0L
        val first = manager.update(listOf(detection("111", 100f, 100f, 300f, 200f)))
        val trackId = first.tracks.single().id

        // 平移 15px：IoU 约 0.78 > 0.3，应匹配到同一 track（且第二帧即确认）。
        nowMs = 100L
        val second = manager.update(listOf(detection("111", 115f, 105f, 315f, 205f)))
        assertEquals(1, second.tracks.size)
        assertEquals(trackId, second.tracks.single().id)
        assertTrue(second.tracks.single().confirmed)
        assertEquals(1, second.newlyConfirmed.size)
    }

    @Test
    fun distantTallBox_centerDistanceMatch_keepsSameTrack() {
        // 细高框：水平平移 120px 后完全无交集（IoU=0），但中心距 120 < 0.5*高(400)=200。
        nowMs = 0L
        val trackId = manager.update(listOf(detection("222", 100f, 100f, 160f, 500f))).tracks.single().id

        nowMs = 100L
        val second = manager.update(listOf(detection("222", 220f, 100f, 280f, 500f)))
        assertEquals(1, second.tracks.size)
        assertEquals(trackId, second.tracks.single().id)
    }

    @Test
    fun trackExpires_onlyAfterMoreThan500msWithoutMatch() {
        nowMs = 0L
        manager.update(listOf(detection("111", 100f, 100f, 300f, 200f)))
        nowMs = 100L
        manager.update(listOf(detection("111", 100f, 100f, 300f, 200f)))
        assertEquals(1, manager.update(emptyList()).tracks.size)

        nowMs = 600L // 距 lastSeen=100 恰好 500ms：仍存活（契约：500ms+ 才移除）
        assertEquals(1, manager.update(emptyList()).tracks.size)

        nowMs = 601L
        assertTrue("501ms 未出现应过期移除", manager.update(emptyList()).tracks.isEmpty())
    }

    @Test
    fun confirmedTrack_keepsStableTextLineCountAndSymbology() {
        nowMs = 0L
        manager.update(listOf(detection("111", 100f, 100f, 300f, 200f, lineCount = 7, symbology = "]C0")))
        nowMs = 100L
        manager.update(listOf(detection("111", 100f, 100f, 300f, 200f, lineCount = 7, symbology = "]C0")))

        // 后续帧 lineCount/symbology 抖动不能改写已确认 track 的稳定字段。
        nowMs = 200L
        val snapshot = manager.update(
            listOf(detection("111", 100f, 100f, 300f, 200f, lineCount = 3, symbology = "]C1")),
        ).tracks.single()
        assertTrue(snapshot.confirmed)
        assertEquals(7, snapshot.lineCount)
        assertEquals("]C0", snapshot.symbologyIdentifier)
        assertEquals("111", snapshot.text)
    }

    @Test
    fun reappearingCode_isNewTrack_butDedupIndexSaysGrey() = runBlocking {
        val codeIndex = SessionCodeIndex()
        var dbCalls = 0

        // 第一次出现并确认：DB 返回 true（新插入）→ New/绿。
        nowMs = 0L
        manager.update(listOf(detection("999", 100f, 100f, 300f, 200f)))
        nowMs = 100L
        manager.update(listOf(detection("999", 100f, 100f, 300f, 200f)))
        val first = codeIndex.classify("999") { dbCalls += 1; true }
        assertTrue(first is CodeClassification.New)
        assertEquals(1, dbCalls)
        val firstTrackId = manager.update(emptyList()).tracks.single().id

        // 超过 500ms 未出现 → 过期。
        nowMs = 700L
        assertTrue(manager.update(emptyList()).tracks.isEmpty())

        // 同码重新出现在新位置：是新 track，需要重新累计 2 帧才确认。
        nowMs = 800L
        val reappear = manager.update(listOf(detection("999", 500f, 300f, 700f, 400f)))
        val secondTrackId = reappear.tracks.single().id
        assertNotEquals("过期后重新出现必须是新 track id", firstTrackId, secondTrackId)
        assertFalse(reappear.tracks.single().confirmed)

        nowMs = 900L
        val reconfirmed = manager.update(listOf(detection("999", 500f, 300f, 700f, 400f)))
        assertTrue(reconfirmed.tracks.single().confirmed)
        assertEquals(1, reconfirmed.newlyConfirmed.size)

        // 去重索引：DB 未再查（缓存命中），但状态为 Grey（已入库，不再算新码）。
        val second = codeIndex.classify("999") { dbCalls += 1; false }
        assertTrue(second is CodeClassification.Grey)
        assertTrue(second.fromCache)
        assertEquals("DB 每个 code 只查一次", 1, dbCalls)
    }

    // ---------------------------------------------------------------- helpers

    private fun detection(
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        lineCount: Int = 5,
        symbology: String? = "]C0",
    ): Detection = Detection(
        text = text,
        format = "Code128",
        symbologyIdentifier = symbology,
        lineCount = lineCount,
        cornersInBufferSpace = listOf(
            PointF(left, top),
            PointF(right, top),
            PointF(right, bottom),
            PointF(left, bottom),
        ),
    )

    private fun confirmedCount(update: TrackUpdate): Int = update.tracks.count { it.confirmed }
}
