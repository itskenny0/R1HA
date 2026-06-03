package com.github.itskenny0.r1ha.feature.zones

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.ui.components.AutoRefresh
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.WindowTier
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.icons.R1Icons

/**
 * Zones surface: a list-by-zone view of who's currently where,
 * plus a small abstract map at the top showing the relative
 * geographic layout of every zone (and the tracked people / devices
 * inside or around them).
 *
 * The map is a Compose Canvas: no tiles, no actual map data; it
 * draws each zone as a circle sized by its radius attribute and
 * positioned by its lat/lon, normalised to fit inside the canvas
 * with a 10 % margin. Tracked entities reporting GPS are plotted as
 * small dots on top. Occupied zones get a filled accent; empty zones
 * a hairline outline. This is much less than a real map but enough to
 * communicate the geographic relationship between zones at a glance.
 *
 * Below the map: a list of zones, each carrying its icon, occupant
 * names + radius. Outside (`not_home`) persons collect under a final
 * OUTSIDE section.
 */
@Composable
fun ZonesScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: ZonesViewModel = viewModel(factory = ZonesViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    // 60s auto-refresh: persons move slowly; tighter would waste API.
    AutoRefresh(everyMillis = 60_000L) { vm.refresh() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(
            title = "ZONES",
            onBack = onBack,
            action = {
                // Refresh chip routed through R1Chip so the busy/idle state is
                // spoken rather than only shown by the swapped glyph.
                val busy = ui.loading || ui.refreshing
                R1Chip(
                    text = if (busy) "..." else "REFRESH",
                    variant = R1ChipVariant.Action,
                    onClick = { vm.refresh() },
                    modifier = Modifier
                        .heightIn(min = R1.MinTarget)
                        .semantics {
                            contentDescription =
                                if (busy) "Refreshing zones" else "Refresh zones"
                        },
                )
            },
        )
        com.github.itskenny0.r1ha.ui.layout.AdaptiveContent(modifier = Modifier.weight(1f)) {
        when {
            ui.loading && ui.zones.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(22.dp)
                        .semantics { contentDescription = "Loading zones" },
                    strokeWidth = 2.dp,
                    color = R1.AccentWarm,
                )
            }
            ui.error != null && ui.zones.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Zones load failed: ${ui.error}",
                    style = responsiveType(R1.body),
                    color = R1.StatusRed,
                )
            }
            ui.zones.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No zones defined. Settings, Areas & Zones in HA's web UI.",
                    style = responsiveType(R1.body),
                    color = R1.InkMuted,
                )
            }
            else -> PullToRefreshBox(
                isRefreshing = ui.refreshing,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                val dimens = rememberResponsiveDimens()
                // The map preview scales with the panel: a slab on the R1 would
                // eat the whole viewport, while 200dp marooned in the centre of a
                // 13in panel reads as an afterthought. Grow it gently by tier.
                val mapHeight = when (dimens.tier) {
                    WindowTier.R1 -> 160.dp
                    WindowTier.COMPACT -> 200.dp
                    WindowTier.MEDIUM -> 240.dp
                    else -> 280.dp
                }
                // On roomy tiers the single zone column wastes the centred width,
                // so flow the zone rows two-up. Mini / compact stay one column.
                val zoneColumns = if (dimens.tier.isAtLeast(WindowTier.EXPANDED)) 2 else 1
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = dimens.screenGutter, vertical = R1.space.s,
                    ),
                    verticalArrangement = Arrangement.spacedBy(R1.space.s),
                ) {
                    // Map preview: only when at least two points (zones
                    // and/or trackers) carry lat/lon so there's something
                    // meaningful to draw.
                    val mappableZones = ui.zones.filter {
                        it.latitude != null && it.longitude != null
                    }
                    val points = mappableZones.size + ui.trackers.size
                    if (mappableZones.isNotEmpty() && points >= 2) {
                        item("__map__") {
                            ZoneMap(
                                zones = mappableZones,
                                trackers = ui.trackers,
                                height = mapHeight,
                            )
                        }
                    }
                    if (zoneColumns > 1) {
                        // Pair the rows into two-wide bands so the extra width is
                        // used; each cell shares the row height via weight.
                        val rows = ui.zones.chunked(zoneColumns)
                        items(items = rows, key = { it.first().entityId }) { chunk ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(R1.space.s),
                            ) {
                                chunk.forEach { zone ->
                                    Box(modifier = Modifier.weight(1f)) { ZoneRow(zone) }
                                }
                                // Keep a half-pair left-aligned by padding the gap.
                                repeat(zoneColumns - chunk.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    } else {
                        items(items = ui.zones, key = { it.entityId }) { zone ->
                            ZoneRow(zone)
                        }
                    }
                    if (ui.outside.isNotEmpty()) {
                        item("__outside__") {
                            OutsideRow(names = ui.outside)
                        }
                    }
                }
            }
        }
        } // AdaptiveContent
    }
}

