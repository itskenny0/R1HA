package com.github.itskenny0.r1ha.wear.feature.cardstack

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
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
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.LovelaceViewInfo
import com.github.itskenny0.r1ha.core.input.WheelEvent
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.wear.theme.WearColors

/**
 * Main watch screen — Lovelace-tab navigation.
 *
 * The screen mirrors the user's HA dashboard tabs (Home, Mouse, Lights…):
 *  - **Bezel / crown** rotates to switch between tabs.
 *  - **Swipe up/down** scrolls the entity list within the current tab.
 *  - Each entity chip shows a domain emoji, friendly name and live state;
 *    tapping it toggles the entity.
 *  - Tabs that have a custom card (e.g. the Mouse touchpad) show a
 *    "Remote Control" shortcut chip at the top in addition to any
 *    standard entities in that tab.
 *
 * ```
 *   ┌─────────────────────────┐
 *   │  12:34                  │  ← TimeText
 *   │  🏠 Home  (1/6)         │  ← tab title + position
 *   │  ─────────────────────  │
 *   │  💡 LR Lights      ON   │  ← entity chip
 *   │  🔌 Front Door Lock OFF │
 *   │  📺 Living Room TV  off │
 *   │  …                      │
 *   │         [≡] [⚙]         │  ← bottom action chips
 *   └─────────────────────────┘
 * ```
 */
@Composable
fun WearCardStackScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onOpenMenu: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFavoritesPicker: () -> Unit,
    onOpenRemote: () -> Unit,
) {
    val vm: WearCardStackViewModel = viewModel(
        factory = WearCardStackViewModel.factory(haRepository),
    )
    val state by vm.uiState.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState(pageCount = { state.views.size.coerceAtLeast(1) })

    // Sync tab index back to VM when the pager settles.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            vm.onTabChanged(page)
        }
    }

    // Bezel / crown navigates between tabs.
    LaunchedEffect(wheelInput, pagerState) {
        wheelInput.events.collect { event ->
            val delta = if (event.direction == WheelEvent.Direction.DOWN) 1 else -1
            val target = (pagerState.currentPage + delta)
                .coerceIn(0, (pagerState.pageCount - 1).coerceAtLeast(0))
            if (target != pagerState.currentPage) {
                pagerState.animateScrollToPage(target)
            }
        }
    }

    Scaffold(timeText = { TimeText() }) {
        when {
            state.loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("Loading dashboard…", style = MaterialTheme.typography.caption1)
                    }
                }
            }

            state.error != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        Text(
                            text = state.error!!,
                            style = MaterialTheme.typography.caption2,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colors.error,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(12.dp))
                        SmallChip(label = "Retry", onClick = { vm.retry() })
                    }
                }
            }

            else -> {
                VerticalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val view = state.views.getOrNull(page)
                        ?: return@VerticalPager
                    TabPage(
                        view = view,
                        tabIndex = page,
                        totalTabs = state.views.size,
                        entityStates = state.entityStates,
                        onEntityTap = { vm.onEntityTap(it) },
                        onOpenMenu = onOpenMenu,
                        onOpenSettings = onOpenSettings,
                        onOpenRemote = onOpenRemote,
                    )
                }
            }
        }
    }
}

// ── Tab page ─────────────────────────────────────────────────────────────────

@Composable
private fun TabPage(
    view: LovelaceViewInfo,
    tabIndex: Int,
    totalTabs: Int,
    entityStates: Map<EntityId, EntityState>,
    onEntityTap: (EntityState) -> Unit,
    onOpenMenu: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRemote: () -> Unit,
) {
    val listState = rememberScalingLazyListState()

    Box(Modifier.fillMaxSize()) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 28.dp,
                bottom = 56.dp,   // leave room for bottom action row
                start = 8.dp,
                end = 8.dp,
            ),
        ) {
            // Tab title header
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                ) {
                    Text(
                        text = view.title.uppercase(),
                        style = MaterialTheme.typography.title3,
                        fontWeight = FontWeight.Bold,
                        color = WearColors.Primary,
                        textAlign = TextAlign.Center,
                    )
                    TabDots(total = totalTabs, current = tabIndex)
                }
            }

            // Remote Control shortcut (Mouse tab / any tab with custom cards)
            if (view.hasRemoteCard) {
                item {
                    Chip(
                        label = { Text("🖥 Remote Control") },
                        onClick = onOpenRemote,
                        colors = ChipDefaults.primaryChipColors(
                            backgroundColor = WearColors.Primary.copy(alpha = 0.25f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Entity chips
            val entities = view.entityIds.mapNotNull { entityStates[EntityId(it)] }
            if (entities.isEmpty() && !view.hasRemoteCard) {
                item {
                    Text(
                        text = "Connecting…",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                items(entities.size) { i ->
                    val entity = entities[i]
                    EntityChip(entity = entity, onTap = { onEntityTap(entity) })
                }
            }
        }

        // Bottom action row: always visible above the list
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallChip(label = "≡", onClick = onOpenMenu)
            SmallChip(label = "⚙", onClick = onOpenSettings)
        }
    }
}

// ── Entity chip ───────────────────────────────────────────────────────────────

@Composable
private fun EntityChip(entity: EntityState, onTap: () -> Unit) {
    val stateLabel = when {
        !entity.isAvailable -> "unavailable"
        entity.unit != null -> "${entity.raw ?: "—"} ${entity.unit}"
        entity.isOn         -> "ON"
        else                -> "off"
    }
    val labelColor = if (entity.isOn) WearColors.Primary
    else MaterialTheme.colors.onBackground.copy(alpha = 0.55f)

    Chip(
        label = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = domainEmoji(entity.id.domain, entity.isOn),
                        fontSize = 14.sp,
                    )
                    Text(
                        text = entity.friendlyName,
                        style = MaterialTheme.typography.caption1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.caption2,
                    color = labelColor,
                    maxLines = 1,
                )
            }
        },
        onClick = onTap,
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun TabDots(total: Int, current: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 4.dp),
    ) {
        repeat(total.coerceAtMost(10)) { i ->
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
    }
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

private fun domainEmoji(domain: Domain, isOn: Boolean): String = when (domain) {
    Domain.LIGHT         -> if (isOn) "💡" else "🔦"
    Domain.SWITCH        -> if (isOn) "🔌" else "⭕"
    Domain.INPUT_BOOLEAN -> if (isOn) "✅" else "⬜"
    Domain.FAN           -> "🌀"
    Domain.COVER         -> if (isOn) "⬆" else "⬇"
    Domain.SCENE         -> "🎬"
    Domain.SCRIPT        -> "▶"
    Domain.AUTOMATION    -> "⚙"
    Domain.MEDIA_PLAYER  -> if (isOn) "🔊" else "🔇"
    Domain.CLIMATE       -> "🌡"
    Domain.LOCK          -> if (isOn) "🔓" else "🔒"
    Domain.SENSOR,
    Domain.BINARY_SENSOR -> "📊"
    Domain.CAMERA        -> "📷"
    Domain.WEATHER       -> "⛅"
    Domain.PERSON        -> "👤"
    else                 -> "●"
}
