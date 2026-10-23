package com.example.multiscan.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** `sessions` 表数据访问对象（全部 suspend / Flow，由 Room 切到 IO 执行）。 */
@Dao
interface SessionDao {

    /** INSERT OR IGNORE（主键冲突返回 -1）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(session: SessionEntity): Long

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): SessionEntity?

    /** 会话收尾：同一事务内由调用方配合 outbox 写入。 */
    @Query("UPDATE sessions SET state = :state, finished_at = :finishedAt WHERE id = :id")
    suspend fun finish(id: String, finishedAt: String, state: String): Int

    @Query("UPDATE sessions SET state = :state WHERE id = :id")
    suspend fun setState(id: String, state: String): Int

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: String): Int

    /** App 启动 sweeper：所有未收尾会话。 */
    @Query("SELECT * FROM sessions WHERE state = 'open' ORDER BY started_at ASC")
    suspend fun openSessions(): List<SessionEntity>

    /** 记录页数据源：不展示进行中的 open 会话，最新在前（UTC ISO 字符串可直接字典序排序）。 */
    @Query("SELECT * FROM sessions WHERE state != 'open' ORDER BY started_at DESC")
    fun observeVisibleSessions(): Flow<List<SessionEntity>>

    @Query("DELETE FROM sessions WHERE state = 'synced'")
    suspend fun deleteSynced(): Int
}
