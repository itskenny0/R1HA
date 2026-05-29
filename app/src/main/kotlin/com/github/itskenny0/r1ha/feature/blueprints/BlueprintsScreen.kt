package com.github.itskenny0.r1ha.feature.blueprints

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.BlueprintInfo
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Button
import com.github.itskenny0.r1ha.ui.components.R1ButtonVariant
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent

/**
 * Blueprints browser: lists installed automation + script blueprints in
 * two collapsible sections, with a top-bar IMPORT FROM URL chip that opens
 * a two-stage dialog (paste URL : preview HA's parse + validation : INSTALL).
 *
 * Install lands in HA's `blueprints/<domain>/` directory via the WS
 * `blueprint/save` command, mirroring what HA's frontend's "Import" button
 * does. Creating actual automations / scripts from a blueprint stays in
 * HA's web UI: that flow ships a dynamic schema editor per blueprint input
 * which the native client doesn't reimplement.
 */
@Composable
fun BlueprintsScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: BlueprintsViewModel = viewModel(
        factory = BlueprintsViewModel.factory(haRepository),
    )
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
            title = "BLUEPRINTS",
            onBack = onBack,
            action = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
                ) {
                    R1Chip(
                        text = "IMPORT URL",
                        variant = R1ChipVariant.Action,
                        selected = true,
                        onClick = { vm.openImportDialog() },
                        contentDescription = "Import blueprint from URL",
                    )
                    R1Chip(
                        text = if (ui.loading) "..." else "REFRESH",
                        variant = R1ChipVariant.Action,
                        onClick = { vm.refresh() },
                        contentDescription = "Refresh blueprints",
                    )
                }
            },
        )
        AdaptiveContent(modifier = Modifier.weight(1f)) {
            when {
                ui.loading && ui.totalCount == 0 -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = R1.AccentWarm,
                    )
                }
                ui.error != null && ui.totalCount == 0 -> ErrorState(message = ui.error.orEmpty())
                ui.totalCount == 0 -> EmptyState(
                    message = "No blueprints installed yet. Use IMPORT URL above to add one " +
                        "from a HA community post.",
                )
                else -> PullToRefreshBox(
                    isRefreshing = ui.loading,
                    onRefresh = { vm.refresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = R1.space.m, vertical = R1.space.s),
                        verticalArrangement = Arrangement.spacedBy(R1.space.xs),
                    ) {
                        item(key = "automation_header") {
                            SectionHeader(
                                label = "AUTOMATION BLUEPRINTS",
                                count = ui.automations.size,
                                expanded = ui.automationsExpanded,
                                onToggle = { vm.toggleAutomations() },
                            )
                        }
                        if (ui.automationsExpanded) {
                            if (ui.automations.isEmpty()) {
                                item(key = "automation_empty") {
                                    EmptySectionHint(
                                        message = "No automation blueprints on disk.",
                                    )
                                }
                            } else {
                                for (bp in ui.automations) {
                                    item(key = "auto/${bp.path}") {
                                        BlueprintRow(blueprint = bp)
                                    }
                                }
                            }
                        }
                        item(key = "script_header") {
                            SectionHeader(
                                label = "SCRIPT BLUEPRINTS",
                                count = ui.scripts.size,
                                expanded = ui.scriptsExpanded,
                                onToggle = { vm.toggleScripts() },
                            )
                        }
                        if (ui.scriptsExpanded) {
                            if (ui.scripts.isEmpty()) {
                                item(key = "script_empty") {
                                    EmptySectionHint(
                                        message = "No script blueprints on disk.",
                                    )
                                }
                            } else {
                                for (bp in ui.scripts) {
                                    item(key = "script/${bp.path}") {
                                        BlueprintRow(blueprint = bp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Import flow overlay. Lives outside the AdaptiveContent because the
    // Dialog hoists its own window and shouldn't be tied to the LazyColumn
    // layout above.
    if (ui.importPhase != BlueprintsViewModel.ImportPhase.NONE) {
        ImportFlowDialog(
            phase = ui.importPhase,
            url = ui.importUrl,
            preview = ui.previewBlueprint,
            error = ui.importError,
            onUrlChange = { vm.setImportUrl(it) },
            onPreview = { vm.previewImport() },
            onInstall = { vm.installImport() },
            onCancel = { vm.cancelImport() },
        )
    }
}

@Composable
private fun SectionHeader(
    label: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    // Canonical group-header treatment (matches R1Section's title line) made tappable so the
    // section collapses: uppercase section-header type in the accent colour, a hairline rule
    // filling the gap, a count pill, and a +/- expand glyph at the right edge.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .r1Pressable(onClick = onToggle, contentDescription = "Toggle $label")
            .padding(top = R1.space.s, bottom = R1.space.xs, start = R1.space.xs, end = R1.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = R1.sectionHeader,
            color = R1.AccentWarm,
        )
        Spacer(Modifier.width(R1.space.m))
        Box(
            modifier = Modifier
                .height(1.dp)
                .weight(1f)
                .background(R1.Hairline),
        )
        Spacer(Modifier.width(R1.space.s))
        R1Chip(text = "$count", variant = R1ChipVariant.Pill, tone = R1.InkSoft)
        Spacer(Modifier.width(R1.space.s))
        Text(
            text = if (expanded) "−" else "+",
            style = R1.bodyEmph,
            color = R1.InkSoft,
        )
    }
}

@Composable
private fun BlueprintRow(blueprint: BlueprintInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = R1.space.m, vertical = R1.space.m),
        verticalArrangement = Arrangement.spacedBy(R1.space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = blueprint.name,
                style = R1.bodyEmph,
                color = R1.Ink,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            Spacer(Modifier.width(R1.space.s))
            R1Chip(
                text = blueprint.domain.uppercase(),
                variant = R1ChipVariant.Pill,
                tone = domainTone(blueprint.domain),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = blueprint.path,
                style = R1.labelMicro.copy(fontFamily = FontFamily.Monospace),
                color = R1.InkSoft,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            BlueprintGrouping.inputChipLabel(blueprint.inputCount)?.let { label ->
                Spacer(Modifier.width(R1.space.s))
                R1Chip(
                    text = label,
                    variant = R1ChipVariant.Pill,
                    tone = R1.AccentCool,
                )
            }
        }
        val src = blueprint.sourceUrl
        if (!src.isNullOrBlank()) {
            Text(
                text = src,
                style = R1.labelMicro.copy(fontFamily = FontFamily.Monospace),
                color = R1.InkMuted,
                maxLines = 1,
            )
        }
        if (blueprint.description.isNotBlank()) {
            Text(
                text = blueprint.description,
                style = R1.labelMicro,
                color = R1.InkMuted,
                maxLines = 3,
            )
        }
    }
}

/** Domain accent: automation = warm, script = cool, anything else muted. */
private fun domainTone(domain: String): Color = when (domain.lowercase()) {
    "automation" -> R1.AccentWarm
    "script" -> R1.AccentCool
    else -> R1.InkMuted
}

@Composable
private fun EmptySectionHint(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.m, vertical = R1.space.m),
    ) {
        Text(text = message, style = R1.labelMicro, color = R1.InkMuted)
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(R1.space.xl),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = message, style = R1.body, color = R1.InkMuted)
    }
}

