package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.ui.components.attrInt
import com.github.itskenny0.r1ha.ui.components.attrString
import com.github.itskenny0.r1ha.ui.components.attrStringList

/**
 * Pure decision logic for the entities-card interactive rows. Every function
 * here is Compose-free so the per-domain gating that HA spreads across its
 * data-layer helpers (cover canOpen/canClose, media control set, update state
 * line, script run-state, lock code requirement, group recursive toggleability,
 * number slider-vs-stepper) can be unit-tested directly. The row composables in
 * EntityRows.kt only consume these results.
 */

/** The domain segment of a `domain.object_id`. Empty when malformed. */
internal fun domainOf(entityId: String): String =
    entityId.substringBefore('.', missingDelimiterValue = "")

/**
 * Maps an entity id to the row "kind" HA's create-row-element resolves it to.
 * Mirrors DOMAIN_TO_ELEMENT_TYPE: domains with no dedicated interactive row
 * fall through to [RowKind.Toggle] (HA's toggle row) or [RowKind.Display] for
 * the read-only sensor-style domains a sibling batch owns. Display kinds keep
 * the existing state-chip rendering.
 */
internal enum class RowKind {
    Toggle, Button, InputButton, Climate, Cover, Group, Humidifier,
    InputDatetime, InputNumber, InputSelect, InputText, Lock, MediaPlayer,
    Number, Scene, Script, Select, Update, Valve,
    // Read-only display rows with a specialised renderer per domain.
    Event, Weather, Timer, Display,
}

internal fun rowKindFor(entityId: String): RowKind = when (domainOf(entityId)) {
    "alert", "automation", "fan", "input_boolean", "light", "remote",
    "siren", "switch", "vacuum" -> RowKind.Toggle
    "button" -> RowKind.Button
    "input_button" -> RowKind.InputButton
    "climate", "water_heater" -> RowKind.Climate
    "cover" -> RowKind.Cover
    "group" -> RowKind.Group
    "humidifier" -> RowKind.Humidifier
    "input_datetime" -> RowKind.InputDatetime
    "input_number" -> RowKind.InputNumber
    "input_select" -> RowKind.InputSelect
    "input_text" -> RowKind.InputText
    "lock" -> RowKind.Lock
    "media_player" -> RowKind.MediaPlayer
    "number" -> RowKind.Number
    "scene" -> RowKind.Scene
    "script" -> RowKind.Script
    "select" -> RowKind.Select
    "update" -> RowKind.Update
    "valve" -> RowKind.Valve
    // Display domains with a dedicated read-only renderer.
    "event" -> RowKind.Event
    "weather" -> RowKind.Weather
    "timer" -> RowKind.Timer
    // sensor / date / time and any unmodelled domain: render the generic
    // read-only state chip (timestamp-aware via the shared format engine).
    else -> RowKind.Display
}

// ── Cover gating (mirrors data/cover.ts) ────────────────────────────────────

internal object CoverBit {
    const val OPEN = 1
    const val CLOSE = 2
    const val STOP = 8
    const val OPEN_TILT = 16
    const val CLOSE_TILT = 32
    const val STOP_TILT = 64
}

private fun EntityState.coverSf(): Int = attrInt("supported_features") ?: 0
private fun EntityState.coverHas(bit: Int): Boolean = (coverSf() and bit) != 0
private fun EntityState.assumed(): Boolean =
    (attributesJson?.get("assumed_state") as? kotlinx.serialization.json.JsonPrimitive)
        ?.content?.toBooleanStrictOrNull() == true

/** A cover that exposes tilt commands but no position/open commands. HA hides
 *  the main open/close buttons for these and shows tilt buttons instead. */
internal fun coverIsTiltOnly(state: EntityState): Boolean {
    val supportsCover = state.coverHas(CoverBit.OPEN) || state.coverHas(CoverBit.CLOSE) ||
        state.coverHas(CoverBit.STOP)
    val supportsTilt = state.coverHas(CoverBit.OPEN_TILT) || state.coverHas(CoverBit.CLOSE_TILT) ||
        state.coverHas(CoverBit.STOP_TILT)
    return supportsTilt && !supportsCover
}

private fun EntityState.coverPosition(): Int? = attrInt("current_position")

