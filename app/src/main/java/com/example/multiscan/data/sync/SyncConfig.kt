package com.example.multiscan.data.sync

/** 同步参数常量（APP_VERSION 与 build.gradle.kts 的 versionName 保持一致）。 */
object SyncConfig {
    /** WorkManager 唯一任务名：全进程串行同步。 */
    const val UNIQUE_WORK_NAME = "sync-scans"

    /** 服务端单批上限（docs/API.md：1–500 条）。 */
    const val MAX_ITEMS_PER_BATCH = 500

    const val APP_VERSION = "0.1.0"

    /** WorkManager tag，便于诊断/排查。 */
    const val WORK_TAG = "multiscan-sync"
}
