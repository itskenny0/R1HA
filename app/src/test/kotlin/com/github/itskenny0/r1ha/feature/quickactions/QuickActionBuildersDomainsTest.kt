package com.github.itskenny0.r1ha.feature.quickactions

import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import com.github.itskenny0.r1ha.core.prefs.EntityOverride
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Builder-level tests for the cover/fan/lock/vacuum/remote/siren/valve/water_heater/humidifier
 * Quick Sheet actions. Each test builds a representative [EntityState] (lists / flags fed
 * through [EntityState.attributesJson] the way the live repository hands them over), runs the
 * builder, asserts the surfaced chips, then fires an action and inspects the captured
 * [ServiceCall].
 */
class QuickActionBuildersDomainsTest {

    private class Captured {
        val calls = mutableListOf<ServiceCall>()
        var dismissed = 0
    }

    private fun stateOf(
        id: String,
        isOn: Boolean = true,
        percent: Int? = null,
        supportedFeatures: Int = 0,
        vacuumSupportedFeatures: Int = 0,
        attrs: JsonObject = JsonObject(emptyMap()),
    ) = EntityState(
        id = EntityId(id),
        friendlyName = id.substringAfter('.'),
        area = null,
        isOn = isOn,
        percent = percent,
        raw = null,
        lastChanged = Instant.EPOCH,
        isAvailable = true,
        supportedFeatures = supportedFeatures,
        vacuumSupportedFeatures = vacuumSupportedFeatures,
        attributesJson = attrs,
    )

    private fun ctxFor(
        state: EntityState,
        override: EntityOverride = EntityOverride.NONE,
        cap: Captured = Captured(),
    ) = QuickActionContext(
        state = state,
        override = override,
        onEntityCall = { cap.calls += it },
        onSetPercent = { _, _ -> },
        dismiss = { cap.dismissed++ },
    )

    private fun List<QuickActionGroup>.allActions(): List<QuickAction> = flatMap { it.actions }
    private fun List<QuickActionGroup>.ids(): List<String> = allActions().map { it.id }
    private fun List<QuickActionGroup>.byId(id: String): QuickAction =
        allActions().first { it.id == id }

    @Test fun `cover OPEN fires cover open_cover and dismisses`() {
        val cap = Captured()
        val groups = CoverQuickActions.build(ctxFor(stateOf("cover.shade"), cap = cap))
        groups.byId("cover.open").onFire()
        val call = cap.calls.single()
        assertThat(call.service).isEqualTo("open_cover")
        assertThat(call.target.value).isEqualTo("cover.shade")
        assertThat(cap.dismissed).isEqualTo(1)
    }

    @Test fun `cover favourite position fires set_cover_position with the chosen value`() {
        val cap = Captured()
        val override = EntityOverride(favoritePositions = listOf(40, 80))
        val groups = CoverQuickActions.build(
            ctxFor(stateOf("cover.shade"), override = override, cap = cap),
        )
        val fav = groups.byId("cover.fav.40")
        assertThat(fav.label).isEqualTo("40%")
        fav.onFire()
        val call = cap.calls.single()
        assertThat(call.service).isEqualTo("set_cover_position")
        assertThat(call.data["position"]).isEqualTo(JsonPrimitive(40))
    }

    @Test fun `cover tilt chips appear only when a tilt feature bit is advertised`() {
        // No supported_features attr -> no tilt section (CoverPanel's anyTiltBit guard).
        val plain = CoverQuickActions.build(ctxFor(stateOf("cover.shade")))
        assertThat(plain.ids()).doesNotContain("cover.tilt_open")
        // OPEN_TILT | CLOSE_TILT set -> both tilt chips surface.
        val tiltBits = EntityState.CoverFeature.OPEN_TILT or EntityState.CoverFeature.CLOSE_TILT
        val attrs = buildJsonObject { put("supported_features", JsonPrimitive(tiltBits)) }
        val tilting = CoverQuickActions.build(ctxFor(stateOf("cover.blind", attrs = attrs)))
        assertThat(tilting.ids()).containsAtLeast("cover.tilt_open", "cover.tilt_close")
    }

    @Test fun `lock with requirePinToUnlock omits unlock and open, leaving only lock`() {
        val gated = LockQuickActions.build(
            ctxFor(
                stateOf("lock.front", attrs = buildJsonObject { put("supported_features", JsonPrimitive(1)) }),
                override = EntityOverride(requirePinToUnlock = true),
            ),
        ).ids()
        assertThat(gated).contains("lock.lock")
        assertThat(gated).doesNotContain("lock.unlock")
        assertThat(gated).doesNotContain("lock.open")

        // Without the gate, UNLOCK is present; OPEN appears only with the OPEN feature bit.
        val plain = LockQuickActions.build(
            ctxFor(stateOf("lock.front", attrs = buildJsonObject { put("supported_features", JsonPrimitive(1)) })),
        ).ids()
        assertThat(plain).containsAtLeast("lock.lock", "lock.unlock", "lock.open")
    }

