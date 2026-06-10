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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
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
 *  - `hours_to_show` history trail: HA draws a polyline of past positions per
 *    entity. R1HA's history endpoint is fetched with `no_attributes`, so past
 *    lat/lon are not available without a new attribute-bearing history fetch.
 *    The trail is therefore not drawn; only the current position is plotted.
 *  - `geo_location_sources` / `show_all`: the canvas plots the explicitly
 *    listed entities only; auto-discovered geo-location entities and the
 *    "every entity with coordinates" mode are not enumerated here.
 *  - zones rendering / `fit_zones` / `cluster`: with no tile layer there is no
 *    zone-circle or marker-cluster layer to toggle; markers are always plotted
 *    individually and zones are not drawn as circles.
 */
@Composable
fun MapCard(
    card: LovelaceCard.Map,
    stateMap: EntityStates,
    modifier: Modifier = Modifier,
) {
    // Marker config is 1:1 with card.entities in declaration order; pre-resolve
    // each marker's palette/explicit colour once so the legend and the plot
    // agree. When markers is empty (every row was a bare string the parser
    // dropped) fall back to a default config per entity row.
    val markers = card.markers.ifEmpty {
        card.entities.map { com.github.itskenny0.r1ha.core.lovelace.MapMarkerConfig(entityId = it.entityId) }
    }
    val colors = assignMarkerColors(markers)

    val located = card.entities.mapIndexedNotNull { idx, row ->
        val state = safeEntityId(row.entityId)?.let { stateMap[it] }
        val lat = latLon(state, "latitude")
        val lon = latLon(state, "longitude")
        if (lat != null && lon != null) {
            val marker = markers.getOrNull(idx)
            val mode = effectiveLabelMode(card.labelMode, marker?.labelMode)
            val attr = effectiveLabelAttribute(card.labelAttribute, marker?.attribute)
            val friendly = resolveName(row.name, state, row.entityId)
            val label = markerLabel(mode, attr, state, friendly)
            // A marker is focus when either no focus list narrows the card and
            // the marker's own focus flag is true, or it is in the focus list.
            val markerFocus = marker?.focus ?: true
            val isFocus = if (card.focusEntities.isEmpty()) markerFocus else row.entityId in card.focusEntities
            MapPoint(
                label = label,
                lat = lat,
                lon = lon,
                isFocus = isFocus,
                color = colors.getOrElse(idx) { R1.AccentWarm },
            )
        } else {
            null
        }
    }

    CardSurface(modifier = modifier, title = card.title?.takeUnless { it.isBlank() }) {
        Column(modifier = Modifier.padding(horizontal = 14.dp)) {
            if (located.isEmpty()) {
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
                // Use only focus-flagged entities for the bounding box so
                // far-away non-focus entities don't zoom the viewport out.
                val focusPoints = located.filter { it.isFocus }.takeUnless { it.isEmpty() } ?: located
                MapCanvas(allPoints = located, boundsPoints = focusPoints)
                Spacer(Modifier.height(8.dp))
                located.forEach { p ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
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
private fun MapCanvas(allPoints: List<MapPoint>, boundsPoints: List<MapPoint> = allPoints) {
    // Bounding box is derived from the focus/bounds points only.
    val lats = boundsPoints.map { it.lat }
    val lons = boundsPoints.map { it.lon }
    val latMin = lats.min()
    val latMax = lats.max()
    val lonMin = lons.min()
    val lonMax = lons.max()
    val latSpan = (latMax - latMin).takeIf { it > 1e-9 } ?: 0.01
    val lonSpan = (lonMax - lonMin).takeIf { it > 1e-9 } ?: 0.01
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
            // All points are plotted; the projection is driven by boundsPoints.
            allPoints.forEach { p ->
                // Normalise into [0.1 .. 0.9] with north = up.
                val xFrac = ((p.lon - lonMin) / lonSpan).toFloat() * 0.8f + 0.1f
                val yFrac = 1f - (((p.lat - latMin) / latSpan).toFloat() * 0.8f + 0.1f)
                val centre = Offset(xFrac * w, yFrac * h)
                // Non-focus entities are plotted dimmer so they read as context, not primary.
                val alpha = if (p.isFocus) 1f else 0.4f
                drawCircle(color = p.color.copy(alpha = 0.24f * alpha), radius = 10f, center = centre)
                drawCircle(color = p.color.copy(alpha = alpha), radius = 3.5f, center = centre)
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
)

private fun latLon(state: EntityState?, key: String): Double? {
    val prim = state?.attributesJson?.get(key) as? JsonPrimitive ?: return null
    return prim.doubleOrNull ?: prim.content.toDoubleOrNull()
}