/**
 * Abstract map of every zone + tracked entity: Compose Canvas, not a
 * real geo map. Each zone is a circle sized by its `radius` attribute,
 * positioned in [0..1] coordinate space using the bounding box of every
 * plotted point's lat/lon, then projected onto the canvas with a 10%
 * margin so the outermost markers aren't clipped at the edge.
 *
 * Zones: filled when occupied, hairline-outlined when empty. Trackers:
 * small dots (warm when home, cool when out). At a glance the user sees
 * 'where are my zones relative to each other', 'which ones have someone
 * in them', and 'where exactly is each tracked person right now'.
 */
@Composable
private fun ZoneMap(
    zones: List<ResolvedZone>,
    trackers: List<MappableTracker>,
    height: androidx.compose.ui.unit.Dp = 200.dp,
) {
    // Bounding box across every plotted point: zone centres and tracker
    // positions both, so the frame contains everyone.
    val points = remember(zones, trackers) {
        buildList {
            zones.forEach { z ->
                val lat = z.latitude
                val lon = z.longitude
                if (lat != null && lon != null) add(lat to lon)
            }
            trackers.forEach { add(it.latitude to it.longitude) }
        }
    }
    val bounds = geoBounds(points) ?: return
    val metersPerLonDeg = metersPerLonDegree(bounds.midLat)
    // The map is decorative geometry; give it a single merged spoken
    // description and point the user at the zone list below (the real
    // accessible path).
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .semantics(mergeDescendants = true) {
                contentDescription = ZoneA11y.mapDescription(
                    zoneCount = zones.size,
                    trackerCount = trackers.size,
                )
            },
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(2.dp))
                .padding(16.dp),
        ) {
            val w = size.width
            val h = size.height
            // Faint cross-hair at the centre for visual grounding.
            drawLine(
                color = R1.Hairline,
                start = Offset(w * 0.5f, 0f),
                end = Offset(w * 0.5f, h),
                strokeWidth = 1f,
            )
            drawLine(
                color = R1.Hairline,
                start = Offset(0f, h * 0.5f),
                end = Offset(w, h * 0.5f),
                strokeWidth = 1f,
            )
            zones.forEach { zone ->
                val lat = zone.latitude ?: return@forEach
                val lon = zone.longitude ?: return@forEach
                val (xFrac, yFrac) = projectToCanvasFraction(lat, lon, bounds)
                val centre = Offset(xFrac * w, yFrac * h)
                // Translate radius to canvas units via the bounding-box span
                // (in metres) → canvas span (in pixels) ratio. Caps are
                // relative to canvas size so a tablet's larger viewport
                // doesn't render the same metric radii as visually smaller
                // circles than the R1's portrait display does.
                val radiusM = zone.radiusMeters ?: 100.0
                val canvasPerMeter =
                    w / (bounds.lonSpan * metersPerLonDeg).toFloat().coerceAtLeast(1f)
                val rMin = (w * 0.03f).coerceAtLeast(8f)
                val rMax = (w * 0.18f).coerceAtMost(96f)
                val r = (radiusM.toFloat() * canvasPerMeter).coerceIn(rMin, rMax)
                val occupied = zone.occupants.isNotEmpty()
                // Passive zones detect presence but never set a person's state,
                // so HA renders their radius in a muted colour. Mirror that: an
                // occupied passive zone is rare but still drawn warm, otherwise
                // passive reads as the faintest outline.
                val outline = when {
                    occupied -> R1.AccentWarm
                    zone.passive -> R1.InkMuted
                    else -> R1.Hairline
                }
                if (occupied) {
                    drawCircle(
                        color = R1.AccentWarm.copy(alpha = 0.24f),
                        radius = r,
                        center = centre,
                    )
                }
                drawCircle(
                    color = outline,
                    radius = r,
                    center = centre,
                    style = Stroke(width = 1.5f),
                )
                // Centre dot: the zone's exact position.
                drawCircle(
                    color = if (occupied) R1.AccentWarm else R1.InkSoft,
                    radius = 2.5f,
                    center = centre,
                )
            }
            // Tracked entities on top of the zones: a small filled dot with
            // a thin halo so it reads against a zone circle of either colour.
            trackers.forEach { t ->
                val (xFrac, yFrac) = projectToCanvasFraction(t.latitude, t.longitude, bounds)
                val centre = Offset(xFrac * w, yFrac * h)
                val dot = if (t.home) R1.AccentWarm else R1.AccentCool
                drawCircle(
                    color = R1.Bg,
                    radius = 5f,
                    center = centre,
                )
                drawCircle(
                    color = dot,
                    radius = 3.5f,
                    center = centre,
                )
            }
        }
    }
}

