package com.example.multiscan.ui.common

import android.content.Context
import com.example.multiscan.R
import com.example.multiscan.data.repository.ManualSyncResult

/**
 * 手动同步结果 → 中文提示（Home 与 Settings 共用，契约 §13.2）。
 *
 * - `Started(n)` → 「已开始同步 n 条」
 * - `UpToDate` → 「没有待同步记录」
 * - `NeedsRegistration` → 「请先在设置页注册本机」
 * - `Error(message)` → 直接展示 data 层的错误文案
 */
fun manualSyncResultMessage(context: Context, result: ManualSyncResult): String = when (result) {
    is ManualSyncResult.Started -> context.getString(R.string.sync_result_started, result.pendingCount)
    ManualSyncResult.UpToDate -> context.getString(R.string.sync_result_up_to_date)
    ManualSyncResult.NeedsRegistration -> context.getString(R.string.sync_result_need_register)
    is ManualSyncResult.Error -> result.message
}
