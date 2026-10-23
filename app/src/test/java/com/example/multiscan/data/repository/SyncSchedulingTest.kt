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
import com.example.multiscan.data.prefs.SettingsSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v3 契约 §13.1 自动同步开关语义：`finishSession` / `sweepOpenSessions` 仅在 autoSync 为 true
 * 时调度；手动同步 / 记录页重试不受 autoSync 限制；wifiOnly 偏好必须传给 trigger。
 */
class SyncSchedulingTest {

    private val sessionDao = FakeSessionDao()
    private val itemDao = FakeScanItemDao()
    private val outboxDao = FakeOutboxDao()
    private val trigger = FakeSyncTrigger()
    private val clock = TestClock()

    private fun repository(settings: FakeSettings): ScanRepository = ScanRepository(
        sessionDao = sessionDao,
        scanItemDao = itemDao,
        outboxDao = outboxDao,
        tx = PassthroughTransactionRunner(),
        syncScheduler = trigger,
        diagLog = DiagLog(),
        settings = settings,
        clock = clock.clock,
    )

    private fun settings(autoSync: Boolean = true, syncWifiOnly: Boolean = false): FakeSettings = FakeSettings(
        SettingsSnapshot(
            deviceId = "dev-1",
            hasToken = true,
            autoSync = autoSync,
            syncWifiOnly = syncWifiOnly,
        ),
    )

    private suspend fun finishOneScannedSession(repository: ScanRepository) {
        val sessionId = repository.startSession("2026-10-23T00:00:00Z")
        repository.registerCode(sessionId, "6901234567890", "Code128", "]C0", 1, "2026-10-23T00:00:01Z")
        repository.finishSession(sessionId, "2026-10-23T00:01:00Z")
    }

    private fun seedOpenSession() {
        sessionDao.rows["open-1"] = SessionEntity("open-1", "2026-10-23T00:00:00Z", null, "open")
        itemDao.rows["i-1"] = ScanItemEntity(
            id = "i-1",
            sessionId = "open-1",
            code = "111",
            firstSeenAt = "2026-10-23T00:00:01Z",
        )
    }

    @Test
    fun `autoSync disabled finishSession writes outbox but does not enqueue`() = runBlocking {
        val repository = repository(settings(autoSync = false))

        finishOneScannedSession(repository)

        assertEquals(1, outboxDao.rows.size)
        assertEquals("pending", outboxDao.rows.values.single().state)
        assertEquals(0, trigger.enqueueNowCount)
    }

    @Test
    fun `autoSync enabled finishSession enqueues with wifiOnly snapshot`() = runBlocking {
        val repository = repository(settings(autoSync = true, syncWifiOnly = true))

        finishOneScannedSession(repository)

        assertEquals(1, trigger.enqueueNowCount)
        assertEquals(listOf(true), trigger.enqueueNowWifiOnly)
    }

    @Test
    fun `autoSync disabled sweepOpenSessions does not enqueue`() = runBlocking {
        seedOpenSession()
        val repository = repository(settings(autoSync = false))

        repository.sweepOpenSessions()

        assertEquals("finished", sessionDao.rows.getValue("open-1").state)
        assertEquals("pending", outboxDao.rows.values.single().state)
        assertEquals(0, trigger.enqueueNowCount)
    }

    @Test
    fun `autoSync enabled sweepOpenSessions enqueues with wifiOnly snapshot`() = runBlocking {
        seedOpenSession()
        val repository = repository(settings(autoSync = true, syncWifiOnly = true))

        repository.sweepOpenSessions()

        assertEquals(1, trigger.enqueueNowCount)
        assertEquals(listOf(true), trigger.enqueueNowWifiOnly)
    }

    @Test
    fun `retryOutbox is not blocked by autoSync and passes wifiOnly`() = runBlocking {
        sessionDao.rows["sess-x"] = SessionEntity("sess-x", "2026-10-23T00:00:00Z", "2026-10-23T00:01:00Z", "failed")
        outboxDao.rows["ob-x"] = OutboxEntity(id = "ob-x", sessionId = "sess-x", state = "failed", attempts = 2)
        val repository = repository(settings(autoSync = false, syncWifiOnly = true))

        repository.retryOutbox("sess-x")

        assertEquals(1, trigger.enqueueNowCount)
        assertEquals(listOf(true), trigger.enqueueNowWifiOnly)
        assertEquals("pending", outboxDao.rows.getValue("ob-x").state)
    }

    @Test
    fun `manual sync ignores autoSync switch and passes wifiOnly`() = runBlocking {
        outboxDao.rows["ob-1"] = OutboxEntity(id = "ob-1", sessionId = "sess-1", state = "pending")
        val repository = repository(settings(autoSync = false, syncWifiOnly = true))

        val result = repository.requestManualSync()

        assertEquals(ManualSyncResult.Started(1), result)
        assertEquals(listOf(true), trigger.enqueueNowWifiOnly)
    }
}
