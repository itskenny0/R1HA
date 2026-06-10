package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AreaCardLogicTest {

    private fun s(
        id: String,
        raw: String,
        deviceClass: String? = null,
        unit: String? = null,
        on: Boolean = false,
        available: Boolean = true,
        attrs: JsonObject = JsonObject(emptyMap()),
    ): EntityState = EntityState(
        id = EntityId(id),
        friendlyName = id,
        area = null,
        isOn = on,
        percent = null,
        raw = null,
        lastChanged = Instant.EPOCH,
        isAvailable = available,
        rawState = raw,
        unit = unit,
        deviceClass = deviceClass,
        attributesJson = attrs,
    )

    // ── sensor summary: median default ─────────────────────────────────────

    @Test fun `default summary is median temperature then humidity`() {
        val states = listOf(
            s("sensor.t1", "20", "temperature", "°C"),
            s("sensor.t2", "22", "temperature", "°C"),
            s("sensor.h1", "55", "humidity", "%"),
        )
        // median(20,22) = 21; humidity = 55.
        assertEquals("21°C · 55%", areaSensorSummary(emptyList(), states))
    }

    @Test fun `power class is summed not medianed`() {
        val states = listOf(
            s("sensor.p1", "100", "power", "W"),
            s("sensor.p2", "250", "power", "W"),
        )
        assertEquals("350W", areaSensorSummary(listOf("power"), states))
    }

    @Test fun `climate current_temperature contributes to temperature`() {
        val states = listOf(
            s(
                "climate.living",
                "heat",
                unit = "°C",
                attrs = buildJsonObject { put("current_temperature", JsonPrimitive(19.0)) },
            ),
        )
        assertEquals("19°C", areaSensorSummary(listOf("temperature"), states))
    }

    @Test fun `humidifier current_humidity contributes to humidity`() {
        val states = listOf(
            s(
                "humidifier.bed",
                "on",
                attrs = buildJsonObject { put("current_humidity", JsonPrimitive(48.0)) },
            ),
        )
        assertEquals("48%", areaSensorSummary(listOf("humidity"), states))
    }

    @Test fun `device dedupe counts one reading per device`() {
        // Two temperature sources on the SAME device should not double-count.
        val states = listOf(
            s("sensor.t_a", "20", "temperature", "°C"),
            s(
                "climate.t_a",
                "heat",
                unit = "°C",
                attrs = buildJsonObject { put("current_temperature", JsonPrimitive(30.0)) },
            ),
        )
        val key: (EntityState) -> String = { "device1" }
        // Only the first-seen source (the sensor, 20) survives dedupe.
        assertEquals("20°C", areaSensorSummary(listOf("temperature"), states, deviceKeyOf = key))
    }

    @Test fun `preferred entity short-circuits the median`() {
        val states = listOf(
            s("sensor.t1", "10", "temperature", "°C"),
            s("sensor.t2", "30", "temperature", "°C"),
            s("sensor.preferred", "25", "temperature", "°C"),
        )
        val out = areaSensorSummary(
            listOf("temperature"),
            states,
            preferredEntity = { cls -> if (cls == "temperature") "sensor.preferred" else null },
        )
        assertEquals("25°C", out)
    }

    @Test fun `empty when nothing readable`() {
        assertNull(areaSensorSummary(emptyList(), emptyList()))
        assertNull(areaSensorSummary(emptyList(), listOf(s("light.a", "on", on = true))))
    }

    // ── alert classes ──────────────────────────────────────────────────────

    @Test fun `default alert classes match common safety sensors`() {
        val motion = s("binary_sensor.m", "on", "motion", on = true)
        assertTrue(isAreaActiveAlert(motion, emptySet()))
        val off = s("binary_sensor.m2", "off", "motion", on = false)
        assertFalse(isAreaActiveAlert(off, emptySet()))
    }

    @Test fun `configured alert classes override the default set`() {
        val door = s("binary_sensor.d", "on", "door", on = true)
        // Only "smoke" configured: a door alert no longer counts.
        assertFalse(isAreaActiveAlert(door, setOf("smoke")))
        val smoke = s("binary_sensor.s", "on", "smoke", on = true)
        assertTrue(isAreaActiveAlert(smoke, setOf("smoke")))
    }

    @Test fun `active alert classes are distinct and sorted`() {
        val states = listOf(
            s("binary_sensor.m1", "on", "motion", on = true),
            s("binary_sensor.m2", "on", "motion", on = true),
            s("binary_sensor.g", "on", "gas", on = true),
            s("binary_sensor.off", "off", "smoke", on = false),
        )
        assertEquals(listOf("gas", "motion"), areaActiveAlertClasses(states, emptySet()))
    }

    @Test fun `non-binary-sensor is never an alert`() {
        val light = s("light.a", "on", on = true)
        assertFalse(isAreaActiveAlert(light, emptySet()))
    }

    @Test fun `areaMemberIsControl picks actionable domains only`() {
        assertTrue(areaMemberIsControl(s("light.a", "on", on = true)))
        assertTrue(areaMemberIsControl(s("cover.c", "open")))
        assertFalse(areaMemberIsControl(s("sensor.x", "5")))
        assertFalse(areaMemberIsControl(s("binary_sensor.b", "on", on = true)))
    }
}
