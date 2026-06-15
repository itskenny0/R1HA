package com.github.itskenny0.r1ha.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.util.areaLabel
import com.github.itskenny0.r1ha.core.theme.LocalCardFillSlot
import com.github.itskenny0.r1ha.core.theme.LocalCardInk
import com.github.itskenny0.r1ha.core.theme.cardTitleTarget
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * The "switch" card variant — rendered for entities that have no settable scalar (a switch
 * dressed as a light, a non-dimmable bulb, a media player without VOLUME_SET, etc). Looks
 * like a physical two-position toggle: a tall track with ON at top and OFF at bottom, and a
 * thumb that snaps between them. The wheel and a tap both flip it; the slider position
 * animates with the same snappy spring as the percent slider.
 *
 * Layout mirrors the percent card's spec (DOMAIN · AREA header, friendly-name title, label
 * micros) so the cards in the stack feel cohesive — only the value display differs.
 */
@Composable
fun SwitchCard(
    state: EntityState,
    accent: Color,
    domainLabel: String,
    showArea: Boolean,
    onTapToggle: () -> Unit,
    onSetOn: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Ink rides in from the theme via the EntityCard wrapper (white over a gradient
    // backdrop, the classic R1 greys everywhere else). The backdrop itself is painted
    // by the wrapper too, so the card stays theme-agnostic.
    val ink = LocalCardInk.current
    // Wrap mode (the DYNAMIC deck, LocalCardFillSlot = false): the card sizes
    // to its content so every control stays visible; fill mode keeps the
    // historical full-slot layout.
    val fillSlot = LocalCardFillSlot.current
    Column(
        modifier = modifier
            .then(if (fillSlot) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
        // ── Header ─────────────────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(width = 14.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
            Spacer(Modifier.width(8.dp))
            Text(domainLabel, style = R1.labelMicro, color = ink.ink)
            Spacer(Modifier.width(8.dp))
            Text("· ON/OFF", style = R1.labelMicro, color = ink.muted)
            if (showArea && !state.area.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                Text("·", style = R1.labelMicro, color = ink.muted)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = areaLabel(state.area),
                    style = R1.labelMicro,
                    color = ink.soft,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = state.friendlyName,
            style = R1.titleCard,
            color = ink.ink,
            maxLines = 2,
            // Tap the title to select this card in the dynamic deck (no-op
            // elsewhere); full width so the whole title row is the target.
            modifier = Modifier.fillMaxWidth().cardTitleTarget(),
        )
        Spacer(Modifier.height(20.dp))

        // ── State word ─────────────────────────────────────────────────────────────
        // Crossfade ON↔OFF so the flip reads as a deliberate state change rather than a
        // text swap. AnimatedContent picks the labels by isOn key, fades the outgoing
        // word out while the new one fades in — quicker than the default 220 ms because
        // the user has already seen the thumb move on the switch track and the readout
        // should land before the eye drifts away.
        // Alarming states (a jammed lock, an errored vacuum / mower, a triggered alarm)
        // read red so they stand out from an ordinary on/off rather than sitting muted.
        val alarmingState = isAlarmingSwitchState(state.rawState)
        val labelColor by androidx.compose.animation.animateColorAsState(
            targetValue = when {
                alarmingState -> R1.StatusRed
                state.isOn -> accent
                else -> ink.soft
            },
            label = "switch-label-color",
        )
        // State word — domain-aware. Covers in motion show OPENING/CLOSING (not ON/OFF
        // which would lie about the in-between state); vacuums show CLEANING/RETURNING/
        // DOCKED/IDLE for the same reason. Everything else falls back to ON/OFF.
        val stateWord = friendlySwitchStateWord(state)
        androidx.compose.animation.AnimatedContent(
            targetState = stateWord,
            transitionSpec = {
                androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core.tween(durationMillis = 120),
                ) togetherWith androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core.tween(durationMillis = 120),
                )
            },
            label = "switch-state-word",
        ) { word ->
            Text(
                text = word,
                style = R1.numeralXl,
                color = labelColor,
            )
        }
        // For automations: when the rule last actually ran ("ran 2h ago"). last_triggered
        // is distinct from the enabled/disabled toggle, so it answers "did this fire?" at a
        // glance. Hidden until the automation has fired at least once.
        val lastRan = rememberRelativeTime(state.lastTriggered)
        if (lastRan.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(text = "ran $lastRan", style = R1.labelMicro, color = ink.muted, maxLines = 1)
        }

        Spacer(Modifier.height(14.dp))

        // ── The two-position switch ────────────────────────────────────────────────
        // Track labels are domain-aware so a lock reads UNLOCKED / LOCKED on its
        // tappable end-stops instead of the generic ON / OFF. The state word above
        // tells the user the current state; the labels tell them what tapping
        // each end will *do*, which for locks is much clearer phrased as the
        // physical state rather than the abstract on/off.
        val (onLabel, offLabel) = when (state.id.domain) {
            com.github.itskenny0.r1ha.core.ha.Domain.LOCK -> "UNLOCK" to "LOCK"
            // Covers / valves: tapping the top end-stop opens, tapping the bottom
            // end-stop closes. Same shape as ON / OFF semantically (the entity
            // has two stable states) but reads more naturally for the physical
            // mechanism. Matches the state word above (OPEN / CLOSED).
            com.github.itskenny0.r1ha.core.ha.Domain.COVER,
            com.github.itskenny0.r1ha.core.ha.Domain.VALVE -> "OPEN" to "CLOSE"
            // Vacuums map ON / OFF to CLEAN / DOCK — tapping CLEAN starts the
            // robot, tapping DOCK sends it back to base. Matches the actual
            // services dispatched (vacuum.start and vacuum.return_to_base).
            com.github.itskenny0.r1ha.core.ha.Domain.VACUUM -> "CLEAN" to "DOCK"
            // Lawn mowers map identically — MOW kicks off the start_mowing
            // service, DOCK sends the mower back. The DOCK label matches the
            // service name `lawn_mower.dock` so the affordance reads true.
            com.github.itskenny0.r1ha.core.ha.Domain.LAWN_MOWER -> "MOW" to "DOCK"
            // Media players reach the SwitchCard path when they don't advertise
            // VOLUME_SET (radio streams, simple players). Tapping the top
            // end-stop dispatches media_play, bottom dispatches media_pause —
            // PLAY / PAUSE matches both the services and the universal media-
            // control glyph language.
            com.github.itskenny0.r1ha.core.ha.Domain.MEDIA_PLAYER -> "PLAY" to "PAUSE"
            else -> "ON" to "OFF"
        }
        // Hide the SwitchTrack for code-required locks. The LockPanel below
        // surfaces explicit LOCK / UNLOCK chips that open the PIN keypad;
        // tapping the SwitchTrack here would otherwise fire lock.unlock
        // without the code and HA would reject with `code_required`,
        // looking like the app silently lost the tap. For locks without a
        // code_format the track still drives the entity directly since
        // there's no keypad to gate it. The per-card 'Require PIN to
        // unlock' override also triggers this path so users can gate locks
        // HA doesn't mark code-required.
        val perCardOverride = com.github.itskenny0.r1ha.core.theme.LocalEntityOverrides
            .current[state.id.value]
        val needsLockCode = state.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.LOCK &&
            (!state.lockCodeFormat.isNullOrBlank() ||
                perCardOverride?.requirePinToUnlock == true)
        // Alarm control panel never surfaces the binary switch track — the
        // valid actions are a finite set of arm modes plus disarm, all of
        // which live on AlarmPanel. The switch track would force an
        // ambiguous "on" mapping that doesn't reflect any single HA service.
        val hideTrackForAlarm = state.id.domain ==
            com.github.itskenny0.r1ha.core.ha.Domain.ALARM_CONTROL_PANEL
        if (!needsLockCode && !hideTrackForAlarm) {
            SwitchTrack(
                isOn = state.isOn,
                accent = accent,
                onSetOn = onSetOn,
                onLabel = onLabel,
                offLabel = offLabel,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Dedicated per-domain panels under the switch track. Each panel
        // self-gates on the entity's `supported_features` bitmask so unused
        // chip sets vanish quietly. The order mirrors the SwitchCard's domain
        // priority (vacuum → mower → lock → valve → water_heater → climate);
        // only one of these renders for a given entity since the domains are
        // disjoint.
        // Each panel renders its own leading Spacer inline rather than us
        // hard-coding 12 dp here; that way panels that early-return on no
        // feature support don't leave a dangling 12 dp gap below the
        // switch track.
        when (state.id.domain) {
            com.github.itskenny0.r1ha.core.ha.Domain.VACUUM ->
                VacuumPanel(state = state, accent = accent, modifier = Modifier.padding(top = 12.dp))
            com.github.itskenny0.r1ha.core.ha.Domain.LAWN_MOWER ->
                LawnMowerPanel(state = state, accent = accent, modifier = Modifier.padding(top = 12.dp))
            com.github.itskenny0.r1ha.core.ha.Domain.LOCK ->
                LockPanel(state = state, accent = accent, modifier = Modifier.padding(top = 12.dp))
            com.github.itskenny0.r1ha.core.ha.Domain.VALVE ->
                ValvePanel(state = state, accent = accent, modifier = Modifier.padding(top = 12.dp))
            com.github.itskenny0.r1ha.core.ha.Domain.WATER_HEATER ->
                WaterHeaterPanel(state = state, accent = accent, modifier = Modifier.padding(top = 12.dp))
            com.github.itskenny0.r1ha.core.ha.Domain.CLIMATE ->
                ClimatePanel(state = state, accent = accent, modifier = Modifier.padding(top = 12.dp))
            com.github.itskenny0.r1ha.core.ha.Domain.ALARM_CONTROL_PANEL ->
                AlarmPanel(state = state, accent = accent, modifier = Modifier.padding(top = 12.dp))
            // Covers reach the SwitchCard path when they advertise no position
            // (tilt-only venetian blinds, simple open/close shutters). The tilt
            // chips self-gate on the cover's tilt feature bits, so a plain
            // open/close blind renders nothing here.
            com.github.itskenny0.r1ha.core.ha.Domain.COVER ->
                CoverPanel(state = state, accent = accent, modifier = Modifier.padding(top = 12.dp))
            // Humidifiers without a `humidity` scalar land on the SwitchCard path;
            // the mode picker self-gates on the MODES feature bit + available_modes.
            com.github.itskenny0.r1ha.core.ha.Domain.HUMIDIFIER ->
                HumidifierPanel(state = state, accent = accent, modifier = Modifier.padding(top = 12.dp))
            else -> Unit
        }

        // Media-player extras — when a media_player lands on the SwitchCard path
        // (no VOLUME_SET feature / null volume_level), it would otherwise have no
        // transport at all. Render now-playing info + the same MediaControlsRow as
        // the scalar path so play/pause/next/prev/mute still work end-to-end.
        if (state.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.MEDIA_PLAYER) {
            // Always render the inline now-playing block — the previous
            // title-or-picture conditional left the block missing for
            // the same entity on bigger screens that showed it on R1.
            // MediaNowPlayingInline already hides its own empty rows.
            Spacer(Modifier.height(14.dp))
            MediaNowPlayingInline(state = state, accent = accent)
            Spacer(Modifier.height(10.dp))
            com.github.itskenny0.r1ha.core.theme.MediaControlsRow(
                entityId = state.id,
                isPlaying = state.isOn,
                accent = accent,
                isMuted = state.isVolumeMuted,
                supportedFeatures = state.mediaSupportedFeatures,
            )
            // Shuffle / repeat / source row — same panel as the scalar
            // path so a non-VOLUME_SET media player still gets the
            // discrete media controls.
            MediaExtrasPanel(state = state, accent = accent, modifier = Modifier.padding(top = 8.dp))
        }

        // Fill mode only: absorb the leftover slot height. A weighted spacer
        // in a wrap-height column would re-inflate the card to the cap,
        // defeating content sizing.
        if (fillSlot) Spacer(Modifier.weight(1f))
    }
}

/**
 * Vertical track 12dp wide × ~120dp tall, with two end-stop labels and a thumb that animates
 * between the top (ON) and bottom (OFF). The ON / OFF labels are clickable: tap ON to set
 * the entity on regardless of current state, tap OFF to set off. The track itself isn't a
 * drag-handle — driving the switch is wheel-or-tap-on-labels only.
 */
@Composable
private fun SwitchTrack(
    isOn: Boolean,
    accent: Color,
    onSetOn: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Top end-stop label. Defaults to ON for switches / lights / etc.; locks
     *  override with UNLOCK to read more naturally. */
    onLabel: String = "ON",
    /** Bottom end-stop label, paired with [onLabel]. */
    offLabel: String = "OFF",
) {
    // Critically damped spring — a physical toggle snaps to its stop and stays put; no
    // bounce. (The percent slider on the other card variant does bounce because there are
    // intermediate positions to telegraph; here there are only two stops.) Critical damping
    // also keeps the animated value strictly inside [0, 1], which matters because the thumb
    // is placed by multiplying this fraction into a Dp — a brief overshoot to -0.03 used to
    // produce a negative Dp and crash `Modifier.padding(top = …)`. We now use `offset`
    // instead, which accepts any Dp, but the critically-damped spring is the correct shape
    // for this control regardless.
    val rawFrac by animateFloatAsState(
        targetValue = if (isOn) 0f else 1f,  // 0 = top (ON), 1 = bottom (OFF)
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "switch-frac",
    )
    val frac = rawFrac.coerceIn(0f, 1f)
    // Same theme-provided ink as the card body — the end-stop labels and the off-state
    // thumb need the white palette to stay legible on a gradient backdrop.
    val ink = LocalCardInk.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left labels — the top one sets the ON state, the bottom one sets OFF.
        // Both are clickable explicit setters. Text content comes from the caller
        // so locks read UNLOCK / LOCK instead of the generic ON / OFF.
        Column(
            modifier = Modifier.height(120.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = onLabel,
                style = R1.numeralM,
                color = if (isOn) accent else ink.muted,
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .r1Pressable(onClick = { onSetOn(true) })
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Text(
                text = offLabel,
                style = R1.numeralM,
                color = if (!isOn) ink.soft else ink.muted,
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .r1Pressable(onClick = { onSetOn(false) })
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.width(16.dp))

        // Track + thumb.
        BoxWithConstraints(
            modifier = Modifier
                .height(120.dp)
                .width(12.dp),
        ) {
            // Track.
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(2.dp)
                    .align(Alignment.Center)
                    .background(R1.Hairline),
            )
            // Thumb — 24×8 dp pill, accent when ON, dim when OFF.
            val thumbHeight = 8.dp
            val trackHeight = maxHeight
            val travel = trackHeight - thumbHeight
            // `offset` rather than `padding` for the Y position — `padding` rejects negative
            // Dp with IllegalArgumentException, and any non-zero spring bounce would briefly
            // produce a value just outside [0, 1]. `offset` accepts any Dp.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = travel * frac)
                    .width(24.dp)
                    .height(thumbHeight)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isOn) accent else ink.soft),
            )
        }
    }
}

