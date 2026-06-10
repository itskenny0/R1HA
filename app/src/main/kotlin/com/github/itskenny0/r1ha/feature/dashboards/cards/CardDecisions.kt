package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.EntityState

/**
 * Pure decision logic shared by the tile / thermostat / media-control /
 * toggle-group renderers. Everything here is Compose-free and IO-free so the
 * branching that mirrors HA's frontend (control-set computation, icon-badge
 * selection, dual-setpoint math, aggregate toggle label) is unit-tested
 * directly rather than only exercised through Compose.
 */

// ── Tile: icon-action default + status badge + pulse ─────────────────────────

/**
 * HA's default icon action for a tile bound to [entityId]: "toggle" for a
 * toggleable domain (HA's shared [DOMAINS_TOGGLE]) or a button/input_button/
 * scene, else "none". Mirrors `getEntityDefaultTileIconAction` in
 * hui-tile-card.ts, which splits the tile's two gesture surfaces so the body
 * taps to more-info while the icon taps to toggle/press.
 */
internal fun getEntityDefaultTileIconAction(entityId: String): String {
    val domain = entityId.substringBefore('.', missingDelimiterValue = "")
    val supportsIconAction = domain in DOMAINS_TOGGLE ||
        domain in setOf("button", "input_button", "scene")
    return if (supportsIconAction) "toggle" else "none"
}

/**
 * The small overlay badge HA draws on a tile's icon disc
 * (cards/tile/badges/tile-badge.ts). Returned as a typed value so the renderer
 * picks a glyph + accent without re-deriving HA's branch order.
 */
internal sealed interface TileBadge {
    /** Unavailable entity: an orange "!" warning badge. */
    data object Unavailable : TileBadge

    /** person / device_tracker: home vs away (or a named zone). [away] is true
     *  when the entity is `not_home`; [home] true when `home`; otherwise the
     *  entity is in a named zone (still rendered with the home glyph). */
    data class Person(val home: Boolean, val away: Boolean) : TileBadge

    /** climate hvac_action (heating / cooling / drying / fan). [action] is the
     *  raw attribute value, non-"off". */
    data class Climate(val action: String) : TileBadge

    /** humidifier action (humidifying / drying / idle). Non-"off" only. */
    data class Humidifier(val action: String) : TileBadge
}

/**
 * Select the icon badge for a tile, mirroring `renderTileBadge`'s branch order:
 * unknown -> none, unavailable -> warning, then person/device_tracker, climate,
 * humidifier. Returns null when no badge applies.
 */
internal fun tileBadgeFor(entityId: String, state: EntityState?): TileBadge? {
    if (state == null) return null
    val raw = state.rawState?.lowercase().orEmpty()
    if (raw == "unknown") return null
    if (!state.isAvailable || raw == "unavailable") return TileBadge.Unavailable
    val domain = entityId.substringBefore('.', missingDelimiterValue = "")
    return when (domain) {
        "person", "device_tracker" -> TileBadge.Person(
            home = raw == "home",
            away = raw == "not_home",
        )
        "climate" -> {
            val action = state.climateHvacAction?.lowercase()?.takeUnless { it.isBlank() || it == "off" }
            action?.let { TileBadge.Climate(it) }
        }
        "humidifier" -> {
            val action = state.attributesJson?.get("action")
                ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?.lowercase()?.takeUnless { it.isBlank() || it == "off" }
            action?.let { TileBadge.Humidifier(it) }
        }
        else -> null
    }
}

/**
 * Whether the tile's icon disc should pulse (a subtle repeating alpha fade).
 * Mirrors the CSS rule in hui-tile-card.ts that animates an alarm panel that is
 * pending / arming / triggered, and a jammed lock.
 */
internal fun tileIconPulses(entityId: String, state: EntityState?): Boolean {
    val raw = state?.rawState?.lowercase() ?: return false
    val domain = entityId.substringBefore('.', missingDelimiterValue = "")
    return when (domain) {
        "alarm_control_panel" -> raw == "pending" || raw == "arming" || raw == "triggered"
        "lock" -> raw == "jammed"
        else -> false
    }
}

// ── Media-control: control-set computation + description ──────────────────────

/** One transport control HA's media-control card renders, with the wire
 *  action it fires. [primary] marks the central play/pause/stop button (HA
 *  renders it larger). */
internal data class MediaTransportControl(val action: String, val primary: Boolean)

/**
 * Compute the ordered transport-control set for a media player, mirroring HA's
 * `computeMediaControls(stateObj, useExtendedControls=false)`. Returns an empty
 * list when no control applies (the card then shows no transport row).
 *
 * The non-extended path the card uses skips shuffle/repeat (those live in the
 * tile MEDIA_PLAYBACK feature / more-info). Assumed-state players (which can't
 * report play/pause reliably) get the full power + play + pause + stop set HA
 * offers; everything else follows the state-gated rules.
 */
