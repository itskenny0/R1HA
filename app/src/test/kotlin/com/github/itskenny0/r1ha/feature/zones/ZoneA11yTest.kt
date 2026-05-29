package com.github.itskenny0.r1ha.feature.zones

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ZoneA11yTest {

    @BeforeEach
    fun setLocale() {
        Locale.setDefault(Locale.US)
    }

    @Test
    fun `empty zone reads as empty with radius`() {
        assertThat(ZoneA11y.zoneRowLabel("Home", emptyList(), 100.0))
            .isEqualTo("Zone Home. empty. radius 100 metres")
    }

    @Test
    fun `single occupant is singular`() {
        assertThat(ZoneA11y.zoneRowLabel("Home", listOf("Alice"), null))
            .isEqualTo("Zone Home. 1 person inside, Alice")
    }

    @Test
    fun `multiple occupants are listed`() {
        assertThat(ZoneA11y.zoneRowLabel("Work", listOf("Alice", "Bob"), 250.0))
            .isEqualTo("Zone Work. 2 people inside, Alice, Bob. radius 250 metres")
    }

    @Test
    fun `large radius reads in kilometres`() {
        assertThat(ZoneA11y.zoneRowLabel("Region", emptyList(), 1500.0))
            .isEqualTo("Zone Region. empty. radius 1.5 kilometres")
    }

    @Test
    fun `blank zone name falls back`() {
        assertThat(ZoneA11y.zoneRowLabel("  ", listOf("Alice"), null))
            .isEqualTo("Zone Unnamed zone. 1 person inside, Alice")
    }

    @Test
    fun `blank occupant names are dropped`() {
        assertThat(ZoneA11y.zoneRowLabel("Home", listOf("Alice", "  ", "Bob"), null))
            .isEqualTo("Zone Home. 2 people inside, Alice, Bob")
    }

    @Test
    fun `outside row singular and plural`() {
        assertThat(ZoneA11y.outsideRowLabel(listOf("Alice")))
            .isEqualTo("Outside any zone. 1 person, Alice")
        assertThat(ZoneA11y.outsideRowLabel(listOf("Alice", "Bob")))
            .isEqualTo("Outside any zone. 2 people, Alice, Bob")
    }

    @Test
    fun `outside row empty`() {
        assertThat(ZoneA11y.outsideRowLabel(emptyList()))
            .isEqualTo("Outside any zone. Nobody.")
    }

    @Test
    fun `map description points at the list and pluralises`() {
        assertThat(ZoneA11y.mapDescription(zoneCount = 1, trackerCount = 1))
            .isEqualTo(
                "Map showing 1 zone and 1 tracked person by location. " +
                    "See the zone list below for occupancy details.",
            )
        assertThat(ZoneA11y.mapDescription(zoneCount = 3, trackerCount = 0))
            .isEqualTo(
                "Map showing 3 zones and 0 tracked people by location. " +
                    "See the zone list below for occupancy details.",
            )
    }
}
