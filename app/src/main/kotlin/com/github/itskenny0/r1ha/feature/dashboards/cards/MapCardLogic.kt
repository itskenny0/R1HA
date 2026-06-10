package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.ui.graphics.Color
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.MapMarkerConfig
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * Pure decision logic for the map card: marker colour assignment, label text,
 * and trail simplification. No Compose / Android dependencies beyond
 * [androidx.compose.ui.graphics.Color] (a value type), so these are unit-tested
 * directly under plain JUnit.
 */

/**
 * The default marker palette, in the declaration order HA cycles through with
 * getColorByIndex. R1's accent set is small, so this is the closest legible
 * subset rather than HA's 54-colour wheel: enough distinct hues that a handful
 * of markers each read as a different colour. The renderer assigns a colour to
 * each marker that has no explicit `color:` by its position among the
 * palette-assigned markers (HA increments its colour index only for entities
 * without an explicit colour, which this mirrors).
 */
internal val MAP_MARKER_PALETTE: List<Color> = listOf(
    R1.AccentWarm,
    R1.AccentCool,
    R1.AccentGreen,
    R1.StatusAmber,
    R1.StatusRed,
    R1.AccentNeutral,
)

/**
 * Resolve the colour for each marker in declaration order. A marker with an
 * explicit `color:` (hex or theme-colour name) keeps it; a marker without one
 * is assigned the next palette colour, cycling. This mirrors HA's map card,
 * which only advances its palette index for entities that don't set `color`, so
 * explicit-colour markers don't "consume" a palette slot.
 *
 * [markers] is the per-entity config in config order; the returned list is
 * 1:1 with it.
 */
internal fun assignMarkerColors(markers: List<MapMarkerConfig>): List<Color> {
    if (markers.isEmpty()) return emptyList()
    var paletteIndex = 0
    return markers.map { marker ->
        val explicit = haColorAccent(marker.color)
        if (explicit != null) {
            explicit
        } else {
            val c = MAP_MARKER_PALETTE[paletteIndex % MAP_MARKER_PALETTE.size]
            paletteIndex++
            c
        }
    }
}

/**
 * Compute the label text a marker should show, honouring HA's `label_mode`:
 *
 *  - "name" (or null): the entity friendly name (the caller passes it in as
 *    [friendlyName], already resolved).
 *  - "state": the entity's raw state.
 *  - "attribute": the value of [attributeKey] from the entity's attributes,
 *    with [unit] appended when present.
 *
 * Falls back to [friendlyName] when the requested data is missing so a marker is
 * never blank. Pure; the renderer supplies [state] / [friendlyName].
 */
