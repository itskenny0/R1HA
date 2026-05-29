package com.github.itskenny0.r1ha.feature.systemhealth

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure `system_health/info` parse / group / format helpers
 * in [SystemHealthInfo]. No device or websocket required.
 */
class SystemHealthInfoTest {

    private fun parse(json: String) =
        SystemHealthInfo.parse(Json.parseToJsonElement(json))

    // --- parse / grouping ---

    @Test
    fun parse_groups_by_domain_and_titles_them() {
        val sections = parse(
            """
            {
              "homeassistant": { "info": { "version": "2024.6.0" } },
              "cloud": { "info": { "logged_in": true } }
            }
            """.trimIndent(),
        )
        assertThat(sections).hasSize(2)
        // homeassistant always sorts first.
        assertThat(sections[0].domain).isEqualTo("homeassistant")
        assertThat(sections[0].title).isEqualTo("Home Assistant")
        assertThat(sections[1].title).isEqualTo("Home Assistant Cloud")
    }

    @Test
    fun parse_sorts_non_core_domains_alphabetically_by_title() {
        val sections = parse(
            """
            {
              "zwave": { "info": { "a": 1 } },
              "homeassistant": { "info": { "version": "1" } },
              "cloud": { "info": { "b": 2 } }
            }
            """.trimIndent(),
        )
        assertThat(sections.map { it.domain })
            .containsExactly("homeassistant", "cloud", "zwave")
            .inOrder()
    }

    @Test
    fun parse_falls_back_to_direct_map_when_no_info_wrapper() {
        val sections = parse("""{ "lovelace": { "dashboards": 3 } }""")
        assertThat(sections).hasSize(1)
        assertThat(sections[0].rows.first().value.display).isEqualTo("3")
    }

    @Test
    fun parse_skips_malformed_and_empty_entries() {
        val sections = parse(
            """
            {
              "broken": 42,
              "empty": { "info": {} },
              "good": { "info": { "x": 1 } }
            }
            """.trimIndent(),
        )
        assertThat(sections.map { it.domain }).containsExactly("good")
    }

    @Test
    fun parse_returns_empty_for_non_object_or_null() {
        assertThat(SystemHealthInfo.parse(null)).isEmpty()
        assertThat(parse("[]")).isEmpty()
        assertThat(parse("\"nope\"")).isEmpty()
    }

    @Test
    fun parse_sorts_rows_within_a_section_by_label() {
        val rows = parse(
            """{ "homeassistant": { "info": { "version": "1", "arch": "x86" } } }""",
        )[0].rows
        // "Architecture" sorts before "Version".
        assertThat(rows.map { it.label }).containsExactly("Architecture", "Version").inOrder()
    }

    // --- value normalisation: reachability status ---

    @Test
    fun normalize_tagged_failed_object_carries_error_and_status() {
        val sections = parse(
            """{ "cloud": { "info": {
                "can_reach_cloud": { "type": "failed", "error": "unreachable" }
            } } }""",
        )
        val value = sections[0].rows.first().value
        assertThat(value.status).isEqualTo(HealthStatus.FAILED)
        assertThat(value.error).isEqualTo("unreachable")
        assertThat(value.display).isEqualTo("failed: unreachable")
    }

    @Test
    fun normalize_tagged_pending_object() {
        val sections = parse(
            """{ "cloud": { "info": { "c": { "type": "pending" } } } }""",
        )
        val value = sections[0].rows.first().value
        assertThat(value.status).isEqualTo(HealthStatus.PENDING)
        assertThat(value.display).isEqualTo("checking…")
    }

    @Test
    fun normalize_tagged_ok_object() {
        val sections = parse(
            """{ "cloud": { "info": { "c": { "type": "ok" } } } }""",
        )
        assertThat(sections[0].rows.first().value.status).isEqualTo(HealthStatus.OK)
    }

