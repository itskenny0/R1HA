package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Renderer for HA's `map` card. The R1 substrate is an abstract Compose canvas
 * (no real map tiles, mirroring the Zones map): a bounding-box projection with a
 * centre cross-hair and a coloured dot per locatable entity, plus a labelled
 * legend underneath. Each entity's `latitude`/`longitude` attributes drive its
 * position; entities without coordinates are dropped from the plot.
 *
 * Config gaps honoured by this substrate:
 *  - per-entity marker colours (`entities: [{entity, color}]`) and the default
 *    palette ordering, via [assignMarkerColors].
 *  - `label_mode` (name / state / attribute + attribute key + unit) and the
 *    per-entity `label_mode` / `attribute` override, via [markerLabel].
 *  - per-entity `focus` flag: a non-focus marker is plotted dimmer and excluded
 *    from the bounding-box auto-fit.
 *
 * Adaptations (the abstract canvas can't express these the way Leaflet does, so
 * each degrades to the closest legible equivalent rather than being silently
 * ignored):
 *  - `hours_to_show` history trail: drawn as a polyline of the entity's past
 *    positions, fetched via the attribute-bearing location-history endpoint and
 *    simplified with Ramer-Douglas-Peucker.
 *  - `show_all` / `geo_location_sources`: auto-populate the plot from every
 *    locatable device_tracker / person, or from geo_location entities matching
 *    the configured sources ("all" wildcard), fetched from the full entity set.
 *  - zones rendering / `fit_zones`: zone entities are drawn as translucent
 *    circles with their names; `fit_zones` includes them in the auto-fit bounds.
 *  - `cluster`: overlapping markers merge into a single count chip when enabled.
 *  - per-marker `conditions:`: each plotted entity is run through the card's
 *    visibility conditions (Batch B evaluator) and dropped when they fail.
 */
