package com.example.multiscan.ui.scan

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.multiscan.AppContainer
import com.example.multiscan.R
import com.example.multiscan.scan.mapper.CoordinateMapper
import com.example.multiscan.scan.model.ScanSessionSummary
import com.example.multiscan.ui.common.screenViewModel
import com.example.multiscan.ui.theme.ScanExistingAnchorGray
import com.example.multiscan.ui.theme.ScanNewAnchorGreen

/**
 * 扫描页（契约 §7 核心页面）。
 *
 * 结构：
 *  - 相机权限未授予 → 中文说明页（去授权 / 去设置），不崩溃；
 *  - 已授予 → 全屏 [PreviewView]（FILL_CENTER）+ Compose Canvas 叠加识别框；
 *  - 手势（契约 §12.4）：透明手势层单击对焦、双击 1x↔2x、双指捏合缩放；底部栏显示当前倍率；
 *  - 右上角手电筒/完成按钮；底部半透明「本次已扫 N / 新扫 M」；
 *  - 返回键弹「结束本次扫描？」确认；确认后 finishSession 并回首页（snackbar 由 AppRoot 显示）。
 *
 * 性能：PreviewView 只在 AndroidView factory 中创建一次（remember），不参与重组；
 * 状态更新频率已由 ScanController 节流（10–15Hz），这里只 collect。
 */
@Composable
fun ScanScreen(
    container: AppContainer,
    sessionKey: Long,
    onFinished: (ScanSessionSummary) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModel: ScanViewModel = screenViewModel { ScanViewModel(context, container) }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val overlayVisible by viewModel.overlayVisible.collectAsStateWithLifecycle()
    val viewModelError by viewModel.error.collectAsStateWithLifecycle()
    val finishing by viewModel.finishing.collectAsStateWithLifecycle()
    val sessionStarted by viewModel.sessionStarted.collectAsStateWithLifecycle()

    var permissionGranted by remember { mutableStateOf(hasCameraPermission(context)) }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var autoRequested by rememberSaveable { mutableStateOf(false) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    var showExitDialog by rememberSaveable(sessionKey) { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        permanentlyDenied = if (granted) {
            false
        } else {
            val activity = context.findActivity()
            permissionRequested && activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) != true
        }
    }

    // 首次进入自动申请一次相机权限；被拒绝后不再自动弹（避免死循环），由说明页按钮触发。
    LaunchedEffect(Unit) {
        if (!permissionGranted && !autoRequested) {
            autoRequested = true
            permissionRequested = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // 从系统设置页返回时重新检查授权状态（去设置 → 开启 → 返回即可恢复扫描）。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = hasCameraPermission(context)
                permissionGranted = granted
                if (granted) permanentlyDenied = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 拿到相机权限后才创建 DB 会话，避免「只看到权限说明页就退出」产生空会话。
    LaunchedEffect(permissionGranted) {
        if (permissionGranted) viewModel.startSession()
    }

    // PreviewView 只创建一次；COMPATIBLE(TextureView) 让 Compose 覆盖层稳定绘制在预览之上。
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(previewView, permissionGranted, lifecycleOwner) {
        if (permissionGranted) {
            viewModel.bind(previewView, lifecycleOwner)
        }
        onDispose { viewModel.unbind() }
    }

    fun finishAndExit() {
        showExitDialog = false
        viewModel.finish { summary -> onFinished(summary) }
    }

    // 结束中（awaiting finishSession）也不允许 back 直接退出 Activity：忽略返回，等待协程结束。
    BackHandler {
        if (finishing) return@BackHandler
        if (sessionStarted) {
            showExitDialog = true
        } else {
            onExit()
        }
    }

    val currentState = uiState
    val viewModelErrorMessage = viewModelError
    val scanStateError = currentState.error
    val displayError: String? = when {
        viewModelErrorMessage != null -> viewModelErrorMessage
        scanStateError != null -> stringResource(R.string.scan_error_prefix, scanStateError)
        else -> null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (permissionGranted) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
            )

            if (overlayVisible) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val geometry = currentState.geometry ?: return@Canvas
                    val viewWidth = size.width.toInt()
                    val viewHeight = size.height.toInt()
                    if (viewWidth <= 0 || viewHeight <= 0) return@Canvas
                    val strokeWidthPx = 3.dp.toPx()
                    for (anchor in currentState.anchors) {
                        val mapped = CoordinateMapper.mapQuad(geometry, viewWidth, viewHeight, anchor.quad)
                        if (mapped.size < 3) continue
                        val path = Path()
                        path.moveTo(mapped[0].x, mapped[0].y)
                        for (index in 1 until mapped.size) {
                            path.lineTo(mapped[index].x, mapped[index].y)
                        }
                        path.close()
                        // 先画深色粗描边再画彩色细线 → 亮/暗背景下都清晰。
                        drawPath(
                            path = path,
                            color = Color.Black.copy(alpha = 0.75f),
                            style = Stroke(width = strokeWidthPx + 2f, join = StrokeJoin.Round),
                        )
                        drawPath(
                            path = path,
                            color = if (anchor.isNew) ScanNewAnchorGreen else ScanExistingAnchorGray,
                            style = Stroke(width = strokeWidthPx, join = StrokeJoin.Round),
                        )
                    }
                }
            }

            // 手势层（契约 §12.4）：透明 fillMaxSize Box 叠在 PreviewView/Canvas 之上、右上角按钮之下。
            // 单击 → tapToFocus(视图坐标)；双击 → 1x↔2x；双指捏合 → zoomBy(相对增量)。
            // 位于本 Box 之前的 PreviewView/Canvas 不参与命中测试，因此手势会先到这里；
            // ScanControls / ScanBottomBar 在其后声明，仍处于更上层，按钮点击不受影响。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { offset -> viewModel.tapToFocus(offset.x, offset.y) },
                            onDoubleTap = { viewModel.toggleZoom() },
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures(panZoomLock = false) { _, _, zoom, _ ->
                            if (zoom != 1f) viewModel.zoomBy(zoom)
                        }
                    },
            )

            ScanControls(
                torchOn = currentState.torchOn,
                torchAvailable = currentState.torchAvailable,
                finishing = finishing,
                onToggleTorch = { viewModel.setTorch(!currentState.torchOn) },
                onFinish = { finishAndExit() },
                modifier = Modifier.align(Alignment.TopEnd),
            )

            ScanBottomBar(
                total = currentState.totalDetected,
                newCount = currentState.newDetected,
                zoomRatio = currentState.zoomRatio,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else {
            CameraPermissionPage(
                permanentlyDenied = permanentlyDenied,
                onRequest = {
                    permissionRequested = true
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                },
                onOpenSettings = { openAppSettings(context) },
                onExit = onExit,
            )
        }

        if (displayError != null) {
            ErrorBanner(
                text = displayError,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding(),
            )
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { if (!finishing) showExitDialog = false },
            title = { Text(stringResource(R.string.scan_exit_title)) },
            text = { Text(stringResource(R.string.scan_exit_message)) },
            confirmButton = {
                TextButton(onClick = { finishAndExit() }, enabled = !finishing) {
                    Text(stringResource(R.string.scan_exit_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }, enabled = !finishing) {
                    Text(stringResource(R.string.scan_exit_cancel))
                }
            },
        )
    }
}

