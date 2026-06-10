package com.github.itskenny0.r1ha.core.ha

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The per-entity options HA's frontend reads from the extended entity-registry
 * entry (`config/entity_registry/get`), narrowed to the fields the Lovelace
 * card-features need: favorite positions / tilt positions (cover, valve),
 * favorite colours (light), and the default code (lock, alarm panel).
 *
 * Older HA servers, or entities with no registry entry, return nothing for the
 * call; the cache degrades to an empty value so the dependent features still
 * render their built-in defaults rather than breaking.
 */
data class ExtEntityRegistryOptions(
    /** `options.<domain>.favorite_positions` (cover / valve), normalised 0..100. */
    val favoritePositions: List<Int> = emptyList(),
    /** `options.cover.favorite_tilt_positions`, normalised 0..100. */
    val favoriteTiltPositions: List<Int> = emptyList(),
    /** `options.light.favorite_colors` as raw colour payloads (rgb_color /
     *  color_temp_kelvin / hs_color / rgbw_color / rgbww_color objects). */
    val favoriteColors: List<JsonObject> = emptyList(),
    /** Whether the registry carried an explicit favorite-positions list (vs the
     *  key being absent). HA falls back to defaults only when absent. */
    val hasFavoritePositions: Boolean = false,
    /** As [hasFavoritePositions] for tilt. */
    val hasFavoriteTiltPositions: Boolean = false,
    /** Whether the registry carried an explicit favorite-colors list. */
    val hasFavoriteColors: Boolean = false,
    /** `options.lock.default_code` / `options.alarm_control_panel.default_code`.
     *  When set, the protected service can fire without prompting for a code. */
    val defaultCode: String? = null,
) {
    companion object {
        val EMPTY = ExtEntityRegistryOptions()

        /**
         * Parse the `config/entity_registry/get` payload into the narrowed
         * options. The payload nests under `options.<domain>`; the domain is
         * derived from the entity id. Returns [EMPTY] when the payload is absent
         * or lacks the expected shape.
         */
        fun fromPayload(domain: String, payload: JsonObject?): ExtEntityRegistryOptions {
            val options = payload?.get("options") as? JsonObject ?: return EMPTY
            val domainOpts = options[domain] as? JsonObject ?: return EMPTY

            val rawPositions = domainOpts["favorite_positions"] as? JsonArray
            val rawTilt = domainOpts["favorite_tilt_positions"] as? JsonArray
            val rawColors = domainOpts["favorite_colors"] as? JsonArray
            val defaultCode = (domainOpts["default_code"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content?.takeUnless { it.isBlank() }

            return ExtEntityRegistryOptions(
                favoritePositions = normalizeFavoritePositions(rawPositions),
                favoriteTiltPositions = normalizeFavoritePositions(rawTilt),
                favoriteColors = rawColors.orEmpty().mapNotNull { it as? JsonObject },
                hasFavoritePositions = rawPositions != null,
                hasFavoriteTiltPositions = rawTilt != null,
                hasFavoriteColors = rawColors != null,
                defaultCode = defaultCode,
            )
        }

        /**
         * HA's `normalizeFavoritePositions`: Number-coerce each entry, drop
         * non-numbers, clamp 0..100, dedupe while preserving order.
         */
        fun normalizeFavoritePositions(arr: JsonArray?): List<Int> {
            if (arr == null) return emptyList()
            val seen = LinkedHashSet<Int>()
            for (el in arr) {
                val prim = el as? JsonPrimitive ?: continue
                val n = prim.intOrNull ?: prim.doubleOrNull?.toInt() ?: continue
                seen.add(n.coerceIn(0, 100))
            }
            return seen.toList()
        }
    }
}
