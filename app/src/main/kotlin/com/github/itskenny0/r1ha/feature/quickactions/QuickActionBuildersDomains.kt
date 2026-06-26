package com.github.itskenny0.r1ha.feature.quickactions

import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import com.github.itskenny0.r1ha.core.ha.VacuumAction
import com.github.itskenny0.r1ha.core.util.optionLabel
import com.github.itskenny0.r1ha.ui.components.attrBoolean
import com.github.itskenny0.r1ha.ui.components.attrInt
import com.github.itskenny0.r1ha.ui.components.attrString
import com.github.itskenny0.r1ha.ui.components.attrStringList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Per-domain Quick Sheet builders for the long-press surface. Each object claims one HA
 * domain and turns the entity's current state into the chip groups [QuickActionSheet]
 * renders, mirroring the service names, attribute reads, and `supported_features` gating of
 * the matching control panel in [com.github.itskenny0.r1ha.ui.components] (CoverPanel,
 * FanPanel, VacuumPanel, etc.).
 *
 * Conventions used here (shared with the light/climate/media builders):
 *  - Attribute reads go through the shared [attrString] / [attrStringList] / [attrBoolean] /
 *    [attrInt] extensions on [EntityState], so a builder works off the verbatim HA
 *    `attributesJson` exactly like the cover / humidifier panels do, never depending on the
 *    repository having parsed a value into a typed field.
 *  - `supported_features` has two sources. Domains the repository populates into the typed
 *    [EntityState.supportedFeatures] / [EntityState.vacuumSupportedFeatures] (fan / vacuum /
 *    valve) gate through the typed `hasFanFeature` / `hasVacuumFeature` / `hasFeature`
 *    helpers; domains it does not parse (cover / humidifier / lock) read the raw bitmask off
 *    the attributes via [rawHasFeature], matching the private reader EntityPanels uses.
 *  - A terminal one-shot command (OPEN, TURN OFF, LOCK, START) fires and dismisses the sheet
 *    via [fireOnce]; a stateful selection chip (preset, mode, direction, fan-speed, activity,
 *    away) fires but leaves the sheet open via [fireSticky] so the selection highlight moves
 *    in place and the user can pick again. This is the same split the light/climate/media
 *    builders use and the one [QuickActionContext.dismiss] documents.
 */

/** Empty `service_data` payload, shared by the zero-arg commands (open_cover, turn_on, …). */
private val NO_DATA: JsonObject = JsonObject(emptyMap())

/**
 * HA `LockEntityFeature.OPEN` bit. [EntityState] doesn't ship a `LockFeature` constants
 * object (the lock panel only does lock/unlock + a keypad), so the OPEN gate is defined here.
 * The repository doesn't parse lock `supported_features` into the typed field either, so it is
 * read off the raw attributes the same way cover / humidifier are.
 */
private const val LOCK_FEATURE_OPEN = 1

/**
 * `supported_features` read straight from the entity's raw attributes, for domains the
 * repository doesn't parse into [EntityState.supportedFeatures] (cover / humidifier / lock).
 * Returns 0 when absent, which [rawHasFeature] treats as "forgive the omission".
 */
private fun EntityState.rawSupportedFeatures(): Int = attrInt("supported_features") ?: 0

/**
 * True when [bit] is set in the raw `supported_features`, or when the integration advertised
 * no bitmask at all (== 0): the same forgive-an-omission rule the typed
 * [EntityState.hasFeature] helpers use.
 */
private fun EntityState.rawHasFeature(bit: Int): Boolean {
    val sf = rawSupportedFeatures()
    return sf == 0 || (sf and bit) != 0
}

/** Build the onFire lambda for a terminal command: dispatch [call], then close the sheet. */
private fun QuickActionContext.fireOnce(call: ServiceCall): () -> Unit = {
    onEntityCall(call)
    dismiss()
}

/** Build the onFire lambda for a stateful selection chip: dispatch [call], leave sheet open. */
private fun QuickActionContext.fireSticky(call: ServiceCall): () -> Unit = {
    onEntityCall(call)
}

// ── Cover ────────────────────────────────────────────────────────────────────────────────

