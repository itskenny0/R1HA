package com.github.itskenny0.r1ha.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.prefs.PhoneNavStyle
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * The logical section a [NavDestination] belongs to. The rail / drawer draw a divider
 * wherever consecutive destinations cross a group boundary, so the panel reads as grouped
 * sections (core app destinations, then user-pinned surfaces, then pinned dashboards)
 * rather than one undifferentiated list. Order matches the on-screen order.
 */
enum class NavGroup(val header: String?) {
    PRIMARY(null),
    PINNED("PINNED"),
    DASHBOARD("DASHBOARDS"),
}

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
    /** Stable id used to filter the destination out of the panel via the user's
     *  [com.github.itskenny0.r1ha.core.prefs.NavPanelSettings.hiddenNavItems].
     *  Defaults to [route] so call sites that don't set it (Home, Settings) keep a
     *  unique, never-hidden id. */
    val id: String = route,
    /** Which section this destination sits in; drives the grouping dividers. Defaults to
     *  [NavGroup.PRIMARY] so callers that don't group (previews, the default set) render
     *  as a single undivided section. */
    val group: NavGroup = NavGroup.PRIMARY,
)

/**
 * The adaptive navigation shell that frames the whole app. It decides the navigation
 * *affordance* by [WindowTier] while leaving the actual screen content ([content]) untouched:
 *
 * - [WindowTier.R1] and [WindowTier.COMPACT]: [content] fills the window exactly as before; the
 *   per-screen chrome (the card stack's hamburger, the dashboard's top bar) remains the
 *   navigation. When [phoneNavStyle] is [PhoneNavStyle.SLIDEOUT] (the default) the shell ALSO
 *   hosts a hamburger-triggered slide-out of the same navigation panel over the content,
 *   opened via [LocalNavDrawerController]; [PhoneNavStyle.MODAL] keeps the historical pure
 *   passthrough so the card stack opens its QuickActions sheet instead.
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
    /** Invoked by the always-present "Manage" edit affordance the rail / drawer render below
     *  the destinations. The host wires it to navigate to the sidebar-config surface so the
     *  user can change what the sidebar shows in one tap from the sidebar itself. Null hides
     *  the affordance (e.g. previews / tests that don't supply a target). */
    onConfigure: (() -> Unit)? = null,
    /** How the hamburger behaves on portrait phone tiers (R1 / COMPACT). [PhoneNavStyle.SLIDEOUT]
     *  (default) hosts the navigation panel as a hamburger-triggered slide-out over the card
     *  stack; [PhoneNavStyle.MODAL] leaves those tiers a pure passthrough so the card stack's
     *  hamburger opens its QuickActions sheet instead. Ignored on MEDIUM+ tiers, which always
     *  render the permanent rail / drawer. */
    phoneNavStyle: PhoneNavStyle = PhoneNavStyle.SLIDEOUT,
    /** The scroll-wheel source. When non-null and the portrait slide-out is open, the wheel
     *  scrolls the slide-out's destination list (touch scrolling still works either way), so
     *  the R1's primary input drives the menu it just opened. Null on hosts without a wheel. */
    wheelInput: com.github.itskenny0.r1ha.core.input.WheelInput? = null,
    content: @Composable () -> Unit,
) {
    val window by androidx.compose.runtime.rememberUpdatedState(LocalWindowTier.current)
    val tier = window.tier

    when {
        // Chrome suppressed by the host: full-bleed passthrough on any tier.
        !showChrome -> {
            content()
        }
        // Smallest two tiers (portrait phone / R1): no permanent chrome. The card stack's own
        // hamburger is the navigation. When the user keeps the SLIDEOUT style we host a
        // hamburger-triggered slide-out of the same panel over the content; otherwise this is
        // the historical pure passthrough and the hamburger opens the QuickActions modal.
        !tier.isAtLeast(WindowTier.MEDIUM) -> {
            PhoneNavSlideoutHost(
                enabled = showChrome && phoneNavStyle == PhoneNavStyle.SLIDEOUT,
                destinations = destinations,
                currentRoute = currentRoute,
                onNavigate = onNavigate,
                onConfigure = onConfigure,
                wheelInput = wheelInput,
                modifier = modifier,
                content = content,
            )
        }
        // Medium: compact icon rail.
        tier == WindowTier.MEDIUM -> {
            Row(modifier = modifier.fillMaxSize()) {
                NavRail(
                    destinations = destinations,
                    currentRoute = currentRoute,
                    onNavigate = onNavigate,
                    onConfigure = onConfigure,
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
                    onConfigure = onConfigure,
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
    onConfigure: (() -> Unit)?,
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
        destinations.forEachIndexed { i, dest ->
            // Divider where the section changes (core → pinned → dashboards).
            if (i > 0 && dest.group != destinations[i - 1].group) RailDivider()
            RailItem(
                dest = dest,
                active = dest.isActive(currentRoute),
                onClick = { onNavigate(dest.route) },
            )
        }
        // Always-present edit affordance: opens the sidebar-config surface so the user
        // can change what the sidebar shows without leaving it. Rendered as a quiet
        // pencil glyph item below the destinations, set off by its own divider.
        if (onConfigure != null) {
            if (destinations.isNotEmpty()) RailDivider()
            RailItem(
                dest = NavDestination(route = "__configure__", label = "Manage", glyph = "✎"),
                active = false,
                onClick = onConfigure,
            )
        }
    }
}

/** A short centred hairline separating sections in the rail. */
@Composable
private fun RailDivider() {
    Box(
        modifier = Modifier
            .padding(vertical = R1.space.xs)
            .width(28.dp)
            .height(1.dp)
            .background(R1.Hairline),
    )
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
            text = dest.label.uppercase(java.util.Locale.US),
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
    onConfigure: (() -> Unit)?,
) {
    Row {
        NavDrawerContent(
            destinations = destinations,
            currentRoute = currentRoute,
            onNavigate = onNavigate,
            onConfigure = onConfigure,
            modifier = Modifier
                .width(232.dp)
                .fillMaxHeight()
                .background(R1.Surface)
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(vertical = R1.space.l, horizontal = R1.space.m),
        )
        // Trailing hairline.
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(R1.Hairline),
        )
    }
}

/** The drawer body shared by the permanent [NavDrawer] and the portrait slide-out: a product
 *  wordmark header with a one-tap Manage glyph, the destination rows, and a labelled Manage
 *  row. [modifier] supplies the surface chrome (width / background / scroll / padding) so the
 *  permanent and slide-out hosts can size it differently while the content stays identical. */
@Composable
private fun NavDrawerContent(
    destinations: List<NavDestination>,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    onConfigure: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(R1.space.xxs),
    ) {
        // Wordmark header — uses the accent so the drawer reads as branded chrome.
        // A trailing edit glyph opens the sidebar-config surface in one tap.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = R1.space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "R1·HA",
                style = R1.screenTitle,
                color = R1.AccentWarm,
                modifier = Modifier.padding(start = R1.space.s).weight(1f),
            )
            if (onConfigure != null) {
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeM)
                        .r1Pressable(onClick = onConfigure, contentDescription = "Manage sidebar")
                        .heightIn(min = R1.MinTarget)
                        .width(R1.MinTarget),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "✎", style = R1.numeralM, color = R1.InkSoft, textAlign = TextAlign.Center)
                }
            }
        }
        destinations.forEachIndexed { i, dest ->
            // Section boundary (core → pinned surfaces → pinned dashboards): a divider, then
            // the section's label so each group reads as a titled section.
            val sectionStart = i == 0 || dest.group != destinations[i - 1].group
            if (sectionStart && i > 0) DrawerDivider()
            if (sectionStart) dest.group.header?.let { DrawerSectionLabel(it) }
            DrawerItem(
                dest = dest,
                active = dest.isActive(currentRoute),
                onClick = { onNavigate(dest.route) },
            )
        }
        // Always-present labelled edit row below the destinations — mirrors the rail's
        // Manage affordance for users who don't spot the header glyph, set off by a divider.
        if (onConfigure != null) {
            if (destinations.isNotEmpty()) DrawerDivider()
            DrawerItem(
                dest = NavDestination(route = "__configure__", label = "Manage sidebar", glyph = "✎"),
                active = false,
                onClick = onConfigure,
            )
        }
    }
}

