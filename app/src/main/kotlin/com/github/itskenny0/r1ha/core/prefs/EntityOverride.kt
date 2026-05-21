package com.github.itskenny0.r1ha.core.prefs

/**
 * Per-card client-side customization. Each field is nullable so the absence of an
 * override means "fall through to the global setting" — that way a brand new card picks
 * up whatever UI option the user has set, but a card the user has customized stays
 * customized even when global options change.
 *
 * Stored alongside (not inside) the [AppSettings.nameOverrides] map. Names live in their
 * own map because that field shipped earlier and renaming it would force a migration of
 * users' existing renames; this struct adds the rest of the customizable surface.
 */
@kotlinx.serialization.Serializable
data class EntityOverride(
    /**
     * Absolute text size in sp for the card's big readout (the percent number, the
     * ON/OFF word, the sensor value). Null = use the theme default (72 sp). The picker
     * offers a curated list of values from 6 sp (tiny, for long sensor strings like
     * news headlines) up to 104 sp (huge). Previously stored as a 0.7..1.3 multiplier;
     * legacy values are still accepted on decode for back-compat.
     */
    val textSizeSp: Int? = null,
    /** Per-card override for [UiOptions.showOnOffPill]; null = inherit global. */
    val showOnOffPill: Boolean? = null,
    /** Per-card override for [UiOptions.showAreaLabel]; null = inherit global. */
    val showAreaLabel: Boolean? = null,
    /**
     * Entity to fire on long-press of this card. e.g. long-press a light card to trigger
     * a `scene.movie_night` or run a `script.bedtime`. Empty string = no long-press
     * action (the default). Validation: the entity_id must contain a "." and the
     * prefix must be a domain we know how to dispatch (anything supported, basically).
     */
    val longPressTarget: String? = null,
    /**
     * Per-card override for [UiOptions.maxDecimalPlaces]. Null = inherit global. Range
     * 0..6; 0 means "no decimals, integer only" which is useful for power meters and the
     * like where a fractional watt is just noise. Only relevant for sensor entities; the
     * customize dialog hides the picker for non-sensors.
     */
    val maxDecimalPlaces: Int? = null,
    /**
     * Per-card accent colour as an ARGB int, null = inherit the domain-derived accent.
     * The accent flows through to the card's domain-tab, the percent suffix, the switch
     * thumb when on, etc. Stored as Int rather than Color so the same encoding works in
     * preferences without needing a separate serializer.
     */
    val accentColor: Int? = null,
    /**
     * Fixed colour-temperature in kelvin to apply every time the light is turned on
     * (any wheel-up from 0% or tap-on to ON). Null = inherit HA's last value, which is
     * HA's default behaviour anyway. Only meaningful for light entities that report
     * `color_temp_kelvin` in their supported_color_modes. Sweet spots: 2700 warm,
     * 4000 neutral, 5500 cool-white, 6500 daylight.
     */
    val lightColorTempK: Int? = null,
    /**
     * Per-card hidden-button set for light cards. Defaults to empty (every supported
     * button visible). The user can toggle any of BRIGHT / WHITE / HUE / FX off from
     * the customize dialog — useful when a card only really needs BRIGHTNESS (a
     * "lamp" they never colour-tweak) and the WHITE/HUE/FX buttons just add noise.
     * Buttons are only rendered when the bulb actually supports them in HA AND the
     * button isn't in this hidden set; hiding a button the bulb doesn't support is
     * a no-op and harmless.
     */
    val lightButtonsHidden: Set<LightCardButton> = emptySet(),
    /**
     * Per-card override for the [Behavior.tapToToggle] setting. Three-state:
     *  - null: inherit the global setting (default).
     *  - true: tap-to-toggle is ENABLED on this card regardless of the global.
     *  - false: tap-to-toggle is DISABLED on this card regardless of the global.
     * Users surface this from the customize dialog as 'Inherit / On / Off' chips.
     * Useful when one specific card keeps getting toggled accidentally (e.g. a
     * smart-plug behind a thin chrome strip) without having to flip the global.
     */
    val tapToToggle: Boolean? = null,
    /**
     * Per-card override for "the wheel drives this card". Three-state:
     *  - null: inherit the per-domain default. Select / input_select default
     *    to OFF (cycling options is too easy to trigger accidentally); every
     *    other domain (lights, switches, climate, fans, covers, etc.)
     *    defaults to ON.
     *  - true: wheel ENABLED regardless of the per-domain default.
     *  - false: wheel DISABLED regardless of the per-domain default.
     * The customize dialog surfaces this as Inherit / On / Off chips.
     */
    val wheelEnabled: Boolean? = null,
) {
    companion object {
        /** Curated CT presets surfaced in the customize dialog. */
        val LIGHT_CT_PRESETS = listOf(
            "WARM" to 2700,
            "SOFT" to 3500,
            "NEUTRAL" to 4000,
            "COOL" to 5500,
            "DAY" to 6500,
        )

        /**
         * Default readout size in sp when no override is set. Matches R1.numeralXl —
         * defined here as a copy so the customize-dialog picker can label the default
         * chip with the actual sp value rather than the abstract word "default".
         */
        const val DEFAULT_TEXT_SIZE_SP = 72

        /**
         * Absolute sp values exposed in the customize-dialog text-size picker. Lower
         * bound goes to 6 sp so users can fit long sensor strings (RSS headlines,
         * verbose enum states) onto a single card without truncation; upper bound
         * stays at 104 sp for users who want a giant focal-point readout. The default
         * (72 sp) is included in the list so the chip-picker has a clear "back to
         * default" position.
         */
        val TEXT_SIZES_SP = listOf(6, 8, 10, 12, 14, 16, 20, 24, 28, 36, 48, 56, 72, 88, 104)

        /** Curated palette for the per-card accent picker. Hand-picked to feel cohesive
         *  on the near-black background — no neon, no muddy mid-tones. Names track the
         *  R1 design vocabulary where possible (Warm = stock orange). */
        val ACCENT_PALETTE: List<Pair<String, Int>> = listOf(
            "WARM" to 0xFFF36F21.toInt(),
            "COOL" to 0xFF41BDF5.toInt(),
            "GREEN" to 0xFF52C77F.toInt(),
            "NEUTRAL" to 0xFFB0B0B0.toInt(),
            "RED" to 0xFFE53935.toInt(),
            "AMBER" to 0xFFFFB300.toInt(),
            "VIOLET" to 0xFFB388FF.toInt(),
            "PINK" to 0xFFFF6F91.toInt(),
            "CYAN" to 0xFF26C6DA.toInt(),
        )

        val NONE = EntityOverride()

        /**
         * Per-domain default for whether the wheel acts on a card when the user
         * hasn't set a per-card override. Selects default OFF — cycling
         * through options on every detent was too easy to trigger
         * accidentally and the tap-to-open picker is the deliberate path.
         * Every other domain defaults ON because the wheel is the R1's
         * primary input and a brightness / volume / setpoint dial is the
         * whole reason for the wheel.
         */
        fun wheelEnabledByDefault(domainPrefix: String): Boolean = when (domainPrefix) {
            "select", "input_select" -> false
            else -> true
        }
    }

    /**
     * Resolve the effective wheel-enabled flag for this card's domain. The
     * explicit override wins when set; otherwise the per-domain default
     * applies.
     */
    fun resolvedWheelEnabled(domainPrefix: String): Boolean =
        wheelEnabled ?: wheelEnabledByDefault(domainPrefix)
}

/**
 * Light-card button identity for [EntityOverride.lightButtonsHidden]. Stored by its
 * single-character [code] in the preferences blob to keep the encoded size small —
 * EntityOverride already runs close to a screenful of pipe-separated fields and a
 * Set<Enum> stored as full names would dominate.
 */
@kotlinx.serialization.Serializable
enum class LightCardButton(val code: Char) {
    BRIGHTNESS('B'),
    WHITE('W'),
    HUE('H'),
    EFFECTS('F'),
    ;
    companion object {
        fun fromCode(code: Char): LightCardButton? = entries.firstOrNull { it.code == code }
    }
}