/**
 * Cover quick actions: OPEN / STOP / CLOSE primary commands, favourite-position chips from the
 * card's [EntityOverride.favoritePositions], and tilt OPEN / CLOSE gated on the cover's tilt
 * `supported_features` bits exactly as CoverPanel does (raw bitmask, and only when an explicit
 * tilt bit is advertised so a plain roller blind doesn't sprout a dead tilt row).
 */
object CoverQuickActions : QuickActionBuilder {
    override fun supports(state: EntityState): Boolean = state.id.domain == Domain.COVER

    override fun build(ctx: QuickActionContext): List<QuickActionGroup> {
        val state = ctx.state
        val groups = mutableListOf<QuickActionGroup>()

        groups += QuickActionGroup(
            title = null,
            actions = listOf(
                QuickAction(
                    id = "cover.open",
                    label = "OPEN",
                    onFire = ctx.fireOnce(ServiceCall(state.id, "open_cover", NO_DATA)),
                ),
                QuickAction(
                    id = "cover.stop",
                    label = "STOP",
                    onFire = ctx.fireOnce(ServiceCall(state.id, "stop_cover", NO_DATA)),
                ),
                QuickAction(
                    id = "cover.close",
                    label = "CLOSE",
                    onFire = ctx.fireOnce(ServiceCall(state.id, "close_cover", NO_DATA)),
                ),
            ),
        )

        // Tilt: require an explicit tilt bit (not the forgive-omission default) so covers
        // without slats stay tilt-free, mirroring CoverPanel's `anyTiltBit` guard.
        val sf = state.rawSupportedFeatures()
        val anyTiltBit = sf != 0 && (sf and (
            EntityState.CoverFeature.OPEN_TILT or
                EntityState.CoverFeature.CLOSE_TILT or
                EntityState.CoverFeature.STOP_TILT or
                EntityState.CoverFeature.SET_TILT_POSITION
            )) != 0
        if (anyTiltBit) {
            val tilt = mutableListOf<QuickAction>()
            if ((sf and EntityState.CoverFeature.OPEN_TILT) != 0) {
                tilt += QuickAction(
                    id = "cover.tilt_open",
                    label = "OPEN",
                    onFire = ctx.fireOnce(ServiceCall.coverOpenTilt(state.id)),
                )
            }
            if ((sf and EntityState.CoverFeature.CLOSE_TILT) != 0) {
                tilt += QuickAction(
                    id = "cover.tilt_close",
                    label = "CLOSE",
                    onFire = ctx.fireOnce(ServiceCall.coverCloseTilt(state.id)),
                )
            }
            if (tilt.isNotEmpty()) groups += QuickActionGroup(title = "TILT", actions = tilt)
        }

        favoritePositionActions(ctx, "cover") { pos ->
            ServiceCall(
                state.id,
                "set_cover_position",
                buildJsonObject { put("position", JsonPrimitive(pos)) },
            )
        }?.let { groups += it }

        return groups
    }
}

// ── Fan ──────────────────────────────────────────────────────────────────────────────────

/**
 * Fan quick actions: TURN ON / OFF, preset chips from `preset_modes`, an oscillate toggle, and
 * FORWARD / REVERSE direction chips. Preset / oscillate / direction are gated on the fan's
 * `supported_features` exactly as FanPanel (typed [EntityState.hasFanFeature], with the same
 * "attribute present or bit set" allowance for oscillate / direction).
 */
object FanQuickActions : QuickActionBuilder {
    override fun supports(state: EntityState): Boolean = state.id.domain == Domain.FAN