internal fun coverIsFullyOpen(state: EntityState): Boolean {
    state.coverPosition()?.let { return it == 100 }
    return state.rawState.equals("open", ignoreCase = true)
}

internal fun coverIsFullyClosed(state: EntityState): Boolean {
    state.coverPosition()?.let { return it == 0 }
    return state.rawState.equals("closed", ignoreCase = true)
}

internal fun coverCanOpen(state: EntityState): Boolean {
    if (!state.isAvailable) return false
    if (state.assumed()) return true
    val opening = state.rawState.equals("opening", ignoreCase = true)
    return !coverIsFullyOpen(state) && !opening
}

internal fun coverCanClose(state: EntityState): Boolean {
    if (!state.isAvailable) return false
    if (state.assumed()) return true
    val closing = state.rawState.equals("closing", ignoreCase = true)
    return !coverIsFullyClosed(state) && !closing
}

internal fun coverCanStop(state: EntityState): Boolean = state.isAvailable

internal fun coverHasStop(state: EntityState): Boolean = state.coverHas(CoverBit.STOP)

private fun EntityState.tiltPosition(): Int? = attrInt("current_tilt_position")

internal fun coverCanOpenTilt(state: EntityState): Boolean {
    if (!state.isAvailable) return false
    if (state.assumed()) return true
    return state.tiltPosition()?.let { it != 100 } ?: true
}

internal fun coverCanCloseTilt(state: EntityState): Boolean {
    if (!state.isAvailable) return false
    if (state.assumed()) return true
    return state.tiltPosition()?.let { it != 0 } ?: true
}

// ── Media-player control set (mirrors hui-media-player-entity-row) ──────────

/** One transport button HA renders, identified by the service it fires. */
internal enum class MediaControl { TURN_ON, TURN_OFF, PREVIOUS, PLAY_PAUSE, PLAY, PAUSE, STOP, NEXT }

private fun EntityState.assumedMedia(): Boolean =
    (attributesJson?.get("assumed_state") as? kotlinx.serialization.json.JsonPrimitive)
        ?.content?.toBooleanStrictOrNull() == true

/** Active in HA's `stateActive` sense: a player that is not off / idle / standby. */
internal fun mediaIsActive(state: EntityState): Boolean = when (state.rawState?.lowercase()) {
    null, "off", "idle", "standby", "unavailable", "unknown" -> false
    else -> true
}

/**
 * The ordered transport / power controls HA's media-player row shows for the
 * current state and supported_features. Mirrors the row's button gating:
 * power-on when supported and off/assumed, the transport trio gated on the
 * playing/paused/idle state, dual play+pause when assumed_state, and power-off
 * when active/assumed.
 */
internal fun mediaControlSet(state: EntityState): List<MediaControl> {
    val sf = state.mediaSupportedFeatures
    fun has(bit: Int) = sf != 0 && (sf and bit) != 0
    val assumed = state.assumedMedia()
    val active = mediaIsActive(state)
    val playing = state.rawState.equals("playing", ignoreCase = true)
    val pausedOrIdle = state.rawState.equals("paused", ignoreCase = true) ||
        state.rawState.equals("idle", ignoreCase = true)
    val on = state.rawState.equals("on", ignoreCase = true)
    val out = mutableListOf<MediaControl>()

    // Power-on: shown when supported and the player is inactive (or assumed).
    if (has(EntityState.MediaPlayerFeature.TURN_ON) && (!active || assumed) &&
        !state.rawState.equals("unavailable", ignoreCase = true)
    ) {
        out += MediaControl.TURN_ON
    }

    // Transport block only renders when the player is active / assumed / has no
    // power control and isn't a pure volume box (HA's gate).
    val hasVolume = has(EntityState.MediaPlayerFeature.VOLUME_SET) ||
        has(EntityState.MediaPlayerFeature.VOLUME_STEP)
    val showTransport = !hasVolume &&
        (active || assumed || !has(EntityState.MediaPlayerFeature.TURN_ON) ||
            state.rawState.equals("unavailable", ignoreCase = true))
    if (showTransport) {
        if ((playing || assumed) && has(EntityState.MediaPlayerFeature.PREVIOUS_TRACK)) {
            out += MediaControl.PREVIOUS
        }
        if (!assumed) {
            val canPlayPause = (playing &&
                (has(EntityState.MediaPlayerFeature.PAUSE) || has(EntityState.MediaPlayerFeature.STOP))) ||
                (pausedOrIdle && has(EntityState.MediaPlayerFeature.PLAY)) ||
                (on && (has(EntityState.MediaPlayerFeature.PLAY) || has(EntityState.MediaPlayerFeature.PAUSE)))
            if (canPlayPause) out += MediaControl.PLAY_PAUSE
        } else {
            if (has(EntityState.MediaPlayerFeature.PLAY)) out += MediaControl.PLAY
            if (has(EntityState.MediaPlayerFeature.PAUSE)) out += MediaControl.PAUSE
            if (has(EntityState.MediaPlayerFeature.STOP) && !has(EntityState.MediaPlayerFeature.VOLUME_SET)) {
                out += MediaControl.STOP
            }
        }
        val showNext = (playing || (assumed && !has(EntityState.MediaPlayerFeature.VOLUME_SET))) &&
            has(EntityState.MediaPlayerFeature.NEXT_TRACK)
        if (showNext) out += MediaControl.NEXT
    }

    if (has(EntityState.MediaPlayerFeature.TURN_OFF) && (active || assumed)) {
        out += MediaControl.TURN_OFF
    }
    return out
}

