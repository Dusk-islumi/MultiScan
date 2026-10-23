package com.example.multiscan.data.sync

import com.example.multiscan.core.time.Utc
import com.example.multiscan.data.local.OutboxDao
import com.example.multiscan.data.local.OutboxEntity
import com.example.multiscan.data.local.ScanItemDao
import com.example.multiscan.data.local.ScanItemEntity
import com.example.multiscan.data.local.SessionDao
import com.example.multiscan.data.local.SessionDbState
import com.example.multiscan.data.local.TransactionRunner
import com.example.multiscan.data.prefs.SettingsAccess
import com.example.multiscan.data.prefs.SettingsSnapshot
import com.example.multiscan.data.remote.MultiScanApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * 同步引擎（纯逻辑，JVM 可测）：把所有 due outbox 行按服务端契约处理完。
 *
 * 行为矩阵（契约 §6.5）：
 *  - 2xx：outbox=synced、session=synced、last_sync_status=success；
 *  - 401/403：outbox=auth_error、session=failed、last_sync_status=auth_error，**不安排重试**；
 *  - 413：递归对半拆分重发，batch_id 用 [BatchPayloadBuilder] 确定性派生；全部成功才算 synced；
 *  - 422：outbox=failed、session=failed、last_error（脱敏）；
 *  - 5xx / IOException / 超时：attempts+1、next_retry_at=now+[SyncBackoff]，保持 pending；
 *  - 无 due 任务：不访问网络，只按最早的 next_retry_at 给出下一次唤醒延迟。
 *
 * 并发保护：上传前把 outbox 标为 uploading（防重入），进程崩溃残留由本轮开头 reset。
 */