@Composable
private fun ScanControls(
    torchOn: Boolean,
    torchAvailable: Boolean,
    finishing: Boolean,
    onToggleTorch: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = Color.Black.copy(alpha = 0.45f),
            shape = RoundedCornerShape(24.dp),
        ) {
            TextButton(
                onClick = onToggleTorch,
                enabled = torchAvailable,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color.White,
                    disabledContentColor = Color.White.copy(alpha = 0.35f),
                ),
            ) {
                Text(
                    text = when {
                        !torchAvailable -> stringResource(R.string.scan_torch)
                        torchOn -> stringResource(R.string.scan_torch_on)
                        else -> stringResource(R.string.scan_torch_off)
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Surface(
            color = Color.Black.copy(alpha = 0.45f),
            shape = RoundedCornerShape(24.dp),
        ) {
            TextButton(
                onClick = onFinish,
                enabled = !finishing,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color.White,
                    disabledContentColor = Color.White.copy(alpha = 0.35f),
                ),
            ) {
                Text(
                    text = if (finishing) {
                        stringResource(R.string.scan_finishing)
                    } else {
                        stringResource(R.string.scan_finish)
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun ScanBottomBar(
    total: Int,
    newCount: Int,
    zoomRatio: Float,
    modifier: Modifier = Modifier,
) {
    // 倍率 ≈1.0x 时不显示（契约 §12.4：扫描页显示当前倍率）。
    val showZoomChip = zoomRatio > 1.05f || zoomRatio < 0.95f
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Black.copy(alpha = 0.5f),
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.scan_session_total, total),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.scan_session_new, newCount),
                    color = ScanNewAnchorGreen,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (showZoomChip) {
                    ZoomChip(zoomRatio = zoomRatio)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.scan_gesture_hint),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** 当前相机倍率小徽标，例如 `2.0x`（契约 §12.4）。 */
@Composable
private fun ZoomChip(zoomRatio: Float) {
    Surface(
        color = Color.White.copy(alpha = 0.18f),
        shape = RoundedCornerShape(percent = 50),
    ) {
        Text(
            text = stringResource(R.string.scan_zoom_format, zoomRatio),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ErrorBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(top = 64.dp, start = 16.dp, end = 16.dp),
        color = Color(0xCC7F1D1D),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CameraPermissionPage(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onExit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.scan_blocked_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (permanentlyDenied) {
                stringResource(R.string.scan_blocked_permanent)
            } else {
                stringResource(R.string.scan_blocked_message)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        if (permanentlyDenied) {
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.scan_open_settings))
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.scan_grant))
            }
        } else {
            Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.scan_grant))
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.scan_open_settings))
            }
        }
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onExit) {
            Text(stringResource(R.string.common_back))
        }
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
