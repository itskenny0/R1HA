package com.github.itskenny0.r1ha.feature.energy

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Serialise an [EnergyViewModel.UiState] snapshot to a plain-text CSV string
 * suitable for sharing via ACTION_SEND or writing to a cache file.
 *
 * The output has two sections separated by a blank row:
 *
 *   SUMMARY section - live tile metrics in a two-column (field, value) table:
 *     field,value
 *     draw_w,<number or "">
 *     production_w,<number or "">
 *     today_kwh,<number or "">
 *
 *   TOP CONSUMERS section - current draw per device:
 *     entity_id,name,watts
 *     sensor.foo,"My Device",123.4
 *     ...
 *
 *   HISTORY section - per-bucket consumption:
 *     timestamp_utc,kwh
 *     2024-01-01T00:00:00Z,1.23
 *     ...
 *
 * All timestamps are ISO-8601 UTC ("Z" suffix). All numbers use dot-decimal
 * (Locale.US) so the file is parseable regardless of the device locale.
 * String values containing commas or double-quotes are CSV-escaped per RFC 4180.
 */
internal fun energyCsv(
    currentDrawW: Double?,
    productionW: Double?,
    todayKwh: Double?,
    topConsumers: List<EnergyViewModel.Consumer>,
    historyBars: List<EnergyViewModel.HistoryBar>,
    generatedAt: Instant = Instant.now(),
): String {
    val sb = StringBuilder()
    val gen = TIMESTAMP_FMT.format(generatedAt)

    // ---- header comment --------------------------------------------------------
    sb.appendLine("# R1HA energy export - generated $gen")

    // ---- SUMMARY ---------------------------------------------------------------
    sb.appendLine("section,summary")
    sb.appendLine("field,value")
    sb.appendLine("draw_w,${formatOptional(currentDrawW)}")
    sb.appendLine("production_w,${formatOptional(productionW)}")
    sb.appendLine("today_kwh,${formatOptional(todayKwh)}")
    sb.appendLine()

    // ---- TOP CONSUMERS ---------------------------------------------------------
    sb.appendLine("section,top_consumers")
    sb.appendLine("entity_id,name,watts")
    for (c in topConsumers) {
        sb.appendLine("${csvEscape(c.entityId)},${csvEscape(c.name)},${formatDouble(c.watts)}")
    }
    sb.appendLine()

    // ---- HISTORY ---------------------------------------------------------------
    sb.appendLine("section,history")
    sb.appendLine("timestamp_utc,kwh")
    for (bar in historyBars) {
        sb.appendLine("${TIMESTAMP_FMT.format(bar.timestamp)},${formatDouble(bar.kwh)}")
    }

    return sb.toString()
}

/** Convenience overload that unpacks [EnergyViewModel.UiState] directly. */
internal fun energyCsv(ui: EnergyViewModel.UiState): String = energyCsv(
    currentDrawW = ui.currentDrawW,
    productionW = ui.productionW,
    todayKwh = ui.statsTodayKwh ?: ui.todayKwh,
    topConsumers = ui.topConsumers,
    historyBars = ui.historyBars,
)

// ---- helpers -----------------------------------------------------------------

private val TIMESTAMP_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

private fun formatDouble(v: Double): String = String.format(Locale.US, "%.4f", v)

private fun formatOptional(v: Double?): String =
    if (v == null) "" else formatDouble(v)

/**
 * Escape a string field per RFC 4180: wrap in double-quotes if the value
 * contains a comma, double-quote, or newline; double any embedded double-quotes.
 * Plain values pass through unchanged so the common case adds no noise.
 */
internal fun csvEscape(value: String): String {
    if (!value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) return value
    return "\"${value.replace("\"", "\"\"")}\""
}
