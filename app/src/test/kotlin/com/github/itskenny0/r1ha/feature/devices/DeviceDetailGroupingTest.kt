package com.github.itskenny0.r1ha.feature.devices

import com.github.itskenny0.r1ha.core.ha.EntityRegistryEntry
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the device drill-in's pure grouping / lookup helpers. The detail
 * view trusts these for which entities belong to a device, how they bucket
 * by domain, and which bucket sorts first; a regression here mislabels or
 * misorders the only data the screen shows.
 */
class DeviceDetailGroupingTest {

    private fun entry(
        entityId: String,
        deviceId: String? = "dev1",
        name: String? = null,
        originalName: String? = null,
    ) = EntityRegistryEntry(
        entityId = entityId,
        name = name,
        originalName = originalName,
        deviceId = deviceId,
        areaId = null,
        platform = null,
        disabledBy = null,
        hiddenBy = null,
    )

    @Test
    fun `domain prefix splits on the first dot`() {
        assertThat(domainOfEntityId("light.kitchen")).isEqualTo("light")
        assertThat(domainOfEntityId("sensor.cpu_load_1m")).isEqualTo("sensor")
    }

    @Test
    fun `domain prefix tolerates a missing dot without throwing`() {
        assertThat(domainOfEntityId("malformed")).isEqualTo("malformed")
    }

    @Test
    fun `domain prefix accepts domains the EntityId value class would reject`() {
        // device_tracker / event aren't modelled by EntityId's whitelist, but
        // the drill-in still lists them, so the helper must not throw.
        assertThat(domainOfEntityId("device_tracker.phone")).isEqualTo("device_tracker")
        assertThat(domainOfEntityId("event.doorbell")).isEqualTo("event")
    }

    @Test
    fun `control domains are recognised, sensors are not`() {
        assertThat(isControlDomain("light")).isTrue()
        assertThat(isControlDomain("switch")).isTrue()
        assertThat(isControlDomain("climate")).isTrue()
        assertThat(isControlDomain("sensor")).isFalse()
        assertThat(isControlDomain("binary_sensor")).isFalse()
        assertThat(isControlDomain("device_tracker")).isFalse()
    }

    @Test
    fun `entitiesForDevice matches only the requested device id`() {
        val all = listOf(
            entry("light.a", deviceId = "dev1"),
            entry("sensor.b", deviceId = "dev2"),
            entry("switch.c", deviceId = "dev1"),
            entry("sensor.d", deviceId = null),
        )
        val mine = entitiesForDevice(all, "dev1")
        assertThat(mine.map { it.entityId }).containsExactly("light.a", "switch.c")
    }

    @Test
    fun `groups put control domains before read-only ones`() {
        val entities = listOf(
            entry("sensor.temp"),
            entry("light.lamp"),
            entry("binary_sensor.motion"),
            entry("switch.relay"),
        )
        val groups = groupEntitiesByDomain(entities)
        // Controls first (alphabetical within block), then read-only.
        assertThat(groups.map { it.domain })
            .containsExactly("light", "switch", "binary_sensor", "sensor")
            .inOrder()
        assertThat(groups.first { it.domain == "light" }.isControl).isTrue()
        assertThat(groups.first { it.domain == "sensor" }.isControl).isFalse()
    }

    @Test
    fun `entities within a domain sort by display name case-insensitively`() {
        val entities = listOf(
            entry("light.z", originalName = "Zebra Lamp"),
            entry("light.a", originalName = "apple lamp"),
            entry("light.m", originalName = "Mango Lamp"),
        )
        val light = groupEntitiesByDomain(entities).single { it.domain == "light" }
        assertThat(light.entities.map { it.displayName })
            .containsExactly("apple lamp", "Mango Lamp", "Zebra Lamp")
            .inOrder()
    }

    @Test
    fun `grouping is empty for a device with no entities`() {
        assertThat(groupEntitiesByDomain(emptyList())).isEmpty()
    }
}
