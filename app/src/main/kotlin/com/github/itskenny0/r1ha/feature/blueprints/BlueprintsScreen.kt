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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(R1.ShapeS)
                            .background(R1.AccentWarm.copy(alpha = 0.18f))
                            .border(1.dp, R1.AccentWarm.copy(alpha = 0.5f), R1.ShapeS)
                            .r1Pressable(onClick = { vm.openImportDialog() })
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "IMPORT URL",
                            style = R1.labelMicro,
                            color = R1.AccentWarm,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(R1.ShapeS)
                            .background(R1.SurfaceMuted)
                            .border(1.dp, R1.Hairline, R1.ShapeS)
                            .r1Pressable(onClick = { vm.refresh() })
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = if (ui.loading) "..." else "REFRESH",
                            style = R1.labelMicro,
                            color = R1.InkSoft,
                        )
                    }
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
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .r1Pressable(onClick = onToggle)
            .padding(top = 8.dp, bottom = 4.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = R1.labelMicro,
            color = R1.AccentWarm,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$count",
            style = R1.labelMicro,
            color = R1.InkMuted,
            modifier = Modifier.padding(end = 6.dp),
        )
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = blueprint.name,
                style = R1.bodyEmph,
                color = R1.Ink,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            Spacer(Modifier.width(8.dp))
            DomainChip(domain = blueprint.domain)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = blueprint.path,
                style = R1.labelMicro.copy(fontFamily = FontFamily.Monospace),
                color = R1.InkSoft,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (blueprint.inputCount > 0) {
                Spacer(Modifier.width(6.dp))
                MicroChip(
                    text = "${blueprint.inputCount} INPUT" +
                        if (blueprint.inputCount == 1) "" else "S",
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

@Composable
private fun DomainChip(domain: String) {
    val tone = when (domain.lowercase()) {
        "automation" -> R1.AccentWarm
        "script" -> R1.AccentCool
        else -> R1.InkMuted
    }
    Box(
        modifier = Modifier
            .clip(R1.ShapeS)
            .background(tone.copy(alpha = 0.18f))
            .border(1.dp, tone.copy(alpha = 0.5f), R1.ShapeS)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text = domain.uppercase(), style = R1.labelMicro, color = tone)
    }
}

@Composable
private fun MicroChip(text: String, tone: Color) {
    Box(
        modifier = Modifier
            .clip(R1.ShapeS)
            .background(tone.copy(alpha = 0.18f))
            .border(1.dp, tone.copy(alpha = 0.5f), R1.ShapeS)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = text, style = R1.labelMicro, color = tone)
    }
}

@Composable
private fun EmptySectionHint(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(text = message, style = R1.labelMicro, color = R1.InkMuted)
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = message, style = R1.body, color = R1.InkMuted)
    }
}

@Composable
private fun ErrorState(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "COULDN'T LOAD BLUEPRINTS", style = R1.labelMicro, color = R1.StatusAmber)
        Spacer(Modifier.height(6.dp))
        Text(text = message, style = R1.body, color = R1.InkSoft)
        Spacer(Modifier.height(12.dp))
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
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                    val canInstall = preview != null &&
                        !preview.rawYaml.isNullOrBlank() &&
                        preview.path.isNotBlank() &&
                        preview.validationErrors.isNullOrBlank() &&
                        phase == BlueprintsViewModel.ImportPhase.PREVIEW
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = preview.name,
                style = R1.bodyEmph,
                color = R1.Ink,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            Spacer(Modifier.width(8.dp))
            DomainChip(domain = preview.domain)
        }
        if (preview.path.isNotBlank()) {
            Text(
                text = "→ blueprints/${preview.domain}/${preview.path}",
                style = R1.labelMicro.copy(fontFamily = FontFamily.Monospace),
                color = R1.InkSoft,
                maxLines = 1,
            )
        }
        if (preview.inputCount > 0) {
            MicroChip(
                text = "${preview.inputCount} INPUT" +
                    if (preview.inputCount == 1) "" else "S",
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
    }
}
