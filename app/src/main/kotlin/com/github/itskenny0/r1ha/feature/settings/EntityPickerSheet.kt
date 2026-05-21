package com.github.itskenny0.r1ha.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Reusable overlay sheet for picking one HA entity_id from the live
 * registry. Used by the Settings → Quick Settings tile binding so the
 * user doesn't have to type `light.kitchen` by hand.
 *
 * Layout: full-screen translucent backdrop, centred card with title /
 * search bar / scrollable result list. Tap a result → fires onPick;
 * tap the backdrop or fires BackHandler → dismisses.
 *
 * Filter: defaults to toggleable + action domains (the meaningful set
 * for a Quick Settings tile target). Sensors / numbers / selects are
 * excluded — a tile that 'toggles' a sensor would do nothing useful.
 */
@Composable
fun EntityPickerSheet(
    haRepository: HaRepository,
    onPick: (entityId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val entities by produceState<List<EntityState>?>(null) {
        value = haRepository.listAllEntities().getOrNull().orEmpty()
            .filter { it.id.domain in PICKABLE_DOMAINS }
            .sortedBy { it.friendlyName.lowercase() }
    }
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Focus the search field on open so the user can immediately
        // type — matches the QuickSearch UX.
        kotlinx.coroutines.delay(80)
        runCatching { focus.requestFocus() }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.92f))
            .r1Pressable(onClick = onDismiss, hapticOnClick = false)
            .systemBarsPadding()
            // The sheet auto-focuses the search field on open, which pops the
            // IME. Without imePadding() the centred Column would partially sit
            // behind the keyboard (the activity uses enableEdgeToEdge so the
            // window doesn't auto-resize). imePadding() shrinks the available
            // height by the IME inset so the Column re-centres above the
            // keyboard's top edge.
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp)
                .clip(R1.ShapeS)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onClick = {}, hapticOnClick = false)
                .padding(14.dp),
        ) {
            Text(text = "PICK ENTITY", style = R1.sectionHeader, color = R1.AccentWarm)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Toggleable + action entities only",
                style = R1.labelMicro,
                color = R1.InkSoft,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    R1TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "kitchen, fan, scene…",
                        monospace = false,
                        focusRequester = focus,
                    )
                }
                if (query.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .r1Pressable(onClick = { query = "" }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "✕", style = R1.labelMicro, color = R1.InkSoft)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            val all = entities
            when {
                all == null -> Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = R1.AccentWarm,
                    )
                }
                all.isEmpty() -> Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No toggleable entities loaded yet. Sign in or wait for the registry to catch up.",
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                    )
                }
                else -> {
                    // Substring filter on friendly_name + entity_id —
                    // cheap (lists are typically <100 toggleables).
                    val filtered = remember(query, all) {
                        val q = query.trim().lowercase()
                        if (q.isBlank()) all
                        else all.filter {
                            it.friendlyName.lowercase().contains(q) ||
                                it.id.value.lowercase().contains(q)
                        }
                    }
                    if (filtered.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "No matches for '${query}'.",
                                style = R1.labelMicro,
                                color = R1.InkMuted,
                            )
                        }
                    } else {
                        val listState = rememberLazyListState()
                        // Bounded height so the sheet doesn't push the
                        // screen-level padding off the visible area on
                        // small displays. The list scrolls internally.
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(360.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(items = filtered, key = { it.id.value }) { entity ->
                                EntityPickRow(entity = entity, onPick = { onPick(entity.id.value) })
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // Single CANCEL row at the bottom — keeps the dismiss
            // affordance discoverable without depending on the
            // backdrop tap (which a confused user might not realise
            // works).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(R1.ShapeS)
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(onClick = onDismiss)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "CANCEL", style = R1.labelMicro, color = R1.InkSoft)
            }
        }
    }
}

@Composable
private fun EntityPickRow(entity: EntityState, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onPick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = entity.friendlyName, style = R1.body, color = R1.Ink, maxLines = 1)
            Text(
                text = entity.id.value,
                style = R1.labelMicro,
                color = R1.InkSoft,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = entity.id.domain.prefix.uppercase().take(6),
            style = R1.labelMicro,
            color = R1.AccentNeutral,
        )
    }
}

/** Set of domains worth binding to a Quick Settings tile. Toggleable
 *  + action entities only — sensors / numbers / selects would render
 *  meaningless tile state. The Quick Settings UX is fundamentally
 *  one-tap-fires-action; matching against this set hides irrelevant
 *  entities from the picker. */
private val PICKABLE_DOMAINS = setOf(
    Domain.LIGHT,
    Domain.SWITCH,
    Domain.INPUT_BOOLEAN,
    Domain.AUTOMATION,
    Domain.FAN,
    Domain.COVER,
    Domain.LOCK,
    Domain.MEDIA_PLAYER,
    Domain.HUMIDIFIER,
    Domain.CLIMATE,
    Domain.WATER_HEATER,
    Domain.VACUUM,
    Domain.LAWN_MOWER,
    Domain.VALVE,
    Domain.SCENE,
    Domain.SCRIPT,
    Domain.BUTTON,
    Domain.INPUT_BUTTON,
)
