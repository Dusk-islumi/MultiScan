package com.example.multiscan.data.local

/**
 * 事务抽象：生产实现是 [RoomTransactionRunner]（`database.withTransaction`），
 * JVM 单测用直通 fake。
 *
 * 说明：契约原文写作 `fun interface`，但「泛型 + suspend」的抽象方法无法做 SAM 转换
 * （Kotlin lambda 不能是泛型函数），故此处为普通 interface；调用方式完全一致（`tx.run { ... }`）。
 * Repository / SyncEngine 只依赖本接口与 DAO 接口，不依赖 AppDatabase 具体类，保证 JVM 可测。
 */
interface TransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}