    override fun build(ctx: QuickActionContext): List<QuickActionGroup> {
        val state = ctx.state
        val groups = mutableListOf<QuickActionGroup>()

        groups += QuickActionGroup(
            title = null,
            actions = listOf(
                QuickAction(
                    id = "fan.on",
                    label = "TURN ON",
                    selected = state.isOn,
                    onFire = ctx.fireOnce(ServiceCall(state.id, "turn_on", NO_DATA)),
                ),
                QuickAction(
                    id = "fan.off",
                    label = "TURN OFF",
                    selected = !state.isOn,
                    onFire = ctx.fireOnce(ServiceCall(state.id, "turn_off", NO_DATA)),
                ),
            ),
        )

        val presets = state.attrStringList("preset_modes")
        if (presets.isNotEmpty() && state.hasFanFeature(EntityState.FanFeature.PRESET_MODE)) {
            val current = state.attrString("preset_mode")
            groups += QuickActionGroup(
                title = "PRESET",
                actions = presets.map { mode ->
                    QuickAction(
                        id = "fan.preset.$mode",
                        label = optionLabel(mode),
                        selected = current.equals(mode, ignoreCase = true),
                        onFire = ctx.fireSticky(ServiceCall.setPresetMode(state.id, mode)),
                    )
                },
            )
        }

        val hasOscillate = state.attrBoolean("oscillating") != null ||
            state.hasFanFeature(EntityState.FanFeature.OSCILLATE)
        if (hasOscillate) {
            val on = state.attrBoolean("oscillating") == true
            groups += QuickActionGroup(
                title = null,
                actions = listOf(
                    QuickAction(
                        id = "fan.oscillate",
                        label = if (on) "OSCILLATING" else "OSCILLATE",
                        selected = on,
                        onFire = ctx.fireSticky(ServiceCall.fanOscillate(state.id, !on)),
                    ),
                ),
            )
        }

        val hasDirection = state.attrString("direction") != null ||
            state.hasFanFeature(EntityState.FanFeature.DIRECTION)
        if (hasDirection) {
            val current = state.attrString("direction")?.lowercase()
            groups += QuickActionGroup(
                title = "DIRECTION",
                actions = listOf(
                    QuickAction(
                        id = "fan.dir.forward",
                        label = "FORWARD",
                        selected = current == "forward",
                        onFire = ctx.fireSticky(ServiceCall.fanSetDirection(state.id, "forward")),
                    ),
                    QuickAction(
                        id = "fan.dir.reverse",
                        label = "REVERSE",
                        selected = current == "reverse",
                        onFire = ctx.fireSticky(ServiceCall.fanSetDirection(state.id, "reverse")),
                    ),
                ),
            )
        }

        return groups
    }
}

// ── Lock ─────────────────────────────────────────────────────────────────────────────────

/**
 * Lock quick actions: LOCK / UNLOCK, plus OPEN (unlatch) when the lock advertises HA's OPEN
 * `supported_features` bit. When the card carries a client-side PIN gate
 * ([EntityOverride.requirePinToUnlock] == true), the UNLOCK / OPEN chips are omitted and only
 * LOCK remains: unlatching goes through the PIN keypad path on the card itself (LockPanel),
 * not the Quick Sheet, so a stray long-press chip can't bypass the gate.
 */
object LockQuickActions : QuickActionBuilder {
    override fun supports(state: EntityState): Boolean = state.id.domain == Domain.LOCK

    override fun build(ctx: QuickActionContext): List<QuickActionGroup> {
        val state = ctx.state
        val pinGated = ctx.override.requirePinToUnlock == true

        val actions = mutableListOf<QuickAction>()
        actions += QuickAction(
            id = "lock.lock",
            label = "LOCK",
            onFire = ctx.fireOnce(ServiceCall.lockSet(state.id, lock = true)),
        )
        // PIN-gated locks surface only LOCK here; the keypad on the card owns unlock / open.
        if (!pinGated) {
            actions += QuickAction(
                id = "lock.unlock",
                label = "UNLOCK",
                onFire = ctx.fireOnce(ServiceCall.lockSet(state.id, lock = false)),
            )
            // OPEN actuates the latch; an uncommon, optional capability, so require the bit to
            // be explicitly set rather than forgiving an absent bitmask.
            if ((state.rawSupportedFeatures() and LOCK_FEATURE_OPEN) != 0) {
                actions += QuickAction(
                    id = "lock.open",
                    label = "OPEN",
                    onFire = ctx.fireOnce(ServiceCall(state.id, "open", NO_DATA)),
                )
            }
        }

        return listOf(QuickActionGroup(title = null, actions = actions))
    }
}

// ── Vacuum ───────────────────────────────────────────────────────────────────────────────

