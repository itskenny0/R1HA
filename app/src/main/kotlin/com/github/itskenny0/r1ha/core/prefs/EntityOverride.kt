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
    /**
     * When true, this card is hidden from the deck whenever its entity is
     * unavailable (HA state `unavailable` / `unknown` / blank). False / null
     * keeps the previous behaviour: an unavailable card stays in the deck,
     * dimmed via the UNAVAILABLE treatment. Useful for "sometimes-on" devices
     * (a vacuum that disappears when docked, a guest's phone, a
     * non-permanently-paired Bluetooth speaker) where the deck would
     * otherwise carry dead stubs.
     */
    val hideWhenUnavailable: Boolean? = null,
    /**
     * User-defined service-call buttons that render as chips below the
     * per-domain panel on the card. Each entry is a single tap → fire HA
     * service. Use cases: vendor-specific services HA's standard schema
     * doesn't surface (e.g. `xiaomi_miio_fan.fan_set_natural_mode_on`),
     * one-off scripts, integration-specific helpers.
     *
     * Stored per-entity (not globally) because the buttons make sense only
     * in the context of one entity; "natural mode" means nothing on a kettle.
     * Empty list = no extra chips render, no overhead.
     */
    val customActions: List<CustomAction> = emptyList(),
    /**
     * Client-side PIN gate for lock entities that don't enforce a code
     * server-side. When true, the lock card hides its direct UNLOCK/LOCK
     * switch and routes the action through the same PIN keypad
     * code-required locks use. The actual code isn't sent to HA (the lock
     * doesn't accept one) — the keypad's role is purely to require a
     * deliberate, multi-step gesture so a stray tap can't unlock the
     * door. Stored per-entity because adding the gate makes sense only
     * for the specific lock the user wants to harden; null / false means
     * the lock behaves as before (direct tap toggles).
     */
    val requirePinToUnlock: Boolean? = null,
    /**
     * The PIN the user must enter when [requirePinToUnlock] is true.
     * Stored as the SHA-256 of the PIN (hex-encoded) so the actual digits
     * never sit in plaintext on disk. Null / blank means "any non-empty
     * digit sequence accepted" — the gate then degrades to a deliberate-
     * gesture confirm rather than a true secret check, but the user can
     * always fill the PIN in later from the customize sheet to upgrade.
     */
    val requirePinHash: String? = null,
    /**
     * Per-card override for the small "you are here" position indicator
     * the chrome / overlay surfaces draw. Null = inherit the global
     * [UiOptions.positionDotLocation]; an explicit value pins the pip to
     * that corner whenever this card is the active one in the deck.
     *
     * Useful when one specific card has a bottom-right element (a tape
     * meter readout, a media transport row) that the global pip position
     * would collide with: the user can move the pip out of the way on
     * that one card without changing the global. Inherit is the common
     * case; the override should be reached for sparingly.
     */
    val positionDotLocation: PositionDotLocation? = null,
    /**
     * Per-card glyph override — a single emoji, Unicode character or
     * short symbol that replaces the domain-derived glyph on this card.
     * Null = use whatever glyph the card's domain renders by default
     * (the LIGHT / FAN / COVER / MEDIA / SWITCH families). Stored as a
     * free-form String rather than a constrained enum so users can pick
     * any Unicode codepoint without us having to ship a curated set.
     *
     * Reading composables that don't yet honour the override fall through
     * to the domain default; the field is additive and harmless to ignore.
     */
    val glyphOverride: String? = null,
    /**
     * Per-card override for the action that fires on a single tap of the
     * card body. Null = inherit (the historical behaviour: TOGGLE when
     * the global [Behavior.tapToToggle] is on and the card is a scalar
     * or boolean entity, NOOP otherwise). Explicit values let the user
     * repurpose the tap surface — e.g. a `light.kitchen` card whose tap
     * fires a `script.scene_pick` instead of toggling the light.
     *
     * See [TapAction] for the full list of supported targets. Routed
     * through the same dispatch path the long-press action uses, so an
     * "INHERIT" override still pays the existing card-level gesture
     * cost — there's no performance trade-off in setting this.
     */
    val actionOnTap: TapAction? = null,
    /**
     * Per-card override for the wheel-press (centre push of the scroll
     * wheel on R1 hardware; equivalent to the configurable hardware key
     * on other devices). Null = inherit the per-device default of NOOP.
     * Useful for one-tap shortcuts on a card the user looks at often
     * (e.g. wheel-press the front-door lock card to fire `script.away`).
     */
    val actionOnWheelPress: TapAction? = null,
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
 * One user-defined service-call button bound to a card. [label] is what the
 * user sees on the chip; [service] is HA's dotted `domain.service` (e.g.
 * `xiaomi_miio_fan.fan_set_natural_mode_on`); [dataJson] is the optional
 * `service_data` payload as a raw JSON object (parsed at fire time so a
 * malformed string fails loudly to the user once, not on every render);
 * [targetEntityId] overrides the card's own entity_id when the service
 * needs to act on a different entity (rare; usually the card's own entity).
 */
@kotlinx.serialization.Serializable
data class CustomAction(
    val label: String,
    val service: String,
    val dataJson: String? = null,
    val targetEntityId: String? = null,
)

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

/**
 * Where the "you are here" position indicator (the vertical pip plus the
 * "N/M" counter) sits on the card deck. Used both as a global default
 * ([UiOptions.positionDotLocation]) and as a per-card override
 * ([EntityOverride.positionDotLocation]) — the per-card value wins when
 * present, so a single card whose layout collides with the pip can move
 * it elsewhere without changing the global.
 *
 * Encoded by [code] in the per-card preferences blob to match the same
 * single-character convention [LightCardButton] uses, keeping the
 * pipe-separated row compact even on installs with hundreds of
 * customised cards. The full enum name is used in the global
 * [UiOptions.positionDotLocation] slot because that one is JSON-shaped
 * and doesn't pay the per-row cost.
 */
@kotlinx.serialization.Serializable
enum class PositionDotLocation(val code: Char) {
    /** Top-left corner. */
    TOP_LEFT('1'),
    /** Top-centre — the canonical chrome-row position (default global). */
    TOP_CENTER('2'),
    /** Top-right corner. */
    TOP_RIGHT('3'),
    /** Left edge, vertically centred. */
    LEFT_CENTER('4'),
    /** Right edge, vertically centred. */
    RIGHT_CENTER('5'),
    /** Bottom-left corner. */
    BOTTOM_LEFT('6'),
    /** Bottom-centre. */
    BOTTOM_CENTER('7'),
    /** Bottom-right corner. */
    BOTTOM_RIGHT('8'),
    /** Hide the indicator entirely. */
    HIDDEN('0'),
    ;
    companion object {
        fun fromCode(code: Char): PositionDotLocation? = entries.firstOrNull { it.code == code }
    }
}

/**
 * What happens when the user invokes one of the per-card action surfaces
 * — tap, long-press, wheel-press. Inherit semantics live on the
 * containing field (a null override means "use the card's default
 * behaviour for this surface"); the explicit values below pick a
 * specific behaviour regardless of what the global setting would say.
 *
 * NOOP is included so the user can disarm a surface entirely — useful
 * for cards next to sensitive controls (e.g. a `lock.front_door` card
 * whose long-press would otherwise fire an unintended scene). FIRE
 * targets the card's own entity (toggle for booleans, activate for
 * actions); TOGGLE is the explicit "tap-to-toggle" intent; NAVIGATE_HISTORY
 * opens the sensor history overlay even on non-sensor entities (handy
 * for diagnosing a switch that flips itself).
 */
@kotlinx.serialization.Serializable
enum class TapAction(val code: Char) {
    /** Toggle the card's own entity (lights, switches, locks, etc.). */
    TOGGLE('T'),
    /** Fire the card's own entity service (activate scenes / scripts / buttons). */
    FIRE('F'),
    /** Open the sensor-history overlay for this entity. */
    NAVIGATE_HISTORY('H'),
    /** Do nothing — explicitly disarm this surface. */
    NOOP('0'),
    ;
    companion object {
        fun fromCode(code: Char): TapAction? = entries.firstOrNull { it.code == code }
    }
}
