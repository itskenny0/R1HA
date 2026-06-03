package com.github.itskenny0.r1ha.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.prefs.NavItemId
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.nav.PinnableSurface
import com.github.itskenny0.r1ha.nav.PinnableSurfaces
import com.github.itskenny0.r1ha.ui.components.R1Row
import com.github.itskenny0.r1ha.ui.components.R1Switch
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.coroutines.launch

/**
 * "Manage sidebar" config surface (Routes.SIDEBAR_CONFIG). Lets the user choose
 * exactly what appears in the side navigation rail / drawer on tablet tiers (and
 * the phone QuickActions drawer's PINNED / DASHBOARDS sections), in one place:
 *
 *  - CORE DESTINATIONS: a switch per hideable core item (Today / Search / Assist).
 *    On = visible, off = hidden (persisted via [NavPanelSettings.hiddenNavItems]).
 *    Home and Settings are intentionally absent: the rail keeps them always-on so
 *    there is always a route home and to settings.
 *  - PINNED SURFACES: the user's currently-pinned surfaces, in display order, each
 *    with up/down reorder controls (committed via [SettingsRepository.setPinnedSurfaces])
 *    and a switch to unpin.
 *  - ALL SURFACES: every entry in [PinnableSurfaces.ALL] with a switch bound to its
 *    membership in the pinned list (on => pinSurface, off => unpinSurface).
 *  - PINNED DASHBOARDS: the user's pinned Lovelace views, each with an unpin control.
 *    (Pinning a dashboard happens from the dashboards list / a view's top bar.)
 *
 * Every mutation persists live through the existing repository mutators / the
 * SettingsViewModel.updateNavPanel hook; nothing is duplicated here.
 */
@Composable
fun SidebarConfigScreen(
    settings: SettingsRepository,
    tokens: TokenStore,
    onBack: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(settings, tokens))
    val s by vm.state.collectAsStateWithLifecycle()
    val navPanel = s.navPanel
    val scope = rememberCoroutineScope()

    // Resolve the persisted pin list to renderable surfaces (drops unknown ids).
    val pinnedSurfaces = remember(navPanel.pinnedSurfaces) {
        PinnableSurfaces.resolve(navPanel.pinnedSurfaces)
    }
    val pinnedRouteSet = remember(navPanel.pinnedSurfaces) { navPanel.pinnedSurfaces.toSet() }

    Box(modifier = Modifier.fillMaxSize().background(R1.Bg).systemBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            R1TopBar(title = "MANAGE SIDEBAR", onBack = onBack)

            // Centre + width-cap the rows on tablet / desktop tiers so a switch
            // row doesn't span a 1280 dp+ panel. R1 / compact fill.
            val dimens = com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens()
            val listModifier = if (dimens.capsContentWidth) {
                Modifier
                    .fillMaxSize()
                    .widthIn(max = dimens.maxContentWidth)
                    .align(Alignment.CenterHorizontally)
            } else {
                Modifier.fillMaxSize()
            }
            LazyColumn(
                modifier = listModifier,
                verticalArrangement = Arrangement.spacedBy(R1.space.xxs),
            ) {
                item {
                    Text(
                        text = "Choose what shows in the navigation sidebar. " +
                            "On tablet tiers this is the rail / drawer; on the phone it is " +
                            "the QuickActions drawer's PINNED and DASHBOARDS sections.",
                        style = R1.body,
                        color = R1.InkMuted,
                        modifier = Modifier.padding(
                            horizontal = R1.space.xl,
                            vertical = R1.space.l,
                        ),
                    )
                }

                // ── CORE DESTINATIONS ──
                item { SectionHeader("CORE DESTINATIONS") }
                items(coreNavItems, key = { "core-${it.id}" }) { core ->
                    val visible = core.id !in navPanel.hiddenNavItems
                    GlyphSwitchRow(
                        glyph = core.glyph,
                        label = core.label,
                        subtitle = if (visible) "Shown" else "Hidden",
                        checked = visible,
                        onCheckedChange = { show ->
                            vm.updateNavPanel { panel ->
                                val next = panel.hiddenNavItems.toMutableSet()
                                if (show) next.remove(core.id) else next.add(core.id)
                                panel.copy(hiddenNavItems = next)
                            }
                        },
                    )
                }
                item {
                    Text(
                        text = "Home and Settings are always shown.",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                        modifier = Modifier.padding(
                            horizontal = R1.space.xl,
                            vertical = R1.space.xs,
                        ),
                    )
                }

                // ── PINNED SURFACES (reorderable) ──
                if (pinnedSurfaces.isNotEmpty()) {
                    item { SectionHeader("PINNED SURFACES") }
                    itemsIndexed(pinnedSurfaces) { index, surface ->
                        PinnedSurfaceRow(
                            surface = surface,
                            isFirst = index == 0,
                            isLast = index == pinnedSurfaces.lastIndex,
                            onMoveUp = {
                                scope.launch {
                                    settings.setPinnedSurfaces(
                                        movedByRoute(navPanel.pinnedSurfaces, surface.route, -1),
                                    )
                                }
                            },
                            onMoveDown = {
                                scope.launch {
                                    settings.setPinnedSurfaces(
                                        movedByRoute(navPanel.pinnedSurfaces, surface.route, +1),
                                    )
                                }
                            },
                            onUnpin = { scope.launch { settings.unpinSurface(surface.route) } },
                        )
                    }
                }

                // ── ALL SURFACES (pin / unpin) ──
                item { SectionHeader("ALL SURFACES") }
                items(PinnableSurfaces.ALL, key = { "all-${it.route}" }) { surface ->
                    val pinned = surface.route in pinnedRouteSet
                    GlyphSwitchRow(
                        glyph = surface.glyph,
                        label = surface.label,
                        subtitle = if (pinned) "Pinned" else null,
                        checked = pinned,
                        onCheckedChange = { pin ->
                            scope.launch {
                                if (pin) settings.pinSurface(surface.route)
                                else settings.unpinSurface(surface.route)
                            }
                        },
                    )
                }

                // ── PINNED DASHBOARDS (unpin) ──
                if (navPanel.pinnedDashboards.isNotEmpty()) {
                    item { SectionHeader("PINNED DASHBOARDS") }
                    items(navPanel.pinnedDashboards, key = { "dash-${it.route}" }) { dash ->
                        GlyphSwitchRow(
                            glyph = "▤",
                            label = dash.title,
                            subtitle = "Pinned",
                            checked = true,
                            onCheckedChange = { keep ->
                                if (!keep) scope.launch { settings.unpinDashboard(dash.route) }
                            },
                        )
                    }
                    item {
                        Text(
                            text = "Pin a dashboard from the dashboards list or a view's top bar.",
                            style = R1.labelMicro,
                            color = R1.InkSoft,
                            modifier = Modifier.padding(
                                horizontal = R1.space.xl,
                                vertical = R1.space.xs,
                            ),
                        )
                    }
                }

                item { Spacer(Modifier.height(R1.space.xl)) }
            }
        }
    }
}