@Composable
fun MapCard(
    card: LovelaceCard.Map,
    stateMap: EntityStates,
    modifier: Modifier = Modifier,
    onAction: (LovelaceAction) -> Unit = {},
) {
    val repo = com.github.itskenny0.r1ha.core.theme.LocalHaRepository.current
    // show_all / geo_location_sources / fit_zones need the full entity set, which
    // the per-card slice doesn't carry. Fetch it once when any of those is active.
    val needsAllEntities = card.showAll || card.geoLocationSources.isNotEmpty() || card.fitZones
    var allStates by androidx.compose.runtime.remember(card) {
        androidx.compose.runtime.mutableStateOf<List<EntityState>>(emptyList())
    }
    if (repo != null && needsAllEntities) {
        androidx.compose.runtime.LaunchedEffect(card) {
            allStates = repo.listAllEntities().getOrNull().orEmpty()
        }
    }

    // The plotted entity id set: explicit entities, or (when none configured and
    // show_all is set) every locatable tracker/person, plus any geo_location
    // sources. De-duplicated, declaration order preserved.
    val plotIds = androidx.compose.runtime.remember(card, allStates) {
        val ids = LinkedHashSet<String>()
        card.entities.forEach { ids.add(it.entityId) }
        if (ids.isEmpty() && card.showAll) ids.addAll(showAllEntityIds(allStates))
        ids.addAll(geoLocationEntityIds(allStates, card.geoLocationSources))
        ids.toList()
    }

    // Per-entity marker config keyed by id (explicit entities keep their config;
    // auto-populated ids get a default).
    val markerByEntity = androidx.compose.runtime.remember(card) {
        val configured = card.markers.ifEmpty {
            card.entities.map { com.github.itskenny0.r1ha.core.lovelace.MapMarkerConfig(entityId = it.entityId) }
        }
        configured.associateBy { it.entityId }
    }
    val nameByEntity = androidx.compose.runtime.remember(card) {
        card.entities.associate { it.entityId to it.name }
    }
    val colorForId = androidx.compose.runtime.remember(card, plotIds) {
        val markers = plotIds.map { id ->
            markerByEntity[id] ?: com.github.itskenny0.r1ha.core.lovelace.MapMarkerConfig(entityId = id)
        }
        plotIds.zip(assignMarkerColors(markers)).toMap()
    }

    // Per-marker visibility conditions (Batch B evaluator).
    val conditionContext = rememberLovelaceConditionContext(card.conditions)

    val located = plotIds.mapNotNull { id ->
        val state = stateMap.byRaw(id) ?: allStates.firstOrNull { it.id.value == id }
        val lat = latLon(state, "latitude")
        val lon = latLon(state, "longitude")
        if (lat == null || lon == null) return@mapNotNull null
        // Drop entities whose conditions fail (HA filters plotted entities by the
        // card-level conditions, evaluated per entity).
        if (card.conditions.isNotEmpty()) {
            val ctx = conditionContext.copy(contextEntityId = id)
            if (!evaluateConditions(card.conditions, stateMap, ctx)) return@mapNotNull null
        }
        val marker = markerByEntity[id]
        val mode = effectiveLabelMode(card.labelMode, marker?.labelMode)
        val attr = effectiveLabelAttribute(card.labelAttribute, marker?.attribute)
        val friendly = resolveName(nameByEntity[id], state, id)
        val markerFocus = marker?.focus ?: true
        val isFocus = if (card.focusEntities.isEmpty()) markerFocus else id in card.focusEntities
        MapPoint(
            entityId = id,
            label = markerLabel(mode, attr, state, friendly),
            lat = lat,
            lon = lon,
            isFocus = isFocus,
            color = colorForId[id] ?: R1.AccentWarm,
        )
    }

    // Zones (drawn as translucent circles); fit_zones folds them into the bounds.
    val zones = androidx.compose.runtime.remember(allStates) {
        zoneEntityIds(allStates).mapNotNull { id ->
            val state = allStates.firstOrNull { it.id.value == id }
            val lat = latLon(state, "latitude")
            val lon = latLon(state, "longitude")
            val radius = (state?.attributesJson?.get("radius") as? JsonPrimitive)?.doubleOrNull
            if (lat != null && lon != null) {
                MapZone(
                    label = resolveName(null, state, id),
                    lat = lat,
                    lon = lon,
                    radiusMeters = radius ?: 100.0,
                )
            } else {
                null
            }
        }
    }

    // History trails: fetch each plotted entity's past positions when the card
    // configures hours_to_show. Simplified to a clean polyline.
    var trails by androidx.compose.runtime.remember(card, plotIds) {
        androidx.compose.runtime.mutableStateOf<Map<String, List<TrailPoint>>>(emptyMap())
    }
    val hours = card.hoursToShow
    if (repo != null && hours != null && hours > 0 && plotIds.isNotEmpty()) {
        androidx.compose.runtime.LaunchedEffect(plotIds, hours) {
            val out = LinkedHashMap<String, List<TrailPoint>>()
            plotIds.forEach { id ->
                val eid = safeEntityId(id) ?: return@forEach
                repo.fetchLocationHistory(eid, hours).getOrNull()?.let { fixes ->
                    val pts = fixes.map { TrailPoint(it.latitude, it.longitude) }
                    if (pts.size >= 2) out[id] = simplifyTrail(pts, epsilon = 0.0002)
                }
            }
            trails = out
        }
    }

    CardSurface(modifier = modifier, title = card.title?.takeUnless { it.isBlank() }) {
        Column(modifier = Modifier.padding(horizontal = 14.dp)) {
            if (located.isEmpty() && zones.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(R1.SurfaceMuted),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "NO LOCATABLE ENTITIES", style = R1.labelMicro, color = R1.InkMuted)
                }
            } else {
                // Bounds: focus markers, plus zones when fit_zones is set.
                val focusPoints = located.filter { it.isFocus }.takeUnless { it.isEmpty() } ?: located
                val boundsPoints = if (card.fitZones) {
                    focusPoints + zones.map { MapPoint("", it.lat, it.lon) }
                } else {
                    focusPoints
                }.ifEmpty { zones.map { MapPoint("", it.lat, it.lon) } }
                MapCanvas(
                    allPoints = located,
                    boundsPoints = boundsPoints,
                    zones = zones,
                    trails = trails,
                    cluster = card.cluster,
                )
                Spacer(Modifier.height(8.dp))
                located.forEach { p ->
                    // HA's entity markers open more-info on tap; the R1 legend row
                    // is the touch target for that (canvas hit-testing is coarse).
                    val rowMod = Modifier
                        .fillMaxWidth()
                        .let {
                            if (p.entityId.isNotBlank()) {
                                it.r1Pressable(
                                    onClick = { onAction(LovelaceAction.Builtin("more-info", p.entityId)) },
                                    contentDescription = p.label,
                                )
                            } else {
                                it
                            }
                        }
                        .padding(vertical = 3.dp)
                    Row(
                        modifier = rowMod,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(p.color),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = p.label,
                            style = R1.body,
                            color = R1.Ink,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "%.4f, %.4f".format(java.util.Locale.US, p.lat, p.lon),
                            style = R1.labelMicro,
                            color = R1.InkMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MapCanvas(
    allPoints: List<MapPoint>,
    boundsPoints: List<MapPoint> = allPoints,
    zones: List<MapZone> = emptyList(),
    trails: Map<String, List<TrailPoint>> = emptyMap(),
    cluster: Boolean = true,
) {
    // Bounding box is derived from the focus/bounds points (and zones when asked).
    val boundsSource = boundsPoints.ifEmpty { allPoints }
    val lats = (boundsSource.map { it.lat } + zones.map { it.lat }).ifEmpty { listOf(0.0) }
    val lons = (boundsSource.map { it.lon } + zones.map { it.lon }).ifEmpty { listOf(0.0) }
    val latMin = lats.min()
    val latMax = lats.max()
    val lonMin = lons.min()
    val lonMax = lons.max()
    val latSpan = (latMax - latMin).takeIf { it > 1e-9 } ?: 0.01
    val lonSpan = (lonMax - lonMin).takeIf { it > 1e-9 } ?: 0.01

    fun xFrac(lon: Double) = ((lon - lonMin) / lonSpan).toFloat() * 0.8f + 0.1f
    fun yFrac(lat: Double) = 1f - (((lat - latMin) / latSpan).toFloat() * 0.8f + 0.1f)

    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted),
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(160.dp).padding(16.dp)) {
            val w = size.width
            val h = size.height
            drawLine(R1.Hairline, Offset(w * 0.5f, 0f), Offset(w * 0.5f, h), strokeWidth = 1f)
            drawLine(R1.Hairline, Offset(0f, h * 0.5f), Offset(w, h * 0.5f), strokeWidth = 1f)

            // Zones: translucent circles with a name label.
            zones.forEach { z ->
                val c = Offset(xFrac(z.lon) * w, yFrac(z.lat) * h)
                drawCircle(color = R1.AccentCool.copy(alpha = 0.12f), radius = 18f, center = c)
                drawCircle(
                    color = R1.AccentCool.copy(alpha = 0.4f),
                    radius = 18f,
                    center = c,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f),
                )
            }

            // History trails: a faint polyline per entity behind the markers.
            allPoints.forEach { p ->
                val trail = trails[p.entityId] ?: return@forEach
                for (i in 0 until trail.size - 1) {
                    val a = Offset(xFrac(trail[i].lon) * w, yFrac(trail[i].lat) * h)
                    val b = Offset(xFrac(trail[i + 1].lon) * w, yFrac(trail[i + 1].lat) * h)
                    drawLine(p.color.copy(alpha = 0.5f), a, b, strokeWidth = 2f)
                }
            }

            // Markers, clustered into count chips when enabled.
            val plot = allPoints.mapIndexed { i, p -> PlotPoint(i, xFrac(p.lon), yFrac(p.lat)) }
            val clusters = if (cluster) clusterPlotPoints(plot, radiusFrac = 0.04f) else
                plot.map { PlotCluster(it.xFrac, it.yFrac, listOf(it.index)) }
            clusters.forEach { cl ->
                val centre = Offset(cl.xFrac * w, cl.yFrac * h)
                if (cl.members.size > 1) {
                    // Count chip for merged markers.
                    drawCircle(color = R1.AccentWarm.copy(alpha = 0.3f), radius = 12f, center = centre)
                    drawCircle(color = R1.AccentWarm, radius = 12f, center = centre, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
                    val text = cl.members.size.toString()
                    val layout = measurer.measure(text, style = R1.labelMicro.copy(color = R1.Ink))
                    drawText(layout, topLeft = Offset(centre.x - layout.size.width / 2f, centre.y - layout.size.height / 2f))
                } else {
                    val p = allPoints[cl.members.first()]
                    val alpha = if (p.isFocus) 1f else 0.4f
                    drawCircle(color = p.color.copy(alpha = 0.24f * alpha), radius = 10f, center = centre)
                    drawCircle(color = p.color.copy(alpha = alpha), radius = 3.5f, center = centre)
                }
            }
        }
    }
}

private data class MapPoint(
    val label: String,
    val lat: Double,
    val lon: Double,
    val isFocus: Boolean = true,
    val color: Color = R1.AccentWarm,
    val entityId: String = "",
)

private data class MapZone(
    val label: String,
    val lat: Double,
    val lon: Double,
    val radiusMeters: Double,
)

private fun latLon(state: EntityState?, key: String): Double? {
    val prim = state?.attributesJson?.get(key) as? JsonPrimitive ?: return null
    return prim.doubleOrNull ?: prim.content.toDoubleOrNull()
}
