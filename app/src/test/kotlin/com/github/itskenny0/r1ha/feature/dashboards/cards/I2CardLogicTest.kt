package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.AreaInfo
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityRegistryEntry
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.LogbookEntry
import com.github.itskenny0.r1ha.core.lovelace.LogbookTarget
import com.github.itskenny0.r1ha.core.lovelace.MapMarkerConfig
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Pure decision-logic tests for Batch I2 cards (light, map, logbook). Each
 * function under test is Compose/Android-free (uses only the [androidx.compose.ui.graphics.Color]
 * value type), so these run under plain JUnit5.
 */
class I2CardLogicTest {

    private fun state(
        id: String,
        raw: String = "on",
        on: Boolean = true,
        attrs: JsonObject = JsonObject(emptyMap()),
        colorModes: List<String> = emptyList(),
        percent: Int? = null,
    ): EntityState = EntityState(
        id = EntityId(id),
        friendlyName = id,
        area = null,
        isOn = on,
        percent = percent,
        raw = null,
        lastChanged = Instant.EPOCH,
        isAvailable = true,
        rawState = raw,
        attributesJson = attrs,
        supportedColorModes = colorModes,
    )

    // ── LightCardLogic ──────────────────────────────────────────────────────

    @Test fun `light brightness supported only for non-onoff color modes`() {
        assertThat(lightSupportsBrightness(state("light.a", colorModes = listOf("onoff")))).isFalse()
        assertThat(lightSupportsBrightness(state("light.a", colorModes = emptyList()))).isFalse()
        assertThat(lightSupportsBrightness(null)).isFalse()
        assertThat(lightSupportsBrightness(state("light.a", colorModes = listOf("brightness")))).isTrue()
        assertThat(
            lightSupportsBrightness(state("light.a", colorModes = listOf("onoff", "brightness"))),
        ).isTrue()
    }

    @Test fun `next brightness pct steps and clamps into 1 to 100`() {
        assertThat(nextBrightnessPct(50, up = true)).isEqualTo(60)
        assertThat(nextBrightnessPct(50, up = false)).isEqualTo(40)
        // Floor is 1, not 0 (0 would be the toggle's job).
        assertThat(nextBrightnessPct(5, up = false)).isEqualTo(1)
        assertThat(nextBrightnessPct(0, up = false)).isEqualTo(1)
        assertThat(nextBrightnessPct(95, up = true)).isEqualTo(100)
        assertThat(nextBrightnessPct(100, up = true)).isEqualTo(100)
    }

    @Test fun `light icon tint takes rgb_color when on else falls back`() {
        val red = state(
            "light.a",
            on = true,
            attrs = buildJsonObject {
                put("rgb_color", buildJsonArray {
                    add(JsonPrimitive(255)); add(JsonPrimitive(0)); add(JsonPrimitive(0))
                })
            },
        )
        val tint = lightIconTint(red, onAccent = androidx.compose.ui.graphics.Color.Green, offAccent = androidx.compose.ui.graphics.Color.Gray)
        assertThat(tint.red).isWithin(0.01f).of(1f)
        assertThat(tint.green).isWithin(0.01f).of(0f)
        assertThat(tint.blue).isWithin(0.01f).of(0f)
        // Off ignores rgb and uses the off accent.
        val off = lightIconTint(state("light.a", on = false), androidx.compose.ui.graphics.Color.Green, androidx.compose.ui.graphics.Color.Gray)
        assertThat(off).isEqualTo(androidx.compose.ui.graphics.Color.Gray)
        // On without rgb uses the on accent.
        val plainOn = lightIconTint(state("light.a", on = true), androidx.compose.ui.graphics.Color.Green, androidx.compose.ui.graphics.Color.Gray)
        assertThat(plainOn).isEqualTo(androidx.compose.ui.graphics.Color.Green)
    }

    @Test fun `light unavailable detection`() {
        assertThat(lightIsUnavailable(state("light.a", raw = "unavailable"))).isTrue()
        assertThat(lightIsUnavailable(state("light.a", raw = "unknown"))).isTrue()
        assertThat(lightIsUnavailable(null)).isTrue()
        assertThat(lightIsUnavailable(state("light.a", raw = "on"))).isFalse()
    }

    // ── MapCardLogic ────────────────────────────────────────────────────────

    @Test fun `marker colors assign palette by index skipping explicit colors`() {
        val markers = listOf(
            MapMarkerConfig(entityId = "a"),
            MapMarkerConfig(entityId = "b", color = "#112233"),
            MapMarkerConfig(entityId = "c"),
        )
        val colors = assignMarkerColors(markers)
        assertThat(colors).hasSize(3)
        // a gets palette[0], c gets palette[1] (b consumed no palette slot).
        assertThat(colors[0]).isEqualTo(MAP_MARKER_PALETTE[0])
        assertThat(colors[2]).isEqualTo(MAP_MARKER_PALETTE[1])
        // b honours its explicit hex.
        assertThat(colors[1].red).isWithin(0.01f).of(0x11 / 255f)
    }

    @Test fun `marker palette wraps when more markers than palette colors`() {
        val markers = (0 until MAP_MARKER_PALETTE.size + 1).map { MapMarkerConfig(entityId = "e$it") }
        val colors = assignMarkerColors(markers)
        assertThat(colors.last()).isEqualTo(MAP_MARKER_PALETTE[0])
    }

