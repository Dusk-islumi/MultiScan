package com.example.multiscan.data.sync

/**
 * 同步调度抽象：生产实现是 WorkManager 版 [SyncScheduler]，JVM 单测用 fake。
 *
 * Repository / Worker 只依赖本接口，方便在纯 JVM 单测里断言「是否触发同步」。
 */
interface SyncTrigger {

    /** 立即排一次同步（已有未完成任务时按调度器策略合并）。 */
    fun enqueueNow()

    /** 延迟排一次同步（用于自定义退避，如 30s / 2min / 10min / 30min）。 */
    fun enqueueWithDelay(delayMillis: Long)
}
