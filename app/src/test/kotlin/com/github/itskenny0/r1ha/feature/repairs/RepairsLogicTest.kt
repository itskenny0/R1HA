package com.github.itskenny0.r1ha.feature.repairs

import com.github.itskenny0.r1ha.core.ha.RepairIssue
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Unit tests for the pure Repairs helpers in RepairsLogic.kt: severity ranking,
 * list sort order, header breakdown / summary, created-time parsing, and the
 * HA fix-flow deep link builder.
 */
class RepairsLogicTest {

    private fun issue(
        domain: String = "homeassistant",
        issueId: String = "id",
        severity: String = "warning",
        isFixable: Boolean = false,
        ignored: Boolean = false,
        createdAt: String? = null,
    ) = RepairIssue(
        domain = domain,
        issueId = issueId,
        severity = severity,
        translationKey = null,
        description = null,
        isFixable = isFixable,
        ignored = ignored,
        createdAt = createdAt,
    )

    // --- severityRank ---

    @Test
    fun severityRank_orders_critical_error_warning_then_unknown() {
        assertThat(RepairsLogic.severityRank("critical")).isEqualTo(0)
        assertThat(RepairsLogic.severityRank("error")).isEqualTo(1)
        assertThat(RepairsLogic.severityRank("warning")).isEqualTo(2)
        assertThat(RepairsLogic.severityRank("nonsense")).isEqualTo(3)
    }

    @Test
    fun severityRank_is_case_insensitive() {
        assertThat(RepairsLogic.severityRank("CRITICAL")).isEqualTo(0)
        assertThat(RepairsLogic.severityRank("Error")).isEqualTo(1)
    }

    // --- humanizeTitle ---

    @Test
    fun humanizeTitle_strips_issue_prefix_and_title_cases() {
        assertThat(
            RepairsLogic.humanizeTitle("issue_homeassistant_yaml_deprecated", "fallback"),
        ).isEqualTo("Homeassistant Yaml Deprecated")
    }

    @Test
    fun humanizeTitle_falls_back_when_key_blank_or_null() {
        assertThat(RepairsLogic.humanizeTitle(null, "id_fallback")).isEqualTo("id_fallback")
        assertThat(RepairsLogic.humanizeTitle("   ", "id_fallback")).isEqualTo("id_fallback")
    }

    @Test
    fun humanizeTitle_handles_dot_and_dash_separators() {
        assertThat(RepairsLogic.humanizeTitle("deprecated.api-call", "fb"))
            .isEqualTo("Deprecated Api Call")
    }

    // --- sortIssues ---

    @Test
    fun sortIssues_puts_ignored_last_regardless_of_severity() {
        val ignoredCritical = issue(issueId = "a", severity = "critical", ignored = true)
        val activeWarning = issue(issueId = "b", severity = "warning")
        val sorted = RepairsLogic.sortIssues(listOf(ignoredCritical, activeWarning))
        assertThat(sorted.map { it.issueId }).containsExactly("b", "a").inOrder()
    }

    @Test
    fun sortIssues_orders_active_by_severity_then_newest_created() {
        val warn = issue(issueId = "w", severity = "warning", createdAt = "2024-01-01T00:00:00Z")
        val errOld = issue(issueId = "e1", severity = "error", createdAt = "2024-01-01T00:00:00Z")
        val errNew = issue(issueId = "e2", severity = "error", createdAt = "2024-06-01T00:00:00Z")
        val crit = issue(issueId = "c", severity = "critical", createdAt = "2020-01-01T00:00:00Z")
        val sorted = RepairsLogic.sortIssues(listOf(warn, errOld, errNew, crit))
        assertThat(sorted.map { it.issueId }).containsExactly("c", "e2", "e1", "w").inOrder()
    }

    @Test
    fun sortIssues_handles_null_created_as_oldest() {
        val withTime = issue(issueId = "t", severity = "error", createdAt = "2024-01-01T00:00:00Z")
        val noTime = issue(issueId = "n", severity = "error", createdAt = null)
        val sorted = RepairsLogic.sortIssues(listOf(noTime, withTime))
        assertThat(sorted.map { it.issueId }).containsExactly("t", "n").inOrder()
    }

