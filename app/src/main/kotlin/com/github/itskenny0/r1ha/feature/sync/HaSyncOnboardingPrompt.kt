package com.github.itskenny0.r1ha.feature.sync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.App
import com.github.itskenny0.r1ha.core.ha.ConnectionState
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.sync.HaSettingsSync
import com.github.itskenny0.r1ha.core.sync.SyncCategory
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.ui.components.R1Switch
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * The single sync dialog. Introduces multi-device mirroring the first time a
 * configured device reaches the card stack with a live WS, and is the ONLY
 * place the offer appears: onboarding no longer carries its own sync step, so
 * the user is never asked the same question twice.
 *
 * One decision, expressed three ways in one panel:
 *   - a live device-link schematic that scans HA, then shows which way state
 *     will flow (HA -> THIS to follow, THIS -> HA to lead);
 *   - a MODE choice: TWO-WAY (send + receive) or RECEIVE ONLY (follow the
 *     household, never upload this device's changes) -> persists
 *     [AppSettings.IntegrationSettings.haSyncReadOnly];
 *   - the seed DIRECTION when a remote already exists (import it, or overwrite
 *     it with this device), plus a per-category opt-out.
 *
 * Renders only when a server is configured, the WS is Connected (so the probe
 * can run), the user hasn't answered before ([haSyncPromptSeen]), and the card
 * stack is the current destination ([onCardStack]) so it never paints over a
 * sub-screen. Inline window overlay rather than a Compose Dialog so it sits in
 * the activity window and dodges Dialog's hardware-key routing quirks.
 */
@Composable
fun HaSyncOnboardingPrompt(
    settings: AppSettings,
    connection: ConnectionState,
    /** True only while the card stack (home) is the current destination. The
     *  overlay is a window-level sibling of the NavHost; without this gate it
     *  would paint over whatever screen is up. */
    onCardStack: Boolean,
    onMarkSeen: () -> Unit,
    /** Commit. [readOnly] persists receive-only mode; [seedFromThisDevice]
     *  true means push this device up as the source of truth (only ever true
     *  in two-way mode), false means adopt the shared state. */
    onEnable: (excludedCategories: Set<String>, readOnly: Boolean, seedFromThisDevice: Boolean) -> Unit,
) {
    val visible = onCardStack &&
        settings.server != null &&
        !settings.integrations.haSyncPromptSeen &&
        connection is ConnectionState.Connected
    if (!visible) return

    val context = LocalContext.current
    val syncManager: HaSettingsSync? = remember(context) {
        (context.applicationContext as? App)?.graph?.haSettingsSync
    }
    var remoteTimestamp by remember { mutableStateOf<Long?>(null) }
    var probed by remember { mutableStateOf(false) }
    var probeError by remember { mutableStateOf<String?>(null) }
    var probeAttempt by remember { mutableStateOf(0) }

    // User decisions.
    var receiveOnly by remember { mutableStateOf(false) }
    // In two-way mode, when a remote exists: false = import (follow), true =
    // push (overwrite). Default to the non-destructive import.
    var pushOverRemote by remember { mutableStateOf(false) }
    var customising by remember { mutableStateOf(false) }
    val excluded = remember {
        mutableStateOf(settings.integrations.haSyncExcludedCategories)
    }

    // Probe HA on first show (and on RETRY) so the seed direction is offered
    // only when a remote payload actually exists. Bounded by the probe call's
    // own 15s timeout; the UI is never blocked on it.
    LaunchedEffect(probeAttempt) {
        if (probeAttempt > 0) {
            probed = false
            probeError = null
            remoteTimestamp = null
        }
        if (syncManager != null) {
            val result = runCatching { syncManager.probeRemoteExists() }
            remoteTimestamp = result.getOrNull()
            probeError = result.exceptionOrNull()?.message
        } else {
            probeError = "Sync unavailable: settings manager not ready."
        }
        probed = true
    }

    val hasRemote = remoteTimestamp != null
    // Which way the schematic and labels point. Receiving (import / follow)
    // pulls HA -> THIS; seeding pushes THIS -> HA.
    val seed = !receiveOnly && (!hasRemote || pushOverRemote)
    val flowToThis = !seed

    // One-shot entrance: the card rises + fades as a single calm beat; the
    // schematic's own motion carries the rest of the life.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val enter by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "syncPromptEnter",
    )

    val dimens = rememberResponsiveDimens()
    val cardWidthCap = if (dimens.capsContentWidth) 520.dp else androidx.compose.ui.unit.Dp.Unspecified

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.96f * enter))
            // Eat backdrop taps so the card stack underneath stays inert.
            .r1Pressable(onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .graphicsLayer {
                    alpha = enter
                    translationY = (1f - enter) * 28.dp.toPx()
                }
                .fillMaxWidth()
                .widthIn(max = cardWidthCap)
                .systemBarsPadding()
                .padding(horizontal = R1.space.l, vertical = R1.space.m)
                .heightIn(max = 600.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, RoundedCornerShape(12.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = R1.space.l, vertical = R1.space.l),
            verticalArrangement = Arrangement.spacedBy(R1.space.s),
        ) {
            Text(
                text = "DEVICE SYNC",
                style = responsiveType(R1.labelMicro),
                color = R1.AccentWarm,
            )
            Text(
                text = "Mirror across devices",
                style = responsiveType(R1.screenTitle),
                color = R1.Ink,
            )
            Text(
                text = "Share theme, pages, favourites, and overrides through " +
                    "Home Assistant's per-user storage. Server URL, iBeacon, " +
                    "webhook, and MQTT stay device-local.",
                style = responsiveType(R1.body),
                color = R1.InkMuted,
            )

            Spacer(Modifier.height(R1.space.xxs))

            // ── Hero: the live device link ───────────────────────────
            LinkDiagram(
                probed = probed,
                hasRemote = hasRemote,
                error = probeError != null,
                flowToThis = flowToThis,
            )
            ProbeCaption(probed = probed, hasRemote = hasRemote, error = probeError)

            // On a probe failure we can't know whether a remote exists, so we
            // never offer a blind push that could clobber an unread payload —
            // only RETRY and NOT NOW.
            if (probed && probeError != null) {
                Spacer(Modifier.height(R1.space.xs))
                PromptButton(
                    text = "RETRY",
                    tint = R1.AccentWarm,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { probeAttempt++ },
                )
                Spacer(Modifier.height(R1.space.xxs))
                PromptButton(
                    text = "NOT NOW",
                    tint = R1.InkMuted,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onMarkSeen,
                )
                return@Column
            }

            Spacer(Modifier.height(R1.space.xs))
            SectionLabel("MODE")
            ChoiceCard(
                title = "TWO-WAY",
                body = "This device sends its changes up and pulls others' down.",
                selected = !receiveOnly,
                accent = R1.AccentGreen,
                onClick = { receiveOnly = false },
            )
            ChoiceCard(
                title = "RECEIVE ONLY",
                body = "Follow the household. This device never uploads its own changes.",
                selected = receiveOnly,
                accent = R1.AccentCool,
                onClick = { receiveOnly = true },
            )

            // Seed direction only matters in two-way mode when a remote
            // already exists: do we adopt it, or overwrite it with this device?
            AnimatedVisibility(
                visible = !receiveOnly && hasRemote,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(R1.space.s)) {
                    Spacer(Modifier.height(R1.space.xs))
                    SectionLabel("SEED FROM")
                    ChoiceCard(
                        title = "HOME ASSISTANT",
                        body = "Adopt the shared settings now, then keep in step.",
                        selected = !pushOverRemote,
                        accent = R1.AccentGreen,
                        onClick = { pushOverRemote = false },
                    )
                    ChoiceCard(
                        title = "THIS DEVICE",
                        body = "Make this device the source; overwrite the shared copy.",
                        selected = pushOverRemote,
                        accent = R1.StatusAmber,
                        onClick = { pushOverRemote = true },
                    )
                }
            }

            // ── What syncs (collapsed by default) ────────────────────
            Spacer(Modifier.height(R1.space.xs))
            val onCount = SyncCategory.entries.count { !excluded.value.contains(it.name) }
            CategoryDisclosureHeader(
                onCount = onCount,
                total = SyncCategory.entries.size,
                expanded = customising,
                onToggle = { customising = !customising },
            )
            AnimatedVisibility(
                visible = customising,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    SyncCategory.entries.forEach { category ->
                        val included = !excluded.value.contains(category.name)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = R1.MinTarget)
                                .r1Pressable(
                                    onClick = {
                                        excluded.value = excluded.value.toMutableSet().apply {
                                            if (contains(category.name)) remove(category.name)
                                            else add(category.name)
                                        }
                                    },
                                    hapticOnClick = false,
                                    contentDescription =
                                        "${category.displayLabel}, sync ${if (included) "on" else "off"}",
                                )
                                .padding(vertical = R1.space.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = category.displayLabel,
                                style = responsiveType(R1.body),
                                color = R1.Ink,
                                modifier = Modifier.weight(1f),
                            )
                            R1Switch(
                                checked = included,
                                onCheckedChange = { v ->
                                    excluded.value = excluded.value.toMutableSet().apply {
                                        if (v) remove(category.name) else add(category.name)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // ── Commit ───────────────────────────────────────────────
            Spacer(Modifier.height(R1.space.xs))
            val commitLabel = when {
                !probed -> "CHECKING…"
                receiveOnly && hasRemote -> "IMPORT & FOLLOW"
                receiveOnly -> "ENABLE · RECEIVE ONLY"
                seed && hasRemote -> "OVERWRITE & SYNC"
                seed -> "ENABLE SYNC"
                else -> "IMPORT FROM HA"
            }
            PromptButton(
                text = commitLabel,
                // Seeding over an existing remote is the one destructive path —
                // tint it amber so it never reads as the safe default.
                tint = if (seed && hasRemote) R1.StatusAmber else R1.AccentGreen,
                modifier = Modifier.fillMaxWidth(),
                enabled = probed,
                onClick = {
                    onEnable(excluded.value, receiveOnly, seed)
                    onMarkSeen()
                },
            )
            Spacer(Modifier.height(R1.space.xxs))
            PromptButton(
                text = "NOT NOW",
                tint = R1.InkMuted,
                modifier = Modifier.fillMaxWidth(),
                onClick = onMarkSeen,
            )
        }
    }
}

/**
 * The signature element: a schematic of THIS device wired to the HA hub.
 * While probing, a lone scanner pip sweeps the rail; once the probe lands,
 * a steady train of packets marches in the direction state will flow (left
 * toward THIS when receiving, right toward HA when seeding). A failed probe
 * dashes the rail red and stops the traffic.
 */
@Composable
private fun LinkDiagram(
    probed: Boolean,
    hasRemote: Boolean,
    error: Boolean,
    flowToThis: Boolean,
) {
    val railTint by animateColorAsState(
        targetValue = when {
            !probed -> R1.StatusAmber
            error -> R1.StatusRed
            flowToThis -> R1.AccentGreen
            else -> R1.AccentWarm
        },
        animationSpec = tween(280),
        label = "railTint",
    )
    val transition = rememberInfiniteTransition(label = "link")
    // Steady march once probed; a slow back-and-forth scan while probing.
    val march by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "march",
    )
    val scan by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scan",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.Bg)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = R1.space.m, vertical = R1.space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
    ) {
        LinkNode(label = "THIS", tint = if (flowToThis) railTint else R1.InkSoft, lit = probed && !error)
        Box(
            modifier = Modifier
                .weight(1f)
                .height(28.dp),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val y = size.height / 2f
                val dot = 2.5.dp.toPx()
                val left = dot
                val right = size.width - dot
                // Base rail.
                if (error) {
                    drawLine(
                        color = railTint.copy(alpha = 0.7f),
                        start = Offset(left, y),
                        end = Offset(right, y),
                        strokeWidth = 1.5.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(4.dp.toPx(), 4.dp.toPx()), 0f,
                        ),
                    )
                    return@Canvas
                }
                drawLine(
                    color = R1.Hairline,
                    start = Offset(left, y),
                    end = Offset(right, y),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                val span = right - left
                if (!probed) {
                    // Scanning sweep: one bright pip ping-ponging the rail.
                    val x = left + span * scan
                    drawCircle(railTint, radius = dot * 1.4f, center = Offset(x, y))
                    drawCircle(railTint.copy(alpha = 0.25f), radius = dot * 3f, center = Offset(x, y))
                } else {
                    // Marching packets in the flow direction, fading at the tails.
                    val count = 4
                    for (i in 0 until count) {
                        val phase = (march + i.toFloat() / count) % 1f
                        val pos = if (flowToThis) 1f - phase else phase
                        val x = left + span * pos
                        // Brightest mid-rail, dimmer at the ends, for a sense of travel.
                        val a = 0.35f + 0.65f * (1f - kotlin.math.abs(pos - 0.5f) * 2f)
                        drawCircle(railTint.copy(alpha = a), radius = dot, center = Offset(x, y))
                    }
                }
            }
        }
        LinkNode(label = "HA", tint = if (!flowToThis) railTint else R1.InkSoft, lit = probed && !error)
    }
}

