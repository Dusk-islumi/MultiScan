package com.example.multiscan.ui.debug

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.multiscan.AppContainer
import com.example.multiscan.R
import com.example.multiscan.ui.diag.DiagExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 调试面板的一次性 UI 状态（导出进度 / 结果消息 / 待分享文件）。 */
data class DebugUiState(
    val exporting: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val shareFile: File? = null,
)

/**
 * 调试面板 ViewModel：
 *  - 实时统计来自进程级 [ScanDebugBus]（扫描页存活期间由 ScanViewModel 镜像）；
 *  - 锚点开关直连 `SettingsStore.overlayVisible`；
 *  - 诊断导出在 IO 线程生成 zip，再由页面用 FileProvider 分享。
 */
class DebugViewModel(context: Context, container: AppContainer) : ViewModel() {

    private val appContext: Context = context.applicationContext
    private val appContainer: AppContainer = container
    private val settingsStore = container.settingsStore

    val stats = ScanDebugBus.stats
    val geometry = ScanDebugBus.geometry

    val overlayVisible: StateFlow<Boolean> = settingsStore.snapshot
        .map { snapshot -> snapshot.overlayVisible }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _ui = MutableStateFlow(DebugUiState())
    val ui: StateFlow<DebugUiState> = _ui.asStateFlow()

    fun setOverlayVisible(visible: Boolean) {
        viewModelScope.launch {
            runCatching { settingsStore.setOverlayVisible(visible) }
        }
    }

    fun exportDiagnostics() {
        if (_ui.value.exporting) return
        _ui.update { it.copy(exporting = true, message = null, messageIsError = false) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    DiagExporter.export(appContext, appContainer)
                }
            }.onSuccess { file ->
                _ui.update {
                    it.copy(
                        exporting = false,
                        message = appContext.getString(R.string.debug_export_done),
                        messageIsError = false,
                        shareFile = file,
                    )
                }
            }.onFailure { throwable ->
                _ui.update {
                    it.copy(
                        exporting = false,
                        message = appContext.getString(
                            R.string.debug_export_failed,
                            throwable.message ?: appContext.getString(R.string.common_unknown),
                        ),
                        messageIsError = true,
                    )
                }
            }
        }
    }

    /** 页面完成分享尝试后回调：errorMessage 为 null 表示已拉起分享器。 */
    fun consumeShareRequest(errorMessage: String?) {
        _ui.update {
            if (errorMessage != null) {
                it.copy(
                    message = appContext.getString(R.string.debug_share_failed, errorMessage),
                    messageIsError = true,
                    shareFile = null,
                )
            } else {
                it.copy(shareFile = null)
            }
        }
    }
}
