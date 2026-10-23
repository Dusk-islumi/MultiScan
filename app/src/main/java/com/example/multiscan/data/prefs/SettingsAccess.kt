package com.example.multiscan.data.prefs

import kotlinx.coroutines.flow.StateFlow

/**
 * 设置的可测试抽象（生产实现：[SettingsStore]，JVM 单测用 fake）。
 *
 * Repository / SyncEngine / NetworkFactory 只依赖本接口，避免在纯 JVM 单测里触碰
 * Android DataStore / Keystore。
 */
interface SettingsAccess {

    val snapshot: StateFlow<SettingsSnapshot>

    suspend fun current(): SettingsSnapshot

    /** 解密后的 token；未注册或解密失败返回 null。 */
    suspend fun token(): String?

    suspend fun setServerUrl(url: String)

    suspend fun setEnrollSecret(secret: String)

    suspend fun saveRegistration(deviceId: String, token: String)

    suspend fun setOverlayVisible(visible: Boolean)

    suspend fun setLastSync(atUtcIso: String?, status: String)

    suspend fun clearRegistration()
}
