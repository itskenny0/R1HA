package com.github.itskenny0.r1ha.feature.automations

import com.github.itskenny0.r1ha.core.ha.EntityId
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Pure-helper coverage for the Automations surface: the name + entity_id
 * filter that backs the visible row list, last-triggered parsing, mode
 * mapping, enabled-state derivation, and the optimistic-toggle list
 * transform. All run with no repository / Android dependencies.
 */
class AutomationsViewModelTest {

    private fun entry(
        id: String,
        name: String,
        enabled: Boolean = true,
        mode: AutomationsViewModel.Mode = AutomationsViewModel.Mode.SINGLE,
        currentRunning: Int = 0,
        lastTriggered: Instant? = null,
    ) = AutomationsViewModel.Entry(
        id = EntityId(id),
        name = name,
        enabled = enabled,
        mode = mode,
        currentRunning = currentRunning,
        lastTriggered = lastTriggered,
    )

    private fun stateWith(
        all: List<AutomationsViewModel.Entry>,
        query: String = "",
    ) = AutomationsViewModel.UiState(loading = false, all = all, query = query)

    // --- enabledFrom -----------------------------------------------------

    @Test fun `enabledFrom is true only for an on state`() {
        assertThat(AutomationsViewModel.enabledFrom("on")).isTrue()
        assertThat(AutomationsViewModel.enabledFrom("ON")).isTrue()
        assertThat(AutomationsViewModel.enabledFrom("  on  ")).isTrue()
    }

    @Test fun `enabledFrom is false for off unavailable unknown blank and null`() {
        for (s in listOf("off", "OFF", "unavailable", "unknown", "", "   ")) {
            assertThat(AutomationsViewModel.enabledFrom(s)).isFalse()
        }
        assertThat(AutomationsViewModel.enabledFrom(null)).isFalse()
    }

    // --- availableFrom ---------------------------------------------------

    @Test fun `availableFrom is true for a clean on or off state`() {
        for (s in listOf("on", "OFF", "  on  ", "off")) {
            assertThat(AutomationsViewModel.availableFrom(s)).isTrue()
        }
    }

    @Test fun `availableFrom is false for unavailable unknown blank and null`() {
        for (s in listOf("unavailable", "unknown", "", "   ")) {
            assertThat(AutomationsViewModel.availableFrom(s)).isFalse()
        }
        assertThat(AutomationsViewModel.availableFrom(null)).isFalse()
    }

    // --- modeOf ----------------------------------------------------------

    @Test fun `modeOf maps the four known modes case-insensitively`() {
        assertThat(AutomationsViewModel.modeOf("single")).isEqualTo(AutomationsViewModel.Mode.SINGLE)
        assertThat(AutomationsViewModel.modeOf("PARALLEL")).isEqualTo(AutomationsViewModel.Mode.PARALLEL)
        assertThat(AutomationsViewModel.modeOf("  queued ")).isEqualTo(AutomationsViewModel.Mode.QUEUED)
        assertThat(AutomationsViewModel.modeOf("Restart")).isEqualTo(AutomationsViewModel.Mode.RESTART)
    }

    @Test fun `modeOf falls through to UNKNOWN for null or unrecognised`() {
        assertThat(AutomationsViewModel.modeOf(null)).isEqualTo(AutomationsViewModel.Mode.UNKNOWN)
        assertThat(AutomationsViewModel.modeOf("")).isEqualTo(AutomationsViewModel.Mode.UNKNOWN)
        assertThat(AutomationsViewModel.modeOf("future_mode")).isEqualTo(AutomationsViewModel.Mode.UNKNOWN)
    }

    // --- lastTriggeredOf -------------------------------------------------

    @Test fun `lastTriggeredOf parses an ISO timestamp`() {
        val ts = "2026-05-29T08:13:04.123456+00:00"
        assertThat(AutomationsViewModel.lastTriggeredOf(ts)).isEqualTo(Instant.parse(ts))
    }

    @Test fun `lastTriggeredOf trims surrounding whitespace`() {
        val ts = "2026-05-29T08:13:04+00:00"
        assertThat(AutomationsViewModel.lastTriggeredOf("  $ts  ")).isEqualTo(Instant.parse(ts))
    }

    @Test fun `lastTriggeredOf yields null for never-fired and malformed input`() {
        for (s in listOf(null, "", "   ", "not-a-timestamp", "unknown")) {
            assertThat(AutomationsViewModel.lastTriggeredOf(s)).isNull()
        }
    }

    // --- withEnabled (optimistic toggle) ---------------------------------

    private val morning = entry("automation.morning", "Morning", enabled = false)
    private val away = entry("automation.away", "Away Mode", enabled = true)

    @Test fun `withEnabled flips only the targeted entry`() {
        val out = AutomationsViewModel.withEnabled(
            listOf(morning, away),
            EntityId("automation.morning"),
            enabled = true,
        )
        assertThat(out.first { it.id == morning.id }.enabled).isTrue()
        assertThat(out.first { it.id == away.id }.enabled).isTrue() // untouched
    }

    @Test fun `withEnabled preserves order and other fields`() {
        val out = AutomationsViewModel.withEnabled(
            listOf(morning, away),
            EntityId("automation.away"),
            enabled = false,
        )
        assertThat(out.map { it.id }).containsExactly(morning.id, away.id).inOrder()
        assertThat(out.first { it.id == away.id }.name).isEqualTo("Away Mode")
        assertThat(out.first { it.id == away.id }.enabled).isFalse()
    }

    @Test fun `withEnabled is a no-op when the id is absent`() {
        val input = listOf(morning, away)
        val out = AutomationsViewModel.withEnabled(input, EntityId("automation.ghost"), enabled = true)
        assertThat(out).isEqualTo(input)
    }

    // --- filter (UiState.entries) ---------------------------------------

    private val kitchen = entry("automation.kitchen_lights", "Kitchen Lights")
    private val sunset = entry("automation.sunset_scene", "Sunset Scene")
    private val bedtime = entry("automation.bedtime", "Bedtime")
    private val sample = listOf(kitchen, sunset, bedtime)

    @Test fun `blank query returns everything`() {
        assertThat(stateWith(sample).entries).containsExactly(kitchen, sunset, bedtime)
    }

    @Test fun `query matches name case-insensitively`() {
        assertThat(stateWith(sample, query = "KITCHEN").entries).containsExactly(kitchen)
    }

    @Test fun `query matches entity id`() {
        assertThat(stateWith(sample, query = "sunset_scene").entries).containsExactly(sunset)
    }

    @Test fun `query is trimmed before matching`() {
        assertThat(stateWith(sample, query = "  bedtime  ").entries).containsExactly(bedtime)
    }

    @Test fun `no match returns empty`() {
        assertThat(stateWith(sample, query = "zzz").entries).isEmpty()
    }
}
