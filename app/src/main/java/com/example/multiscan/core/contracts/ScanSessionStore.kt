package com.example.multiscan.core.contracts

/**
 * 扫描模块（`scan`）与本地存储模块（`data`）之间的唯一契约边界。
 *
 * 由 `data.repository.ScanRepository` 实现；`scan.ScanController` 只依赖本接口，
 * 从而可以在 JVM 单测中用 fake 实现驱动扫描确认/去重逻辑。
 *
 * 实现语义（必须遵守）：
 *  - 时间参数均为 UTC ISO-8601 字符串（`core.time.Utc.nowIso()`）。
 *  - [registerCode] 的「新插入」判断由数据库 UNIQUE 索引兜底，重复码永远只入库一次。
 *  - [finishSession] 在同一事务内完成：会话状态收尾 + 写 outbox + 触发同步调度。
 */
interface ScanSessionStore {

    /**
     * 进入扫描页时调用：创建一条 `sessions`（state = open），返回 session id。
     * 该 id 同时就是未来上传时的 `batch_id`（UUID 字符串）。
     */
    suspend fun startSession(startedAtUtc: String): String

    /**
     * 已确认（连续 2 帧同文本）的码入库。
     *
     * INSERT OR IGNORE 语义：若 `scan_items.code` 已存在则不入库。
     *
     * @return true = 本次新插入（UI 画绿框）；false = 之前扫过（UI 画灰框）。
     */
    suspend fun registerCode(
        sessionId: String,
        code: String,
        format: String,
        symbologyId: String?,
        lineCount: Int,
        firstSeenAtUtc: String,
    ): Boolean

    /**
     * 结束会话（同一事务）：
     *  - `sessions.finished_at = finishedAtUtc`，state = finished；
     *  - 若该会话有 >= 1 条 `scan_items`，插入 `outbox`（state = pending）；
     *  - 若 0 条，删除该空会话（不产生 outbox，避免空批次上云）。
     * 事务提交后触发同步调度（实现内部调用 `SyncScheduler.enqueueNow()`）。
     */
    suspend fun finishSession(sessionId: String, finishedAtUtc: String)
}
