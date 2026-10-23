package com.example.multiscan.ui.debug

import com.example.multiscan.scan.model.FrameGeometry
import com.example.multiscan.scan.model.ScanDebugStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 扫描调试统计的进程级总线（**经 Lead 批准的例外**）。
 *
 * 背景：`ScanController` 只在扫描页存活，而调试面板在扫描页关闭后仍然要显示最近一次会话的
 * FPS/解码耗时/track 数/相机分辨率。UI 层用这个轻量 object 做单向镜像：
 * ScanViewModel 在扫描页存活期间把 `controller.debugStats` / `uiState.geometry` 写入总线，
 * DebugScreen 只读。
 *
 * 安全约束：**只保存值类型数据**，绝不保存 Context / Activity / PreviewView 等引用，避免泄漏；
 * 它不参与业务逻辑（无 Repository/网络/数据库调用），仅用于诊断展示与导出。
 *
 * v2（契约 §12.5）新增的 `decodedFrames` / `decodeHitRate` / `analysisResolution` / `lastDecodePass`
 * 随整个 [ScanDebugStats] 值对象一起镜像（本类不逐字段处理，保持值类型、无引用）。
 */
object ScanDebugBus {

    private val _stats = MutableStateFlow(ScanDebugStats())
    private val _geometry = MutableStateFlow<FrameGeometry?>(null)

    /** 最近一次扫描会话的实时统计；从未扫描时为默认空值（不是假数据）。 */
    val stats: StateFlow<ScanDebugStats> = _stats.asStateFlow()

    /** 最近一帧的分析图几何信息（分辨率 + 旋转角），用于叠加层映射与调试展示。 */
    val geometry: StateFlow<FrameGeometry?> = _geometry.asStateFlow()

    fun publishStats(stats: ScanDebugStats) {
        _stats.value = stats
    }

    fun publishGeometry(geometry: FrameGeometry?) {
        _geometry.value = geometry
    }

    fun reset() {
        _stats.value = ScanDebugStats()
        _geometry.value = null
    }
}
