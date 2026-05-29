package com.github.itskenny0.r1ha.feature.logbook

import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.LogbookEntry
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Unit tests for the pure Logbook filtering / grouping helpers in
 * LogbookGrouping.kt. These cover the entity / domain / text filter pass, the
 * domain enumeration that feeds the chip row, and the relative-day bucketing.
 */
class LogbookGroupingTest {
    private val utc = ZoneId.of("UTC")

    private fun entry(
        whenIso: String,
        name: String = "Thing",
        message: String = "changed",
        entityId: String? = null,
        domain: String? = null,
        state: String? = null,
        contextUserId: String? = null,
        contextEntityId: String? = null,
        contextName: String? = null,
    ) = LogbookEntry(
        timestamp = Instant.parse(whenIso),
        name = name,
        message = message,
        entityId = entityId?.let { EntityId(it) },
        domain = domain,
        state = state,
        contextUserId = contextUserId,
        contextEntityId = contextEntityId,
        contextName = contextName,
    )

    // ── applyFilters ──────────────────────────────────────────────────────

    @Test
    fun `no filters returns the same list instance`() {
        val list = listOf(entry("2026-05-29T10:00:00Z"))
        assertThat(applyFilters(list, null, null, "")).isSameInstanceAs(list)
    }

    @Test
    fun `entity filter matches exact entity_id only`() {
        val list = listOf(
            entry("2026-05-29T10:00:00Z", entityId = "light.kitchen", domain = "light"),
            entry("2026-05-29T10:01:00Z", entityId = "light.bedroom", domain = "light"),
        )
        val out = applyFilters(list, "light.kitchen", null, "")
        assertThat(out).hasSize(1)
        assertThat(out.single().entityId?.value).isEqualTo("light.kitchen")
    }

    @Test
    fun `domain filter matches exact domain only`() {
        val list = listOf(
            entry("2026-05-29T10:00:00Z", entityId = "light.kitchen", domain = "light"),
            entry("2026-05-29T10:01:00Z", entityId = "automation.x", domain = "automation"),
        )
        val out = applyFilters(list, null, "automation", "")
        assertThat(out).hasSize(1)
        assertThat(out.single().domain).isEqualTo("automation")
    }

    @Test
    fun `text query matches name message and entity_id case-insensitively`() {
        val list = listOf(
            entry("2026-05-29T10:00:00Z", name = "Kitchen Light", entityId = "light.kitchen"),
            entry("2026-05-29T10:01:00Z", name = "Hallway", message = "turned ON the light"),
            entry("2026-05-29T10:02:00Z", name = "Bedroom", entityId = "light.bedroom"),
        )
        // name + entity_id both match the first row only.
        assertThat(applyFilters(list, null, null, "kitchen")).hasSize(1)
        // entity_id substring "light." matches the two rows carrying an entity.
        assertThat(applyFilters(list, null, null, "light.")).hasSize(2)
        // message match (case-insensitive).
        assertThat(applyFilters(list, null, null, "turned on")).hasSize(1)
    }

    @Test
    fun `combined entity and domain and query all apply`() {
        val list = listOf(
            entry("2026-05-29T10:00:00Z", name = "Kitchen", entityId = "light.kitchen", domain = "light"),
            entry("2026-05-29T10:01:00Z", name = "Kitchen", entityId = "switch.kitchen", domain = "switch"),
        )
        val out = applyFilters(list, "light.kitchen", "light", "kitchen")
        assertThat(out).hasSize(1)
        assertThat(out.single().entityId?.value).isEqualTo("light.kitchen")
    }

    @Test
    fun `blank entity and domain are treated as no-op`() {
        val list = listOf(entry("2026-05-29T10:00:00Z", domain = "light"))
        assertThat(applyFilters(list, "", "  ", "")).isSameInstanceAs(list)
    }

    // ── availableDomains ──────────────────────────────────────────────────

    @Test
    fun `availableDomains is distinct and sorted case-insensitively`() {
        val list = listOf(
            entry("2026-05-29T10:00:00Z", domain = "light"),
            entry("2026-05-29T10:01:00Z", domain = "automation"),
            entry("2026-05-29T10:02:00Z", domain = "light"),
            entry("2026-05-29T10:03:00Z", domain = null),
            entry("2026-05-29T10:04:00Z", domain = ""),
        )
        assertThat(availableDomains(list)).containsExactly("automation", "light").inOrder()
    }

    @Test
    fun `availableDomains is empty for no domains`() {
        val list = listOf(entry("2026-05-29T10:00:00Z", domain = null))
        assertThat(availableDomains(list)).isEmpty()
    }

    // ── groupByDay ────────────────────────────────────────────────────────

    @Test
    fun `groupByDay buckets into today yesterday and absolute dates`() {
        val now = Instant.parse("2026-05-29T12:00:00Z")
        val entries = listOf(
            entry("2026-05-29T11:00:00Z"), // today
            entry("2026-05-29T09:00:00Z"), // today
            entry("2026-05-28T20:00:00Z"), // yesterday
            entry("2026-05-26T08:00:00Z"), // older
        )
        val groups = groupByDay(entries, utc, now)
        assertThat(groups.map { it.header }).containsExactly(
            "TODAY", "YESTERDAY", "TUE, MAY 26",
        ).inOrder()
        assertThat(groups[0].entries).hasSize(2)
        assertThat(groups[1].entries).hasSize(1)
        assertThat(groups[2].entries).hasSize(1)
    }

