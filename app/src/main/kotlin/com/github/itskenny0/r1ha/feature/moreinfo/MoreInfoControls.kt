package com.github.itskenny0.r1ha.feature.moreinfo

import com.github.itskenny0.r1ha.core.util.optionLabel

/**
 * Pure decision logic for the primary domain controls on the more-info sheet,
 * mirroring the gating HA's `more-info-*` controls and the `ha-state-control-*`
 * components apply. Compose stays thin and asks these helpers "should this
 * control show / be enabled, and what should it say".
 *
 * The cross-cutting rule (gap 11): every interactive control disables when the
 * entity is unavailable. [controlEnabled] is the single seam the Compose layer
 * routes through so the gate can't be forgotten per-control.
 */
object MoreInfoControls {

    /** HA's `LightEntityFeature` / `LightColorMode` strings the sheet keys off. */
    const val COLOR_MODE_WHITE = "white"
    const val COLOR_MODE_RGBW = "rgbw"
    const val COLOR_MODE_RGBWW = "rgbww"

    /** HA `LockEntityFeature.OPEN`. */
    const val LOCK_FEATURE_OPEN = 1

    /**
     * Whether an interactive control should be enabled. False for an unavailable
     * entity (HA renders the control disabled, not hidden, so the user still sees
     * the affordance grey). `unknown` state is still actionable (you can turn a
     * just-restarted light on), only `unavailable` locks the controls out.
     */
    fun controlEnabled(rawState: String?, isAvailable: Boolean): Boolean =
        isAvailable && !rawState.equals("unavailable", ignoreCase = true)

    /**
     * Friendly label for a humidifier's `action` attribute
     * (humidifying / drying / idle / off), mirroring HA's
     * more-info-humidifier action chip. Null when the humidifier reports no
     * action so the caller renders nothing.
     */
    fun humidifierActionLabel(action: String?): String? {
        val a = action?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        return optionLabel(a)
    }

    /** True when a light advertises the dedicated WHITE colour mode, so the sheet
     *  offers a "white" button that fires `light.turn_on {white: <brightness>}`.
     *  Mirrors `lightSupportsColorMode(LightColorMode.WHITE)`. */
    fun lightSupportsWhite(colorModes: List<String>): Boolean =
        colorModes.any { it.equals(COLOR_MODE_WHITE, ignoreCase = true) }

    /** True when a light advertises an RGBW / RGBWW colour mode, so the sheet
     *  offers a white-channel slider alongside the colour wheel. */
    fun lightSupportsWhiteChannel(colorModes: List<String>): Boolean =
        colorModes.any {
            it.equals(COLOR_MODE_RGBW, ignoreCase = true) ||
                it.equals(COLOR_MODE_RGBWW, ignoreCase = true)
        }

    fun lightSupportsRgbww(colorModes: List<String>): Boolean =
        colorModes.any { it.equals(COLOR_MODE_RGBWW, ignoreCase = true) }

    fun lightSupportsRgbw(colorModes: List<String>): Boolean =
        colorModes.any { it.equals(COLOR_MODE_RGBW, ignoreCase = true) }

    /** Convert a 0..100 percent white-channel value to HA's 0..255 byte (matching
     *  `Math.min(255, Math.round(value * 255 / 100))`). */
    fun whiteChannelByte(percent: Int): Int =
        ((percent.coerceIn(0, 100) * 255 + 50) / 100).coerceIn(0, 255)

    /**
     * Build the `rgbw_color` array (`[r,g,b,w]`) for an rgbw white-channel slider
     * change, preserving the current rgb part. Mirrors HA's `_wvSliderChanged`
     * "wv" branch: take the bulb's current rgb (or [0,0,0] when none), keep r/g/b
     * and set the white channel to [whitePercent] scaled to 0..255.
     */
    fun rgbwColorForWhite(currentRgb: List<Int>?, whitePercent: Int): List<Int> {
        val rgb = (currentRgb ?: emptyList()).take(3)
        val r = rgb.getOrElse(0) { 0 }
        val g = rgb.getOrElse(1) { 0 }
        val b = rgb.getOrElse(2) { 0 }
        return listOf(r, g, b, whiteChannelByte(whitePercent))
    }

    /** RGBWW slider channels: cold-white is index 3, warm-white is index 4. */
    enum class RgbwwChannel { COLD, WARM }

