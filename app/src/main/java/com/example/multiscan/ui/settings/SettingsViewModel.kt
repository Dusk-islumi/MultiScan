package com.example.multiscan.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.multiscan.AppContainer
import com.example.multiscan.R
import com.example.multiscan.core.time.Utc
import com.example.multiscan.data.prefs.SettingsSnapshot
import com.example.multiscan.data.remote.HealthCheckResult
import com.example.multiscan.data.remote.RegisterResult
import com.example.multiscan.ui.common.manualSyncResultMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI

/** 设置页一次性操作状态（保存 / 测试连通性 / 注册本机），全部在主线程之外完成 IO。 */
data class SettingsUiState(
    val saving: Boolean = false,
    val testing: Boolean = false,
    val registering: Boolean = false,
    val saveMessage: String? = null,
    val saveIsError: Boolean = false,
    val healthMessage: String? = null,
    val healthIsError: Boolean = false,
    val registerMessage: String? = null,
    val registerIsError: Boolean = false,
)

/**
 * 设置页 ViewModel。
 *
 * 网络全部走 `ServerRepository` 的 suspend API（内部 IO 调度），UI 线程只更新状态；
 * 服务器地址由 `SettingsStore.setServerUrl` 保存（内部会 trim，并在地址变化时清 token/deviceId）。
 *
 * v3（契约 §13.2）：同步设置（自动同步 / 仅 Wi-Fi）+ 手动同步（与 Home 共用一套结果提示）。
 */
class SettingsViewModel(context: Context, container: AppContainer) : ViewModel() {

    private val appContext: Context = context.applicationContext
    private val appContainer: AppContainer = container
    private val settingsStore = container.settingsStore
    private val serverRepository = container.serverRepository
    private val scanRepository = container.scanRepository

    val snapshot = settingsStore.snapshot
    val appVersion: String = resolveAppVersion(appContext)

    /** 待同步批次数量（用于同步设置卡的状态行）。 */
    val pendingSync = scanRepository.homeStats
        .map { stats -> stats.pendingSync }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** worker 是否正在同步（`SettingsSnapshot.SYNC_RUNNING`）。 */
    val syncing = settingsStore.snapshot
        .map { snapshot -> snapshot.lastSyncStatus == SettingsSnapshot.SYNC_RUNNING }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 手动同步请求在途（防连点）。 */
    private val _manualSyncInFlight = MutableStateFlow(false)
    val manualSyncInFlight: StateFlow<Boolean> = _manualSyncInFlight.asStateFlow()

    /** 一次性中文提示，Snackbar 展示后 consume。 */
    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    private val _ui = MutableStateFlow(SettingsUiState())
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    fun save(serverUrl: String, enrollSecret: String) {
        if (_ui.value.saving) return
        val url = serverUrl.trim()
        val secret = enrollSecret.trim()
        if (!isValidHttpUrl(url)) {
            _ui.update {
                it.copy(
                    saveMessage = appContext.getString(R.string.settings_invalid_url),
                    saveIsError = true,
                )
            }
            return
        }
        _ui.update { it.copy(saving = true, saveMessage = null) }
        viewModelScope.launch {
            runCatching {
                settingsStore.setServerUrl(url)
                settingsStore.setEnrollSecret(secret)
            }.onSuccess {
                _ui.update {
                    it.copy(
                        saving = false,
                        saveMessage = appContext.getString(R.string.settings_saved),
                        saveIsError = false,
                    )
                }
            }.onFailure { throwable ->
                _ui.update {
                    it.copy(
                        saving = false,
                        saveMessage = appContext.getString(
                            R.string.settings_save_failed,
                            throwable.message ?: appContext.getString(R.string.common_unknown),
                        ),
                        saveIsError = true,
                    )
                }
            }
        }
    }