/** Whether the row should show a volume control (slider/buttons) at all. */
internal fun mediaShowsVolume(state: EntityState): Boolean {
    val sf = state.mediaSupportedFeatures
    fun has(bit: Int) = sf != 0 && (sf and bit) != 0
    return (has(EntityState.MediaPlayerFeature.VOLUME_SET) ||
        has(EntityState.MediaPlayerFeature.VOLUME_STEP)) && mediaIsActive(state)
}

/** True when the player prefers a continuous slider (VOLUME_SET) over +/- buttons. */
internal fun mediaVolumeIsSlider(state: EntityState): Boolean {
    val sf = state.mediaSupportedFeatures
    return sf != 0 && (sf and EntityState.MediaPlayerFeature.VOLUME_SET) != 0
}

/**
 * HA's `computeMediaDescription`: the secondary line for a media row. Reads
 * artist / playlist / series / channel / app name keyed off media_content_type.
 * Empty when nothing is playing.
 */
internal fun mediaDescription(state: EntityState): String {
    fun attr(k: String) = state.attrString(k)
    return when (state.attrString("media_content_type")) {
        "music", "image" -> attr("media_artist").orEmpty()
        "playlist" -> attr("media_playlist") ?: attr("media_artist").orEmpty()
        "tvshow" -> {
            var s = attr("media_series_title").orEmpty()
            attr("media_season")?.let { season ->
                s += " S$season"
                attr("media_episode")?.let { ep -> s += "E$ep" }
            }
            s
        }
        "channel" -> attr("media_channel").orEmpty()
        else -> attr("app_name").orEmpty()
    }
}

// ── Update row state line (mirrors computeUpdateStateDisplay) ────────────────

internal object UpdateBit {
    const val INSTALL = 1
    const val PROGRESS = 4
}

/** True when an update is available (state "on") and supports installing. */
internal fun updateCanInstall(state: EntityState): Boolean {
    if (!state.rawState.equals("on", ignoreCase = true)) return false
    val sf = state.attrInt("supported_features") ?: 0
    return (sf and UpdateBit.INSTALL) != 0
}

/** True when an update install is actively in progress (`in_progress`). */
internal fun updateIsInstalling(state: EntityState): Boolean =
    (state.attributesJson?.get("in_progress") as? kotlinx.serialization.json.JsonPrimitive)
        ?.content?.toBooleanStrictOrNull() == true

/**
 * The state line HA shows in the update row: "Up-to-date" when off and not
 * skipped, the skipped version when the latest is skipped, "Installing N%"
 * (or "Installing") while in progress, else the latest version / state.
 */
internal fun updateStateLine(state: EntityState): String {
    val latest = state.attrString("latest_version")
    val skipped = state.attrString("skipped_version")
    val off = state.rawState.equals("off", ignoreCase = true)
    if (off) {
        if (latest != null && latest == skipped) return latest
        return "Up-to-date"
    }
    if (state.rawState.equals("on", ignoreCase = true) && updateIsInstalling(state)) {
        val sf = state.attrInt("supported_features") ?: 0
        val pct = state.attrInt("update_percentage")
        if ((sf and UpdateBit.PROGRESS) != 0 && pct != null) return "Installing $pct%"
        return "Installing"
    }
    // Update available: show the version to move to.
    return latest ?: "Update available"
}

