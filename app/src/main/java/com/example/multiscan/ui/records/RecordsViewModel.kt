package com.example.multiscan.ui.records

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.multiscan.AppContainer
import com.example.multiscan.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 记录页 ViewModel：只读 `ScanRepository.sessions`（已收尾的批次，最新在前）+ 两个人工操作。
 *
 * `retryOutbox(id)`：data-sync 已确认接受 **sessionId 或 outbox 行 id**（outbox.session_id 唯一 1:1），
 * 因此这里直接传 `SessionRecord.sessionId`。
 */
class RecordsViewModel(context: Context, container: AppContainer) : ViewModel() {

    private val appContext: Context = context.applicationContext
    private val repository = container.scanRepository

    /** null = 首份数据尚未到达。 */
    val sessions = repository.sessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun retry(sessionId: String) {
        viewModelScope.launch {
            runCatching { repository.retryOutbox(sessionId) }
                .onSuccess { _message.value = appContext.getString(R.string.records_retry_done) }
                .onFailure { throwable ->
                    _message.value = appContext.getString(
                        R.string.records_action_failed,
                        throwable.message ?: appContext.getString(R.string.common_unknown),
                    )
                }
        }
    }

    fun clearSynced() {
        viewModelScope.launch {
            runCatching { repository.clearSyncedData() }
                .onSuccess { _message.value = appContext.getString(R.string.records_cleared) }
                .onFailure { throwable ->
                    _message.value = appContext.getString(
                        R.string.records_action_failed,
                        throwable.message ?: appContext.getString(R.string.common_unknown),
                    )
                }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