/**
 * Inline now-playing row for media_player entities that landed on the SwitchCard
 * variant (no VOLUME_SET capability). Title + artist text, plus the album cover
 * when HA provided one. No progress bar here — the SwitchCard's vertical real
 * estate is already taken by the switch track; a third row would crowd the
 * layout. The PragmaticHybridTheme's scalar-path variant has the full progress
 * UI.
 */
@Composable
private fun MediaNowPlayingInline(state: EntityState, accent: Color) {
    val serverUrl = com.github.itskenny0.r1ha.core.theme.LocalHaServerUrl.current
    // Same auth fix as MediaNowPlayingCompact — the Bearer header is needed for the
    // half of media_player integrations whose entity_picture is a plain /api/... path
    // (no `?token=...` baked in). Harmless when the URL already carries a token.
    val bearerToken = com.github.itskenny0.r1ha.core.theme.LocalHaBearerToken.current
    // Theme-provided ink so the track metadata stays legible on a gradient backdrop.
    val ink = LocalCardInk.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!state.mediaPicture.isNullOrBlank()) {
            AsyncBitmap(
                url = state.mediaPicture,
                serverUrl = serverUrl,
                bearerToken = bearerToken,
                modifier = Modifier
                    .size(48.dp)
                    .clip(R1.ShapeS),
                contentDescription = "Album art",
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            if (!state.mediaSource.isNullOrBlank()) {
                Text(
                    text = state.mediaSource.uppercase(),
                    style = R1.labelMicro,
                    color = accent,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            if (!state.mediaTitle.isNullOrBlank()) {
                Text(
                    text = state.mediaTitle,
                    style = R1.bodyEmph,
                    color = ink.ink,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            if (!state.mediaArtist.isNullOrBlank()) {
                Text(
                    text = state.mediaArtist,
                    style = R1.body,
                    color = ink.soft,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Domain-aware state word for the SwitchCard's big readout. Covers in motion read
 * OPENING / CLOSING; vacuums read CLEANING / DOCKED / RETURNING / PAUSED; locks read
 * UNLOCKED / LOCKED; climate reads its HVAC mode (HEAT / COOL / etc.) when on. Falls
 * back to ON / OFF for everything else.
 */
/** Raw HA states that warrant a red readout on a switch-style card: a stuck lock, an
 *  errored vacuum / mower, a tripped alarm. Pure + unit-tested. */
private val ALARMING_SWITCH_STATES = setOf("jammed", "error", "triggered")

internal fun isAlarmingSwitchState(rawState: String?): Boolean =
    rawState?.trim()?.lowercase() in ALARMING_SWITCH_STATES

/** Humanise a climate / water_heater HVAC mode for the card readout: the multi-word HA
 *  modes carry underscores ("heat_cool", "fan_only") that read poorly uppercased as-is.
 *  Maps the known modes to clean labels and falls back to underscore-stripped uppercase
 *  for anything unrecognised. Pure + unit-tested. */
internal fun hvacModeLabel(rawState: String?): String = when (rawState?.trim()?.lowercase()) {
    "heat" -> "HEAT"
    "cool" -> "COOL"
    "heat_cool" -> "HEAT/COOL"
    "auto" -> "AUTO"
    "dry" -> "DRY"
    "fan_only" -> "FAN ONLY"
    "off" -> "OFF"
    else -> (rawState ?: "").trim().replace('_', ' ').uppercase()
}

/** Humanise a thermostat's `hvac_action` (what the equipment is actively doing right now)
 *  for a compact status chip: heating / cooling / idle / etc. Returns null for a blank or
 *  absent action so the card omits the chip entirely. Pure + unit-tested. */
internal fun hvacActionLabel(hvacAction: String?): String? = when (val a = hvacAction?.trim()?.lowercase()) {
    null, "" -> null
    "heating" -> "HEATING"
    "cooling" -> "COOLING"
    "drying" -> "DRYING"
    "idle" -> "IDLE"
    "fan" -> "FAN"
    "off" -> "OFF"
    "preheating" -> "PREHEATING"
    "defrosting" -> "DEFROSTING"
    else -> a.replace('_', ' ').uppercase()
}

/** Whether an `hvac_action` means the equipment is actively running (vs idle / off), so
 *  the status chip can read in the accent colour when working and muted when not. `idle`
 *  and `off` are the only standing-by states; everything else (heating / cooling / drying
 *  / fan / preheating / defrosting / unknown) counts as active. Pure + unit-tested. */
internal fun hvacActionIsActive(hvacAction: String?): Boolean =
    when (hvacAction?.trim()?.lowercase()) {
        null, "", "idle", "off" -> false
        else -> true
    }

private fun friendlySwitchStateWord(state: EntityState): String {
    val raw = state.rawState?.lowercase() ?: return if (state.isOn) "ON" else "OFF"
    return when (state.id.domain) {
        com.github.itskenny0.r1ha.core.ha.Domain.COVER,
        com.github.itskenny0.r1ha.core.ha.Domain.VALVE -> when (raw) {
            "open" -> "OPEN"
            "closed" -> "CLOSED"
            "opening" -> "OPENING"
            "closing" -> "CLOSING"
            "stopped" -> "STOPPED"
            else -> raw.uppercase()
        }
        com.github.itskenny0.r1ha.core.ha.Domain.VACUUM -> when (raw) {
            "cleaning" -> "CLEANING"
            "docked" -> "DOCKED"
            "returning" -> "RETURNING"
            "paused" -> "PAUSED"
            "idle" -> "IDLE"
            "error" -> "ERROR"
            else -> raw.uppercase()
        }
        com.github.itskenny0.r1ha.core.ha.Domain.LAWN_MOWER -> when (raw) {
            "mowing" -> "MOWING"
            "docked" -> "DOCKED"
            "returning" -> "RETURNING"
            "paused" -> "PAUSED"
            "error" -> "ERROR"
            else -> raw.uppercase()
        }
        com.github.itskenny0.r1ha.core.ha.Domain.LOCK -> when (raw) {
            "locked" -> "LOCKED"
            "unlocked" -> "UNLOCKED"
            // Mid-transition states. HA reports these during the actuator's
            // mechanical travel; collapsing them to LOCKED / UNLOCKED via isOn
            // (the previous behaviour) read wrong because isOn = unlocked, so
            // a locking-in-progress lock falsely showed 'LOCKED' before the
            // bolt actually engaged.
            "locking" -> "LOCKING"
            "unlocking" -> "UNLOCKING"
            // Some integrations expose intermediate motion-detected / opening
            // states; pass through if we ever see one.
            "opening" -> "OPENING"
            // Mechanical failure state — the lock got stuck mid-travel.
            // Surfacing JAMMED is much more useful than the old 'LOCKED' for
            // a user trying to figure out why the lock isn't responding.
            "jammed" -> "JAMMED"
            else -> raw.uppercase()
        }
        com.github.itskenny0.r1ha.core.ha.Domain.CLIMATE,
        com.github.itskenny0.r1ha.core.ha.Domain.WATER_HEATER -> hvacModeLabel(raw)
        com.github.itskenny0.r1ha.core.ha.Domain.MEDIA_PLAYER -> when (raw) {
            "playing" -> "PLAYING"
            "paused" -> "PAUSED"
            "idle" -> "IDLE"
            "standby" -> "STANDBY"
            "buffering" -> "BUFFERING"
            else -> raw.uppercase()
        }
        com.github.itskenny0.r1ha.core.ha.Domain.ALARM_CONTROL_PANEL -> when (raw) {
            "disarmed" -> "DISARMED"
            "armed_away" -> "ARMED AWAY"
            "armed_home" -> "ARMED HOME"
            "armed_night" -> "ARMED NIGHT"
            "armed_vacation" -> "ARMED VACATION"
            "armed_custom_bypass" -> "ARMED BYPASS"
            "pending" -> "PENDING"
            "arming" -> "ARMING"
            "disarming" -> "DISARMING"
            "triggered" -> "TRIGGERED"
            else -> raw.replace('_', ' ').uppercase()
        }
        else -> if (state.isOn) "ON" else "OFF"
    }
}
