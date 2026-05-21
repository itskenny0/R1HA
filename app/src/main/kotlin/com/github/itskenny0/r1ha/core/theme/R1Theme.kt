package com.github.itskenny0.r1ha.core.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.github.itskenny0.r1ha.core.prefs.ThemeId

@Stable
data class SystemBarColors(val status: Color, val nav: Color, val lightIcons: Boolean = false)

/** What an EntityCard needs to render. Kept minimal and theme-agnostic. */
@Stable
data class CardRenderModel(
    val entityIdText: String,
    val friendlyName: String,
    val area: String?,
    val percent: Int,
    val isOn: Boolean,
    val domainGlyph: Glyph,
    val accent: AccentRole,
    val isAvailable: Boolean,
    /** Optional per-card override colour from [EntityOverride.accentColor]. Themes
     *  consult this before falling back to their role→colour mapping. */
    val accentOverride: Color? = null,
    /**
     * For entities where the percent abstraction would hide the actual interesting
     * value (climate showing "21 °C" rather than "60 %"), the EntityCard wrapper sets
     * these. Themes use them in place of the percent + "%" suffix when present.
     * Null → standard percent display.
     */
    val displayValue: String? = null,
    val displayUnit: String? = null,
    /** Per-card absolute readout size in sp from [EntityOverride.textSizeSp]. Null
     *  means use the theme's default size (72 sp for the big numeralXl on the percent
     *  card). The suffix is scaled proportionally so the unit doesn't dominate the
     *  reduced numeral. */
    val textSizeSp: Int? = null,
    /**
     * Light-card-only: which wheel mode is currently active. Null means default
     * (BRIGHTNESS). Themes use this to render the right suffix (% / K / °) and the
     * tap-to-cycle affordance on the readout. Non-light cards ignore.
     */
    val lightWheelMode: com.github.itskenny0.r1ha.core.ha.LightWheelMode? = null,
    /** Light-card-only: currently-active effect name from HA (`effect` attribute). Null
     *  when the bulb doesn't support effects or has none active. */
    val lightEffect: String? = null,
    /** Light-card-only: how many effects the bulb has available. Used by themes to
     *  decide whether to show the effect chip — single-effect-list lights still get
     *  the chip so the user can toggle the effect on/off. */
    val lightEffectListSize: Int = 0,
    /** Light-card-only: the full list of available effects from HA's `effect_list`.
     *  Themes pass this to the effect-picker sheet so the user can tap any effect by
     *  name rather than cycling. Empty for non-light entities or bulbs without effects. */
    val lightEffectList: List<String> = emptyList(),
    /**
     * Light-card-only: which wheel modes this bulb supports, derived from HA's
     * `supported_color_modes`. Always at least [LightWheelMode.BRIGHTNESS]. Themes use
     * this to render the segmented mode picker (BRIGHT / WHITE / COLOUR) — buttons for
     * unsupported modes are hidden so a tunable-white bulb doesn't show a useless
     * COLOUR button.
     */
    val lightAvailableModes: List<com.github.itskenny0.r1ha.core.ha.LightWheelMode> = emptyList(),
    /**
     * Light-card-only: per-card hidden-button set from the customize dialog. Themes
     * subtract this from [lightAvailableModes] / the FX button before rendering. Empty
     * = show every supported button (default).
     */
    val lightButtonsHidden: Set<com.github.itskenny0.r1ha.core.prefs.LightCardButton> = emptySet(),
    /**
     * Media-player-only: now-playing snapshot for the rich track-info row. The card
     * renders an album cover, title / artist, and a progress bar that ticks live by
     * interpolating [mediaPosition] forward from [mediaPositionUpdatedAt]. Fields
     * default to null / 0 / 0 so non-media cards (and idle media_players) skip the
     * row entirely without consuming layout space.
     */
    val mediaTitle: String? = null,
    val mediaArtist: String? = null,
    val mediaAlbumName: String? = null,
    val mediaDurationSec: Int? = null,
    val mediaPositionSec: Int? = null,
    val mediaPositionUpdatedAt: java.time.Instant? = null,
    val mediaPicture: String? = null,
    val mediaIsPlaying: Boolean = false,
    /** Mirrors [com.github.itskenny0.r1ha.core.ha.EntityState.isVolumeMuted] —
     *  surfaces the muted state to the media controls so the mute button can
     *  render its 'currently muted' visual (filled background + slashed speaker
     *  glyph) instead of being permanently stuck in one state. */
    val mediaIsMuted: Boolean = false,
    /** Mirrors [com.github.itskenny0.r1ha.core.ha.EntityState.mediaSupportedFeatures]
     *  — drives which transport buttons render on the card. When the integration
     *  doesn't advertise NEXT_TRACK / PREVIOUS_TRACK we hide those buttons rather
     *  than show them and let HA reject the call with a Validation error. */
    val mediaSupportedFeatures: Int = 0,
    /** Mirrors [com.github.itskenny0.r1ha.core.ha.EntityState.lastChanged] —
     *  surfaces the state's age to themes that want to render a small
     *  'X min ago' label so users can tell at a glance whether a sensor is
     *  fresh. Null when the cache hasn't seen the entity yet. */
    val lastChangedAt: java.time.Instant? = null,
    /**
     * Tick labels for the vertical tape meter (right side of the card). Top→bottom
     * order, typically five strings. Null falls back to the default `100/75/50/25/0`
     * percent labels. Climate / water_heater cards override this with the actual
     * temperature range in the user's chosen unit so the meter conveys the real
     * setpoint range rather than an abstract 0..100. Number / input_number cards
     * pass their min..max so the user can see what value the bottom of the bar
     * represents.
     */
    val meterLabels: List<String>? = null,
    /**
     * Live [EntityState] backing this card. Themes that render dedicated
     * per-domain panels (LockPanel, VacuumPanel, ClimatePanel, etc.) reach
     * into the per-domain attribute set on this object instead of plumbing
     * every field through the model. Null only on synthetic preview cards
     * (e.g. theme picker samples) where there's no real entity backing.
     */
    val entityState: com.github.itskenny0.r1ha.core.ha.EntityState? = null,
) {
    enum class Glyph {
        LIGHT, FAN, COVER, MEDIA_PLAYER,
        // Generic on/off — used for switch/input_boolean/automation. Theme cards just
        // render the domain label text; the glyph itself isn't drawn as an icon today.
        SWITCH,
        LOCK,
        HUMIDIFIER,
        CLIMATE,
        NUMBER,
        VALVE,
        VACUUM,
        WATER_HEATER,
        LAWN_MOWER,
    }
    enum class AccentRole { WARM, COOL, GREEN, NEUTRAL }
}

