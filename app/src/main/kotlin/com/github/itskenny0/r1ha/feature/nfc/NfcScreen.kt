package com.github.itskenny0.r1ha.feature.nfc

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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.HaTag
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Button
import com.github.itskenny0.r1ha.ui.components.R1ButtonVariant
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1Section
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent

/**
 * NFC screen: a tester's-eye view of HA's tag registry. Lists every NFC / QR
 * tag HA knows about with its friendly name, raw id, and when it last fired,
 * newest-scan-first. Each row carries a SCAN button that fires the same
 * `tag_scanned` event a real tap would, so a tag-trigger automation can be
 * exercised without the physical tag. A manual field at the top fires an
 * arbitrary id for tags that aren't registered yet.
 *
 * Read-mostly and additive: renaming / deleting tags lives on the dedicated
 * Tags registry editor; this surface focuses on auditing and testing scans.
 */
@Composable
fun NfcScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: NfcViewModel = viewModel(factory = NfcViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    LaunchedEffect(Unit) { vm.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        R1TopBar(
            title = "NFC TAGS",
            onBack = onBack,
            action = {
                R1Chip(
                    text = if (ui.loading) "…" else "REFRESH",
                    variant = R1ChipVariant.Action,
                    onClick = { vm.refresh() },
                    contentDescription = "Refresh tags",
                )
            },
        )
        AdaptiveContent(modifier = Modifier.weight(1f)) {
            when {
                ui.loading && ui.tags.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = R1.AccentWarm,
                    )
                }
                ui.error != null && ui.tags.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(R1.space.xl),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Tag list failed: ${ui.error}",
                        style = R1.body,
                        color = R1.StatusRed,
                    )
                }
                else -> PullToRefreshBox(
                    isRefreshing = ui.loading,
                    onRefresh = { vm.refresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal = R1.space.m,
                            vertical = R1.space.s,
                        ),
                        verticalArrangement = Arrangement.spacedBy(R1.space.xs),
                    ) {
                        item {
                            SimulateScanCard(
                                manualId = ui.manualId,
                                firing = ui.firingId != null,
                                onIdChange = { vm.setManualId(it) },
                                onFire = { vm.simulateScan(ui.manualId) },
                            )
                        }
                        if (ui.tags.isEmpty()) {
                            item {
                                R1Section(title = "REGISTERED TAGS", count = 0) {
                                    Text(
                                        text = "No tags registered yet. Scan an NFC / QR tag at HA " +
                                            "to register it, or fire an id above to test a " +
                                            "tag-trigger automation.",
                                        style = R1.body,
                                        color = R1.InkMuted,
                                        modifier = Modifier.padding(
                                            horizontal = R1.space.l,
                                            vertical = R1.space.m,
                                        ),
                                    )
                                }
                            }
                        } else {
                            item {
                                R1Section(
                                    title = "REGISTERED TAGS",
                                    count = ui.tags.size,
                                    description = "Newest scan first. SCAN fires a tag_scanned " +
                                        "event so a tag-trigger automation runs as if tapped.",
                                ) {}
                            }
                            items(items = ui.tags, key = { it.id }) { tag ->
                                TagRow(
                                    tag = tag,
                                    firing = ui.firingId == NfcViewModel.normalizeTagId(tag.id),
                                    onSimulate = { vm.simulateScan(tag.id) },
                                )
                            }
                        }
                        item { Spacer(Modifier.size(R1.space.xl)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SimulateScanCard(
    manualId: String,
    firing: Boolean,
    onIdChange: (String) -> Unit,
    onFire: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(R1.space.m),
    ) {
        Text(text = "SIMULATE SCAN", style = R1.sectionHeader, color = R1.AccentWarm)
        Spacer(Modifier.size(R1.space.xxs))
        Text(
            text = "Fire a tag_scanned event for any id without the physical tag.",
            style = R1.labelMicro,
            color = R1.InkMuted,
        )
        Spacer(Modifier.size(R1.space.s))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                R1TextField(
                    value = manualId,
                    onValueChange = onIdChange,
                    placeholder = "tag id, e.g. 04a1b2c3",
                    monospace = true,
                )
            }
            Spacer(Modifier.width(R1.space.s))
            R1Button(
                text = if (firing) "…" else "FIRE",
                onClick = onFire,
                enabled = !firing && manualId.isNotBlank(),
            )
        }
    }
}

@Composable
private fun TagRow(
    tag: HaTag,
    firing: Boolean,
    onSimulate: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .heightIn(min = R1.MinTarget)
            .padding(horizontal = R1.space.m, vertical = R1.space.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = NfcViewModel.displayName(tag),
                style = R1.bodyEmph,
                color = R1.Ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (tag.lastScanned != null) {
                RelativeTimeLabel(
                    at = tag.lastScanned,
                    color = R1.AccentCool,
                    style = R1.labelMicro,
                )
            } else {
                Text(text = "NEVER SCANNED", style = R1.labelMicro, color = R1.InkMuted)
            }
        }
        if (!tag.name.isNullOrBlank()) {
            Spacer(Modifier.size(R1.space.xxs))
            Text(
                text = tag.id,
                style = R1.labelMicro.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = TextUnit(10f, TextUnitType.Sp),
                ),
                color = R1.InkMuted,
                maxLines = 1,
            )
        }
        if (!tag.description.isNullOrBlank()) {
            Spacer(Modifier.size(R1.space.xs))
            Text(
                text = tag.description,
                style = R1.labelMicro,
                color = R1.InkSoft,
                maxLines = 3,
            )
        }
        Spacer(Modifier.size(R1.space.s))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            R1Button(
                text = if (firing) "…" else "SCAN",
                onClick = onSimulate,
                enabled = !firing,
                variant = R1ButtonVariant.Outlined,
            )
        }
    }
}
