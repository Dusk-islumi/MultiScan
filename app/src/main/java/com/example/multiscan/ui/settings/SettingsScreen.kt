package com.example.multiscan.ui.settings

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.multiscan.AppContainer
import com.example.multiscan.R
import com.example.multiscan.core.time.Utc
import com.example.multiscan.ui.common.ScreenTopBar
import com.example.multiscan.ui.common.StatusBadge
import com.example.multiscan.ui.common.StatusLabel
import com.example.multiscan.ui.common.screenViewModel
import com.example.multiscan.ui.common.syncStatusLabel
import com.example.multiscan.ui.theme.StatusSuccess

/**
 * 设置页：服务器地址 / 注册口令（密码样式 + 显示切换）/ 保存 / 测试连通性 / 注册本机 /
 * 设备信息 / 调试面板入口。所有网络操作由 ViewModel 在协程中完成，主线程只更新状态。
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onDebug: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = screenViewModel { SettingsViewModel(context, container) }
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val pendingSync by viewModel.pendingSync.collectAsStateWithLifecycle()
    val workerSyncing by viewModel.syncing.collectAsStateWithLifecycle()
    val manualSyncInFlight by viewModel.manualSyncInFlight.collectAsStateWithLifecycle()
    val syncMessage by viewModel.syncMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val syncInProgress = manualSyncInFlight || workerSyncing

    LaunchedEffect(syncMessage) {
        val message = syncMessage
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeSyncMessage()
        }
    }

    // 输入框以 SettingsStore 快照为初值；保存后快照变化才重置（输入过程中不会被冲掉）。
    var serverUrlInput by remember(snapshot.serverUrl) { mutableStateOf(snapshot.serverUrl) }
    var enrollSecretInput by remember(snapshot.enrollSecret) { mutableStateOf(snapshot.enrollSecret) }
    var secretVisible by rememberSaveable { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ScreenTopBar(
                title = stringResource(R.string.settings_title),
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
            SectionCard(title = stringResource(R.string.settings_section_server)) {
                OutlinedTextField(
                    value = serverUrlInput,
                    onValueChange = { serverUrlInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_server_url)) },
                    singleLine = true,
                    enabled = !ui.saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = enrollSecretInput,
                    onValueChange = { enrollSecretInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_enroll_secret)) },
                    singleLine = true,
                    enabled = !ui.saving,
                    visualTransformation = if (secretVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { secretVisible = !secretVisible }) {
                            Text(
                                text = if (secretVisible) {
                                    stringResource(R.string.common_hide)
                                } else {
                                    stringResource(R.string.common_show)
                                },
                            )
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { viewModel.save(serverUrlInput, enrollSecretInput) },
                        enabled = !ui.saving,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = if (ui.saving) {
                                stringResource(R.string.settings_saving)
                            } else {
                                stringResource(R.string.settings_save)
                            },
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.testHealth() },
                        enabled = !ui.testing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = if (ui.testing) {
                                stringResource(R.string.settings_testing)
                            } else {
                                stringResource(R.string.settings_test_health)
                            },
                        )
                    }
                }

                val saveMessage = ui.saveMessage
                if (saveMessage != null) {
                    StatusText(text = saveMessage, isError = ui.saveIsError)
                }
                val healthMessage = ui.healthMessage
                if (healthMessage != null) {
                    StatusText(text = healthMessage, isError = ui.healthIsError)
                }

                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { viewModel.registerDevice(Build.MODEL, enrollSecretInput) },
                    enabled = !ui.registering,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (ui.registering) {
                            stringResource(R.string.settings_registering)
                        } else {
                            stringResource(R.string.settings_register)
                        },
                    )
                }
                val registerMessage = ui.registerMessage
                if (registerMessage != null) {
                    StatusText(text = registerMessage, isError = ui.registerIsError)
                }
            }

            // 同步设置（契约 §13.2）：单向说明 + 自动同步 / 仅 Wi-Fi + 状态行 + 立即同步。
            SectionCard(title = stringResource(R.string.settings_section_sync)) {
                Text(
                    text = stringResource(R.string.settings_sync_one_way),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                SwitchRow(
                    title = stringResource(R.string.settings_auto_sync),
                    description = stringResource(R.string.settings_auto_sync_desc),
                    checked = snapshot.autoSync,
                    onCheckedChange = { enabled -> viewModel.setAutoSync(enabled) },
                )
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    title = stringResource(R.string.settings_wifi_only),
                    description = stringResource(R.string.settings_wifi_only_desc),
                    checked = snapshot.syncWifiOnly,
                    onCheckedChange = { enabled -> viewModel.setSyncWifiOnly(enabled) },
                )
                Spacer(Modifier.height(12.dp))
                InfoLine(
                    label = stringResource(R.string.settings_sync_pending),
                    value = stringResource(R.string.sync_pending_value, pendingSync),
                )
                InfoLine(
                    label = stringResource(R.string.settings_sync_last),
                    value = Utc.formatDisplay(snapshot.lastSyncAt),
                )
                StatusInfoLine(
                    label = stringResource(R.string.settings_sync_status),
                    status = syncStatusLabel(
                        lastSyncStatus = snapshot.lastSyncStatus,
                        lastSyncAt = snapshot.lastSyncAt,
                    ),
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { viewModel.manualSync() },
                    enabled = !syncInProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (syncInProgress) {
                            stringResource(R.string.sync_in_progress)
                        } else {
                            stringResource(R.string.sync_now)
                        },
                    )
                }
            }

            SectionCard(title = stringResource(R.string.settings_device_info)) {
                InfoLine(label = stringResource(R.string.settings_device_model), value = Build.MODEL)
                InfoLine(label = stringResource(R.string.settings_device_manufacturer), value = Build.MANUFACTURER)
                InfoLine(
                    label = stringResource(R.string.settings_device_os),
                    value = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")",
                )
                InfoLine(label = stringResource(R.string.settings_device_app_version), value = viewModel.appVersion)
                InfoLine(
                    label = stringResource(R.string.settings_device_id),
                    value = snapshot.deviceId ?: stringResource(R.string.settings_not_registered),
                )
                InfoLine(
                    label = stringResource(R.string.settings_device_token),
                    value = snapshot.maskedToken ?: stringResource(R.string.settings_not_registered),
                )
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDebug),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_debug_entry),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.settings_debug_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
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
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun StatusText(text: String, isError: Boolean) {
    Spacer(Modifier.height(10.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else StatusSuccess,
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(96.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 同步设置里的开关行：标题 + 说明 + Switch。 */
@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 同步设置里的状态行：左侧标签 + 右侧彩色状态徽标。 */
@Composable
private fun StatusInfoLine(label: String, status: StatusLabel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(96.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        StatusBadge(label = status)
    }
}
