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
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Renderer for HA's `map` card. Plots each configured entity's
 * `latitude`/`longitude` attributes on an abstract Compose canvas (no real
 * map tiles, mirroring the Zones map): a bounding-box projection with a
 * centre cross-hair, a labelled dot per locatable entity. Entities without
 * coordinates are listed underneath so the user still sees them.
 */
@Composable
fun MapCard(
    card: LovelaceCard.Map,
    stateMap: EntityStates,
    modifier: Modifier = Modifier,
) {
    val located = card.entities.mapNotNull { row ->
        val state = safeEntityId(row.entityId)?.let { stateMap[it] }
        val lat = latLon(state, "latitude")
        val lon = latLon(state, "longitude")
        if (lat != null && lon != null) {
            MapPoint(
                label = resolveName(row.name, state, row.entityId),
                lat = lat,
                lon = lon,
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
                MapCanvas(located)
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
                                .background(R1.AccentWarm),
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
private fun MapCanvas(points: List<MapPoint>) {
    val lats = points.map { it.lat }
    val lons = points.map { it.lon }
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
            points.forEach { p ->
                // Normalise into [0.1 .. 0.9] with north = up.
                val xFrac = ((p.lon - lonMin) / lonSpan).toFloat() * 0.8f + 0.1f
                val yFrac = 1f - (((p.lat - latMin) / latSpan).toFloat() * 0.8f + 0.1f)
                val centre = Offset(xFrac * w, yFrac * h)
                drawCircle(color = R1.AccentWarm.copy(alpha = 0.24f), radius = 10f, center = centre)
                drawCircle(color = R1.AccentWarm, radius = 3.5f, center = centre)
            }
        }
    }
}

private data class MapPoint(val label: String, val lat: Double, val lon: Double)

private fun latLon(state: EntityState?, key: String): Double? {
    val prim = state?.attributesJson?.get(key) as? JsonPrimitive ?: return null
    return prim.doubleOrNull ?: prim.content.toDoubleOrNull()
}