internal fun markerLabel(
    labelMode: String?,
    attributeKey: String?,
    state: EntityState?,
    friendlyName: String,
): String {
    return when (labelMode?.trim()?.lowercase()) {
        "state" -> state?.rawState?.takeUnless { it.isBlank() } ?: friendlyName
        "attribute" -> {
            val key = attributeKey?.takeUnless { it.isBlank() } ?: return friendlyName
            val raw = (state?.attributesJson?.get(key)
                as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (raw.isNullOrBlank()) {
                friendlyName
            } else {
                val unit = mapAttributeUnit(state)
                if (unit.isNullOrBlank()) raw else "$raw $unit"
            }
        }
        // "name" and anything unrecognised fall back to the friendly name.
        else -> friendlyName
    }
}

/**
 * The unit suffix HA appends to an attribute label. HA uses the entity's
 * `unit_of_measurement` attribute; we read the same so an attribute label like
 * a battery level reads "84 %".
 */
private fun mapAttributeUnit(state: EntityState?): String? =
    (state?.attributesJson?.get("unit_of_measurement")
        as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeUnless { it.isBlank() }

/**
 * Resolve the per-marker label mode, letting a marker's own `label_mode`
 * override the card-level one. Returns the effective mode for [markerLabel].
 */
internal fun effectiveLabelMode(cardLabelMode: String?, markerLabelMode: String?): String? =
    markerLabelMode ?: cardLabelMode

/**
 * Resolve the per-marker attribute key, letting a marker's own `attribute`
 * override the card-level one.
 */
internal fun effectiveLabelAttribute(cardAttribute: String?, markerAttribute: String?): String? =
    markerAttribute ?: cardAttribute

// ── cluster grouping ────────────────────────────────────────────────────────

/** A normalised plot position (fractions 0..1 within the canvas) carrying its
 *  source index, used by the clusterer to merge overlapping points. */
data class PlotPoint(val index: Int, val xFrac: Float, val yFrac: Float)

/** A cluster of plotted points: the centroid position and the member indices. */
data class PlotCluster(val xFrac: Float, val yFrac: Float, val members: List<Int>)

/**
 * Greedily merge plot points whose canvas distance is within [radiusFrac] into
 * clusters (HA's marker clustering). Points are processed in order; each
 * unclustered point seeds a cluster that absorbs every later point within the
 * radius of the seed. The cluster position is the centroid of its members. When
 * clustering is disabled the caller skips this and plots points individually.
 *
 * Pure (operates on normalised fractions), so the grouping is unit-tested without
 * a canvas. A single-member cluster renders as a normal marker; a multi-member
 * cluster renders as a count chip.
 */
fun clusterPlotPoints(points: List<PlotPoint>, radiusFrac: Float): List<PlotCluster> {
    val out = ArrayList<PlotCluster>()
    val taken = BooleanArray(points.size)
    for (i in points.indices) {
        if (taken[i]) continue
        taken[i] = true
        val members = ArrayList<Int>()
        members.add(points[i].index)
        var sx = points[i].xFrac
        var sy = points[i].yFrac
        for (j in i + 1 until points.size) {
            if (taken[j]) continue
            val dx = points[j].xFrac - points[i].xFrac
            val dy = points[j].yFrac - points[i].yFrac
            if (dx * dx + dy * dy <= radiusFrac * radiusFrac) {
                taken[j] = true
                members.add(points[j].index)
                sx += points[j].xFrac
                sy += points[j].yFrac
            }
        }
        out.add(PlotCluster(sx / members.size, sy / members.size, members))
    }
    return out
}

// ── trail simplification ────────────────────────────────────────────────────

/** A latitude/longitude trail point for simplification (renderer-agnostic). */
data class TrailPoint(val lat: Double, val lon: Double)

/**
 * Simplify a GPS trail with the Ramer-Douglas-Peucker algorithm so a long
 * location history draws as a clean polyline without thousands of nearly-colinear
 * vertices. [epsilon] is the max perpendicular deviation (in degrees) a point may
 * have from the simplified segment before it is kept. A trail of two or fewer
 * points is returned unchanged.
 *
 * Pure + deterministic, so the simplification is unit-tested directly.
 */
fun simplifyTrail(points: List<TrailPoint>, epsilon: Double): List<TrailPoint> {
    if (points.size <= 2) return points
    var maxDist = 0.0
    var index = 0
    val end = points.size - 1
    for (i in 1 until end) {
        val d = perpendicularDistance(points[i], points[0], points[end])
        if (d > maxDist) {
            maxDist = d
            index = i
        }
    }
    return if (maxDist > epsilon) {
        val left = simplifyTrail(points.subList(0, index + 1), epsilon)
        val right = simplifyTrail(points.subList(index, points.size), epsilon)
        left.dropLast(1) + right
    } else {
        listOf(points[0], points[end])
    }
}

/** Perpendicular distance of [p] from the line through [a] and [b] (degrees). */
internal fun perpendicularDistance(p: TrailPoint, a: TrailPoint, b: TrailPoint): Double {
    val dx = b.lon - a.lon
    val dy = b.lat - a.lat
    val denom = kotlin.math.sqrt(dx * dx + dy * dy)
    if (denom < 1e-12) {
        val ex = p.lon - a.lon
        val ey = p.lat - a.lat
        return kotlin.math.sqrt(ex * ex + ey * ey)
    }
    val num = kotlin.math.abs(dy * p.lon - dx * p.lat + b.lon * a.lat - b.lat * a.lon)
    return num / denom
}