    @Test fun `lock OPEN is hidden when the lock does not advertise the open bit`() {
        val ids = LockQuickActions.build(
            // supported_features without bit 1 (OPEN); 0 would forgive the omission, so use 2.
            ctxFor(stateOf("lock.back", attrs = buildJsonObject { put("supported_features", JsonPrimitive(2)) })),
        ).ids()
        assertThat(ids).contains("lock.unlock")
        assertThat(ids).doesNotContain("lock.open")
    }

    @Test fun `vacuum START is gated by supported_features`() {
        val withStart = VacuumQuickActions.build(
            ctxFor(stateOf("vacuum.rover", vacuumSupportedFeatures = EntityState.VacuumFeature.START)),
        ).ids()
        assertThat(withStart).contains("vacuum.start")

        // A non-zero mask without the START bit hides START (PAUSE still shows).
        val withoutStart = VacuumQuickActions.build(
            ctxFor(stateOf("vacuum.rover", vacuumSupportedFeatures = EntityState.VacuumFeature.PAUSE)),
        ).ids()
        assertThat(withoutStart).doesNotContain("vacuum.start")
        assertThat(withoutStart).contains("vacuum.pause")
    }

    @Test fun `vacuum fan speed chip fires set_fan_speed`() {
        val cap = Captured()
        val attrs = buildJsonObject {
            put("fan_speed_list", buildJsonArray { add(JsonPrimitive("quiet")); add(JsonPrimitive("turbo")) })
            put("fan_speed", JsonPrimitive("quiet"))
        }
        val groups = VacuumQuickActions.build(
            ctxFor(
                stateOf("vacuum.rover", vacuumSupportedFeatures = EntityState.VacuumFeature.FAN_SPEED, attrs = attrs),
                cap = cap,
            ),
        )
        val quiet = groups.byId("vacuum.speed.quiet")
        assertThat(quiet.selected).isTrue()
        groups.byId("vacuum.speed.turbo").onFire()
        val call = cap.calls.single()
        assertThat(call.service).isEqualTo("set_fan_speed")
        assertThat(call.data["fan_speed"]).isEqualTo(JsonPrimitive("turbo"))
    }

    @Test fun `remote with empty activity_list yields no actions`() {
        val groups = RemoteQuickActions.build(ctxFor(stateOf("remote.harmony")))
        assertThat(groups).isEmpty()
    }

    @Test fun `remote activity chip fires remote turn_on with the activity`() {
        val cap = Captured()
        val attrs = buildJsonObject {
            put("activity_list", buildJsonArray { add(JsonPrimitive("Watch TV")); add(JsonPrimitive("Music")) })
            put("current_activity", JsonPrimitive("Watch TV"))
        }
        val groups = RemoteQuickActions.build(ctxFor(stateOf("remote.harmony", attrs = attrs), cap = cap))
        val watch = groups.byId("remote.activity.Watch TV")
        assertThat(watch.selected).isTrue()
        groups.byId("remote.activity.Music").onFire()
        val call = cap.calls.single()
        assertThat(call.service).isEqualTo("turn_on")
        assertThat(call.data["activity"]).isEqualTo(JsonPrimitive("Music"))
    }

    @Test fun `fan turn on fires turn_on and preset chip fires set_preset_mode`() {
        val cap = Captured()
        val attrs = buildJsonObject {
            put("preset_modes", buildJsonArray { add(JsonPrimitive("eco")); add(JsonPrimitive("sleep")) })
            put("preset_mode", JsonPrimitive("eco"))
        }
        val groups = FanQuickActions.build(
            ctxFor(
                stateOf("fan.bedroom", supportedFeatures = EntityState.FanFeature.PRESET_MODE, attrs = attrs),
                cap = cap,
            ),
        )
        groups.byId("fan.on").onFire()
        assertThat(cap.calls.single().service).isEqualTo("turn_on")
        assertThat(cap.dismissed).isEqualTo(1)

        val eco = groups.byId("fan.preset.eco")
        assertThat(eco.selected).isTrue()
        cap.calls.clear()
        eco.onFire()
        val call = cap.calls.single()
        assertThat(call.service).isEqualTo("set_preset_mode")
        assertThat(call.data["preset_mode"]).isEqualTo(JsonPrimitive("eco"))
        // Selection chips leave the sheet open: dismissed count unchanged from the fan.on tap.
        assertThat(cap.dismissed).isEqualTo(1)
    }

    @Test fun `fan oscillate toggle sends the inverse of the current state`() {
        val cap = Captured()
        val attrs = buildJsonObject { put("oscillating", JsonPrimitive(false)) }
        val groups = FanQuickActions.build(
            ctxFor(
                stateOf("fan.bedroom", supportedFeatures = EntityState.FanFeature.OSCILLATE, attrs = attrs),
                cap = cap,
            ),
        )
        groups.byId("fan.oscillate").onFire()
        val call = cap.calls.single()
        assertThat(call.service).isEqualTo("oscillate")
        assertThat(call.data["oscillating"]).isEqualTo(JsonPrimitive(true))
    }

