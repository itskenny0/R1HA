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
