package com.example.multiscan.data.repository

import com.example.multiscan.core.diag.DiagLog
import com.example.multiscan.data.FakeOutboxDao
import com.example.multiscan.data.FakeScanItemDao
import com.example.multiscan.data.FakeSessionDao
import com.example.multiscan.data.FakeSettings
import com.example.multiscan.data.FakeSyncTrigger
import com.example.multiscan.data.PassthroughTransactionRunner
import com.example.multiscan.data.TestClock
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
import java.time.Instant

/**
 * 契约 §6.6：fake DAO 下的去重 / finishSession / sweepOpenSessions 行为。
 * 全部纯 JVM（无 Robolectric），证明 Repository 只依赖 DAO 接口 + 事务抽象。
 */
class ScanRepositoryDedupTest {

    private val sessionDao = FakeSessionDao()
    private val itemDao = FakeScanItemDao()
    private val outboxDao = FakeOutboxDao()
    private val trigger = FakeSyncTrigger()
    private val clock = TestClock()

    private val repository = ScanRepository(
        sessionDao = sessionDao,
        scanItemDao = itemDao,
        outboxDao = outboxDao,
        tx = PassthroughTransactionRunner(),
        syncScheduler = trigger,
        diagLog = DiagLog(),
        settings = FakeSettings(),
        clock = clock.clock,
    )

    @Test
    fun `registerCode returns true on first insert and false for a duplicate code`() = runBlocking {
        val sessionId = repository.startSession("2026-10-23T00:00:00Z")

        assertTrue(repository.registerCode(sessionId, "6901234567890", "Code128", "]C0", 3, "2026-10-23T00:00:01Z"))
        assertFalse(repository.registerCode(sessionId, "6901234567890", "Code128", "]C0", 3, "2026-10-23T00:00:02Z"))
        assertTrue(repository.registerCode(sessionId, "6901234567891", "Code128", null, 1, "2026-10-23T00:00:03Z"))

        assertEquals(2, itemDao.rows.size)
        assertEquals(1, sessionDao.rows.size)
    }

    @Test
    fun `registerCode dedups across sessions because code is globally unique`() = runBlocking {
        val firstSession = repository.startSession("2026-10-23T00:00:00Z")
        val secondSession = repository.startSession("2026-10-23T00:10:00Z")

        assertTrue(repository.registerCode(firstSession, "123456", "Code128", null, 1, "2026-10-23T00:00:01Z"))
        assertFalse(repository.registerCode(secondSession, "123456", "Code128", null, 1, "2026-10-23T00:10:01Z"))

        assertEquals(1, itemDao.rows.size)
    }

    @Test
    fun `finishSession with items sets finished and creates pending outbox then enqueues sync`() = runBlocking {
        val sessionId = repository.startSession("2026-10-23T00:00:00Z")
        repository.registerCode(sessionId, "111", "Code128", null, 1, "2026-10-23T00:00:01Z")

        repository.finishSession(sessionId, "2026-10-23T00:01:00Z")

        val session = sessionDao.rows.getValue(sessionId)
        assertEquals("finished", session.state)
        assertEquals("2026-10-23T00:01:00Z", session.finishedAt)

        val outboxRow = outboxDao.rows.values.single()
        assertEquals(sessionId, outboxRow.sessionId)
        assertEquals("pending", outboxRow.state)
        assertEquals(0, outboxRow.attempts)
        assertNull(outboxRow.nextRetryAt)
        assertEquals(1, trigger.enqueueNowCount)
    }

    @Test
    fun `finishSession is idempotent for outbox when called twice`() = runBlocking {
        val sessionId = repository.startSession("2026-10-23T00:00:00Z")
        repository.registerCode(sessionId, "222", "Code128", null, 1, "2026-10-23T00:00:01Z")

        repository.finishSession(sessionId, "2026-10-23T00:01:00Z")
        repository.finishSession(sessionId, "2026-10-23T00:01:30Z")

        assertEquals(1, outboxDao.rows.size)
        assertEquals("finished", sessionDao.rows.getValue(sessionId).state)
        assertEquals("2026-10-23T00:01:30Z", sessionDao.rows.getValue(sessionId).finishedAt)
    }

    @Test
    fun `finishSession deletes empty session without creating outbox`() = runBlocking {
        val sessionId = repository.startSession("2026-10-23T00:00:00Z")

        repository.finishSession(sessionId, "2026-10-23T00:01:00Z")

        assertNull(sessionDao.rows[sessionId])
        assertTrue(outboxDao.rows.isEmpty())
        assertEquals(0, trigger.enqueueNowCount)
    }

