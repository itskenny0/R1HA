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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1Section
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable
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
    val listState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
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
        com.github.itskenny0.r1ha.ui.layout.AdaptiveContent(modifier = Modifier.weight(1f)) {
            when {
                ui.loading && ui.labels.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = R1.AccentWarm,
                    )
                }
                ui.error != null && ui.labels.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = ui.error ?: "Error", style = R1.body, color = R1.StatusRed)
                }
                ui.labels.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No labels defined in HA. Settings, Labels in HA's web UI.",
                        style = R1.body,
                        color = R1.InkMuted,
                    )
                }
                else -> androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                    isRefreshing = ui.loading,
                    onRefresh = { vm.refresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp, vertical = 8.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        item(key = "__search") {
                            R1TextField(
                                value = ui.query,
                                onValueChange = { vm.setQuery(it) },
                                placeholder = "Search labels",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (visibleLabels.isEmpty()) {
                            item(key = "__noresults") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "No labels match \"${ui.query}\".",
                                        style = R1.labelMicro,
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
    val accent: Color = LabelLogic.parseLabelColor(label.color, R1.AccentWarm)
    val iconSlug = LabelLogic.normalizeIcon(label.icon)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        // The header Row is the toggle target. Merge swatch / name / icon-slug /
        // count / chevron into one spoken phrase (count in words, expand state
        // announced) on the Row itself, so the expanded member rows below stay
        // individually focusable as the accessible path into the label.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = R1.MinTarget)
                .r1Pressable(
                    onClick = onToggle,
                    contentDescription = LabelLogic.labelRowLabel(
                        name = label.name,
                        memberCount = label.memberCount,
                        expanded = expanded,
                    ),
                ),
        ) {
            // Color swatch is the consistent accent for the label.
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(R1.ShapeS)
                    .background(accent)
                    .border(1.dp, R1.Hairline, R1.ShapeS),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label.name, style = R1.body, color = R1.Ink, maxLines = 1)
                if (iconSlug != null) {
                    Text(
                        text = "mdi:$iconSlug",
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${label.memberCount}",
                style = R1.labelMicro,
                color = accent,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (expanded) "v" else ">",
                style = R1.labelMicro,
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
            Spacer(Modifier.height(6.dp))
            if (membership.isEmpty) {
                Text(
                    text = "Nothing is tagged with this label yet.",
                    style = R1.labelMicro,
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
                    .padding(horizontal = R1.space.l, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(R1.ShapeS)
                        .background(accent),
                )
                Spacer(Modifier.width(R1.space.s))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = m.name, style = R1.labelMicro, color = R1.InkSoft, maxLines = 1)
                    if (m.name != m.id) {
                        Text(text = m.id, style = R1.labelMicro, color = R1.InkMuted, maxLines = 1)
                    }
                }
            }
        }
    }
}
