package com.github.itskenny0.r1ha.core.ha

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EntityIdTest {
    @Test fun `parses prefix into Domain`() {
        val id = EntityId("light.kitchen")
        assertThat(id.domain).isEqualTo(Domain.LIGHT)
        assertThat(id.objectId).isEqualTo("kitchen")
    }

    @Test fun `media_player keeps underscore`() {
        val id = EntityId("media_player.living_room")
        assertThat(id.domain).isEqualTo(Domain.MEDIA_PLAYER)
        assertThat(id.objectId).isEqualTo("living_room")
    }

    @Test fun `rejects missing dot`() {
        assertThrows<IllegalArgumentException> { EntityId("light_kitchen") }
    }

    @Test fun `rejects unsupported domain`() {
        // device_tracker / sun are read-only state surfaces without a clean R1
        // control affordance. Adding them would either show as inert cards or
        // duplicate the sensor read-out path with no real value, so they stay out
        // to keep the picker signal-to-noise high.
        assertThrows<IllegalArgumentException> { EntityId("device_tracker.phone") }
        assertThrows<IllegalArgumentException> { EntityId("sun.sun") }
    }

    @Test fun `parses sensor domains`() {
        assertThat(EntityId("sensor.living_room_temperature").domain).isEqualTo(Domain.SENSOR)
        assertThat(EntityId("binary_sensor.front_door").domain).isEqualTo(Domain.BINARY_SENSOR)
        // person + weather are read-only info entities that now surface.
        assertThat(EntityId("person.alice").domain).isEqualTo(Domain.PERSON)
        assertThat(EntityId("weather.home").domain).isEqualTo(Domain.WEATHER)
    }

    @Test fun `parses on-off and scalar domains`() {
        assertThat(EntityId("switch.desk_lamp").domain).isEqualTo(Domain.SWITCH)
        assertThat(EntityId("input_boolean.guest_mode").domain).isEqualTo(Domain.INPUT_BOOLEAN)
        assertThat(EntityId("automation.morning_routine").domain).isEqualTo(Domain.AUTOMATION)
        assertThat(EntityId("lock.front_door").domain).isEqualTo(Domain.LOCK)
        assertThat(EntityId("humidifier.bedroom").domain).isEqualTo(Domain.HUMIDIFIER)
        assertThat(EntityId("climate.living_room").domain).isEqualTo(Domain.CLIMATE)
    }

    @Test fun `parses action-only domains`() {
        assertThat(EntityId("scene.movie_night").domain).isEqualTo(Domain.SCENE)
        assertThat(EntityId("script.morning_lights").domain).isEqualTo(Domain.SCRIPT)
        assertThat(EntityId("button.doorbell").domain).isEqualTo(Domain.BUTTON)
    }

    @Test fun `isAction flags action-only domains`() {
        assertThat(Domain.SCENE.isAction).isTrue()
        assertThat(Domain.SCRIPT.isAction).isTrue()
        assertThat(Domain.BUTTON.isAction).isTrue()
        assertThat(Domain.LIGHT.isAction).isFalse()
        assertThat(Domain.SWITCH.isAction).isFalse()
    }
}
