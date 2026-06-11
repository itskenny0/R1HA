package com.github.itskenny0.r1ha.feature.moreinfo

import kotlin.math.roundToInt

/**
 * Pure favourites logic for the more-info sheet, mirroring HA's
 * `src/dialogs/more-info/favorites.ts`, `src/data/favorite_positions.ts`, and
 * `computeDefaultFavoriteColors` from `src/data/light.ts`.
 *
 * The HA frontend stores per-entity favourite positions / colours in the entity
 * registry under `options[<domain>]`. When the user has not customised them it
 * falls back to a computed default. R1HA reads the registry options through the
 * shared [com.github.itskenny0.r1ha.feature.dashboards.cards.EntityRegistryOptionsCache]
 * (decoded into [RegistryFavorites]); this file holds the unit-testable rules for
 * normalising stored values and computing the defaults, so the Compose layer can
 * stay thin.
 *
 * Kept Compose-free and free of Android types on purpose: the "what colours /
 * positions should show, and in what order" decision is exactly the part worth
 * pinning with JVM tests.
 */
object MoreInfoFavorites {

    /** HA `DEFAULT_COVER_FAVORITE_POSITIONS` / `DEFAULT_VALVE_FAVORITE_POSITIONS`. */
    val DEFAULT_POSITIONS: List<Int> = listOf(0, 25, 75, 100)

    /** HA `COLOR_TEMP_COUNT` — the number of evenly-spaced colour-temperature
     *  swatches the default favourites produce. */
    const val COLOR_TEMP_COUNT: Int = 4

    /**
     * HA `DEFAULT_COLORED_COLORS` (light.ts): the four fixed RGB swatches appended
     * to the default favourites whenever a bulb supports colour. Each entry is an
     * opaque 0xFFRRGGBB ARGB int so the swatch chips can render it directly.
     */
    val DEFAULT_COLORED_COLORS: List<Int> = listOf(
        argb(127, 172, 255), // blue  #7FACFF
        argb(215, 150, 255), // purple #D796FF
        argb(255, 158, 243), // pink  #FF9EF3
        argb(255, 110, 84),  // red   #FF6E54
    )

    /**
     * Normalise a stored favourite-position list, mirroring HA's
     * `normalizeFavoritePositions`: drop non-numeric entries, clamp each into
     * 0..100, and de-duplicate while preserving first-seen order. A null input
     * (option absent) yields an empty list so the caller can decide whether to
     * substitute the computed default.
     */
    fun normalizePositions(positions: List<Int>?): List<Int> {
        if (positions == null) return emptyList()
        val seen = LinkedHashSet<Int>()
        for (p in positions) {
            val clamped = p.coerceIn(0, 100)
            seen.add(clamped)
        }
        return seen.toList()
    }

    /**
     * Resolve the favourite positions to show for a cover / valve: the stored
     * (normalised) list when the user has customised it, otherwise the normalised
     * [DEFAULT_POSITIONS]. [supportsPosition] gates the whole feature — when the
     * entity has no settable position there are no position favourites at all.
     */
    fun resolvePositions(stored: List<Int>?, supportsPosition: Boolean): List<Int> {
        if (!supportsPosition) return emptyList()
        val normalizedStored = normalizePositions(stored)
        if (normalizedStored.isNotEmpty()) return normalizedStored
        return normalizePositions(DEFAULT_POSITIONS)
    }

