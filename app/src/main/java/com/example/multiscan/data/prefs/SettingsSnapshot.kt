package com.example.multiscan.data.prefs

/**
 * 设置项只读快照（DataStore 投影）。
 *
 * [lastSyncStatus] 取值见本类常量：`never | success | failed | auth_error`。
 * token 明文不出现在本类中，只暴露 [hasToken] 与打码后的 [maskedToken]。
 */
data class SettingsSnapshot(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val enrollSecret: String = "",
    val deviceId: String? = null,
    val hasToken: Boolean = false,
    val maskedToken: String? = null,
    val overlayVisible: Boolean = true,
    val lastSyncAt: String? = null,
    val lastSyncStatus: String = SYNC_NEVER,
) {
    companion object {
        const val DEFAULT_SERVER_URL = "http://10.0.2.2:8000"

        const val SYNC_NEVER = "never"
        const val SYNC_SUCCESS = "success"
        const val SYNC_FAILED = "failed"
        const val SYNC_AUTH_ERROR = "auth_error"
    }
}

/**
 * token 展示打码：前 4 位 + `****` + 后 4 位；空值为 null，过短（<=8）整体打码。
 * 该函数只用于 UI 展示与日志，绝不产生可用于认证的完整 token。
 */
fun maskToken(token: String?): String? {
    if (token.isNullOrEmpty()) return null
    return if (token.length > 8) token.take(4) + "****" + token.takeLast(4) else "****"
}
