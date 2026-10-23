package com.example.multiscan.data.sync

/**
 * 一次 [SyncEngine.runOnce] 的结果。
 *
 * [nextWakeDelayMillis] 非空表示还有「未到期」的 pending 批次，worker 需要按该延迟
 * 调度下一次同步（空 = 无事可做，不需要再唤醒）。
 */
data class SyncRunSummary(
    val sessionsSynced: Int = 0,
    val sessionsFailed: Int = 0,
    val authErrors: Int = 0,
    val transientErrors: Int = 0,
    val nextWakeDelayMillis: Long? = null,
) {
    val processed: Int get() = sessionsSynced + sessionsFailed + authErrors + transientErrors
}
