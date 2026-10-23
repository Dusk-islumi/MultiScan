package com.example.multiscan.data.local

import androidx.room.withTransaction

/** Room 事务实现：块内多个 DAO 调用原子提交。 */
class RoomTransactionRunner(private val database: AppDatabase) : TransactionRunner {

    override suspend fun <T> run(block: suspend () -> T): T = database.withTransaction { block() }
}