// ── Script run-state (mirrors hui-script-entity-row) ────────────────────────

/** Whether the script is currently running (state "on"). */
internal fun scriptIsRunning(state: EntityState): Boolean =
    state.rawState.equals("on", ignoreCase = true)

/** Parallel-run count when a script's `mode` allows it and runs are active.
 *  Null when single-mode or no concurrent runs (HA shows a plain CANCEL then). */
internal fun scriptRunningCount(state: EntityState): Int? {
    val mode = state.attrString("mode")
    val current = state.attrInt("current") ?: 0
    if (mode != null && !mode.equals("single", ignoreCase = true) && current > 0) return current
    return null
}

/** A script's RUN button shows when it's off, or whenever it advertises `max`
 *  (so a queued/parallel script can still be launched while running). */
internal fun scriptShowsRun(state: EntityState): Boolean =
    state.rawState.equals("off", ignoreCase = true) || state.attrInt("max") != null

/** RUN is enabled only when available and HA's `canRun` allows it (a parallel
 *  script at its max concurrent runs can't start another). */
internal fun scriptCanRun(state: EntityState): Boolean {
    if (!state.isAvailable) return false
    val max = state.attrInt("max") ?: return true
    val current = state.attrInt("current") ?: 0
    return current < max
}

// ── Lock code requirement (mirrors data/lock.ts) ────────────────────────────

/** True when the lock advertises a `code_format`, so lock/unlock needs a code. */
internal fun lockRequiresCode(state: EntityState): Boolean = !state.lockCodeFormat.isNullOrBlank()

/** The lock/unlock service for the current state. */
internal fun lockToggleService(state: EntityState): String =
    if (state.rawState.equals("locked", ignoreCase = true)) "lock.unlock" else "lock.lock"

// ── Group recursive toggleability (mirrors hui-group-entity-row) ────────────

private val TOGGLE_DOMAINS = setOf(
    "light", "switch", "fan", "input_boolean", "automation", "siren", "remote",
    "cover", "media_player", "humidifier", "script", "group", "lock", "climate",
    "valve", "alert", "vacuum",
)

/**
 * Whether a group should render a toggle: true when any recursively-resolved
 * member is in a toggleable domain. Nested groups are resolved via [resolve],
 * which returns a group entity's `entity_id` member list (or null when the
 * member can't be resolved). Mirrors HA's `_computeCanToggle`.
 */
internal fun groupCanToggle(
    memberIds: List<String>,
    resolve: (String) -> List<String>?,
): Boolean = memberIds.any { memberId ->
    val domain = domainOf(memberId)
    if (domain == "group") {
        val nested = resolve(memberId) ?: return@any false
        groupCanToggle(nested, resolve)
    } else {
        domain in TOGGLE_DOMAINS
    }
}

/** A group's member entity ids from its `entity_id` attribute. */
internal fun groupMembers(state: EntityState?): List<String> =
    state?.attrStringList("entity_id") ?: emptyList()

// ── number / input_number slider-vs-stepper (mirrors the rows) ──────────────

/**
 * HA renders a slider when mode is "slider", or (number domain only) when mode
 * is "auto" and the value range divided by step is <= 256; otherwise a numeric
 * input. On R1 the numeric-input path becomes a stepper. Returns true for the
 * slider path.
 */
internal fun numberUsesSlider(state: EntityState, isInputNumber: Boolean): Boolean {
    val mode = state.attrString("mode")?.lowercase()
    if (mode == "slider") return true
    if (isInputNumber) return false
    if (mode == "auto") {
        val min = state.attrString("min")?.toDoubleOrNull() ?: state.minRaw
        val max = state.attrString("max")?.toDoubleOrNull() ?: state.maxRaw
        val step = state.attrString("step")?.toDoubleOrNull() ?: state.step
        if (min != null && max != null && step != null && step > 0) {
            return (max - min) / step <= 256
        }
    }
    return false
}
