package com.example.multiscan.data.sync

import com.example.multiscan.data.FakeApi
import com.example.multiscan.data.FakeOutboxDao
import com.example.multiscan.data.FakeScanItemDao
import com.example.multiscan.data.FakeSessionDao
import com.example.multiscan.data.FakeSettings
import com.example.multiscan.data.PassthroughTransactionRunner
import com.example.multiscan.data.TestClock
import com.example.multiscan.data.httpError
import com.example.multiscan.data.local.OutboxEntity
import com.example.multiscan.data.local.ScanItemEntity
import com.example.multiscan.data.local.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.UUID

/**
 * 契约 §6.5 同步状态矩阵（纯 JVM：fake DAO + fake API + 固定时钟）。
 *
 * 覆盖：2xx / 401（不重试）/ 422（failed + 脱敏 last_error）/ 5xx+IOException（attempts+退避）/
 * 413（递归对半拆分 + 确定性 batch_id + 全部成功才 synced）/ 500 条分块 / 无 due 不访问网络。
 */
class SyncEngineStatusMatrixTest {

    private val sessionDao = FakeSessionDao()
    private val itemDao = FakeScanItemDao()
    private val outboxDao = FakeOutboxDao()
    private val settings = FakeSettings()
    private val clock = TestClock()

    private fun engine(api: FakeApi): SyncEngine = SyncEngine(
        sessionDao = sessionDao,
        scanItemDao = itemDao,
        outboxDao = outboxDao,
        tx = PassthroughTransactionRunner(),
        settings = settings,
        apiProvider = { api },
        clock = clock.clock,
        nowIso = clock.iso,
    )

    private fun seedSession(
        sessionId: String = SESSION_ID,
        outboxId: String = OUTBOX_ID,
        itemCount: Int = 2,
    ) {
        sessionDao.rows[sessionId] = SessionEntity(
            id = sessionId,
            startedAt = "2026-10-23T00:00:00Z",
            finishedAt = "2026-10-23T00:01:00Z",
            state = "finished",
        )
        repeat(itemCount) { index ->
            val itemId = "$sessionId-%04d".format(index)
            itemDao.rows[itemId] = ScanItemEntity(
                id = itemId,
                sessionId = sessionId,
                code = "$sessionId-%04d".format(index),
                firstSeenAt = "2026-10-23T00:00:30Z",
                format = "Code128",
                symbologyId = "]C0",
                lineCount = index + 1,
            )
        }
        outboxDao.rows[outboxId] = OutboxEntity(
            id = outboxId,
            sessionId = sessionId,
            state = "pending",
        )
    }

    private fun outboxRow(id: String = OUTBOX_ID): OutboxEntity = outboxDao.rows.getValue(id)

    private fun sessionRow(id: String = SESSION_ID): SessionEntity = sessionDao.rows.getValue(id)

    private fun derived(sessionId: String, from: Int, to: Int): String =
        UUID.nameUUIDFromBytes("$sessionId|$from|$to".toByteArray(Charsets.UTF_8)).toString()

    @Test
    fun `2xx marks outbox and session synced and records success`() = runBlocking {
        seedSession(itemCount = 2)
        val api = FakeApi()

        val summary = engine(api).runOnce()

        assertEquals(1, summary.sessionsSynced)
        assertEquals(1, summary.processed)
        assertEquals("synced", outboxRow().state)
        assertEquals("synced", sessionRow().state)
        assertEquals("success", settings.value.lastSyncStatus)
        assertNotNull(settings.value.lastSyncAt)
        assertNull(summary.nextWakeDelayMillis)

        val upload = api.uploads.single()
        assertEquals(SESSION_ID, upload.batchId)
        assertEquals("0.1.0", upload.appVersion)
        assertEquals("2026-10-23T00:01:00Z", upload.capturedAt)
        assertEquals(listOf("$SESSION_ID-0000", "$SESSION_ID-0001"), upload.items.map { it.rawText })
        assertEquals("Code128", upload.items.first().format)
        assertEquals("]C0", upload.items.first().symbologyId)
        assertEquals(1, upload.items.first().lineCount)
    }

    @Test
    fun `no due rows performs no network call`() = runBlocking {
        seedSession(itemCount = 1)
        engine(FakeApi()).runOnce()

        val secondApi = FakeApi()
        val summary = engine(secondApi).runOnce()

        assertTrue(secondApi.uploads.isEmpty())
        assertEquals(0, summary.processed)
        assertNull(summary.nextWakeDelayMillis)
    }

    @Test
    fun `401 marks auth error fails session and schedules no retry`() = runBlocking {
        seedSession(itemCount = 1)
        val api = FakeApi(onUpload = { _, _ -> throw httpError(401, "invalid token") })

        val summary = engine(api).runOnce()

        assertEquals(1, summary.authErrors)
        assertEquals(1, api.uploads.size)
        val row = outboxRow()
        assertEquals("auth_error", row.state)
        assertEquals(0, row.attempts)
        assertNull(row.nextRetryAt)
        assertEquals("failed", sessionRow().state)
        assertEquals("auth_error", settings.value.lastSyncStatus)
        // 401 不安排任何重试
        assertNull(summary.nextWakeDelayMillis)
    }