/** A labelled end node in the link schematic. */
@Composable
private fun LinkNode(label: String, tint: Color, lit: Boolean) {
    Box(
        modifier = Modifier
            .size(width = 46.dp, height = 30.dp)
            .clip(R1.ShapeS)
            .background(if (lit) tint.copy(alpha = 0.14f) else R1.SurfaceMuted)
            .border(1.dp, if (lit) tint else R1.Hairline, R1.ShapeS),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = responsiveType(R1.labelMicro),
            color = if (lit) tint else R1.InkSoft,
            textAlign = TextAlign.Center,
        )
    }
}

/** One-line status under the schematic, matching its colour story. */
@Composable
private fun ProbeCaption(probed: Boolean, hasRemote: Boolean, error: String?) {
    val (tint, text) = when {
        !probed -> R1.StatusAmber to "Scanning Home Assistant for shared settings…"
        error != null -> R1.StatusRed to error
        hasRemote -> R1.AccentGreen to "Shared settings found on this HA user."
        else -> R1.AccentWarm to "No shared settings yet. This device can seed them."
    }
    Text(text = text, style = responsiveType(R1.labelMicro), color = tint)
}

@Composable
private fun SectionLabel(text: String) {
    Text(text = text, style = responsiveType(R1.labelMicro), color = R1.InkSoft)
}

