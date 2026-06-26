package com.github.itskenny0.r1ha.feature.quickactions

import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.MediaTransport
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import com.github.itskenny0.r1ha.core.prefs.EntityOverride
import com.github.itskenny0.r1ha.core.util.optionLabel
import com.github.itskenny0.r1ha.ui.components.attrBoolean
import com.github.itskenny0.r1ha.ui.components.attrString
import com.github.itskenny0.r1ha.ui.components.attrStringList
import com.github.itskenny0.r1ha.ui.components.favoriteColorAction

/**
 * Quick-action builders for the three highest-traffic controllable domains: light,
 * climate and media_player. Each turns the entity's live [EntityState] into the
 * cluster of chips the Quick Sheet surfaces on a long-press.
 *
 * Every chip mirrors the exact service call, attribute read and `supported_features`
 * gate the matching panel in [com.github.itskenny0.r1ha.ui.components] already uses, so
 * the Quick Sheet can never offer a control the integration would reject:
 *
 *  - light  → [com.github.itskenny0.r1ha.ui.components.favoriteColorAction] (rgb_color),
 *             colour-temp / effect / on-off, all via `light.turn_on` / `light.turn_off`.
 *  - climate → HVAC / preset / fan chips, read from the raw attribute map and dispatched
 *             through [ServiceCall.setHvacMode] / [ServiceCall.setPresetMode] /
 *             [ServiceCall.setFanMode] (parity with `ClimatePanel`).
 *  - media  → transport / source / shuffle / repeat, gated on
 *             [EntityState.MediaPlayerFeature] bits exactly as `MediaExtrasPanel` and the
 *             media transport row gate them (parity with `hasMediaFeature`).
 *
 * Builders return only data, never touch Compose, and close over the
 * [QuickActionContext]'s `onEntityCall` to dispatch. Terminal one-shot toggles (a light's
 * ON / OFF) also call `dismiss`; adjustable mode chips leave the sheet open so the user
 * can keep tuning.
 */

/** Cap on effect chips so a bulb advertising dozens of effects can't flood the sheet. */
private const val MAX_LIGHT_EFFECT_CHIPS = 12

/** Light quick actions: favourite colours, colour-temp presets, effects, on/off. */
object LightQuickActions : QuickActionBuilder {
    override fun supports(state: EntityState): Boolean = state.id.domain == Domain.LIGHT

    override fun build(ctx: QuickActionContext): List<QuickActionGroup> {
        val state = ctx.state
        val groups = mutableListOf<QuickActionGroup>()

        // Favourite colours — one swatch chip per saved colour, firing the same
        // `light.turn_on { rgb_color: [r,g,b] }` call the card's swatch row uses.
        val favourites = ctx.override.favoriteColors
        if (favourites.isNotEmpty()) {
            groups += QuickActionGroup(
                title = "FAVOURITE COLOURS",
                actions = favourites.map { argb ->
                    QuickAction(
                        id = "light-colour-$argb",
                        label = "#" + Integer.toHexString(argb).takeLast(6).uppercase(),
                        glyph = "●", // ● filled swatch dot
                        accentArgb = argb,
                        onFire = { ctx.onEntityCall(favoriteColorAction(state, argb)) },
                    )
                },
            )
        }

        // Colour-temp presets — only for bulbs that advertise color_temp. Reuses the
        // curated kelvin set the customize dialog offers (WARM..DAY); a chip is selected
        // when its kelvin matches the bulb's current colour temperature.
        val supportsColorTemp = state.supportedColorModes.any { it.equals("color_temp", ignoreCase = true) }
        if (supportsColorTemp) {
            groups += QuickActionGroup(
                title = "COLOUR TEMP",
                actions = EntityOverride.LIGHT_CT_PRESETS.map { (label, kelvin) ->
                    QuickAction(
                        id = "light-ct-$kelvin",
                        label = label,
                        selected = state.colorTempK == kelvin,
                        onFire = { ctx.onEntityCall(ServiceCall.setLightColorTemp(state.id, kelvin)) },
                    )
                },
            )
        }

        // Effects — one chip per effect (capped), firing `light.turn_on { effect }`.
        val effects = state.effectList
        if (effects.isNotEmpty()) {
            groups += QuickActionGroup(
                title = "EFFECT",
                actions = effects.take(MAX_LIGHT_EFFECT_CHIPS).map { effect ->
                    QuickAction(
                        id = "light-fx-$effect",
                        label = optionLabel(effect),
                        selected = effect.equals(state.effect, ignoreCase = true),
                        onFire = { ctx.onEntityCall(ServiceCall.setLightEffect(state.id, effect)) },
                    )
                },
            )
        }

        // On / off — terminal one-shot toggles, so they fire and dismiss the sheet.
        groups += QuickActionGroup(
            title = "POWER",
            actions = listOf(
                QuickAction(
                    id = "light-on",
                    label = "TURN ON",
                    selected = state.isOn,
                    onFire = {
                        ctx.onEntityCall(ServiceCall.setSwitch(state.id, on = true))
                        ctx.dismiss()
                    },
                ),
                QuickAction(
                    id = "light-off",
                    label = "TURN OFF",
                    selected = !state.isOn,
                    onFire = {
                        ctx.onEntityCall(ServiceCall.setSwitch(state.id, on = false))
                        ctx.dismiss()
                    },
                ),
            ),
        )

        return groups
    }
}

