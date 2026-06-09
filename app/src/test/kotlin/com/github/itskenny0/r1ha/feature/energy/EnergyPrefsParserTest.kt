package com.github.itskenny0.r1ha.feature.energy

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Test

/**
 * Covers the pure [parseEnergyPrefsJson] helper that extracts the
 * entity-id -> custom-name map from HA's `energy/get_prefs` WS response.
 *
 * UNVERIFIED OFFLINE: the JSON shapes tested here are derived from HA's
 * documented energy websocket API and have not been verified against a live
 * Home Assistant instance.
 *
 * No Android APIs, no Compose, no coroutines.
 */
class EnergyPrefsParserTest {

    private fun parse(json: String) =
        parseEnergyPrefsJson(Json.parseToJsonElement(json))

    // ---- well-formed payloads ------------------------------------------------

    @Test fun `parses a single entry with a name`() {
        val result = parse(
            """{"device_consumption": [{"stat_consumption": "sensor.fridge_power", "name": "Fridge"}]}""",
        )
        assertThat(result).containsExactly("sensor.fridge_power", "Fridge")
    }

    @Test fun `parses multiple entries`() {
        val result = parse(
            """{"device_consumption": [
                {"stat_consumption": "sensor.fridge_power", "name": "Fridge"},
                {"stat_consumption": "sensor.oven_power", "name": "Oven"}
            ]}""",
        )
        assertThat(result).containsExactly(
            "sensor.fridge_power", "Fridge",
            "sensor.oven_power", "Oven",
        )
    }

    // ---- absent or blank name field -----------------------------------------

    @Test fun `entry without name field is omitted from result`() {
        val result = parse(
            """{"device_consumption": [{"stat_consumption": "sensor.fridge_power"}]}""",
        )
        assertThat(result).isEmpty()
    }

    @Test fun `entry with blank name is omitted`() {
        val result = parse(
            """{"device_consumption": [{"stat_consumption": "sensor.fridge_power", "name": ""}]}""",
        )
        assertThat(result).isEmpty()
    }

    @Test fun `entry with whitespace-only name is omitted`() {
        val result = parse(
            """{"device_consumption": [{"stat_consumption": "sensor.fridge_power", "name": "   "}]}""",
        )
        assertThat(result).isEmpty()
    }

    @Test fun `mix of named and unnamed entries - only named returned`() {
        val result = parse(
            """{"device_consumption": [
                {"stat_consumption": "sensor.fridge_power", "name": "Fridge"},
                {"stat_consumption": "sensor.tv_power"}
            ]}""",
        )
        assertThat(result).containsExactly("sensor.fridge_power", "Fridge")
    }

    // ---- missing or malformed structure -------------------------------------

    @Test fun `null payload yields empty map`() {
        assertThat(parseEnergyPrefsJson(null)).isEmpty()
    }

    @Test fun `JsonNull payload yields empty map`() {
        assertThat(parseEnergyPrefsJson(JsonNull)).isEmpty()
    }

    @Test fun `missing device_consumption key yields empty map`() {
        val result = parse("""{"other_key": []}""")
        assertThat(result).isEmpty()
    }

    @Test fun `empty device_consumption array yields empty map`() {
        val result = parse("""{"device_consumption": []}""")
        assertThat(result).isEmpty()
    }

    @Test fun `malformed row (not an object) is skipped`() {
        val result = parse(
            """{"device_consumption": [42, {"stat_consumption": "sensor.fridge_power", "name": "Fridge"}]}""",
        )
        assertThat(result).containsExactly("sensor.fridge_power", "Fridge")
    }

    @Test fun `entry with blank stat_consumption is skipped`() {
        val result = parse(
            """{"device_consumption": [{"stat_consumption": "", "name": "Fridge"}]}""",
        )
        assertThat(result).isEmpty()
    }

    @Test fun `entry with missing stat_consumption is skipped`() {
        val result = parse(
            """{"device_consumption": [{"name": "Fridge"}]}""",
        )
        assertThat(result).isEmpty()
    }

    // ---- extra fields in the payload are ignored ----------------------------

    @Test fun `extra top-level keys in payload are ignored`() {
        val result = parse(
            """{"device_consumption": [{"stat_consumption": "sensor.fridge_power", "name": "Fridge"}],
               "solar_power": [{"stat_solar_production": "sensor.solar"}]}""",
        )
        assertThat(result).containsExactly("sensor.fridge_power", "Fridge")
    }

    @Test fun `extra fields inside a row are ignored`() {
        val result = parse(
            """{"device_consumption": [
                {"stat_consumption": "sensor.fridge_power", "name": "Fridge", "icon": "mdi:fridge"}
            ]}""",
        )
        assertThat(result).containsExactly("sensor.fridge_power", "Fridge")
    }
}
