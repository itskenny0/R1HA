package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * One top-level navigation target shown by [AdaptiveNavShell]. [route] is the navigation
 * route to push when tapped; [label] is the human title; [glyph] is a 1-2 char monospace
 * mark drawn in the rail / drawer (kept text so the shell has zero coupling to the per-glyph
 * vector composables and stays cheap to skip). [matchRoutes] lets a destination light up for
 * several routes (e.g. the Home item highlights on both the card stack and the dashboard).
 */
@Immutable
data class NavDestination(
    val route: String,
    val label: String,
    val glyph: String,
    val matchRoutes: Set<String> = setOf(route),
)

/**
 * The adaptive navigation shell that frames the whole app. It decides the navigation
 * *affordance* by [WindowTier] while leaving the actual screen content ([content]) untouched:
 *
 * - [WindowTier.R1] and [WindowTier.COMPACT]: PURE PASSTHROUGH. The shell renders nothing of
 *   its own; [content] fills the window exactly as it did before this system existed. This is
 *   the contract that keeps the Rabbit R1's card-stack / wheel experience bit-for-bit
 *   unchanged. The per-screen chrome (the card stack's hamburger, the dashboard's top bar)
 *   remains the navigation on these tiers.
 * - [WindowTier.MEDIUM]: a slim [NavigationRail] pinned to the leading edge with the
 *   top-level destinations; content fills the rest.
 * - [WindowTier.EXPANDED] / [WindowTier.EXTRA_LARGE]: a permanent labelled drawer on the
 *   leading edge; content fills the rest. On extra-large the content side additionally
 *   centres + caps its width via the screen's own [R1CenteredContent] usage.
 *
 * The shell is intentionally dumb about navigation mechanics: it calls [onNavigate] with a
 * route and reads [currentRoute] to highlight the active item. The host (MainActivity) wires
 * those to the NavController. This keeps the shell unit-reasoned and reusable.
 *
 * Why a custom rail/drawer instead of Material's NavigationRail/ModalDrawer: the app has a
 * sharp, hairline-ruled "mission control" language (see [R1] tokens) that Material's rounded,
 * rippled chrome fights. Hand-rolling keeps the rail/drawer in the same visual family as
 * every other surface, exactly as the existing [R1TopBar] does for top bars.
 */
@Composable
fun AdaptiveNavShell(
    destinations: List<NavDestination>,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** When false the shell is a pure passthrough on EVERY tier, regardless of width. The
     *  host suppresses chrome on full-bleed flows where a rail / drawer makes no sense yet:
     *  onboarding (no server configured), the long-lived-token setup, etc. Defaults to true. */
    showChrome: Boolean = true,
    content: @Composable () -> Unit,
) {
    val window by androidx.compose.runtime.rememberUpdatedState(LocalWindowTier.current)
    val tier = window.tier

    when {
        // Chrome suppressed by the host: full-bleed passthrough on any tier.
        !showChrome -> {
            content()
        }
        // Smallest two tiers: do not add any chrome. Preserve today's experience exactly.
        !tier.isAtLeast(WindowTier.MEDIUM) -> {
            content()
        }
        // Medium: compact icon rail.
        tier == WindowTier.MEDIUM -> {
            Row(modifier = modifier.fillMaxSize()) {
                NavRail(
                    destinations = destinations,
                    currentRoute = currentRoute,
                    onNavigate = onNavigate,
                )
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) { content() }
            }
        }
        // Expanded / extra-large: permanent labelled drawer.
        else -> {
            Row(modifier = modifier.fillMaxSize()) {
                NavDrawer(
                    destinations = destinations,
                    currentRoute = currentRoute,
                    onNavigate = onNavigate,
                )
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) { content() }
            }
        }
    }
}

/** Is [dest] the active destination for [currentRoute]? */
private fun NavDestination.isActive(currentRoute: String?): Boolean =
    currentRoute != null && currentRoute in matchRoutes

/** Slim icon rail for MEDIUM tier: glyph above a tiny label, full-height, hairline trailing
 *  edge. Scrolls if the destination list outgrows the height. */
@Composable
private fun NavRail(
    destinations: List<NavDestination>,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(76.dp)
            .fillMaxHeight()
            .background(R1.Surface)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(vertical = R1.space.m),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(R1.space.xs),
    ) {
        for (dest in destinations) {
            RailItem(
                dest = dest,
                active = dest.isActive(currentRoute),
                onClick = { onNavigate(dest.route) },
            )
        }
    }
}