interface R1Theme {
    val id: ThemeId
    val displayName: String
    val systemBars: SystemBarColors
    val baseline: ColorScheme

    @Composable fun Card(model: CardRenderModel, modifier: Modifier, onTapToggle: () -> Unit)
}

/** Shared baseline used by all three themes for non-card screens (settings, picker, about, onboarding). */
internal val sharedDarkBaseline: ColorScheme = darkColorScheme(
    primary = Color(0xFFF36F21),
    onPrimary = Color(0xFF1A0E04),
    background = Color(0xFF0A0A0A),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF141414),
    onSurface = Color(0xFFEDEDED),
)

/**
 * Spring-animated 0..1 fraction for the slider. Damping is just under critical (0.45) and
 * stiffness is in the medium band so the overshoot is actually visible — the fill bounces ~5%
 * past the new target and settles over ~250 ms. StiffnessHigh + LowBouncy from earlier was so
 * stiff that the bounce settled in one frame and effectively disappeared.
 */
@Composable
internal fun rememberSliderFraction(percent: Int): Float {
    val target = percent.coerceIn(0, 100) / 100f
    val animated by androidx.compose.animation.core.animateFloatAsState(
        targetValue = target,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.45f,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
            visibilityThreshold = 0.001f,
        ),
        label = "sliderFraction",
    )
    return animated
}
