package com.example.multiscan.scan.dedup

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 一次扫描会话内的去重分类结果（sealed，便于 `when` 穷举）。
 *
 * 语义（与 `ScanController` 的绿/灰上屏一致）：
 *  - [New]  = 本会话第一次见到该码，且本次 `registerCode` 插入成功 → UI 绿框；
 *  - [Grey] = 该码此前已存在（DB 里已有，或本会话已经登记过）→ UI 灰框。
 *
 * @property fromCache true=命中本会话内存缓存（未查库）。注意「之前是新插入的码再次出现」
 *                     同样返回 [Grey]（此时它已经入库，不再算新码），只是 `fromCache=true`。
 */
sealed interface CodeClassification {
    val code: String
    val isNew: Boolean
    val fromCache: Boolean

    /** 本会话首次见到且本次新入库 → UI 绿框。 */
    data class New(
        override val code: String,
        override val fromCache: Boolean,
    ) : CodeClassification {
        override val isNew: Boolean get() = true
    }

    /** 已存在（DB 已存在 / 插入被 UNIQUE 忽略 / 本会话已登记过）→ UI 灰框。 */
    data class Grey(
        override val code: String,
        override val fromCache: Boolean,
    ) : CodeClassification {
        override val isNew: Boolean get() = false
    }
}

/**
 * 扫描会话内存去重索引（纯 JVM，可单测；内部 `synchronized` + [Mutex] 保证线程安全）。
 *
 * 职责：
 *  - 缓存本会话已经确认过的所有 code 及其「新/灰」分类；
 *  - 每个 code 最多触发 **一次** DB 操作（`ScanSessionStore.registerCode`，INSERT OR IGNORE 语义），
 *    之后的重复出现（包括 track 过期后重新确认）直接走内存，避免重复写库/重复计数；
 *  - [register] 返回值语义：**true = 本次新插入（新码）**，false = 数据库已存在（旧码）。
 *
 * 线程安全：
 *  - [contains] / [cachedIsNew] / [record] 为同步非挂起方法；
 *  - [classify] 用 [Mutex] 串行化「查缓存 → 调 DB → 写缓存」整个临界区，
 *    因此并发调用同一个 code 也只会查库一次（coroutine 并发安全）。
 *
 * 控制器侧实际调用都串行经过单一 actor（Dispatchers.IO），Mutex 只是对额外调用方的兜底。
 */
class SessionCodeIndex {

    private val lock = Any()
    private val seen = HashMap<String, Boolean>()
    private val classifyMutex = Mutex()

    /** 本会话是否已确认过该 code（无论新/灰）。 */
    fun contains(code: String): Boolean = synchronized(lock) { seen.containsKey(code) }

    /** 已缓存分类：true=新码，false=旧码，null=尚未分类（需要查库）。 */
    fun cachedIsNew(code: String): Boolean? = synchronized(lock) { seen[code] }

    /** 已缓存的不同码数量。 */
    fun size(): Int = synchronized(lock) { seen.size }

    /** 直接记录分类结果（已存在则保留首次结果）。 */
    fun record(code: String, isNew: Boolean) {
        synchronized(lock) { seen.putIfAbsent(code, isNew) }
    }

    /** 缓存快照（测试/诊断用）。 */
    fun snapshot(): Map<String, Boolean> = synchronized(lock) { seen.toMap() }

    /**
     * 分类一个已确认的 code：内存命中直接返回灰（本会话已登记，不再算新码）；
     * 未命中才调用一次 [register]（suspend，含 DB IO），并把结果缓存起来。
     */
    suspend fun classify(
        code: String,
        register: suspend (String) -> Boolean,
    ): CodeClassification = classifyMutex.withLock {
        val cached = synchronized(lock) { seen[code] }
        if (cached != null) {
            // 本会话之前已登记过（无论当时是否新插入）：再次出现一律灰框，且不再查库。
            CodeClassification.Grey(code, fromCache = true)
        } else {
            val inserted = register(code)
            synchronized(lock) { seen[code] = inserted }
            if (inserted) CodeClassification.New(code, fromCache = false) else CodeClassification.Grey(code, fromCache = false)
        }
    }
}
