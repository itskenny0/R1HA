package com.github.itskenny0.r1ha.core.ha

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EntityDomainTest {
    @Test fun `fromPrefix maps known prefixes`() {
        assertThat(Domain.fromPrefix("light")).isEqualTo(Domain.LIGHT)
        assertThat(Domain.fromPrefix("fan")).isEqualTo(Domain.FAN)
        assertThat(Domain.fromPrefix("cover")).isEqualTo(Domain.COVER)
        assertThat(Domain.fromPrefix("media_player")).isEqualTo(Domain.MEDIA_PLAYER)
        assertThat(Domain.fromPrefix("switch")).isEqualTo(Domain.SWITCH)
        assertThat(Domain.fromPrefix("input_boolean")).isEqualTo(Domain.INPUT_BOOLEAN)
        assertThat(Domain.fromPrefix("automation")).isEqualTo(Domain.AUTOMATION)
        assertThat(Domain.fromPrefix("lock")).isEqualTo(Domain.LOCK)
        assertThat(Domain.fromPrefix("humidifier")).isEqualTo(Domain.HUMIDIFIER)
        assertThat(Domain.fromPrefix("sensor")).isEqualTo(Domain.SENSOR)
        assertThat(Domain.fromPrefix("binary_sensor")).isEqualTo(Domain.BINARY_SENSOR)
        assertThat(Domain.fromPrefix("alarm_control_panel")).isEqualTo(Domain.ALARM_CONTROL_PANEL)
        assertThat(Domain.fromPrefix("person")).isEqualTo(Domain.PERSON)
        assertThat(Domain.fromPrefix("weather")).isEqualTo(Domain.WEATHER)
    }

    @Test fun `person and weather are supported read-only sensor domains`() {
        // Both now surface (so the Weather screen + presence cards work). They're
        // read-only: classed as sensors, never as actions or settable selects.
        assertThat(Domain.isSupportedPrefix("person")).isTrue()
        assertThat(Domain.isSupportedPrefix("weather")).isTrue()
        assertThat(Domain.PERSON.prefix).isEqualTo("person")
        assertThat(Domain.WEATHER.prefix).isEqualTo("weather")
        assertThat(Domain.PERSON.isSensor).isTrue()
        assertThat(Domain.WEATHER.isSensor).isTrue()
        assertThat(Domain.PERSON.isAction).isFalse()
        assertThat(Domain.WEATHER.isAction).isFalse()
        assertThat(Domain.PERSON.isSelect).isFalse()
        assertThat(Domain.WEATHER.isSelect).isFalse()
    }

    @Test fun `new domains are mapped from prefix`() {
        assertThat(Domain.fromPrefix("text")).isEqualTo(Domain.TEXT)
        assertThat(Domain.fromPrefix("date")).isEqualTo(Domain.DATE)
        assertThat(Domain.fromPrefix("datetime")).isEqualTo(Domain.DATETIME)
        assertThat(Domain.fromPrefix("time")).isEqualTo(Domain.TIME)
        assertThat(Domain.fromPrefix("siren")).isEqualTo(Domain.SIREN)
        assertThat(Domain.fromPrefix("image")).isEqualTo(Domain.IMAGE)
        assertThat(Domain.fromPrefix("event")).isEqualTo(Domain.EVENT)
    }

    @Test fun `new read-only domains are sensor-like`() {
        // text / date / datetime / time / image / event are read-only from the card stack
        for (d in listOf(Domain.TEXT, Domain.DATE, Domain.DATETIME, Domain.TIME, Domain.IMAGE, Domain.EVENT)) {
            assertThat(d.isSensor).isTrue()
            assertThat(d.isAction).isFalse()
            assertThat(d.isSelect).isFalse()
        }
        // Siren is on/off — NOT a sensor; not action, not select.
        assertThat(Domain.SIREN.isSensor).isFalse()
        assertThat(Domain.SIREN.isAction).isFalse()
        assertThat(Domain.SIREN.isSelect).isFalse()
    }

    @Test fun `fromPrefix rejects unknown prefix`() {
        // Domains the app has no card archetype for. fromPrefix stays strict (throws) for the
        // control paths; the lenient fromPrefixOrOther below is the search path's entry point.
        assertThrows<IllegalArgumentException> { Domain.fromPrefix("device_tracker") }
        assertThrows<IllegalArgumentException> { Domain.fromPrefix("sun") }
        assertThrows<IllegalArgumentException> { Domain.fromPrefix("") }
    }

    @Test fun `fromPrefixOrOther maps unknown prefixes to OTHER`() {
        assertThat(Domain.fromPrefixOrOther("light")).isEqualTo(Domain.LIGHT)
        assertThat(Domain.fromPrefixOrOther("device_tracker")).isEqualTo(Domain.OTHER)
        assertThat(Domain.fromPrefixOrOther("zone")).isEqualTo(Domain.OTHER)
        assertThat(Domain.fromPrefixOrOther("")).isEqualTo(Domain.OTHER)
    }

    @Test fun `OTHER is never reachable via prefix lookup`() {
        // Its empty sentinel prefix must not shadow a real lookup.
        assertThat(Domain.isSupportedPrefix("")).isFalse()
        assertThat(Domain.isSupportedPrefix("other")).isFalse()
    }
}
