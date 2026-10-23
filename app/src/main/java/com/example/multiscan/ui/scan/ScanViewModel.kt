package com.example.multiscan.ui.scan

import android.content.Context
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.multiscan.AppContainer
import com.example.multiscan.R
import com.example.multiscan.scan.ScanController
import com.example.multiscan.scan.model.ScanSessionSummary
import com.example.multiscan.ui.debug.ScanDebugBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 扫描页 ViewModel：持有契约 §5 的 [ScanController]，把「相机绑定 / 会话生命周期 / 调试统计镜像」
 * 从 Compose 组合中隔离出来。
 *
 * 生命周期：由 [com.example.multiscan.ui.common.screenViewModel] 绑定到扫描页组合，
 * 页面离开 → onCleared → controller.release()（兜底 unbind + 关闭资源，不结束未 finish 的会话）。
 */
class ScanViewModel(context: Context, container: AppContainer) : ViewModel() {

    private val appContext: Context = context.applicationContext
    private val diagLog = container.diagLog
    private val controller = ScanController(appContext, container.scanRepository, diagLog)

    /** 契约 §5：含 anchors/totalDetected/newDetected/torch/geometry/error，更新已由 controller 节流。 */
    val uiState = controller.uiState

    /** 是否在该页绘制识别框：来自 SettingsStore.overlay_visible（调试面板可切）。 */
    val overlayVisible: StateFlow<Boolean> = container.settingsStore.snapshot
        .map { snapshot -> snapshot.overlayVisible }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** ViewModel 侧的非致命错误（相机绑定失败 / 结束会话失败 / 创建会话失败）。 */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _finishing = MutableStateFlow(false)
    val finishing: StateFlow<Boolean> = _finishing.asStateFlow()

    /** 是否已成功发起过 DB 会话（决定返回键是「确认结束」还是「直接退出」）。 */
    private val _sessionStarted = MutableStateFlow(false)
    val sessionStarted: StateFlow<Boolean> = _sessionStarted.asStateFlow()

    init {
        // 把 controller 的调试统计镜像到进程级总线，供扫描页之外的调试面板读取。
        viewModelScope.launch {
            controller.debugStats.collect { stats -> ScanDebugBus.publishStats(stats) }
        }
        // 相机分辨率/旋转角来自 uiState.geometry（与叠加层使用的同一份几何信息）。
        viewModelScope.launch {
            controller.uiState.collect { state -> ScanDebugBus.publishGeometry(state.geometry) }
        }
    }

    /** 进入扫描页（拿到相机权限后）调用；controller 内部对重复调用为 no-op。 */
    fun startSession() {
        if (_sessionStarted.value || _finishing.value) return
        _sessionStarted.value = true
        viewModelScope.launch {
            runCatching { controller.startSession() }
                .onFailure { throwable ->
                    _sessionStarted.value = false
                    _error.value = appContext.getString(
                        R.string.scan_start_failed,
                        throwable.message ?: appContext.getString(R.string.common_unknown),
                    )
                    diagLog.log("ScanUI", "startSession failed: ${throwable.javaClass.simpleName}")
                }
        }
    }

    fun bind(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        runCatching { controller.bind(previewView, lifecycleOwner) }
            .onFailure { throwable ->
                _error.value = appContext.getString(
                    R.string.scan_camera_failed,
                    throwable.message ?: appContext.getString(R.string.common_unknown),
                )
                diagLog.log("ScanUI", "bind failed: ${throwable.javaClass.simpleName}")
            }
    }

    fun unbind() {
        runCatching { controller.unbind() }
    }

    fun setTorch(enabled: Boolean) {
        runCatching { controller.setTorch(enabled) }
            .onFailure { diagLog.log("ScanUI", "setTorch failed: ${it.javaClass.simpleName}") }
    }

    /**
     * 单击预览：以视图坐标（Box 内像素）对焦测光。
     * 契约 §12.4：controller 用 `previewView.meteringPointFactory` 把视图坐标转为 metering 点。
     */
    fun tapToFocus(viewX: Float, viewY: Float) {
        runCatching { controller.tapToFocus(viewX, viewY) }
            .onFailure { diagLog.log("ScanUI", "tapToFocus failed: ${it.javaClass.simpleName}") }
    }

    /** 双指捏合：factor 为相对缩放增量（controller 内部按 min/max 倍率 clamp）。 */
    fun zoomBy(factor: Float) {
        runCatching { controller.zoomBy(factor) }
            .onFailure { diagLog.log("ScanUI", "zoomBy failed: ${it.javaClass.simpleName}") }
    }

    /** 双击预览：1x ↔ min(2.0x, maxZoomRatio) 切换。 */
    fun toggleZoom() {
        runCatching { controller.toggleZoom() }
            .onFailure { diagLog.log("ScanUI", "toggleZoom failed: ${it.javaClass.simpleName}") }
    }

    /** 结束会话：成功后回调 summary（主线程），由页面负责返回 + snackbar。 */
    fun finish(onFinished: (ScanSessionSummary) -> Unit) {
        if (_finishing.value) return
        _finishing.value = true
        viewModelScope.launch {
            runCatching { controller.finishSession() }
                .onSuccess { summary -> onFinished(summary) }
                .onFailure { throwable ->
                    _error.value = appContext.getString(
                        R.string.scan_finish_failed,
                        throwable.message ?: appContext.getString(R.string.common_unknown),
                    )
                    diagLog.log("ScanUI", "finishSession failed: ${throwable.javaClass.simpleName}")
                }
            _finishing.value = false
        }
    }

    override fun onCleared() {
        runCatching { controller.release() }
        super.onCleared()
    }
}
