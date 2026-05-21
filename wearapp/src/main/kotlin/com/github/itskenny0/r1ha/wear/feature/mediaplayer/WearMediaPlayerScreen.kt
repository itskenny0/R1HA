package com.github.itskenny0.r1ha.wear.feature.mediaplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.MediaTransport
import com.github.itskenny0.r1ha.wear.theme.WearColors

/**
 * Full-featured Wear OS screen for a `media_player` entity.
 *
 * Layout (scrollable column):
 * 1. Entity name + state (Playing / Idle / Off)
 * 2. Now-playing: track title + artist (if available)
 * 3. Transport row: ⏮ ⏯ ⏭
 * 4. Volume row: 🔉 [mute] 🔊
 * 5. Power toggle chip
 * 6. Source list (if entity reports source_list)
 */
@Composable
fun WearMediaPlayerScreen(
    haRepository: HaRepository,
    entityId: EntityId,
    onBack: () -> Unit,
) {
    val vm: WearMediaPlayerViewModel = viewModel(
        key = entityId.value,
        factory = WearMediaPlayerViewModel.factory(haRepository, entityId),
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()

    Scaffold(timeText = { TimeText() }) {
        when {
            state.loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.entity == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Entity not found", style = MaterialTheme.typography.caption1)
                }
            }

            else -> {
                val entity = state.entity!!
                ScalingLazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // ── Header: name + state ─────────────────────────────────
                    item {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        ) {
                            Text(
                                text = entity.friendlyName,
                                style = MaterialTheme.typography.title3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = WearColors.Primary,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = when (entity.rawState) {
                                    "playing"  -> "▶ Playing"
                                    "paused"   -> "⏸ Paused"
                                    "idle"     -> "Idle"
                                    "standby"  -> "Standby"
                                    "off"      -> "Off"
                                    else       -> entity.rawState?.replaceFirstChar { it.uppercaseChar() } ?: ""
                                },
                                style = MaterialTheme.typography.caption2,
                                color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                            )
                        }
                    }

                    // ── Now playing ──────────────────────────────────────────
                    if (!entity.mediaTitle.isNullOrBlank()) {
                        item {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = entity.mediaTitle,
                                    style = MaterialTheme.typography.body1,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                                if (!entity.mediaArtist.isNullOrBlank()) {
                                    Text(
                                        text = entity.mediaArtist,
                                        style = MaterialTheme.typography.caption2,
                                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }

                    // ── Transport row ────────────────────────────────────────
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val canPrev = entity.hasMediaFeature(EntityState.MediaPlayerFeature.PREVIOUS_TRACK)
                            val canNext = entity.hasMediaFeature(EntityState.MediaPlayerFeature.NEXT_TRACK)
                            val canPlay = entity.hasMediaFeature(EntityState.MediaPlayerFeature.PAUSE) ||
                                entity.hasMediaFeature(EntityState.MediaPlayerFeature.PLAY)

                            TransportButton(
                                label = "⏮",
                                enabled = canPrev,
                                onClick = { vm.onTransport(MediaTransport.PREVIOUS) },
                            )
                            TransportButton(
                                label = if (entity.rawState == "playing") "⏸" else "▶",
                                enabled = canPlay,
                                onClick = { vm.onTransport(MediaTransport.PLAY_PAUSE) },
                                primary = true,
                            )
                            TransportButton(
                                label = "⏭",
                                enabled = canNext,
                                onClick = { vm.onTransport(MediaTransport.NEXT) },
                            )
                        }
                    }

                    // ── Volume row ───────────────────────────────────────────
                    val canVol = entity.hasMediaFeature(EntityState.MediaPlayerFeature.VOLUME_SET) ||
                        entity.hasMediaFeature(EntityState.MediaPlayerFeature.VOLUME_STEP)
                    val canMute = entity.hasMediaFeature(EntityState.MediaPlayerFeature.VOLUME_MUTE)

                    if (canVol || canMute) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TransportButton(
                                    label = "🔉",
                                    enabled = canVol,
                                    onClick = { vm.onTransport(MediaTransport.VOLUME_DOWN) },
                                )
                                TransportButton(
                                    label = if (entity.isVolumeMuted) "🔇" else "🔊",
                                    enabled = canMute,
                                    onClick = { vm.onTransport(MediaTransport.MUTE_TOGGLE) },
                                )
                                TransportButton(
                                    label = "🔊",
                                    enabled = canVol,
                                    onClick = { vm.onTransport(MediaTransport.VOLUME_UP) },
                                )
                            }
                        }
                    }

                    // ── Power toggle ─────────────────────────────────────────
                    item {
                        Chip(
                            label = { Text(if (entity.isOn) "⏻ Turn Off" else "⏻ Turn On") },
                            onClick = { vm.onPowerToggle() },
                            colors = if (entity.isOn)
                                ChipDefaults.secondaryChipColors()
                            else
                                ChipDefaults.primaryChipColors(),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }

                    // ── Source list ──────────────────────────────────────────
                    if (state.sourceList.isNotEmpty()) {
                        item {
                            Text(
                                text = "SOURCE",
                                style = MaterialTheme.typography.caption2,
                                color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                            )
                        }
                        items(state.sourceList) { source ->
                            val isActive = source == state.currentSource
                            Chip(
                                label = {
                                    Text(
                                        text = source,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                onClick = { vm.onSelectSource(source) },
                                colors = if (isActive)
                                    ChipDefaults.primaryChipColors(
                                        backgroundColor = WearColors.Primary.copy(alpha = 0.3f),
                                    )
                                else
                                    ChipDefaults.secondaryChipColors(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun TransportButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = if (primary)
            ButtonDefaults.buttonColors(backgroundColor = WearColors.Primary)
        else
            ButtonDefaults.secondaryButtonColors(),
        modifier = Modifier.size(if (primary) 48.dp else 40.dp),
    ) {
        Text(
            text = label,
            fontSize = if (primary) 18.sp else 14.sp,
        )
    }
}
