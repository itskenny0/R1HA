package com.github.itskenny0.r1ha.feature.users

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.github.itskenny0.r1ha.core.ha.HaUser
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent

/**
 * Read-only Users browser. Mirrors HA's Settings > People > Users tab
 * with deliberately no editing affordances: HA's own surface handles
 * password resets, MFA enrolment, and per-user permissions, and an R1
 * client that tried to be the second source-of-truth would invite drift.
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
                ui.loading && ui.users.isEmpty() -> Box(
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
                ui.error != null && ui.users.isEmpty() -> EmptyState(
                    title = "COULDN'T LOAD",
                    body = ui.error ?: "Unknown error",
                    accent = R1.StatusRed,
                )
                ui.users.isEmpty() -> EmptyState(
                    title = "NO USERS",
                    body = "HA returned an empty user list. (Unusual.)",
                    accent = R1.InkMuted,
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = R1.space.m,
                        vertical = R1.space.s,
                    ),
                    verticalArrangement = Arrangement.spacedBy(R1.space.xs),
                ) {
                    item {
                        Text(
                            text = "${ui.users.size} user${if (ui.users.size == 1) "" else "s"}" +
                                "  ·  read-only",
                            style = R1.labelMicro,
                            color = R1.InkSoft,
                            modifier = Modifier.padding(vertical = R1.space.xs),
                        )
                    }
                    items(items = ui.users, key = { it.id }) { user ->
                        UserRow(user)
                    }
                    item { Spacer(Modifier.size(R1.space.xl)) }
                }
            }
        }
    }
}

@Composable
private fun UserRow(user: HaUser) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = R1.space.m, vertical = R1.space.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = user.name.ifBlank { "(no name)" },
                style = R1.bodyEmph,
                color = if (user.isActive) R1.Ink else R1.InkMuted,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (user.systemGenerated) {
                R1Chip(text = "SYSTEM", variant = R1ChipVariant.Pill, tone = R1.AccentNeutral)
                Spacer(Modifier.width(R1.space.xs))
            }
            if (!user.isActive) {
                R1Chip(text = "DISABLED", variant = R1ChipVariant.Pill, tone = R1.StatusRed)
                Spacer(Modifier.width(R1.space.xs))
            }
            if (user.localOnly) {
                R1Chip(text = "LOCAL", variant = R1ChipVariant.Pill, tone = R1.AccentCool)
            }
        }
        Spacer(Modifier.size(R1.space.xxs))
        Text(
            text = user.id,
            style = R1.labelMicro.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = TextUnit(10f, TextUnitType.Sp),
            ),
            color = R1.InkMuted,
            maxLines = 1,
        )
        if (user.groupIds.isNotEmpty()) {
            Spacer(Modifier.size(R1.space.xs))
            Text(
                text = "GROUPS · " + user.groupIds.joinToString(", "),
                style = R1.labelMicro,
                color = R1.InkSoft,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String, accent: androidx.compose.ui.graphics.Color) {
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
