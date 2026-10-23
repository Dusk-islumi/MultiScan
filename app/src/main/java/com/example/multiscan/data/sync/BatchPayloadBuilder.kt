package com.example.multiscan.data.sync

import com.example.multiscan.data.local.ScanItemEntity
import com.example.multiscan.data.remote.ScanBatchRequest
import com.example.multiscan.data.remote.ScanItemPayload
import java.util.UUID

/**
 * 批次 payload 组装 + 413 拆分后的确定性 batch_id。
 *
 * id 规则（契约 §6.5）：
 *  - 整批单段（from = 0, to = total）：`batch_id = sessionId`（会话 id 即幂等键）；
 *  - 拆分 / 500 条分块：`UUID.nameUUIDFromBytes("$sessionId|$from|$to")`，
 *    同一区间重传永远是同一个 id，服务端可幂等去重。
 */
object BatchPayloadBuilder {

    fun batchIdFor(sessionId: String, from: Int, to: Int, total: Int): String =
        if (from == 0 && to == total) {
            sessionId
        } else {
            UUID.nameUUIDFromBytes("$sessionId|$from|$to".toByteArray(Charsets.UTF_8)).toString()
        }

    fun buildRequest(
        sessionId: String,
        capturedAt: String,
        items: List<ScanItemEntity>,
        from: Int,
        to: Int,
    ): ScanBatchRequest {
        require(from in 0..to && to <= items.size) { "invalid range [$from, $to) for ${items.size} items" }
        return ScanBatchRequest(
            batchId = batchIdFor(sessionId, from, to, items.size),
            appVersion = SyncConfig.APP_VERSION,
            capturedAt = capturedAt,
            items = items.subList(from, to).map { it.toPayload() },
        )
    }

    private fun ScanItemEntity.toPayload(): ScanItemPayload = ScanItemPayload(
        itemId = id,
        rawText = code,
        format = format,
        symbologyId = symbologyId,
        lineCount = lineCount,
    )
}