/** Climate quick actions: HVAC mode, preset, fan mode. */
object ClimateQuickActions : QuickActionBuilder {
    override fun supports(state: EntityState): Boolean = state.id.domain == Domain.CLIMATE

    override fun build(ctx: QuickActionContext): List<QuickActionGroup> {
        val state = ctx.state
        val groups = mutableListOf<QuickActionGroup>()

        // HVAC modes — the entity's HA state IS the active mode, so a chip is selected
        // when it matches the raw state string. Fires `climate.set_hvac_mode`.
        val hvacModes = state.attrStringList("hvac_modes")
        if (hvacModes.isNotEmpty()) {
            groups += QuickActionGroup(
                title = "MODE",
                actions = hvacModes.map { mode ->
                    QuickAction(
                        id = "climate-hvac-$mode",
                        label = optionLabel(mode),
                        selected = mode.equals(state.rawState, ignoreCase = true),
                        onFire = { ctx.onEntityCall(ServiceCall.setHvacMode(state.id, mode)) },
                    )
                },
            )
        }

        // Presets — `climate.set_preset_mode`; selected against the `preset_mode` attr.
        val presetModes = state.attrStringList("preset_modes")
        if (presetModes.isNotEmpty()) {
            val currentPreset = state.attrString("preset_mode")
            groups += QuickActionGroup(
                title = "PRESET",
                actions = presetModes.map { preset ->
                    QuickAction(
                        id = "climate-preset-$preset",
                        label = optionLabel(preset),
                        selected = preset.equals(currentPreset, ignoreCase = true),
                        onFire = { ctx.onEntityCall(ServiceCall.setPresetMode(state.id, preset)) },
                    )
                },
            )
        }

        // Fan modes — `climate.set_fan_mode`; selected against the `fan_mode` attr.
        val fanModes = state.attrStringList("fan_modes")
        if (fanModes.isNotEmpty()) {
            val currentFan = state.attrString("fan_mode")
            groups += QuickActionGroup(
                title = "FAN",
                actions = fanModes.map { fan ->
                    QuickAction(
                        id = "climate-fan-$fan",
                        label = optionLabel(fan),
                        selected = fan.equals(currentFan, ignoreCase = true),
                        onFire = { ctx.onEntityCall(ServiceCall.setFanMode(state.id, fan)) },
                    )
                },
            )
        }

        return groups
    }
}

/** Media-player quick actions: transport, source, shuffle, repeat. */
object MediaQuickActions : QuickActionBuilder {
    override fun supports(state: EntityState): Boolean = state.id.domain == Domain.MEDIA_PLAYER

