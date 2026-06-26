package com.github.itskenny0.r1ha.feature.quickactions

import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import com.github.itskenny0.r1ha.core.prefs.EntityOverride
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Behavioural tests for the light / climate / media quick-action builders. Each test
 * builds a representative [EntityState], runs the builder through a capturing
 * [QuickActionContext], asserts the expected groups / chips, then invokes one chip's
 * `onFire` and asserts the dispatched [ServiceCall] carries the right target / service /
 * data. The service names and attribute reads are pinned against the per-domain panels
 * in `ui.components` so a future divergence fails loudly here.
 */
class QuickActionBuildersLightClimateMediaTest {

    // ── fixtures ──────────────────────────────────────────────────────────────────

    private fun lightState(
        id: String = "light.kitchen",
        isOn: Boolean = true,
        supportedColorModes: List<String> = emptyList(),
        colorTempK: Int? = null,
        effectList: List<String> = emptyList(),
        effect: String? = null,
    ) = EntityState(
        id = EntityId(id),
        friendlyName = "Kitchen",
        area = null,
        isOn = isOn,
        percent = null,
        raw = null,
        lastChanged = Instant.EPOCH,
        isAvailable = true,
        supportedColorModes = supportedColorModes,
        colorTempK = colorTempK,
        effectList = effectList,
        effect = effect,
    )

    private fun climateState(
        rawState: String? = "cool",
        attrs: JsonObject = buildJsonObject {
            putJsonArray("hvac_modes") { add(JsonPrimitive("off")); add(JsonPrimitive("heat")); add(JsonPrimitive("cool")) }
            putJsonArray("preset_modes") { add(JsonPrimitive("eco")); add(JsonPrimitive("comfort")) }
            putJsonArray("fan_modes") { add(JsonPrimitive("low")); add(JsonPrimitive("high")) }
            put("preset_mode", JsonPrimitive("eco"))
            put("fan_mode", JsonPrimitive("low"))
        },
    ) = EntityState(
        id = EntityId("climate.living_room"),
        friendlyName = "Living Room",
        area = null,
        isOn = true,
        percent = null,
        raw = null,
        lastChanged = Instant.EPOCH,
        isAvailable = true,
        rawState = rawState,
        attributesJson = attrs,
    )

    private fun mediaState(
        isOn: Boolean = true,
        supportedFeatures: Int,
        attrs: JsonObject = buildJsonObject {
            putJsonArray("source_list") { add(JsonPrimitive("Spotify")); add(JsonPrimitive("Radio")) }
            put("source", JsonPrimitive("Spotify"))
            put("shuffle", JsonPrimitive(true))
            put("repeat", JsonPrimitive("off"))
        },
    ) = EntityState(
        id = EntityId("media_player.den"),
        friendlyName = "Den",
        area = null,
        isOn = isOn,
        percent = null,
        raw = null,
        lastChanged = Instant.EPOCH,
        isAvailable = true,
        mediaSupportedFeatures = supportedFeatures,
        attributesJson = attrs,
    )

    private class Capture {
        val calls = mutableListOf<ServiceCall>()
        var dismissed = false
        fun ctx(state: EntityState, override: EntityOverride = EntityOverride.NONE) =
            QuickActionContext(
                state = state,
                override = override,
                onEntityCall = { calls.add(it) },
                onSetPercent = { _, _ -> },
                dismiss = { dismissed = true },
            )
    }

    private fun List<QuickActionGroup>.group(title: String) = first { it.title == title }
    private fun QuickActionGroup.action(id: String) = actions.first { it.id == id }

    // ── supports() claims the right domains ────────────────────────────────────────

    @Test fun `each builder claims only its own domain`() {
        assertThat(LightQuickActions.supports(lightState())).isTrue()
        assertThat(LightQuickActions.supports(climateState())).isFalse()
        assertThat(ClimateQuickActions.supports(climateState())).isTrue()
        assertThat(ClimateQuickActions.supports(lightState())).isFalse()
        assertThat(MediaQuickActions.supports(mediaState(supportedFeatures = 0))).isTrue()
        assertThat(MediaQuickActions.supports(lightState())).isFalse()
    }

    @Test fun `exposed list registers the three builders in order`() {
        assertThat(lightClimateMediaQuickActionBuilders)
            .containsExactly(LightQuickActions, ClimateQuickActions, MediaQuickActions)
            .inOrder()
    }

    // ── light ──────────────────────────────────────────────────────────────────────

