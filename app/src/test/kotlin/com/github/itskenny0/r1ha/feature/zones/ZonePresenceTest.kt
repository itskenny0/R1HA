package com.github.itskenny0.r1ha.feature.zones

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ZonePresenceTest {

    private fun zone(
        id: String,
        name: String,
        lat: Double? = null,
        lon: Double? = null,
        radius: Double? = null,
        icon: String? = null,
        isHome: Boolean = false,
    ) = ZoneInput(id, name, lat, lon, radius, icon, isHome)

    private fun tracker(
        id: String,
        name: String,
        state: String,
        lat: Double? = null,
        lon: Double? = null,
    ) = TrackedInput(id, name, state, lat, lon)

    @Test fun `tracker matched to zone by exact friendly name`() {
        val res = resolveZoneMembership(
            zones = listOf(zone("zone.work", "Work")),
            trackers = listOf(tracker("person.a", "Alice", "Work")),
        )
        val work = res.zones.single { it.name == "Work" }
        assertThat(work.occupants).containsExactly("Alice")
        assertThat(res.outside).isEmpty()
    }

    @Test fun `zone matching is case insensitive`() {
        val res = resolveZoneMembership(
            zones = listOf(zone("zone.work", "Work")),
            trackers = listOf(tracker("person.a", "Alice", "work")),
        )
        assertThat(res.zones.single().occupants).containsExactly("Alice")
    }

    @Test fun `home state attaches to the home zone only`() {
        val res = resolveZoneMembership(
            zones = listOf(
                zone("zone.home", "Home", isHome = true),
                zone("zone.work", "Work"),
            ),
            trackers = listOf(tracker("person.a", "Alice", "home")),
        )
        assertThat(res.zones.single { it.name == "Home" }.occupants)
            .containsExactly("Alice")
        assertThat(res.zones.single { it.name == "Work" }.occupants).isEmpty()
    }

    @Test fun `home state with no home zone leaves nobody placed`() {
        val res = resolveZoneMembership(
            zones = listOf(zone("zone.work", "Work")),
            trackers = listOf(tracker("person.a", "Alice", "home")),
        )
        assertThat(res.zones.single().occupants).isEmpty()
        assertThat(res.outside).isEmpty()
    }

    @Test fun `home zone combines named matches and home-state trackers without duplicates`() {
        val res = resolveZoneMembership(
            zones = listOf(zone("zone.home", "Home", isHome = true)),
            trackers = listOf(
                tracker("person.a", "Alice", "home"),
                tracker("person.b", "Bob", "Home"),
            ),
        )
        // Bob matches by name "Home", Alice by the "home" special case.
        assertThat(res.zones.single().occupants).containsExactly("Alice", "Bob")
    }

    @Test fun `away and unknown states collect under outside`() {
        val res = resolveZoneMembership(
            zones = listOf(zone("zone.home", "Home", isHome = true)),
            trackers = listOf(
                tracker("person.a", "Alice", "not_home"),
                tracker("person.b", "Bob", "away"),
                tracker("person.c", "Carol", "unknown"),
                tracker("person.d", "Dave", "unavailable"),
                tracker("person.e", "Eve", ""),
            ),
        )
        assertThat(res.outside)
            .containsExactly("Alice", "Bob", "Carol", "Dave", "Eve")
        assertThat(res.zones.single().occupants).isEmpty()
    }

    @Test fun `zones sorted most occupied first`() {
        val res = resolveZoneMembership(
            zones = listOf(
                zone("zone.a", "Empty"),
                zone("zone.b", "Busy"),
            ),
            trackers = listOf(
                tracker("person.a", "Alice", "Busy"),
                tracker("person.b", "Bob", "Busy"),
            ),
        )
        assertThat(res.zones.first().name).isEqualTo("Busy")
    }

    @Test fun `duplicate occupant names are de-duplicated`() {
        val res = resolveZoneMembership(
            zones = listOf(zone("zone.work", "Work")),
            trackers = listOf(
                tracker("person.a", "Alice", "Work"),
                tracker("device_tracker.a_phone", "Alice", "Work"),
            ),
        )
        assertThat(res.zones.single().occupants).containsExactly("Alice")
    }

    @Test fun `mappable trackers keep only finite gps points`() {
        val out = mappableTrackers(
            trackers = listOf(
                tracker("person.a", "Alice", "home", 52.0, 13.0),
                tracker("person.b", "Bob", "not_home", null, 13.0),
                tracker("person.c", "Carol", "home", Double.NaN, 13.0),
            ),
            homeZoneName = "Home",
        )
        assertThat(out.map { it.name }).containsExactly("Alice")
    }

    @Test fun `mappable tracker home flag set by home state and home zone name`() {
        val out = mappableTrackers(
            trackers = listOf(
                tracker("person.a", "Alice", "home", 1.0, 1.0),
                tracker("person.b", "Bob", "Home", 2.0, 2.0),
                tracker("person.c", "Carol", "Work", 3.0, 3.0),
            ),
            homeZoneName = "Home",
        )
        assertThat(out.single { it.name == "Alice" }.home).isTrue()
        assertThat(out.single { it.name == "Bob" }.home).isTrue()
        assertThat(out.single { it.name == "Carol" }.home).isFalse()
    }

    @Test fun `geo bounds spans all supplied points`() {
        val b = geoBounds(listOf(1.0 to 10.0, 3.0 to 30.0, 2.0 to 20.0))!!
        assertThat(b.latMin).isEqualTo(1.0)
        assertThat(b.latMax).isEqualTo(3.0)
        assertThat(b.lonMin).isEqualTo(10.0)
        assertThat(b.lonMax).isEqualTo(30.0)
    }

    @Test fun `geo bounds returns null for empty input`() {
        assertThat(geoBounds(emptyList())).isNull()
    }

    @Test fun `projection places min corner at margin and inverts latitude`() {
        val b = GeoBounds(latMin = 0.0, latMax = 10.0, lonMin = 0.0, lonMax = 10.0)
        // South-west corner: x at left margin, y at bottom (1 - margin).
        val (x, y) = projectToCanvasFraction(0.0, 0.0, b, margin = 0.1f)
        assertThat(x).isWithin(1e-5f).of(0.1f)
        assertThat(y).isWithin(1e-5f).of(0.9f)
    }

    @Test fun `projection places max corner at opposite margin with north up`() {
        val b = GeoBounds(latMin = 0.0, latMax = 10.0, lonMin = 0.0, lonMax = 10.0)
        // North-east corner: x at right margin, y at top.
        val (x, y) = projectToCanvasFraction(10.0, 10.0, b, margin = 0.1f)
        assertThat(x).isWithin(1e-5f).of(0.9f)
        assertThat(y).isWithin(1e-5f).of(0.1f)
    }

    @Test fun `projection centre lands at canvas centre`() {
        val b = GeoBounds(latMin = 0.0, latMax = 10.0, lonMin = 0.0, lonMax = 10.0)
        val (x, y) = projectToCanvasFraction(5.0, 5.0, b, margin = 0.1f)
        assertThat(x).isWithin(1e-5f).of(0.5f)
        assertThat(y).isWithin(1e-5f).of(0.5f)
    }

    @Test fun `degenerate span does not divide by zero`() {
        // A single point: span clamps to 0.01 so the fraction stays finite.
        val b = GeoBounds(latMin = 5.0, latMax = 5.0, lonMin = 5.0, lonMax = 5.0)
        val (x, y) = projectToCanvasFraction(5.0, 5.0, b, margin = 0.1f)
        assertThat(x.isFinite()).isTrue()
        assertThat(y.isFinite()).isTrue()
    }

    @Test fun `meters per lon degree shrinks toward the poles`() {
        assertThat(metersPerLonDegree(0.0)).isWithin(1.0).of(METERS_PER_LAT_DEGREE)
        assertThat(metersPerLonDegree(60.0)).isLessThan(metersPerLonDegree(0.0))
    }
}
