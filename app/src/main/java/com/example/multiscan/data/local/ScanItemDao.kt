package com.example.multiscan.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** `scan_items` 表数据访问对象。 */
@Dao
interface ScanItemDao {

    /**
     * INSERT OR IGNORE：`code` UNIQUE 命中时返回 -1（表示「已扫过」），
     * 这是 [com.example.multiscan.data.repository.ScanRepository.registerCode] 去重的第一道判断。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(item: ScanItemEntity): Long

    @Query("SELECT COUNT(*) FROM scan_items WHERE session_id = :sessionId")
    suspend fun countBySession(sessionId: String): Int

    /** 上传顺序：抓拍时间升序，同一时间按 id 稳定排序。 */
    @Query("SELECT * FROM scan_items WHERE session_id = :sessionId ORDER BY first_seen_at ASC, id ASC")
    suspend fun itemsForSession(sessionId: String): List<ScanItemEntity>

    @Query("SELECT * FROM scan_items WHERE code = :code LIMIT 1")
    suspend fun findByCode(code: String): ScanItemEntity?

    @Query("SELECT * FROM scan_items")
    fun observeAll(): Flow<List<ScanItemEntity>>

    /** 首页「今日已扫」：本地时区今天 00:00 转 UTC ISO 后的下界。 */
    @Query("SELECT COUNT(*) FROM scan_items WHERE first_seen_at >= :todayStartIso")
    fun observeTodayCount(todayStartIso: String): Flow<Int>

    /** 首页「本地总数」。 */
    @Query("SELECT COUNT(*) FROM scan_items")
    fun observeTotalCount(): Flow<Int>

    @Query("DELETE FROM scan_items WHERE session_id IN (SELECT id FROM sessions WHERE state = 'synced')")
    suspend fun deleteSyncedItems(): Int
}
