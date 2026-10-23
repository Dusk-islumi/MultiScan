package com.example.multiscan.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.example.multiscan.core.contracts.ScanSessionStore
import com.example.multiscan.core.diag.DiagLog
import com.example.multiscan.core.time.Utc
import com.example.multiscan.data.local.OutboxDao
import com.example.multiscan.data.local.OutboxDbState
import com.example.multiscan.data.local.OutboxEntity
import com.example.multiscan.data.local.ScanItemDao
import com.example.multiscan.data.local.ScanItemEntity
import com.example.multiscan.data.local.SessionDao
import com.example.multiscan.data.local.SessionDbState
import com.example.multiscan.data.local.SessionEntity
import com.example.multiscan.data.local.TransactionRunner
import com.example.multiscan.data.prefs.SettingsAccess
import com.example.multiscan.data.sync.SyncTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** 首页统计：DAO 聚合查询 + SettingsStore 快照组合。 */
data class HomeStats(
    val todayScanned: Int,
    val totalRecords: Int,
    val pendingSync: Int,
    val failed: Int,
    val lastSyncAt: String?,
    val lastSyncStatus: String,
)

/** 记录页展开项。 */
data class ScanItemRecord(
    val id: String,
    val code: String,
    val firstSeenAt: String,
    val format: String,
    val lineCount: Int,
)

enum class SessionState { OPEN, FINISHED, SYNCED, FAILED }

/** 记录页按会话分组的卡片数据；[outboxState] 为原始字符串（pending|uploading|failed|auth_error|synced，可能为 null）。 */
data class SessionRecord(
    val sessionId: String,
    val startedAt: String,
    val finishedAt: String?,
    val state: SessionState,
    val outboxState: String?,
    val itemCount: Int,
    val items: List<ScanItemRecord>,
)

/**
 * 「立即同步」按钮结果（v3 契约 §13.1）。同步是**单向上传**：只把本地待同步批次发到服务器，
 * 不下载 / 不覆盖 / 不删除任何本地数据。
 */
sealed interface ManualSyncResult {
    /** 已入队开始同步；[pendingCount] = 当前 pending 批次数。 */
    data class Started(val pendingCount: Int) : ManualSyncResult

    /** 没有待同步批次（未入队）。 */
    data object UpToDate : ManualSyncResult

    /** 未注册（无 token / 无 deviceId），需先去设置页注册本机。 */
    data object NeedsRegistration : ManualSyncResult

    /** 意外失败（中文提示，可直接展示）。 */
    data class Error(val message: String) : ManualSyncResult
}

/**
 * 本地数据仓库：实现 scan 模块的 [ScanSessionStore] 边界，并向 UI 暴露首页统计 / 记录流。
 *
 * 构造参数只依赖 DAO 接口 + 事务抽象 + 调度抽象（[SyncTrigger]）+ 设置抽象（[SettingsAccess]），
 * 因此可以在纯 JVM 单测中用 fake 完整驱动（无 Robolectric / Android framework）。
 *
 * 不变量：
 *  - `scan_items.code` 全局唯一，重复码永远返回 false（灰框）；
 *  - [finishSession] 在同一事务内置 finished 状态并写 outbox（0 条则删除空会话）；
 *  - 事务提交后才触发 [SyncTrigger.enqueueNow]（避免 worker 读不到 outbox）。
 */