    /**
     * Compute the default favourite colours for a light, mirroring HA's
     * `computeDefaultFavoriteColors`. Returns an ordered list of swatches:
     *  - [COLOR_TEMP_COUNT] evenly-spaced colour-temperature swatches when the
     *    bulb supports `color_temp` (across the bulb's own kelvin range), or the
     *    same count derived from the 2000..6500 K span when it only supports
     *    chromatic colour,
     *  - followed by the four [DEFAULT_COLORED_COLORS] when the bulb supports
     *    colour at all.
     *
     * A bulb that supports neither (brightness-only / on-off) yields an empty
     * list, so no colour favourites render.
     */
    fun computeDefaultFavoriteColors(
        supportsColorTemp: Boolean,
        supportsColor: Boolean,
        minColorTempK: Int?,
        maxColorTempK: Int?,
    ): List<FavoriteColor> {
        val out = ArrayList<FavoriteColor>()
        if (supportsColorTemp) {
            val min = minColorTempK ?: 2000
            val max = (maxColorTempK ?: 6500).let { if (it > min) it else min + 1 }
            val step = (max - min).toDouble() / (COLOR_TEMP_COUNT - 1)
            for (i in 0 until COLOR_TEMP_COUNT) {
                out.add(FavoriteColor.ColorTemp((min + step * i).roundToInt()))
            }
        } else if (supportsColor) {
            val min = 2000
            val max = 6500
            val step = (max - min).toDouble() / (COLOR_TEMP_COUNT - 1)
            for (i in 0 until COLOR_TEMP_COUNT) {
                out.add(FavoriteColor.ColorTemp((min + step * i).roundToInt()))
            }
        }
        if (supportsColor) {
            DEFAULT_COLORED_COLORS.forEach { out.add(FavoriteColor.Rgb(it)) }
        }
        return out
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    // ── Edit / reset / copy orchestration (pure) ─────────────────────────────

    /**
     * Add [position] to a favourite-position list, normalising the result (clamp
     * + de-dupe + first-seen order). Adding a duplicate is a no-op.
     */
    fun addPosition(positions: List<Int>, position: Int): List<Int> =
        normalizePositions(positions + position.coerceIn(0, 100))

    /** Remove [position] from a favourite-position list. */
    fun removePosition(positions: List<Int>, position: Int): List<Int> =
        positions.filter { it != position }

    /** Remove the colour swatch at [index] from a colour-favourites list. */
    fun removeColorAt(colors: List<FavoriteColor>, index: Int): List<FavoriteColor> =
        if (index in colors.indices) colors.filterIndexed { i, _ -> i != index } else colors

    /**
     * Whether a copy of one entity's favourites can target [candidateDomain]. HA
     * only lets you copy favourites between entities of the SAME domain (the
     * options block is keyed by domain) that also share the relevant capability:
     *  - light: the target must support the same colour axis (colour or
     *    colour-temperature) so the swatches are meaningful;
     *  - cover / valve: the target must support set-position.
     * [capabilityOk] carries that per-domain capability check the caller resolves
     * from the candidate's attributes.
     */
    fun canCopyTo(
        sourceDomain: String,
        candidateDomain: String,
        capabilityOk: Boolean,
    ): Boolean = sourceDomain == candidateDomain && capabilityOk

    /** Outcome of a multi-target copy: which entity ids succeeded and which
     *  failed, so the UI can show a partial-failure report. */
    data class CopyReport(
        val succeeded: List<String>,
        val failed: List<String>,
    ) {
        val allOk: Boolean get() = failed.isEmpty()
        val total: Int get() = succeeded.size + failed.size
    }

    /**
     * Aggregate per-target results (entity id -> success) into a [CopyReport],
     * preserving the input order so the report reads predictably.
     */
    fun summariseCopy(results: List<Pair<String, Boolean>>): CopyReport =
        CopyReport(
            succeeded = results.filter { it.second }.map { it.first },
            failed = results.filterNot { it.second }.map { it.first },
        )
}

/**
 * A single favourite light colour. HA stores favourites as one of several typed
 * shapes (`color_temp_kelvin`, `rgb_color`, `rgbw_color`, ...); the more-info
 * sheet only needs to render a swatch and fire the matching `light.turn_on`, so
 * we model the two the defaults produce: a colour-temperature kelvin and a plain
 * RGB swatch (packed opaque ARGB).
 */
sealed interface FavoriteColor {
    data class ColorTemp(val kelvin: Int) : FavoriteColor
    data class Rgb(val argb: Int) : FavoriteColor
}
