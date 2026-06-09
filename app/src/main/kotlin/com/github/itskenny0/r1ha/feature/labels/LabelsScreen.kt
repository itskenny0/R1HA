package com.github.itskenny0.r1ha.feature.labels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1EmptyState
import com.github.itskenny0.r1ha.ui.components.R1ErrorState
import com.github.itskenny0.r1ha.ui.components.R1Section
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.SkeletonList
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.icons.R1Icons
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Labels registry browser. A label is HA's user-defined cross-axis category
 * ("daily routine", "needs batteries") that can tag entities, devices, and
 * areas alike. The screen lists each label with its color accent + icon slug,
 * supports search, and drills into a label to show its full footprint grouped
 * by registry kind.
 */
@Composable
fun LabelsScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: LabelsViewModel = viewModel(factory = LabelsViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val visibleLabels by vm.visibleLabels.collectAsState()
    val gridState = rememberLazyGridState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    com.github.itskenny0.r1ha.ui.components.WheelScrollForGrid(
        wheelInput = wheelInput,
        gridState = gridState,
        settings = settings,
    )
    LaunchedEffect(Unit) { vm.refresh() }
    var expandedLabelId by remember { mutableStateOf<String?>(null) }

    fun openInHa(entityId: String) {
        scope.launch {
            val server = runCatching { settings.settings.first().server?.url }.getOrNull()
            if (server.isNullOrBlank()) {
                Toaster.error("No HA server configured")
                return@launch
            }
            val url = "${server.trimEnd('/')}/history?entity_id=$entityId"
            runCatching {
                context.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { t ->
                R1Log.w("Labels", "open-in-HA failed: ${t.message}")
                Toaster.error("No browser to open $url")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(
            title = "LABELS",
            onBack = onBack,
            action = {
                val nextSort = if (ui.sort == LabelsViewModel.Sort.ALPHA)
                    LabelsViewModel.Sort.COUNT else LabelsViewModel.Sort.ALPHA
                val sortSpoken = if (ui.sort == LabelsViewModel.Sort.ALPHA) {
                    "Sorted A to Z. Tap to sort by tagged count."
                } else {
                    "Sorted by tagged count. Tap to sort A to Z."
                }
                R1Chip(
                    text = if (ui.sort == LabelsViewModel.Sort.ALPHA) "A-Z" else "BY COUNT",
                    variant = R1ChipVariant.Action,
                    onClick = { vm.setSort(nextSort) },
                    modifier = Modifier
                        .heightIn(min = R1.MinTarget)
                        .semantics { contentDescription = sortSpoken },
                )
            },
        )
        val dimens = rememberResponsiveDimens()
        com.github.itskenny0.r1ha.ui.layout.AdaptiveContent(modifier = Modifier.weight(1f)) {
            when {
                ui.loading && ui.labels.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    SkeletonList()
                }
                ui.error != null && ui.labels.isEmpty() -> R1ErrorState(
                    title = "COULDN'T LOAD LABELS",
                    message = ui.error,
                    onRetry = { vm.refresh() },
                )
                ui.labels.isEmpty() -> R1EmptyState(
                    title = "NO LABELS",
                    body = "Labels are optional cross-axis tags " +
                        "(\"needs batteries\", \"rec room AV\") you add in Home " +
                        "Assistant under Settings, Labels. Once you tag entities, " +
                        "devices, or areas they show up here.",
                )
                else -> androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                    isRefreshing = ui.refreshing,
                    onRefresh = { vm.refresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // On roomy tiers fan the label cards into dashboardColumns so
                    // the wide panel reads as a tidy multi-column grid rather than
                    // one stretched column; mini / compact stay a single column.
                    // The search field and any empty-state line span the full row.
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(dimens.dashboardColumns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = dimens.screenGutter, vertical = R1.space.s,
                        ),
                        verticalArrangement = Arrangement.spacedBy(R1.space.xs),
                        horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
                    ) {
                        item(key = "__search", span = { GridItemSpan(maxLineSpan) }) {
                            R1TextField(
                                value = ui.query,
                                onValueChange = { vm.setQuery(it) },
                                placeholder = "Search labels",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (visibleLabels.isEmpty()) {
                            item(key = "__noresults", span = { GridItemSpan(maxLineSpan) }) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = R1.space.xl),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "No labels match \"${ui.query}\".",
                                        style = responsiveType(R1.labelMicro),
                                        color = R1.InkMuted,
                                    )
                                }
                            }
                        }
                        items(items = visibleLabels, key = { it.id }) { label ->
                            LabelRow(
                                label = label,
                                expanded = expandedLabelId == label.id,
                                onToggle = {
                                    expandedLabelId =
                                        if (expandedLabelId == label.id) null else label.id
                                },
                                onTapEntity = { eid -> openInHa(eid) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LabelRow(
    label: LabelsViewModel.Label,
    expanded: Boolean,
    onToggle: () -> Unit,
    onTapEntity: (String) -> Unit,
) {
    // The raw label color drives the small swatch (HA shows the true color
    // there); the accent used for text/count is lifted to a legible tone so a
    // near-black label color is not invisible on the dark surface.
    val swatchColor: Color = remember(label) {
        LabelLogic.parseLabelColor(label.color, R1.AccentWarm)
    }
    val accent: Color = remember(swatchColor) {
        LabelLogic.accentOnDark(swatchColor, R1.AccentWarm)
    }
    val iconSlug = remember(label) { LabelLogic.normalizeIcon(label.icon) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
    ) {
        // The header Row is the toggle target. Merge swatch / name / icon-slug /
        // count / chevron into one spoken phrase (count in words, expand state
        // announced) on the Row itself, so the expanded member rows below stay
        // individually focusable as the accessible path into the label.
        val rowDescription = remember(label, expanded) {
            buildString {
                append(
                    LabelLogic.labelRowLabel(
                        name = label.name,
                        memberCount = label.memberCount,
                        expanded = expanded,
                    ),
                )
                // Speak the description too; r1Pressable's explicit
                // contentDescription replaces descendant text, so the
                // visible description line is otherwise silent.
                if (!label.description.isNullOrBlank()) {
                    append(" ")
                    append(label.description.trim())
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = R1.MinTarget)
                .r1Pressable(
                    onClick = onToggle,
                    contentDescription = rowDescription,
                ),
        ) {
            // Swatch shows the label's true HA color; the hairline keeps it
            // visible even for a near-black or white color.
            Box(
                modifier = Modifier
                    .size(R1.space.m)
                    .clip(R1.ShapeS)
                    .background(swatchColor)
                    .border(1.dp, R1.Hairline, R1.ShapeS),
            )
            Spacer(Modifier.width(R1.space.s))
            // The label's configured mdi icon, rendered as an in-house glyph
            // (when we curate that slug) rather than the literal "mdi:..." text
            // it used to show. Tinted to the legible accent. Omitted when the
            // slug isn't curated.
            val iconGlyph = remember(iconSlug) { R1Icons.forMdi(iconSlug) }
            if (iconGlyph != null) {
                Icon(
                    imageVector = iconGlyph,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(R1.space.s))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label.name,
                    style = responsiveType(R1.body),
                    color = R1.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!label.description.isNullOrBlank()) {
                    Text(
                        text = label.description,
                        style = responsiveType(R1.labelMicro),
                        color = R1.InkSoft,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(R1.space.s))
            Text(
                text = "${label.memberCount}",
                style = responsiveType(R1.labelMicro),
                color = accent,
            )
            Spacer(Modifier.width(R1.space.xs))
            Text(
                text = if (expanded) "v" else ">",
                style = responsiveType(R1.labelMicro),
                color = R1.InkSoft,
            )
        }
        if (expanded) {
            val membership = remember(label) {
                LabelLogic.groupMembership(
                    entities = label.entities,
                    devices = label.devices,
                    areas = label.areas,
                )
            }
            Spacer(Modifier.height(R1.space.xs))
            if (membership.isEmpty) {
                Text(
                    text = "Nothing is tagged with this label yet.",
                    style = responsiveType(R1.labelMicro),
                    color = R1.InkMuted,
                )
            } else {
                MemberGroup(
                    title = "Entities",
                    members = membership.entities,
                    accent = accent,
                    onTap = { onTapEntity(it.id) },
                )
                MemberGroup(
                    title = "Devices",
                    members = membership.devices,
                    accent = accent,
                    onTap = null,
                )
                MemberGroup(
                    title = "Areas",
                    members = membership.areas,
                    accent = accent,
                    onTap = null,
                )
            }
        }
    }
}

@Composable
private fun MemberGroup(
    title: String,
    members: List<LabelLogic.LabelMember>,
    accent: Color,
    onTap: ((LabelLogic.LabelMember) -> Unit)?,
) {
    if (members.isEmpty()) return
    R1Section(
        title = title,
        count = members.size,
        topSpace = R1.space.s,
    ) {
        for (m in members) {
            val rowMod = if (onTap != null) {
                Modifier
                    .heightIn(min = R1.MinTarget)
                    .r1Pressable(
                        onClick = { onTap(m) },
                        contentDescription = LabelLogic.memberRowLabel(
                            name = m.name,
                            kind = m.kind,
                            tappable = true,
                        ),
                    )
            } else {
                Modifier.semantics(mergeDescendants = true) {
                    contentDescription = LabelLogic.memberRowLabel(
                        name = m.name,
                        kind = m.kind,
                        tappable = false,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(rowMod)
                    .padding(horizontal = R1.space.l, vertical = R1.space.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Lead each member with a glyph for its kind: the entity's own
                // domain glyph, a zone/area marker for areas, a generic chip for
                // devices. Tinted to the label accent so the group reads as one.
                Icon(
                    imageVector = when (m.kind) {
                        LabelLogic.MemberKind.ENTITY -> R1Icons.forEntity(m.id)
                        LabelLogic.MemberKind.AREA -> R1Icons.forDomain("zone")
                        LabelLogic.MemberKind.DEVICE -> R1Icons.forDomain("generic")
                    },
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(R1.space.s))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = m.name,
                        style = responsiveType(R1.labelMicro),
                        color = R1.InkSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (m.name != m.id) {
                        Text(
                            text = m.id,
                            style = responsiveType(R1.labelMicro),
                            color = R1.InkMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
