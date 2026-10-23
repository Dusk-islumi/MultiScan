package com.example.multiscan.core.diag

import com.example.multiscan.core.time.Utc

/**
 * 进程内诊断日志环形缓冲（最近 [CAPACITY] 条），供调试面板「导出诊断」使用。
 *
 * 线程安全：所有写入在 [lock] 内完成，扫描分析线程 / 网络线程 / 主线程都可以调用。
 *
 * 安全红线（对应需求「token 不要明文写进日志」）：
 *  1. 调用方必须自行打码敏感信息；
 *  2. 本类做兜底的二次掩码：`token=...`、`secret=...`、`Authorization: Bearer ...`
 *     这类模式统一替换为 `***`，避免误记。
 */
class DiagLog {

    private val lock = Any()
    private val buffer = ArrayDeque<String>(CAPACITY)

    fun log(tag: String, message: String) {
        val line = "${Utc.nowIso()} [$tag] ${mask(message)}"
        synchronized(lock) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(line)
        }
    }

    /** 最近 CAPACITY 条日志，旧 → 新。 */
    fun snapshot(): List<String> = synchronized(lock) { buffer.toList() }

    fun clear() {
        synchronized(lock) { buffer.clear() }
    }

    companion object {
        const val CAPACITY = 200

        private val bearerPattern = Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+")
        private val tokenPattern = Regex("(?i)\\b(token|enroll_secret|secret|authorization)\\b\\s*[=:]\\s*[^,\\s}\"]+")
        private val jsonTokenPattern = Regex("(?i)\"((?:token|enroll_secret|secret))\"\\s*:\\s*\"[^\"]*\"")

        /** 二次掩码：确保任何 token/secret 形态都不会以明文进入日志。 */
        fun mask(raw: String): String {
            var result = raw
            result = bearerPattern.replace(result) { match -> "${match.groupValues[1]}***" }
            result = jsonTokenPattern.replace(result) { match -> "\"${match.groupValues[1]}\":\"***\"" }
            result = tokenPattern.replace(result) { match -> "${match.groupValues[1]}=***" }
            return result
        }
    }
}
