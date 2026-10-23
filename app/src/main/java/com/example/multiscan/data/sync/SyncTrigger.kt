package com.example.multiscan.data.sync

/**
 * 同步调度抽象：生产实现是 WorkManager 版 [SyncScheduler]，JVM 单测用 fake。
 *
 * Repository / Worker 只依赖本接口，方便在纯 JVM 单测里断言「是否触发同步」以及
 * Wi-Fi-only 网络约束是否正确传递（v3 契约 §13.1）。
 */
interface SyncTrigger {

    /** 立即排一次同步（已有未完成任务时按调度器策略合并）。[wifiOnly] 决定网络约束。 */
    fun enqueueNow(wifiOnly: Boolean = false)

    /** 延迟排一次同步（自定义退避：30s / 2min / 10min / 30min）。 */
    fun enqueueWithDelay(delayMillis: Long, wifiOnly: Boolean = false)

    /**
     * 切换「仅 Wi-Fi 同步」后立即生效：取消同名唯一任务，并按新网络约束重新入队。
     *
     * 注意：**正在运行的同步 worker 会被取消**。这对数据是安全的 —— SyncEngine 下次运行会把
     * 残留的 `uploading` 重置为 pending，且所有批次 id 都是确定性派生（服务端幂等），
     * 重新入队后会安全重传。
     */
    fun reapplyConstraints(wifiOnly: Boolean)
}
