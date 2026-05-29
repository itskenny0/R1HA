package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Renderer for HA's `media-control` card. Bound to one `media_player.*`
 * entity, it shows the now-playing line, a transport row (previous /
 * play-pause / next), and a volume stepper. Each control is gated on the
 * player's advertised `supported_features` so the card never offers a button
 * HA would reject.
 *
 * Service calls dispatch through the standard [LovelaceAction.CallService]
 * path; the card stays Compose-pure and never touches the repository.
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

    // What's playing: title plus artist when both are present.
    val nowPlaying = listOfNotNull(
        state?.mediaTitle?.takeUnless { it.isBlank() },
        state?.mediaArtist?.takeUnless { it.isBlank() },
    ).joinToString(" - ").ifBlank { raw.replace('_', ' ').ifBlank { ". " } }

    val hasPrev = state?.hasMediaFeature(EntityState.MediaPlayerFeature.PREVIOUS_TRACK) == true
    val hasNext = state?.hasMediaFeature(EntityState.MediaPlayerFeature.NEXT_TRACK) == true
    val hasVolume = state?.hasMediaFeature(EntityState.MediaPlayerFeature.VOLUME_SET) == true
    val volPct = state?.percent

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
            StateChip(text = if (raw.isBlank()) ". " else raw.replace('_', ' '), accent = accent)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = nowPlaying,
            style = R1.body,
            color = R1.InkSoft,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // Selected input source (e.g. "Spotify", "HDMI 1") when the player
        // reports one, so the card reads like HA's media-control card.
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
        Spacer(Modifier.height(12.dp))
        // Transport row.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasPrev) {
                TransportButton(label = "⏮", accent = accent) {
                    onAction(mediaServiceAction(card.entityId, "media_previous_track"))
                }
            }
            TransportButton(label = if (playing) "⏸" else "▶", accent = accent, primary = true) {
                onAction(mediaServiceAction(card.entityId, "media_play_pause"))
            }
            if (hasNext) {
                TransportButton(label = "⏭", accent = accent) {
                    onAction(mediaServiceAction(card.entityId, "media_next_track"))
                }
            }
        }
        if (hasVolume) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "VOLUME", style = R1.labelMicro, color = R1.InkMuted)
                    Spacer(Modifier.height(2.dp))
                    Text(text = volPct?.let { "$it%" } ?: ". ", style = R1.numeralM, color = accent)
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

private fun mediaServiceAction(entityId: String, service: String): LovelaceAction.CallService =
    LovelaceAction.CallService(
        service = "media_player.$service",
        entityId = entityId,
        data = null,
    )

private fun volumeAction(entityId: String, pct: Int): LovelaceAction.CallService =
    LovelaceAction.CallService(
        service = "media_player.volume_set",
        entityId = entityId,
        data = buildJsonObject {
            put("volume_level", JsonPrimitive(EntityState.mediaVolumeFromPct(pct)))
        },
    )
