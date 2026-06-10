package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import java.time.Instant

class MapEntitySelectionTest {

    private fun state(
        id: String,
        lat: Double? = null,
        lon: Double? = null,
        source: String? = null,
    ): EntityState = EntityState(
        id = EntityId(id),
        friendlyName = id,
        area = null,
        isOn = false,
        percent = null,
        raw = null,
        lastChanged = Instant.EPOCH,
        isAvailable = true,
        rawState = "home",
        attributesJson = buildJsonObject {
            if (lat != null) put("latitude", JsonPrimitive(lat))
            if (lon != null) put("longitude", JsonPrimitive(lon))
            if (source != null) put("source", JsonPrimitive(source))
        },
    )

    private val states = listOf(
        state("person.alice", lat = 1.0, lon = 2.0),
        state("device_tracker.phone", lat = 3.0, lon = 4.0),
        state("device_tracker.no_gps"), // no coordinates
        state("sensor.temp", lat = 5.0, lon = 6.0), // not a tracker/person
        state("geo_location.quake1", lat = 7.0, lon = 8.0, source = "usgs"),
        state("geo_location.quake2", lat = 9.0, lon = 10.0, source = "nsw_rural_fire"),
        state("zone.home", lat = 0.0, lon = 0.0),
    )

    @Test fun `show_all collects locatable trackers and persons only`() {
        assertThat(showAllEntityIds(states))
            .containsExactly("person.alice", "device_tracker.phone").inOrder()
    }

    @Test fun `geo_location_sources filters by source`() {
        assertThat(geoLocationEntityIds(states, listOf("usgs")))
            .containsExactly("geo_location.quake1")
    }

    @Test fun `geo_location_sources all wildcard plots every geo entity`() {
        assertThat(geoLocationEntityIds(states, listOf("all")))
            .containsExactly("geo_location.quake1", "geo_location.quake2").inOrder()
    }

    @Test fun `empty sources plots nothing`() {
        assertThat(geoLocationEntityIds(states, emptyList())).isEmpty()
    }

    @Test fun `zoneEntityIds collects zones with coordinates`() {
        assertThat(zoneEntityIds(states)).containsExactly("zone.home")
    }
}