@Composable
private fun ErrorState(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(R1.space.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "COULDN'T LOAD BLUEPRINTS", style = R1.labelMicro, color = R1.StatusAmber)
        Spacer(Modifier.height(R1.space.s))
        Text(text = message, style = R1.body, color = R1.InkSoft)
        Spacer(Modifier.height(R1.space.m))
        Text(
            text = "blueprint/list only flows over the live WebSocket. Retry once it reconnects.",
            style = R1.labelMicro,
            color = R1.InkMuted,
        )
    }
}

/**
 * Modal import flow. Phase-driven: URL_PROMPT shows the text input + IMPORT
 * action; IMPORTING blocks with a spinner; PREVIEW shows the parsed metadata
 * + INSTALL action; INSTALLING blocks during the save. The same dialog
 * frame stays mounted across phases so the back button + outside-tap
 * cancel reliably regardless of state.
 */
@Composable
private fun ImportFlowDialog(
    phase: BlueprintsViewModel.ImportPhase,
    url: String,
    preview: BlueprintInfo?,
    error: String?,
    onUrlChange: (String) -> Unit,
    onPreview: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(R1.ShapeM)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeM)
                .padding(horizontal = R1.space.l, vertical = R1.space.l),
            verticalArrangement = Arrangement.spacedBy(R1.space.m),
        ) {
            Text(
                text = when (phase) {
                    BlueprintsViewModel.ImportPhase.PREVIEW,
                    BlueprintsViewModel.ImportPhase.INSTALLING -> "INSTALL BLUEPRINT"
                    else -> "IMPORT BLUEPRINT FROM URL"
                },
                style = R1.sectionHeader,
                color = R1.AccentWarm,
            )
            when (phase) {
                BlueprintsViewModel.ImportPhase.URL_PROMPT,
                BlueprintsViewModel.ImportPhase.IMPORTING -> {
                    Text(
                        text = "Paste a HA community blueprint URL, GitHub permalink, " +
                            "or raw YAML URL. HA fetches and validates before anything " +
                            "lands on disk.",
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                    )
                    R1TextField(
                        value = url,
                        onValueChange = onUrlChange,
                        placeholder = "https://community.home-assistant.io/...",
                        monospace = true,
                        enabled = phase == BlueprintsViewModel.ImportPhase.URL_PROMPT,
                    )
                    if (!error.isNullOrBlank()) {
                        Text(
                            text = error,
                            style = R1.labelMicro,
                            color = R1.StatusRed,
                            maxLines = 4,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(R1.space.s),
                    ) {
                        R1Button(
                            text = "CANCEL",
                            onClick = onCancel,
                            modifier = Modifier.weight(1f),
                            variant = R1ButtonVariant.Outlined,
                        )
                        R1Button(
                            text = if (phase == BlueprintsViewModel.ImportPhase.IMPORTING)
                                "FETCHING…" else "IMPORT",
                            onClick = onPreview,
                            modifier = Modifier.weight(1f),
                            enabled = phase == BlueprintsViewModel.ImportPhase.URL_PROMPT &&
                                url.isNotBlank(),
                        )
                    }
                }
                BlueprintsViewModel.ImportPhase.PREVIEW,
                BlueprintsViewModel.ImportPhase.INSTALLING -> {
                    if (preview != null) {
                        PreviewPane(preview = preview)
                    }
                    if (!error.isNullOrBlank()) {
                        Text(
                            text = error,
                            style = R1.labelMicro,
                            color = R1.StatusRed,
                            maxLines = 6,
                        )
                    }
                    val canInstall = BlueprintGrouping.canInstall(preview) &&
                        phase == BlueprintsViewModel.ImportPhase.PREVIEW
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(R1.space.s),
                    ) {
                        R1Button(
                            text = "CANCEL",
                            onClick = onCancel,
                            modifier = Modifier.weight(1f),
                            variant = R1ButtonVariant.Outlined,
                        )
                        R1Button(
                            text = if (phase == BlueprintsViewModel.ImportPhase.INSTALLING)
                                "INSTALLING…" else "INSTALL",
                            onClick = onInstall,
                            modifier = Modifier.weight(1f),
                            enabled = canInstall,
                        )
                    }
                }
                BlueprintsViewModel.ImportPhase.NONE -> Unit
            }
        }
    }
}

