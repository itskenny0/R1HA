package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.LocalHaBearerToken
import com.github.itskenny0.r1ha.core.theme.LocalHaServerUrl
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.nav.Routes
import com.github.itskenny0.r1ha.ui.components.AsyncBitmap
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Renderer for HA's `media-control` card. Bound to one `media_player.*`
 * entity, it shows album art beside the now-playing line, a transport row
 * whose buttons are computed per the player's state + advertised
 * `supported_features` (HA's `computeMediaControls`), an optional progress bar
 * with tap-to-seek, a volume stepper, and the browse-media / group / more-info
 * affordances.
 *
 * Service calls dispatch through the standard [LovelaceAction.CallService] /
 * [LovelaceAction.Builtin] / [LovelaceAction.Navigate] paths; the card stays
 * Compose-pure and never touches the repository.
 */
@Composable
fun MediaControlCard(
    card: LovelaceCard.MediaControl,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val eid = safeEntityId(card.entityId)
    val state = eid?.let { stateMap[it] }
    val name = resolveName(card.name, state, card.entityId)
    val raw = state?.rawState?.lowercase().orEmpty()
    val playing = raw == "playing"
    val active = raw.isNotBlank() && raw != "off" && raw != "unavailable" && raw != "idle" && raw != "standby"
    val accent = if (active) R1.AccentWarm else R1.InkSoft

    // Title line: HA prefers the media_title; the description (artist / series /
    // channel / app, per content type) is the secondary line.
    val title = state?.mediaTitle?.takeUnless { it.isBlank() }
    val description = state?.let { computeMediaDescription(it) }?.takeUnless { it.isBlank() }
    val nowPlaying = title ?: description ?: raw.replace('_', ' ').ifBlank { "Idle" }

    val controls = state?.let { computeMediaControls(it) }.orEmpty()
    val hasVolume = state?.hasMediaFeature(EntityState.MediaPlayerFeature.VOLUME_SET) == true
    val hasSeek = state?.hasMediaFeature(EntityState.MediaPlayerFeature.SEEK) == true
    val hasBrowse = state?.hasMediaFeature(EntityState.MediaPlayerFeature.PLAY_MEDIA) == true
    val hasGrouping = state?.hasMediaFeature(EntityState.MediaPlayerFeature.GROUPING) == true
    val volPct = state?.percent
    val picture = state?.mediaPicture?.takeUnless { it.isBlank() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, accent.copy(alpha = 0.4f), R1.ShapeM)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = R1.bodyEmph,
                color = R1.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            StateChip(text = if (raw.isBlank()) "-" else raw.replace('_', ' '), accent = accent)
            Spacer(Modifier.width(8.dp))
            MoreInfoDot(accent = accent) { onAction(LovelaceAction.Builtin("more-info", card.entityId)) }
        }
        Spacer(Modifier.height(10.dp))
        // Album art thumbnail + now-playing metadata.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (picture != null) {
                AsyncBitmap(
                    url = picture,
                    serverUrl = LocalHaServerUrl.current,
                    bearerToken = LocalHaBearerToken.current,
                    modifier = Modifier.size(56.dp).clip(R1.ShapeS),
                    contentDescription = "Album art",
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                // Scroll long titles while playing, matching HA's hui-marquee. basicMarquee
                // only activates on overflow, so short titles are unaffected; gating on
                // `playing` keeps a paused/buffering title stationary.
                Text(
                    text = nowPlaying,
                    style = R1.body,
                    color = R1.InkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (playing) Modifier.basicMarquee() else Modifier,
                )
                // The description line (artist / series SxEy / channel / app).
                if (title != null && description != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = description,
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Selected input source (e.g. "Spotify", "HDMI 1") when reported.
                state?.mediaSource?.takeUnless { it.isBlank() }?.let { source ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = source,
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // Progress bar with tap-to-seek (only when the player reports a duration).
        val duration = state?.mediaDuration
        if (state != null && duration != null && duration > 0 && state.mediaPosition != null) {
            Spacer(Modifier.height(10.dp))
            MediaProgressBar(
                positionSec = state.mediaPosition!!,
                positionUpdatedAt = state.mediaPositionUpdatedAt,
                durationSec = duration,
                isPlaying = playing,
                accent = accent,
                seekable = hasSeek,
                onSeek = { fraction ->
                    onAction(seekAction(card.entityId, (duration * fraction)))
                },
            )
        }
        // Transport row (HA's computeMediaControls).
        if (controls.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                controls.forEach { control ->
                    TransportButton(
                        label = mediaTransportGlyph(control.action),
                        accent = accent,
                        primary = control.primary,
                    ) {
                        onAction(mediaServiceAction(card.entityId, control.action))
                    }
                }
                Spacer(Modifier.weight(1f))
                if (hasBrowse) {
                    TransportButton(label = "≣", accent = accent) {
                        onAction(LovelaceAction.Navigate(Routes.MEDIA_BROWSE))
                    }
                }
                if (hasGrouping) {
                    // Full join UI lives in the more-info batch; the button opens
                    // more-info (its group section) for now.
                    TransportButton(label = "⧉", accent = accent) {
                        onAction(LovelaceAction.Builtin("more-info", card.entityId))
                    }
                }
            }
        }
        if (hasVolume) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "VOLUME", style = R1.labelMicro, color = R1.InkMuted)
                    Spacer(Modifier.height(2.dp))
                    Text(text = volPct?.let { "$it%" } ?: "-", style = R1.numeralM, color = accent)
                }
                StepperButton(label = "−", accent = accent, enabled = volPct != null) {
                    val next = ((volPct ?: 0) - 5).coerceIn(0, 100)
                    onAction(volumeAction(card.entityId, next))
                }
                Spacer(Modifier.width(10.dp))
                StepperButton(label = "+", accent = accent, enabled = volPct != null) {
                    val next = ((volPct ?: 0) + 5).coerceIn(0, 100)
                    onAction(volumeAction(card.entityId, next))
                }
            }
        }
    }
}

