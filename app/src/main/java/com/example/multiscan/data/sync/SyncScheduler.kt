package com.example.multiscan.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * WorkManager 同步调度器：唯一任务 [SyncConfig.UNIQUE_WORK_NAME] + 网络已连接约束。
 *
 * 唯一任务语义（重要）：
 *  - [enqueueNow] 使用 `ExistingWorkPolicy.KEEP`：已有排队/运行中的同步时**不重复入队**，
 *    因此 finishSession / 手动重试多次触发也不会堆积任务；
 *  - [enqueueWithDelay] 使用 `ExistingWorkPolicy.APPEND_OR_REPLACE`：从 [SyncWorker] 内部
 *    （退避重试）调用，把下一次同步追加到同一唯一任务链尾部 —— 不取消正在运行的 worker，
 *    也不会像 REPLACE 那样把「正在收尾的当前任务」取消掉；只有旧链已失败/取消时才替换。
 *    追加任务的 initialDelay 从入队时刻开始计时（可能比理论退避略短，可接受）。
 */
class SyncScheduler(private val context: Context) : SyncTrigger {

    override fun enqueueNow() {
        enqueue(ExistingWorkPolicy.KEEP, delayMillis = 0L)
    }

    override fun enqueueWithDelay(delayMillis: Long) {
        enqueue(ExistingWorkPolicy.APPEND_OR_REPLACE, delayMillis.coerceAtLeast(0L))
    }

    private fun enqueue(policy: ExistingWorkPolicy, delayMillis: Long) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
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
