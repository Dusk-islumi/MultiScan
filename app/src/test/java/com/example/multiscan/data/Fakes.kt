package com.example.multiscan.data

import com.example.multiscan.data.local.OutboxDao
import com.example.multiscan.data.local.OutboxEntity
import com.example.multiscan.data.local.ScanItemDao
import com.example.multiscan.data.local.ScanItemEntity
import com.example.multiscan.data.local.SessionDao
import com.example.multiscan.data.local.SessionEntity
import com.example.multiscan.data.local.TransactionRunner
import com.example.multiscan.data.prefs.SettingsAccess
import com.example.multiscan.data.prefs.SettingsSnapshot
import com.example.multiscan.data.prefs.maskToken
import com.example.multiscan.data.remote.HealthResponse
import com.example.multiscan.data.remote.MultiScanApi
import com.example.multiscan.data.remote.RegisterRequest
import com.example.multiscan.data.remote.RegisterResponse
import com.example.multiscan.data.remote.ScanBatchRequest
import com.example.multiscan.data.remote.ScanBatchResponse
import com.example.multiscan.data.sync.SyncTrigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.time.Instant

/**
 * 纯 JVM 单测替身集合（无 Robolectric / Android framework）：
 * fake DAO 接口 + 直通事务 + fake 设置/调度/API + 可推进时钟。
 */

/** 构造任意 HTTP 状态码的 [HttpException]，模拟 Retrofit 非 2xx 行为。 */
fun httpError(code: Int, detail: String = "error"): HttpException = HttpException(
    Response.error<Any>(
        code,
        """{"detail":"$detail"}""".toResponseBody("application/json".toMediaType()),
    ),
)

/** 可手动推进的固定时钟：同时给出 millis 与 UTC ISO 两种投影（引擎两者都注入）。 */
class TestClock(startMillis: Long = 1_700_000_000_000L) {

    var nowMillis: Long = startMillis
        private set

    fun advance(millis: Long) {
        nowMillis += millis
    }

    val clock: () -> Long = { nowMillis }

    val iso: () -> String = { Instant.ofEpochMilli(nowMillis).toString() }
}

class FakeSessionDao : SessionDao {

    val rows: LinkedHashMap<String, SessionEntity> = LinkedHashMap()

    override suspend fun insert(session: SessionEntity): Long {
        if (rows.containsKey(session.id)) return -1L
        rows[session.id] = session
        return 1L
    }

    override suspend fun findById(id: String): SessionEntity? = rows[id]

    override suspend fun finish(id: String, finishedAt: String, state: String): Int {
        val current = rows[id] ?: return 0
        rows[id] = current.copy(finishedAt = finishedAt, state = state)
        return 1
    }

    override suspend fun setState(id: String, state: String): Int {
        val current = rows[id] ?: return 0
        rows[id] = current.copy(state = state)
        return 1
    }

    override suspend fun deleteById(id: String): Int = if (rows.remove(id) != null) 1 else 0

    override suspend fun openSessions(): List<SessionEntity> =
        rows.values.filter { it.state == "open" }.sortedBy { it.startedAt }

    override fun observeVisibleSessions(): Flow<List<SessionEntity>> =
        flowOf(rows.values.filter { it.state != "open" }.sortedByDescending { it.startedAt })

    override suspend fun deleteSynced(): Int {
        val ids = rows.values.filter { it.state == "synced" }.map { it.id }
        ids.forEach { rows.remove(it) }
        return ids.size
    }
}

class FakeScanItemDao : ScanItemDao {

    val rows: LinkedHashMap<String, ScanItemEntity> = LinkedHashMap()

    /** 由测试注入：哪些 session 属于 synced（供 deleteSyncedItems 模拟 SQL 子查询）。 */
    var syncedSessionIds: () -> Set<String> = { emptySet() }

    override suspend fun insertIgnore(item: ScanItemEntity): Long {
        if (rows.values.any { it.code == item.code }) return -1L
        rows[item.id] = item
        return 1L
    }

    override suspend fun countBySession(sessionId: String): Int =
        rows.values.count { it.sessionId == sessionId }

    override suspend fun itemsForSession(sessionId: String): List<ScanItemEntity> =
        rows.values.filter { it.sessionId == sessionId }
            .sortedWith(compareBy({ it.firstSeenAt }, { it.id }))

    override suspend fun findByCode(code: String): ScanItemEntity? =
        rows.values.firstOrNull { it.code == code }

    override fun observeAll(): Flow<List<ScanItemEntity>> = flowOf(rows.values.toList())