/**
 * A selectable option card: title + one-line consequence, with a left accent
 * rail and a fill that lights up when chosen. Used for both MODE and SEED-FROM
 * so the two decisions read as the same kind of choice.
 */
@Composable
private fun ChoiceCard(
    title: String,
    body: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = R1.MinTarget)
            .clip(R1.ShapeS)
            .background(if (selected) accent.copy(alpha = 0.12f) else R1.SurfaceMuted)
            .border(1.dp, if (selected) accent else R1.Hairline, R1.ShapeS)
            .r1Pressable(
                onClick = onClick,
                contentDescription = "$title. $body${if (selected) ". Selected" else ""}",
            )
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(R1.space.m),
    ) {
        // Selection marker: a filled bar when chosen, a hairline ring when not.
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 30.dp)
                .clip(R1.ShapeRound)
                .background(if (selected) accent else R1.Hairline),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = responsiveType(R1.labelMicro),
                color = if (selected) accent else R1.Ink,
            )
            Spacer(Modifier.height(R1.space.xxs))
            Text(text = body, style = responsiveType(R1.body), color = R1.InkSoft)
        }
    }
}

/** Tappable header that expands the per-category opt-out list. Summarises the
 *  count so the user knows what's on without expanding. */
@Composable
private fun CategoryDisclosureHeader(
    onCount: Int,
    total: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(200),
        label = "categoryChevron",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = R1.MinTarget)
            .clip(R1.ShapeS)
            .r1Pressable(
                onClick = onToggle,
                contentDescription = "What syncs, $onCount of $total on. ${if (expanded) "Collapse" else "Expand"}",
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "WHAT SYNCS",
            style = responsiveType(R1.labelMicro),
            color = R1.InkSoft,
        )
        Spacer(Modifier.size(R1.space.s))
        Text(
            text = "$onCount / $total ON",
            style = responsiveType(R1.labelMicro),
            color = R1.AccentWarm,
            modifier = Modifier.weight(1f),
        )
        // A simple ">" that rotates to "v" on expand.
        Text(
            text = "›",
            style = responsiveType(R1.bodyEmph),
            color = R1.InkSoft,
            modifier = Modifier.rotate(chevron),
        )
    }
}

@Composable
private fun PromptButton(
    text: String,
    tint: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .heightIn(min = R1.MinTarget)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, if (enabled) tint.copy(alpha = 0.4f) else R1.Hairline, R1.ShapeS)
            .then(
                if (enabled) {
                    Modifier.r1Pressable(onClick = onClick, contentDescription = text)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = R1.space.m, vertical = R1.space.m),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = responsiveType(R1.labelMicro),
            color = if (enabled) tint else tint.copy(alpha = 0.4f),
        )
    }
}