    override fun build(ctx: QuickActionContext): List<QuickActionGroup> {
        val state = ctx.state
        val groups = mutableListOf<QuickActionGroup>()

        // Transport — each chip gated on the same `supported_features` bit the media
        // transport row / MoreInfoSheet gate against. A non-zero bitmask with no
        // transport bits drops every chip and the whole group is omitted; a zero
        // (unknown) bitmask forgives the omission and shows them all.
        // [EntityState.hasMediaFeature] is strict (false when the bitmask is 0/unknown),
        // but the Quick Sheet forgives an unreported bitmask and shows the common transport
        // controls; a non-zero bitmask still gates precisely (a VOLUME_SET-only player shows
        // no transport).
        val featuresUnknown = state.mediaSupportedFeatures == 0
        val hasPlayPause = featuresUnknown ||
            state.hasMediaFeature(EntityState.MediaPlayerFeature.PLAY) ||
            state.hasMediaFeature(EntityState.MediaPlayerFeature.PAUSE)
        val hasNext = featuresUnknown || state.hasMediaFeature(EntityState.MediaPlayerFeature.NEXT_TRACK)
        val hasPrevious = featuresUnknown || state.hasMediaFeature(EntityState.MediaPlayerFeature.PREVIOUS_TRACK)
        val transport = mutableListOf<QuickAction>()
        if (hasPrevious) {
            transport += QuickAction(
                id = "media-previous",
                label = "PREVIOUS",
                onFire = { ctx.onEntityCall(ServiceCall.mediaTransport(state.id, MediaTransport.PREVIOUS)) },
            )
        }
        if (hasPlayPause) {
            // Explicit media_play / media_pause (not the toggle) so the dispatched call
            // is deterministic; the chip shows the action a tap will take.
            transport += QuickAction(
                id = "media-play-pause",
                label = if (state.isOn) "PAUSE" else "PLAY",
                selected = state.isOn,
                onFire = { ctx.onEntityCall(ServiceCall.setSwitch(state.id, on = !state.isOn)) },
            )
        }
        if (hasNext) {
            transport += QuickAction(
                id = "media-next",
                label = "NEXT",
                onFire = { ctx.onEntityCall(ServiceCall.mediaTransport(state.id, MediaTransport.NEXT)) },
            )
        }
        if (transport.isNotEmpty()) {
            groups += QuickActionGroup(title = "PLAYBACK", actions = transport)
        }

        // Source — gated on SELECT_SOURCE; chips read from the `source_list` attr,
        // selected against the current `source` attr. Fires `media_player.select_source`.
        val sources = state.attrStringList("source_list")
        if (state.hasMediaFeature(EntityState.MediaPlayerFeature.SELECT_SOURCE) && sources.isNotEmpty()) {
            val currentSource = state.attrString("source")
            groups += QuickActionGroup(
                title = "SOURCE",
                actions = sources.map { source ->
                    QuickAction(
                        id = "media-source-$source",
                        label = optionLabel(source),
                        selected = source.equals(currentSource, ignoreCase = true),
                        onFire = { ctx.onEntityCall(ServiceCall.mediaSelectSource(state.id, source)) },
                    )
                },
            )
        }

        // Shuffle toggle + repeat cycle — each gated on its feature bit. Repeat cycles
        // off → all → one → off, matching MediaExtrasPanel.
        val hasShuffle = state.hasMediaFeature(EntityState.MediaPlayerFeature.SHUFFLE_SET)
        val hasRepeat = state.hasMediaFeature(EntityState.MediaPlayerFeature.REPEAT_SET)
        if (hasShuffle || hasRepeat) {
            val playbackMode = mutableListOf<QuickAction>()
            if (hasShuffle) {
                val shuffling = state.attrBoolean("shuffle") ?: false
                playbackMode += QuickAction(
                    id = "media-shuffle",
                    label = "SHUFFLE",
                    selected = shuffling,
                    onFire = { ctx.onEntityCall(ServiceCall.mediaShuffleSet(state.id, !shuffling)) },
                )
            }
            if (hasRepeat) {
                val current = (state.attrString("repeat") ?: "off").lowercase()
                val next = when (current) {
                    "off" -> "all"
                    "all" -> "one"
                    else -> "off"
                }
                playbackMode += QuickAction(
                    id = "media-repeat",
                    label = "REPEAT " + current.uppercase(),
                    selected = current != "off",
                    onFire = { ctx.onEntityCall(ServiceCall.mediaRepeatSet(state.id, next)) },
                )
            }
            groups += QuickActionGroup(title = "SHUFFLE & REPEAT", actions = playbackMode)
        }

        return groups
    }
}

/**
 * Registry slice contributed by this file. The orchestrator
 * ([DOMAIN_QUICK_ACTION_BUILDERS]) appends this list so the light / climate /
 * media builders are scanned ahead of the generic fallback.
 */
val lightClimateMediaQuickActionBuilders: List<QuickActionBuilder> =
    listOf(LightQuickActions, ClimateQuickActions, MediaQuickActions)
