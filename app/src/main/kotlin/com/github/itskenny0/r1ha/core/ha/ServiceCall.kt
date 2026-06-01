package com.github.itskenny0.r1ha.core.ha

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Discrete media-player transport / volume actions. Used by the media_player card's
 * control row and dispatched via [ServiceCall.mediaTransport].
 */
enum class MediaTransport {
    PLAY_PAUSE, NEXT, PREVIOUS, VOLUME_UP, VOLUME_DOWN, MUTE_TOGGLE,
}

/** A concrete HA service call: which `domain.service` + the `service_data` JSON. Target is the entity. */
data class ServiceCall(
    val target: EntityId,
    val service: String,
    val data: JsonObject,
) {
    val haDomain: String get() = target.domain.prefix

    companion object {
        fun setPercent(target: EntityId, pct: Int): ServiceCall {
            val clamped = pct.coerceIn(0, 100)
            return when (target.domain) {
                Domain.LIGHT -> if (clamped == 0)
                    ServiceCall(target, "turn_off", JsonObject(emptyMap()))
                else
                    ServiceCall(target, "turn_on", buildJsonObject { put("brightness_pct", JsonPrimitive(clamped)) })

                Domain.FAN -> if (clamped == 0)
                    ServiceCall(target, "turn_off", JsonObject(emptyMap()))
                else
                    ServiceCall(target, "set_percentage", buildJsonObject { put("percentage", JsonPrimitive(clamped)) })

                Domain.COVER ->
                    ServiceCall(target, "set_cover_position", buildJsonObject { put("position", JsonPrimitive(clamped)) })

                Domain.MEDIA_PLAYER ->
                    ServiceCall(target, "volume_set", buildJsonObject {
                        put("volume_level", JsonPrimitive(EntityState.mediaVolumeFromPct(clamped)))
                    })

                // Humidifier — `humidity` is already 0..100 in HA, no normalisation needed.
                // We also auto-turn-on at the start of the wheel turn (clamped > 0) so the
                // user doesn't have to engage the device with a separate tap before setting
                // the target. clamped == 0 turns it off.
                Domain.HUMIDIFIER -> if (clamped == 0)
                    ServiceCall(target, "turn_off", JsonObject(emptyMap()))
                else
                    ServiceCall(target, "set_humidity", buildJsonObject { put("humidity", JsonPrimitive(clamped)) })

                // Pure on/off domains shouldn't hit setPercent at all — the wheel routes
                // them through setSwitch in the VM. Defensive default: any non-zero percent
                // = on, zero = off.
                Domain.SWITCH, Domain.INPUT_BOOLEAN, Domain.AUTOMATION -> ServiceCall(
                    target,
                    if (clamped == 0) "turn_off" else "turn_on",
                    JsonObject(emptyMap()),
                )
                Domain.LOCK -> ServiceCall(
                    target,
                    if (clamped == 0) "lock" else "unlock",
                    JsonObject(emptyMap()),
                )
                // Climate is now rendered as scalar when the entity exposes a
                // temperature range — but this path is only entered for the fallback
                // (no range / TARGET_TEMPERATURE not supported), so on/off is correct.
                // The proper scalar path uses [setTemperature] with the converted Celsius/
                // Fahrenheit value computed at the VM layer.
                Domain.CLIMATE, Domain.WATER_HEATER -> ServiceCall(
                    target,
                    if (clamped == 0) "turn_off" else "turn_on",
                    JsonObject(emptyMap()),
                )
                // Vacuums shouldn't reach the percent path — they're switch cards. If
                // they do (defensive), start when non-zero, return-to-base on zero.
                Domain.VACUUM -> ServiceCall(
                    target,
                    if (clamped == 0) "return_to_base" else "start",
                    JsonObject(emptyMap()),
                )
                Domain.LAWN_MOWER -> ServiceCall(
                    target,
                    if (clamped == 0) "dock" else "start_mowing",
                    JsonObject(emptyMap()),
                )
                // Action-only domains shouldn't reach setPercent — the wheel is ignored on
                // ActionCards. Defensive fallback: just fire the action.
                Domain.SCENE, Domain.SCRIPT -> ServiceCall(target, "turn_on", JsonObject(emptyMap()))
                Domain.BUTTON, Domain.INPUT_BUTTON -> ServiceCall(target, "press", JsonObject(emptyMap()))
                // Sensors are read-only — the wheel and tap are no-ops at the VM level so
                // this branch shouldn't fire. Defensive: emit homeassistant.update_entity
                // which is the closest thing to "do something" without changing state, so
                // the call is loggable but harmless if anything ever does reach here.
                Domain.SENSOR, Domain.BINARY_SENSOR -> ServiceCall(
                    target,
                    "update_entity",
                    JsonObject(emptyMap()),
                )
                // Valve: same shape as cover; setPercent maps to set_valve_position.
                Domain.VALVE -> ServiceCall(
                    target,
                    "set_valve_position",
                    buildJsonObject { put("position", JsonPrimitive(clamped)) },
                )
                // Number / input_number: VM converts the wheel's percent into the
                // entity's native range and calls setNumberValue directly — this path
                // is the fallback (no range cached). Coerce 0..100 directly as the value.
                Domain.NUMBER, Domain.INPUT_NUMBER -> ServiceCall(
                    target,
                    "set_value",
                    buildJsonObject { put("value", JsonPrimitive(clamped)) },
                )
                // Select / input_select don't go through setPercent — the wheel handler
                // routes them to setSelectOption directly. Defensive no-op: dispatching
                // `select_option` without a known option string would fail anyway, so we
                // emit a homeassistant.update_entity which is harmless.
                Domain.SELECT, Domain.INPUT_SELECT -> ServiceCall(
                    target,
                    "update_entity",
                    JsonObject(emptyMap()),
                )
                // Helper-only domains — Helpers screen dispatches their own
                // services (counter.increment, timer.start, etc.). The card
                // stack doesn't reach setPercent for these; defensive
                // homeassistant.update_entity no-op keeps the call safe.
                Domain.COUNTER, Domain.TIMER,
                Domain.INPUT_TEXT, Domain.INPUT_DATETIME -> ServiceCall(
                    target,
                    "update_entity",
                    JsonObject(emptyMap()),
                )
                // Update entities never enter the wheel/percent path — they
                // have their own install/skip flow surfaced from the Updates
                // screen. Defensive: emit homeassistant.update_entity (a HA
                // service that pokes the integration to re-poll its source)
                // so a stray dispatch is a harmless refresh.
                Domain.UPDATE -> ServiceCall(target, "update_entity", JsonObject(emptyMap()))
                // Remote: wheel doesn't drive an IR blaster's percent; defensive
                // turn_on/turn_off keeps the call safe if anything ever reaches here.
                Domain.REMOTE -> ServiceCall(
                    target,
                    if (clamped == 0) "turn_off" else "turn_on",
                    JsonObject(emptyMap()),
                )
                // Alarm control panels never enter the percent path — every
                // action goes through the dedicated AlarmPanel chips with a
                // PIN keypad. Defensive update_entity no-op keeps a stray
                // dispatch harmless.
                Domain.ALARM_CONTROL_PANEL -> ServiceCall(
                    target,
                    "update_entity",
                    JsonObject(emptyMap()),
                )
                // Person / weather are read-only — the wheel and tap are no-ops at
                // the VM level so this branch shouldn't fire. Defensive
                // homeassistant.update_entity keeps a stray dispatch harmless.
                Domain.PERSON, Domain.WEATHER -> ServiceCall(
                    target,
                    "update_entity",
                    JsonObject(emptyMap()),
                )
                // Catch-all domains with no archetype never reach setPercent (the wheel is
                // inert on them). Defensive no-op refresh keeps the dispatch path total.
                Domain.OTHER -> ServiceCall(target, "update_entity", JsonObject(emptyMap()))
            }
        }

        /**
         * Number / input_number value setter. VM converts the wheel's 0..100 percent
         * into the entity's [EntityState.minRaw]/[EntityState.maxRaw] range and calls
         * this helper with the resolved value. Rounded to the nearest step where
         * possible at the call site; here we just emit whatever Double the VM gave us.
         */
        /**
         * Light colour-temp setter — `light.turn_on` with `color_temp_kelvin`. The VM
         * passes the kelvin value computed from the wheel's percent + the entity's
         * min/max range. Optionally includes a brightness so the bulb is guaranteed to
         * be on while it's being tinted; pass null to leave brightness untouched.
         */
        fun setLightColorTemp(target: EntityId, kelvin: Int, brightnessPct: Int? = null): ServiceCall =
            ServiceCall(
                target,
                "turn_on",
                buildJsonObject {
                    put("color_temp_kelvin", JsonPrimitive(kelvin.coerceAtLeast(1)))
                    if (brightnessPct != null) put("brightness_pct", JsonPrimitive(brightnessPct.coerceIn(0, 100)))
                },
            )

        /**
         * Light hue setter — `light.turn_on` with `hs_color: [hue, saturation]`. We pin
         * saturation at 100% so the wheel's hue scan goes through fully-saturated
         * colours; the user can de-saturate from HA if they want pastels. Bundles
         * brightness when supplied for the same reason as [setLightColorTemp].
         */
        fun setLightHue(target: EntityId, hueDegrees: Double, brightnessPct: Int? = null): ServiceCall {
            // Normalise hue into 0..360. The wheel can swing past those edges over time
            // so a defensive modular clamp keeps us inside HA's accepted range.
            val h = ((hueDegrees % 360.0) + 360.0) % 360.0
            return ServiceCall(
                target,
                "turn_on",
                buildJsonObject {
                    put(
                        "hs_color",
                        kotlinx.serialization.json.buildJsonArray {
                            add(JsonPrimitive(h))
                            add(JsonPrimitive(100))
                        },
                    )
                    if (brightnessPct != null) put("brightness_pct", JsonPrimitive(brightnessPct.coerceIn(0, 100)))
                },
            )
        }

        /**
         * Light effect setter — `light.turn_on` with `effect: "<name>"`. Pass null /
         * empty to clear the effect (HA accepts the literal string "None" for "no
         * effect"). The bulb stays in whatever mode it was in; only the effect changes.
         */
        fun setLightEffect(target: EntityId, effect: String?): ServiceCall =
            ServiceCall(
                target,
                "turn_on",
                buildJsonObject {
                    put("effect", JsonPrimitive(effect.takeIf { !it.isNullOrBlank() } ?: "None"))
                },
            )

        /**
         * Discrete media-player transport / volume actions surfaced on the
         * media_player card's control row. None of them carry a payload — HA's
         * media_player domain exposes them as zero-arg services.
         *
         * volume_up / volume_down are 1 dB-or-so increments depending on the device;
         * a tap is the right granularity for fine-tuning without bothering with the
         * wheel. media_play_pause is the universal toggle (HA picks play vs pause
         * based on the current state).
         */
        /**
         * @param currentlyMuted Only meaningful for [MediaTransport.MUTE_TOGGLE] —
         *   HA's `volume_mute` service requires an explicit `is_volume_muted` value,
         *   so we send the inversion of the current state. Other actions ignore it.
         */
        fun mediaTransport(
            target: EntityId,
            action: MediaTransport,
            currentlyMuted: Boolean = false,
        ): ServiceCall =
            ServiceCall(
                target,
                when (action) {
                    MediaTransport.PLAY_PAUSE -> "media_play_pause"
                    MediaTransport.NEXT -> "media_next_track"
                    MediaTransport.PREVIOUS -> "media_previous_track"
                    MediaTransport.VOLUME_UP -> "volume_up"
                    MediaTransport.VOLUME_DOWN -> "volume_down"
                    MediaTransport.MUTE_TOGGLE -> "volume_mute"
                },
                if (action == MediaTransport.MUTE_TOGGLE) {
                    // Send the inversion of the current mute state so a second tap
                    // actually unmutes. Previously this always sent `true`, which
                    // made the MUTE button a one-way trip — the only way to unmute
                    // was via vol+ landing above zero on some integrations.
                    buildJsonObject { put("is_volume_muted", JsonPrimitive(!currentlyMuted)) }
                } else {
                    JsonObject(emptyMap())
                },
            )

        /**
         * Select an option on a `select.*` / `input_select.*` entity. HA's service is
         * `<domain>.select_option` with `{option: "<value>"}`. The option string must
         * be present in the entity's `options` attribute; HA rejects unknown options
         * with a 4xx and the caller's service-failure path surfaces a toast.
         */
        fun setSelectOption(target: EntityId, option: String): ServiceCall =
            ServiceCall(
                target,
                "select_option",
                buildJsonObject { put("option", JsonPrimitive(option)) },
            )

        fun setNumberValue(target: EntityId, value: Double): ServiceCall {
            val rounded = (Math.round(value * 100.0) / 100.0)
            return ServiceCall(
                target,
                "set_value",
                buildJsonObject { put("value", JsonPrimitive(rounded)) },
            )
        }

        /**
         * Install a software update for an `update.*` entity. [version] is optional —
         * when null, HA installs the `latest_version`. [backup] requests a pre-install
         * snapshot when the underlying integration supports it (HA Core / Supervisor
         * / OS expose the `SUPPORT_BACKUP` bit; add-ons and integration firmware
         * usually don't). Setting [backup] true on an entity that doesn't support it
         * is a no-op rather than an error per HA's behaviour, so the caller can pass
         * the user's intent without first checking the supported_features bitmask.
         */
        fun installUpdate(target: EntityId, version: String? = null, backup: Boolean = false): ServiceCall =
            ServiceCall(
                target,
                "install",
                buildJsonObject {
                    if (!version.isNullOrBlank()) put("version", JsonPrimitive(version))
                    if (backup) put("backup", JsonPrimitive(true))
                },
            )

        /**
         * Skip the currently-offered update for an `update.*` entity. The entity stays
         * in the "available" state but the user has expressed "don't pester me about
         * this version"; HA hides it from default views until a newer version arrives
         * or [clearSkippedUpdate] is called.
         */
        fun skipUpdate(target: EntityId): ServiceCall =
            ServiceCall(target, "skip", JsonObject(emptyMap()))

        /** Inverse of [skipUpdate] — re-surface a skipped update so it can be installed. */
        fun clearSkippedUpdate(target: EntityId): ServiceCall =
            ServiceCall(target, "clear_skipped", JsonObject(emptyMap()))

        fun tapAction(target: EntityId, isOn: Boolean): ServiceCall = when (target.domain) {
            Domain.LIGHT, Domain.FAN -> ServiceCall(
                target,
                if (isOn) "turn_off" else "turn_on",
                JsonObject(emptyMap()),
            )
            // For covers, `isOn` here means "currently open". Toggle to the opposite end of
            // travel — close if open, open if closed/stopped/in-motion. (Sending open_cover
            // while the cover is already opening is a no-op on HA's side.)
            Domain.COVER -> ServiceCall(target, if (isOn) "close_cover" else "open_cover", JsonObject(emptyMap()))
            // Valve: same dispatch shape as cover, parallel service names.
            Domain.VALVE -> ServiceCall(target, if (isOn) "close_valve" else "open_valve", JsonObject(emptyMap()))
            Domain.MEDIA_PLAYER -> ServiceCall(target, "media_play_pause", JsonObject(emptyMap()))
            // Generic on/off — switch.foo, input_boolean.foo, automation.foo, humidifier.foo,
            // climate.foo, water_heater.foo all use the same turn_on/turn_off pair. For
            // climate this restores the previous HVAC mode (HA remembers it across cycles).
            Domain.SWITCH, Domain.INPUT_BOOLEAN, Domain.AUTOMATION,
            Domain.HUMIDIFIER, Domain.CLIMATE, Domain.WATER_HEATER -> ServiceCall(
                target,
                if (isOn) "turn_off" else "turn_on",
                JsonObject(emptyMap()),
            )
            // Vacuum: tap when running sends it back to base; tap when docked starts it.
            // Pause/stop need a dedicated UI affordance; for the simple tap-toggle this
            // is the natural "send the robot somewhere" intent.
            Domain.VACUUM -> ServiceCall(
                target,
                if (isOn) "return_to_base" else "start",
                JsonObject(emptyMap()),
            )
            // Robot lawn mower — same intent as vacuum's tap-toggle: send it out
            // when docked, dock it when active. State semantics match (`mowing` =
            // active, `docked` = parked); the dedicated card exposes a finer
            // PAUSE option that this minimal tap doesn't.
            Domain.LAWN_MOWER -> ServiceCall(
                target,
                if (isOn) "dock" else "start_mowing",
                JsonObject(emptyMap()),
            )
            // Lock entity — `isOn` here means "unlocked". Tap to flip: lock if unlocked,
            // unlock if locked.
            Domain.LOCK -> ServiceCall(
                target,
                if (isOn) "lock" else "unlock",
                JsonObject(emptyMap()),
            )
            // Action entities — there's no "off" service. Tap always fires the trigger,
            // regardless of any (mostly meaningless) isOn state. Buttons use `press`,
            // scenes / scripts use `turn_on` to activate.
            Domain.SCENE, Domain.SCRIPT -> ServiceCall(target, "turn_on", JsonObject(emptyMap()))
            Domain.BUTTON, Domain.INPUT_BUTTON -> ServiceCall(target, "press", JsonObject(emptyMap()))
            // Sensors are read-only — tapToggle shouldn't reach them (EntityCard skips the
            // tap modifier for sensor domains). Defensive: emit update_entity so any
            // accidental dispatch is at least a no-op refresh.
            Domain.SENSOR, Domain.BINARY_SENSOR -> ServiceCall(
                target,
                "update_entity",
                JsonObject(emptyMap()),
            )
            // Number / input_number — there's no "toggle" semantics. Tap is mostly
            // dead code on these entities since the wheel does the work. Refresh.
            Domain.NUMBER, Domain.INPUT_NUMBER -> ServiceCall(
                target,
                "update_entity",
                JsonObject(emptyMap()),
            )
            // Select entities — tap on the card opens the picker rather than calling
            // a service. Defensive no-op refresh keeps the dispatch path safe if any
            // caller ever hits this branch.
            Domain.SELECT, Domain.INPUT_SELECT -> ServiceCall(
                target,
                "update_entity",
                JsonObject(emptyMap()),
            )
            // Helper-only domains — Helpers screen dispatches the real
            // services (counter.increment, timer.start, etc.); the card
            // stack never reaches tapAction for these so this is purely
            // defensive.
            Domain.COUNTER, Domain.TIMER,
            Domain.INPUT_TEXT, Domain.INPUT_DATETIME -> ServiceCall(
                target,
                "update_entity",
                JsonObject(emptyMap()),
            )
            // Update entities have a bespoke install/skip/clear flow surfaced
            // from the dedicated Updates screen — card-stack tap / wheel
            // dispatches never reach them today. Defensive no-op keeps every
            // generic dispatch path safe if a future surface routes here.
            Domain.UPDATE -> ServiceCall(target, "update_entity", JsonObject(emptyMap()))
            // Remote: tap toggles turn_on/turn_off. For activity hubs (Harmony) the
            // RemotePanel's activity chips are the real path; for learned-command
            // blasters the user wires custom buttons. Tap on the card itself just
            // flips the integration listener.
            Domain.REMOTE -> ServiceCall(
                target,
                if (isOn) "turn_off" else "turn_on",
                JsonObject(emptyMap()),
            )
            // Alarm: card tap is intentionally inert. Arming and disarming run
            // through the AlarmPanel's labelled chips so the action that lands
            // on HA is unambiguous (an accidental tap dispatching alarm_disarm
            // would be the worst possible 'on/off' surprise). Defensive no-op
            // refresh.
            Domain.ALARM_CONTROL_PANEL -> ServiceCall(
                target,
                "update_entity",
                JsonObject(emptyMap()),
            )
            // Person / weather are read-only — EntityCard skips the tap modifier for
            // these sensor domains. Defensive update_entity no-op refresh.
            Domain.PERSON, Domain.WEATHER -> ServiceCall(
                target,
                "update_entity",
                JsonObject(emptyMap()),
            )
            // Catch-all domains with no archetype: Search routes their tap to a detail
            // toast, never here. Defensive no-op refresh keeps the dispatch total.
            Domain.OTHER -> ServiceCall(target, "update_entity", JsonObject(emptyMap()))
        }

        /**
         * Explicit on/off (not toggle) for switch-card entities — the wheel needs to set an
         * absolute state, not flip it. For media players we use `media_play`/`media_pause`
         * because HA's `media_play_pause` is the toggle equivalent; the explicit variants
         * give us deterministic behaviour from the wheel.
         */
        fun setSwitch(target: EntityId, on: Boolean): ServiceCall = when (target.domain) {
            Domain.LIGHT, Domain.FAN, Domain.HUMIDIFIER, Domain.CLIMATE, Domain.WATER_HEATER,
            Domain.SWITCH, Domain.INPUT_BOOLEAN, Domain.AUTOMATION, Domain.REMOTE -> ServiceCall(
                target,
                if (on) "turn_on" else "turn_off",
                JsonObject(emptyMap()),
            )
            Domain.VACUUM -> ServiceCall(
                target,
                if (on) "start" else "return_to_base",
                JsonObject(emptyMap()),
            )
            Domain.LAWN_MOWER -> ServiceCall(
                target,
                if (on) "start_mowing" else "dock",
                JsonObject(emptyMap()),
            )
            Domain.COVER -> ServiceCall(
                target,
                if (on) "open_cover" else "close_cover",
                JsonObject(emptyMap()),
            )
            Domain.VALVE -> ServiceCall(
                target,
                if (on) "open_valve" else "close_valve",
                JsonObject(emptyMap()),
            )
            Domain.NUMBER, Domain.INPUT_NUMBER -> ServiceCall(
                target,
                "set_value",
                buildJsonObject { put("value", JsonPrimitive(if (on) 100 else 0)) },
            )
            Domain.MEDIA_PLAYER -> ServiceCall(
                target,
                if (on) "media_play" else "media_pause",
                JsonObject(emptyMap()),
            )
            Domain.LOCK -> ServiceCall(
                target,
                if (on) "unlock" else "lock",
                JsonObject(emptyMap()),
            )
            // Action entities only have a "fire" service — there's no "off" equivalent for
            // a button press or a scene activation. The on-side of setSwitch is the fire
            // service; the off-side is a no-op (we just turn_on again, which is harmless).
            Domain.SCENE, Domain.SCRIPT -> ServiceCall(target, "turn_on", JsonObject(emptyMap()))
            Domain.BUTTON, Domain.INPUT_BUTTON -> ServiceCall(target, "press", JsonObject(emptyMap()))
            // Sensors are read-only — defensive update_entity no-op.
            Domain.SENSOR, Domain.BINARY_SENSOR -> ServiceCall(
                target,
                "update_entity",
                JsonObject(emptyMap()),
            )
            // Select — no on/off concept. Defensive no-op refresh.
            Domain.SELECT, Domain.INPUT_SELECT -> ServiceCall(
                target,
                "update_entity",
                JsonObject(emptyMap()),
            )
            // Helper-only — Helpers screen owns dispatch for these.
            Domain.COUNTER, Domain.TIMER,
            Domain.INPUT_TEXT, Domain.INPUT_DATETIME -> ServiceCall(
                target,
                "update_entity",
                JsonObject(emptyMap()),
            )
            // Update entities have a bespoke install/skip/clear flow surfaced
            // from the dedicated Updates screen — card-stack tap / wheel
            // dispatches never reach them today. Defensive no-op keeps every
            // generic dispatch path safe if a future surface routes here.
            Domain.UPDATE -> ServiceCall(target, "update_entity", JsonObject(emptyMap()))
            // Alarm: explicit setSwitch has no sensible mapping; routed
            // through the AlarmPanel chips instead.
            Domain.ALARM_CONTROL_PANEL -> ServiceCall(
                target,
                "update_entity",
                JsonObject(emptyMap()),
            )
            // Person / weather are read-only — no on/off concept. Defensive no-op.
            Domain.PERSON, Domain.WEATHER -> ServiceCall(
                target,
                "update_entity",
                JsonObject(emptyMap()),
            )
            // Catch-all domains with no archetype: no on/off. Defensive no-op refresh.
            Domain.OTHER -> ServiceCall(target, "update_entity", JsonObject(emptyMap()))
        }

        /**
         * Climate / water_heater target-temperature setter. The VM converts the wheel's
         * 0..100 percent to a temperature using the entity's [EntityState.minRaw] /
         * [EntityState.maxRaw] range and calls this helper with the resolved value in
         * the entity's native temperature unit (°C or °F — HA picks based on the
         * user's HA config, we don't convert). Rounded to 1 decimal because most
         * thermostats won't accept finer resolution and 21.3 → 21.27 is just noise.
         *
         * Both domains use the same `set_temperature` service name and the same
         * `temperature` data field, so no domain-specific dispatch is needed here.
         */
        fun setTemperature(target: EntityId, temperature: Double): ServiceCall {
            val rounded = (Math.round(temperature * 10.0) / 10.0)
            return ServiceCall(
                target,
                "set_temperature",
                buildJsonObject { put("temperature", JsonPrimitive(rounded)) },
            )
        }

        /**
         * Climate range-mode setter — used when the thermostat advertises
         * `TARGET_TEMPERATURE_RANGE`. HA's `set_temperature` accepts a
         * `target_temp_low` + `target_temp_high` pair instead of a single value.
         */
        fun setTemperatureRange(target: EntityId, low: Double, high: Double): ServiceCall {
            val lo = (Math.round(low * 10.0) / 10.0)
            val hi = (Math.round(high * 10.0) / 10.0)
            return ServiceCall(
                target,
                "set_temperature",
                buildJsonObject {
                    put("target_temp_low", JsonPrimitive(lo))
                    put("target_temp_high", JsonPrimitive(hi))
                },
            )
        }

        /** Climate `set_hvac_mode` with one of the modes from `hvac_modes`. */
        fun setHvacMode(target: EntityId, mode: String): ServiceCall = ServiceCall(
            target,
            "set_hvac_mode",
            buildJsonObject { put("hvac_mode", JsonPrimitive(mode)) },
        )

        /** Climate `set_fan_mode`. */
        fun setFanMode(target: EntityId, mode: String): ServiceCall = ServiceCall(
            target,
            "set_fan_mode",
            buildJsonObject { put("fan_mode", JsonPrimitive(mode)) },
        )

        /** Climate `set_preset_mode` — eco / away / boost / comfort / sleep / etc. */
        fun setPresetMode(target: EntityId, mode: String): ServiceCall = ServiceCall(
            target,
            "set_preset_mode",
            buildJsonObject { put("preset_mode", JsonPrimitive(mode)) },
        )

        /** Water heater `set_operation_mode` (eco / electric / heat_pump / off …). */
        fun setOperationMode(target: EntityId, mode: String): ServiceCall = ServiceCall(
            target,
            "set_operation_mode",
            buildJsonObject { put("operation_mode", JsonPrimitive(mode)) },
        )

        /** Fan `oscillate` toggle. HA's service takes the new state as a bool. */
        fun fanOscillate(target: EntityId, oscillating: Boolean): ServiceCall = ServiceCall(
            target,
            "oscillate",
            buildJsonObject { put("oscillating", JsonPrimitive(oscillating)) },
        )

        /** Fan `set_direction` — "forward" or "reverse". HA rejects anything else. */
        fun fanSetDirection(target: EntityId, direction: String): ServiceCall = ServiceCall(
            target,
            "set_direction",
            buildJsonObject { put("direction", JsonPrimitive(direction)) },
        )

        /** Remote `send_command` — fires a learned IR / RF command by name.
         *  Optional `device` slot when the integration scopes commands per
         *  sub-device (e.g. Broadlink `device: "tv"`, `command: "power"`).
         *  Pass null for `device` when the command lives at the remote root. */
        fun remoteSendCommand(
            target: EntityId,
            command: String,
            device: String? = null,
        ): ServiceCall = ServiceCall(
            target,
            "send_command",
            buildJsonObject {
                put("command", JsonPrimitive(command))
                if (device != null) put("device", JsonPrimitive(device))
            },
        )

        /** Remote `turn_on` activity — used by Harmony Hub / activity remotes
         *  to switch to a named activity (Watch TV, Listen to Music, etc.). */
        fun remoteActivate(target: EntityId, activity: String): ServiceCall = ServiceCall(
            target,
            "turn_on",
            buildJsonObject { put("activity", JsonPrimitive(activity)) },
        )

        /**
         * Vacuum command dispatch. Maps a [VacuumAction] to the appropriate HA
         * service. All services take no data beyond the entity target.
         */
        fun vacuumCommand(target: EntityId, action: VacuumAction): ServiceCall =
            ServiceCall(
                target,
                when (action) {
                    VacuumAction.START -> "start"
                    VacuumAction.PAUSE -> "pause"
                    VacuumAction.STOP -> "stop"
                    VacuumAction.RETURN_TO_BASE -> "return_to_base"
                    VacuumAction.LOCATE -> "locate"
                    VacuumAction.CLEAN_SPOT -> "clean_spot"
                },
                JsonObject(emptyMap()),
            )

        /** Vacuum `set_fan_speed` with one of the strings from `fan_speed_list`. */
        fun vacuumSetFanSpeed(target: EntityId, fanSpeed: String): ServiceCall =
            ServiceCall(
                target,
                "set_fan_speed",
                buildJsonObject { put("fan_speed", JsonPrimitive(fanSpeed)) },
            )

        /** Lawn-mower command dispatch — start_mowing / pause / dock. */
        fun lawnMowerCommand(target: EntityId, action: LawnMowerAction): ServiceCall =
            ServiceCall(
                target,
                when (action) {
                    LawnMowerAction.START_MOWING -> "start_mowing"
                    LawnMowerAction.PAUSE -> "pause"
                    LawnMowerAction.DOCK -> "dock"
                },
                JsonObject(emptyMap()),
            )

        /**
         * Lock with optional code. HA's `lock.lock` / `lock.unlock` services
         * accept an optional `code` field; integrations that require a code
         * (Yale, Schlage Encode) error out with `code_required` when it's
         * absent. Pass `code` = null for locks that don't require it.
         */
        fun lockSet(target: EntityId, lock: Boolean, code: String? = null): ServiceCall =
            ServiceCall(
                target,
                if (lock) "lock" else "unlock",
                if (code.isNullOrBlank()) {
                    JsonObject(emptyMap())
                } else {
                    buildJsonObject { put("code", JsonPrimitive(code)) }
                },
            )

        /** Valve `set_valve_position` (0..100, closed → open). */
        fun valveSetPosition(target: EntityId, position: Int): ServiceCall =
            ServiceCall(
                target,
                "set_valve_position",
                buildJsonObject { put("position", JsonPrimitive(position.coerceIn(0, 100))) },
            )

        /** Valve `stop_valve` — stops a valve mid-travel. */
        fun valveStop(target: EntityId): ServiceCall =
            ServiceCall(target, "stop_valve", JsonObject(emptyMap()))

        /** Cover `open_cover_tilt` — tilts the slats fully open. Gated on the
         *  cover's OPEN_TILT supported_features bit. */
        fun coverOpenTilt(target: EntityId): ServiceCall =
            ServiceCall(target, "open_cover_tilt", JsonObject(emptyMap()))

        /** Cover `close_cover_tilt` — tilts the slats fully closed. Gated on the
         *  cover's CLOSE_TILT supported_features bit. */
        fun coverCloseTilt(target: EntityId): ServiceCall =
            ServiceCall(target, "close_cover_tilt", JsonObject(emptyMap()))

        /** Cover `stop_cover_tilt` — halts tilt motion mid-travel. Gated on the
         *  cover's STOP_TILT supported_features bit. */
        fun coverStopTilt(target: EntityId): ServiceCall =
            ServiceCall(target, "stop_cover_tilt", JsonObject(emptyMap()))

        /** Cover `set_cover_tilt_position` (0..100, closed slats → open slats).
         *  Gated on the cover's SET_TILT_POSITION supported_features bit. */
        fun coverSetTiltPosition(target: EntityId, position: Int): ServiceCall =
            ServiceCall(
                target,
                "set_cover_tilt_position",
                buildJsonObject { put("tilt_position", JsonPrimitive(position.coerceIn(0, 100))) },
            )

        /** Humidifier `set_mode` — selects one of the strings from `available_modes`
         *  (e.g. "normal" / "eco" / "away" / "boost" / "sleep"). Gated on the
         *  humidifier's MODES supported_features bit. */
        fun humidifierSetMode(target: EntityId, mode: String): ServiceCall =
            ServiceCall(
                target,
                "set_mode",
                buildJsonObject { put("mode", JsonPrimitive(mode)) },
            )

        /** Media-player shuffle toggle. */
        fun mediaShuffleSet(target: EntityId, shuffle: Boolean): ServiceCall =
            ServiceCall(
                target,
                "shuffle_set",
                buildJsonObject { put("shuffle", JsonPrimitive(shuffle)) },
            )

        /**
         * Media-player repeat setter. HA values: "off" / "one" / "all". The card
         * cycles through these three so a single button suffices instead of three.
         */
        fun mediaRepeatSet(target: EntityId, repeat: String): ServiceCall =
            ServiceCall(
                target,
                "repeat_set",
                buildJsonObject { put("repeat", JsonPrimitive(repeat)) },
            )

        /** Media-player `select_source` — switches the active input. */
        fun mediaSelectSource(target: EntityId, source: String): ServiceCall =
            ServiceCall(
                target,
                "select_source",
                buildJsonObject { put("source", JsonPrimitive(source)) },
            )

        /**
         * Alarm-control-panel action dispatch. Maps an [AlarmAction] to the
         * matching HA service. Disarm always requires a code when the alarm
         * has `code_format != null`; arm services additionally require one
         * when `code_arm_required` is true. Pass `code` = null only when the
         * caller has already confirmed the alarm accepts code-less calls.
         */
        fun alarmAction(target: EntityId, action: AlarmAction, code: String? = null): ServiceCall =
            ServiceCall(
                target,
                when (action) {
                    AlarmAction.DISARM -> "alarm_disarm"
                    AlarmAction.ARM_AWAY -> "alarm_arm_away"
                    AlarmAction.ARM_HOME -> "alarm_arm_home"
                    AlarmAction.ARM_NIGHT -> "alarm_arm_night"
                    AlarmAction.ARM_VACATION -> "alarm_arm_vacation"
                    AlarmAction.ARM_CUSTOM_BYPASS -> "alarm_arm_custom_bypass"
                    AlarmAction.TRIGGER -> "alarm_trigger"
                },
                if (code.isNullOrBlank()) {
                    JsonObject(emptyMap())
                } else {
                    buildJsonObject { put("code", JsonPrimitive(code)) }
                },
            )
    }
}

/** Vacuum command set surfaced on VacuumCard. */
enum class VacuumAction {
    START, PAUSE, STOP, RETURN_TO_BASE, LOCATE, CLEAN_SPOT,
}

/** Lawn-mower command set surfaced on LawnMowerCard. */
enum class LawnMowerAction {
    START_MOWING, PAUSE, DOCK,
}

/** Alarm-control-panel command set surfaced on AlarmPanel. */
enum class AlarmAction {
    DISARM, ARM_AWAY, ARM_HOME, ARM_NIGHT, ARM_VACATION, ARM_CUSTOM_BYPASS, TRIGGER,
}