    override fun observeTodayCount(todayStartIso: String): Flow<Int> =
        flowOf(rows.values.count { it.firstSeenAt >= todayStartIso })

    override fun observeTotalCount(): Flow<Int> = flowOf(rows.size)

    override suspend fun deleteSyncedItems(): Int {
        val synced = syncedSessionIds()
        val removed = rows.values.filter { it.sessionId in synced }.map { it.id }
        removed.forEach { rows.remove(it) }
        return removed.size
    }
}

class FakeOutboxDao : OutboxDao {

    val rows: LinkedHashMap<String, OutboxEntity> = LinkedHashMap()

    override suspend fun insertIgnore(row: OutboxEntity): Long {
        if (rows.values.any { it.sessionId == row.sessionId }) return -1L
        rows[row.id] = row
        return 1L
    }

    override suspend fun findById(id: String): OutboxEntity? = rows[id]

    override suspend fun findBySessionId(sessionId: String): OutboxEntity? =
        rows.values.firstOrNull { it.sessionId == sessionId }

    override suspend fun duePending(nowIso: String): List<OutboxEntity> =
        rows.values.filter { row ->
            row.state == "pending" && (row.nextRetryAt == null || row.nextRetryAt <= nowIso)
        }.sortedBy { it.sessionId }

    override suspend fun nextRetryAt(): String? =
        rows.values.filter { it.state == "pending" }.mapNotNull { it.nextRetryAt }.minOrNull()

    override suspend fun resetPendingSchedule(): Int {
        var count = 0
        for (entry in rows.entries.toList()) {
            if (entry.value.state == "pending" && entry.value.nextRetryAt != null) {
                rows[entry.key] = entry.value.copy(nextRetryAt = null)
                count++
            }
        }
        return count
    }

    override suspend fun countPending(): Int = rows.values.count { it.state == "pending" }

    override suspend fun resetStaleUploading(): Int {
        var count = 0
        for (entry in rows.entries.toList()) {
            if (entry.value.state == "uploading") {
                rows[entry.key] = entry.value.copy(state = "pending")
                count++
            }
        }
        return count
    }

    override suspend fun markUploading(id: String): Int = update(id) { row ->
        if (row.state == "pending") row.copy(state = "uploading") else null
    }

    override suspend fun markSynced(id: String): Int = update(id) { row ->
        row.copy(state = "synced", nextRetryAt = null)
    }

    override suspend fun markFailed(id: String, error: String?): Int = update(id) { row ->
        row.copy(state = "failed", lastError = error, nextRetryAt = null)
    }

    override suspend fun markAuthError(id: String, error: String?): Int = update(id) { row ->
        row.copy(state = "auth_error", lastError = error, nextRetryAt = null)
    }

    override suspend fun markPendingRetry(
        id: String,
        attempts: Int,
        nextRetryAt: String?,
        error: String?,
    ): Int = update(id) { row ->
        row.copy(state = "pending", attempts = attempts, nextRetryAt = nextRetryAt, lastError = error)
    }

    override suspend fun resetForRetry(id: String): Int = update(id) { row ->
        row.copy(state = "pending", attempts = 0, nextRetryAt = null, lastError = null)
    }

    override suspend fun resetAllFailed(): Int {
        var count = 0
        for (entry in rows.entries.toList()) {
            if (entry.value.state == "failed" || entry.value.state == "auth_error") {
                rows[entry.key] = entry.value.copy(
                    state = "pending",
                    attempts = 0,
                    nextRetryAt = null,
                    lastError = null,
                )
                count++
            }
        }
        return count
    }

    override fun observeAll(): Flow<List<OutboxEntity>> = flowOf(rows.values.toList())

    override fun observePendingCount(): Flow<Int> =
        flowOf(rows.values.count { it.state == "pending" || it.state == "uploading" })

    override fun observeFailedCount(): Flow<Int> =
        flowOf(rows.values.count { it.state == "failed" || it.state == "auth_error" })

    override suspend fun deleteSynced(): Int {
        val ids = rows.values.filter { it.state == "synced" }.map { it.id }
        ids.forEach { rows.remove(it) }
        return ids.size
    }

    private fun update(id: String, transform: (OutboxEntity) -> OutboxEntity?): Int {
        val current = rows[id] ?: return 0
        val next = transform(current) ?: return 0
        rows[id] = next
        return 1
    }
}

