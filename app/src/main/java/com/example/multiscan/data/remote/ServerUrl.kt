package com.example.multiscan.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 服务器地址规范化与校验。
 *
 * 规则（对应契约「校验 http(s):// 基本格式」）：
 *  - 去首尾空白；
 *  - 必须以 `http://` 或 `https://` 开头；
 *  - 能被 OkHttp 解析为合法 HttpUrl；
 *  - 返回值补尾部 `/`（Retrofit `baseUrl` 强制要求），非法输入返回 null。
 */
object ServerUrl {

    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        val url = trimmed.toHttpUrlOrNull() ?: return null
        val normalized = url.toString()
        return if (normalized.endsWith("/")) normalized else "$normalized/"
    }
}
