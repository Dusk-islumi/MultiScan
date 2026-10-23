package com.example.multiscan.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** `outbox` 表数据访问对象；状态流转见 [OutboxDbState]。 */
@Dao
interface OutboxDao {

    /** `session_id` UNIQUE：同一会话重复 finish/sweep 只保留一行。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: OutboxEntity): Long

    @Query("SELECT * FROM outbox WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): OutboxEntity?

    @Query("SELECT * FROM outbox WHERE session_id = :sessionId LIMIT 1")
    suspend fun findBySessionId(sessionId: String): OutboxEntity?

    /** 本轮可发批次：pending 且没有退避限制（next_retry_at 为空或已到期）。 */
    @Query("SELECT * FROM outbox WHERE state = 'pending' AND (next_retry_at IS NULL OR next_retry_at <= :nowIso) ORDER BY session_id ASC")
    suspend fun duePending(nowIso: String): List<OutboxEntity>

    /** 最近一个待重试时间（UTC ISO），用于调度下一次延迟 worker。 */
    @Query("SELECT MIN(next_retry_at) FROM outbox WHERE state = 'pending' AND next_retry_at IS NOT NULL")
    suspend fun nextRetryAt(): String?

    /** 进程崩溃残留的 uploading → pending（SyncEngine.runOnce 开头调用）。 */
    @Query("UPDATE outbox SET state = 'pending' WHERE state = 'uploading'")
    suspend fun resetStaleUploading(): Int

    /** 认领批次，防止并发 worker 重复上传。 */
    @Query("UPDATE outbox SET state = 'uploading' WHERE id = :id AND state = 'pending'")
    suspend fun markUploading(id: String): Int

    @Query("UPDATE outbox SET state = 'synced', next_retry_at = NULL WHERE id = :id")
    suspend fun markSynced(id: String): Int

    @Query("UPDATE outbox SET state = 'failed', last_error = :error, next_retry_at = NULL WHERE id = :id")
    suspend fun markFailed(id: String, error: String?): Int

    @Query("UPDATE outbox SET state = 'auth_error', last_error = :error, next_retry_at = NULL WHERE id = :id")
    suspend fun markAuthError(id: String, error: String?): Int

    /** 5xx / IOException：attempts+1 并写入下一次重试时间，保持 pending。 */
    @Query("UPDATE outbox SET state = 'pending', attempts = :attempts, next_retry_at = :nextRetryAt, last_error = :error WHERE id = :id")
    suspend fun markPendingRetry(id: String, attempts: Int, nextRetryAt: String?, error: String?): Int

    /** 手动重试单条：清退避与错误，立即重发。 */
    @Query("UPDATE outbox SET state = 'pending', attempts = 0, next_retry_at = NULL, last_error = NULL WHERE id = :id")
    suspend fun resetForRetry(id: String): Int

    /** 手动重试全部失败/登录失效批次。 */
    @Query("UPDATE outbox SET state = 'pending', attempts = 0, next_retry_at = NULL, last_error = NULL WHERE state IN ('failed', 'auth_error')")
    suspend fun resetAllFailed(): Int

    @Query("SELECT * FROM outbox")
    fun observeAll(): Flow<List<OutboxEntity>>

    /** 首页「待同步」：pending + 上传中的批次。 */
    @Query("SELECT COUNT(*) FROM outbox WHERE state IN ('pending', 'uploading')")
    fun observePendingCount(): Flow<Int>

    /** 首页「失败」：永久失败 + 登录失效。 */
    @Query("SELECT COUNT(*) FROM outbox WHERE state IN ('failed', 'auth_error')")
    fun observeFailedCount(): Flow<Int>

    @Query("DELETE FROM outbox WHERE state = 'synced'")
    suspend fun deleteSynced(): Int
}