    @Test
    fun `401 stops processing the remaining due rows`() = runBlocking {
        seedSession(sessionId = "sess-a", outboxId = "ob-a", itemCount = 1)
        seedSession(sessionId = "sess-b", outboxId = "ob-b", itemCount = 1)
        val api = FakeApi(onUpload = { _, _ -> throw httpError(401) })

        val summary = engine(api).runOnce()

        assertEquals(1, api.uploads.size)
        assertEquals(1, summary.authErrors)
        assertEquals("auth_error", outboxRow("ob-a").state)
        assertEquals("pending", outboxRow("ob-b").state)
        assertNull(summary.nextWakeDelayMillis)
    }

    @Test
    fun `422 marks outbox and session failed with non-secret last error`() = runBlocking {
        seedSession(itemCount = 1)
        val api = FakeApi(onUpload = { _, _ -> throw httpError(422, "invalid raw_text") })

        val summary = engine(api).runOnce()

        assertEquals(1, summary.sessionsFailed)
        val row = outboxRow()
        assertEquals("failed", row.state)
        assertNotNull(row.lastError)
        assertFalse(row.lastError!!.contains("Bearer"))
        assertEquals("failed", sessionRow().state)
        assertEquals("failed", settings.value.lastSyncStatus)
        assertNull(summary.nextWakeDelayMillis)
    }

    @Test
    fun `5xx increments attempts and schedules 30s retry`() = runBlocking {
        seedSession(itemCount = 1)
        val api = FakeApi(onUpload = { _, _ -> throw httpError(500, "server boom") })

        val summary = engine(api).runOnce()

        assertEquals(1, summary.transientErrors)
        val row = outboxRow()
        assertEquals("pending", row.state)
        assertEquals(1, row.attempts)
        assertEquals(Instant.ofEpochMilli(clock.nowMillis + 30_000L).toString(), row.nextRetryAt)
        assertEquals(30_000L, summary.nextWakeDelayMillis)
        // 5xx 只影响 outbox（保持 pending 重试）；会话仍是 finished，等下一次成功上传才 synced。
        assertEquals("finished", sessionRow().state)
    }

    @Test
    fun `transient failure backs off 30s then 2min and skips not due rows`() = runBlocking {
        seedSession(itemCount = 1)
        val api = FakeApi(onUpload = { _, _ -> throw httpError(500) })
        val syncEngine = engine(api)

        val first = syncEngine.runOnce()
        assertEquals(1, outboxRow().attempts)
        assertEquals(30_000L, first.nextWakeDelayMillis)

        // 未到期：不重复请求，但仍给出下一次唤醒延迟
        val notDue = syncEngine.runOnce()
        assertEquals(1, api.uploads.size)
        assertEquals(0, notDue.processed)
        assertEquals(30_000L, notDue.nextWakeDelayMillis)

        clock.advance(30_000L)
        syncEngine.runOnce()
        assertEquals(2, api.uploads.size)
        assertEquals(2, outboxRow().attempts)
        assertEquals(Instant.ofEpochMilli(clock.nowMillis + 120_000L).toString(), outboxRow().nextRetryAt)
    }

    @Test
    fun `io exception is transient and last error is sanitized`() = runBlocking {
        seedSession(itemCount = 1)
        val api = FakeApi(onUpload = { _, _ ->
            throw IOException("socket timeout token=supersecret123")
        })

        val summary = engine(api).runOnce()

        assertEquals(1, summary.transientErrors)
        val row = outboxRow()
        assertEquals("pending", row.state)
        assertEquals(1, row.attempts)
        assertNotNull(row.lastError)
        assertFalse(row.lastError!!.contains("supersecret123"))
        assertTrue(row.lastError!!.contains("token=***"))
        assertEquals(Instant.ofEpochMilli(clock.nowMillis + 30_000L).toString(), row.nextRetryAt)
    }

    @Test
    fun `413 splits recursively with deterministic batch ids and marks synced only when all succeed`() = runBlocking {
        seedSession(itemCount = 5)
        val api = FakeApi(onUpload = { request, _ ->
            if (request.items.size > 2) throw httpError(413, "too large")
        })

        val summary = engine(api).runOnce()

        assertEquals(1, summary.sessionsSynced)
        assertEquals("synced", outboxRow().state)
        assertEquals("synced", sessionRow().state)

        // 请求顺序：整批(413) → 左半(2, ok) → 右半(3, 413) → 右半的左(1) → 右半的右(2)
        assertEquals(listOf(5, 2, 3, 1, 2), api.uploads.map { it.items.size })
        assertEquals(
            listOf(
                SESSION_ID,
                derived(SESSION_ID, 0, 2),
                derived(SESSION_ID, 2, 5),
                derived(SESSION_ID, 2, 3),
                derived(SESSION_ID, 3, 5),
            ),
            api.uploads.map { it.batchId },
        )
        // 拆分后 item 不丢不重
        assertEquals((0 until 5).map { "$SESSION_ID-%04d".format(it) }, api.uploads.flatMap { batch -> batch.items.map { it.itemId } }.distinct())
    }

    @Test
    fun `large session is chunked into 500 item batches`() = runBlocking {
        seedSession(itemCount = 1_200)
        val api = FakeApi()

        val summary = engine(api).runOnce()

        assertEquals(1, summary.sessionsSynced)
        assertEquals("synced", outboxRow().state)
        assertEquals(listOf(500, 500, 200), api.uploads.map { it.items.size })
        assertEquals(
            listOf(
                derived(SESSION_ID, 0, 500),
                derived(SESSION_ID, 500, 1_000),
                derived(SESSION_ID, 1_000, 1_200),
            ),
            api.uploads.map { it.batchId },
        )
    }

    private companion object {
        const val SESSION_ID = "sess-1"
        const val OUTBOX_ID = "outbox-1"
    }
}
