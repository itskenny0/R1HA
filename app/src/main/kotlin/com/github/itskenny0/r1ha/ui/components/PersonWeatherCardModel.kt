package com.github.itskenny0.r1ha.ui.components

import com.github.itskenny0.r1ha.core.theme.CardRenderModel
import java.util.Locale

/**
 * Pure mapping helpers for the read-only PERSON and WEATHER cards. Kept free of any
 * Compose / Android types so they can be unit-tested directly and reused by the card
 * body, the EntityCard accent map, and the favourites picker without dragging a
 * composition into a JVM test.
 *
 * PERSON entities report their state as a zone name: "home", "not_home", or a custom
 * zone label (e.g. "Work"). WEATHER entities report a condition word ("sunny",
 * "partlycloudy", "rainy", …) with the current temperature carried in attributes.
 */
object PersonWeatherCardModel {

    // ── PERSON ─────────────────────────────────────────────────────────────────────

    /**
     * Presentation word for a person's presence state. "home" → HOME, "not_home" /
     * "away" → AWAY, and any other value is treated as a custom zone name (Title-cased
     * so "work" reads as "Work"). Null / blank states fall back to "UNKNOWN".
     */
    fun personPresenceLabel(rawState: String?): String {
        val s = rawState?.trim()
        if (s.isNullOrEmpty()) return "UNKNOWN"
        return when (s.lowercase(Locale.US)) {
            "home" -> "HOME"
            "not_home", "away" -> "AWAY"
            "unknown", "unavailable" -> "UNKNOWN"
            // Custom zone — keep the zone's own casing but tidy underscores so
            // "secret_lab" reads as "Secret Lab" rather than shouting in all-caps.
            else -> s.replace('_', ' ')
                .split(' ')
                .filter { it.isNotEmpty() }
                .joinToString(" ") { word ->
                    word.replaceFirstChar { it.titlecase(Locale.US) }
                }
        }
    }

    /** True when the person's presence state reads as "at home". */
    fun personIsHome(rawState: String?): Boolean =
        rawState?.trim()?.equals("home", ignoreCase = true) == true

    /**
     * Accent for a person card. Home reads green ("present / safe"); any other zone
     * (including not_home) reads neutral so the deck doesn't flash a warning colour
     * just because someone stepped out.
     */
    fun personAccent(rawState: String?): CardRenderModel.AccentRole =
        if (personIsHome(rawState)) CardRenderModel.AccentRole.GREEN
        else CardRenderModel.AccentRole.NEUTRAL

    // ── WEATHER ────────────────────────────────────────────────────────────────────

    /**
     * A single text glyph for a weather condition. There is no per-condition icon set
     * in the design tokens, so we lean on widely-supported Unicode weather symbols that
     * render in the app's font: sun, cloud, rain, snow, storm, fog, wind. Unknown
     * conditions fall back to a neutral thermometer so the card never shows a blank.
     */
    fun weatherConditionGlyph(condition: String?): String = when (condition?.lowercase(Locale.US)) {
        "sunny", "clear" -> "☀"          // ☀
        "clear-night" -> "☽"             // ☽ (waxing crescent)
        "partlycloudy" -> "⛅"            // ⛅
        "cloudy" -> "☁"                  // ☁
        "fog" -> "☁"                     // ☁ (no distinct fog glyph; cloud reads close)
        "rainy" -> "☂"                   // ☂
        "pouring" -> "☔"                 // ☔
        "snowy", "snowy-rainy" -> "❄"    // ❄
        "hail" -> "❄"                    // ❄
        "lightning", "lightning-rainy" -> "⚡" // ⚡
        "windy", "windy-variant" -> "≈"  // ≈ (wind streaks)
        "exceptional" -> "⚠"             // ⚠
        else -> "☀"                      // ☀ default rather than blank
    }

    /**
     * Human-readable condition label: underscores / hyphens become spaces and the first
     * letter is capitalised ("partlycloudy" stays one word HA-side, so we leave it but
     * Title-case it; "snowy-rainy" → "Snowy rainy"). Blank → "UNKNOWN".
     */
    fun weatherConditionLabel(condition: String?): String {
        val c = condition?.trim()
        if (c.isNullOrEmpty()) return "UNKNOWN"
        return c.replace('_', ' ')
            .replace('-', ' ')
            .replaceFirstChar { it.titlecase(Locale.US) }
    }

    /**
     * Accent for a weather card, chosen by condition so the deck reads at a glance:
     * sunny/clear warm, wet conditions cool, storms amber-ish (mapped to WARM since the
     * AccentRole palette has no amber), everything else neutral.
     */
    fun weatherAccent(condition: String?): CardRenderModel.AccentRole = when (condition?.lowercase(Locale.US)) {
        "sunny", "clear", "clear-night" -> CardRenderModel.AccentRole.WARM
        "rainy", "pouring", "hail", "snowy", "snowy-rainy", "fog" -> CardRenderModel.AccentRole.COOL
        "lightning", "lightning-rainy", "exceptional" -> CardRenderModel.AccentRole.WARM
        "windy", "windy-variant" -> CardRenderModel.AccentRole.GREEN
        else -> CardRenderModel.AccentRole.NEUTRAL
    }
}