    @Test fun `light favourite-colour swatch fires light turn_on with rgb_color`() {
        val red = 0xFFE53935.toInt() // a = FF, r = E5 (229), g = 39 (57), b = 35 (53)
        val cap = Capture()
        val groups = LightQuickActions.build(
            cap.ctx(lightState(), override = EntityOverride(favoriteColors = listOf(red))),
        )
        val swatch = groups.group("FAVOURITE COLOURS").action("light-colour-$red")
        assertThat(swatch.accentArgb).isEqualTo(red)

        swatch.onFire()
        val call = cap.calls.single()
        assertThat(call.target.value).isEqualTo("light.kitchen")
        assertThat(call.service).isEqualTo("turn_on")
        val rgb = call.data["rgb_color"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }
        assertThat(rgb).containsExactly(229, 57, 53).inOrder()
    }

    @Test fun `light colour-temp group only appears when color_temp is supported`() {
        val without = LightQuickActions.build(Capture().ctx(lightState()))
        assertThat(without.none { it.title == "COLOUR TEMP" }).isTrue()

        val cap = Capture()
        val groups = LightQuickActions.build(
            cap.ctx(lightState(supportedColorModes = listOf("brightness", "color_temp"), colorTempK = 2700)),
        )
        val ct = groups.group("COLOUR TEMP")
        assertThat(ct.actions).hasSize(EntityOverride.LIGHT_CT_PRESETS.size)
        // The 2700 K chip is selected because it matches the bulb's current colorTempK.
        assertThat(ct.action("light-ct-2700").selected).isTrue()

        ct.action("light-ct-2700").onFire()
        val call = cap.calls.single()
        assertThat(call.service).isEqualTo("turn_on")
        assertThat(call.data["color_temp_kelvin"]!!.jsonPrimitive.content.toInt()).isEqualTo(2700)
    }

    @Test fun `light effect chip fires light turn_on with effect and marks the active one`() {
        val cap = Capture()
        val groups = LightQuickActions.build(
            cap.ctx(lightState(effectList = listOf("colorloop", "Rainbow"), effect = "colorloop")),
        )
        val effects = groups.group("EFFECT")
        assertThat(effects.action("light-fx-colorloop").selected).isTrue()
        assertThat(effects.action("light-fx-Rainbow").selected).isFalse()

        effects.action("light-fx-Rainbow").onFire()
        val call = cap.calls.single()
        assertThat(call.service).isEqualTo("turn_on")
        assertThat(call.data["effect"]!!.jsonPrimitive.content).isEqualTo("Rainbow")
    }

    @Test fun `light effect chips are capped`() {
        val many = (1..30).map { "fx$it" }
        val groups = LightQuickActions.build(Capture().ctx(lightState(effectList = many)))
        assertThat(groups.group("EFFECT").actions).hasSize(12)
    }

    @Test fun `light on-off chips fire turn_on turn_off and dismiss the sheet`() {
        val cap = Capture()
        val groups = LightQuickActions.build(cap.ctx(lightState(isOn = true)))
        val power = groups.group("POWER")
        assertThat(power.action("light-on").selected).isTrue()
        assertThat(power.action("light-off").selected).isFalse()

        power.action("light-off").onFire()
        val call = cap.calls.single()
        assertThat(call.service).isEqualTo("turn_off")
        assertThat(cap.dismissed).isTrue()
    }

    // ── climate ──────────────────────────────────────────────────────────────────

    @Test fun `climate surfaces hvac preset and fan groups and fires the right services`() {
        val cap = Capture()
        val groups = ClimateQuickActions.build(cap.ctx(climateState(rawState = "cool")))

        val mode = groups.group("MODE")
        assertThat(mode.action("climate-hvac-cool").selected).isTrue()
        assertThat(mode.action("climate-hvac-heat").selected).isFalse()

        val preset = groups.group("PRESET")
        assertThat(preset.action("climate-preset-eco").selected).isTrue()

        val fan = groups.group("FAN")
        assertThat(fan.action("climate-fan-low").selected).isTrue()

        mode.action("climate-hvac-cool").onFire()
        preset.action("climate-preset-comfort").onFire()
        fan.action("climate-fan-high").onFire()

        assertThat(cap.calls).hasSize(3)
        val hvac = cap.calls[0]
        assertThat(hvac.target.value).isEqualTo("climate.living_room")
        assertThat(hvac.service).isEqualTo("set_hvac_mode")
        assertThat(hvac.data["hvac_mode"]!!.jsonPrimitive.content).isEqualTo("cool")

        val presetCall = cap.calls[1]
        assertThat(presetCall.service).isEqualTo("set_preset_mode")
        assertThat(presetCall.data["preset_mode"]!!.jsonPrimitive.content).isEqualTo("comfort")

        val fanCall = cap.calls[2]
        assertThat(fanCall.service).isEqualTo("set_fan_mode")
        assertThat(fanCall.data["fan_mode"]!!.jsonPrimitive.content).isEqualTo("high")
    }