/** A core nav item the user is allowed to hide (mirrors [NavItemId.HIDEABLE]).
 *  Glyphs match the rail's [com.github.itskenny0.r1ha.ui.components.defaultNavDestinations]. */
private data class CoreNavItem(val id: String, val label: String, val glyph: String)

private val coreNavItems: List<CoreNavItem> = listOf(
    CoreNavItem(NavItemId.TODAY, "Today", "◴"),
    CoreNavItem(NavItemId.SEARCH, "Search", "⌕"),
    CoreNavItem(NavItemId.ASSIST, "Assist", "◌"),
)

/** Move [route] by [delta] positions in [list] (the persisted route-id list), returning a new
 *  list. Resolving the position from the route itself (rather than a row index into the
 *  possibly-shorter resolved list) keeps the reorder correct even when the persisted list
 *  carries unknown ids that the rendered list dropped. Out-of-range targets are no-ops. */
private fun movedByRoute(list: List<String>, route: String, delta: Int): List<String> {
    val from = list.indexOf(route)
    if (from < 0) return list
    val to = from + delta
    if (to !in list.indices) return list
    return list.toMutableList().apply { add(to, removeAt(from)) }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = R1.sectionHeader,
        color = R1.AccentWarm,
        modifier = Modifier.padding(
            start = R1.space.xl,
            end = R1.space.xl,
            top = R1.space.l,
            bottom = R1.space.xs,
        ),
    )
}

/** A [R1Row] with the surface's monospace glyph as the leading mark and a trailing switch.
 *  Mirrors the Settings screen's SwitchRow, with the glyph the rail / drawer render. */
@Composable
private fun GlyphSwitchRow(
    glyph: String,
    label: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    R1Row(
        label = label,
        description = subtitle,
        onClick = { onCheckedChange(!checked) },
        contentDescription = SettingsA11y.switchRowDescription(label, subtitle, checked),
        leadingContent = {
            Text(
                text = glyph,
                style = R1.numeralM,
                color = if (checked) R1.AccentWarm else R1.InkSoft,
                textAlign = TextAlign.Center,
                modifier = Modifier.size(28.dp),
            )
        },
        trailing = { R1Switch(checked = checked, onCheckedChange = onCheckedChange) },
    )
}

/** A pinned-surface row with up/down reorder pills and an unpin switch. */
@Composable
private fun PinnedSurfaceRow(
    surface: PinnableSurface,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onUnpin: () -> Unit,
) {
    R1Row(
        label = surface.label,
        contentDescription = "${surface.label}, pinned",
        leadingContent = {
            Text(
                text = surface.glyph,
                style = R1.numeralM,
                color = R1.AccentWarm,
                textAlign = TextAlign.Center,
                modifier = Modifier.size(28.dp),
            )
        },
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
            ) {
                ReorderPill(glyph = "↑", enabled = !isFirst, onClick = onMoveUp, description = "Move ${surface.label} up")
                ReorderPill(glyph = "↓", enabled = !isLast, onClick = onMoveDown, description = "Move ${surface.label} down")
                R1Switch(checked = true, onCheckedChange = { if (!it) onUnpin() })
            }
        },
    )
}

/** A small square up / down pill for reordering. Greys out + ignores taps at a boundary. */
@Composable
private fun ReorderPill(
    glyph: String,
    enabled: Boolean,
    onClick: () -> Unit,
    description: String,
) {
    val fg = if (enabled) R1.Ink else R1.InkSoft.copy(alpha = 0.4f)
    Box(
        modifier = Modifier
            .size(R1.MinTarget)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .then(
                if (enabled) {
                    Modifier.r1Pressable(onClick = onClick, contentDescription = description)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, style = R1.numeralM, color = fg, textAlign = TextAlign.Center)
    }
}
