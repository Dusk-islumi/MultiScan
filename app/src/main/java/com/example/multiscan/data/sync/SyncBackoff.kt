package com.example.multiscan.data.sync

/**
 * 失败重试退避：30s → 2min → 10min → 30min（封顶）。
 *
 * [attempts] 为累计失败次数（从 1 开始）：1→30s、2→2min、3→10min、4 及以后→30min。
 * 该序列被 [com.example.multiscan.data.local.OutboxDao.markPendingRetry] 使用，
 * 由 JVM 单测 [com.example.multiscan.data.sync.SyncBackoffTest] 锁定。
 */
object SyncBackoff {

    private val DELAYS_MILLIS = longArrayOf(30_000L, 120_000L, 600_000L, 1_800_000L)

    fun delayMillis(attempts: Int): Long =
        DELAYS_MILLIS[(attempts - 1).coerceIn(0, DELAYS_MILLIS.lastIndex)]
}
