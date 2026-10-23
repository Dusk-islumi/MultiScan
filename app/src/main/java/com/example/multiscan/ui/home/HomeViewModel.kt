package com.example.multiscan.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.multiscan.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 首页数据：只从 Repository/SettingsStore 的 Flow 派生，不做任何假数据。
 *
 * - `stats` = `ScanRepository.homeStats`（DAO 聚合 + SettingsStore 快照组合，data-sync 提供）
 * - `serverUrl` = `SettingsStore.snapshot.serverUrl`（顶部连接状态卡展示）
 */
class HomeViewModel(container: AppContainer) : ViewModel() {

    private val repository = container.scanRepository
    private val settingsStore = container.settingsStore

    /** null = 第一份数据尚未到达。 */
    val stats = repository.homeStats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val serverUrl = settingsStore.snapshot
        .map { snapshot -> snapshot.serverUrl }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
}
