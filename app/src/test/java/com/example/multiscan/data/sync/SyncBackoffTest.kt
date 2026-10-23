package com.example.multiscan.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/** 契约 §6.6：attempts 1..5 精确等于 30s / 2min / 10min / 30min / 30min。 */
class SyncBackoffTest {

    @Test
    fun `backoff sequence is exactly 30s 2min 10min 30min capped`() {
        assertEquals(30_000L, SyncBackoff.delayMillis(1))
        assertEquals(120_000L, SyncBackoff.delayMillis(2))
        assertEquals(600_000L, SyncBackoff.delayMillis(3))
        assertEquals(1_800_000L, SyncBackoff.delayMillis(4))
        assertEquals(1_800_000L, SyncBackoff.delayMillis(5))
    }

    @Test
    fun `attempts beyond the cap stay at 30 minutes`() {
        assertEquals(1_800_000L, SyncBackoff.delayMillis(6))
        assertEquals(1_800_000L, SyncBackoff.delayMillis(100))
        assertEquals(1_800_000L, SyncBackoff.delayMillis(Int.MAX_VALUE))
    }

    @Test
    fun `non positive attempts fall back to the first delay`() {
        assertEquals(30_000L, SyncBackoff.delayMillis(0))
        assertEquals(30_000L, SyncBackoff.delayMillis(-3))
    }
}