    // --- breakdown ---

    @Test
    fun breakdown_counts_active_by_severity_and_ignored_separately() {
        val b = RepairsLogic.breakdown(
            listOf(
                issue(issueId = "1", severity = "critical"),
                issue(issueId = "2", severity = "error"),
                issue(issueId = "3", severity = "error"),
                issue(issueId = "4", severity = "warning"),
                issue(issueId = "5", severity = "critical", ignored = true),
            ),
        )
        assertThat(b.critical).isEqualTo(1)
        assertThat(b.errors).isEqualTo(2)
        assertThat(b.warnings).isEqualTo(1)
        assertThat(b.ignored).isEqualTo(1)
        assertThat(b.total).isEqualTo(5)
        assertThat(b.activeTotal).isEqualTo(4)
    }

    @Test
    fun breakdown_treats_unknown_severity_as_warning_bucket() {
        val b = RepairsLogic.breakdown(
            listOf(
                issue(issueId = "1", severity = "warning"),
                issue(issueId = "2", severity = "mystery"),
            ),
        )
        // Both active rows land in warnings so the buckets sum to the active total.
        assertThat(b.warnings).isEqualTo(2)
        assertThat(b.warnings + b.critical + b.errors).isEqualTo(b.activeTotal)
    }

    @Test
    fun breakdown_empty_list_is_all_zero() {
        val b = RepairsLogic.breakdown(emptyList())
        assertThat(b.total).isEqualTo(0)
        assertThat(b.activeTotal).isEqualTo(0)
        assertThat(b.ignored).isEqualTo(0)
    }

    // --- summaryLine ---

    @Test
    fun summaryLine_joins_present_buckets_with_pluralization() {
        val b = RepairsLogic.Breakdown(critical = 2, errors = 1, warnings = 3, ignored = 1, total = 7)
        assertThat(RepairsLogic.summaryLine(b))
            .isEqualTo("2 CRITICAL · 1 ERROR · 3 WARNINGS · 1 IGNORED")
    }

    @Test
    fun summaryLine_omits_zero_buckets() {
        val b = RepairsLogic.Breakdown(critical = 0, errors = 1, warnings = 0, ignored = 0, total = 1)
        assertThat(RepairsLogic.summaryLine(b)).isEqualTo("1 ERROR")
    }

    @Test
    fun summaryLine_falls_back_to_item_count_when_nothing_notable() {
        val b = RepairsLogic.Breakdown(critical = 0, errors = 0, warnings = 0, ignored = 0, total = 3)
        assertThat(RepairsLogic.summaryLine(b)).isEqualTo("3 ITEMS")
    }

    // --- parseCreatedAt ---

    @Test
    fun parseCreatedAt_parses_valid_iso8601() {
        assertThat(RepairsLogic.parseCreatedAt("2024-01-01T00:00:00Z"))
            .isEqualTo(Instant.parse("2024-01-01T00:00:00Z"))
    }

    @Test
    fun parseCreatedAt_returns_null_for_blank_null_and_garbage() {
        assertThat(RepairsLogic.parseCreatedAt(null)).isNull()
        assertThat(RepairsLogic.parseCreatedAt("   ")).isNull()
        assertThat(RepairsLogic.parseCreatedAt("not-a-date")).isNull()
    }

    // --- repairsDashboardUrl ---

    @Test
    fun repairsDashboardUrl_builds_dashboard_path_and_trims_trailing_slash() {
        assertThat(RepairsLogic.repairsDashboardUrl("https://ha.local:8123/"))
            .isEqualTo("https://ha.local:8123/config/repairs/dashboard")
        assertThat(RepairsLogic.repairsDashboardUrl("http://10.0.0.5:8123"))
            .isEqualTo("http://10.0.0.5:8123/config/repairs/dashboard")
    }

    @Test
    fun repairsDashboardUrl_returns_null_for_blank_null_and_non_http() {
        assertThat(RepairsLogic.repairsDashboardUrl(null)).isNull()
        assertThat(RepairsLogic.repairsDashboardUrl("   ")).isNull()
        assertThat(RepairsLogic.repairsDashboardUrl("ha.local:8123")).isNull()
    }
}
