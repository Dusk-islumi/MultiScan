package com.example.multiscan.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.multiscan.core.diag.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** 进程内唯一的 Preferences DataStore（文件名固定 "settings"）。 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 设置持久化（Preferences DataStore）+ token 的 Keystore 加密存储。
 *
 * - [snapshot]：热 StateFlow，UI / Repository 直接订阅；
 * - token 只落密文（[TokenCipher]），对外只有 [SettingsSnapshot.hasToken] / `maskedToken`；
 * - 修改服务器地址会清空 deviceId / token 并重置同步状态（必须向新服务器重新注册）。
 */
class SettingsStore(context: Context, diagLog: DiagLog? = null) : SettingsAccess {

    private val appContext = context.applicationContext
    private val tokenCipher = TokenCipher(diagLog)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val snapshot: StateFlow<SettingsSnapshot> = appContext.settingsDataStore.data
        .map { it.toSnapshot() }
        .stateIn(scope, SharingStarted.Eagerly, SettingsSnapshot())

    override suspend fun current(): SettingsSnapshot = withContext(Dispatchers.IO) {
        appContext.settingsDataStore.data.first().toSnapshot()
    }

    override suspend fun token(): String? = withContext(Dispatchers.IO) {
        tokenCipher.decrypt(appContext.settingsDataStore.data.first()[Keys.TOKEN_CIPHER])
    }

    override suspend fun setServerUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        withContext(Dispatchers.IO) {
            appContext.settingsDataStore.edit { prefs ->
                val previous = prefs[Keys.SERVER_URL] ?: SettingsSnapshot.DEFAULT_SERVER_URL
                if (normalizeForCompare(previous) != normalizeForCompare(trimmed)) {
                    prefs.remove(Keys.DEVICE_ID)
                    prefs.remove(Keys.TOKEN_CIPHER)
                    prefs[Keys.LAST_SYNC_STATUS] = SettingsSnapshot.SYNC_NEVER
                    prefs.remove(Keys.LAST_SYNC_AT)
                }
                prefs[Keys.SERVER_URL] = trimmed
            }
        }
    }

    override suspend fun setEnrollSecret(secret: String) {
        withContext(Dispatchers.IO) {
            appContext.settingsDataStore.edit { prefs -> prefs[Keys.ENROLL_SECRET] = secret.trim() }
        }
    }

    override suspend fun saveRegistration(deviceId: String, token: String) {
        val encrypted = tokenCipher.encrypt(token) ?: throw IllegalStateException("token encryption failed")
        withContext(Dispatchers.IO) {
            appContext.settingsDataStore.edit { prefs ->
                prefs[Keys.DEVICE_ID] = deviceId
                prefs[Keys.TOKEN_CIPHER] = encrypted
            }
        }
    }

    override suspend fun setOverlayVisible(visible: Boolean) {
        withContext(Dispatchers.IO) {
            appContext.settingsDataStore.edit { prefs -> prefs[Keys.OVERLAY_VISIBLE] = visible }
        }
    }

    override suspend fun setLastSync(atUtcIso: String?, status: String) {
        withContext(Dispatchers.IO) {
            appContext.settingsDataStore.edit { prefs ->
                if (atUtcIso.isNullOrBlank()) {
                    prefs.remove(Keys.LAST_SYNC_AT)
                } else {
                    prefs[Keys.LAST_SYNC_AT] = atUtcIso
                }
                prefs[Keys.LAST_SYNC_STATUS] = status
            }
        }
    }

    override suspend fun clearRegistration() {
        withContext(Dispatchers.IO) {
            appContext.settingsDataStore.edit { prefs ->
                prefs.remove(Keys.DEVICE_ID)
                prefs.remove(Keys.TOKEN_CIPHER)
            }
        }
    }

    private fun Preferences.toSnapshot(): SettingsSnapshot {
        val plainToken = tokenCipher.decrypt(this[Keys.TOKEN_CIPHER])
        return SettingsSnapshot(
            serverUrl = this[Keys.SERVER_URL] ?: SettingsSnapshot.DEFAULT_SERVER_URL,
            enrollSecret = this[Keys.ENROLL_SECRET] ?: "",
            deviceId = this[Keys.DEVICE_ID],
            hasToken = plainToken != null,
            maskedToken = maskToken(plainToken),
            overlayVisible = this[Keys.OVERLAY_VISIBLE] ?: true,
            lastSyncAt = this[Keys.LAST_SYNC_AT],
            lastSyncStatus = this[Keys.LAST_SYNC_STATUS] ?: SettingsSnapshot.SYNC_NEVER,
        )
    }

    private fun normalizeForCompare(value: String): String = value.trim().trimEnd('/').lowercase()

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val ENROLL_SECRET = stringPreferencesKey("enroll_secret")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val TOKEN_CIPHER = stringPreferencesKey("token_cipher")
        val OVERLAY_VISIBLE = booleanPreferencesKey("overlay_visible")
        val LAST_SYNC_AT = stringPreferencesKey("last_sync_at")
        val LAST_SYNC_STATUS = stringPreferencesKey("last_sync_status")
    }
}
