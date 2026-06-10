package com.github.itskenny0.r1ha.feature.moreinfo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Small TTL cache + decoder for an entity's registry `options` blob, used by the
 * more-info favourites controls. Reads come through
 * [HaRepository.getEntityRegistryOptions] (`config/entity_registry/get`); the
 * decoded shape is the per-domain favourites HA stores under `options[<domain>]`.
 *
 * NOTE: a sibling batch (parity-j1-features) is expected to land a general
 * `EntityRegistryOptionsCache`. That cache does not exist at this batch's base,
 * so this is a deliberately narrow, non-colliding stand-in (named
 * MoreInfoRegistryOptions) scoped to the favourites the more-info sheet needs.
 * It should be consolidated into the shared cache once that batch merges.
 */
object MoreInfoRegistryOptions {

    private const val TTL_MILLIS = 60_000L

    private data class CacheEntry(val options: JsonObject?, val fetchedAtMillis: Long)

    private val cache = HashMap<String, CacheEntry>()

    /** Fetch (cached) the registry options for [entityId]. Best-effort: a failed
     *  WS call returns null and is NOT cached, so a transient disconnect doesn't
     *  pin an empty result for the whole TTL. */
    suspend fun fetch(haRepository: HaRepository, entityId: String, nowMillis: Long): JsonObject? {
        val cached = cache[entityId]
        if (cached != null && nowMillis - cached.fetchedAtMillis < TTL_MILLIS) {
            return cached.options
        }
        val result = haRepository.getEntityRegistryOptions(entityId)
        return result.fold(
            onSuccess = { opts ->
                cache[entityId] = CacheEntry(opts, nowMillis)
                opts
            },
            onFailure = { cached?.options },
        )
    }

    /** Drop the cached entry for [entityId] so the next read re-fetches (used
     *  after a successful favourites write). */
    fun invalidate(entityId: String) {
        cache.remove(entityId)
    }

    internal fun clearForTest() = cache.clear()
}

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
)

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
 * while the first fetch is in flight. [computedDefault] supplies the fallback
 * (cover/valve positions or light colours) so the holder stays domain-agnostic.
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
    val options = MoreInfoRegistryOptions.fetch(haRepository, entityId, System.currentTimeMillis())
    value = decodeRegistryFavorites(options, domain)
}
