package com.github.itskenny0.r1ha.feature.labels

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure Labels helpers in LabelLogic.kt: membership grouping
 * across the three registries, color parsing (named + hex + fallback), icon
 * normalization, and the search filter.
 */
class LabelLogicTest {

    private val fallback = Color(0xFFF36F21)

    // --- groupMembership ---

    @Test
    fun groupMembership_buckets_each_kind_and_sorts_by_name() {
        val m = LabelLogic.groupMembership(
            entities = mapOf("light.b" to "Beta", "light.a" to "Alpha"),
            devices = mapOf("dev1" to "Zeta Hub"),
            areas = mapOf("kitchen" to "Kitchen", "den" to "Den"),
        )
        assertThat(m.entities.map { it.name }).containsExactly("Alpha", "Beta").inOrder()
        assertThat(m.entities.first().kind).isEqualTo(LabelLogic.MemberKind.ENTITY)
        assertThat(m.devices.single().kind).isEqualTo(LabelLogic.MemberKind.DEVICE)
        assertThat(m.areas.map { it.name }).containsExactly("Den", "Kitchen").inOrder()
        assertThat(m.total).isEqualTo(5)
        assertThat(m.isEmpty).isFalse()
    }

    @Test
    fun groupMembership_falls_back_to_id_when_name_blank_and_drops_blank_ids() {
        val m = LabelLogic.groupMembership(
            entities = mapOf("light.x" to "  ", "   " to "Ignored"),
            devices = emptyMap(),
            areas = emptyMap(),
        )
        assertThat(m.entities).hasSize(1)
        assertThat(m.entities.single().name).isEqualTo("light.x")
        assertThat(m.entities.single().id).isEqualTo("light.x")
    }

    @Test
    fun groupMembership_empty_is_reported_empty() {
        val m = LabelLogic.groupMembership(emptyMap(), emptyMap(), emptyMap())
        assertThat(m.isEmpty).isTrue()
        assertThat(m.total).isEqualTo(0)
    }

    // --- parseLabelColor / parseHex ---

    @Test
    fun parseLabelColor_resolves_named_colors_including_dash_and_underscore() {
        val dash = LabelLogic.parseLabelColor("deep-purple", fallback)
        val underscore = LabelLogic.parseLabelColor("deep_purple", fallback)
        assertThat(dash).isEqualTo(Color(0xFF5E35B1))
        assertThat(underscore).isEqualTo(dash)
    }

    @Test
    fun parseLabelColor_is_case_insensitive() {
        assertThat(LabelLogic.parseLabelColor("RED", fallback)).isEqualTo(Color(0xFFE53935))
    }

    @Test
    fun parseLabelColor_parses_hex() {
        assertThat(LabelLogic.parseLabelColor("#1E88E5", fallback)).isEqualTo(Color(0xFF1E88E5))
    }

    @Test
    fun parseLabelColor_falls_back_for_null_blank_and_unknown() {
        assertThat(LabelLogic.parseLabelColor(null, fallback)).isEqualTo(fallback)
        assertThat(LabelLogic.parseLabelColor("   ", fallback)).isEqualTo(fallback)
        assertThat(LabelLogic.parseLabelColor("chartreuse", fallback)).isEqualTo(fallback)
    }

    @Test
    fun parseHex_handles_6_and_8_digit_and_rejects_garbage() {
        assertThat(LabelLogic.parseHex("#FFAA00")).isEqualTo(Color(0xFFFFAA00))
        assertThat(LabelLogic.parseHex("80FFAA00")).isEqualTo(Color(0x80FFAA00))
        assertThat(LabelLogic.parseHex("nothex")).isNull()
        assertThat(LabelLogic.parseHex("#FFF")).isNull()
        assertThat(LabelLogic.parseHex(null)).isNull()
    }

    // --- normalizeIcon ---

    @Test
    fun normalizeIcon_strips_prefix_and_handles_blank_and_none() {
        assertThat(LabelLogic.normalizeIcon("mdi:tag-outline")).isEqualTo("tag-outline")
        assertThat(LabelLogic.normalizeIcon("tag")).isEqualTo("tag")
        assertThat(LabelLogic.normalizeIcon(null)).isNull()
        assertThat(LabelLogic.normalizeIcon("  ")).isNull()
        assertThat(LabelLogic.normalizeIcon("none")).isNull()
    }

    // --- matchesQuery ---

    @Test
    fun matchesQuery_blank_matches_everything() {
        assertThat(LabelLogic.matchesQuery("", "Anything")).isTrue()
        assertThat(LabelLogic.matchesQuery("   ", "Anything")).isTrue()
    }

    @Test
    fun matchesQuery_matches_label_name_case_insensitively() {
        assertThat(LabelLogic.matchesQuery("rout", "Daily Routine")).isTrue()
        assertThat(LabelLogic.matchesQuery("ROUT", "Daily Routine")).isTrue()
        assertThat(LabelLogic.matchesQuery("zzz", "Daily Routine")).isFalse()
    }

    @Test
    fun matchesQuery_matches_member_names_even_when_label_name_differs() {
        val matched = LabelLogic.matchesQuery(
            query = "kitchen",
            labelName = "AV Gear",
            memberNames = listOf("Living Room", "Kitchen Speaker"),
        )
        assertThat(matched).isTrue()
    }
}
