package com.example.multiscan.ui.debug

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.multiscan.AppContainer
import com.example.multiscan.R
import com.example.multiscan.ui.common.ScreenTopBar
import com.example.multiscan.ui.common.screenViewModel
import com.example.multiscan.ui.diag.DiagExporter
import com.example.multiscan.ui.theme.StatusSuccess

/**
 * 调试面板：实时 FPS / 解码命中率 / 单帧解码耗时 / 已解码帧 / 已分析帧 / 最近解码 pass /
 * track 数 / 丢帧数 / 相机分辨率 / 分析分辨率 + 锚点开关 + 诊断导出（契约 §12.5）。
 *
 * 统计来源是进程级 [ScanDebugBus]（扫描页关闭后仍保留最近一次会话数据；从未扫描则显示空态提示）。
 */
@Composable
fun DebugScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: DebugViewModel = screenViewModel { DebugViewModel(context, container) }
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val geometry by viewModel.geometry.collectAsStateWithLifecycle()
    val overlayVisible by viewModel.overlayVisible.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)

    // 导出完成后由页面负责拉起系统分享器（FileProvider + ACTION_SEND），失败只提示不崩溃。
    LaunchedEffect(ui.shareFile) {
        val file = ui.shareFile
        if (file != null) {
            val shareError = DiagExporter.share(context, file)
            viewModel.consumeShareRequest(shareError)
        }
    }

    val frame = geometry
    val resolutionText = if (frame != null) {
        stringResource(
            R.string.debug_value_resolution,
            frame.imageWidth,
            frame.imageHeight,
            frame.rotationDegrees,
        )
    } else if (stats.cameraResolution.isNotBlank()) {
        stats.cameraResolution
    } else {
        stringResource(R.string.debug_camera_unknown)
    }
    val hasSessionData = frame != null || stats.analyzedFrames > 0L || stats.fps > 0f
    // 契约 §12.5：decodeHitRate = decodedFrames / analyzedFrames；防御 NaN/越界，展示为百分比。
    val hitRatePercent = if (stats.decodeHitRate.isFinite()) {
        (stats.decodeHitRate * 100f).coerceIn(0f, 100f)
    } else {
        0f
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ScreenTopBar(
                title = stringResource(R.string.debug_title),
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DebugSection(title = stringResource(R.string.debug_section_camera)) {
                DebugRow(label = stringResource(R.string.debug_resolution), value = resolutionText)
                DebugRow(
                    label = stringResource(R.string.debug_analysis_resolution),
                    value = stats.analysisResolution.ifBlank {
                        stringResource(R.string.debug_camera_unknown)
                    },
                )
            }

            DebugSection(title = stringResource(R.string.debug_section_session)) {
                if (!hasSessionData) {
                    Text(
                        text = stringResource(R.string.debug_no_session),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                DebugRow(
                    label = stringResource(R.string.debug_fps),
                    value = stringResource(R.string.debug_value_fps, stats.fps),
                )
                DebugRow(
                    label = stringResource(R.string.debug_hit_rate),
                    value = stringResource(R.string.debug_value_percent, hitRatePercent),
                )
                DebugRow(
                    label = stringResource(R.string.debug_decode_last),
                    value = stringResource(R.string.debug_value_ms, stats.lastDecodeMs),
                )
                DebugRow(
                    label = stringResource(R.string.debug_decode_avg),
                    value = stringResource(R.string.debug_value_ms, stats.avgDecodeMs),
                )
                DebugRow(
                    label = stringResource(R.string.debug_decoded_frames),
                    value = stringResource(R.string.debug_value_int, stats.decodedFrames),
                )
                DebugRow(
                    label = stringResource(R.string.debug_analyzed),
                    value = stringResource(R.string.debug_value_int, stats.analyzedFrames),
                )
                DebugRow(
                    label = stringResource(R.string.debug_last_pass),
                    value = if (stats.lastDecodePass > 0) {
                        stringResource(R.string.debug_value_int, stats.lastDecodePass)
                    } else {
                        stringResource(R.string.debug_value_none)
                    },
                )
                DebugRow(
                    label = stringResource(R.string.debug_tracks),
                    value = stringResource(R.string.debug_value_int, stats.activeTracks),
                )
                DebugRow(
                    label = stringResource(R.string.debug_dropped),
                    value = stringResource(R.string.debug_value_int, stats.droppedFrames),
                )
            }

            DebugSection(title = stringResource(R.string.debug_section_overlay)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.debug_overlay_visible),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.debug_overlay_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = overlayVisible,
                        onCheckedChange = { checked -> viewModel.setOverlayVisible(checked) },
                    )
                }
            }

            DebugSection(title = stringResource(R.string.debug_section_export)) {
                Button(
                    onClick = { viewModel.exportDiagnostics() },
                    enabled = !ui.exporting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (ui.exporting) {
                            stringResource(R.string.debug_exporting)
                        } else {
                            stringResource(R.string.debug_export)
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.debug_export_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val message = ui.message
                if (message != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ui.messageIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            StatusSuccess
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DebugSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun DebugRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}
