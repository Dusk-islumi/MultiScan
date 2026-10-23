package com.example.multiscan.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * WorkManager 同步调度器：唯一任务 [SyncConfig.UNIQUE_WORK_NAME] + 网络约束。
 *
 * 网络约束（v3 契约 §13.1）：
 *  - `wifiOnly = true` → `NetworkType.UNMETERED`（仅 Wi-Fi / 以太网等不计费网络）；
 *  - `wifiOnly = false` → `NetworkType.CONNECTED`（任意联网）。
 *
 * 唯一任务语义：
 *  - [enqueueNow] 使用 `ExistingWorkPolicy.KEEP`：已有排队/运行中的同步时不重复入队，
 *    因此 finishSession / 手动同步多次触发也不会堆积任务；
 *  - [enqueueWithDelay] 使用 `ExistingWorkPolicy.APPEND_OR_REPLACE`：从 [SyncWorker] 内部
 *    （退避重试）调用，追加到同一唯一任务链尾部，不取消正在运行的 worker；
 *  - [reapplyConstraints]：先 `cancelUniqueWork`（**运行中的 worker 会被取消**）再用 REPLACE
 *    立即按新约束重新入队，保证「仅 Wi-Fi」切换即时生效。取消是崩溃安全的：SyncEngine 下次
 *    启动会把残留 uploading 重置为 pending，批次 id 确定性派生，服务端幂等去重。
 */
class SyncScheduler(private val context: Context) : SyncTrigger {

    override fun enqueueNow(wifiOnly: Boolean) {
        enqueue(ExistingWorkPolicy.KEEP, delayMillis = 0L, wifiOnly = wifiOnly)
    }

    override fun enqueueWithDelay(delayMillis: Long, wifiOnly: Boolean) {
        enqueue(ExistingWorkPolicy.APPEND_OR_REPLACE, delayMillis.coerceAtLeast(0L), wifiOnly)
    }

    override fun reapplyConstraints(wifiOnly: Boolean) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(SyncConfig.UNIQUE_WORK_NAME)
        enqueue(ExistingWorkPolicy.REPLACE, delayMillis = 0L, wifiOnly = wifiOnly)
    }

    private fun enqueue(policy: ExistingWorkPolicy, delayMillis: Long, wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build(),
            )
            .apply {
                if (delayMillis > 0L) {
                    setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                }
            }
            .addTag(SyncConfig.WORK_TAG)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(SyncConfig.UNIQUE_WORK_NAME, policy, request)
    }
}
