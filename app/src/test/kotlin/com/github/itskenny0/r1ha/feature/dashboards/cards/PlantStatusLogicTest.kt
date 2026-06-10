package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import java.time.Instant

class PlantStatusLogicTest {

    private fun plant(
        rawState: String,
        moisture: String? = null,
        temperature: String? = null,
        battery: String? = null,
        problem: String? = "none",
        units: Map<String, String> = emptyMap(),
        sensors: Map<String, String> = emptyMap(),
    ): EntityState = EntityState(
        id = EntityId("plant.fern"),
        friendlyName = "Fern",
        area = null,
        isOn = false,
        percent = null,
        raw = null,
        lastChanged = Instant.EPOCH,
        isAvailable = true,
        rawState = rawState,
        attributesJson = buildJsonObject {
            if (moisture != null) put("moisture", JsonPrimitive(moisture))
            if (temperature != null) put("temperature", JsonPrimitive(temperature))
            if (battery != null) put("battery", JsonPrimitive(battery))
            if (problem != null) put("problem", JsonPrimitive(problem))
            if (units.isNotEmpty()) put("unit_of_measurement_dict", buildJsonObject {
                units.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            })
            if (sensors.isNotEmpty()) put("sensors", buildJsonObject {
                sensors.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            })
        },
    )

    @Test fun `readouts resolve only reported attributes in display order`() {
        val s = plant(
            rawState = "ok",
            moisture = "42",
            battery = "88",
            units = mapOf("moisture" to "%", "battery" to "%"),
            sensors = mapOf("moisture" to "sensor.fern_moisture"),
        )
        val readouts = plantReadouts(s)
        assertThat(readouts.map { it.attribute }).containsExactly("moisture", "battery").inOrder()
        val moisture = readouts.first { it.attribute == "moisture" }
        assertThat(moisture.value).isEqualTo("42")
        assertThat(moisture.unit).isEqualTo("%")
        assertThat(moisture.backingEntity).isEqualTo("sensor.fern_moisture")
        assertThat(moisture.isProblem).isFalse()
    }

    @Test fun `problem attribute flags the named readouts`() {
        val s = plant(
            rawState = "problem",
            moisture = "5",
            temperature = "30",
            problem = "moisture low, temperature high",
        )
        val readouts = plantReadouts(s)
        assertThat(readouts.first { it.attribute == "moisture" }.isProblem).isTrue()
        assertThat(readouts.first { it.attribute == "temperature" }.isProblem).isTrue()
    }

    @Test fun `problem none flags nothing`() {
        assertThat(plantProblemTokens("none")).isEmpty()
        assertThat(plantProblemTokens("")).isEmpty()
        assertThat(plantProblemTokens(null)).isEmpty()
    }

    @Test fun `plantHasProblem reads state and problem attribute`() {
        assertThat(plantHasProblem(plant(rawState = "problem"))).isTrue()
        assertThat(plantHasProblem(plant(rawState = "ok", problem = "moisture low"))).isTrue()
        assertThat(plantHasProblem(plant(rawState = "ok", problem = "none"))).isFalse()
    }
}
