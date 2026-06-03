package com.github.itskenny0.r1ha.feature.repairs

import androidx.compose.ui.graphics.vector.ImageVector
import com.github.itskenny0.r1ha.core.ha.RepairIssue
import com.github.itskenny0.r1ha.core.ha.parseHaInstant
import com.github.itskenny0.r1ha.ui.icons.R1IconSet
import java.time.Instant
import java.util.Locale

/**
 * Pure, side-effect-free helpers behind the Repairs surface: severity ranking,
 * the list sort order, the header breakdown counts, ignore-state partitioning,
 * and the HA fix-flow deep link. Kept out of the ViewModel so each piece is
 * unit-testable without a coroutine scope or an Android runtime.
 */
object RepairsLogic {

    /**
     * Sort key for severity, lower is shown first: critical, then error, then
     * warning, then anything HA didn't label (treated as least urgent). Matches
     * the colour ramp the rows render.
     */
    fun severityRank(severity: String): Int = when (severity.lowercase(Locale.US)) {
        "critical" -> 0
        "error" -> 1
        "warning" -> 2
        else -> 3
    }

    /**
     * Severity glyph for the row's leading mark: an alert siren for anything HA
     * flagged (critical / error / warning) and the neutral generic glyph for
     * unlabelled / informational issues. Tinted at the call site to the row's
     * severity tone.
     */
    fun severityIcon(severity: String): ImageVector = when (severity.lowercase(Locale.US)) {
        "critical", "error", "warning" -> R1IconSet.Siren
        else -> R1IconSet.Generic
    }

    /**
     * Canonical list order for the feed: active issues before ignored ones,
     * then severity-first, then newest-created-first. Stable for ties so equal
     * rows keep HA's original ordering. The `createdAt` strings are ISO-8601 so
     * lexical descending matches chronological newest-first.
     */
    fun sortIssues(issues: List<RepairIssue>): List<RepairIssue> =
        issues.sortedWith(
            compareBy<RepairIssue> { if (it.ignored) 1 else 0 }
                .thenBy { severityRank(it.severity) }
                .thenByDescending { it.createdAt ?: "" },
        )

    /** Counts driving the header breakdown line and its accent colour. */
    data class Breakdown(
        val critical: Int,
        val errors: Int,
        val warnings: Int,
        val ignored: Int,
        val total: Int,
    ) {
        val activeTotal: Int get() = critical + errors + warnings
    }

    /**
     * Bucket the list into the header counts. `warnings` absorbs every active
     * row HA didn't label critical or error (including unknown severities) so
     * the four buckets always sum to the active total without dropping rows.
     */
    fun breakdown(issues: List<RepairIssue>): Breakdown {
        val active = issues.filterNot { it.ignored }
        val critical = active.count { it.severity.equals("critical", ignoreCase = true) }
        val errors = active.count { it.severity.equals("error", ignoreCase = true) }
        val warnings = active.size - critical - errors
        val ignored = issues.size - active.size
        return Breakdown(
            critical = critical,
            errors = errors,
            warnings = warnings,
            ignored = ignored,
            total = issues.size,
        )
    }

    /**
     * Compose the one-line severity summary shown above the feed. Returns a
     * plain item count when there is nothing notable to call out (e.g. only
     * unknown-severity rows with no ignores would still report the warning
     * count, but a truly empty breakdown falls back to "N ITEMS").
     */
    fun summaryLine(b: Breakdown): String {
        val parts = buildList {
            if (b.critical > 0) add("${b.critical} CRITICAL")
            if (b.errors > 0) add("${b.errors} ERROR${plural(b.errors)}")
            if (b.warnings > 0) add("${b.warnings} WARNING${plural(b.warnings)}")
            if (b.ignored > 0) add("${b.ignored} IGNORED")
        }
        return if (parts.isEmpty()) "${b.total} ITEMS" else parts.joinToString(" · ")
    }

    private fun plural(n: Int): String = if (n == 1) "" else "S"

    /** Parse HA's ISO-8601 `created` string into an Instant, null when absent or malformed. */
    fun parseCreatedAt(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        return parseHaInstant(raw)
    }

    /**
     * Humanize a repair title for display. HA routes most titles through a
     * translation key shaped like `issue_homeassistant_yaml_deprecated`; the raw
     * key reads as noise, so we strip a leading `issue_` marker, replace
     * separators with spaces, and Title-Case each word. A blank input falls back
     * to the issue id (handled by the caller passing it as [fallback]).
     */
    fun humanizeTitle(translationKey: String?, fallback: String): String {
        val key = translationKey?.trim()?.takeIf { it.isNotBlank() } ?: return fallback
        val cleaned = key
            .removePrefix("issue_")
            .replace('_', ' ')
            .replace('.', ' ')
            .replace('-', ' ')
            .trim()
            .ifBlank { return fallback }
        return cleaned.split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { c ->
                    if (c.isLowerCase()) c.titlecase(Locale.US) else c.toString()
                }
            }
    }

    /**
     * Build the HA web-UI deep link to the repairs dashboard from a server base
     * URL (HA's external or internal URL). Returns null when no usable base is
     * known so the caller can fall back to plain "Fix in Home Assistant" copy
     * rather than an unopenable link.
     */
    fun repairsDashboardUrl(baseUrl: String?): String? {
        val base = baseUrl?.trim()?.trimEnd('/')
        if (base.isNullOrBlank()) return null
        if (!base.startsWith("http://") && !base.startsWith("https://")) return null
        return "$base/config/repairs/dashboard"
    }
}