    fun testHealth() {
        if (_ui.value.testing) return
        _ui.update { it.copy(testing = true, healthMessage = null) }
        viewModelScope.launch {
            val health = runCatching { serverRepository.testHealth() }.getOrNull()
            if (health == null) {
                _ui.update {
                    it.copy(
                        testing = false,
                        healthIsError = true,
                        healthMessage = appContext.getString(
                            R.string.settings_health_error,
                            appContext.getString(R.string.common_unknown),
                        ),
                    )
                }
                return@launch
            }
            when (health) {
                is HealthCheckResult.Ok -> _ui.update {
                    it.copy(
                        testing = false,
                        healthIsError = false,
                        healthMessage = appContext.getString(
                            R.string.settings_health_ok,
                            health.version,
                            Utc.formatDisplay(health.serverTime),
                        ),
                    )
                }

                is HealthCheckResult.Error -> _ui.update {
                    it.copy(
                        testing = false,
                        healthIsError = true,
                        healthMessage = appContext.getString(R.string.settings_health_error, health.message),
                    )
                }
            }
        }
    }

    fun registerDevice(deviceName: String, enrollSecret: String) {
        if (_ui.value.registering) return
        val secret = enrollSecret.trim()
        if (secret.isEmpty()) {
            _ui.update {
                it.copy(
                    registerMessage = appContext.getString(R.string.settings_register_need_secret),
                    registerIsError = true,
                )
            }
            return
        }
        _ui.update { it.copy(registering = true, registerMessage = null) }
        viewModelScope.launch {
            val result = runCatching {
                settingsStore.setEnrollSecret(secret)
                serverRepository.registerDevice(deviceName)
            }.getOrNull()
            if (result == null) {
                _ui.update {
                    it.copy(
                        registering = false,
                        registerIsError = true,
                        registerMessage = appContext.getString(
                            R.string.settings_register_error,
                            appContext.getString(R.string.common_unknown),
                        ),
                    )
                }
                return@launch
            }
            when (result) {
                is RegisterResult.Ok -> _ui.update {
                    it.copy(
                        registering = false,
                        registerIsError = false,
                        registerMessage = appContext.getString(
                            R.string.settings_register_ok,
                            result.deviceId,
                            result.maskedToken,
                        ),
                    )
                }

                is RegisterResult.Error -> _ui.update {
                    it.copy(
                        registering = false,
                        registerIsError = true,
                        registerMessage = appContext.getString(R.string.settings_register_error, result.message),
                    )
                }
            }
        }
    }

    /** 自动同步开关：只影响扫描完成/启动收尾时是否入队，不影响手动同步。 */
    fun setAutoSync(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { settingsStore.setAutoSync(enabled) }
                .onFailure { throwable ->
                    _syncMessage.value = appContext.getString(
                        R.string.sync_action_failed,
                        throwable.message ?: appContext.getString(R.string.common_unknown),
                    )
                }
        }
    }

    /** 仅 Wi-Fi 同步开关：保存后立即按新约束取消/重排唯一同步任务（契约 §13.1.2）。 */
    fun setSyncWifiOnly(enabled: Boolean) {
        viewModelScope.launch {
            runCatching {
                settingsStore.setSyncWifiOnly(enabled)
                appContainer.syncScheduler.reapplyConstraints(wifiOnly = enabled)
            }.onFailure { throwable ->
                _syncMessage.value = appContext.getString(
                    R.string.sync_action_failed,
                    throwable.message ?: appContext.getString(R.string.common_unknown),
                )
            }
        }
    }

    /**
     * 「立即同步」：不受自动同步开关限制；无 token/deviceId → NeedsRegistration（提示先去注册）。
     */
    fun manualSync() {
        if (_manualSyncInFlight.value) return
        _manualSyncInFlight.value = true
        viewModelScope.launch {
            val message = runCatching { scanRepository.requestManualSync() }
                .fold(
                    onSuccess = { result -> manualSyncResultMessage(appContext, result) },
                    onFailure = { throwable ->
                        appContext.getString(
                            R.string.sync_action_failed,
                            throwable.message ?: appContext.getString(R.string.common_unknown),
                        )
                    },
                )
            _syncMessage.value = message
            _manualSyncInFlight.value = false
        }
    }

    fun consumeSyncMessage() {
        _syncMessage.value = null
    }
}

/** 只接受 http(s)://host 形式的地址，避免把明显错误的值写进 SettingsStore。 */
private fun isValidHttpUrl(raw: String): Boolean {
    if (raw.isBlank()) return false
    return runCatching {
        val uri = URI(raw)
        val scheme = uri.scheme?.lowercase()
        (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}

@Suppress("DEPRECATION")
private fun resolveAppVersion(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: context.getString(R.string.common_unknown)
}.getOrDefault(context.getString(R.string.common_unknown))