    @Test fun `marker label honours name state and attribute modes`() {
        val s = state(
            "device_tracker.phone",
            raw = "home",
            attrs = buildJsonObject {
                put("battery_level", JsonPrimitive("84"))
                put("unit_of_measurement", JsonPrimitive("%"))
            },
        )
        assertThat(markerLabel(null, null, s, "Phone")).isEqualTo("Phone")
        assertThat(markerLabel("name", null, s, "Phone")).isEqualTo("Phone")
        assertThat(markerLabel("state", null, s, "Phone")).isEqualTo("home")
        assertThat(markerLabel("attribute", "battery_level", s, "Phone")).isEqualTo("84 %")
        // Missing attribute falls back to the name.
        assertThat(markerLabel("attribute", "nope", s, "Phone")).isEqualTo("Phone")
    }

    @Test fun `effective label mode and attribute prefer per-marker override`() {
        assertThat(effectiveLabelMode("name", "state")).isEqualTo("state")
        assertThat(effectiveLabelMode("name", null)).isEqualTo("name")
        assertThat(effectiveLabelAttribute("a", "b")).isEqualTo("b")
        assertThat(effectiveLabelAttribute("a", null)).isEqualTo("a")
    }

    // ── LogbookCardLogic ────────────────────────────────────────────────────

    private fun reg(id: String, deviceId: String? = null, areaId: String? = null) =
        EntityRegistryEntry(
            entityId = id,
            name = null,
            originalName = null,
            deviceId = deviceId,
            areaId = areaId,
            platform = null,
            disabledBy = null,
            hiddenBy = null,
        )

    @Test fun `target resolves area device and floor while label is unresolvable`() {
        val registry = listOf(
            reg("light.kitchen", areaId = "kitchen"),
            reg("switch.hall", deviceId = "dev1"),
            reg("sensor.bedroom", areaId = "bedroom"),
            reg("light.via_device", deviceId = "dev2"),
        )
        val areas = listOf(
            AreaInfo(areaId = "kitchen", name = "Kitchen", floorId = "ground"),
            AreaInfo(areaId = "bedroom", name = "Bedroom", floorId = "upstairs"),
        )
        // dev2 lives in the kitchen area via the device registry.
        val deviceAreas = mapOf("dev2" to "kitchen")

        val areaTarget = resolveLogbookTarget(LogbookTarget(areaIds = listOf("kitchen")), registry, areas, deviceAreas)
        assertThat(areaTarget).containsExactly("light.kitchen", "light.via_device")

        val deviceTarget = resolveLogbookTarget(LogbookTarget(deviceIds = listOf("dev1")), registry, areas, deviceAreas)
        assertThat(deviceTarget).containsExactly("switch.hall")

        val floorTarget = resolveLogbookTarget(LogbookTarget(floorIds = listOf("upstairs")), registry, areas, deviceAreas)
        assertThat(floorTarget).containsExactly("sensor.bedroom")

        // Label targets can't resolve and flag the unresolvable note.
        assertThat(hasUnresolvableTarget(LogbookTarget(labelIds = listOf("favourites")))).isTrue()
        assertThat(hasUnresolvableTarget(LogbookTarget(areaIds = listOf("kitchen")))).isFalse()
    }

    private fun entry(id: String?, ts: Instant, st: String? = null) = LogbookEntry(
        timestamp = ts,
        name = id ?: "x",
        message = "changed",
        entityId = id?.let { EntityId(it) },
        domain = null,
        state = st,
    )

    @Test fun `filter scopes to entity set and state filter newest first`() {
        val t0 = Instant.ofEpochSecond(100)
        val t1 = Instant.ofEpochSecond(200)
        val t2 = Instant.ofEpochSecond(300)
        val entries = listOf(
            entry("light.a", t0, st = "on"),
            entry("switch.b", t1, st = "off"),
            entry("light.a", t2, st = "off"),
        )
        // Scope to light.a, newest first.
        val scoped = filterLogbookEntries(entries, setOf("light.a"), emptyList())
        assertThat(scoped.map { it.timestamp }).containsExactly(t2, t0).inOrder()
        // State filter "on" keeps only the on entry.
        val onlyOn = filterLogbookEntries(entries, emptySet(), listOf("ON"))
        assertThat(onlyOn).hasSize(1)
        assertThat(onlyOn[0].entityId?.value).isEqualTo("light.a")
        // Empty filters keep everything.
        assertThat(filterLogbookEntries(entries, emptySet(), emptyList())).hasSize(3)
    }

    @Test fun `logbook not loaded detection matches integration absence shapes`() {
        assertThat(isLogbookNotLoaded(RuntimeException("Logbook integration not loaded"))).isTrue()
        assertThat(isLogbookNotLoaded(RuntimeException("HTTP 404 for /api/logbook"))).isTrue()
        assertThat(isLogbookNotLoaded(RuntimeException("Integration not found: recorder"))).isTrue()
        assertThat(isLogbookNotLoaded(RuntimeException("connection reset"))).isFalse()
        assertThat(isLogbookNotLoaded(null)).isFalse()
    }
}
