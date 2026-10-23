package com.example.multiscan

import android.app.Application
import com.example.multiscan.core.diag.DiagLog

/**
 * Application：创建并持有手写单例容器 [AppContainer]。
 *
 * 需求明确禁止 Hilt/Koin，所有依赖在这里手工组装（见 [AppContainer]）。
 */
class MultiScanApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.initialize()
    }

    /** 仅供 JVM 友好访问；实际使用请通过 `(application as MultiScanApplication).container`。 */
    val diagLog: DiagLog
        get() = container.diagLog
}
