package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * Generic list / detail scaffold that adapts to the window's [WindowTier].
 *
 * - On [WindowTier.R1] and [WindowTier.COMPACT]: a single pane. The list shows until the
 *   caller selects something ([hasSelection] true), then the detail takes over. Back is the
 *   caller's job (clear the selection); this scaffold only decides *which* pane to draw.
 * - On [WindowTier.MEDIUM]: two panes side by side WHEN there is enough room and the caller
 *   opted in via [allowTwoPaneOnMedium] (default true); otherwise it falls back to the
 *   single-pane behaviour above.
 * - On [WindowTier.EXPANDED] / [WindowTier.EXTRA_LARGE]: always two panes. The list takes a
 *   fixed, comfortable width ([listPaneWidth]) and the detail fills the rest, so the list
 *   doesn't balloon to half a 13" screen.
 *
 * It is deliberately content-agnostic: the caller supplies [list] and [detail] composables
 * and owns all selection state. This keeps the scaffold reusable for any future surface
 * (registry browsers, search results, a device list) that wants a tablet two-pane upgrade
 * without re-implementing the breakpoint plumbing.
 *
 * Adoption pattern:
 * ```
 * R1ListDetailPane(
 *     hasSelection = selected != null,
 *     list = { ItemList(onSelect = { selected = it }) },
 *     detail = { selected?.let { ItemDetail(it) } ?: EmptyDetailPlaceholder() },
 * )
 * ```
 * When two-pane is active, [list] and [detail] are composed at the same time, so make the
 * list highlight its selected row (the detail is visible right beside it).
 */
@Composable
fun R1ListDetailPane(
    hasSelection: Boolean,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /** Fixed width of the list column in the two-pane layout. Tuned so a list row stays
     *  readable without crowding the detail. */
    listPaneWidth: Dp = 340.dp,
    /** Whether MEDIUM tier may show two panes. Some surfaces with very wide detail content
     *  prefer to stay single-pane until EXPANDED; pass false for those. */
    allowTwoPaneOnMedium: Boolean = true,
    /** Placeholder shown in the detail pane (two-pane mode) when nothing is selected. */
    emptyDetail: @Composable () -> Unit = { DefaultEmptyDetail() },
) {
    val window by androidx.compose.runtime.rememberUpdatedState(LocalWindowTier.current)
    val info = window
    val twoPane = when {
        info.tier.isAtLeast(WindowTier.EXPANDED) -> true
        info.tier == WindowTier.MEDIUM -> allowTwoPaneOnMedium && info.isLandscape
        else -> false
    }

    if (twoPane) {
        Row(modifier = modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .width(listPaneWidth)
                    .fillMaxHeight(),
            ) { list() }
            // Hairline divider between panes, matching the app's 1dp rule language.
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(R1.Hairline),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                if (hasSelection) detail() else emptyDetail()
            }
        }
    } else {
        // Single pane: show the detail once something is selected, else the list.
        Box(modifier = modifier.fillMaxSize()) {
            if (hasSelection) detail() else list()
        }
    }
}

/**
 * Convenience overload for surfaces whose detail content is naturally centred and width
 * capped (forms, a single entity readout): in two-pane mode the detail is centred within its
 * pane and capped at [detailMaxWidth] so it doesn't stretch across a huge window. Falls
 * through to [R1ListDetailPane] for the actual breakpoint logic.
 */
@Composable
fun R1ListDetailPaneCapped(
    hasSelection: Boolean,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    listPaneWidth: Dp = 340.dp,
    detailMaxWidth: Dp = 720.dp,
    allowTwoPaneOnMedium: Boolean = true,
) {
    R1ListDetailPane(
        hasSelection = hasSelection,
        modifier = modifier,
        listPaneWidth = listPaneWidth,
        allowTwoPaneOnMedium = allowTwoPaneOnMedium,
        list = list,
        detail = {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(modifier = Modifier.widthIn(max = detailMaxWidth).fillMaxWidth()) { detail() }
            }
        },
    )
}

/** Quiet placeholder for the detail pane before a selection exists. */
@Composable
private fun DefaultEmptyDetail() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "SELECT AN ITEM", style = R1.label, color = R1.InkMuted)
    }
}

/**
 * Centres [content] in a width-capped column when the current tier caps content width
 * (medium and up), otherwise fills the available width (R1 / compact never letterbox).
 * The drop-in replacement for an ad-hoc "centre on tablet" wrapper: it reads the tier and
 * the cap from the responsive tokens so every screen treats large windows the same way.
 *
 * Use it to wrap list / form screen bodies that should read as a centred column on big
 * displays rather than stretching every row across the panel.
 */
@Composable
fun R1CenteredContent(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Center,
    content: @Composable () -> Unit,
) {
    val dimens = com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens()
    if (!dimens.capsContentWidth) {
        Box(modifier = modifier.fillMaxSize()) { content() }
        return
    }
    Row(
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = horizontalArrangement,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = dimens.maxContentWidth)
                .fillMaxWidth()
                .fillMaxHeight(),
        ) { content() }
    }
}
