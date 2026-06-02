package com.github.itskenny0.r1ha.core.ha

import java.time.Instant
import java.time.OffsetDateTime

/**
 * Parse a Home Assistant ISO-8601 timestamp into an [Instant], tolerant of the forms HA
 * actually emits. Returns null (never throws) for blank or unparseable input.
 *
 * HA serialises `last_changed` / `last_updated` / history / logbook / calendar / statistics
 * times with a numeric UTC offset, e.g. `2026-06-01T22:12:17.511064+00:00`. Plain
 * [Instant.parse] is backed by `DateTimeFormatter.ISO_INSTANT`, which only accepts the bare
 * `Z` form. On the platform JVM that distinction is invisible (its `Instant.parse` also
 * accepts a numeric offset), but the desugared `java.time` this app ships under core-library
 * desugaring (enabled for the minSdk-23 floor) does not: `Instant.parse("…+00:00")` throws
 * `DateTimeParseException`. Every HA-timestamp parse then silently yielded null and the value
 * was dropped — most visibly, sensor-history charts read "NOT ENOUGH HISTORY" because every
 * point's timestamp failed to parse.
 *
 * [OffsetDateTime.parse] is backed by `ISO_OFFSET_DATE_TIME`, which accepts the numeric
 * offset, `Z`, and non-UTC offsets (normalised to UTC), and is verified to work under the
 * desugared runtime. We keep an [Instant.parse] fallback for any bare-`Z` shape that is not a
 * full offset-date-time.
 */
fun parseHaInstant(raw: String?): Instant? {
    if (raw.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(raw).toInstant() }
        .recoverCatching { Instant.parse(raw) }
        .getOrNull()
}
