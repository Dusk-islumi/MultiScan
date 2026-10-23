package com.example.multiscan.data.remote

import com.example.multiscan.data.prefs.SettingsAccess
import com.example.multiscan.data.prefs.SettingsSnapshot
import com.example.multiscan.data.prefs.maskToken
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import retrofit2.HttpException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** 「测试连通性」结果；[Ok] 对应服务端 `ok: true`。 */
sealed interface HealthCheckResult {
    data class Ok(val version: String, val serverTime: String) : HealthCheckResult
    data class Error(val message: String) : HealthCheckResult
}

/** 「注册本机」结果；403 = 注册口令错误。 */
sealed interface RegisterResult {
    data class Ok(val deviceId: String, val maskedToken: String) : RegisterResult
    data class Error(val message: String, val httpCode: Int? = null) : RegisterResult
}

/**
 * 服务器配置 + 注册 + 健康检查。所有网络调用都是 suspend（Retrofit/OkHttp IO），
 * 错误统一转成 sealed result，UI 不需要处理异常。
 */
class ServerRepository(
    private val settings: SettingsAccess,
    private val networkFactory: NetworkFactory,
) {

    /** 只读设置快照（UI 的 config 数据源）。 */
    val config: StateFlow<SettingsSnapshot> get() = settings.snapshot

    /**
     * 校验并保存服务器地址（UI 已自行校验时可不用；返回 false 表示格式非法、未保存）。
     * 地址变化时由 [SettingsAccess.setServerUrl] 清空旧注册信息。
     */
    suspend fun saveServerUrl(url: String): Boolean {
        if (ServerUrl.normalize(url) == null) return false
        settings.setServerUrl(url)
        return true
    }

    suspend fun saveEnrollSecret(secret: String) {
        settings.setEnrollSecret(secret)
    }

    /** GET {serverUrl}/health，5 秒超时；能拿到 `ok: true` 即视为连通。 */
    suspend fun testHealth(): HealthCheckResult = try {
        val api = networkFactory.createApi()
        val response = withTimeout(HEALTH_TIMEOUT_MILLIS) { api.health() }
        if (response.ok) {
            HealthCheckResult.Ok(response.version, response.serverTime)
        } else {
            HealthCheckResult.Error("服务端返回 ok=false")
        }
    } catch (e: TimeoutCancellationException) {
        HealthCheckResult.Error("连接超时（5s）")
    } catch (e: HttpException) {
        HealthCheckResult.Error("HTTP ${e.code()}")
    } catch (e: IOException) {
        HealthCheckResult.Error("网络错误：${e.describe()}")
    } catch (e: IllegalArgumentException) {
        HealthCheckResult.Error("服务器地址无效")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        HealthCheckResult.Error("连接失败：${e::class.java.simpleName}")
    }

    /**
     * POST /v1/devices/register：使用已保存的注册口令注册本机。
     * 成功时保存 deviceId + 加密 token，并返回打码 token 供 UI 展示。
     */
    suspend fun registerDevice(deviceName: String): RegisterResult {
        val current = settings.current()
        val secret = current.enrollSecret.trim()
        if (secret.isEmpty()) return RegisterResult.Error("请先填写注册口令")

        return try {
            val api = networkFactory.createApi()
            val name = deviceName.trim().ifEmpty { "Android" }
            val response = withTimeout(REGISTER_TIMEOUT_MILLIS) {
                api.register(RegisterRequest(name = name, enrollSecret = secret))
            }
            settings.saveRegistration(response.deviceId, response.token)
            RegisterResult.Ok(response.deviceId, maskToken(response.token) ?: "****")
        } catch (e: HttpException) {
            when (e.code()) {
                403 -> RegisterResult.Error("注册口令错误", 403)
                401 -> RegisterResult.Error("未授权（401）", 401)
                else -> RegisterResult.Error("HTTP ${e.code()}", e.code())
            }
        } catch (e: TimeoutCancellationException) {
            RegisterResult.Error("注册超时")
        } catch (e: IOException) {
            RegisterResult.Error("网络错误：${e.describe()}")
        } catch (e: IllegalArgumentException) {
            RegisterResult.Error("服务器地址无效")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RegisterResult.Error("注册失败：${e::class.java.simpleName}")
        }
    }

    private fun IOException.describe(): String = message?.take(120) ?: "未知"

    private companion object {
        const val HEALTH_TIMEOUT_MILLIS = 5_000L
        const val REGISTER_TIMEOUT_MILLIS = 20_000L
    }
}
