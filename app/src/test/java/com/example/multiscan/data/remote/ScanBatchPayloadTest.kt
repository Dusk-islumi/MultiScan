package com.example.multiscan.data.remote

import com.example.multiscan.data.local.ScanItemEntity
import com.example.multiscan.data.sync.BatchPayloadBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * 契约 §6.3 的 wire 格式锁定：字段名（snake_case）、可选字段省略、413 派生 batch_id 稳定性、
 * 以及「时间是带 Z 的 UTC ISO 字符串」。
 */
class ScanBatchPayloadTest {

    private fun item(index: Int, symbologyId: String? = "]C0"): ScanItemEntity = ScanItemEntity(
        id = "item-$index",
        sessionId = "sess-1",
        code = "0123456789012$index",
        firstSeenAt = "2026-10-23T04:09:02Z",
        format = "Code128",
        symbologyId = symbologyId,
        lineCount = index + 1,
    )

    @Test
    fun `batch payload serializes exact snake case field names`() {
        val request = BatchPayloadBuilder.buildRequest(
            sessionId = "sess-1",
            capturedAt = "2026-10-23T04:09:02Z",
            items = listOf(item(0)),
            from = 0,
            to = 1,
        )

        val encoded = MultiScanJson.json.encodeToString(ScanBatchRequest.serializer(), request)

        assertEquals(
            """{"batch_id":"sess-1","app_version":"0.1.0","captured_at":"2026-10-23T04:09:02Z","items":[{"item_id":"item-0","raw_text":"01234567890120","format":"Code128","symbology_id":"]C0","line_count":1}]}""",
            encoded,
        )
    }

    @Test
    fun `optional item fields are omitted when null`() {
        val payload = ScanItemPayload(itemId = "item-0", rawText = "6901234567890")

        val encoded = MultiScanJson.json.encodeToString(ScanItemPayload.serializer(), payload)

        assertEquals("""{"item_id":"item-0","raw_text":"6901234567890","format":"Code128"}""", encoded)
        assertFalse(encoded.contains("symbology_id"))
        assertFalse(encoded.contains("line_count"))
    }

    @Test
    fun `server responses decode snake case and ignore unknown fields`() {
        val health = MultiScanJson.json.decodeFromString(
            HealthResponse.serializer(),
            """{"ok":true,"version":"1.0.0","server_time":"2026-10-23T04:09:02+00:00","unknown_field":42}""",
        )
        assertEquals("1.0.0", health.version)
        assertEquals("2026-10-23T04:09:02+00:00", health.serverTime)

        val register = MultiScanJson.json.decodeFromString(
            RegisterResponse.serializer(),
            """{"device_id":"dev_1","token":"secret-token","server_time":null}""",
        )
        assertEquals("dev_1", register.deviceId)
        assertEquals("secret-token", register.token)

        val batch = MultiScanJson.json.decodeFromString(
            ScanBatchResponse.serializer(),
            """{"batch_id":"b1","accepted":2,"duplicated":1,"barcodes_total":3}""",
        )
        assertEquals("b1", batch.batchId)
        assertEquals(3, batch.barcodesTotal)
    }

    @Test
    fun `full range uses session id and split ranges use deterministic derived ids`() {
        assertEquals("sess-1", BatchPayloadBuilder.batchIdFor("sess-1", 0, 5, 5))

        val expectedLeft = UUID.nameUUIDFromBytes("sess-1|0|2".toByteArray(Charsets.UTF_8)).toString()
        val derived = BatchPayloadBuilder.batchIdFor("sess-1", 0, 2, 5)
        assertEquals(expectedLeft, derived)
        // 同一区间重传必须得到同一 id（幂等）
        assertEquals(derived, BatchPayloadBuilder.batchIdFor("sess-1", 0, 2, 5))
        // 不同区间必须不同
        assertNotEquals(derived, BatchPayloadBuilder.batchIdFor("sess-1", 2, 5, 5))
    }

    @Test
    fun `captured_at is a UTC ISO instant with Z suffix`() {
        val epochMillis = 1_700_000_000_000L
        val iso = Instant.ofEpochMilli(epochMillis).toString()

        assertTrue(iso.endsWith("Z"))
        assertEquals(Instant.ofEpochMilli(epochMillis), Instant.parse(iso))
        assertTrue(Instant.parse("2026-10-23T04:09:02Z").toString().endsWith("Z"))
    }

    @Test
    fun `builder slices a large session into batches of at most 500 items`() {
        val items = (0 until 1_200).map { item(it) }

        val first = BatchPayloadBuilder.buildRequest("sess-1", "2026-10-23T04:09:02Z", items, 0, 500)
        assertEquals(500, first.items.size)
        assertEquals("item-0", first.items.first().itemId)

        val last = BatchPayloadBuilder.buildRequest("sess-1", "2026-10-23T04:09:02Z", items, 1_000, 1_200)
        assertEquals(200, last.items.size)
        assertEquals("item-1000", last.items.first().itemId)
    }
}