@Composable
private fun RailItem(
    dest: NavDestination,
    active: Boolean,
    onClick: () -> Unit,
) {
    val accent = if (active) R1.AccentWarm else R1.InkSoft
    Column(
        modifier = Modifier
            .width(60.dp)
            .clip(R1.ShapeM)
            .background(if (active) R1.AccentWarm.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
            .r1Pressable(onClick = onClick, contentDescription = dest.label)
            .heightIn(min = R1.MinTarget)
            .padding(vertical = R1.space.s),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(R1.space.xxs),
    ) {
        Text(text = dest.glyph, style = R1.numeralM, color = accent, textAlign = TextAlign.Center)
        Text(
            text = dest.label.uppercase(),
            style = R1.labelMicro,
            color = accent,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Permanent labelled drawer for EXPANDED / EXTRA_LARGE tiers: a product wordmark header,
 *  then full-width labelled rows. Hairline trailing edge keeps it in the app's rule
 *  language. */
@Composable
private fun NavDrawer(
    destinations: List<NavDestination>,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    Row {
        Column(
            modifier = Modifier
                .width(232.dp)
                .fillMaxHeight()
                .background(R1.Surface)
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(vertical = R1.space.l, horizontal = R1.space.m),
            verticalArrangement = Arrangement.spacedBy(R1.space.xxs),
        ) {
            // Wordmark header — uses the accent so the drawer reads as branded chrome.
            Text(
                text = "R1·HA",
                style = R1.screenTitle,
                color = R1.AccentWarm,
                modifier = Modifier.padding(start = R1.space.s, bottom = R1.space.m),
            )
            for (dest in destinations) {
                DrawerItem(
                    dest = dest,
                    active = dest.isActive(currentRoute),
                    onClick = { onNavigate(dest.route) },
                )
            }
        }
        // Trailing hairline.
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(R1.Hairline),
        )
    }
}

@Composable
private fun DrawerItem(
    dest: NavDestination,
    active: Boolean,
    onClick: () -> Unit,
) {
    val accent = if (active) R1.AccentWarm else R1.Ink
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(if (active) R1.AccentWarm.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
            .r1Pressable(onClick = onClick, contentDescription = dest.label)
            .heightIn(min = R1.MinTarget)
            .padding(horizontal = R1.space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Active accent bar on the leading edge — the same "you are here" cue the card stack
        // uses, translated to the drawer.
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (active) R1.AccentWarm else androidx.compose.ui.graphics.Color.Transparent),
        )
        Spacer(Modifier.width(R1.space.m))
        Text(
            text = dest.glyph,
            style = R1.numeralM,
            color = accent,
            modifier = Modifier.width(28.dp),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.width(R1.space.s))
        Text(
            text = dest.label,
            style = if (active) R1.bodyEmph else R1.body,
            color = accent,
            maxLines = 1,
        )
    }
}

/**
 * The canonical set of top-level destinations the shell exposes on tablet tiers. Kept here so
 * the shell ships with a sensible default and MainActivity doesn't have to assemble the list
 * inline. Routes reference the same string constants the nav graph registers; the host maps
 * them onto the NavController. Glyphs are short monospace marks chosen to read at rail size.
 *
 * The first entry's [matchRoutes] covers BOTH home surfaces (card stack + dashboard) so the
 * "Home" item stays lit whichever one the user is on.
 */
@Suppress("unused")
fun defaultNavDestinations(
    homeRoute: String,
    dashboardRoute: String,
    searchRoute: String,
    assistRoute: String,
    settingsRoute: String,
): List<NavDestination> = listOf(
    NavDestination(
        route = homeRoute,
        label = "Home",
        glyph = "▣",
        matchRoutes = setOf(homeRoute, dashboardRoute),
    ),
    NavDestination(route = dashboardRoute, label = "Today", glyph = "◴"),
    NavDestination(route = searchRoute, label = "Search", glyph = "⌕"),
    NavDestination(route = assistRoute, label = "Assist", glyph = "◌"),
    NavDestination(route = settingsRoute, label = "Settings", glyph = "⚙"),
)