@Composable
private fun ZoneRow(zone: ResolvedZone) {
    val occupied = zone.occupants.isNotEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = R1.MinTarget)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(
                1.dp,
                if (occupied) R1.AccentWarm.copy(alpha = 0.3f) else R1.Hairline,
                R1.ShapeS,
            )
            // Merge the icon / name / count / occupant chips into one spoken
            // phrase; occupancy is read in words, not just by accent colour.
            .semantics(mergeDescendants = true) {
                contentDescription = ZoneA11y.zoneRowLabel(
                    name = zone.name,
                    occupants = zone.occupants,
                    radiusMeters = zone.radiusMeters,
                    isHome = zone.isHome,
                    passive = zone.passive,
                )
            }
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Lead with the zone's configured mdi glyph when we curate it,
            // otherwise the in-house Zone marker. An occupied zone tints warm so
            // the eye catches "someone's here" before reading the count.
            Icon(
                imageVector = R1Icons.forMdi(zone.icon) ?: R1Icons.forDomain("zone"),
                contentDescription = null,
                tint = if (occupied) R1.AccentWarm else R1.AccentNeutral,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(R1.space.s))
            Text(
                text = zone.name,
                style = responsiveType(R1.body),
                color = R1.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(R1.space.xs))
            // Occupancy badge: filled accent when one or more, muted when 0.
            Text(
                text = "${zone.occupants.size}",
                style = responsiveType(R1.labelMicro),
                color = if (occupied) R1.AccentWarm else R1.InkMuted,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = zone.entityId,
                style = responsiveType(R1.labelMicro),
                color = R1.InkSoft,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The home zone gets a small marker so the "home" special-case is
            // visible to sighted users, mirroring how HA highlights zone.home.
            if (zone.isHome) {
                Spacer(Modifier.width(R1.space.xs))
                Text(
                    text = "HOME",
                    style = responsiveType(R1.labelMicro),
                    color = R1.AccentWarm,
                )
            }
            // Passive zones are orientation-only geofences; flag them so an
            // empty passive zone doesn't read as a misconfigured active one.
            if (zone.passive) {
                Spacer(Modifier.width(R1.space.xs))
                Text(
                    text = "PASSIVE",
                    style = responsiveType(R1.labelMicro),
                    color = R1.InkMuted,
                )
            }
            zone.radiusMeters?.let { r ->
                Spacer(Modifier.width(R1.space.xs))
                Text(
                    text = formatRadius(r),
                    style = responsiveType(R1.labelMicro),
                    color = R1.AccentNeutral,
                )
            }
        }
        if (zone.occupants.isNotEmpty()) {
            Spacer(Modifier.size(R1.space.xs))
            Row(verticalAlignment = Alignment.Top) {
                // A person glyph fronts the occupant list so the line reads as
                // "who's here" at a glance.
                Icon(
                    imageVector = R1Icons.forDomain("person"),
                    contentDescription = null,
                    tint = R1.AccentWarm,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(R1.space.xs))
                Text(
                    text = zone.occupants.joinToString(" · "),
                    style = responsiveType(R1.body),
                    color = R1.AccentWarm,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun OutsideRow(names: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = R1.MinTarget)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .semantics(mergeDescendants = true) {
                contentDescription = ZoneA11y.outsideRowLabel(names)
            }
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "OUTSIDE",
                style = responsiveType(R1.labelMicro),
                color = R1.InkSoft,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${names.size}",
                style = responsiveType(R1.labelMicro),
                color = R1.StatusAmber,
            )
        }
        Spacer(Modifier.size(R1.space.xs))
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = R1Icons.forDomain("person"),
                contentDescription = null,
                tint = R1.InkSoft,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(R1.space.xs))
            Text(
                text = names.joinToString(" · "),
                style = responsiveType(R1.body),
                color = R1.InkSoft,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** "152m" / "1.2km": match the rest of the app's compact metric
 *  language. */
private fun formatRadius(meters: Double): String =
    if (meters >= 1000) "${"%.1f".format(java.util.Locale.US, meters / 1000.0)}km"
    else "${meters.toInt()}m"
