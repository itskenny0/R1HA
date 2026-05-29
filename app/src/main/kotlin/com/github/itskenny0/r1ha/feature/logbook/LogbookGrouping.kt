package com.github.itskenny0.r1ha.feature.logbook

import com.github.itskenny0.r1ha.core.ha.LogbookEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Pure parsing / filtering / grouping helpers for the Logbook surface.
 *
 * Kept free of Compose and Android types so they can be unit-tested in
 * isolation (see LogbookGroupingTest). The screen and view-model wire these
 * together; all stateful concerns (fetch, re-fetch, picker overlay) live there.
 *
 * Lovelace's logbook-card lets you scope the feed to a single entity and/or a
 * domain and bucket the rows under relative-day headers ("Today", "Yesterday",
 * then absolute dates). These helpers reproduce that grouping/filtering shape
 * on top of the existing [LogbookEntry] list the repository already returns, so
 * no extra round-trip to HA is needed for filtering: the window fetch stays the
 * single source and we slice it locally.
 */

/**
 * A glyph for a logbook row keyed off the HA domain prefix. Deliberately small
 * and ASCII / emoji-free leaning where possible so it renders crisply on the
 * R1's display; anything unrecognised gets a neutral bullet so a row never goes
 * glyph-less. The set mirrors the accent grouping the screen already uses
 * (warm = controllable, cool = sensing, green = action/scene).
 */
fun domainGlyph(domain: String?): String = when (domain) {
    "light" -> "☼"          // bright sun
    "switch", "input_boolean" -> "⏻" // power symbol
    "fan" -> "❈"            // sparkle/fan
    "media_player" -> "♫"   // music note
    "cover", "valve" -> "▤" // square with fill
    "lock" -> "⚿"           // squared key
    "climate", "humidifier", "water_heater" -> "♨" // hot springs
    "sensor", "binary_sensor", "number", "input_number" -> "○" // ring
    "person", "device_tracker" -> "◈" // diamond
    "scene" -> "☀"          // sun
    "script", "automation" -> "↻" // clockwise arrow
    "button", "input_button" -> "◉" // fisheye
    "alarm_control_panel" -> "⚠" // warning
    "weather" -> "☁"        // cloud
    "sun" -> "☉"            // sun (astronomical)
    null -> "•"             // bullet
    else -> "•"
}

/**
 * Distinct, display-ordered set of domains present in [entries]. Used to render
 * the domain filter chips so the user only ever sees domains that actually
 * appear in the current window (no dead chips). Sorted case-insensitively for a
 * stable chip order across refreshes.
 */
fun availableDomains(entries: List<LogbookEntry>): List<String> =
    entries.asSequence()
        .mapNotNull { it.domain?.takeIf { d -> d.isNotBlank() } }
        .distinct()
        .sortedBy { it.lowercase(Locale.US) }
        .toList()

/**
 * Apply the entity / domain / text filters to [entries] in one pass. Any
 * argument left null/blank is a no-op for that dimension, so the unfiltered
 * feed is just `applyFilters(entries, null, null, "")`.
 *
 * - [entityId] matches exactly against [LogbookEntry.entityId] (the picker
 *   hands back a concrete entity_id string).
 * - [domain] matches exactly against [LogbookEntry.domain].
 * - [query] is a case-insensitive substring match across the name, message and
 *   entity_id, matching the existing search-bar behaviour.
 */
fun applyFilters(
    entries: List<LogbookEntry>,
    entityId: String?,
    domain: String?,
    query: String,
): List<LogbookEntry> {
    val q = query.trim().lowercase(Locale.US)
    val wantEntity = entityId?.takeIf { it.isNotBlank() }
    val wantDomain = domain?.takeIf { it.isNotBlank() }
    if (wantEntity == null && wantDomain == null && q.isBlank()) return entries
    return entries.filter { e ->
        (wantEntity == null || e.entityId?.value == wantEntity) &&
            (wantDomain == null || e.domain == wantDomain) &&
            (q.isBlank() ||
                e.name.lowercase(Locale.US).contains(q) ||
                e.message.lowercase(Locale.US).contains(q) ||
                (e.entityId?.value?.lowercase(Locale.US)?.contains(q) ?: false))
    }
}

/** A run of logbook rows sharing a relative-day header. */
data class LogbookDayGroup(
    val header: String,
    val entries: List<LogbookEntry>,
)

/**
 * Bucket [entries] (assumed newest-first) under relative-day headers in the
 * device [zone]: "TODAY", "YESTERDAY", then an absolute date label (e.g.
 * "MON, MAY 26") for older days. Stable header order follows the input order so
 * the newest day group comes first.
 *
 * [now] is injectable for deterministic tests; defaults to wall-clock.
 */
fun groupByDay(
    entries: List<LogbookEntry>,
    zone: ZoneId,
    now: Instant = Instant.now(),
): List<LogbookDayGroup> {
    if (entries.isEmpty()) return emptyList()
    val today = now.atZone(zone).toLocalDate()
    val yesterday = today.minusDays(1)
    // Preserve input order: LinkedHashMap keeps first-seen day order.
    val byDay = LinkedHashMap<LocalDate, MutableList<LogbookEntry>>()
    for (e in entries) {
        val day = e.timestamp.atZone(zone).toLocalDate()
        byDay.getOrPut(day) { mutableListOf() }.add(e)
    }
    return byDay.map { (day, rows) ->
        LogbookDayGroup(header = dayHeader(day, today, yesterday), entries = rows)
    }
}

/** Header label for a calendar [day] relative to [today] / [yesterday]. */
internal fun dayHeader(day: LocalDate, today: LocalDate, yesterday: LocalDate): String =
    when (day) {
        today -> "TODAY"
        yesterday -> "YESTERDAY"
        else -> {
            // e.g. "MON, MAY 26" — short weekday + month + day-of-month.
            val dow = day.dayOfWeek.getDisplayName(
                java.time.format.TextStyle.SHORT,
                Locale.US,
            ).uppercase(Locale.US)
            val mon = day.month.getDisplayName(
                java.time.format.TextStyle.SHORT,
                Locale.US,
            ).uppercase(Locale.US)
            "$dow, $mon ${day.dayOfMonth}"
        }
    }
