package com.example.multiscan.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * 服务端 v1 契约（见 docs/API.md，只读不可改）。
 *
 * baseUrl 必须带尾部 `/`（由 [NetworkFactory] 规范化）；所有非 2xx 由 Retrofit 抛
 * [retrofit2.HttpException]，调用方按状态码分支处理。
 */
interface MultiScanApi {

    @GET("health")
    suspend fun health(): HealthResponse

    @POST("v1/devices/register")
    suspend fun register(@Body body: RegisterRequest): RegisterResponse

    @POST("v1/scan-batches")
    suspend fun uploadBatch(@Body body: ScanBatchRequest): ScanBatchResponse
}

@Serializable
data class HealthResponse(
    val ok: Boolean,
    val version: String,
    @SerialName("server_time") val serverTime: String,
)

@Serializable
data class RegisterRequest(
    val name: String,
    @SerialName("enroll_secret") val enrollSecret: String,
)

@Serializable
data class RegisterResponse(
    @SerialName("device_id") val deviceId: String,
    val token: String,
    @SerialName("server_time") val serverTime: String? = null,
)

@Serializable
data class ScanBatchRequest(
    @SerialName("batch_id") val batchId: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("captured_at") val capturedAt: String,
    val items: List<ScanItemPayload>,
)

@Serializable
data class ScanItemPayload(
    @SerialName("item_id") val itemId: String,
    @SerialName("raw_text") val rawText: String,
    val format: String = "Code128",
    @SerialName("symbology_id") val symbologyId: String? = null,
    @SerialName("line_count") val lineCount: Int? = null,
)

@Serializable
data class ScanBatchResponse(
    @SerialName("batch_id") val batchId: String,
    val accepted: Int,
    val duplicated: Int,
    @SerialName("barcodes_total") val barcodesTotal: Int,
)