@Composable
private fun PreviewPane(preview: BlueprintInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(R1.space.m),
        verticalArrangement = Arrangement.spacedBy(R1.space.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = preview.name,
                style = R1.bodyEmph,
                color = R1.Ink,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            Spacer(Modifier.width(R1.space.s))
            R1Chip(
                text = preview.domain.uppercase(),
                variant = R1ChipVariant.Pill,
                tone = domainTone(preview.domain),
            )
        }
        if (preview.path.isNotBlank()) {
            Text(
                text = "→ blueprints/${preview.domain}/${preview.path}",
                style = R1.labelMicro.copy(fontFamily = FontFamily.Monospace),
                color = R1.InkSoft,
                maxLines = 1,
            )
        }
        BlueprintGrouping.inputChipLabel(preview.inputCount)?.let { label ->
            R1Chip(
                text = label,
                variant = R1ChipVariant.Pill,
                tone = R1.AccentCool,
            )
        }
        if (!preview.sourceUrl.isNullOrBlank()) {
            Text(
                text = preview.sourceUrl,
                style = R1.labelMicro.copy(fontFamily = FontFamily.Monospace),
                color = R1.InkMuted,
                maxLines = 2,
            )
        }
        if (preview.description.isNotBlank()) {
            Text(
                text = preview.description,
                style = R1.labelMicro,
                color = R1.InkMuted,
                maxLines = 6,
            )
        }
        // Spell out why INSTALL is greyed out when HA returned no installable
        // payload. validationErrors already render as their own red banner in
        // the dialog, so only cover the structural cases here.
        val blockReason = when {
            preview.validationErrors.isNullOrBlank() && preview.rawYaml.isNullOrBlank() ->
                "This Home Assistant version didn't return the blueprint contents, so it " +
                    "can't be installed from here. Update HA Core, or add it from HA's web UI."
            preview.validationErrors.isNullOrBlank() && preview.path.isBlank() ->
                "HA didn't suggest a filename for this blueprint, so it can't be saved from here."
            else -> null
        }
        if (blockReason != null) {
            Text(
                text = blockReason,
                style = R1.labelMicro,
                color = R1.StatusAmber,
                maxLines = 4,
            )
        }
    }
}