    @Test fun `siren tone chip fires turn_on with the tone`() {
        val cap = Captured()
        val attrs = buildJsonObject { put("available_tones", buildJsonArray { add(JsonPrimitive("bleep")); add(JsonPrimitive("chime")) }) }
        val groups = SirenQuickActions.build(ctxFor(stateOf("siren.alarm", isOn = false, attrs = attrs), cap = cap))
        assertThat(groups.ids()).containsAtLeast("siren.on", "siren.off", "siren.tone.bleep")
        groups.byId("siren.tone.bleep").onFire()
        val call = cap.calls.single()
        assertThat(call.service).isEqualTo("turn_on")
        assertThat(call.data["tone"]).isEqualTo(JsonPrimitive("bleep"))
    }

    @Test fun `valve favourite position is gated on SET_POSITION and fires set_valve_position`() {
        val override = EntityOverride(favoritePositions = listOf(50))
        // No SET_POSITION bit -> no favourite chip.
        val noPos = ValveQuickActions.build(
            ctxFor(
                stateOf("valve.water", supportedFeatures = EntityState.ValveFeature.OPEN),
                override = override,
            ),
        )
        assertThat(noPos.ids()).doesNotContain("valve.fav.50")

        val cap = Captured()
        val withPos = ValveQuickActions.build(
            ctxFor(
                stateOf("valve.water", supportedFeatures = EntityState.ValveFeature.SET_POSITION),
                override = override,
                cap = cap,
            ),
        )
        withPos.byId("valve.fav.50").onFire()
        val call = cap.calls.single()
        assertThat(call.service).isEqualTo("set_valve_position")
        assertThat(call.data["position"]).isEqualTo(JsonPrimitive(50))

        cap.calls.clear()
        withPos.byId("valve.open").onFire()
        assertThat(cap.calls.single().service).isEqualTo("open_valve")
    }

    @Test fun `water heater operation mode and away toggle fire the right services`() {
        val cap = Captured()
        val attrs = buildJsonObject {
            put("operation_list", buildJsonArray { add(JsonPrimitive("eco")); add(JsonPrimitive("electric")) })
            put("operation_mode", JsonPrimitive("eco"))
            put("away_mode", JsonPrimitive("off"))
        }
        val groups = WaterHeaterQuickActions.build(ctxFor(stateOf("water_heater.tank", attrs = attrs), cap = cap))
        val eco = groups.byId("water_heater.mode.eco")
        assertThat(eco.selected).isTrue()
        eco.onFire()
        val modeCall = cap.calls.single()
        assertThat(modeCall.service).isEqualTo("set_operation_mode")
        assertThat(modeCall.data["operation_mode"]).isEqualTo(JsonPrimitive("eco"))

        cap.calls.clear()
        val away = groups.byId("water_heater.away")
        assertThat(away.selected).isFalse()
        away.onFire()
        val awayCall = cap.calls.single()
        assertThat(awayCall.service).isEqualTo("set_away_mode")
        // Service schema expects a boolean; state was "off" so the toggle engages away.
        assertThat(awayCall.data["away_mode"]).isEqualTo(JsonPrimitive(true))
    }

    @Test fun `humidifier surfaces mode chips when MODES is advertised`() {
        val cap = Captured()
        val attrs = buildJsonObject {
            put("available_modes", buildJsonArray { add(JsonPrimitive("normal")); add(JsonPrimitive("eco")) })
            put("mode", JsonPrimitive("normal"))
            put("supported_features", JsonPrimitive(EntityState.HumidifierFeature.MODES))
        }
        val groups = HumidifierQuickActions.build(ctxFor(stateOf("humidifier.bedroom", attrs = attrs), cap = cap))
        assertThat(groups.ids()).containsAtLeast(
            "humidifier.on", "humidifier.off", "humidifier.mode.normal", "humidifier.mode.eco",
        )
        groups.byId("humidifier.mode.eco").onFire()
        val call = cap.calls.single()
        assertThat(call.service).isEqualTo("set_mode")
        assertThat(call.data["mode"]).isEqualTo(JsonPrimitive("eco"))
    }

    @Test fun `extraDomainQuickActionBuilders registers all nine domain builders`() {
        assertThat(extraDomainQuickActionBuilders).containsExactly(
            CoverQuickActions,
            FanQuickActions,
            LockQuickActions,
            VacuumQuickActions,
            RemoteQuickActions,
            SirenQuickActions,
            ValveQuickActions,
            WaterHeaterQuickActions,
            HumidifierQuickActions,
        )
    }

    @Test fun `each builder claims only its own domain`() {
        assertThat(CoverQuickActions.supports(stateOf("cover.shade"))).isTrue()
        assertThat(CoverQuickActions.supports(stateOf("fan.bedroom"))).isFalse()
        assertThat(WaterHeaterQuickActions.supports(stateOf("water_heater.tank"))).isTrue()
        assertThat(HumidifierQuickActions.supports(stateOf("humidifier.x"))).isTrue()
    }
}