    /**
     * Build the `rgbww_color` array (`[r,g,b,cw,ww]`) for an rgbww white-channel
     * slider change, preserving the rgb part AND the other white channel. Mirrors
     * HA's `_wvSliderChanged` rgbww branch: start from the current rgbww (padded to
     * five entries), then overwrite only the changed cold/warm-white channel.
     */
    fun rgbwwColorForWhite(
        currentRgbww: List<Int>?,
        channel: RgbwwChannel,
        whitePercent: Int,
    ): List<Int> {
        val base = (currentRgbww ?: emptyList()).toMutableList()
        while (base.size < 5) base.add(0)
        val idx = if (channel == RgbwwChannel.COLD) 3 else 4
        base[idx] = whiteChannelByte(whitePercent)
        return base.take(5)
    }

    /** Current white-channel percent (0..100) for a slider, read from the matching
     *  rgbw/rgbww attribute index. Null = no value yet (slider defaults to 0). */
    fun whiteChannelPercent(channelByte: Int?): Int? =
        channelByte?.let { ((it.coerceIn(0, 255) * 100 + 127) / 255).coerceIn(0, 100) }

    /**
     * Scale an rgb triple by a 0..100 color-brightness percent, mirroring HA's
     * `_adjustColorBrightness` (black normalises to white first, then each channel
     * scales by value/255). Returns the adjusted `[r,g,b]`.
     */
    fun adjustColorBrightness(currentRgb: List<Int>?, brightnessPercent: Int): List<Int> {
        var r = currentRgb?.getOrNull(0) ?: 255
        var g = currentRgb?.getOrNull(1) ?: 255
        var b = currentRgb?.getOrNull(2) ?: 255
        if (r == 0 && g == 0 && b == 0) {
            r = 255; g = 255; b = 255
        }
        val value = (brightnessPercent.coerceIn(0, 100) * 255 + 50) / 100
        if (value != 255) {
            val ratio = value / 255.0
            r = minOf(255, Math.round(r * ratio).toInt())
            g = minOf(255, Math.round(g * ratio).toInt())
            b = minOf(255, Math.round(b * ratio).toInt())
        }
        return listOf(r, g, b)
    }

    /**
     * True when a lock advertises the OPEN feature (unlatch the door), so the
     * sheet offers an "open door" button. [supportedFeatures] is the raw
     * `supported_features` bitmask; a value of 0 (integration omitted it) is
     * treated as "no open support" because unlatching is a deliberate,
     * higher-risk action and we don't forgive its omission the way we do for
     * read-only chips.
     */
    fun lockSupportsOpen(supportedFeatures: Int): Boolean =
        (supportedFeatures and LOCK_FEATURE_OPEN) != 0

    /**
     * Whether the lock-open keypad can be skipped: HA skips the code prompt when a
     * `default_code` registry option is configured (the integration fills it in).
     * Returns the code to send when skippable, else null (prompt / no code).
     */
    fun lockDefaultCode(defaultCode: String?): String? =
        defaultCode?.takeIf { it.isNotBlank() }

    /**
     * True when a fan has only on/off (no speed and no preset): the primary
     * control collapses to a power toggle. Mirrors HA showing just the on/off
     * button for a SET_SPEED-less fan with no presets. When it has a speed or a
     * preset the caller renders those plus a power button beside them.
     */
    fun fanIsToggleOnly(supportsSetSpeed: Boolean, hasPresetModes: Boolean): Boolean =
        !supportsSetSpeed && !hasPresetModes

    /** HA `WaterHeaterEntityFeature.AWAY_MODE` bit. */
    const val WATER_HEATER_AWAY_MODE = 4

    /** True when a water heater advertises away-mode support, so the sheet offers
     *  an away-mode toggle. */
    fun waterHeaterSupportsAway(supportedFeatures: Int): Boolean =
        supportedFeatures == 0 || (supportedFeatures and WATER_HEATER_AWAY_MODE) != 0

    /** HA `ClimateEntityFeature.TARGET_HUMIDITY` bit. */
    const val CLIMATE_TARGET_HUMIDITY = 4

    /** True when a climate device exposes a settable target humidity, so the sheet
     *  shows a humidity slider alongside temperature. */
    fun climateSupportsTargetHumidity(supportedFeatures: Int): Boolean =
        (supportedFeatures and CLIMATE_TARGET_HUMIDITY) != 0
}
