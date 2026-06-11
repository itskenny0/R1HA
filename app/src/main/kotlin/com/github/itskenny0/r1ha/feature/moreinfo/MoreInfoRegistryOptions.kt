package com.github.itskenny0.r1ha.feature.moreinfo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.github.itskenny0.r1ha.core.ha.ExtEntityRegistryOptions
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.feature.dashboards.cards.EntityRegistryOptionsCache
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Decoder + Compose holder for an entity's registry favourites, used by the
 * more-info sheet's favourites controls.
 *
 * Caching is delegated to the shared [EntityRegistryOptionsCache] (the J1
 * card-features cache over `config/entity_registry/get`); this file no longer
 * carries its own TTL cache. It keeps only the pure decode from the registry
 * `options` blob into the [RegistryFavorites] shape (positions / tilt positions
 * / typed colour swatches) the sheet renders, plus the holder that resolves it.
 */

/**
 * The favourites the more-info sheet reads off an entity's registry options.
 * Every field defaults empty so an entity with no stored favourites yields a
 * blank holder and the controls fall back to computed defaults.
 */
data class RegistryFavorites(
    val positions: List<Int> = emptyList(),
    val tiltPositions: List<Int> = emptyList(),
    /** Light favourites as opaque ARGB swatches (rgb / rgbw / rgbww flattened to
     *  RGB) plus colour-temperature kelvin entries, decoded in stored order. */
    val colors: List<FavoriteColor> = emptyList(),
) {
    companion object {
        /**
         * Adapt the shared cache's [ExtEntityRegistryOptions] (which carries the
         * raw favourite-colour objects) into the more-info [RegistryFavorites]
         * shape with typed colour swatches.
         */
        fun from(ext: ExtEntityRegistryOptions): RegistryFavorites = RegistryFavorites(
            positions = ext.favoritePositions,
            tiltPositions = ext.favoriteTiltPositions,
            colors = ext.favoriteColors.mapNotNull { decodeFavoriteColor(it) },
        )
    }
}

/**
 * Decode the per-domain favourites out of a registry `options` object for
 * [domain]. Pure so the codec is unit-testable. Mirrors the HA option keys:
 * `favorite_positions`, `favorite_tilt_positions`, and `favorite_colors` (a list
 * of single-key colour maps: `color_temp_kelvin` / `rgb_color` / `rgbw_color` /
 * `rgbww_color` / `hs_color`).
 */
fun decodeRegistryFavorites(options: JsonObject?, domain: String): RegistryFavorites {
    val domainOpts = options?.get(domain) as? JsonObject ?: return RegistryFavorites()
    val positions = intList(domainOpts["favorite_positions"])
    val tilt = intList(domainOpts["favorite_tilt_positions"])
    val colors = (domainOpts["favorite_colors"] as? JsonArray)
        ?.mapNotNull { decodeFavoriteColor(it as? JsonObject) }
        .orEmpty()
    return RegistryFavorites(positions = positions, tiltPositions = tilt, colors = colors)
}

/**
 * Encode favourite positions into the per-domain options object HA persists
 * (`{ favorite_positions: [...] }`). Pure so the codec round-trips under test.
 * Used by the more-info favourites editor to write back via
 * [HaRepository.updateEntityRegistryOptions].
 */
fun encodeFavoritePositions(positions: List<Int>): JsonObject =
    kotlinx.serialization.json.buildJsonObject {
        put("favorite_positions", kotlinx.serialization.json.buildJsonArray {
            positions.forEach { add(JsonPrimitive(it)) }
        })
    }

/**
 * Encode favourite light colours into the per-domain options object
 * (`{ favorite_colors: [ {color_temp_kelvin|rgb_color}, ... ] }`). A ColorTemp
 * favourite encodes as `{color_temp_kelvin: K}`; an Rgb favourite as
 * `{rgb_color: [r,g,b]}` (the swatch ARGB unpacked back to a triple).
 */
fun encodeFavoriteColors(colors: List<FavoriteColor>): JsonObject =
    kotlinx.serialization.json.buildJsonObject {
        put("favorite_colors", kotlinx.serialization.json.buildJsonArray {
            colors.forEach { fav ->
                add(
                    kotlinx.serialization.json.buildJsonObject {
                        when (fav) {
                            is FavoriteColor.ColorTemp ->
                                put("color_temp_kelvin", JsonPrimitive(fav.kelvin))
                            is FavoriteColor.Rgb -> put(
                                "rgb_color",
                                kotlinx.serialization.json.buildJsonArray {
                                    add(JsonPrimitive((fav.argb shr 16) and 0xFF))
                                    add(JsonPrimitive((fav.argb shr 8) and 0xFF))
                                    add(JsonPrimitive(fav.argb and 0xFF))
                                },
                            )
                        }
                    },
                )
            }
        })
    }

private fun intList(el: kotlinx.serialization.json.JsonElement?): List<Int> =
    (el as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() }
        .orEmpty()

private fun decodeFavoriteColor(obj: JsonObject?): FavoriteColor? {
    if (obj == null || obj.isEmpty()) return null
    obj["color_temp_kelvin"]?.let { k ->
        (k as? JsonPrimitive)?.content?.toIntOrNull()?.let { return FavoriteColor.ColorTemp(it) }
    }
    // rgb / rgbw / rgbww all start with an [r,g,b] triple; flatten to an opaque
    // RGB swatch (the white channels only affect the bulb, not the swatch fill).
    for (key in listOf("rgb_color", "rgbw_color", "rgbww_color")) {
        val arr = obj[key] as? JsonArray ?: continue
        val rgb = arr.take(3).mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
        if (rgb.size == 3) {
            val argb = (0xFF shl 24) or
                (rgb[0].coerceIn(0, 255) shl 16) or
                (rgb[1].coerceIn(0, 255) shl 8) or
                rgb[2].coerceIn(0, 255)
            return FavoriteColor.Rgb(argb)
        }
    }
    return null
}

/**
 * Compose holder that resolves an entity's [RegistryFavorites] for [domain],
 * substituting the computed defaults when the user has stored none. Returns null
 * while the first fetch is in flight. Reads through the shared
 * [EntityRegistryOptionsCache] so a card-feature and the more-info sheet share
 * one fetch per entity per TTL window.
 */
@Composable
fun rememberRegistryFavorites(
    haRepository: HaRepository,
    entityId: String,
    domain: String,
    enabled: Boolean,
): State<RegistryFavorites?> = produceState<RegistryFavorites?>(
    initialValue = null,
    key1 = entityId,
    key2 = enabled,
) {
    if (!enabled) {
        value = null
        return@produceState
    }
    val ext = EntityRegistryOptionsCache.get(haRepository, entityId, System.currentTimeMillis())
    value = RegistryFavorites.from(ext)
}
