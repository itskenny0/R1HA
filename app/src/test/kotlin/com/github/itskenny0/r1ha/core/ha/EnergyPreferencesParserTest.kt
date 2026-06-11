package com.github.itskenny0.r1ha.core.ha

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Covers the pure [parseEnergyPreferences] / [parseEnergyInfo] decoders for the
 * `energy/get_prefs` and `energy/info` payloads.
 *
 * UNVERIFIED OFFLINE: the JSON shapes are derived from HA's documented energy
 * websocket API and have not been verified against a live HA energy setup.
 */
class EnergyPreferencesParserTest {

    private fun prefs(json: String) = parseEnergyPreferences(Json.parseToJsonElement(json))
    private fun info(json: String) = parseEnergyInfo(Json.parseToJsonElement(json))

    @Test fun `parses grid solar battery sources`() {
        val p = prefs(
            """
            {
              "energy_sources": [
                {"type": "grid", "stat_energy_from": "sensor.grid_in", "stat_energy_to": "sensor.grid_out", "stat_cost": "sensor.cost"},
                {"type": "solar", "stat_energy_from": "sensor.solar"},
                {"type": "battery", "stat_energy_from": "sensor.bat_out", "stat_energy_to": "sensor.bat_in"}
              ],
              "device_consumption": [
                {"stat_consumption": "sensor.fridge", "name": "Fridge"}
              ],
              "device_consumption_water": []
            }
            """.trimIndent(),
        )
        assertThat(p.sources).hasSize(3)
        val gridSource = p.sources.first { it.type == "grid" }
        assertThat(gridSource.statEnergyFrom).isEqualTo("sensor.grid_in")
        assertThat(gridSource.statEnergyTo).isEqualTo("sensor.grid_out")
        assertThat(gridSource.statCost).isEqualTo("sensor.cost")
        assertThat(p.deviceConsumption).hasSize(1)
        assertThat(p.deviceConsumption[0].statConsumption).isEqualTo("sensor.fridge")
        assertThat(p.deviceConsumption[0].name).isEqualTo("Fridge")
    }

    @Test fun `gas and water sources carry unit override`() {
        val p = prefs(
            """
            {"energy_sources": [
              {"type": "gas", "stat_energy_from": "sensor.gas", "unit_of_measurement": "m3"},
              {"type": "water", "stat_energy_from": "sensor.water"}
            ]}
            """.trimIndent(),
        )
        assertThat(p.sources.first { it.type == "gas" }.unitOfMeasurement).isEqualTo("m3")
        assertThat(p.sources.first { it.type == "water" }.statEnergyFrom).isEqualTo("sensor.water")
    }

    @Test fun `skips malformed source rows`() {
        val p = prefs(
            """{"energy_sources": [{"stat_energy_from": "sensor.a"}, {"type": "solar", "stat_energy_from": "sensor.b"}]}""",
        )
        // The row without a type is dropped; the well-formed solar survives.
        assertThat(p.sources).hasSize(1)
        assertThat(p.sources[0].statEnergyFrom).isEqualTo("sensor.b")
    }

    @Test fun `null payload yields empty prefs`() {
        assertThat(parseEnergyPreferences(null).sources).isEmpty()
        assertThat(parseEnergyPreferences(null).deviceConsumption).isEmpty()
    }

    @Test fun `null name is treated as absent`() {
        val p = prefs("""{"device_consumption": [{"stat_consumption": "sensor.a", "name": null}]}""")
        assertThat(p.deviceConsumption[0].name).isNull()
    }

    @Test fun `parses energy info cost sensors`() {
        val i = info("""{"cost_sensors": {"sensor.grid_in": "sensor.grid_cost", "sensor.x": "sensor.y"}}""")
        assertThat(i.costSensors).containsEntry("sensor.grid_in", "sensor.grid_cost")
        assertThat(i.costSensors).hasSize(2)
    }

    @Test fun `energy info empty when missing`() {
        assertThat(parseEnergyInfo(null).costSensors).isEmpty()
        assertThat(info("""{}""").costSensors).isEmpty()
    }

    @Test fun `parses fossil energy consumption period map`() {
        // FIXTURE-ONLY: HA's FossilEnergyConsumption = Record<string, number>.
        val map = parseFossilEnergyConsumption(
            Json.parseToJsonElement("""{"2026-06-01T00:00:00+00:00": 12.5, "2026-06-02T00:00:00+00:00": 8.0}"""),
        )
        assertThat(map).containsEntry("2026-06-01T00:00:00+00:00", 12.5)
        assertThat(map).hasSize(2)
    }

    @Test fun `fossil consumption empty for non-object`() {
        assertThat(parseFossilEnergyConsumption(null)).isEmpty()
        assertThat(parseFossilEnergyConsumption(Json.parseToJsonElement("""[]"""))).isEmpty()
    }
}
