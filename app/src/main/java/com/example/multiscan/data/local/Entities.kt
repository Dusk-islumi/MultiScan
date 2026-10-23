package com.example.multiscan.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `sessions` 表：一次扫描会话 = 一个上传批次。
 *
 * [state] 取值见 [SessionDbState]；时间统一为 UTC ISO-8601 字符串（`Instant.toString()`）。
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "started_at") val startedAt: String,
    @ColumnInfo(name = "finished_at") val finishedAt: String?,
    val state: String,
)

/**
 * `scan_items` 表：会话内已确认条码。
 *
 * `code` 全局 UNIQUE：同一个码在整个 App 生命周期内只入库一次（DB 层强约束），
 * 重复扫描由 INSERT OR IGNORE + 约束异常兜底判定为「已存在」。
 */
@Entity(tableName = "scan_items", indices = [Index(value = ["code"], unique = true)])
data class ScanItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val code: String,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: String,
    val format: String = "Code128",
    @ColumnInfo(name = "symbology_id") val symbologyId: String? = null,
    @ColumnInfo(name = "line_count") val lineCount: Int = 1,
)

/**
 * `outbox` 表：待上传批次队列（一个 session 一行，`session_id` UNIQUE）。
 *
 * [state] 取值见 [OutboxDbState]；[nextRetryAt] 为空表示「立即可发」，
 * 5xx/IO 失败后按退避写入，用于下一次 worker 唤醒时间。
 */
@Entity(tableName = "outbox", indices = [Index(value = ["session_id"], unique = true)])
data class OutboxEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val state: String,
    val attempts: Int = 0,
    @ColumnInfo(name = "next_retry_at") val nextRetryAt: String? = null,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
)

/** `sessions.state` 合法取值（DB 中存字符串，避免 Room enum 迁移成本）。 */
object SessionDbState {
    const val OPEN = "open"
    const val FINISHED = "finished"
    const val SYNCED = "synced"
    const val FAILED = "failed"
}

/** `outbox.state` 合法取值。 */
object OutboxDbState {
    const val PENDING = "pending"
    const val UPLOADING = "uploading"
    const val FAILED = "failed"
    const val AUTH_ERROR = "auth_error"
    const val SYNCED = "synced"
}
