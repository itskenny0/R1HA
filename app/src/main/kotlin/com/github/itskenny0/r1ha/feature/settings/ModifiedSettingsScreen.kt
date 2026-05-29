package com.github.itskenny0.r1ha.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.SettingCategory
import com.github.itskenny0.r1ha.core.prefs.SettingEntry
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.modifiedSettings
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Row
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent

/**
 * Read-only audit of every setting that differs from its constructor default.
 * Walks [SETTINGS_REGISTRY], filters by `!isDefault(current)`, and renders each
 * matched entry's label + category + current display value.
 *
 * Why read-only: rebuilding each entry's editor inline here would duplicate
 * every section composable from SettingsScreen. The user can read what's
 * modified at a glance and scroll the main Settings screen to the matching
 * section to change it.
 *
 * Empty state when nothing's modified reads as a clean affirmation rather than
 * "we couldn't load anything", so the user knows the fresh-install state is
 * still in effect.
 */
@Composable
fun ModifiedSettingsScreen(
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val current by settings.settings.collectAsState(initial = AppSettings())
    val modified = modifiedSettings(current)
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "MODIFIED SETTINGS", onBack = onBack)
        AdaptiveContent(modifier = Modifier.weight(1f)) {
            if (modified.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(R1.space.xl),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Every registered setting is at its default value. " +
                            "Adjust anything in Settings and it'll appear here.",
                        style = R1.body,
                        color = R1.InkMuted,
                    )
                }
                return@AdaptiveContent
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = R1.space.m,
                    vertical = R1.space.s,
                ),
                verticalArrangement = Arrangement.spacedBy(R1.space.xs),
            ) {
                // Lightweight summary header: count + a clarifying line so the user
                // knows the list isn't an exhaustive enumeration of every setting.
                item("__header") {
                    Column(modifier = Modifier.padding(horizontal = R1.space.m, vertical = R1.space.s)) {
                        Text(
                            text = "${modified.size} modified",
                            style = R1.bodyEmph,
                            color = R1.Ink,
                        )
                        Text(
                            text = "Entries that differ from their constructor-default value. " +
                                "Tap the parent section in Settings to change.",
                            style = R1.labelMicro,
                            color = R1.InkMuted,
                        )
                    }
                }
                // Group entries by category, preserving the registry's overall order.
                // The diff list now reads category-by-category, matching the layout of
                // the parent Settings screen so the user can map each diff row to its
                // visual neighbourhood there.
                val grouped: List<Pair<SettingCategory, List<SettingEntry>>> =
                    modified.groupBy { it.category }
                        .toList()
                        .sortedBy { (category, _) ->
                            SettingCategory.entries.indexOf(category)
                        }
                grouped.forEach { (category, entries) ->
                    item("__cat_${category.name}") {
                        // Canonical group header (R1Section title treatment) instead of a
                        // bare uppercase label, so the diff list reads with the same
                        // hierarchy as the rest of the app.
                        Text(
                            text = category.label.uppercase(),
                            style = R1.sectionHeader,
                            color = R1.AccentWarm,
                            modifier = Modifier.padding(
                                start = R1.space.xs,
                                top = R1.space.s,
                                bottom = R1.space.xxs,
                            ),
                        )
                    }
                    itemsIndexed(entries, key = { _, it -> it.id }) { _, entry ->
                        ModifiedSettingRow(
                            entry = entry,
                            current = current,
                            onJumpToSection = {
                                // Push the section name onto the focus bus and pop back.
                                // SettingsScreen's collector handles the expand + scroll.
                                com.github.itskenny0.r1ha.core.util.SettingsFocusBus.request(
                                    sectionNameForCategory(entry.category),
                                )
                                onBack()
                            },
                        )
                    }
                }
            }
        } // AdaptiveContent
    }
}

@Composable
private fun ModifiedSettingRow(
    entry: SettingEntry,
    current: AppSettings,
    onJumpToSection: () -> Unit,
) {
    // Canonical settings row. Tap surfs back to Settings with the entry's
    // section expanded and focused, so the audit-then-edit flow is one tap;
    // the chevron hints the row navigates rather than opening an inline editor.
    // Category tag lives on the group header now, so the row only needs
    // label + description + current value.
    R1Row(
        label = entry.label,
        description = entry.description,
        value = entry.currentDisplay(current),
        onClick = onJumpToSection,
        showChevron = true,
        boxed = true,
        contentDescription = "Open ${entry.label} in Settings",
    )
}

// Suppress lint: SettingCategory is referenced via entry.category.label only,
// but Kotlin's strict-imports rules want the type imported even when used
// transitively. Keeping the explicit reference here also makes the call-site
// independent of which categories exist today.
@Suppress("unused")
private val keepCategoryImport: SettingCategory = SettingCategory.SERVER
