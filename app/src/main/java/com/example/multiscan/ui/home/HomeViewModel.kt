package com.example.multiscan.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.multiscan.AppContainer
import com.example.multiscan.R
import com.example.multiscan.data.prefs.SettingsSnapshot
import com.example.multiscan.ui.common.manualSyncResultMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 首页数据：只从 Repository/SettingsStore 的 Flow 派生，不做任何假数据。
 *
 * - `stats` = `ScanRepository.homeStats`（DAO 聚合 + SettingsStore 快照组合，data-sync 提供）
 * - `serverUrl` / `syncing` = `SettingsStore.snapshot`
 * - `manualSync()` = `ScanRepository.requestManualSync()`（契约 §13.1/§13.2：单向手动上传）
 */
class HomeViewModel(context: Context, container: AppContainer) : ViewModel() {

    private val appContext: Context = context.applicationContext
    private val repository = container.scanRepository
    private val settingsStore = container.settingsStore

    /** null = 第一份数据尚未到达。 */
    val stats = repository.homeStats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val serverUrl = settingsStore.snapshot
        .map { snapshot -> snapshot.serverUrl }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** 同步状态是否处于 "syncing"（SyncWorker 开始时写入，结束时改写为 success/failed/auth_error）。 */
    val syncing = settingsStore.snapshot
        .map { snapshot -> snapshot.lastSyncStatus == SettingsSnapshot.SYNC_RUNNING }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 手动同步请求在途（防止连点；与 worker 的 syncing 状态互补，避免按钮闪烁）。 */
    private val _manualSyncInFlight = MutableStateFlow(false)
    val manualSyncInFlight: StateFlow<Boolean> = _manualSyncInFlight.asStateFlow()

    /** 一次性的中文结果提示，Snackbar 展示后由页面 consume。 */
    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    /**
     * 「立即同步」：手动同步不受「自动同步」开关限制（契约 §13.1.3）。
     * 结果 → 中文 Snackbar；异常兜底为「同步失败：…」，不会崩溃。
     */
    fun manualSync() {
        if (_manualSyncInFlight.value) return
        _manualSyncInFlight.value = true
        viewModelScope.launch {
            val message = runCatching { repository.requestManualSync() }
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