/**
 * Vacuum quick actions: START / PAUSE / RETURN / LOCATE / CLEAN SPOT, each gated on the
 * vacuum's `supported_features` via [EntityState.hasVacuumFeature] exactly as VacuumPanel, plus
 * fan-speed chips from `fan_speed_list`.
 */
object VacuumQuickActions : QuickActionBuilder {
    override fun supports(state: EntityState): Boolean = state.id.domain == Domain.VACUUM

    override fun build(ctx: QuickActionContext): List<QuickActionGroup> {
        val state = ctx.state
        val groups = mutableListOf<QuickActionGroup>()

        val commands = mutableListOf<QuickAction>()
        if (state.hasVacuumFeature(EntityState.VacuumFeature.START)) {
            commands += QuickAction(
                id = "vacuum.start",
                label = "START",
                onFire = ctx.fireOnce(ServiceCall.vacuumCommand(state.id, VacuumAction.START)),
            )
        }
        if (state.hasVacuumFeature(EntityState.VacuumFeature.PAUSE)) {
            commands += QuickAction(
                id = "vacuum.pause",
                label = "PAUSE",
                onFire = ctx.fireOnce(ServiceCall.vacuumCommand(state.id, VacuumAction.PAUSE)),
            )
        }
        if (state.hasVacuumFeature(EntityState.VacuumFeature.RETURN_HOME)) {
            commands += QuickAction(
                id = "vacuum.return",
                label = "RETURN",
                onFire = ctx.fireOnce(ServiceCall.vacuumCommand(state.id, VacuumAction.RETURN_TO_BASE)),
            )
        }
        if (state.hasVacuumFeature(EntityState.VacuumFeature.LOCATE)) {
            commands += QuickAction(
                id = "vacuum.locate",
                label = "LOCATE",
                onFire = ctx.fireOnce(ServiceCall.vacuumCommand(state.id, VacuumAction.LOCATE)),
            )
        }
        if (state.hasVacuumFeature(EntityState.VacuumFeature.CLEAN_SPOT)) {
            commands += QuickAction(
                id = "vacuum.spot",
                label = "CLEAN SPOT",
                onFire = ctx.fireOnce(ServiceCall.vacuumCommand(state.id, VacuumAction.CLEAN_SPOT)),
            )
        }
        if (commands.isNotEmpty()) groups += QuickActionGroup(title = null, actions = commands)

        val speeds = state.attrStringList("fan_speed_list")
        if (speeds.isNotEmpty() && state.hasVacuumFeature(EntityState.VacuumFeature.FAN_SPEED)) {
            val current = state.attrString("fan_speed")
            groups += QuickActionGroup(
                title = "FAN",
                actions = speeds.map { speed ->
                    QuickAction(
                        id = "vacuum.speed.$speed",
                        label = optionLabel(speed),
                        selected = current.equals(speed, ignoreCase = true),
                        onFire = ctx.fireSticky(ServiceCall.vacuumSetFanSpeed(state.id, speed)),
                    )
                },
            )
        }

        return groups
    }
}

// ── Remote ───────────────────────────────────────────────────────────────────────────────

/**
 * Remote quick actions: one chip per `activity_list` entry, firing `remote.turn_on` with the
 * activity name (Harmony-style activity remotes). Learned-command blasters expose no activity
 * list, so the builder yields no groups: the Quick Sheet collapses straight to the manage row.
 */
object RemoteQuickActions : QuickActionBuilder {
    override fun supports(state: EntityState): Boolean = state.id.domain == Domain.REMOTE

    override fun build(ctx: QuickActionContext): List<QuickActionGroup> {
        val state = ctx.state
        val activities = state.attrStringList("activity_list")
        if (activities.isEmpty()) return emptyList()
        val current = state.attrString("current_activity")
        return listOf(
            QuickActionGroup(
                title = "ACTIVITY",
                actions = activities.map { activity ->
                    QuickAction(
                        id = "remote.activity.$activity",
                        label = optionLabel(activity),
                        selected = current.equals(activity, ignoreCase = true),
                        onFire = ctx.fireSticky(ServiceCall.remoteActivate(state.id, activity)),
                    )
                },
            ),
        )
    }
}

// ── Siren ────────────────────────────────────────────────────────────────────────────────