internal fun computeMediaControls(state: EntityState): List<MediaTransportControl> {
    val raw = state.rawState?.lowercase().orEmpty()
    if (raw == "unavailable") return emptyList()
    val assumed = state.attributesJson?.get("assumed_state")
        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content == "true" } == true

    fun has(bit: Int) = state.hasMediaFeature(bit)
    val F = EntityState.MediaPlayerFeature

    val active = state.isOn && raw != "off" && raw != "standby"
    if (!active && !assumed) {
        // Inactive (off / idle-but-inactive): only a power-on button, when supported.
        return if (has(F.TURN_ON)) listOf(MediaTransportControl("turn_on", primary = false))
        else emptyList()
    }

    val buttons = mutableListOf<MediaTransportControl>()
    if (assumed && has(F.TURN_ON)) buttons += MediaTransportControl("turn_on", primary = false)
    if (has(F.TURN_OFF)) buttons += MediaTransportControl("turn_off", primary = false)

    val playingOrPaused = raw == "playing" || raw == "paused"
    if ((playingOrPaused || assumed) && has(F.PREVIOUS_TRACK)) {
        buttons += MediaTransportControl("media_previous_track", primary = false)
    }

    // Central play/pause/stop, only for non-assumed players (HA gates this on
    // !assumedState; assumed players get the explicit play/pause/stop trio below).
    if (!assumed) {
        val showPlayPause = (raw == "playing" && (has(F.PAUSE) || has(F.STOP))) ||
            ((raw == "paused" || raw == "idle") && has(F.PLAY)) ||
            (raw == "on" && (has(F.PLAY) || has(F.PAUSE)))
        if (showPlayPause) {
            val action = when {
                raw != "playing" -> "media_play"
                has(F.PAUSE) -> "media_pause"
                else -> "media_stop"
            }
            buttons += MediaTransportControl(action, primary = true)
        }
    }
    if (assumed && has(F.PLAY)) buttons += MediaTransportControl("media_play", primary = true)
    if (assumed && has(F.PAUSE)) buttons += MediaTransportControl("media_pause", primary = true)
    if (assumed && has(F.STOP)) buttons += MediaTransportControl("media_stop", primary = true)

    if ((playingOrPaused || assumed) && has(F.NEXT_TRACK)) {
        buttons += MediaTransportControl("media_next_track", primary = false)
    }
    return buttons
}

/** Short glyph/label for a transport control button. */
internal fun mediaTransportGlyph(action: String): String = when (action) {
    "turn_on" -> "⏻"
    "turn_off" -> "⏻"
    "media_previous_track" -> "⏮"
    "media_play" -> "▶"
    "media_pause" -> "⏸"
    "media_stop" -> "⏹"
    "media_next_track" -> "⏭"
    else -> "•"
}

/**
 * Build the secondary "media description" line, mirroring HA's
 * `computeMediaDescription`: artist for music/image, playlist-or-artist for a
 * playlist, "Series SxEy" for a tvshow, channel for a channel, else app_name.
 * Returns an empty string when nothing applies.
 */
internal fun computeMediaDescription(state: EntityState): String {
    fun attr(key: String): String? = state.attributesJson?.get(key)
        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        ?.takeUnless { it.isBlank() }

    return when (attr("media_content_type")) {
        "music", "image" -> attr("media_artist") ?: state.mediaArtist.orEmpty()
        "playlist" -> attr("media_playlist") ?: attr("media_artist") ?: state.mediaArtist.orEmpty()
        "tvshow" -> {
            val series = attr("media_series_title") ?: ""
            val season = attr("media_season")
            val episode = attr("media_episode")
            buildString {
                append(series)
                if (season != null) {
                    append(" S").append(season)
                    if (episode != null) append("E").append(episode)
                }
            }.trim()
        }
        "channel" -> attr("media_channel") ?: ""
        else -> attr("app_name") ?: ""
    }
}

// ── Toggle-group: aggregate label + toggle-all service ───────────────────────

/**
 * The aggregate label HA's toggle-group card shows: "All off" when none are on,
 * "All on" when every entity is on, else "N on". Mirrors `_computeLabel`.
 */
internal fun toggleGroupLabel(onCount: Int, total: Int): String = when {
    total == 0 -> ""
    onCount == 0 -> "All off"
    onCount == total -> "All on"
    else -> "$onCount on"
}

/**
 * The domain + service the toggle-all tap fires, mirroring `_handleTap`: a
 * cover group closes when any is open and opens otherwise; every other domain
 * toggles off when any is on and on otherwise. [domain] is the first entity's
 * domain (HA keys the whole group on it). Returns `domain to service`.
 */
internal fun toggleGroupService(domain: String, anyOn: Boolean): Pair<String, String> =
    if (domain == "cover") {
        domain to if (anyOn) "close_cover" else "open_cover"
    } else {
        domain to if (anyOn) "turn_off" else "turn_on"
    }

// ── Thermostat: dual-setpoint clamp + display ────────────────────────────────

/**
 * Nudge a dual-setpoint bound by [step] in [direction] (-1 / +1), keeping the
 * low bound at or below the high bound and clamping to [min]/[max]. Returns the
 * new value for the nudged side. [editingLow] selects which bound moves; the
 * sibling bound is the floor/ceiling so the two never cross.
 */
internal fun nudgeDualSetpoint(
    low: Double,
    high: Double,
    editingLow: Boolean,
    direction: Int,
    step: Double,
    min: Double?,
    max: Double?,
): Double {
    return if (editingLow) {
        val next = low + direction * step
        // Low may not exceed high; clamp to the configured min too.
        next.coerceAtMost(high).let { if (min != null) it.coerceAtLeast(min) else it }
    } else {
        val next = high + direction * step
        // High may not drop below low; clamp to the configured max too.
        next.coerceAtLeast(low).let { if (max != null) it.coerceAtMost(max) else it }
    }
}
