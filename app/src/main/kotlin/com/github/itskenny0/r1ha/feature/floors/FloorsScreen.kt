package com.github.itskenny0.r1ha.feature.floors

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.icons.R1Icons
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent

/**
 * Floors registry browser: lists HA's floor primitives (groupings of
 * areas) ordered by building level, with the constituent areas and their
 * entity counts rolled up. A useful at-a-glance overview of "what's
 * installed where" on a multi-storey install. Floors are an optional HA
 * concept, so the empty state explains that rather than reading as an error.
 */
@Composable
fun FloorsScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: FloorsViewModel = viewModel(factory = FloorsViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    val dimens = rememberResponsiveDimens()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    LaunchedEffect(Unit) { vm.refresh() }
    // Expansion is keyed on the stable floor_id, not the display name: two
    // floors can share a name, and a rename mustn't silently move the open row.
    var expandedFloorId by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "FLOORS", onBack = onBack)
        AdaptiveContent(modifier = Modifier.weight(1f)) {
            when {
                ui.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = R1.AccentWarm,
                    )
                }
                ui.error != null && ui.floors.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(R1.space.xl),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = ui.error ?: "Error", style = responsiveType(R1.body), color = R1.StatusRed)
                }
                ui.floors.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(R1.space.xxl),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "NO FLOORS",
                            style = responsiveType(R1.sectionHeader),
                            color = R1.InkSoft,
                        )
                        Spacer(Modifier.size(R1.space.s))
                        Text(
                            text = "Floors are an optional HA concept that group areas " +
                                "by building storey. Add them under Settings, Areas & Zones, " +
                                "Floors to see them here.",
                            style = responsiveType(R1.body),
                            color = R1.InkMuted,
                        )
                    }
                }
                else -> PullToRefreshBox(
                    isRefreshing = ui.refreshing,
                    onRefresh = { vm.refresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal = dimens.screenGutter,
                            vertical = R1.space.s,
                        ),
                        verticalArrangement = Arrangement.spacedBy(R1.space.xs),
                    ) {
                        items(items = ui.floors, key = { it.floorId }) { floor ->
                            FloorRow(
                                floor = floor,
                                expanded = expandedFloorId == floor.floorId,
                                onToggle = {
                                    expandedFloorId =
                                        if (expandedFloorId == floor.floorId) null else floor.floorId
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact, human label for a floor's building level. HA stores level as a
 * signed integer (0 = ground, negative = below ground), so we surface the
 * common cases by name and fall back to a signed "L" badge for the rest.
 */
private fun levelLabel(level: Int): String = when (level) {
    0 -> "GROUND"
    -1 -> "BASEMENT"
    else -> if (level > 0) "L$level" else "B${-level}"
}

@Composable
private fun FloorRow(
    floor: FloorsViewModel.Floor,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val totalEntities = floor.areas.sumOf { it.entityCount }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onToggle)
            .heightIn(min = R1.MinTarget)
            .semantics {
                contentDescription = buildString {
                    append(floor.name)
                    floor.level?.let { append(", level ${levelLabel(it).lowercase()}") }
                    append(", ")
                    append(FloorsViewModel.floorTally(floor.areas.size, totalEntities, ", "))
                    append(if (expanded) ". Expanded." else ". Tap to expand.")
                }
            }
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Level badge leads the row: it is the axis HA orders floors on,
            // so showing it makes the list's ordering legible at a glance.
            floor.level?.let { lvl ->
                Text(
                    text = levelLabel(lvl),
                    style = responsiveType(R1.labelMicro),
                    color = R1.AccentNeutral,
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.Surface)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .padding(horizontal = R1.space.s, vertical = R1.space.xxs),
                )
                Spacer(Modifier.width(R1.space.s))
            }
            // Floor glyph: the user's configured mdi icon when we curate it,
            // otherwise the in-house generic marker. Leads the name so floors
            // scan as a row type alongside areas / zones.
            Icon(
                imageVector = R1Icons.forMdi(floor.icon) ?: R1Icons.forDomain("generic"),
                contentDescription = null,
                tint = R1.AccentNeutral,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(R1.space.s))
            Text(
                text = floor.name,
                style = responsiveType(R1.body),
                color = R1.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(R1.space.s))
            Text(
                text = FloorsViewModel.floorTally(floor.areas.size, totalEntities, " · "),
                style = responsiveType(R1.labelMicro),
                color = R1.AccentWarm,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(R1.space.xs))
            Text(
                text = if (expanded) "▾" else "▸",
                style = responsiveType(R1.labelMicro),
                color = R1.InkSoft,
            )
        }
        if (expanded && floor.areas.isNotEmpty()) {
            Spacer(Modifier.size(R1.space.xs))
            Column(verticalArrangement = Arrangement.spacedBy(R1.space.xxs)) {
                for (a in floor.areas) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Each constituent area leads with the in-house area /
                        // zone marker so the expanded list reads as areas.
                        Icon(
                            imageVector = R1Icons.forDomain("zone"),
                            contentDescription = null,
                            tint = R1.InkMuted,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(R1.space.xs))
                        Text(
                            text = a.name,
                            style = responsiveType(R1.labelMicro),
                            color = R1.InkSoft,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(R1.space.s))
                        Text(
                            text = "${a.entityCount}",
                            style = responsiveType(R1.labelMicro),
                            color = R1.InkMuted,
                        )
                    }
                }
            }
        }
        if (expanded && floor.areas.isEmpty()) {
            Spacer(Modifier.size(R1.space.xs))
            Text(
                text = "No areas assigned to this floor.",
                style = responsiveType(R1.labelMicro),
                color = R1.InkMuted,
            )
        }
    }
}