/**
 * Siren quick actions: TURN ON / OFF, plus a chip per `available_tones` entry that fires
 * `siren.turn_on` with the chosen tone.
 */
object SirenQuickActions : QuickActionBuilder {
    override fun supports(state: EntityState): Boolean = state.id.domain == Domain.SIREN

    override fun build(ctx: QuickActionContext): List<QuickActionGroup> {
        val state = ctx.state
        val groups = mutableListOf<QuickActionGroup>()

        groups += QuickActionGroup(
            title = null,
            actions = listOf(
                QuickAction(
                    id = "siren.on",
                    label = "TURN ON",
                    selected = state.isOn,
                    onFire = ctx.fireOnce(ServiceCall(state.id, "turn_on", NO_DATA)),
                ),
                QuickAction(
                    id = "siren.off",
                    label = "TURN OFF",
                    selected = !state.isOn,
                    onFire = ctx.fireOnce(ServiceCall(state.id, "turn_off", NO_DATA)),
                ),
            ),
        )

        val tones = state.attrStringList("available_tones")
        if (tones.isNotEmpty()) {
            groups += QuickActionGroup(
                title = "TONE",
                actions = tones.map { tone ->
                    QuickAction(
                        id = "siren.tone.$tone",
                        label = optionLabel(tone),
                        onFire = ctx.fireOnce(
                            ServiceCall(
                                state.id,
                                "turn_on",
                                buildJsonObject { put("tone", JsonPrimitive(tone)) },
                            ),
                        ),
                    )
                },
            )
        }

        return groups
    }
}

// ── Valve ────────────────────────────────────────────────────────────────────────────────

/**
 * Valve quick actions: OPEN / STOP / CLOSE primary commands, plus favourite-position chips from
 * [EntityOverride.favoritePositions] when the valve advertises the SET_POSITION
 * `supported_features` bit (typed [EntityState.hasFeature], the source the repository populates
 * for valves).
 */
object ValveQuickActions : QuickActionBuilder {
    override fun supports(state: EntityState): Boolean = state.id.domain == Domain.VALVE

    override fun build(ctx: QuickActionContext): List<QuickActionGroup> {
        val state = ctx.state
        val groups = mutableListOf<QuickActionGroup>()

        groups += QuickActionGroup(
            title = null,
            actions = listOf(
                QuickAction(
                    id = "valve.open",
                    label = "OPEN",
                    onFire = ctx.fireOnce(ServiceCall(state.id, "open_valve", NO_DATA)),
                ),
                QuickAction(
                    id = "valve.stop",
                    label = "STOP",
                    onFire = ctx.fireOnce(ServiceCall.valveStop(state.id)),
                ),
                QuickAction(
                    id = "valve.close",
                    label = "CLOSE",
                    onFire = ctx.fireOnce(ServiceCall(state.id, "close_valve", NO_DATA)),
                ),
            ),
        )

        if (state.hasFeature(EntityState.ValveFeature.SET_POSITION)) {
            favoritePositionActions(ctx, "valve") { pos ->
                ServiceCall.valveSetPosition(state.id, pos)
            }?.let { groups += it }
        }

        return groups
    }
}

// ── Water heater ─────────────────────────────────────────────────────────────────────────

/**
 * Water-heater quick actions: operation-mode chips from `operation_list` firing
 * `water_heater.set_operation_mode`, plus an AWAY toggle when the entity reports an
 * `away_mode` attribute (`water_heater.set_away_mode`).
 */
object WaterHeaterQuickActions : QuickActionBuilder {
    override fun supports(state: EntityState): Boolean = state.id.domain == Domain.WATER_HEATER

