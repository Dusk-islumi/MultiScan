package com.example.multiscan.scan.dedup

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话内去重索引单测（JVM，fake DB register）：
 *  - 首次新插入 → New/绿；
 *  - 同码再次出现 → Grey/灰 且命中缓存，不重复查库；
 *  - DB 已存在的码 → Grey；
 *  - 并发 classify 同一 code 也只查库一次（Mutex 串行化）。
 */
class SessionCodeIndexTest {

    @Test
    fun firstSeenNew_secondSeenGrey_databaseHitOnlyOnce() = runBlocking {
        val index = SessionCodeIndex()
        var dbCalls = 0

        val first = index.classify("CODE-A") { code ->
            assertEquals("CODE-A", code)
            dbCalls += 1
            true // INSERT OR IGNORE 成功 → 新码
        }
        assertTrue(first is CodeClassification.New)
        assertFalse(first.fromCache)
        assertTrue(first.isNew)
        assertTrue(index.contains("CODE-A"))
        assertEquals(1, index.size())

        val second = index.classify("CODE-A") { dbCalls += 1; false }
        assertTrue(second is CodeClassification.Grey)
        assertTrue(second.fromCache)
        assertFalse(second.isNew)
        assertEquals("第二次必须命中内存缓存，不再查库", 1, dbCalls)
    }

    @Test
    fun codeExistingInDb_isGrey_andCachedAfterwards() = runBlocking {
        val index = SessionCodeIndex()
        var dbCalls = 0

        val first = index.classify("CODE-B") { dbCalls += 1; false } // DB 已存在
        assertTrue(first is CodeClassification.Grey)
        assertFalse("首次查询仍需访问 DB", first.fromCache)
        assertEquals(1, dbCalls)

        val second = index.classify("CODE-B") { dbCalls += 1; true }
        assertTrue(second is CodeClassification.Grey)
        assertTrue(second.fromCache)
        assertEquals(1, dbCalls)
    }

    @Test
    fun containsAndRecord_workAsMemoryCache() {
        val index = SessionCodeIndex()
        assertFalse(index.contains("CODE-C"))
        assertEquals(null, index.cachedIsNew("CODE-C"))

        index.record("CODE-C", isNew = true)
        assertTrue(index.contains("CODE-C"))
        assertEquals(true, index.cachedIsNew("CODE-C"))

        // 已存在则保留首次结果。
        index.record("CODE-C", isNew = false)
        assertEquals(true, index.cachedIsNew("CODE-C"))
        assertEquals(1, index.size())
        assertEquals(mapOf("CODE-C" to true), index.snapshot())
    }

    @Test
    fun concurrentClassifySameCode_queriesDbOnlyOnce() = runBlocking {
        val index = SessionCodeIndex()
        val dbCalls = AtomicInteger(0)

        val results = coroutineScope {
            (1..8)
                .map {
                    async(Dispatchers.Default) {
                        index.classify("CODE-D") {
                            dbCalls.incrementAndGet()
                            true
                        }
                    }
                }
                .awaitAll()
        }

        assertEquals("并发下每个 code 只允许一次 DB 调用", 1, dbCalls.get())
        assertEquals(1, results.count { it is CodeClassification.New })
        assertEquals(7, results.count { it is CodeClassification.Grey && it.fromCache })
    }

    @Test
    fun differentCodes_areCountedSeparately() = runBlocking {
        val index = SessionCodeIndex()
        var dbCalls = 0
        val register: suspend (String) -> Boolean = { dbCalls += 1; true }

        assertTrue(index.classify("CODE-1", register) is CodeClassification.New)
        assertTrue(index.classify("CODE-2", register) is CodeClassification.New)
        assertEquals(2, dbCalls)
        assertEquals(2, index.size())
    }
}
