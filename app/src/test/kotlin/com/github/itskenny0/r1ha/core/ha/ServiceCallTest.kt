package com.github.itskenny0.r1ha.core.ha

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class ServiceCallTest {
    @Test fun `light setPct 0 maps to turn_off`() {
        val call = ServiceCall.setPercent(EntityId("light.kitchen"), 0)
        assertThat(call.service).isEqualTo("turn_off")
        assertThat(call.data).isEqualTo(JsonObject(emptyMap()))
    }
    @Test fun `light setPct 50 maps to turn_on with brightness_pct`() {
        val call = ServiceCall.setPercent(EntityId("light.kitchen"), 50)
        assertThat(call.service).isEqualTo("turn_on")
        assertThat(call.data["brightness_pct"]).isEqualTo(JsonPrimitive(50))
    }
    @Test fun `fan setPct 0 maps to turn_off`() {
        val call = ServiceCall.setPercent(EntityId("fan.bedroom"), 0)
        assertThat(call.service).isEqualTo("turn_off")
    }
    @Test fun `fan setPct 30 maps to set_percentage`() {
        val call = ServiceCall.setPercent(EntityId("fan.bedroom"), 30)
        assertThat(call.service).isEqualTo("set_percentage")
        assertThat(call.data["percentage"]).isEqualTo(JsonPrimitive(30))
    }
    @Test fun `cover setPct maps to set_cover_position`() {
        val call = ServiceCall.setPercent(EntityId("cover.shade"), 75)
        assertThat(call.service).isEqualTo("set_cover_position")
        assertThat(call.data["position"]).isEqualTo(JsonPrimitive(75))
    }
    @Test fun `media setPct maps to volume_set with float`() {
        val call = ServiceCall.setPercent(EntityId("media_player.kitchen"), 40)
        assertThat(call.service).isEqualTo("volume_set")
        // 0.40 with rounding; compare numeric value rather than literal
        val v = call.data["volume_level"]!!.toString().toDouble()
        assertThat(v).isWithin(0.001).of(0.40)
    }
    @Test fun `tap action varies by domain`() {
        assertThat(ServiceCall.tapAction(EntityId("light.x"), isOn = true).service).isEqualTo("turn_off")
        assertThat(ServiceCall.tapAction(EntityId("light.x"), isOn = false).service).isEqualTo("turn_on")
        // Cover tap toggles to the opposite extreme rather than just stopping — `stop_cover`
        // on a stationary cover was a no-op which felt broken from the user's perspective.
        assertThat(ServiceCall.tapAction(EntityId("cover.x"), isOn = true).service).isEqualTo("close_cover")
        assertThat(ServiceCall.tapAction(EntityId("cover.x"), isOn = false).service).isEqualTo("open_cover")
        assertThat(ServiceCall.tapAction(EntityId("media_player.x"), isOn = true).service).isEqualTo("media_play_pause")
    }
    @Test fun `alarm action maps to per-mode service`() {
        val target = EntityId("alarm_control_panel.front_door")
        assertThat(ServiceCall.alarmAction(target, AlarmAction.DISARM).service).isEqualTo("alarm_disarm")
        assertThat(ServiceCall.alarmAction(target, AlarmAction.ARM_AWAY).service).isEqualTo("alarm_arm_away")
        assertThat(ServiceCall.alarmAction(target, AlarmAction.ARM_HOME).service).isEqualTo("alarm_arm_home")
        assertThat(ServiceCall.alarmAction(target, AlarmAction.ARM_NIGHT).service).isEqualTo("alarm_arm_night")
        assertThat(ServiceCall.alarmAction(target, AlarmAction.ARM_VACATION).service).isEqualTo("alarm_arm_vacation")
        assertThat(ServiceCall.alarmAction(target, AlarmAction.ARM_CUSTOM_BYPASS).service)
            .isEqualTo("alarm_arm_custom_bypass")
        assertThat(ServiceCall.alarmAction(target, AlarmAction.TRIGGER).service).isEqualTo("alarm_trigger")
    }
    @Test fun `alarm action carries code when provided`() {
        val call = ServiceCall.alarmAction(EntityId("alarm_control_panel.x"), AlarmAction.DISARM, code = "1234")
        assertThat(call.data["code"]).isEqualTo(JsonPrimitive("1234"))
    }
    @Test fun `alarm action omits code when null or blank`() {
        val noCode = ServiceCall.alarmAction(EntityId("alarm_control_panel.x"), AlarmAction.ARM_HOME, code = null)
        assertThat(noCode.data).isEqualTo(JsonObject(emptyMap()))
        val blank = ServiceCall.alarmAction(EntityId("alarm_control_panel.x"), AlarmAction.ARM_HOME, code = "")
        assertThat(blank.data).isEqualTo(JsonObject(emptyMap()))
    }

    @Test fun `cover tilt open close stop map to tilt services`() {
        val target = EntityId("cover.blind")
        assertThat(ServiceCall.coverOpenTilt(target).service).isEqualTo("open_cover_tilt")
        assertThat(ServiceCall.coverCloseTilt(target).service).isEqualTo("close_cover_tilt")
        assertThat(ServiceCall.coverStopTilt(target).service).isEqualTo("stop_cover_tilt")
    }
    @Test fun `cover set tilt position carries clamped tilt_position`() {
        val target = EntityId("cover.blind")
        val call = ServiceCall.coverSetTiltPosition(target, 130)
        assertThat(call.service).isEqualTo("set_cover_tilt_position")
        assertThat(call.data["tilt_position"]).isEqualTo(JsonPrimitive(100))
        val low = ServiceCall.coverSetTiltPosition(target, -5)
        assertThat(low.data["tilt_position"]).isEqualTo(JsonPrimitive(0))
    }
    @Test fun `humidifier set mode maps to set_mode with mode`() {
        val call = ServiceCall.humidifierSetMode(EntityId("humidifier.living"), "eco")
        assertThat(call.service).isEqualTo("set_mode")
        assertThat(call.data["mode"]).isEqualTo(JsonPrimitive("eco"))
    }
    @Test fun `fan oscillate and direction carry their payloads`() {
        val target = EntityId("fan.bedroom")
        assertThat(ServiceCall.fanOscillate(target, true).data["oscillating"]).isEqualTo(JsonPrimitive(true))
        assertThat(ServiceCall.fanSetDirection(target, "reverse").data["direction"]).isEqualTo(JsonPrimitive("reverse"))
    }
    @Test fun `media extras map to their services`() {
        val target = EntityId("media_player.kitchen")
        assertThat(ServiceCall.mediaShuffleSet(target, true).data["shuffle"]).isEqualTo(JsonPrimitive(true))
        assertThat(ServiceCall.mediaRepeatSet(target, "all").data["repeat"]).isEqualTo(JsonPrimitive("all"))
        assertThat(ServiceCall.mediaSelectSource(target, "Spotify").data["source"]).isEqualTo(JsonPrimitive("Spotify"))
    }
    @Test fun `vacuum set fan speed carries fan_speed`() {
        val call = ServiceCall.vacuumSetFanSpeed(EntityId("vacuum.robi"), "turbo")
        assertThat(call.service).isEqualTo("set_fan_speed")
        assertThat(call.data["fan_speed"]).isEqualTo(JsonPrimitive("turbo"))
    }
}