/** 模拟「立即同步」过程中 DAO 抛异常，用于 ManualSyncResult.Error 分支单测。 */
class FailingOutboxDao(val fake: FakeOutboxDao = FakeOutboxDao()) : OutboxDao by fake {

    override suspend fun resetPendingSchedule(): Int = throw IllegalStateException("simulated outbox failure")
}

/** 直通事务（单测没有真实 DB，事务边界不影响断言）。 */
class PassthroughTransactionRunner : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}

class FakeSettings(initial: SettingsSnapshot = SettingsSnapshot()) : SettingsAccess {

    private val state = MutableStateFlow(initial)

    override val snapshot: StateFlow<SettingsSnapshot> get() = state

    val value: SettingsSnapshot get() = state.value

    override suspend fun current(): SettingsSnapshot = state.value

    override suspend fun token(): String? =
        if (state.value.hasToken) "test-token-0123456789" else null

    override suspend fun setServerUrl(url: String) {
        state.value = state.value.copy(serverUrl = url.trim())
    }

    override suspend fun setEnrollSecret(secret: String) {
        state.value = state.value.copy(enrollSecret = secret)
    }

    override suspend fun saveRegistration(deviceId: String, token: String) {
        state.value = state.value.copy(
            deviceId = deviceId,
            hasToken = true,
            maskedToken = maskToken(token),
        )
    }

    override suspend fun setOverlayVisible(visible: Boolean) {
        state.value = state.value.copy(overlayVisible = visible)
    }

    override suspend fun setLastSync(atUtcIso: String?, status: String) {
        state.value = state.value.copy(lastSyncAt = atUtcIso, lastSyncStatus = status)
    }

    override suspend fun clearRegistration() {
        state.value = state.value.copy(deviceId = null, hasToken = false, maskedToken = null)
    }

    override suspend fun setAutoSync(enabled: Boolean) {
        state.value = state.value.copy(autoSync = enabled)
    }

    override suspend fun setSyncWifiOnly(enabled: Boolean) {
        state.value = state.value.copy(syncWifiOnly = enabled)
    }

    override suspend fun setSyncRunning() {
        state.value = state.value.copy(lastSyncStatus = SettingsSnapshot.SYNC_RUNNING)
    }
}

class FakeSyncTrigger : SyncTrigger {

    var enqueueNowCount: Int = 0
        private set

    /** 每次 enqueueNow 传入的 wifiOnly 标志（按调用顺序）。 */
    val enqueueNowWifiOnly: MutableList<Boolean> = mutableListOf()

    /** 每次 enqueueWithDelay 的 (delayMillis, wifiOnly)。 */
    val delayedRequests: MutableList<Pair<Long, Boolean>> = mutableListOf()

    /** 每次 reapplyConstraints 传入的 wifiOnly。 */
    val reapplyConstraintsCalls: MutableList<Boolean> = mutableListOf()

    override fun enqueueNow(wifiOnly: Boolean) {
        enqueueNowCount++
        enqueueNowWifiOnly += wifiOnly
    }

    override fun enqueueWithDelay(delayMillis: Long, wifiOnly: Boolean) {
        delayedRequests += delayMillis to wifiOnly
    }

    override fun reapplyConstraints(wifiOnly: Boolean) {
        reapplyConstraintsCalls += wifiOnly
    }
}

/**
 * 可编程的假 API：记录所有 uploadBatch 请求（顺序敏感），
 * 通过 [onUpload] 抛出 [HttpException] / [java.io.IOException] 模拟错误矩阵。
 */
class FakeApi(
    private val onHealth: () -> HealthResponse = {
        HealthResponse(ok = true, version = "1.0.0", serverTime = "2026-10-23T04:09:02+00:00")
    },
    private val onRegister: (RegisterRequest) -> RegisterResponse = { request ->
        RegisterResponse(deviceId = "dev_1", token = "token-${request.name}")
    },
    private val onUpload: (ScanBatchRequest, Int) -> Unit = { _, _ -> },
) : MultiScanApi {

    val uploads: MutableList<ScanBatchRequest> = mutableListOf()

    override suspend fun health(): HealthResponse = onHealth()

    override suspend fun register(body: RegisterRequest): RegisterResponse = onRegister(body)

    override suspend fun uploadBatch(body: ScanBatchRequest): ScanBatchResponse {
        val index = uploads.size
        uploads += body
        onUpload(body, index)
        return ScanBatchResponse(
            batchId = body.batchId,
            accepted = body.items.size,
            duplicated = 0,
            barcodesTotal = body.items.size,
        )
    }
}
