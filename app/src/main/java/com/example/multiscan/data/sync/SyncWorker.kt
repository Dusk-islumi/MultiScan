package com.example.multiscan.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.example.multiscan.MultiScanApplication
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
            val summary = container.syncEngine.runOnce()
            summary.nextWakeDelayMillis?.let { delayMillis ->
                container.syncScheduler.enqueueWithDelay(delayMillis)
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
