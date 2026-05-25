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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.AutoRefresh
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Zones surface — a list-by-zone view of who's currently where,
 * plus a small abstract map at the top showing the relative
 * geographic layout of every zone (and their occupancy).
 *
 * The map is a Compose Canvas — no tiles, no actual map data; it
 * draws each zone as a circle sized by its radius_m attribute and
 * positioned by its lat/lon, normalised to fit inside the canvas
 * with a 10 % margin. Occupied zones get a filled accent; empty
 * zones a hairline outline. This is much less than a real map
 * but enough to communicate the geographic relationship between
 * zones at a glance.
 *
 * Below the map: a list of zones, each carrying its occupant
 * names + lat/lon for orientation. Outside (`not_home`) persons
 * collect under a final OUTSIDE section.
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
    // 60s auto-refresh — persons move slowly; tighter would waste API.
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
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .r1Pressable(onClick = { vm.refresh() })
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (ui.loading) "…" else "REFRESH",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                }
            },
        )
        com.github.itskenny0.r1ha.ui.layout.AdaptiveContent(modifier = Modifier.weight(1f)) {
        when {
            ui.loading && ui.zones.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
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
                    style = R1.body,
                    color = R1.StatusRed,
                )
            }
            ui.zones.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No zones defined. Settings → Areas & Zones in HA's web UI.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp, vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Map preview — only when at least two zones carry
                // lat/lon so there's something meaningful to draw.
                val mappable = ui.zones.filter { it.latitude != null && it.longitude != null }
                if (mappable.size >= 2) {
                    item("__map__") {
                        ZoneMap(zones = mappable)
                    }
                }
                items(items = ui.zones, key = { it.entityId }) { zone ->
                    ZoneRow(zone)
                }
                if (ui.outside.isNotEmpty()) {
                    item("__outside__") {
                        OutsideRow(names = ui.outside)
                    }
                }
            }
        }
        } // AdaptiveContent
    }
}

/**
 * Abstract map of every zone — Compose Canvas; not a real geo map.
 * Each zone is a circle sized by its `radius_m` attribute,
 * positioned in [0..1] coordinate space using the bounding box of
 * every zone's lat/lon, then projected onto the canvas with a 10%
 * margin so the outermost circles aren't clipped at the edge.
 *
 * Filled when occupied, hairline-outlined when empty — at a glance
 * the user sees both 'where are my zones relative to each other'
 * and 'which ones have someone in them right now'.
 */
@Composable
private fun ZoneMap(zones: List<ZonesViewModel.Zone>) {
    // Bounding box across every mappable zone. We pad by 10% of each
    // axis span so a zone at the extreme corner has room around it
    // for its radius circle.
    val lats = zones.mapNotNull { it.latitude }
    val lons = zones.mapNotNull { it.longitude }
    if (lats.isEmpty() || lons.isEmpty()) return
    val latMin = lats.min()
    val latMax = lats.max()
    val lonMin = lons.min()
    val lonMax = lons.max()
    val latSpan = (latMax - latMin).takeIf { it > 1e-9 } ?: 0.01
    val lonSpan = (lonMax - lonMin).takeIf { it > 1e-9 } ?: 0.01
    // Approximate metres per degree at the bounding box's mean lat —
    // good enough for the abstract map; we never claim it's accurate
    // for navigation.
    val midLat = (latMin + latMax) / 2.0
    val metersPerLatDeg = 111_320.0
    val metersPerLonDeg = 111_320.0 * kotlin.math.cos(Math.toRadians(midLat))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS),
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
                // Normalise lat/lon to [0.1 .. 0.9] of the canvas span
                // so circles near the edge have margin. Y is inverted
                // (north = up).
                val xFrac = ((lon - lonMin) / lonSpan).toFloat() * 0.8f + 0.1f
                val yFrac = 1f - (((lat - latMin) / latSpan).toFloat() * 0.8f + 0.1f)
                val centre = Offset(xFrac * w, yFrac * h)
                // Translate radius_m to canvas units via the bounding-
                // box span (in metres) → canvas span (in pixels) ratio.
                // Caps are relative to canvas size so a tablet's larger
                // viewport doesn't render the same metric radii as visually
                // smaller circles than the R1's portrait display does. The
                // previous absolute (8f, 48f) was tuned for the R1's 240px
                // canvas; on a 720px tablet that made every zone read as a
                // sub-thumbnail dot.
                val radiusM = zone.radiusMeters ?: 100.0
                val canvasPerMeter = w / (lonSpan * metersPerLonDeg).toFloat().coerceAtLeast(1f)
                val rMin = (w * 0.03f).coerceAtLeast(8f)
                val rMax = (w * 0.18f).coerceAtMost(96f)
                val r = (radiusM.toFloat() * canvasPerMeter).coerceIn(rMin, rMax)
                val occupied = zone.occupants.isNotEmpty()
                if (occupied) {
                    drawCircle(
                        color = R1.AccentWarm.copy(alpha = 0.24f),
                        radius = r,
                        center = centre,
                    )
                }
                drawCircle(
                    color = if (occupied) R1.AccentWarm else R1.Hairline,
                    radius = r,
                    center = centre,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
                )
                // Centre dot — labels HA's "this is the zone's exact
                // position" rather than its radius.
                drawCircle(
                    color = if (occupied) R1.AccentWarm else R1.InkSoft,
                    radius = 2.5f,
                    center = centre,
                )
            }
        }
    }
}

@Composable
private fun ZoneRow(zone: ZonesViewModel.Zone) {
    val occupied = zone.occupants.isNotEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(
                1.dp,
                if (occupied) R1.AccentWarm.copy(alpha = 0.3f) else R1.Hairline,
                R1.ShapeS,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = zone.name,
                style = R1.body,
                color = R1.Ink,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            // Occupancy badge — filled accent when ≥1, muted when 0.
            Text(
                text = "${zone.occupants.size}",
                style = R1.labelMicro,
                color = if (occupied) R1.AccentWarm else R1.InkMuted,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = zone.entityId,
                style = R1.labelMicro,
                color = R1.InkSoft,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            zone.radiusMeters?.let { r ->
                Spacer(Modifier.width(6.dp))
                Text(
                    text = formatRadius(r),
                    style = R1.labelMicro,
                    color = R1.AccentNeutral,
                )
            }
        }
        if (zone.occupants.isNotEmpty()) {
            Spacer(Modifier.size(4.dp))
            Text(
                text = zone.occupants.joinToString(" · "),
                style = R1.body,
                color = R1.AccentWarm,
                maxLines = 3,
            )
        }
    }
}

@Composable
private fun OutsideRow(names: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "OUTSIDE",
                style = R1.labelMicro,
                color = R1.InkSoft,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${names.size}",
                style = R1.labelMicro,
                color = R1.StatusAmber,
            )
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = names.joinToString(" · "),
            style = R1.body,
            color = R1.InkSoft,
            maxLines = 4,
        )
    }
}

/** "152m" / "1.2km" — match the rest of the app's compact metric
 *  language. */
private fun formatRadius(meters: Double): String =
    if (meters >= 1000) "${"%.1f".format(meters / 1000.0)}km"
    else "${meters.toInt()}m"