/**
 * Progress bar that interpolates the live position while playing and, when the
 * player advertises SEEK, fires [onSeek] with the tapped fraction (0..1).
 */
@Composable
private fun MediaProgressBar(
    positionSec: Int,
    positionUpdatedAt: Instant?,
    durationSec: Int,
    isPlaying: Boolean,
    accent: Color,
    seekable: Boolean,
    onSeek: (Float) -> Unit,
) {
    val live = rememberLivePosition(positionSec, positionUpdatedAt, durationSec, isPlaying)
    val fraction = (live.toFloat() / durationSec.toFloat()).coerceIn(0f, 1f)
    val barModifier = Modifier
        .fillMaxWidth()
        .height(if (seekable) 14.dp else 6.dp)
    Box(
        modifier = if (seekable) {
            barModifier.pointerInput(durationSec) {
                detectTapGestures { offset ->
                    val f = (offset.x / size.width).coerceIn(0f, 1f)
                    onSeek(f)
                }
            }
        } else {
            barModifier
        },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(R1.ShapeRound)
                .background(R1.SurfaceMuted),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(R1.ShapeRound)
                    .background(accent),
            )
        }
    }
}

/**
 * Interpolate a live media position from the last reported anchor, mirroring
 * HA's getCurrentProgress: while playing, add the wall-clock seconds elapsed
 * since [positionUpdatedAt]; otherwise hold the reported value. Clamped to the
 * duration.
 */
@Composable
private fun rememberLivePosition(
    positionSec: Int,
    positionUpdatedAt: Instant?,
    durationSec: Int,
    isPlaying: Boolean,
): Int {
    var live by remember(positionSec, positionUpdatedAt, isPlaying, durationSec) {
        mutableIntStateOf(positionSec.coerceIn(0, durationSec))
    }
    LaunchedEffect(positionSec, positionUpdatedAt, isPlaying, durationSec) {
        if (!isPlaying || positionUpdatedAt == null) {
            live = positionSec.coerceIn(0, durationSec)
            return@LaunchedEffect
        }
        while (true) {
            val elapsed = Duration.between(positionUpdatedAt, Instant.now()).seconds.toInt().coerceAtLeast(0)
            live = (positionSec + elapsed).coerceIn(0, durationSec)
            kotlinx.coroutines.delay(1_000)
        }
    }
    return live
}

@Composable
private fun TransportButton(label: String, accent: Color, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(if (primary) 52.dp else 44.dp)
            .clip(CircleShape)
            .background(if (primary) accent.copy(alpha = 0.2f) else R1.SurfaceMuted)
            .border(1.dp, if (primary) accent else R1.Hairline, CircleShape)
            .r1Pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = R1.numeralM, color = accent)
    }
}

/** Build the service call for a transport action. The action string is the wire
 *  media_player service name (turn_on / turn_off / media_play / media_pause /
 *  media_stop / media_previous_track / media_next_track). */
private fun mediaServiceAction(entityId: String, action: String): LovelaceAction.CallService =
    LovelaceAction.CallService(
        service = "media_player.$action",
        entityId = entityId,
        data = null,
    )

private fun seekAction(entityId: String, positionSec: Float): LovelaceAction.CallService =
    LovelaceAction.CallService(
        service = "media_player.media_seek",
        entityId = entityId,
        data = buildJsonObject { put("seek_position", JsonPrimitive(positionSec.toDouble())) },
    )

private fun volumeAction(entityId: String, pct: Int): LovelaceAction.CallService =
    LovelaceAction.CallService(
        service = "media_player.volume_set",
        entityId = entityId,
        data = buildJsonObject {
            put("volume_level", JsonPrimitive(EntityState.mediaVolumeFromPct(pct)))
        },
    )
