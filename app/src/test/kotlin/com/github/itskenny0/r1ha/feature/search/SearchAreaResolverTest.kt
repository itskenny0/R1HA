package com.github.itskenny0.r1ha.feature.search

import com.github.itskenny0.r1ha.core.ha.AreaInfo
import com.github.itskenny0.r1ha.core.ha.DeviceInfo
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityRegistryEntry
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.jupiter.api.Test

/**
 * Covers the entity-then-device area precedence the resolver implements. These guard the
 * core promise of area search: typing an area name finds the entities in it, with HA's
 * own resolution rule (explicit entity assignment beats inherited device assignment) and
 * a clean null when nothing resolves rather than a leaked slug.
 */
class SearchAreaResolverTest {

    private fun entity(id: String, area: String? = null): EntityState =
        EntityState(
            id = EntityId(id),
            friendlyName = id,
            area = area,
            isOn = false,
            percent = null,
            raw = null,
            lastChanged = Instant.EPOCH,
            isAvailable = true,
        )

    private fun entry(
        entityId: String,
        deviceId: String? = null,
        areaId: String? = null,
    ): EntityRegistryEntry =
        EntityRegistryEntry(
            entityId = entityId,
            name = null,
            originalName = null,
            deviceId = deviceId,
            areaId = areaId,
            platform = null,
            disabledBy = null,
            hiddenBy = null,
        )

    private fun device(id: String, areaId: String? = null): DeviceInfo =
        DeviceInfo(
            id = id,
            name = id,
            nameByUser = null,
            manufacturer = null,
            model = null,
            areaId = areaId,
            disabledBy = null,
            viaDeviceId = null,
            swVersion = null,
            hwVersion = null,
            configurationUrl = null,
        )

    private val areas = listOf(
        AreaInfo(areaId = "kitchen", name = "Kitchen"),
        AreaInfo(areaId = "bedroom", name = "Bedroom"),
    )

    @Test
    fun `entity with its own areaId resolves to that area name`() {
        val result = SearchAreaResolver.resolveAreas(
            entities = listOf(entity("light.a")),
            areas = areas,
            entityRegistry = listOf(entry("light.a", areaId = "kitchen")),
            devices = emptyList(),
        )
        assertThat(result.single().area).isEqualTo("Kitchen")
    }

    @Test
    fun `entity with no areaId resolves via its device's area`() {
        val result = SearchAreaResolver.resolveAreas(
            entities = listOf(entity("light.b")),
            areas = areas,
            entityRegistry = listOf(entry("light.b", deviceId = "dev1")),
            devices = listOf(device("dev1", areaId = "bedroom")),
        )
        assertThat(result.single().area).isEqualTo("Bedroom")
    }

    @Test
    fun `entity-level areaId wins over the device's areaId`() {
        val result = SearchAreaResolver.resolveAreas(
            entities = listOf(entity("light.c")),
            areas = areas,
            entityRegistry = listOf(entry("light.c", deviceId = "dev1", areaId = "kitchen")),
            devices = listOf(device("dev1", areaId = "bedroom")),
        )
        assertThat(result.single().area).isEqualTo("Kitchen")
    }

    @Test
    fun `entity with neither entity nor device area stays null`() {
        val result = SearchAreaResolver.resolveAreas(
            entities = listOf(entity("light.d")),
            areas = areas,
            entityRegistry = listOf(entry("light.d", deviceId = "dev1")),
            devices = listOf(device("dev1", areaId = null)),
        )
        assertThat(result.single().area).isNull()
    }

    @Test
    fun `unknown area_id not in the registry stays null rather than a raw slug`() {
        val result = SearchAreaResolver.resolveAreas(
            entities = listOf(entity("light.e")),
            areas = areas,
            entityRegistry = listOf(entry("light.e", areaId = "garage")),
            devices = emptyList(),
        )
        assertThat(result.single().area).isNull()
    }

    @Test
    fun `entity absent from the registry keeps its existing area`() {
        // Defensive: an entity with no registry entry at all (e.g. a YAML helper) should
        // pass through untouched rather than being nulled out.
        val result = SearchAreaResolver.resolveAreas(
            entities = listOf(entity("light.f", area = "Pre-set")),
            areas = areas,
            entityRegistry = emptyList(),
            devices = emptyList(),
        )
        assertThat(result.single().area).isEqualTo("Pre-set")
    }
}