    @Test fun `climate skips groups whose attribute list is empty`() {
        val cap = Capture()
        val onlyHvac = buildJsonObject {
            putJsonArray("hvac_modes") { add(JsonPrimitive("off")); add(JsonPrimitive("heat")) }
        }
        val groups = ClimateQuickActions.build(cap.ctx(climateState(attrs = onlyHvac)))
        assertThat(groups.map { it.title }).containsExactly("MODE")
    }

    // ── media ──────────────────────────────────────────────────────────────────────

    private val fullMediaFeatures =
        EntityState.MediaPlayerFeature.PLAY or
            EntityState.MediaPlayerFeature.PAUSE or
            EntityState.MediaPlayerFeature.NEXT_TRACK or
            EntityState.MediaPlayerFeature.PREVIOUS_TRACK or
            EntityState.MediaPlayerFeature.SELECT_SOURCE or
            EntityState.MediaPlayerFeature.SHUFFLE_SET or
            EntityState.MediaPlayerFeature.REPEAT_SET

    @Test fun `media transport fires explicit play pause next and previous services`() {
        val cap = Capture()
        val groups = MediaQuickActions.build(
            cap.ctx(mediaState(isOn = true, supportedFeatures = fullMediaFeatures)),
        )
        val playback = groups.group("PLAYBACK")
        // isOn = playing, so the toggle reads PAUSE and fires media_pause.
        assertThat(playback.action("media-play-pause").label).isEqualTo("PAUSE")

        playback.action("media-previous").onFire()
        playback.action("media-play-pause").onFire()
        playback.action("media-next").onFire()

        assertThat(cap.calls.map { it.service })
            .containsExactly("media_previous_track", "media_pause", "media_next_track")
            .inOrder()
        assertThat(cap.calls.all { it.target.value == "media_player.den" }).isTrue()
    }

    @Test fun `paused media toggle reads PLAY and fires media_play`() {
        val cap = Capture()
        val groups = MediaQuickActions.build(
            cap.ctx(mediaState(isOn = false, supportedFeatures = fullMediaFeatures)),
        )
        val toggle = groups.group("PLAYBACK").action("media-play-pause")
        assertThat(toggle.label).isEqualTo("PLAY")
        toggle.onFire()
        assertThat(cap.calls.single().service).isEqualTo("media_play")
    }

    @Test fun `media source chip fires select_source and marks the active source`() {
        val cap = Capture()
        val groups = MediaQuickActions.build(
            cap.ctx(mediaState(supportedFeatures = fullMediaFeatures)),
        )
        val source = groups.group("SOURCE")
        assertThat(source.action("media-source-Spotify").selected).isTrue()

        source.action("media-source-Radio").onFire()
        val call = cap.calls.single()
        assertThat(call.service).isEqualTo("select_source")
        assertThat(call.data["source"]!!.jsonPrimitive.content).isEqualTo("Radio")
    }

    @Test fun `media shuffle toggles and repeat cycles off to all`() {
        val cap = Capture()
        val groups = MediaQuickActions.build(
            cap.ctx(mediaState(supportedFeatures = fullMediaFeatures)),
        )
        val playbackMode = groups.group("SHUFFLE & REPEAT")
        // shuffle attr is true in the fixture, so the chip is selected and a tap turns it off.
        assertThat(playbackMode.action("media-shuffle").selected).isTrue()

        playbackMode.action("media-shuffle").onFire()
        val shuffleCall = cap.calls.single()
        assertThat(shuffleCall.service).isEqualTo("shuffle_set")
        assertThat(shuffleCall.data["shuffle"]!!.jsonPrimitive.content.toBoolean()).isFalse()

        cap.calls.clear()
        // repeat attr is "off", so the cycle advances to "all".
        playbackMode.action("media-repeat").onFire()
        val repeatCall = cap.calls.single()
        assertThat(repeatCall.service).isEqualTo("repeat_set")
        assertThat(repeatCall.data["repeat"]!!.jsonPrimitive.content).isEqualTo("all")
    }

    @Test fun `media with no transport supported_features omits the transport group`() {
        // A non-zero bitmask that advertises only VOLUME_SET has no PLAY / PAUSE / NEXT /
        // PREVIOUS bits, so the transport group must be dropped entirely.
        val cap = Capture()
        val groups = MediaQuickActions.build(
            cap.ctx(mediaState(supportedFeatures = EntityState.MediaPlayerFeature.VOLUME_SET)),
        )
        assertThat(groups.none { it.title == "PLAYBACK" }).isTrue()
    }

    @Test fun `media with unknown supported_features forgives the omission and shows transport`() {
        val groups = MediaQuickActions.build(Capture().ctx(mediaState(supportedFeatures = 0)))
        assertThat(groups.any { it.title == "PLAYBACK" }).isTrue()
    }
}
