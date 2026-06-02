package com.github.itskenny0.r1ha.feature.backups

import com.github.itskenny0.r1ha.core.ha.BackupInfo
import com.github.itskenny0.r1ha.core.ha.parseHaInstant
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure, side-effect-free helpers backing the Backups surface. Kept out of the
 * composable so size/date formatting and sort order are unit-testable without a
 * Compose or Android runtime. All locale-sensitive formatting pins to
 * [Locale.US] so output is stable across device locales.
 */
object BackupsLogic {

    /** How the backup list is ordered in the UI. */
    enum class Sort { NEWEST_FIRST, OLDEST_FIRST, NAME, SIZE_DESC }

    /**
     * Human-readable byte size: B / KB / MB / GB with one decimal place above the
     * byte tier. Uses 1024-based tiers (matching HA's own backup sizing). Returns
     * "Unknown size" when HA omitted the size for a backup.
     */
    fun formatSize(bytes: Long?): String {
        if (bytes == null) return "Unknown size"
        if (bytes < 0) return "Unknown size"
        return when {
            bytes >= 1_073_741_824L -> String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
            bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    // "YYYY-MM-DD HH:MM" rendered in UTC, HA's storage zone for backup dates.
    // Desugar-safe: the withZone(...).format(Instant) path avoids
    // LocalDateTime.ofInstant (API 31), matching how the rest of the app formats HA
    // timestamps. Pinning to UTC keeps the label deterministic across device locales
    // and time zones and faithful to the instant HA recorded.
    private val createdFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
            .withZone(ZoneOffset.UTC)

    /**
     * Turns HA's ISO-8601 creation timestamp into a compact "YYYY-MM-DD HH:MM"
     * label. HA emits UTC strings like "2024-06-12T08:31:45.123456+00:00"; we parse
     * via [parseHaInstant] (the same desugar-safe parser the history/energy/weather
     * surfaces use, tolerant of the numeric-offset and bare-"Z" forms that plain
     * [Instant.parse] rejects under core-library desugaring) and render the UTC
     * instant at minute resolution. Unlike the previous naive string-slicing, a
     * non-UTC offset such as "...-05:00" is now normalised to UTC rather than having
     * its offset silently dropped. Unparseable input falls back to the raw string,
     * and null/blank to "Unknown date", so we never hide a value just because the
     * shape surprised us.
     */
    fun formatCreatedAt(raw: String?): String {
        if (raw.isNullOrBlank()) return "Unknown date"
        val instant = parseHaInstant(raw) ?: return raw
        return runCatching { createdFmt.format(instant) }.getOrDefault(raw)
    }

    /**
     * Compact relative age ("2h ago", "3d ago") for a backup's creation time,
     * computed against [now]. Mirrors the buckets used by the shared
     * RelativeTime component so the two read consistently. Returns null when the
     * timestamp is missing or unparseable, so callers can fall back to the
     * absolute label rather than rendering a misleading "just now".
     */
    fun relativeCreatedAt(raw: String?, now: Instant = Instant.now()): String? {
        val instant = parseHaInstant(raw) ?: return null
        val deltaSec = (now.toEpochMilli() - instant.toEpochMilli()) / 1000
        if (deltaSec < 0) return "just now"
        return when {
            deltaSec < 60 -> "just now"
            deltaSec < 3600 -> "${deltaSec / 60}m ago"
            deltaSec < 86_400 -> "${deltaSec / 3600}h ago"
            deltaSec < 7 * 86_400 -> "${deltaSec / 86_400}d ago"
            else -> "${deltaSec / (7 * 86_400)}w ago"
        }
    }

    /**
     * Returns a new list ordered per [sort]. Newest/oldest compare on the parsed
     * [Instant] rather than the raw string: HA's timestamps are not guaranteed
     * fixed-width (the offset can be "Z" or "+00:00" and fractional seconds vary in
     * width), so a lexicographic compare can mis-order; parsing first is correct
     * for any shape [parseHaInstant] accepts. Backups whose timestamp is missing or
     * unparseable always sink to the bottom regardless of direction. Sorts are
     * stable so equal keys keep their incoming relative order.
     */
    fun sortBackups(backups: List<BackupInfo>, sort: Sort): List<BackupInfo> = when (sort) {
        Sort.NEWEST_FIRST -> backups.sortedWith(
            compareByDescending<BackupInfo> { parseHaInstant(it.createdAt) != null }
                .thenByDescending { parseHaInstant(it.createdAt) ?: Instant.MIN },
        )
        Sort.OLDEST_FIRST -> backups.sortedWith(
            compareByDescending<BackupInfo> { parseHaInstant(it.createdAt) != null }
                .thenBy { parseHaInstant(it.createdAt) ?: Instant.MAX },
        )
        Sort.NAME -> backups.sortedBy { it.name.lowercase(Locale.US) }
        Sort.SIZE_DESC -> backups.sortedByDescending { it.sizeBytes ?: -1L }
    }

    /** Short type label for a row: capitalised type, or "Manual" when absent. */
    fun typeLabel(type: String?): String {
        val t = type?.trim().orEmpty()
        if (t.isEmpty()) return "Manual"
        return t.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
    }
}
