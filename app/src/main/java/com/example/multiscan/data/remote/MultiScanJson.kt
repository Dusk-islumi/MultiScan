package com.example.multiscan.data.remote

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * 网络层统一 Json 配置：
 *  - `ignoreUnknownKeys = true`：服务端新增字段不导致解析失败；
 *  - `explicitNulls = false`：payload 的可选字段为 null 时不输出（契约要求可选）；
 *  - `encodeDefaults = true`：`format = "Code128"` 这类默认值也要出现在 JSON 里。
 */
@OptIn(ExperimentalSerializationApi::class)
internal object MultiScanJson {

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
}