class SyncEngine(
    private val sessionDao: SessionDao,
    private val scanItemDao: ScanItemDao,
    private val outboxDao: OutboxDao,
    private val tx: TransactionRunner,
    private val settings: SettingsAccess,
    private val apiProvider: suspend () -> MultiScanApi,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val nowIso: () -> String = { Instant.ofEpochMilli(clock()).toString() },
) {

    suspend fun runOnce(): SyncRunSummary {
        // 崩溃恢复：残留 uploading 一律视为 pending 重新排队。
        outboxDao.resetStaleUploading()

        val due = outboxDao.duePending(nowIso())
        if (due.isEmpty()) {
            return SyncRunSummary(nextWakeDelayMillis = nextWakeDelay())
        }

        val api = try {
            apiProvider()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // 依赖/配置初始化失败：不改 attempts，交由用户在设置页修复后手动重试。
            settings.setLastSync(nowIso(), SettingsSnapshot.SYNC_FAILED)
            return SyncRunSummary()
        }

        var synced = 0
        var failed = 0
        var authErrors = 0
        var transientErrors = 0

        for (row in due) {
            when (syncRow(api, row)) {
                RowOutcome.Synced -> synced++
                RowOutcome.PermanentFailure -> failed++
                RowOutcome.AuthError -> {
                    authErrors++
                    break // token 失效：本轮停止，且不安排任何重试
                }
                RowOutcome.TransientFailure -> transientErrors++
            }
        }

        return SyncRunSummary(
            sessionsSynced = synced,
            sessionsFailed = failed,
            authErrors = authErrors,
            transientErrors = transientErrors,
            nextWakeDelayMillis = if (authErrors > 0) null else nextWakeDelay(),
        )
    }

    private suspend fun syncRow(api: MultiScanApi, row: OutboxEntity): RowOutcome {
        val session = sessionDao.findById(row.sessionId)
        if (session == null) {
            markPermanentFailure(row, "会话不存在")
            return RowOutcome.PermanentFailure
        }

        val items = scanItemDao.itemsForSession(session.id)
        if (items.isEmpty()) {
            // 空批次不上云，直接收尾（正常流程不会出现）。
            tx.run {
                outboxDao.markSynced(row.id)
                sessionDao.setState(session.id, SessionDbState.SYNCED)
            }
            settings.setLastSync(nowIso(), SettingsSnapshot.SYNC_SUCCESS)
            return RowOutcome.Synced
        }

        // 认领：并发 worker 情况下只有成功置 uploading 的那个继续。
        if (outboxDao.markUploading(row.id) == 0) return RowOutcome.TransientFailure

        val capturedAt = session.finishedAt ?: session.startedAt
        return when (val outcome = uploadAll(api, session.id, capturedAt, items)) {
            UploadOutcome.Success -> {
                tx.run {
                    outboxDao.markSynced(row.id)
                    sessionDao.setState(session.id, SessionDbState.SYNCED)
                }
                settings.setLastSync(nowIso(), SettingsSnapshot.SYNC_SUCCESS)
                RowOutcome.Synced
            }
            UploadOutcome.AuthError -> {
                tx.run {
                    outboxDao.markAuthError(row.id, AUTH_ERROR_MESSAGE)
                    sessionDao.setState(session.id, SessionDbState.FAILED)
                }
                settings.setLastSync(nowIso(), SettingsSnapshot.SYNC_AUTH_ERROR)
                RowOutcome.AuthError
            }
            is UploadOutcome.PermanentFailure -> {
                markPermanentFailure(row, outcome.message)
                RowOutcome.PermanentFailure
            }
            is UploadOutcome.TransientFailure -> {
                markTransientFailure(row, outcome.message)
                RowOutcome.TransientFailure
            }
        }
    }

    private suspend fun markPermanentFailure(row: OutboxEntity, message: String) {
        val error = sanitizeError(message)
        tx.run {
            outboxDao.markFailed(row.id, error)
            sessionDao.setState(row.sessionId, SessionDbState.FAILED)
        }
        settings.setLastSync(nowIso(), SettingsSnapshot.SYNC_FAILED)
    }

    private suspend fun markTransientFailure(row: OutboxEntity, message: String) {
        val attempts = row.attempts + 1
        val nextRetryAt = Instant.ofEpochMilli(clock() + SyncBackoff.delayMillis(attempts)).toString()
        outboxDao.markPendingRetry(row.id, attempts, nextRetryAt, sanitizeError(message))
        settings.setLastSync(nowIso(), SettingsSnapshot.SYNC_FAILED)
    }

    /** 按 500 条上限顺序上传；任何一段失败立即短路返回。 */
    private suspend fun uploadAll(
        api: MultiScanApi,
        sessionId: String,
        capturedAt: String,
        items: List<ScanItemEntity>,
    ): UploadOutcome {
        var from = 0
        while (from < items.size) {
            val to = (from + SyncConfig.MAX_ITEMS_PER_BATCH).coerceAtMost(items.size)
            val outcome = uploadRange(api, sessionId, capturedAt, items, from, to)
            if (outcome !is UploadOutcome.Success) return outcome
            from = to
        }
        return UploadOutcome.Success
    }

    /** 上传 [from, to) 区间；413 时对半递归拆分（区间仍在整段 items 上计算，保证 id 稳定）。 */
    private suspend fun uploadRange(
        api: MultiScanApi,
        sessionId: String,
        capturedAt: String,
        items: List<ScanItemEntity>,
        from: Int,
        to: Int,
    ): UploadOutcome {
        val request = BatchPayloadBuilder.buildRequest(sessionId, capturedAt, items, from, to)
        return try {
            api.uploadBatch(request)
            UploadOutcome.Success
        } catch (c: CancellationException) {
            throw c
        } catch (e: HttpException) {
            when (e.code()) {
                413 -> if (to - from <= 1) {
                    UploadOutcome.PermanentFailure("批次过大且无法继续拆分（413）")
                } else {
                    val mid = from + (to - from) / 2
                    val left = uploadRange(api, sessionId, capturedAt, items, from, mid)
                    if (left is UploadOutcome.Success) {
                        uploadRange(api, sessionId, capturedAt, items, mid, to)
                    } else {
                        left
                    }
                }
                401, 403 -> UploadOutcome.AuthError
                422 -> UploadOutcome.PermanentFailure("服务端校验失败（422）")
                in 500..599 -> UploadOutcome.TransientFailure("服务端错误（${e.code()}）")
                else -> UploadOutcome.PermanentFailure("HTTP ${e.code()}")
            }
        } catch (e: IOException) {
            if (!currentCoroutineContext().isActive) throw CancellationException("cancelled", e)
            UploadOutcome.TransientFailure(e.message ?: "网络异常")
        }
    }

    /** 最早一个「未到期」pending 批次的唤醒延迟；没有则 null。 */
    private suspend fun nextWakeDelay(): Long? {
        val next = outboxDao.nextRetryAt() ?: return null
        val instant = Utc.parseOrNull(next) ?: return null
        return (instant.toEpochMilli() - clock()).coerceAtLeast(0L)
    }

    /** last_error 脱敏：绝不含 Bearer/token/secret 明文，最长 200 字符。 */
    private fun sanitizeError(message: String): String {
        var text = message
        text = BEARER_PATTERN.replace(text) { "Bearer ***" }
        text = SECRET_PATTERN.replace(text) { match -> "${match.groupValues[1]}=***" }
        return text.take(MAX_ERROR_LENGTH)
    }

    private sealed interface RowOutcome {
        object Synced : RowOutcome
        object PermanentFailure : RowOutcome
        object AuthError : RowOutcome
        object TransientFailure : RowOutcome
    }

    private sealed interface UploadOutcome {
        object Success : UploadOutcome
        data class PermanentFailure(val message: String) : UploadOutcome
        object AuthError : UploadOutcome
        data class TransientFailure(val message: String) : UploadOutcome
    }

    private companion object {
        const val AUTH_ERROR_MESSAGE = "登录失效（401），请重新注册本机"
        const val MAX_ERROR_LENGTH = 200

        val BEARER_PATTERN = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+")
        val SECRET_PATTERN = Regex("(?i)\\b(token|enroll_secret|secret|authorization)\\b\\s*[=:]\\s*\\S+")
    }
}