    @Test
    fun `sweepOpenSessions finishes open sessions with items and deletes empty ones`() = runBlocking {
        sessionDao.rows["open-1"] = SessionEntity("open-1", "2026-10-23T00:00:00Z", null, "open")
        sessionDao.rows["open-2"] = SessionEntity("open-2", "2026-10-23T00:00:05Z", null, "open")
        itemDao.rows["i-1"] = ScanItemEntity(
            id = "i-1",
            sessionId = "open-1",
            code = "333",
            firstSeenAt = "2026-10-23T00:00:01Z",
        )

        repository.sweepOpenSessions()

        val swept = sessionDao.rows.getValue("open-1")
        assertEquals("finished", swept.state)
        assertEquals(Instant.ofEpochMilli(clock.nowMillis).toString(), swept.finishedAt)
        assertNull(sessionDao.rows["open-2"])

        val outboxRow = outboxDao.rows.values.single()
        assertEquals("open-1", outboxRow.sessionId)
        assertEquals("pending", outboxRow.state)
        assertEquals(1, trigger.enqueueNowCount)
    }

    @Test
    fun `sweepOpenSessions resets stale uploading rows back to pending`() = runBlocking {
        sessionDao.rows["open-1"] = SessionEntity("open-1", "2026-10-23T00:00:00Z", null, "open")
        itemDao.rows["i-1"] = ScanItemEntity(
            id = "i-1",
            sessionId = "open-1",
            code = "444",
            firstSeenAt = "2026-10-23T00:00:01Z",
        )
        // 崩溃残留：上次上传到一半的 outbox 行（session 唯一索引已存在，sweep 不应重复插入）
        outboxDao.rows["stale"] = OutboxEntity("stale", "open-1", "uploading")

        repository.sweepOpenSessions()

        assertEquals("pending", outboxDao.rows.getValue("stale").state)
        assertEquals(1, outboxDao.rows.size)
        assertEquals("finished", sessionDao.rows.getValue("open-1").state)
    }

    @Test
    fun `retryOutbox accepts a session id and resets backoff state`() = runBlocking {
        sessionDao.rows["sess-x"] = SessionEntity("sess-x", "2026-10-23T00:00:00Z", "2026-10-23T00:01:00Z", "failed")
        outboxDao.rows["ob-x"] = OutboxEntity(
            id = "ob-x",
            sessionId = "sess-x",
            state = "failed",
            attempts = 2,
            nextRetryAt = "2026-10-23T00:05:00Z",
            lastError = "boom",
        )

        repository.retryOutbox("sess-x")

        val row = outboxDao.rows.getValue("ob-x")
        assertEquals("pending", row.state)
        assertEquals(0, row.attempts)
        assertNull(row.nextRetryAt)
        assertNull(row.lastError)
        assertEquals(1, trigger.enqueueNowCount)
    }

    @Test
    fun `retryAllFailed resets every failed and auth error row`() = runBlocking {
        outboxDao.rows["ob-1"] = OutboxEntity("ob-1", "sess-1", "failed", attempts = 3)
        outboxDao.rows["ob-2"] = OutboxEntity("ob-2", "sess-2", "auth_error")
        outboxDao.rows["ob-3"] = OutboxEntity("ob-3", "sess-3", "pending")

        repository.retryAllFailed()

        assertEquals("pending", outboxDao.rows.getValue("ob-1").state)
        assertEquals(0, outboxDao.rows.getValue("ob-1").attempts)
        assertEquals("pending", outboxDao.rows.getValue("ob-2").state)
        assertEquals(1, trigger.enqueueNowCount)
    }

    @Test
    fun `clearSyncedData removes only synced sessions items and outbox`() = runBlocking {
        sessionDao.rows["synced"] = SessionEntity("synced", "2026-10-23T00:00:00Z", "2026-10-23T00:01:00Z", "synced")
        sessionDao.rows["failed"] = SessionEntity("failed", "2026-10-23T01:00:00Z", "2026-10-23T01:01:00Z", "failed")
        itemDao.rows["i-synced"] = ScanItemEntity("i-synced", "synced", "555", "2026-10-23T00:00:01Z")
        itemDao.rows["i-failed"] = ScanItemEntity("i-failed", "failed", "666", "2026-10-23T01:00:01Z")
        outboxDao.rows["o-synced"] = OutboxEntity("o-synced", "synced", "synced")
        outboxDao.rows["o-failed"] = OutboxEntity("o-failed", "failed", "failed")
        itemDao.syncedSessionIds = { setOf("synced") }

        repository.clearSyncedData()

        assertFalse(sessionDao.rows.containsKey("synced"))
        assertTrue(sessionDao.rows.containsKey("failed"))
        assertFalse(itemDao.rows.containsKey("i-synced"))
        assertTrue(itemDao.rows.containsKey("i-failed"))
        assertFalse(outboxDao.rows.containsKey("o-synced"))
        assertTrue(outboxDao.rows.containsKey("o-failed"))
    }
}
