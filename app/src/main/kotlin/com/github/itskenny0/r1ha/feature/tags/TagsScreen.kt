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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.HaTag
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.ui.components.AutoRefresh
import com.github.itskenny0.r1ha.ui.components.R1Button
import com.github.itskenny0.r1ha.ui.components.R1ButtonVariant
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel
import com.github.itskenny0.r1ha.ui.components.WheelScrollForGrid
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.r1RowPressable
import com.github.itskenny0.r1ha.ui.icons.R1IconSet
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
    val gridState = rememberLazyGridState()
    WheelScrollForGrid(wheelInput = wheelInput, gridState = gridState, settings = settings)
    LaunchedEffect(Unit) { vm.refresh() }
    // HA's tag panel live-updates last_scanned off a tag_scanned subscription; we can't
    // subscribe from here, so poll the registry periodically to keep the newest-scan-first
    // ordering current. Registry data is low-churn, so a relaxed cadence is plenty.
    AutoRefresh(30_000L) { vm.refreshQuiet() }

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
                R1Chip(
                    text = if (ui.loading) "…" else "REFRESH",
                    variant = R1ChipVariant.Action,
                    onClick = { vm.refresh() },
                    contentDescription = "Refresh tags",
                )
            },
        )
        val dimens = rememberResponsiveDimens()
        AdaptiveContent(modifier = Modifier.weight(1f)) {
            when {
                ui.loading && ui.tags.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = "Loading tags" },
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = R1.AccentWarm,
                    )
                }
                ui.error != null && ui.tags.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(dimens.screenGutter),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = ui.error ?: "Error", style = responsiveType(R1.body), color = R1.StatusRed)
                }
                ui.tags.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(dimens.screenGutter),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No tags registered. Scan an NFC / QR tag at HA to register it; " +
                            "it'll appear here automatically.",
                        style = responsiveType(R1.body),
                        color = R1.InkMuted,
                    )
                }
                // Multi-column flow on roomy tiers (gridColumns: 2/2/3/4/5) so a wide panel
                // fills with tag cards side by side instead of one tall ribbon; the R1 /
                // compact panel stays single column where a row's name + scan time + id
                // need the full width to read.
                else -> {
                    val tagColumns = if (dimens.capsContentWidth) dimens.gridColumns else 1
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(tagColumns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = dimens.screenGutter,
                            vertical = R1.space.s,
                        ),
                        verticalArrangement = Arrangement.spacedBy(R1.space.xs),
                        horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
                    ) {
                        item(key = "tags-header", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = "${ui.tags.size} tag${if (ui.tags.size == 1) "" else "s"}" +
                                    "  ·  TAP to rename, HOLD to delete",
                                style = responsiveType(R1.labelMicro),
                                color = R1.InkSoft,
                                modifier = Modifier
                                    .padding(vertical = R1.space.xs)
                                    .semantics { heading() },
                            )
                        }
                        items(items = ui.tags, key = { it.id }) { tag ->
                            TagRow(
                                tag = tag,
                                onTap = { renaming = tag },
                                onLongPress = { deleting = tag },
                            )
                        }
                        item(key = "tags-footer-spacer", span = { GridItemSpan(maxLineSpan) }) {
                            Spacer(Modifier.size(R1.space.xl))
                        }
                    }
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
    val displayName = tag.name?.takeIf { it.isNotBlank() } ?: tag.id
    // Merge the row into one announcement so a screen reader reads
    // "name, scanned 5 minutes ago, double-tap to rename, long-press to delete"
    // instead of the disjoint name / timestamp / id fragments. rememberRelativeTime
    // returns the same phrasing the visible label shows, keeping the two in sync.
    val scannedPhrase = if (tag.lastScanned != null) {
        "last scanned ${com.github.itskenny0.r1ha.ui.components.rememberRelativeTime(tag.lastScanned)}"
    } else {
        "never scanned"
    }
    val rowDescription = "$displayName, $scannedPhrase. Double-tap to rename, long-press to delete."
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1RowPressable(
                onTap = onTap,
                onLongPress = onLongPress,
                contentDescription = rowDescription,
            )
            .heightIn(min = R1.MinTarget)
            .padding(horizontal = R1.space.m, vertical = R1.space.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Leading glyph so a tag row scans as a tag at a glance, matching the
            // leading-icon rhythm of the other registry browsers.
            Icon(
                imageVector = R1IconSet.Generic,
                contentDescription = null,
                tint = R1.InkSoft,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(R1.space.s))
            Text(
                text = displayName,
                style = responsiveType(R1.bodyEmph),
                color = R1.Ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(R1.space.s))
            if (tag.lastScanned != null) {
                RelativeTimeLabel(
                    at = tag.lastScanned,
                    color = R1.AccentCool,
                    style = responsiveType(R1.labelMicro),
                )
            } else {
                Text(text = "NEVER SCANNED", style = responsiveType(R1.labelMicro), color = R1.InkMuted)
            }
        }
        if (tag.name != null && tag.name.isNotBlank()) {
            Spacer(Modifier.size(R1.space.xxs))
            Text(
                text = tag.id,
                style = R1.labelMicro.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = TextUnit(10f, TextUnitType.Sp),
                ),
                color = R1.InkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!tag.description.isNullOrBlank()) {
            Spacer(Modifier.size(R1.space.xs))
            Text(
                text = tag.description,
                style = responsiveType(R1.labelMicro),
                color = R1.InkSoft,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
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
    var name by remember(tag.id) { mutableStateOf(tag.name.orEmpty()) }
    var desc by remember(tag.id) { mutableStateOf(tag.description.orEmpty()) }
    var confirmingDiscard by remember(tag.id) { mutableStateOf(false) }
    // The sheet is dirty when the typed name/description differs from what the
    // tag started with. An outside-tap or back-press while dirty would throw
    // away typed edits silently, so route those through a discard confirm; a
    // clean sheet dismisses immediately.
    val isDirty = name != tag.name.orEmpty() || desc != tag.description.orEmpty()
    val attemptDismiss: () -> Unit = {
        if (isDirty) confirmingDiscard = true else onDismiss()
    }
    BackHandler(onBack = attemptDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.92f))
            .r1Pressable(onClick = attemptDismiss, hapticOnClick = false)
            .systemBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = R1.space.l, vertical = R1.space.l)
                .clip(R1.ShapeS)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onClick = {}, hapticOnClick = false)
                .padding(R1.space.l),
        ) {
            Text(text = "RENAME TAG", style = responsiveType(R1.sectionHeader), color = R1.AccentWarm)
            Spacer(Modifier.size(R1.space.xxs))
            Text(
                text = tag.id,
                style = responsiveType(R1.body).copy(fontFamily = FontFamily.Monospace),
                color = R1.InkMuted,
                maxLines = 1,
            )
            Spacer(Modifier.size(R1.space.m))
            Text(text = "NAME", style = responsiveType(R1.labelMicro), color = R1.InkSoft)
            Spacer(Modifier.size(R1.space.xs))
            R1TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "Friendly name (e.g. Coffee-table puck)",
                monospace = false,
            )
            Spacer(Modifier.size(R1.space.m))
            Text(text = "DESCRIPTION", style = responsiveType(R1.labelMicro), color = R1.InkSoft)
            Spacer(Modifier.size(R1.space.xs))
            R1TextField(
                value = desc,
                onValueChange = { desc = it },
                placeholder = "Optional notes about what this tag does",
                monospace = false,
                singleLine = false,
                minLines = 2,
            )
            Spacer(Modifier.size(R1.space.l))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                R1Button(text = "CANCEL", onClick = attemptDismiss, variant = R1ButtonVariant.Outlined)
                Spacer(Modifier.width(R1.space.s))
                R1Button(text = "SAVE", onClick = { onSave(name.trim(), desc.trim()) })
            }
        }
        // Discard guard: a second confirm over the sheet, so a stray outside-tap
        // can't quietly drop typed edits. KEEP returns to editing; DISCARD exits.
        if (confirmingDiscard) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(R1.Bg.copy(alpha = 0.92f))
                    .r1Pressable(onClick = { confirmingDiscard = false }, hapticOnClick = false),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 440.dp)
                        .fillMaxWidth()
                        .padding(horizontal = R1.space.l, vertical = R1.space.l)
                        .clip(R1.ShapeS)
                        .background(R1.Surface)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .r1Pressable(onClick = {}, hapticOnClick = false)
                        .padding(R1.space.l),
                ) {
                    Text(text = "DISCARD EDITS?", style = responsiveType(R1.sectionHeader), color = R1.AccentWarm)
                    Spacer(Modifier.size(R1.space.s))
                    Text(
                        text = "You've changed this tag's name or description. Leaving now " +
                            "throws those edits away.",
                        style = responsiveType(R1.body),
                        color = R1.InkMuted,
                    )
                    Spacer(Modifier.size(R1.space.l))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        R1Button(
                            text = "KEEP EDITING",
                            onClick = { confirmingDiscard = false },
                            variant = R1ButtonVariant.Outlined,
                        )
                        Spacer(Modifier.width(R1.space.s))
                        R1Button(
                            text = "DISCARD",
                            onClick = { confirmingDiscard = false; onDismiss() },
                            variant = R1ButtonVariant.Outlined,
                            accent = R1.StatusRed,
                        )
                    }
                }
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
                .padding(horizontal = R1.space.l, vertical = R1.space.l)
                .clip(R1.ShapeS)
                .background(R1.Surface)
                .border(1.dp, R1.StatusRed.copy(alpha = 0.5f), R1.ShapeS)
                .r1Pressable(onClick = {}, hapticOnClick = false)
                .padding(R1.space.l),
        ) {
            Text(text = "DELETE TAG", style = responsiveType(R1.sectionHeader), color = R1.StatusRed)
            Spacer(Modifier.size(R1.space.s))
            Text(
                text = tag.name?.takeIf { it.isNotBlank() } ?: tag.id,
                style = responsiveType(R1.body),
                color = R1.Ink,
            )
            Spacer(Modifier.size(R1.space.xxs))
            Text(
                text = tag.id,
                style = responsiveType(R1.labelMicro).copy(fontFamily = FontFamily.Monospace),
                color = R1.InkMuted,
                maxLines = 1,
            )
            Spacer(Modifier.size(R1.space.m))
            Text(
                text = "The tag will be removed from HA's registry. The physical tag still " +
                    "broadcasts its id; a future scan re-registers it with a blank name.",
                style = responsiveType(R1.body),
                color = R1.InkMuted,
            )
            Spacer(Modifier.size(R1.space.l))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                R1Button(text = "CANCEL", onClick = onDismiss, variant = R1ButtonVariant.Outlined)
                Spacer(Modifier.width(R1.space.s))
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
