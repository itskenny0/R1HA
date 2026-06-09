package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.AreaInfo
import com.github.itskenny0.r1ha.core.ha.DeviceInfo
import com.github.itskenny0.r1ha.core.ha.EntityRegistryEntry
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure-logic tests for [DashboardNameResolver]. All maps are built by hand so
 * the tests have no IO or Compose dependency and run on the host JVM.
 */
class DashboardNameResolverTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun entry(
        entityId: String,
        deviceId: String? = null,
        areaId: String? = null,
    ) = EntityRegistryEntry(
        entityId = entityId,
        name = null,
        originalName = null,
        deviceId = deviceId,
        areaId = areaId,
        platform = null,
        disabledBy = null,
        hiddenBy = null,
    )

    private fun device(
        id: String,
        name: String? = id,
        nameByUser: String? = null,
        areaId: String? = null,
    ) = DeviceInfo(
        id = id,
        name = name,
        nameByUser = nameByUser,
        manufacturer = null,
        model = null,
        areaId = areaId,
        disabledBy = null,
        viaDeviceId = null,
        swVersion = null,
        hwVersion = null,
        configurationUrl = null,
    )

    private fun area(id: String, name: String, floorId: String? = null) =
        AreaInfo(areaId = id, name = name, floorId = floorId)

    // Fixture: entity light.a -> device dev1 (named "Hue Bridge") -> area kitchen
    //          entity light.b -> no device, direct area_id bedroom
    //          entity light.c -> device dev2 (area kitchen), but entity itself is in bedroom
    //          entity sensor.x -> no device, no area
    private val entities = listOf(
        entry("light.a", deviceId = "dev1"),
        entry("light.b", areaId = "bedroom"),
        entry("light.c", deviceId = "dev2", areaId = "bedroom"),
        entry("sensor.x"),
    )
    private val devices = listOf(
        device("dev1", name = "Hue Bridge", nameByUser = "Living Room Hub", areaId = "kitchen"),
        device("dev2", name = "Plug", areaId = "kitchen"),
    )
    private val areas = listOf(
        area("kitchen", "Kitchen", floorId = "ground"),
        area("bedroom", "Bedroom", floorId = "upper"),
    )

    private val resolver = DashboardNameResolver.from(entities, devices, areas)

    // ── deviceName ───────────────────────────────────────────────────────────

    @Test
    fun `deviceName returns user override name when set`() {
        assertThat(resolver.deviceName("light.a")).isEqualTo("Living Room Hub")
    }

    @Test
    fun `deviceName returns integration name when no user override`() {
        assertThat(resolver.deviceName("light.c")).isEqualTo("Plug")
    }

    @Test
    fun `deviceName returns null when entity has no device`() {
        assertThat(resolver.deviceName("light.b")).isNull()
        assertThat(resolver.deviceName("sensor.x")).isNull()
    }

    @Test
    fun `deviceName returns null for an unknown entity`() {
        assertThat(resolver.deviceName("light.unknown")).isNull()
    }

    // ── areaName ─────────────────────────────────────────────────────────────

    @Test
    fun `areaName resolves via device when entity has no direct area`() {
        assertThat(resolver.areaName("light.a")).isEqualTo("Kitchen")
    }

    @Test
    fun `areaName uses entity direct area_id`() {
        assertThat(resolver.areaName("light.b")).isEqualTo("Bedroom")
    }

    @Test
    fun `entity area_id overrides device area_id`() {
        // light.c: device dev2 is in kitchen, but entity itself is in bedroom
        assertThat(resolver.areaName("light.c")).isEqualTo("Bedroom")
    }

    @Test
    fun `areaName returns null when neither entity nor device has an area`() {
        assertThat(resolver.areaName("sensor.x")).isNull()
    }

    @Test
    fun `areaName returns null for unknown entity`() {
        assertThat(resolver.areaName("sensor.unknown")).isNull()
    }

    // ── floorName ────────────────────────────────────────────────────────────

    @Test
    fun `floorName is null when floorToName is empty (PARTIAL floor support)`() {
        // Floor names are not derivable from registry lists alone; the resolver
        // returns null and callers fall back to area name or friendly_name.
        assertThat(resolver.floorName("light.a")).isNull()
        assertThat(resolver.floorName("light.b")).isNull()
    }

    @Test
    fun `floorName resolves correctly when floorToName is populated`() {
        val resolverWithFloors = DashboardNameResolver(
            entityToDevice = mapOf("light.a" to "dev1"),
            entityToArea = mapOf("light.a" to null),
            deviceToName = mapOf("dev1" to "Hub"),
            deviceToArea = mapOf("dev1" to "kitchen"),
            areaToName = mapOf("kitchen" to "Kitchen"),
            areaToFloor = mapOf("kitchen" to "ground"),
            floorToName = mapOf("ground" to "Ground Floor"),
        )
        assertThat(resolverWithFloors.floorName("light.a")).isEqualTo("Ground Floor")
    }

    // ── resolveParts ─────────────────────────────────────────────────────────

    @Test
    fun `resolveParts for device returns device name`() {
        assertThat(resolver.resolveParts("device", "light.a")).isEqualTo("Living Room Hub")
    }

    @Test
    fun `resolveParts for area returns area name`() {
        assertThat(resolver.resolveParts("area", "light.b")).isEqualTo("Bedroom")
    }

    @Test
    fun `resolveParts for entity returns null so caller keeps friendly_name`() {
        assertThat(resolver.resolveParts("entity", "light.a")).isNull()
    }

    @Test
    fun `resolveParts joins multiple non-null parts with a space`() {
        assertThat(resolver.resolveParts("device area", "light.a")).isEqualTo("Living Room Hub Kitchen")
    }

    @Test
    fun `resolveParts comma-separated tokens also work`() {
        assertThat(resolver.resolveParts("device,area", "light.a")).isEqualTo("Living Room Hub Kitchen")
    }

    @Test
    fun `resolveParts skips entity token but returns other parts`() {
        assertThat(resolver.resolveParts("entity area", "light.b")).isEqualTo("Bedroom")
    }

    @Test
    fun `resolveParts returns null when no part resolves`() {
        assertThat(resolver.resolveParts("device", "sensor.x")).isNull()
    }

    // ── empty resolver (no-regression fallback) ──────────────────────────────

    @Test
    fun `EMPTY resolver returns null for every lookup`() {
        val empty = DashboardNameResolver.EMPTY
        assertThat(empty.deviceName("light.a")).isNull()
        assertThat(empty.areaName("light.a")).isNull()
        assertThat(empty.floorName("light.a")).isNull()
        assertThat(empty.resolveParts("device", "light.a")).isNull()
        assertThat(empty.resolveParts("area", "light.b")).isNull()
    }
}