    override fun build(ctx: QuickActionContext): List<QuickActionGroup> {
        val state = ctx.state
        val groups = mutableListOf<QuickActionGroup>()

        val modes = state.attrStringList("operation_list")
        if (modes.isNotEmpty()) {
            val current = state.attrString("operation_mode")
            groups += QuickActionGroup(
                title = "MODE",
                actions = modes.map { mode ->
                    QuickAction(
                        id = "water_heater.mode.$mode",
                        label = optionLabel(mode),
                        selected = current.equals(mode, ignoreCase = true),
                        onFire = ctx.fireSticky(ServiceCall.setOperationMode(state.id, mode)),
                    )
                },
            )
        }

        // away_mode is reported as an on/off string in attributes; treat "on" / "true" as
        // engaged. The service schema, however, expects a BOOLEAN away_mode, so the toggle
        // sends the inverse boolean (a second tap clears it).
        val awayRaw = state.attrString("away_mode")
        if (awayRaw != null) {
            val awayOn = awayRaw.equals("on", ignoreCase = true) || awayRaw.equals("true", ignoreCase = true)
            groups += QuickActionGroup(
                title = null,
                actions = listOf(
                    QuickAction(
                        id = "water_heater.away",
                        label = "AWAY",
                        selected = awayOn,
                        onFire = ctx.fireSticky(
                            ServiceCall(
                                state.id,
                                "set_away_mode",
                                buildJsonObject {
                                    put("away_mode", JsonPrimitive(!awayOn))
                                },
                            ),
                        ),
                    ),
                ),
            )
        }

        return groups
    }
}

// ── Humidifier ───────────────────────────────────────────────────────────────────────────

/**
 * Humidifier quick actions: TURN ON / OFF, plus mode chips from `available_modes` firing
 * `humidifier.set_mode`, gated on the MODES `supported_features` bit (raw bitmask, as
 * HumidifierPanel reads it).
 */
object HumidifierQuickActions : QuickActionBuilder {
    override fun supports(state: EntityState): Boolean = state.id.domain == Domain.HUMIDIFIER

    override fun build(ctx: QuickActionContext): List<QuickActionGroup> {
        val state = ctx.state
        val groups = mutableListOf<QuickActionGroup>()

        groups += QuickActionGroup(
            title = null,
            actions = listOf(
                QuickAction(
                    id = "humidifier.on",
                    label = "TURN ON",
                    selected = state.isOn,
                    onFire = ctx.fireOnce(ServiceCall(state.id, "turn_on", NO_DATA)),
                ),
                QuickAction(
                    id = "humidifier.off",
                    label = "TURN OFF",
                    selected = !state.isOn,
                    onFire = ctx.fireOnce(ServiceCall(state.id, "turn_off", NO_DATA)),
                ),
            ),
        )

        val modes = state.attrStringList("available_modes")
        if (modes.isNotEmpty() && state.rawHasFeature(EntityState.HumidifierFeature.MODES)) {
            val current = state.attrString("mode")
            groups += QuickActionGroup(
                title = "MODE",
                actions = modes.map { mode ->
                    QuickAction(
                        id = "humidifier.mode.$mode",
                        label = optionLabel(mode),
                        selected = current.equals(mode, ignoreCase = true),
                        onFire = ctx.fireSticky(ServiceCall.humidifierSetMode(state.id, mode)),
                    )
                },
            )
        }

        return groups
    }
}

// ── Shared helpers ───────────────────────────────────────────────────────────────────────

/**
 * Build a "FAVOURITES" group from the card's [EntityOverride.favoritePositions], one "N%" chip
 * per value firing [callFor]. Returns null when the card has no favourites configured, so the
 * caller appends nothing rather than an empty titled group. [idPrefix] keeps the Compose chip
 * keys unique per domain.
 */
private fun favoritePositionActions(
    ctx: QuickActionContext,
    idPrefix: String,
    callFor: (Int) -> ServiceCall,
): QuickActionGroup? {
    val favorites = ctx.override.favoritePositions
    if (favorites.isEmpty()) return null
    return QuickActionGroup(
        title = "FAVOURITES",
        actions = favorites.map { pos ->
            QuickAction(
                id = "$idPrefix.fav.$pos",
                label = "$pos%",
                selected = ctx.state.percent == pos,
                onFire = ctx.fireOnce(callFor(pos)),
            )
        },
    )
}

/**
 * The extra per-domain builders registered alongside the light / climate / media set. Order is
 * irrelevant: every builder claims a distinct domain, so [buildQuickActions]'s first-match scan
 * resolves the same builder regardless of position.
 */
val extraDomainQuickActionBuilders: List<QuickActionBuilder> = listOf(
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
