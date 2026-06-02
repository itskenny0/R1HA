package com.github.itskenny0.r1ha.feature.users

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1Section
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent

/**
 * Read-only Users browser. Mirrors HA's Settings > People > Users tab
 * with deliberately no editing affordances: HA's own surface handles
 * password resets, MFA enrolment, and per-user permissions, and an R1
 * client that tried to be the second source-of-truth would invite drift.
 *
 * Rows are grouped into Admins / Users / System and tagged with the flags
 * HA exposes per user (owner, admin, local-only, disabled, system vs
 * human). When a `person.*` entity links back to a user, the row also
 * shows that person's friendly name and current presence with a relative
 * "since X" timestamp.
 *
 * Admin-only call. Non-admin tokens hit a permission_denied empty state
 * with a one-line explanation instead of the cryptic WS error string.
 */
@Composable
fun UsersScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: UsersViewModel = viewModel(factory = UsersViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    LaunchedEffect(Unit) { vm.refresh() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(
            title = "USERS",
            onBack = onBack,
            action = {
                R1Chip(
                    text = if (ui.loading) "…" else "REFRESH",
                    variant = R1ChipVariant.Action,
                    onClick = { vm.refresh() },
                    contentDescription = "Refresh users",
                )
            },
        )
        AdaptiveContent(modifier = Modifier.weight(1f)) {
            when {
                ui.loading && ui.sections.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = R1.AccentWarm,
                    )
                }
                ui.permissionDenied -> EmptyState(
                    title = "ADMIN ONLY",
                    body = "Sign in with an admin account to browse users. The current " +
                        "token doesn't have permission to read config/auth/list.",
                    accent = R1.AccentWarm,
                )
                ui.error != null && ui.sections.isEmpty() -> EmptyState(
                    title = "COULDN'T LOAD",
                    body = ui.error ?: "Unknown error",
                    accent = R1.StatusRed,
                )
                ui.sections.isEmpty() -> EmptyState(
                    title = "NO USERS",
                    body = "HA returned an empty user list. (Unusual.)",
                    accent = R1.InkMuted,
                )
                else -> PullToRefreshBox(
                    isRefreshing = ui.loading,
                    onRefresh = { vm.refresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = R1.space.m,
                            vertical = R1.space.s,
                        ),
                        verticalArrangement = Arrangement.spacedBy(R1.space.xs),
                    ) {
                        item(key = "__summary") {
                            Text(
                                text = "${ui.totalCount} user${if (ui.totalCount == 1) "" else "s"}" +
                                    "  ·  read-only",
                                style = R1.labelMicro,
                                color = R1.InkSoft,
                                modifier = Modifier.padding(vertical = R1.space.xs),
                            )
                        }
                        ui.sections.forEachIndexed { index, (section, rows) ->
                            item(key = "__sec_${section.name}", contentType = "header") {
                                R1Section(
                                    title = sectionTitle(section),
                                    count = rows.size,
                                    topSpace = if (index == 0) R1.space.s else R1.space.l,
                                    content = {},
                                )
                            }
                            items(
                                items = rows,
                                key = { it.id },
                                contentType = { "userRow" },
                            ) { row -> UserRow(row) }
                        }
                        item(key = "__tail") { Spacer(Modifier.size(R1.space.xl)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserRow(row: UserRowModel) {
    // Collapse the row's flags + presence into one spoken line so a screen
    // reader announces "Jane, admin, home" rather than reading each chip
    // glyph in isolation.
    val rowDescription = rowContentDescription(row)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .semantics { contentDescription = rowDescription }
            .padding(horizontal = R1.space.m, vertical = R1.space.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.displayName,
                style = R1.bodyEmph,
                color = if (row.isActive) R1.Ink else R1.InkMuted,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
        }
        // Flag chips wrap onto a second line on narrow widths rather than
        // clipping, so every applicable flag stays visible.
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = R1.space.xs),
            horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
            verticalArrangement = Arrangement.spacedBy(R1.space.xs),
        ) {
            if (row.isOwner) {
                R1Chip(text = "OWNER", variant = R1ChipVariant.Pill, tone = R1.AccentWarm)
            }
            if (row.isAdmin) {
                R1Chip(text = "ADMIN", variant = R1ChipVariant.Pill, tone = R1.AccentCool)
            }
            if (row.isReadOnly) {
                R1Chip(text = "READ-ONLY", variant = R1ChipVariant.Pill, tone = R1.AccentNeutral)
            }
            R1Chip(
                text = if (row.systemGenerated) "SYSTEM" else "HUMAN",
                variant = R1ChipVariant.Pill,
                tone = if (row.systemGenerated) R1.AccentNeutral else R1.AccentGreen,
            )
            if (!row.isActive) {
                R1Chip(text = "DISABLED", variant = R1ChipVariant.Pill, tone = R1.StatusRed)
            }
            if (row.localOnly) {
                R1Chip(text = "LOCAL", variant = R1ChipVariant.Pill, tone = R1.AccentCool)
            }
        }
        // Linked person: friendly name + current presence + 'since X'.
        if (row.linkedPersonName != null) {
            Spacer(Modifier.size(R1.space.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val presence = rowPresence(row.linkedPersonState.orEmpty())
                Text(
                    text = presence.label,
                    style = R1.labelMicro,
                    color = presence.color,
                )
                Spacer(Modifier.width(R1.space.s))
                Text(
                    text = row.linkedPersonName,
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.linkedPersonSince != null) {
                    Spacer(Modifier.width(R1.space.s))
                    RelativeTimeLabel(
                        at = row.linkedPersonSince,
                        color = R1.InkMuted,
                        style = R1.labelMicro,
                    )
                }
            }
        }
        Spacer(Modifier.size(R1.space.xxs))
        Text(
            text = row.id,
            style = R1.labelMicro.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = TextUnit(10f, TextUnitType.Sp),
            ),
            color = R1.InkMuted,
            maxLines = 1,
        )
        if (row.groupIds.isNotEmpty()) {
            Spacer(Modifier.size(R1.space.xs))
            Text(
                text = "GROUPS · " + row.groupIds.joinToString(", ") { prettyGroupId(it) },
                style = R1.labelMicro,
                color = R1.InkSoft,
                maxLines = 2,
            )
        }
    }
}

/**
 * Build a single spoken line for a user row's accessibility description. Lists
 * the name, then every flag that applies, then the linked person's presence so
 * the meaning a sighted user reads off the chips is announced as one phrase.
 */
private fun rowContentDescription(row: UserRowModel): String {
    val parts = mutableListOf(row.displayName)
    if (row.isOwner) parts += "owner"
    if (row.isAdmin) parts += "admin"
    if (row.isReadOnly) parts += "read-only"
    parts += if (row.systemGenerated) "system account" else "human account"
    if (!row.isActive) parts += "disabled"
    if (row.localOnly) parts += "local only"
    if (row.linkedPersonName != null) {
        val presence = rowPresence(row.linkedPersonState.orEmpty()).label
        parts += "linked to ${row.linkedPersonName}, $presence"
    }
    return parts.joinToString(", ")
}

/** Presence chip text paired with its R1 palette colour for a linked person. */
private data class RowPresence(val label: String, val color: Color)

/**
 * Map an HA person/device state onto a chip label + colour. Mirrors the
 * "Who's home" buckets without depending on that feature's helpers, so the
 * two surfaces stay independent.
 */
private fun rowPresence(state: String): RowPresence {
    val trimmed = state.trim()
    return when (trimmed.lowercase(java.util.Locale.US)) {
        "home" -> RowPresence("HOME", R1.AccentGreen)
        "not_home", "away" -> RowPresence("AWAY", R1.StatusAmber)
        "unknown", "unavailable", "" -> RowPresence("?", R1.StatusRed)
        else -> RowPresence(trimmed.uppercase(java.util.Locale.US), R1.AccentCool)
    }
}

@Composable
private fun EmptyState(title: String, body: String, accent: Color) {
    Box(
        modifier = Modifier.fillMaxSize().padding(R1.space.xl),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = title, style = R1.sectionHeader, color = accent)
            Spacer(Modifier.size(R1.space.s))
            Text(text = body, style = R1.body, color = R1.InkSoft)
        }
    }
}
