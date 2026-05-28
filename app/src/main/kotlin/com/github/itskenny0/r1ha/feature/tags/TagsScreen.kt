package com.github.itskenny0.r1ha.feature.tags

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.r1RowPressable
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent

/**
 * Tags screen — native HA tag registry editor. Lists every NFC / QR tag
 * the registry knows about, sorted newest-scan-first so a tag the user
 * just touched bubbles to the top.
 *
 * Interactions: tap a row to open the rename sheet (name + description),
 * long-press to delete with a confirm. Creation is intentionally absent;
 * a fresh tag self-registers on its first scan with the raw id as its
 * name, which is the right time to use the rename sheet.
 */
@Composable
fun TagsScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: TagsViewModel = viewModel(factory = TagsViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    LaunchedEffect(Unit) { vm.refresh() }

    var renaming by remember { mutableStateOf<HaTag?>(null) }
    var deleting by remember { mutableStateOf<HaTag?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(
            title = "TAGS",
            onBack = onBack,
            action = {
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .r1Pressable(onClick = { vm.refresh() })
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (ui.loading) "…" else "REFRESH",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                }
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
                    modifier = Modifier.fillMaxSize().padding(22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = ui.error ?: "Error", style = R1.body, color = R1.StatusRed)
                }
                ui.tags.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No tags registered. Scan an NFC / QR tag at HA to register it; " +
                            "it'll appear here automatically.",
                        style = R1.body,
                        color = R1.InkMuted,
                    )
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp,
                        vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item {
                        Text(
                            text = "${ui.tags.size} tag${if (ui.tags.size == 1) "" else "s"}" +
                                "  ·  TAP to rename, HOLD to delete",
                            style = R1.labelMicro,
                            color = R1.InkSoft,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    items(items = ui.tags, key = { it.id }) { tag ->
                        TagRow(
                            tag = tag,
                            onTap = { renaming = tag },
                            onLongPress = { deleting = tag },
                        )
                    }
                    item { Spacer(Modifier.size(24.dp)) }
                }
            }
        }
    }

    val renameTarget = renaming
    if (renameTarget != null) {
        RenameTagSheet(
            tag = renameTarget,
            onDismiss = { renaming = null },
            onSave = { name, desc ->
                renaming = null
                vm.update(renameTarget, name = name, description = desc)
            },
        )
    }
    val deleteTarget = deleting
    if (deleteTarget != null) {
        DeleteTagSheet(
            tag = deleteTarget,
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                vm.delete(deleteTarget)
            },
        )
    }
}

@Composable
private fun TagRow(
    tag: HaTag,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1RowPressable(onTap = onTap, onLongPress = onLongPress)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = tag.name?.takeIf { it.isNotBlank() } ?: tag.id,
                style = R1.body,
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
        if (tag.name != null && tag.name.isNotBlank()) {
            Spacer(Modifier.size(2.dp))
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
            Spacer(Modifier.size(4.dp))
            Text(
                text = tag.description,
                style = R1.labelMicro,
                color = R1.InkSoft,
                maxLines = 3,
            )
        }
    }
}

@Composable
private fun RenameTagSheet(
    tag: HaTag,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String) -> Unit,
) {
    BackHandler(onBack = onDismiss)
    var name by remember(tag.id) { mutableStateOf(tag.name.orEmpty()) }
    var desc by remember(tag.id) { mutableStateOf(tag.description.orEmpty()) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.92f))
            .r1Pressable(onClick = onDismiss, hapticOnClick = false)
            .systemBarsPadding()
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
                .padding(16.dp),
        ) {
            Text(text = "RENAME TAG", style = R1.sectionHeader, color = R1.AccentWarm)
            Spacer(Modifier.size(2.dp))
            Text(
                text = tag.id,
                style = R1.body.copy(fontFamily = FontFamily.Monospace),
                color = R1.InkMuted,
                maxLines = 1,
            )
            Spacer(Modifier.size(12.dp))
            Text(text = "NAME", style = R1.labelMicro, color = R1.InkSoft)
            Spacer(Modifier.size(4.dp))
            R1TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "Friendly name (e.g. Coffee-table puck)",
                monospace = false,
            )
            Spacer(Modifier.size(10.dp))
            Text(text = "DESCRIPTION", style = R1.labelMicro, color = R1.InkSoft)
            Spacer(Modifier.size(4.dp))
            R1TextField(
                value = desc,
                onValueChange = { desc = it },
                placeholder = "Optional notes about what this tag does",
                monospace = false,
                singleLine = false,
                minLines = 2,
            )
            Spacer(Modifier.size(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                R1Button(text = "CANCEL", onClick = onDismiss, variant = R1ButtonVariant.Outlined)
                Spacer(Modifier.width(8.dp))
                R1Button(text = "SAVE", onClick = { onSave(name.trim(), desc.trim()) })
            }
        }
    }
}

@Composable
private fun DeleteTagSheet(
    tag: HaTag,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.92f))
            .r1Pressable(onClick = onDismiss, hapticOnClick = false)
            .systemBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp)
                .clip(R1.ShapeS)
                .background(R1.Surface)
                .border(1.dp, R1.StatusRed.copy(alpha = 0.5f), R1.ShapeS)
                .r1Pressable(onClick = {}, hapticOnClick = false)
                .padding(16.dp),
        ) {
            Text(text = "DELETE TAG", style = R1.sectionHeader, color = R1.StatusRed)
            Spacer(Modifier.size(6.dp))
            Text(
                text = tag.name?.takeIf { it.isNotBlank() } ?: tag.id,
                style = R1.body,
                color = R1.Ink,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = tag.id,
                style = R1.labelMicro.copy(fontFamily = FontFamily.Monospace),
                color = R1.InkMuted,
                maxLines = 1,
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = "The tag will be removed from HA's registry. The physical tag still " +
                    "broadcasts its id; a future scan re-registers it with a blank name.",
                style = R1.body,
                color = R1.InkMuted,
            )
            Spacer(Modifier.size(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                R1Button(text = "CANCEL", onClick = onDismiss, variant = R1ButtonVariant.Outlined)
                Spacer(Modifier.width(8.dp))
                R1Button(
                    text = "DELETE",
                    onClick = onConfirm,
                    variant = R1ButtonVariant.Outlined,
                    accent = R1.StatusRed,
                )
            }
        }
    }
}
