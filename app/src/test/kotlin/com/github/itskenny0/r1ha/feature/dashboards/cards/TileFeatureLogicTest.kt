package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TileFeatureLogicTest {

    private fun entity(
        id: String,
        rawState: String = "on",
        deviceClass: String? = null,
        supportedColorModes: List<String> = emptyList(),
        vacuumSupportedFeatures: Int = 0,
        supportedFeatures: Int = 0,
        minColorTempK: Int? = null,
        maxColorTempK: Int? = null,
        attrs: JsonObject? = null,
    ): EntityState = EntityState(
        id = EntityId(id),
        friendlyName = id,
        area = null,
        isOn = rawState == "on" || rawState == "open",
        percent = null,
        raw = null,
        lastChanged = Instant.EPOCH,
        isAvailable = rawState != "unavailable",
        rawState = rawState,
        deviceClass = deviceClass,
        supportedColorModes = supportedColorModes,
        vacuumSupportedFeatures = vacuumSupportedFeatures,
        supportedFeatures = supportedFeatures,
        minColorTempK = minColorTempK,
        maxColorTempK = maxColorTempK,
        attributesJson = attrs,
    )

    private fun coverAttrs(supportedFeatures: Int, position: Int? = null, assumed: Boolean? = null): JsonObject =
        buildJsonObject {
            put("supported_features", JsonPrimitive(supportedFeatures))
            if (position != null) put("current_position", JsonPrimitive(position))
            if (assumed != null) put("assumed_state", JsonPrimitive(assumed))
        }

    // ── Favorite positions ──────────────────────────────────────────────────

    @Test fun `favorite positions fall back to HA defaults when registry has none`() {
        assertEquals(listOf(0, 25, 75, 100), resolveFavoritePositions(emptyList(), hasRegistryList = false))
    }

    @Test fun `favorite positions use the registry list when present even if empty`() {
        // HA renders nothing for an explicit empty list rather than defaults.
        assertEquals(emptyList<Int>(), resolveFavoritePositions(emptyList(), hasRegistryList = true))
        assertEquals(listOf(10, 50), resolveFavoritePositions(listOf(10, 50), hasRegistryList = true))
    }

    @Test fun `normalizeFavoritePositions clamps dedupes and preserves order`() {
        val arr = JsonArray(
            listOf(150, -5, 25, 25, 50).map { JsonPrimitive(it) },
        )
        assertEquals(
            listOf(100, 0, 25, 50),
            com.github.itskenny0.r1ha.core.ha.ExtEntityRegistryOptions.normalizeFavoritePositions(arr),
        )
    }

    // ── Cover open/close gating ─────────────────────────────────────────────

    @Test fun `cover open-close shows STOP only with explicit STOP bit`() {
        val withStop = entity("cover.a", attrs = coverAttrs(1 or 2 or 8))
        assertTrue(coverOpenCloseGate(withStop).showStop)
        val noStop = entity("cover.b", attrs = coverAttrs(1 or 2))
        assertFalse(coverOpenCloseGate(noStop).showStop)
        // An omitted bitmask forgives open/close but never shows STOP.
        val noMask = entity("cover.c", attrs = coverAttrs(0))
        assertFalse(coverOpenCloseGate(noMask).showStop)
        assertTrue(coverOpenCloseGate(noMask).showOpen)
        assertTrue(coverOpenCloseGate(noMask).showClose)
    }

    @Test fun `cover fully open disables open and enables close`() {
        val gate = coverOpenCloseGate(entity("cover.a", rawState = "open", attrs = coverAttrs(1 or 2, position = 100)))
        assertFalse(gate.canOpen)
        assertTrue(gate.canClose)
    }

    @Test fun `cover fully closed disables close and enables open`() {
        val gate = coverOpenCloseGate(entity("cover.a", rawState = "closed", attrs = coverAttrs(1 or 2, position = 0)))
        assertTrue(gate.canOpen)
        assertFalse(gate.canClose)
    }

    @Test fun `assumed-state cover keeps both directions enabled at limits`() {
        val gate = coverOpenCloseGate(entity("cover.a", rawState = "open", attrs = coverAttrs(1 or 2, position = 100, assumed = true)))
        assertTrue(gate.canOpen)
        assertTrue(gate.canClose)
    }

    @Test fun `unavailable cover disables every button`() {
        val gate = coverOpenCloseGate(entity("cover.a", rawState = "unavailable", attrs = coverAttrs(1 or 2 or 8)))
        assertFalse(gate.canOpen)
        assertFalse(gate.canClose)
        assertFalse(gate.canStop)
    }

    // ── Vacuum commands ─────────────────────────────────────────────────────

    @Test fun `vacuum honours start_pause and return_home config keys`() {
        // PAUSE + STOP + RETURN_HOME features.
        val v = entity("vacuum.x", rawState = "cleaning", vacuumSupportedFeatures = 4 or 8 or 16 or 4096 or 8192)
        val keys = vacuumVisibleCommands(v, listOf("start_pause", "return_home"))
        assertEquals(listOf("start_pause", "return_home"), keys)
    }

    @Test fun `vacuum start_pause is PAUSE while cleaning and START otherwise`() {
        val cleaning = entity("vacuum.x", rawState = "cleaning", vacuumSupportedFeatures = 4 or 8192 or 4096)
        assertEquals("pause", vacuumButtonFor(cleaning, "start_pause").service)
        val docked = entity("vacuum.x", rawState = "docked", vacuumSupportedFeatures = 4 or 8192 or 4096)
        val startBtn = vacuumButtonFor(docked, "start_pause")
        assertEquals("start", startBtn.service)
        assertTrue(startBtn.enabled)
    }

    @Test fun `vacuum start disabled while already cleaning when no pause support`() {
        val cleaning = entity("vacuum.x", rawState = "cleaning", vacuumSupportedFeatures = 8192 or 4096)
        val btn = vacuumButtonFor(cleaning, "start_pause")
        assertEquals("start", btn.service)
        assertFalse(btn.enabled)
    }

    @Test fun `vacuum legacy pause-only falls back to start_pause service`() {
        val legacy = entity("vacuum.x", rawState = "cleaning", vacuumSupportedFeatures = 4)
        assertEquals("start_pause", vacuumButtonFor(legacy, "start_pause").service)
    }

    @Test fun `vacuum return_home disabled while returning`() {
        val returning = entity("vacuum.x", rawState = "returning", vacuumSupportedFeatures = 16)
        assertFalse(vacuumButtonFor(returning, "return_home").enabled)
    }

    @Test fun `vacuum stop disabled when docked`() {
        val docked = entity("vacuum.x", rawState = "docked", vacuumSupportedFeatures = 8)
        assertFalse(vacuumButtonFor(docked, "stop").enabled)
    }

    @Test fun `vacuum with no config shows first three supported keys`() {
        val v = entity("vacuum.x", rawState = "docked", vacuumSupportedFeatures = 4 or 8 or 512 or 1024 or 16 or 4096 or 8192)
        val keys = vacuumVisibleCommands(v, emptyList())
        assertEquals(3, keys.size)
        assertEquals(listOf("start_pause", "stop", "clean_spot"), keys)
    }

    // ── Lock gating ─────────────────────────────────────────────────────────

    @Test fun `lock canLock false when already locked`() {
        assertFalse(lockCanLock(entity("lock.a", rawState = "locked")))
        assertTrue(lockCanLock(entity("lock.a", rawState = "unlocked")))
    }

    @Test fun `lock canUnlock false when already unlocked or in motion`() {
        assertFalse(lockCanUnlock(entity("lock.a", rawState = "unlocked")))
        assertFalse(lockCanUnlock(entity("lock.a", rawState = "unlocking")))
        assertTrue(lockCanUnlock(entity("lock.a", rawState = "locked")))
    }

    @Test fun `lock canOpen false when open or in motion or unavailable`() {
        assertFalse(lockCanOpen(entity("lock.a", rawState = "open")))
        assertFalse(lockCanOpen(entity("lock.a", rawState = "opening")))
        assertFalse(lockCanOpen(entity("lock.a", rawState = "unavailable")))
        assertTrue(lockCanOpen(entity("lock.a", rawState = "locked")))
    }

    @Test fun `lock supports open reads the OPEN bit`() {
        assertTrue(lockSupportsOpen(entity("lock.a", attrs = buildJsonObject { put("supported_features", JsonPrimitive(1)) })))
        assertFalse(lockSupportsOpen(entity("lock.a", attrs = buildJsonObject { put("supported_features", JsonPrimitive(0)) })))
    }

    // ── Area controls ───────────────────────────────────────────────────────

    @Test fun `area control domain and device-class parsing`() {
        assertEquals("cover", areaControlDomain("cover-shutter"))
        assertEquals("shutter", areaControlDeviceClass("cover-shutter"))
        assertEquals("light", areaControlDomain("light"))
        assertNull(areaControlDeviceClass("light"))
    }

    @Test fun `area control entities filter by domain device-class and exclude`() {
        val members = listOf(
            entity("light.a"),
            entity("light.b"),
            entity("cover.shut", deviceClass = "shutter"),
            entity("cover.blind", deviceClass = "blind"),
            entity("switch.s"),
        )
        val lights = areaControlEntities("light", members, setOf("light.b"))
        assertEquals(listOf("light.a"), lights.map { it.id.value })
        val shutters = areaControlEntities("cover-shutter", members, emptySet())
        assertEquals(listOf("cover.shut"), shutters.map { it.id.value })
    }

    @Test fun `area controls default set is capped at four and drops empty groups`() {
        // Only a light present: the default 13-token list collapses to one group.
        val members = listOf(entity("light.a"))
        val resolved = resolveAreaControls(emptyList(), members, emptySet())
        assertEquals(1, resolved.size)
        assertTrue(resolved.first() is AreaControl.DomainGroup)
    }

    @Test fun `area controls explicit config keeps order and entity controls`() {
        val members = listOf(entity("light.a"), entity("switch.s"), entity("input_boolean.flag"))
        val resolved = resolveAreaControls(listOf("switch", "input_boolean.flag", "light"), members, emptySet())
        assertEquals(3, resolved.size)
        assertTrue(resolved[0] is AreaControl.DomainGroup)
        assertTrue(resolved[1] is AreaControl.Entity)
        assertEquals("input_boolean.flag", (resolved[1] as AreaControl.Entity).entityId)
    }

    @Test fun `area group active and toggle service follow group state`() {
        val onLights = listOf(entity("light.a", rawState = "on"), entity("light.b", rawState = "off"))
        assertTrue(areaGroupIsActive("light", onLights))
        assertEquals("light.turn_off", areaToggleService("light", onLights))
        val offLights = listOf(entity("light.a", rawState = "off"))
        assertFalse(areaGroupIsActive("light", offLights))
        assertEquals("light.turn_on", areaToggleService("light", offLights))
    }

    @Test fun `area cover group stops while moving and opens when closed`() {
        val moving = listOf(entity("cover.a", rawState = "opening"))
        assertEquals("cover.stop_cover", areaToggleService("cover-shutter", moving))
        val closed = listOf(entity("cover.a", rawState = "closed"))
        assertEquals("cover.open_cover", areaToggleService("cover-shutter", closed))
        val open = listOf(entity("cover.a", rawState = "open"))
        assertEquals("cover.close_cover", areaToggleService("cover-shutter", open))
    }

    // ── Light favorite colours ──────────────────────────────────────────────

    @Test fun `light supports favorite colours for colour or color_temp`() {
        assertTrue(lightSupportsFavoriteColors(entity("light.a", supportedColorModes = listOf("rgb"))))
        assertTrue(lightSupportsFavoriteColors(entity("light.a", supportedColorModes = listOf("color_temp"))))
        assertFalse(lightSupportsFavoriteColors(entity("light.a", supportedColorModes = listOf("onoff"))))
    }

    @Test fun `light supports brightness only for brightness-capable modes`() {
        assertTrue(lightSupportsBrightness(entity("light.a", supportedColorModes = listOf("brightness"))))
        assertTrue(lightSupportsBrightness(entity("light.a", supportedColorModes = listOf("rgb"))))
        assertFalse(lightSupportsBrightness(entity("light.a", supportedColorModes = listOf("onoff"))))
    }

    @Test fun `default favorite colours include temp swatches and coloured swatches`() {
        val ct = entity("light.a", supportedColorModes = listOf("color_temp", "rgb"), minColorTempK = 2000, maxColorTempK = 6500)
        val colors = computeDefaultFavoriteColors(ct)
        // 4 color-temp swatches + 4 default coloured swatches.
        assertEquals(8, colors.size)
        assertTrue(colors.first().containsKey("color_temp_kelvin"))
    }

    @Test fun `favorite colour swatch reads rgb and converts color_temp`() {
        val rgb = buildJsonObject {
            put("rgb_color", JsonArray(listOf(255, 0, 0).map { JsonPrimitive(it) }))
        }
        assertEquals(0xFFFF0000L, favoriteColorSwatchArgb(rgb))
        val ct = buildJsonObject { put("color_temp_kelvin", JsonPrimitive(4000)) }
        // A kelvin swatch yields some opaque colour (exact value is an approximation).
        assertTrue((favoriteColorSwatchArgb(ct) ?: 0L) and 0xFF000000L == 0xFF000000L)
    }

    // ── Lawn-mower commands ─────────────────────────────────────────────────

    @Test fun `lawn-mower start_pause is PAUSE while mowing and START otherwise`() {
        val f = EntityState.LawnMowerFeature
        val mowing = entity("lawn_mower.x", rawState = "mowing",
            supportedFeatures = f.PAUSE or f.START_MOWING)
        assertEquals("pause", lawnMowerButtonFor(mowing, "start_pause").service)
        val docked = entity("lawn_mower.x", rawState = "docked",
            supportedFeatures = f.PAUSE or f.START_MOWING)
        val startBtn = lawnMowerButtonFor(docked, "start_pause")
        assertEquals("start_mowing", startBtn.service)
        assertTrue(startBtn.enabled)
    }

    @Test fun `lawn-mower start disabled while mowing when PAUSE is not supported`() {
        val f = EntityState.LawnMowerFeature
        val mowing = entity("lawn_mower.x", rawState = "mowing",
            supportedFeatures = f.START_MOWING)
        val btn = lawnMowerButtonFor(mowing, "start_pause")
        assertEquals("start_mowing", btn.service)
        assertFalse(btn.enabled)
    }

    @Test fun `lawn-mower dock disabled when already docked or idle`() {
        val f = EntityState.LawnMowerFeature
        assertFalse(lawnMowerButtonFor(entity("lawn_mower.x", rawState = "docked", supportedFeatures = f.DOCK), "dock").enabled)
        assertFalse(lawnMowerButtonFor(entity("lawn_mower.x", rawState = "idle", supportedFeatures = f.DOCK), "dock").enabled)
        assertTrue(lawnMowerButtonFor(entity("lawn_mower.x", rawState = "mowing", supportedFeatures = f.DOCK), "dock").enabled)
    }

    @Test fun `lawn-mower visible commands respects config filter`() {
        val f = EntityState.LawnMowerFeature
        val all = entity("lawn_mower.x", supportedFeatures = f.START_MOWING or f.PAUSE or f.DOCK)
        // Empty config = all supported.
        assertEquals(listOf("start_pause", "dock"), lawnMowerVisibleCommands(all, emptyList()))
        // Filtered to dock only.
        assertEquals(listOf("dock"), lawnMowerVisibleCommands(all, listOf("dock")))
        // Unknown key in config is ignored.
        assertEquals(listOf("start_pause"), lawnMowerVisibleCommands(all, listOf("start_pause", "unknown")))
    }

    @Test fun `lawn-mower with no dock support omits dock button`() {
        val f = EntityState.LawnMowerFeature
        val mower = entity("lawn_mower.x", supportedFeatures = f.START_MOWING or f.PAUSE)
        assertEquals(listOf("start_pause"), lawnMowerVisibleCommands(mower, emptyList()))
    }

    // ── trend-graph downsampling (detail: false) ────────────────────────────

    @Test fun `detail true returns points unchanged`() {
        val pts = listOf(
            Instant.ofEpochSecond(0) to 1.0,
            Instant.ofEpochSecond(100) to 2.0,
        )
        assertEquals(pts, downsampleTrendPoints(pts, detail = true))
    }

    @Test fun `detail false averages per hour bucket`() {
        val pts = listOf(
            // hour 0: 0s, 1800s -> mean value 2.0 at 900s
            Instant.ofEpochSecond(0) to 1.0,
            Instant.ofEpochSecond(1800) to 3.0,
            // hour 1: 3600s -> 10.0 at 3600s
            Instant.ofEpochSecond(3600) to 10.0,
        )
        val out = downsampleTrendPoints(pts, detail = false)
        assertEquals(2, out.size)
        assertEquals(2.0, out[0].second, 0.0)
        assertEquals(900L, out[0].first.epochSecond)
        assertEquals(10.0, out[1].second, 0.0)
    }

    @Test fun `detail false leaves a single point alone`() {
        val pts = listOf(Instant.ofEpochSecond(0) to 5.0)
        assertEquals(pts, downsampleTrendPoints(pts, detail = false))
    }
}
