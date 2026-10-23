package com.example.multiscan.core.time

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 全 App 统一时间工具。
 *
 * 规则（对应需求「所有时间存 UTC ISO8601」）：
 *  - 持久化/传输：一律 [nowIso] 产生的 UTC ISO-8601 字符串（`Instant.toString()`，带 `Z`）。
 *  - UI 展示：用 [formatDisplay] 转本地时区。
 */
object Utc {

    private val displayFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private val shortFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

    /** 当前时刻的 UTC ISO-8601 字符串，例如 `2026-10-23T05:12:03.123456Z`。 */
    fun nowIso(): String = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()

    /** 指定时刻的 UTC ISO-8601 字符串。 */
    fun toIso(instant: Instant): String = instant.truncatedTo(ChronoUnit.MILLIS).toString()

    /** 本地时区「今天 00:00」对应的 UTC ISO 字符串，用于 Room 的日期范围查询。 */
    fun startOfTodayIso(zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.now().atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toString()

    /** 本地时区「明天 00:00」对应的 UTC ISO 字符串（查询区间右开边界）。 */
    fun startOfTomorrowIso(zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.now().atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toString()

    fun parseOrNull(iso: String?): Instant? = try {
        if (iso.isNullOrBlank()) null else Instant.parse(iso)
    } catch (_: Exception) {
        null
    }

    /** 解析失败返回 null。 */
    fun isToday(iso: String?, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val instant = parseOrNull(iso) ?: return false
        val date = instant.atZone(zone).toLocalDate()
        return date == Instant.now().atZone(zone).toLocalDate()
    }

    /** `yyyy-MM-dd HH:mm:ss`（本地时区）；空/非法返回 `—`。 */
    fun formatDisplay(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String {
        val instant = parseOrNull(iso) ?: return "—"
        return displayFormatter.format(instant.atZone(zone))
    }

    /** `MM-dd HH:mm:ss`（本地时区）；空/非法返回 `—`。 */
    fun formatDisplayShort(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String {
        val instant = parseOrNull(iso) ?: return "—"
        return shortFormatter.format(instant.atZone(zone))
    }
}
