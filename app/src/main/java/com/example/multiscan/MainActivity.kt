package com.example.multiscan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import com.example.multiscan.ui.debug.DebugScreen
import com.example.multiscan.ui.home.HomeScreen
import com.example.multiscan.ui.records.RecordsScreen
import com.example.multiscan.ui.scan.ScanScreen
import com.example.multiscan.ui.settings.SettingsScreen
import com.example.multiscan.ui.theme.MultiScanTheme
import kotlinx.coroutines.launch

/** 手写导航路由（不引入 navigation-compose）。Scan 的 key 用于区分每次扫描会话。 */
sealed interface Screen {
    data object Home : Screen
    data object Records : Screen
    data object Settings : Screen
    data object Debug : Screen
    data class Scan(val key: Long = 0) : Screen
}

private const val ROUTE_HOME = "home"
private const val ROUTE_RECORDS = "records"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_DEBUG = "debug"
private const val ROUTE_SCAN_PREFIX = "scan:"

/** 返回栈的 rememberSaveable 编码（进程被杀重建后仍能回到原页面）。 */
private val screenStackSaver = listSaver<List<Screen>, String>(
    save = { stack -> stack.map { screen -> encodeScreen(screen) } },
    restore = { encoded ->
        encoded.mapNotNull { value -> decodeScreen(value) }.ifEmpty { listOf(Screen.Home) }
    },
)

private fun encodeScreen(screen: Screen): String = when (screen) {
    Screen.Home -> ROUTE_HOME
    Screen.Records -> ROUTE_RECORDS
    Screen.Settings -> ROUTE_SETTINGS
    Screen.Debug -> ROUTE_DEBUG
    is Screen.Scan -> ROUTE_SCAN_PREFIX + screen.key
}

private fun decodeScreen(value: String): Screen? = when {
    value == ROUTE_HOME -> Screen.Home
    value == ROUTE_RECORDS -> Screen.Records
    value == ROUTE_SETTINGS -> Screen.Settings
    value == ROUTE_DEBUG -> Screen.Debug
    value.startsWith(ROUTE_SCAN_PREFIX) ->
        Screen.Scan(value.removePrefix(ROUTE_SCAN_PREFIX).toLongOrNull() ?: 0L)
    else -> null
}

/**
 * 单 Activity 入口：edge-to-edge + Compose；容器来自 [MultiScanApplication.container]。
 * 页面栈与返回行为由 [AppRoot] 手写实现（每个页面自己处理 BackHandler）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MultiScanApplication).container
        setContent {
            MultiScanTheme {
                AppRoot(container = container)
            }
        }
    }
}

/**
 * 根组合：维护 `List<Screen>` 返回栈（rememberSaveable），按栈顶渲染页面；
 * 扫描结束的 summary 在这里统一显示 snackbar（「共 N 个，新扫 M 个」）。
 *
 * 资源读取用 `LocalResources.current` 而不是 `LocalContext.current.getString(...)`：
 * 后者不会随配置变化（语言/字体缩放）自动失效，Compose lint 会报 LocalContextGetResourceValueCall。
 */
@Composable
fun AppRoot(container: AppContainer) {
    var stack by rememberSaveable(stateSaver = screenStackSaver) {
        mutableStateOf(listOf<Screen>(Screen.Home))
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current

    fun push(screen: Screen) {
        stack = stack + screen
    }

    fun pop() {
        stack = if (stack.size > 1) stack.dropLast(1) else listOf(Screen.Home)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val current = stack.last()) {
            Screen.Home -> HomeScreen(
                container = container,
                onScan = { push(Screen.Scan(key = System.currentTimeMillis())) },
                onRecords = { push(Screen.Records) },
                onSettings = { push(Screen.Settings) },
            )

            Screen.Records -> RecordsScreen(
                container = container,
                onBack = { pop() },
            )

            Screen.Settings -> SettingsScreen(
                container = container,
                onBack = { pop() },
                onDebug = { push(Screen.Debug) },
            )

            Screen.Debug -> DebugScreen(
                container = container,
                onBack = { pop() },
            )

            is Screen.Scan -> ScanScreen(
                container = container,
                sessionKey = current.key,
                onFinished = { summary ->
                    pop()
                    val message = if (summary.total > 0) {
                        resources.getString(
                            R.string.scan_finished_message,
                            summary.total,
                            summary.newCount,
                        )
                    } else {
                        resources.getString(R.string.scan_finished_empty)
                    }
                    scope.launch { snackbarHostState.showSnackbar(message) }
                },
                onExit = { pop() },
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
