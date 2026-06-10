package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class GaugeValueTest {

    private fun state(
        rawState: String?,
        raw: Number? = null,
        available: Boolean = true,
        attrs: JsonObject = JsonObject(emptyMap()),
    ): EntityState = EntityState(
        id = EntityId("sensor.g"),
        friendlyName = "G",
        area = null,
        isOn = false,
        percent = null,
        raw = raw,
        lastChanged = Instant.EPOCH,
        isAvailable = available,
        rawState = rawState,
        attributesJson = attrs,
    )

    // ── gaugeNumericValue: attribute vs state ──────────────────────────────

    @Test fun `state numeric is gauged when no attribute set`() {
        assertEquals(42.0, gaugeNumericValue(null, state("42")))
    }

    @Test fun `attribute value is gauged when configured`() {
        val s = state("heat", attrs = buildJsonObject { put("current_temperature", JsonPrimitive(21.5)) })
        assertEquals(21.5, gaugeNumericValue("current_temperature", s))
    }

    @Test fun `non-numeric attribute yields null`() {
        val s = state("heat", attrs = buildJsonObject { put("mode", JsonPrimitive("auto")) })
        assertNull(gaugeNumericValue("mode", s))
    }

    @Test fun `non-numeric state yields null`() {
        assertNull(gaugeNumericValue(null, state("idle")))
    }

    @Test fun `missing entity yields null`() {
        assertNull(gaugeNumericValue(null, null))
        assertNull(gaugeNumericValue("x", null))
    }

    // ── gaugeWarning ───────────────────────────────────────────────────────

    @Test fun `no warning when value present`() {
        assertNull(gaugeWarning(12.0, unavailable = false))
    }

    @Test fun `unavailable entity warns unavailable`() {
        assertEquals("Entity is unavailable", gaugeWarning(null, unavailable = true))
    }

    @Test fun `present non-numeric warns non-numeric`() {
        assertEquals("Entity is non-numeric", gaugeWarning(null, unavailable = false))
    }
}