    @Test
    fun normalize_bare_ok_and_failed_strings() {
        val sections = parse(
            """{ "cloud": { "info": { "a": "ok", "b": "failed", "c": "pending" } } }""",
        )
        val byKey = sections[0].rows.associate { it.key to it.value.status }
        assertThat(byKey["a"]).isEqualTo(HealthStatus.OK)
        assertThat(byKey["b"]).isEqualTo(HealthStatus.FAILED)
        assertThat(byKey["c"]).isEqualTo(HealthStatus.PENDING)
    }

    @Test
    fun normalize_plain_string_is_neutral() {
        val v = SystemHealthInfo.normalizeValue(Json.parseToJsonElement("\"x86_64\""))
        assertThat(v.status).isEqualTo(HealthStatus.NEUTRAL)
        assertThat(v.display).isEqualTo("x86_64")
    }

    // --- value normalisation: primitives ---

    @Test
    fun normalize_boolean_renders_yes_no() {
        assertThat(SystemHealthInfo.normalizeValue(Json.parseToJsonElement("true")).display)
            .isEqualTo("yes")
        assertThat(SystemHealthInfo.normalizeValue(Json.parseToJsonElement("false")).display)
            .isEqualTo("no")
    }

    @Test
    fun normalize_integer_and_double() {
        assertThat(SystemHealthInfo.normalizeValue(Json.parseToJsonElement("3")).display)
            .isEqualTo("3")
        // Whole-valued double collapses to an int string.
        assertThat(SystemHealthInfo.normalizeValue(Json.parseToJsonElement("4.0")).display)
            .isEqualTo("4")
        assertThat(SystemHealthInfo.normalizeValue(Json.parseToJsonElement("1.5")).display)
            .isEqualTo("1.50")
    }

    @Test
    fun normalize_null_is_unknown() {
        assertThat(SystemHealthInfo.normalizeValue(null).display).isEqualTo("unknown")
        assertThat(SystemHealthInfo.normalizeValue(Json.parseToJsonElement("null")).display)
            .isEqualTo("unknown")
    }

    @Test
    fun normalize_array_joins_elements() {
        val v = SystemHealthInfo.normalizeValue(Json.parseToJsonElement("""["a", "b", 3]"""))
        assertThat(v.display).isEqualTo("a, b, 3")
        assertThat(v.status).isEqualTo(HealthStatus.NEUTRAL)
    }

    @Test
    fun normalize_untagged_object_renders_key_values() {
        val v = SystemHealthInfo.normalizeValue(Json.parseToJsonElement("""{"k": 1}"""))
        assertThat(v.display).isEqualTo("k=1")
        assertThat(v.status).isEqualTo(HealthStatus.NEUTRAL)
    }

    // --- section status rollup ---

    @Test
    fun section_hasFailure_and_hasPending_rollup() {
        val sections = parse(
            """{ "cloud": { "info": {
                "ok": "ok",
                "down": { "type": "failed" },
                "wait": { "type": "pending" }
            } } }""",
        )
        assertThat(sections[0].hasFailure).isTrue()
        assertThat(sections[0].hasPending).isTrue()

        val healthy = parse("""{ "cloud": { "info": { "a": "ok" } } }""")
        assertThat(healthy[0].hasFailure).isFalse()
        assertThat(healthy[0].hasPending).isFalse()
    }

    // --- humanizers ---

    @Test
    fun humanizeDomain_known_and_generic() {
        assertThat(SystemHealthInfo.humanizeDomain("mqtt")).isEqualTo("MQTT")
        assertThat(SystemHealthInfo.humanizeDomain("homeassistant")).isEqualTo("Home Assistant")
        assertThat(SystemHealthInfo.humanizeDomain("my_custom_thing"))
            .isEqualTo("My Custom Thing")
    }

    @Test
    fun humanizeKey_known_and_generic() {
        assertThat(SystemHealthInfo.humanizeKey("installation_type"))
            .isEqualTo("Installation type")
        assertThat(SystemHealthInfo.humanizeKey("some_other_key")).isEqualTo("Some Other Key")
    }
}
