package com.example.multiscan

import android.content.Context
import com.example.multiscan.core.diag.DiagLog
import com.example.multiscan.data.local.AppDatabase
import com.example.multiscan.data.local.RoomTransactionRunner
import com.example.multiscan.data.prefs.SettingsStore
import com.example.multiscan.data.remote.NetworkFactory
import com.example.multiscan.data.remote.ServerRepository
import com.example.multiscan.data.repository.ScanRepository
import com.example.multiscan.data.sync.SyncEngine
import com.example.multiscan.data.sync.SyncScheduler
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 手写单例容器（需求明确禁止 Hilt / Koin）。
 *
 * 依赖装配顺序（全部 lazy，首次访问才创建）：
 *   Context → DiagLog
 *           → AppDatabase → DAO/TransactionRunner
 *           → SettingsStore（DataStore + Keystore 加密 token）
 *           → NetworkFactory → ServerRepository
 *           → SyncScheduler → ScanRepository / SyncEngine
 *
 * 生命周期：由 [MultiScanApplication.onCreate] 创建并调用一次 [initialize]；
 * 进程内所有页面 / Worker / Repository 共享同一实例。
 */
class AppContainer(private val appContext: Context) {

    val context: Context get() = appContext

    /** 进程内诊断日志环形缓冲（扫描/同步/UI 写入，调试面板导出）。 */
    val diagLog: DiagLog by lazy { DiagLog() }

    /** Room 数据库（v1，无迁移）。 */
    val database: AppDatabase by lazy { AppDatabase.getInstance(appContext) }

    /** 设置 + 加密 token 的 DataStore 封装。 */
    val settingsStore: SettingsStore by lazy { SettingsStore(appContext, diagLog) }

    private val networkFactory: NetworkFactory by lazy { NetworkFactory(settingsStore) }

    /** 服务器地址 / 注册 / 健康检查。 */
    val serverRepository: ServerRepository by lazy { ServerRepository(settingsStore, networkFactory) }

    /** WorkManager 唯一同步任务调度器（唯一任务名 "sync-scans"）。 */
    val syncScheduler: SyncScheduler by lazy { SyncScheduler(appContext) }

    private val transactionRunner: RoomTransactionRunner by lazy { RoomTransactionRunner(database) }

    /** 本地仓库：实现 scan 模块的 [com.example.multiscan.core.contracts.ScanSessionStore]，并暴露 UI 数据流。 */
    val scanRepository: ScanRepository by lazy {
        ScanRepository(
            sessionDao = database.sessionDao(),
            scanItemDao = database.scanItemDao(),
            outboxDao = database.outboxDao(),
            tx = transactionRunner,
            syncScheduler = syncScheduler,
            diagLog = diagLog,
            settings = settingsStore,
        )
    }

    /** 同步引擎（供 SyncWorker 调用；纯逻辑，可用 fake 在 JVM 单测驱动）。 */
    val syncEngine: SyncEngine by lazy {
        SyncEngine(
            sessionDao = database.sessionDao(),
            scanItemDao = database.scanItemDao(),
            outboxDao = database.outboxDao(),
            tx = transactionRunner,
            settings = settingsStore,
            apiProvider = { networkFactory.createApi() },
        )
    }

    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            diagLog.log(TAG, "background failure: ${throwable::class.java.simpleName}")
        },
    )

    /**
     * App 启动收尾：
     *  1. 崩溃恢复：上次被杀时残留的 open 会话 → finished 并入队 outbox；
     *  2. 若「自动同步」开关开启，触发一次同步调度（唯一任务 KEEP；「仅 Wi-Fi」偏好作为约束传入）；
     *     关闭自动同步时不自动入队，用户可在首页/设置页点「立即同步」。
     */
    fun initialize() {
        diagLog.log(TAG, "AppContainer init")
        appScope.launch {
            runCatching { scanRepository.sweepOpenSessions() }
                .onFailure { diagLog.log(TAG, "sweep failed: ${it::class.java.simpleName}") }
            runCatching {
                val settings = settingsStore.current()
                if (settings.autoSync) {
                    syncScheduler.enqueueNow(wifiOnly = settings.syncWifiOnly)
                } else {
                    diagLog.log(TAG, "auto sync disabled; skip startup enqueue")
                }
            }.onFailure { diagLog.log(TAG, "enqueue sync failed: ${it::class.java.simpleName}") }
            diagLog.log(TAG, "startup recovery finished")
        }
    }

    private companion object {
        const val TAG = "AppContainer"
    }
}
