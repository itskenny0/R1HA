package com.github.itskenny0.r1ha.feature.backups

import com.github.itskenny0.r1ha.core.ha.BackupInfo
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

    /**
     * Turns HA's ISO-8601 creation timestamp into a compact "YYYY-MM-DD HH:MM"
     * label. HA hands back strings like "2024-06-12T08:31:45.123456+00:00"; we
     * keep the date and minute-resolution time and drop sub-minute precision,
     * the timezone offset, and any fractional seconds. Unparseable or null input
     * falls back to the raw string (or "Unknown date" when there is nothing at
     * all), so we never hide a value just because the shape surprised us.
     */
    fun formatCreatedAt(raw: String?): String {
        if (raw.isNullOrBlank()) return "Unknown date"
        val tIndex = raw.indexOf('T')
        if (tIndex <= 0) return raw
        val datePart = raw.substring(0, tIndex)
        // Time chunk runs until the first offset marker or fractional second.
        val afterT = raw.substring(tIndex + 1)
        val cut = afterT.indexOfFirst { it == '.' || it == '+' || it == 'Z' || it == '-' }
        val timeChunk = if (cut >= 0) afterT.substring(0, cut) else afterT
        val hhmm = timeChunk.split(':').take(2).joinToString(":")
        if (datePart.isBlank()) return raw
        return if (hhmm.isBlank()) datePart else "$datePart $hhmm"
    }

    /**
     * Returns a new list ordered per [sort]. Newest/oldest compare on the raw
     * ISO-8601 [BackupInfo.createdAt] string, which sorts lexicographically the
     * same as chronologically for HA's fixed-width UTC timestamps; backups with
     * no timestamp always sink to the bottom regardless of direction. Sorts are
     * stable so equal keys keep their incoming relative order.
     */
    fun sortBackups(backups: List<BackupInfo>, sort: Sort): List<BackupInfo> = when (sort) {
        Sort.NEWEST_FIRST -> backups.sortedWith(
            compareByDescending<BackupInfo> { it.createdAt != null }
                .thenByDescending { it.createdAt ?: "" },
        )
        Sort.OLDEST_FIRST -> backups.sortedWith(
            compareByDescending<BackupInfo> { it.createdAt != null }
                .thenBy { it.createdAt ?: "" },
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