class ScanRepository(
    private val sessionDao: SessionDao,
    private val scanItemDao: ScanItemDao,
    private val outboxDao: OutboxDao,
    private val tx: TransactionRunner,
    private val syncScheduler: SyncTrigger,
    private val diagLog: DiagLog,
    private val settings: SettingsAccess,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ScanSessionStore {

    val homeStats: Flow<HomeStats> = combine(
        scanItemDao.observeTodayCount(Utc.startOfTodayIso()),
        scanItemDao.observeTotalCount(),
        outboxDao.observePendingCount(),
        outboxDao.observeFailedCount(),
        settings.snapshot,
    ) { todayScanned, totalRecords, pendingSync, failed, snapshot ->
        HomeStats(
            todayScanned = todayScanned,
            totalRecords = totalRecords,
            pendingSync = pendingSync,
            failed = failed,
            lastSyncAt = snapshot.lastSyncAt,
            lastSyncStatus = snapshot.lastSyncStatus,
        )
    }

    /** 记录页数据：只含非 open 会话，按 startedAt 最新在前；items 与 outbox 状态已 join。 */
    val sessions: Flow<List<SessionRecord>> = combine(
        sessionDao.observeVisibleSessions(),
        scanItemDao.observeAll(),
        outboxDao.observeAll(),
    ) { sessionEntities, items, outboxRows ->
        val itemsBySession = items.groupBy { it.sessionId }
        val outboxBySession = outboxRows.associateBy { it.sessionId }
        sessionEntities.map { session ->
            val sessionItems = itemsBySession[session.id].orEmpty()
                .sortedWith(compareBy({ it.firstSeenAt }, { it.id }))
            SessionRecord(
                sessionId = session.id,
                startedAt = session.startedAt,
                finishedAt = session.finishedAt,
                state = session.state.toSessionState(),
                outboxState = outboxBySession[session.id]?.state,
                itemCount = sessionItems.size,
                items = sessionItems.map { it.toRecord() },
            )
        }
    }

    override suspend fun startSession(startedAtUtc: String): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        sessionDao.insert(
            SessionEntity(
                id = id,
                startedAt = startedAtUtc,
                finishedAt = null,
                state = SessionDbState.OPEN,
            ),
        )
        diagLog.log(TAG, "start session id=$id")
        id
    }

    override suspend fun registerCode(
        sessionId: String,
        code: String,
        format: String,
        symbologyId: String?,
        lineCount: Int,
        firstSeenAtUtc: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val item = ScanItemEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            code = code,
            firstSeenAt = firstSeenAtUtc,
            format = format,
            symbologyId = symbologyId,
            lineCount = lineCount.coerceAtLeast(1),
        )
        val inserted = try {
            scanItemDao.insertIgnore(item) != -1L
        } catch (e: SQLiteConstraintException) {
            // INSERT OR IGNORE 已覆盖绝大多数冲突；UNIQUE 约束异常兜底视为「已存在」。
            false
        }
        diagLog.log(TAG, if (inserted) "code inserted" else "code duplicated")
        inserted
    }

    override suspend fun finishSession(sessionId: String, finishedAtUtc: String) {
        withContext(Dispatchers.IO) {
            val itemCount = scanItemDao.countBySession(sessionId)
            tx.run {
                if (itemCount == 0) {
                    sessionDao.deleteById(sessionId)
                } else {
                    sessionDao.finish(sessionId, finishedAtUtc, SessionDbState.FINISHED)
                    outboxDao.insertIgnore(
                        OutboxEntity(
                            id = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            state = OutboxDbState.PENDING,
                        ),
                    )
                }
            }
            diagLog.log(TAG, "finish session id=$sessionId items=$itemCount")
            if (itemCount > 0) {
                // v3 契约 §13.1：仅当「自动同步」开启时才调度；手动同步不受此限制。
                val snapshot = settings.current()
                if (snapshot.autoSync) {
                    syncScheduler.enqueueNow(wifiOnly = snapshot.syncWifiOnly)
                }
            }
        }
    }

    /**
     * App 启动崩溃恢复：所有 open 会话 → finished（有 items 的补 outbox）。
     * 同时把进程崩溃残留的 uploading 批次重置为 pending。
     */
    suspend fun sweepOpenSessions() {
        withContext(Dispatchers.IO) {
            outboxDao.resetStaleUploading()
            val openSessions = sessionDao.openSessions()
            if (openSessions.isEmpty()) return@withContext

            val finishedAt = Instant.ofEpochMilli(clock()).toString()
            var outboxCreated = 0
            var deletedEmpty = 0

            for (session in openSessions) {
                val itemCount = scanItemDao.countBySession(session.id)
                tx.run {
                    if (itemCount == 0) {
                        sessionDao.deleteById(session.id)
                    } else {
                        sessionDao.finish(session.id, finishedAt, SessionDbState.FINISHED)
                        val rowId = outboxDao.insertIgnore(
                            OutboxEntity(
                                id = UUID.randomUUID().toString(),
                                sessionId = session.id,
                                state = OutboxDbState.PENDING,
                            ),
                        )
                        if (rowId != -1L) outboxCreated++
                    }
                }
                if (itemCount == 0) deletedEmpty++
            }

            diagLog.log(TAG, "sweep open=${openSessions.size} outbox=$outboxCreated emptyDeleted=$deletedEmpty")
            if (outboxCreated > 0) {
                // v3 契约 §13.1：启动收尾同样受「自动同步」开关约束。
                val snapshot = settings.current()
                if (snapshot.autoSync) {
                    syncScheduler.enqueueNow(wifiOnly = snapshot.syncWifiOnly)
                }
            }
        }
    }

    /**
     * 手动重试一个失败批次。
     *
     * @param id 支持传 **sessionId**（UI 从 [SessionRecord.sessionId] 直接拿）或 outbox 行 id；
     *           outbox.session_id UNIQUE，二者 1:1。
     */
    suspend fun retryOutbox(id: String) {
        withContext(Dispatchers.IO) {
            val row = outboxDao.findById(id) ?: outboxDao.findBySessionId(id) ?: return@withContext
            outboxDao.resetForRetry(row.id)
            diagLog.log(TAG, "retry outbox id=${row.id}")
            // 用户显式操作：不受 autoSync 限制，但按当前网络偏好设置约束。
            syncScheduler.enqueueNow(wifiOnly = settings.current().syncWifiOnly)
        }
    }

    /** 记录页「全部重试」：failed / auth_error 全部重置为 pending 并立即调度。 */
    suspend fun retryAllFailed() {
        withContext(Dispatchers.IO) {
            val count = outboxDao.resetAllFailed()
            if (count > 0) {
                diagLog.log(TAG, "retry all failed count=$count")
                syncScheduler.enqueueNow(wifiOnly = settings.current().syncWifiOnly)
            }
        }
    }

    /**
     * 「立即同步」按钮（v3 契约 §13.1）：手动触发一次同步，**不受 autoSync 开关限制**。
     *
     * 流程：未注册 → [ManualSyncResult.NeedsRegistration]；清 pending 的退避时间
     * （[OutboxDao.resetPendingSchedule]，让退避中的批次立即到期）；无 pending →
     * [ManualSyncResult.UpToDate]（不入队）；有 pending → 按当前「仅 Wi-Fi」偏好入队 →
     * [ManualSyncResult.Started]。
     *
     * 不重置 failed / auth_error（记录页已有「重试」入口）。
     */
    suspend fun requestManualSync(): ManualSyncResult = withContext(Dispatchers.IO) {
        try {
            val snapshot = settings.current()
            if (!snapshot.hasToken || snapshot.deviceId.isNullOrBlank()) {
                return@withContext ManualSyncResult.NeedsRegistration
            }
            outboxDao.resetPendingSchedule()
            val pendingCount = outboxDao.countPending()
            if (pendingCount <= 0) {
                diagLog.log(TAG, "manual sync: nothing pending")
                return@withContext ManualSyncResult.UpToDate
            }
            syncScheduler.enqueueNow(wifiOnly = snapshot.syncWifiOnly)
            diagLog.log(TAG, "manual sync started pending=$pendingCount wifiOnly=${snapshot.syncWifiOnly}")
            ManualSyncResult.Started(pendingCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagLog.log(TAG, "manual sync failed: ${e::class.java.simpleName}")
            ManualSyncResult.Error("同步失败")
        }
    }

    /** 记录页「清空已同步记录」：删除 synced 会话及其 items / outbox（本地数据，不影响服务端）。 */
    suspend fun clearSyncedData() {
        withContext(Dispatchers.IO) {
            tx.run {
                val items = scanItemDao.deleteSyncedItems()
                val outbox = outboxDao.deleteSynced()
                val sessions = sessionDao.deleteSynced()
                diagLog.log(TAG, "clear synced sessions=$sessions items=$items outbox=$outbox")
            }
        }
    }

    private fun String.toSessionState(): SessionState = when (this) {
        SessionDbState.OPEN -> SessionState.OPEN
        SessionDbState.SYNCED -> SessionState.SYNCED
        SessionDbState.FAILED -> SessionState.FAILED
        else -> SessionState.FINISHED
    }

    private fun ScanItemEntity.toRecord(): ScanItemRecord = ScanItemRecord(
        id = id,
        code = code,
        firstSeenAt = firstSeenAt,
        format = format,
        lineCount = lineCount,
    )

    private companion object {
        const val TAG = "ScanRepository"
    }
}