/** A small uppercase section title for a drawer / slide-out group (PINNED, DASHBOARDS). */
@Composable
private fun DrawerSectionLabel(text: String) {
    Text(
        text = text,
        style = R1.labelMicro,
        color = R1.InkSoft,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = R1.space.s, bottom = R1.space.xxs),
    )
}

/** A full-width hairline separating sections in the drawer / slide-out. */
@Composable
private fun DrawerDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = R1.space.xs)
            .height(1.dp)
            .background(R1.Hairline),
    )
}

/**
 * Portrait-phone host (R1 / COMPACT). Renders [content] full-bleed and, when [enabled],
 * overlays a hamburger-triggered slide-out of the same navigation panel the tablet drawer
 * shows. The open-state is owned here and exposed to descendants (the card-stack hamburger)
 * via [LocalNavDrawerController], so the chrome can open the panel without owning its state.
 *
 * When [enabled] is false this is a pure passthrough and the provided controller is inert,
 * so the card stack falls back to its QuickActions modal. The slide-out closes on scrim tap,
 * system back, and after any navigation.
 */
@Composable
private fun PhoneNavSlideoutHost(
    enabled: Boolean,
    destinations: List<NavDestination>,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    onConfigure: (() -> Unit)?,
    wheelInput: com.github.itskenny0.r1ha.core.input.WheelInput? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val openState = remember { mutableStateOf(false) }
    val controller = remember(enabled) { NavDrawerController(available = enabled, openState = openState) }
    // Hoisted so the wheel scroller and the panel's verticalScroll share one state, and the
    // scroll position survives a close/reopen.
    val panelScroll = rememberScrollState()
    // If the panel becomes unavailable (style flip, panel disabled), force it shut so a stale
    // open-state can't resurface the overlay if the panel is re-enabled later. Done in an
    // effect rather than inline so we never write state during composition.
    LaunchedEffect(enabled) { if (!enabled) openState.value = false }

    CompositionLocalProvider(LocalNavDrawerController provides controller) {
        Box(modifier = modifier.fillMaxSize()) {
            content()
            if (enabled) {
                val open = openState.value
                // Scrim: fades in behind the panel, tap anywhere to dismiss. No ripple.
                AnimatedVisibility(
                    visible = open,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(180)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClickLabel = "Close menu",
                                onClick = { openState.value = false },
                            ),
                    )
                }
                // Panel: slides in from the leading edge. Capped so it never fully covers a
                // wider COMPACT phone, but takes most of the narrow R1 width.
                AnimatedVisibility(
                    visible = open,
                    enter = slideInHorizontally(tween(220)) { -it },
                    exit = slideOutHorizontally(tween(200)) { -it },
                ) {
                    // While the panel is shown, route the scroll wheel to its list so the R1's
                    // primary input scrolls the menu it just opened. Mounted inside the visible
                    // content so the collector only runs while open; touch scroll works anyway.
                    if (wheelInput != null) {
                        WheelScrollForScrollState(wheelInput = wheelInput, scrollState = panelScroll)
                    }
                    Row {
                        NavDrawerContent(
                            destinations = destinations,
                            currentRoute = currentRoute,
                            onNavigate = { route ->
                                openState.value = false
                                onNavigate(route)
                            },
                            onConfigure = onConfigure?.let { cfg ->
                                {
                                    openState.value = false
                                    cfg()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth(0.82f)
                                .widthIn(max = 300.dp)
                                .fillMaxHeight()
                                .background(R1.Surface)
                                .systemBarsPadding()
                                .verticalScroll(panelScroll)
                                .padding(vertical = R1.space.l, horizontal = R1.space.m),
                        )
                        // Trailing hairline, matching the permanent drawer's edge.
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(R1.Hairline),
                        )
                    }
                }
                // System back closes the panel before it pops the nav back-stack.
                BackHandler(enabled = open) { openState.value = false }
            }
        }
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
    NavDestination(route = dashboardRoute, label = "Today", glyph = "◴", id = "today"),
    NavDestination(route = searchRoute, label = "Search", glyph = "⌕", id = "search"),
    NavDestination(route = assistRoute, label = "Assist", glyph = "◌", id = "assist"),
    NavDestination(route = settingsRoute, label = "Settings", glyph = "⚙"),
)
