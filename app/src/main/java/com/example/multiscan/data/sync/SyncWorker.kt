package com.example.multiscan.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.example.multiscan.MultiScanApplication
import com.example.multiscan.data.prefs.SettingsSnapshot
import kotlin.coroutines.cancellation.CancellationException

/**
 * 同步 Worker（唯一任务 "sync-scans"）。
 *
 * 业务失败（401/422/5xx/IO）由 [SyncEngine] 落库并写入退避时间，本 worker 仍返回 success；
 * engine 给出的 [SyncRunSummary.nextWakeDelayMillis] 通过 [SyncScheduler.enqueueWithDelay]
 * 追加到同一唯一任务链，形成 30s→2min→10min→30min 的自定义退避。
 *
 * 只有「意外 Throwable」（DB/依赖注入崩溃等）才走 WorkManager 自带 retry，且最多 3 次。
 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as MultiScanApplication).container
        return try {
            // v3（契约 §13.1）：同步开始即把 last_sync_status 置为 syncing；
            // SyncEngine 结束时仍会写 success / failed / auth_error 作为最终状态。
            val previous = container.settingsStore.current()
            container.settingsStore.setSyncRunning()
            val summary = container.syncEngine.runOnce()
            // 本轮无事可做时（如仅因切换 Wi-Fi 约束触发的空跑）引擎不会写最终状态；
            // 这里恢复到同步前状态，避免 UI 永远停在「同步中」而禁用同步按钮。
            if (container.settingsStore.current().lastSyncStatus == SettingsSnapshot.SYNC_RUNNING) {
                val restoreStatus = when {
                    previous.lastSyncStatus == SettingsSnapshot.SYNC_RUNNING ->
                        if (previous.lastSyncAt == null) SettingsSnapshot.SYNC_NEVER else SettingsSnapshot.SYNC_SUCCESS
                    else -> previous.lastSyncStatus
                }
                container.settingsStore.setLastSync(previous.lastSyncAt, restoreStatus)
            }
            summary.nextWakeDelayMillis?.let { delayMillis ->
                // 延迟重试按「当前」仅 Wi-Fi 偏好设置网络约束（用户可能在等待期间切换）。
                val wifiOnly = container.settingsStore.current().syncWifiOnly
                container.syncScheduler.enqueueWithDelay(delayMillis, wifiOnly)
            }
            if (summary.processed > 0) {
                container.diagLog.log(
                    TAG,
                    "sync run: total=${summary.processed} synced=${summary.sessionsSynced} " +
                        "failed=${summary.sessionsFailed} auth=${summary.authErrors} " +
                        "transient=${summary.transientErrors}",
                )
            }
            Result.success()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            container.diagLog.log(TAG, "sync crashed: ${t::class.java.simpleName}")
            if (runAttemptCount >= MAX_WORKER_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    private companion object {
        const val TAG = "SyncWorker"
        const val MAX_WORKER_ATTEMPTS = 3
    }
}
