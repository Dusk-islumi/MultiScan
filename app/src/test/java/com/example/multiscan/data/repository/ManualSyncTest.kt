package com.example.multiscan.data.repository

import com.example.multiscan.core.diag.DiagLog
import com.example.multiscan.data.FailingOutboxDao
import com.example.multiscan.data.FakeOutboxDao
import com.example.multiscan.data.FakeScanItemDao
import com.example.multiscan.data.FakeSessionDao
import com.example.multiscan.data.FakeSettings
import com.example.multiscan.data.FakeSyncTrigger
import com.example.multiscan.data.PassthroughTransactionRunner
import com.example.multiscan.data.TestClock
import com.example.multiscan.data.local.OutboxDao
import com.example.multiscan.data.local.OutboxEntity
import com.example.multiscan.data.prefs.SettingsSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3 契约 §13.1「立即同步」：四种结果 + wifiOnly 透传 + failed/auth_error 不被自动重置。
 * 纯 JVM：fake DAO / SettingsAccess / SyncTrigger。
 */
class ManualSyncTest {

    private val sessionDao = FakeSessionDao()
    private val itemDao = FakeScanItemDao()
    private val outboxDao = FakeOutboxDao()
    private val trigger = FakeSyncTrigger()
    private val clock = TestClock()

    private fun repository(
        settingsOverride: FakeSettings? = null,
        outboxOverride: OutboxDao? = null,
    ): ScanRepository = ScanRepository(
        sessionDao = sessionDao,
        scanItemDao = itemDao,
        outboxDao = outboxOverride ?: outboxDao,
        tx = PassthroughTransactionRunner(),
        syncScheduler = trigger,
        diagLog = DiagLog(),
        settings = settingsOverride ?: FakeSettings(registered()),
        clock = clock.clock,
    )

    private fun registered(
        syncWifiOnly: Boolean = false,
        autoSync: Boolean = true,
    ): SettingsSnapshot = SettingsSnapshot(
        deviceId = "dev-1",
        hasToken = true,
        autoSync = autoSync,
        syncWifiOnly = syncWifiOnly,
    )

    private fun seedOutboxRow(
        id: String,
        state: String = "pending",
        nextRetryAt: String? = null,
    ) {
        outboxDao.rows[id] = OutboxEntity(
            id = id,
            sessionId = "sess-$id",
            state = state,
            nextRetryAt = nextRetryAt,
        )
    }

    @Test
    fun `requestManualSync needs registration without token or device id`() = runBlocking {
        seedOutboxRow("ob-1")

        assertEquals(
            ManualSyncResult.NeedsRegistration,
            repository(settingsOverride = FakeSettings(SettingsSnapshot())).requestManualSync(),
        )
        assertEquals(
            ManualSyncResult.NeedsRegistration,
            repository(settingsOverride = FakeSettings(SettingsSnapshot(hasToken = true, deviceId = null))).requestManualSync(),
        )
        assertEquals(
            ManualSyncResult.NeedsRegistration,
            repository(settingsOverride = FakeSettings(SettingsSnapshot(hasToken = false, deviceId = "dev-1"))).requestManualSync(),
        )
        assertEquals(0, trigger.enqueueNowCount)
    }

    @Test
    fun `requestManualSync is up to date when nothing is pending and does not enqueue`() = runBlocking {
        seedOutboxRow("ob-synced", state = "synced")
        seedOutboxRow("ob-failed", state = "failed")

        val result = repository().requestManualSync()

        assertEquals(ManualSyncResult.UpToDate, result)
        assertEquals(0, trigger.enqueueNowCount)
    }

    @Test
    fun `requestManualSync starts with pending count and clears pending backoff`() = runBlocking {
        seedOutboxRow("ob-1", nextRetryAt = "2026-10-23T10:00:00Z")
        seedOutboxRow("ob-2")
        seedOutboxRow("ob-3", nextRetryAt = "2026-10-23T11:00:00Z")

        val result = repository().requestManualSync()

        assertEquals(ManualSyncResult.Started(3), result)
        assertEquals(1, trigger.enqueueNowCount)
        assertEquals(listOf(false), trigger.enqueueNowWifiOnly)
        assertTrue(
            outboxDao.rows.values.filter { it.state == "pending" }.all { it.nextRetryAt == null },
        )
    }

    @Test
    fun `requestManualSync passes wifiOnly from settings`() = runBlocking {
        seedOutboxRow("ob-1")

        val result = repository(settingsOverride = FakeSettings(registered(syncWifiOnly = true))).requestManualSync()

        assertEquals(ManualSyncResult.Started(1), result)
        assertEquals(listOf(true), trigger.enqueueNowWifiOnly)
    }

    @Test
    fun `requestManualSync does not count or reset failed and auth error rows`() = runBlocking {
        seedOutboxRow("ob-pending")
        seedOutboxRow("ob-failed", state = "failed")
        seedOutboxRow("ob-auth", state = "auth_error")

        val result = repository().requestManualSync()

        assertEquals(ManualSyncResult.Started(1), result)
        assertEquals("failed", outboxDao.rows.getValue("ob-failed").state)
        assertEquals("auth_error", outboxDao.rows.getValue("ob-auth").state)
    }

    @Test
    fun `requestManualSync returns Chinese error message when DAO fails`() = runBlocking {
        val failingOutbox = FailingOutboxDao()
        failingOutbox.fake.rows["ob-1"] = OutboxEntity(id = "ob-1", sessionId = "sess-1", state = "pending")

        val result = repository(
            settingsOverride = FakeSettings(registered()),
            outboxOverride = failingOutbox,
        ).requestManualSync()

        assertTrue(result is ManualSyncResult.Error)
        assertEquals("同步失败", (result as ManualSyncResult.Error).message)
        assertEquals(0, trigger.enqueueNowCount)
    }
}
