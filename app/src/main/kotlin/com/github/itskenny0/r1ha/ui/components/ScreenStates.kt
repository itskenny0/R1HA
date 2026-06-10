package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.responsiveType

/**
 * Canonical empty state for list/collection screens: uppercase title, an
 * optional body explaining how the emptiness gets fixed (point at the HA
 * surface that creates the data), and an optional action chip. Replaces the
 * per-screen bare centred Text so every screen reads the same and none of
 * them strands the user without a next step. The card stack's favourites
 * EmptyState is the richer cousin (spinner, countdown, stalled affordances)
 * and intentionally stays bespoke.
 */
@Composable
fun R1EmptyState(
    title: String,
    body: String? = null,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    StateScaffold(
        title = title.uppercase(),
        titleColor = R1.InkSoft,
        body = body,
        actionText = actionText,
        actionContentDescription = actionText?.let { stateActionDescription(it) },
        onAction = onAction,
        modifier = modifier,
    )
}

/**
 * Canonical error state for a failed first load (nothing cached to show).
 * Always offer [onRetry] when the screen has a refresh path: the empty Box
 * isn't scrollable, so pull-to-refresh can't fire here and a bare error
 * message strands the user. Transient failures over populated lists should
 * stay toasts/banners; this is only for the full-screen replacement case.
 */
@Composable
fun R1ErrorState(
    title: String,
    message: String?,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    StateScaffold(
        title = title.uppercase(),
        titleColor = R1.StatusRed,
        body = message?.takeIf { it.isNotBlank() },
        actionText = if (onRetry != null) "RETRY" else null,
        // "Retry, couldn't load devices" rather than a context-free "Retry":
        // the chip is usually the only focusable node on the screen, so it
        // should carry what it retries.
        actionContentDescription = if (onRetry != null) retryActionDescription(title) else null,
        onAction = onRetry,
        modifier = modifier,
    )
}

@Composable
private fun StateScaffold(
    title: String,
    titleColor: androidx.compose.ui.graphics.Color,
    body: String?,
    actionText: String?,
    actionContentDescription: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .padding(horizontal = R1.space.xl, vertical = R1.space.l)
                // Title + body announce as one sentence-cased unit, and the
                // polite live region speaks the state when it replaces the
                // loading skeleton/content. The action chip merges its own
                // descendants (r1Pressable), so it stays a separate, tappable
                // node rather than folding into this phrase.
                .semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = stateAnnouncement(title, body)
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(R1.space.m),
        ) {
            Text(
                text = title,
                style = responsiveType(R1.sectionHeader),
                color = titleColor,
                textAlign = TextAlign.Center,
            )
            if (body != null) {
                Text(
                    text = body,
                    style = responsiveType(R1.body),
                    color = R1.InkMuted,
                    textAlign = TextAlign.Center,
                )
            }
            if (actionText != null && onAction != null) {
                R1Chip(
                    text = actionText,
                    modifier = Modifier.height(R1.MinTarget),
                    variant = R1ChipVariant.Action,
                    onClick = onAction,
                    contentDescription = actionContentDescription
                        ?: stateActionDescription(actionText),
                )
            }
        }
    }
}