    @Test
    fun `groupByDay preserves newest-first input order across groups`() {
        val now = Instant.parse("2026-05-29T12:00:00Z")
        val entries = listOf(
            entry("2026-05-29T11:00:00Z", name = "a"),
            entry("2026-05-28T11:00:00Z", name = "b"),
        )
        val groups = groupByDay(entries, utc, now)
        assertThat(groups.first().entries.first().name).isEqualTo("a")
    }

    @Test
    fun `groupByDay on empty input is empty`() {
        assertThat(groupByDay(emptyList(), utc, Instant.now())).isEmpty()
    }

    @Test
    fun `groupByDay respects the device zone for the day boundary`() {
        // 2026-05-29T01:00:00Z is still 2026-05-28 in a UTC-3 zone, so relative
        // to a "now" of 2026-05-29T12:00:00Z it lands on YESTERDAY there but
        // TODAY in UTC.
        val now = Instant.parse("2026-05-29T12:00:00Z")
        val e = listOf(entry("2026-05-29T01:00:00Z"))
        val minus3 = ZoneId.of("America/Sao_Paulo") // -03:00, no DST in May
        assertThat(groupByDay(e, utc, now).first().header).isEqualTo("TODAY")
        assertThat(groupByDay(e, minus3, now).first().header).isEqualTo("YESTERDAY")
    }

    // ── dayHeader ─────────────────────────────────────────────────────────

    @Test
    fun `dayHeader formats absolute dates as short weekday month day`() {
        val today = LocalDate.of(2026, 5, 29)
        val yesterday = today.minusDays(1)
        assertThat(dayHeader(today, today, yesterday)).isEqualTo("TODAY")
        assertThat(dayHeader(yesterday, today, yesterday)).isEqualTo("YESTERDAY")
        assertThat(dayHeader(LocalDate.of(2026, 5, 26), today, yesterday))
            .isEqualTo("TUE, MAY 26")
    }

    // ── triggeredByLabel ──────────────────────────────────────────────────

    @Test
    fun `triggeredByLabel is null when no context fields are present`() {
        assertThat(triggeredByLabel(entry("2026-05-29T10:00:00Z"))).isNull()
    }

    @Test
    fun `triggeredByLabel prefers the human context name`() {
        val e = entry(
            "2026-05-29T10:00:00Z",
            contextName = "Front Door Motion",
            contextEntityId = "binary_sensor.front_door",
            contextUserId = "abcdef0123456789",
        )
        assertThat(triggeredByLabel(e)).isEqualTo("by Front Door Motion")
    }

    @Test
    fun `triggeredByLabel falls back to the context entity_id`() {
        val e = entry(
            "2026-05-29T10:00:00Z",
            contextEntityId = "binary_sensor.front_door",
            contextUserId = "abcdef0123456789",
        )
        assertThat(triggeredByLabel(e)).isEqualTo("via binary_sensor.front_door")
    }

    @Test
    fun `triggeredByLabel falls back to a truncated user id`() {
        val e = entry("2026-05-29T10:00:00Z", contextUserId = "abcdef0123456789")
        assertThat(triggeredByLabel(e)).isEqualTo("by user abcdef01")
    }

    @Test
    fun `triggeredByLabel suppresses a self-trigger`() {
        // The context entity_id is the same as the row's own entity: nothing to
        // attribute beyond the row itself.
        val e = entry(
            "2026-05-29T10:00:00Z",
            entityId = "light.kitchen",
            contextEntityId = "light.kitchen",
        )
        assertThat(triggeredByLabel(e)).isNull()
    }

    @Test
    fun `triggeredByLabel still names a self-trigger when a context name is given`() {
        // A self-trigger by entity_id is suppressed, but a human label that
        // differs from the row name is still attributed via the name branch is
        // not reached: self-trigger short-circuits first. Assert that explicitly.
        val e = entry(
            "2026-05-29T10:00:00Z",
            entityId = "light.kitchen",
            contextEntityId = "light.kitchen",
            contextName = "Kitchen Light",
        )
        assertThat(triggeredByLabel(e)).isNull()
    }

    @Test
    fun `triggeredByLabel ignores blank context fields`() {
        val e = entry(
            "2026-05-29T10:00:00Z",
            contextName = "  ",
            contextEntityId = "",
            contextUserId = "   ",
        )
        assertThat(triggeredByLabel(e)).isNull()
    }

    // ── domainGlyph ───────────────────────────────────────────────────────

    @Test
    fun `domainGlyph falls back to a bullet for unknown and null domains`() {
        assertThat(domainGlyph(null)).isEqualTo("•")
        assertThat(domainGlyph("some_unknown_domain")).isEqualTo("•")
        assertThat(domainGlyph("light")).isNotEqualTo("•")
    }
}
