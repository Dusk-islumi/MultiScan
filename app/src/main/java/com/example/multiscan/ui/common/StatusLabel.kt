package com.example.multiscan.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.multiscan.R
import com.example.multiscan.ui.theme.StatusDanger
import com.example.multiscan.ui.theme.StatusInfo
import com.example.multiscan.ui.theme.StatusNeutral
import com.example.multiscan.ui.theme.StatusSuccess
import com.example.multiscan.ui.theme.StatusWarning

/** 状态徽标的展示数据：中文文案 + 语义色。 */
data class StatusLabel(val text: String, val color: Color)

/**
 * 首页顶部连接状态卡的状态文案。
 *
 * 状态语义（契约 §6.2/§6.4）：serverUrl 为空 → 未连接；`auth_error` → 登录失效；
 * `failed` → 同步失败；`success` → 同步成功；其余（含 never / 从未同步）→ 从未同步。
 */
@Composable
fun homeSyncStatusLabel(
    lastSyncStatus: String,
    lastSyncAt: String?,
    serverUrl: String,
): StatusLabel {
    val status = lastSyncStatus.lowercase()
    return when {
        serverUrl.isBlank() -> StatusLabel(stringResource(R.string.home_status_not_connected), StatusNeutral)
        status == "auth_error" -> StatusLabel(stringResource(R.string.home_status_auth_error), StatusDanger)
        status == "failed" -> StatusLabel(stringResource(R.string.home_status_failed), StatusDanger)
        status == "success" && !lastSyncAt.isNullOrBlank() ->
            StatusLabel(stringResource(R.string.home_status_success), StatusSuccess)
        else -> StatusLabel(stringResource(R.string.home_status_never), StatusWarning)
    }
}

/**
 * 记录页批次的同步徽标：优先看 outbox 原始状态（pending|uploading|failed|auth_error|synced），
 * 兜底看 session 状态（OPEN|FINISHED|SYNCED|FAILED）。
 */
@Composable
fun outboxStatusLabel(outboxState: String?, sessionStateName: String): StatusLabel {
    val state = outboxState?.lowercase()
    return when {
        state == "auth_error" -> StatusLabel(stringResource(R.string.records_state_auth_error), StatusDanger)
        state == "failed" -> StatusLabel(stringResource(R.string.records_state_failed), StatusDanger)
        state == "uploading" -> StatusLabel(stringResource(R.string.records_state_syncing), StatusInfo)
        state == "synced" -> StatusLabel(stringResource(R.string.records_state_synced), StatusSuccess)
        state == "pending" -> StatusLabel(stringResource(R.string.records_state_pending), StatusWarning)
        sessionStateName == "SYNCED" -> StatusLabel(stringResource(R.string.records_state_synced), StatusSuccess)
        sessionStateName == "FAILED" -> StatusLabel(stringResource(R.string.records_state_failed), StatusDanger)
        sessionStateName == "OPEN" -> StatusLabel(stringResource(R.string.records_state_open), StatusNeutral)
        else -> StatusLabel(stringResource(R.string.records_state_pending), StatusWarning)
    }
}

/** 圆角状态徽标（不用 M3 Chip，避免实验性 API 依赖）。 */
@Composable
fun StatusBadge(label: StatusLabel, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = label.color.copy(alpha = 0.16f),
    ) {
        Text(
            text = label.text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            color = label.color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
