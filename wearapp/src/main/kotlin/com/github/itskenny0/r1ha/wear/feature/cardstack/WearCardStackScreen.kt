package com.github.itskenny0.r1ha.wear.feature.cardstack

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.wear.theme.WearColors

/**
 * Main screen of the Wear OS app.
 *
 * Displays the user's Home Assistant favourites as a vertical card pager —
 * one entity per page, full-screen.  Swiping up/down navigates between
 * cards. The rotary crown or outer-rim swipe adjusts the value of scalar
 * entities (light brightness, fan speed, cover position, media volume).
 *
 * ## Card layout
 *
 * ```
 *   ┌────────────────────────────────────┐
 *   │  12:34               [•••] menu    │  ← TimeText (watch time)
 *   │                                    │
 *   │        ☀ Living Room Light         │  ← Entity name
 *   │                                    │
 *   │         ████████░░░░░  73 %        │  ← Scalar value bar (if applicable)
 *   │                                    │
 *   │             [ ON ]                 │  ← State label
 *   │                                    │
 *   │    [⚙ Settings]  [▶ Scenes]        │  ← Bottom action chips
 *   └────────────────────────────────────┘
 * ```
 *
 * The scalar value bar doubles as a visual feedback indicator for rotary input:
 * it updates optimistically before the HA round-trip completes.
 *
 * ## Navigation
 *
 * - Swipe left edge → system back (Wear OS swipe-dismiss, handled by nav host)
 * - Tap card → toggle entity (on/off for switches, lights; activate for scenes)
 * - Long press → future: entity detail (not yet implemented)
 */
@Composable
fun WearCardStackScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onOpenScenes: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: WearCardStackViewModel = viewModel(
        factory = WearCardStackViewModel.factory(haRepository, settings, wheelInput),
    )
    val state by vm.uiState.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState(pageCount = { state.cards.size.coerceAtLeast(1) })

    // Keep VM's currentIndex in sync with the pager position.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            vm.onPageChanged(page)
        }
    }

    Scaffold(timeText = { TimeText() }) {
        when {
            !state.settingsLoaded -> {
                // DataStore still loading.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.favouritesCount == 0 -> {
                // No favourites configured.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No favourites.\nSet them in the phone app.",
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }

            state.cards.isEmpty() -> {
                // Favourites configured but HA hasn't sent state yet (connecting).
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("Connecting…", style = MaterialTheme.typography.caption1)
                    }
                }
            }

            else -> {
                VerticalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val entity = state.cards.getOrNull(page) ?: return@VerticalPager
                    val displayPct = state.optimisticPercents[entity.id] ?: entity.percent

                    EntityCardPage(
                        entity = entity,
                        displayPct = displayPct,
                        totalPages = state.cards.size,
                        currentPage = page,
                        onTap = { vm.onCardTap(entity) },
                        onOpenScenes = onOpenScenes,
                        onOpenSettings = onOpenSettings,
                    )
                }
            }
        }
    }
}

// ── Individual entity card page ──────────────────────────────────────────────

@Composable
private fun EntityCardPage(
    entity: EntityState,
    displayPct: Int?,
    totalPages: Int,
    currentPage: Int,
    onTap: () -> Unit,
    onOpenScenes: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WearColors.Bg)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Domain icon glyph
            DomainIcon(
                domain = entity.id.domain,
                isOn = entity.isOn,
                modifier = Modifier.size(28.dp),
            )

            Spacer(Modifier.height(6.dp))

            // Entity friendly name
            Text(
                text = entity.friendlyName,
                style = MaterialTheme.typography.title3,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colors.onBackground,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))

            // Scalar progress bar (brightness, volume, etc.)
            if (displayPct != null && entity.supportsScalar) {
                ScalarBar(
                    percent = displayPct,
                    isOn = entity.isOn,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "$displayPct%",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(6.dp))
            }

            // State label (ON / OFF / unavailable / raw state for sensors)
            Text(
                text = friendlyStatLabel(entity),
                style = MaterialTheme.typography.body2,
                color = if (entity.isOn) WearColors.Primary
                else MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
            )

            // Page dots indicator
            if (totalPages > 1) {
                Spacer(Modifier.height(8.dp))
                PageDots(total = totalPages, current = currentPage)
            }
        }

        // Bottom action row: scenes + settings chips
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallChip(label = "Scenes", onClick = onOpenScenes)
            SmallChip(label = "⚙", onClick = onOpenSettings)
        }
    }
}

// ── Helper composables ───────────────────────────────────────────────────────

@Composable
private fun ScalarBar(percent: Int, isOn: Boolean) {
    val fraction by animateFloatAsState(
        targetValue = percent / 100f,
        label = "ScalarBar",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth(0.8f)
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colors.surface),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (isOn) WearColors.Primary else WearColors.OnSurface.copy(alpha = 0.4f)),
        )
    }
}

@Composable
private fun PageDots(total: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(total.coerceAtMost(8)) { i ->
            Box(
                modifier = Modifier
                    .size(if (i == current) 6.dp else 4.dp)
                    .clip(CircleShape)
                    .background(
                        if (i == current) WearColors.Primary
                        else MaterialTheme.colors.onBackground.copy(alpha = 0.3f)
                    ),
            )
        }
        if (total > 8) {
            Text("…", fontSize = 8.sp, color = MaterialTheme.colors.onBackground.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun DomainIcon(domain: Domain, isOn: Boolean, modifier: Modifier = Modifier) {
    val emoji = when (domain) {
        Domain.LIGHT          -> if (isOn) "💡" else "🔦"
        Domain.SWITCH         -> if (isOn) "🔌" else "⭕"
        Domain.INPUT_BOOLEAN  -> if (isOn) "✅" else "⬜"
        Domain.FAN            -> "🌀"
        Domain.COVER          -> if (isOn) "⬆" else "⬇"
        Domain.SCENE          -> "🎬"
        Domain.SCRIPT         -> "▶"
        Domain.AUTOMATION     -> "⚙"
        Domain.MEDIA_PLAYER   -> if (isOn) "🔊" else "🔇"
        Domain.CLIMATE        -> "🌡"
        Domain.LOCK           -> if (isOn) "🔓" else "🔒"
        Domain.SENSOR,
        Domain.BINARY_SENSOR  -> "📊"
        Domain.CAMERA         -> "📷"
        Domain.WEATHER        -> "⛅"
        Domain.PERSON         -> "👤"
        else                  -> "●"
    }
    Text(text = emoji, fontSize = 24.sp, modifier = modifier)
}

@Composable
private fun SmallChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colors.surface.copy(alpha = 0.8f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onSurface,
        )
    }
}

private fun friendlyStatLabel(entity: EntityState): String = when {
    !entity.isAvailable      -> "unavailable"
    entity.unit != null      -> "${entity.raw ?: "—"} ${entity.unit}"
    entity.isOn              -> "ON"
    else                     -> "off"
}
