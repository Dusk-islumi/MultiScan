package com.example.multiscan.data.remote

import com.example.multiscan.data.prefs.SettingsAccess
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 按「当前 serverUrl」构建并轻量缓存 Retrofit（只缓存最近一个 URL 的实例）。
 *
 * 安全约定：
 *  - `Authorization: Bearer <token>` 由 [AuthInterceptor] 在请求时从 SettingsStore 解密读取，
 *    token 为空则不加头；
 *  - 绝不安装 BODY 级 HttpLoggingInterceptor（会泄漏 token / 明文条码）。
 */
class NetworkFactory(private val settings: SettingsAccess) {

    private val mutex = Mutex()

    @Volatile
    private var cachedBaseUrl: String? = null

    @Volatile
    private var cachedApi: MultiScanApi? = null

    /**
     * 取当前配置（或 [baseUrlOverride]）对应的 API。地址非法抛 [IllegalArgumentException]。
     * 网络调用本身是挂起的；构建 Retrofit 只做内存操作。
     */
    suspend fun createApi(baseUrlOverride: String? = null): MultiScanApi {
        val raw = baseUrlOverride ?: settings.current().serverUrl
        val baseUrl = ServerUrl.normalize(raw)
            ?: throw IllegalArgumentException("invalid server url: $raw")
        cachedApi?.let { api -> if (cachedBaseUrl == baseUrl) return api }
        return mutex.withLock {
            cachedApi?.let { api -> if (cachedBaseUrl == baseUrl) return@withLock api }
            build(baseUrl).also {
                cachedBaseUrl = baseUrl
                cachedApi = it
            }
        }
    }

    private fun build(baseUrl: String): MultiScanApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor { runBlocking { settings.token() } })
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(MultiScanJson.json.asConverterFactory(JSON_MEDIA_TYPE))
            .build()
            .create(MultiScanApi::class.java)
    }

    /**
     * Bearer token 注入。retrofit suspend 调用发生在 OkHttp 工作线程，
     * 这里用 runBlocking 读取 DataStore（内存缓存，毫秒级），不会阻塞主线程。
     */
    private class AuthInterceptor(private val tokenProvider: () -> String?) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val token = tokenProvider()
            val request = if (token.isNullOrBlank()) {
                chain.request()
            } else {
                chain.request().newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            }
            return chain.proceed(request)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 30L
        const val WRITE_TIMEOUT_SECONDS = 30L

        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
