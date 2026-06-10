package com.github.itskenny0.r1ha.core.prefs

import androidx.compose.runtime.Immutable

enum class ThemeId { MINIMAL_DARK, PRAGMATIC_HYBRID, COLORFUL_CARDS }

/**
 * Controls how the app responds to device rotation.
 *
 * FOLLOW_DEVICE  — the activity rotates with the sensor; right choice for
 *                  tablets and phones used in landscape.
 * PORTRAIT_ONLY  — locks to portrait regardless of sensor; right choice for
 *                  the R1 (which users never rotate) and for phone users who
 *                  prefer one-handed portrait use.
 */
enum class OrientationMode { FOLLOW_DEVICE, PORTRAIT_ONLY }

enum class DisplayMode { PERCENT, RAW }

/**
 * Controls the card-stack "peek deck" presentation, where cards render at roughly half
 * the viewport height with the active card centred and the previous / next cards peeking
 * above and below it.
 *
 * AUTO   — peek only on a phone-width tier in portrait orientation; every other device
 *          (the R1 / sub-compact tier, landscape, tablet / expanded tiers) keeps the
 *          historical full-viewport single-card behaviour. This is the default.
 * ALWAYS — peek on every device and orientation. Lets R1 / small-phone users who like
 *          the at-a-glance neighbour view opt in even though AUTO would not pick it.
 * NEVER  — always full-viewport, regardless of device or orientation.
 */
enum class CardPeekMode { AUTO, ALWAYS, NEVER }

/**
 * Display unit for temperature readouts. AUTO follows HA's reported unit
 * (`temperature_unit` attribute on climate entities, defaults to Celsius); CELSIUS and
 * FAHRENHEIT force the display + conversion regardless of HA's setting.
 */
enum class TemperatureUnit { AUTO, CELSIUS, FAHRENHEIT }

/**
 * Global UI text-size step, applied as a multiplier on top of the responsive
 * type pipeline's per-tier scale. DEFAULT keeps every screen byte-for-byte at
 * its hand-tuned size. The smaller / larger steps exist for two real
 * audiences: COMPACT squeezes a little more content onto the R1's tiny panel,
 * while LARGE / EXTRA_LARGE make a wall-mounted kiosk readable from across
 * the room without the user having to change the Android system font size
 * (which kiosk devices often can't reach, and which would also inflate every
 * other app on the device).
 *
 * Implemented as a font-scale multiplier on the composition's Density so it
 * covers EVERY sp-sized text in the app, including the card-stack themes
 * that use the raw hand-tuned [com.github.itskenny0.r1ha.core.theme.R1] type
 * ramp rather than the responsive scaleType pipeline. Container sizes stay
 * in dp, so the extreme step can tighten tall layouts; the steps are kept
 * mild for that reason.
 */
enum class UiTextScale(val factor: Float) {
    COMPACT(0.85f),
    DEFAULT(1.0f),
    LARGE(1.15f),
    EXTRA_LARGE(1.3f),
}

/**
 * Legacy fixed font-face palette, superseded by [UiOptions.fontFamilyName]
 * (any named system family, discovered at runtime). Kept ONLY so values
 * persisted by the eight-face era still decode: the old `ui.font_face`
 * preference key and the `uiFontFace` backup field both store these enum
 * names. New code never selects a [FontFace]; reads map each value onto the
 * family-name model via [fontFaceToFamilyName].
 */
enum class FontFace { DEFAULT, SANS, CONDENSED, LIGHT, SERIF, CASUAL, CURSIVE, MONO }

/**
 * Migration table from the legacy eight-face palette to the family-name
 * model: each face becomes the Android named family it used for chrome and
 * prose, DEFAULT becomes "" (the stock mix). Note the deliberate semantic
 * drift for CONDENSED and SERIF: they used to keep monospace numerals, but
 * the family-name model applies one family to the whole ramp, so migrated
 * installs get matching numerals too, closer to what picking "serif" means.
 */
fun fontFaceToFamilyName(face: FontFace): String = when (face) {
    FontFace.DEFAULT -> ""
    FontFace.SANS -> "sans-serif"
    FontFace.CONDENSED -> "sans-serif-condensed"
    FontFace.LIGHT -> "sans-serif-light"
    FontFace.SERIF -> "serif"
    FontFace.CASUAL -> "casual"
    FontFace.CURSIVE -> "cursive"
    FontFace.MONO -> "monospace"
}

/**
 * Best-effort reverse of [fontFaceToFamilyName], used to materialise the
 * legacy `uiFontFace` backup field so a pre-rework build restoring a new
 * backup still lands near the chosen look. Vendor families with no legacy
 * equivalent collapse to DEFAULT (the stock mix): safe, never garish.
 */
fun fontFaceFromFamilyName(name: String): FontFace =
    FontFace.entries.firstOrNull { fontFaceToFamilyName(it) == name } ?: FontFace.DEFAULT

/**
 * How clock-style time-of-day readouts are rendered (the TODAY greeting
 * clock, sensor-history row times, hourly forecast labels, chart time axes,
 * absolute timestamps). AUTO follows the Android system 12/24-hour setting;
 * H12 / H24 force one style regardless of the device configuration — useful
 * on kiosk R1s running a GSI where the system setting is wrong or
 * unreachable. Values that mirror a Home Assistant server string verbatim
 * (input_datetime raw values, ISO timestamps in debug surfaces) are NOT
 * reformatted; this only affects displays the app composes itself.
 */
enum class ClockFormat { AUTO, H12, H24 }

/**
 * Vertical density of the shared list row ([com.github.itskenny0.r1ha.ui.components.R1Row])
 * used across the list-style screens (Devices, Logbook, Settings, pickers).
 * COMFORTABLE keeps the historical 48 dp minimum touch target; COMPACT
 * tightens the vertical padding and minimum height so a big HA install's
 * device / entity lists fit more rows per screenful — a real win on the R1's
 * short panel where COMFORTABLE shows only a handful of rows at a time.
 */
enum class ListDensity { COMFORTABLE, COMPACT }

/**
 * How "last changed" style timestamps render on cards and list rows.
 * RELATIVE (default) is the live-ticking '5m ago' label. ABSOLUTE swaps in
 * wall-clock time ('14:32' today, '3 Jun 14:32' older) for users who think
 * in clock time rather than deltas — the same toggle HA's own frontend
 * offers per-user. Honors [UiOptions.clockFormat] for the 12/24-hour style.
 */
enum class TimestampStyle { RELATIVE, ABSOLUTE }

/**
 * Shape of the acceleration curve when `wheel.acceleration` is on. The wheel rate (in
 * events/sec) gets folded through the matching slope to produce a step multiplier;
 * SUBTLE keeps the boost small for precise dimming, AGGRESSIVE goes hard so a fast
 * spin can cross the full 0..100 range in a couple of detents. MEDIUM is the previous
 * behaviour (1 + excess*0.5 above 4 ev/s).
 */
enum class AccelerationCurve { SUBTLE, MEDIUM, AGGRESSIVE }

/**
 * Threshold for the in-app toast diagnostic feed. OFF (default) means R1Log events
 * never surface as toasts; the higher levels (ERROR > WARN > INFO > DEBUG) each
 * gate progressively more chatter. Useful for diagnosing 'where's my entity?' on
 * R1 devices without adb access — set to WARN and the picker's per-row drop
 * messages pop up as tappable expanding toasts.
 */
enum class ToastLogLevel { OFF, ERROR, WARN, INFO, DEBUG }

/**
 * Toggleable chrome-row buttons sitting in the right cluster. The left-side
 * hamburger and centre VerticalPagePip are fixed (they're navigation primitives
 * and the page indicator) and so don't appear here. The settings gear stays
 * required-on too — without it the user can't reach Settings to change anything
 * — but it IS part of this list so it can be reordered; the toggle just stays
 * forced-true.
 */
enum class ChromeButtonRef { BATTERY, ASSIST_MIC, DETAIL, EDIT, GEAR }

/**
 * Per-button configuration for the chrome row's right cluster. The list order
 * in [UiOptions.chromeButtons] is the render order (left → right); each entry's
 * [enabled] decides whether the button renders at all. GEAR is forced-on by the
 * settings UI so the user can never lose their way back to Settings.
 */
@Immutable
@kotlinx.serialization.Serializable
data class ChromeButtonConfig(
    val ref: ChromeButtonRef,
    val enabled: Boolean = true,
)

@Immutable
data class WheelSettings(
    val stepPercent: Int = 2,           // 1, 2, 5, or 10
    val acceleration: Boolean = true,
    val invertDirection: Boolean = false,
    /** Slope of the acceleration curve when [acceleration] is on. */
    val accelerationCurve: AccelerationCurve = AccelerationCurve.MEDIUM,
)

@Immutable
data class UiOptions(
    val displayMode: DisplayMode = DisplayMode.PERCENT,
    val showOnOffPill: Boolean = true,
    val showAreaLabel: Boolean = true,
    /** Draw the entity's domain icon on each card-stack card, tinted with the card
     *  accent. Default ON. */
    val cardStackIcons: Boolean = true,
    /**
     * Where the "you are here" position pip and counter sit on the card
     * deck. Default TOP_CENTER matches the historical chrome-row layout;
     * HIDDEN suppresses the indicator entirely. Per-card overrides via
     * [EntityOverride.positionDotLocation] win when set, so a single card
     * whose layout collides with the global slot can move the pip out
     * of the way without changing the deck-wide default.
     *
     * Migration: pre-enum installs stored this as a boolean
     * `showPositionDots`; `true` migrates to TOP_CENTER and `false`
     * migrates to HIDDEN, so existing users see no visual change.
     */
    val positionDotLocation: PositionDotLocation = PositionDotLocation.TOP_CENTER,
    /**
     * Where the main value bar (the brightness / volume / cover-position /
     * setpoint slider) sits on every card. Default RIGHT matches the
     * historical layout where the bar ran flush against the card's right
     * edge. LEFT / TOP / BOTTOM move it to the matching edge; HIDDEN drops
     * it entirely (the wheel and tap-to-toggle still work). Per-card
     * overrides via [EntityOverride.valueBarLocation] win when set.
     */
    val valueBarLocation: ValueBarLocation = ValueBarLocation.RIGHT,
    /** Number of recent state-change entries shown on text/categorical SensorCard history. */
    val textHistoryLength: Int = 20,
    /**
     * When on, the chrome row at the top of the card stack draws a solid background so
     * the previous card's tail-end can't peek through into the chrome area. On by
     * default — most users wanted a clean transition rather than a "deck of cards"
     * look. Off restores the original transparent-chrome behaviour where the previous
     * card is visible under the chrome.
     */
    val hideCardTailAbove: Boolean = true,
    /** Max decimal places shown for numeric sensor readings; 0 = integer, 2 = default. */
    val maxDecimalPlaces: Int = 2,
    /** Force-display temperature unit; AUTO follows HA's native unit. Default Celsius. */
    val tempUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
    /**
     * When on, the card stack wraps — wheeling/swiping past the last card lands on the
     * first, and vice versa. Off by default so a user can tell when they've reached the
     * end of their list. The action-card overscroll-to-fire gesture still wins at the
     * top boundary regardless of this setting.
     */
    val infiniteScroll: Boolean = false,
    /**
     * Per-button configuration for the chrome row's right cluster — the order entries
     * appear in this list is the left→right render order, and each entry's [enabled]
     * gates whether the button shows. GEAR is always present in the list with
     * `enabled = true`; the Settings UI keeps it forced-on so the user can never lose
     * the path back to Settings.
     *
     * Default order matches the pre-config layout: BATTERY → MIC → EDIT → GEAR.
     */
    val chromeButtons: List<ChromeButtonConfig> = listOf(
        ChromeButtonConfig(ChromeButtonRef.BATTERY, enabled = true),
        ChromeButtonConfig(ChromeButtonRef.ASSIST_MIC, enabled = true),
        ChromeButtonConfig(ChromeButtonRef.DETAIL, enabled = true),
        ChromeButtonConfig(ChromeButtonRef.EDIT, enabled = true),
        ChromeButtonConfig(ChromeButtonRef.GEAR, enabled = true),
    ),
    /**
     * When on, cards whose entity is currently off always render their percentage arc at 0 %,
     * regardless of what HA last reported for that entity's brightness. Useful for lights that
     * retain their pre-off brightness in HA (e.g. Zigbee bulbs that store the last value):
     * off by default the card might show "75 %" even though the light is dark, which is
     * confusing. With this on the arc goes blank when the entity is off and snaps back to the
     * real brightness once HA confirms the entity turned on.
     *
     * Default OFF to match the original behaviour: the arc shows whatever HA reported.
     */
    val showZeroPercentWhenOff: Boolean = false,
    /**
     * Card-stack "peek deck" presentation. AUTO (default) renders the peek deck only on
     * a phone-width tier in portrait, leaving the R1 / sub-compact tier, landscape, and
     * tablet / expanded tiers on the historical full-viewport single-card layout. ALWAYS
     * forces the peek deck everywhere (the opt-in path for R1 / small-phone users); NEVER
     * forces full-viewport everywhere. See [com.github.itskenny0.r1ha.feature.cardstack
     * .effectivePeek] for the pure decision function the card stack reads.
     *
     * Default AUTO so existing installs see no change on the R1 / sub-compact tier.
     */
    val cardPeekMode: CardPeekMode = CardPeekMode.AUTO,
    /**
     * Card-stack scroll sensitivity, expressed as a 0..100 percentage that scales the
     * fling inertia (momentum / coast distance) when touch-scrolling the vertical deck.
     *
     * The card stack folds this into the pager's fling decay friction. Higher friction
     * stops the coast sooner (less inertia); lower friction lets a flick glide further
     * and faster (more inertia). The mapping is inverse-proportional and anchored so
     * the DEFAULT of 80 reproduces Compose's stock fling feel exactly:
     *
     *     frictionMultiplier = 0.8 / (sensitivity / 100)   // == 80 / sensitivity
     *
     * At 80 → friction 1.0 (the Compose ExponentialDecay default, i.e. today's feel).
     * Below 80 the friction climbs (e.g. 40 → 2.0) so the deck brakes harder and the
     * coast is shorter; above 80 it drops (e.g. 100 → 0.8) so a flick carries further
     * and faster. Anchoring the default at 80 rather than 100 leaves deliberate
     * head-room for users who want MORE inertia than the stock behaviour, while still
     * allowing meaningfully slower. See [com.github.itskenny0.r1ha.feature.cardstack]'s
     * PageDeck for the consuming code. Coerced to 0..100 on load.
     */
    val cardScrollSensitivity: Int = 80,
    /**
     * Deck-wide default for whether the ultra-detail "more info" sheet is
     * OFFERED on a card / dashboard tile. On by default — the richer
     * attribute / history view is the natural deep-dive surface and most
     * users want it reachable. Per-card overrides via
     * [EntityOverride.moreInfoEnabled] win when set, so a card can opt out
     * (or back in) without changing this deck-wide default. When the
     * effective value is false, the "MORE INFO" / "DETAILS" affordance is
     * simply not shown for that entity; the rest of the card behaves as
     * before.
     */
    val moreInfoEnabledDefault: Boolean = true,
    /**
     * Global text-size step. DEFAULT renders every screen at its hand-tuned
     * size; the other steps multiply the app's font scale so kiosk installs
     * can be read from across a room (LARGE / EXTRA_LARGE) or the R1 can fit
     * a little more per screen (COMPACT). See [UiTextScale] for why this is
     * a font-scale multiplier rather than a responsive-pipeline-only knob.
     */
    val textScale: UiTextScale = UiTextScale.DEFAULT,
    /**
     * Android named font family applied to the WHOLE type ramp, numerals
     * included ("serif", "casual", or any vendor family the device's
     * fonts.xml declares; see [com.github.itskenny0.r1ha.core.theme.SystemFontCatalog]).
     * Empty string = the stock mix: monospace numerals (tabular digits keep
     * readouts steady while values tick) with sans-serif chrome and prose.
     * System typefaces only: the app bundles no font assets, so the
     * public-domain dedication stays clean.
     */
    val fontFamilyName: String = "",
    /**
     * 12 vs 24-hour style for clock readouts the app composes itself (TODAY
     * greeting clock, sensor-history times, hourly forecast labels, chart
     * time axes, absolute timestamps). AUTO (default) follows the Android
     * system setting so existing installs see no change; H12 / H24 force a
     * style for devices whose system setting is wrong or unreachable.
     */
    val clockFormat: ClockFormat = ClockFormat.AUTO,
    /**
     * Vertical density of the shared list row. COMFORTABLE (default) keeps
     * the historical 48 dp touch target; COMPACT trades some finger room for
     * more rows per screenful on big-install device / entity lists.
     */
    val listDensity: ListDensity = ListDensity.COMFORTABLE,
    /**
     * RELATIVE (default) renders "last changed" labels as a live-ticking
     * '5m ago'; ABSOLUTE swaps in wall-clock time for users who think in
     * clock time. Applies to the card freshness labels and every list row
     * that uses the shared relative-time label.
     */
    val timestampStyle: TimestampStyle = TimestampStyle.RELATIVE,
    /**
     * When on, screen-to-screen navigation cuts instantly instead of the
     * fade + rise transition, and the loading-skeleton pulse freezes to a
     * static block. For vestibular comfort and for very low-end devices
     * where the nav animation janks. Off by default — the stock motion is
     * deliberately quick and most users never notice it.
     */
    val reduceMotion: Boolean = false,
)

@Immutable
data class Behavior(
    val haptics: Boolean = true,
    val keepScreenOn: Boolean = true,
    /**
     * Whole-card tap toggles the entity. Default off — users reported accidentally
     * firing entities while aiming for chrome buttons (the chrome's hamburger sits
     * close to the card's top-left, and any miss landed on the card's whole-card
     * tap surface). With this off, the wheel remains the primary control: wheel-down
     * to 0 % turns scalar entities off, wheel-up turns them on. Explicit toggles for
     * non-scalar entities live on their cards (SwitchCard's ON / OFF labels,
     * ActionCard's ACTIVATE button) and aren't affected by this setting.
     */
    val tapToToggle: Boolean = false,
    /**
     * When on, the Android system status bar is hidden across the app via the
     * WindowInsetsController. Off by default — the bar is harmless and gives the user
     * a clock + battery for free. Useful when running on an R1 LineageOS GSI where the
     * bar competes with our chrome row for the precious top 24 dp.
     */
    val hideStatusBar: Boolean = false,
    /**
     * When [hideStatusBar] is on the user loses sight of the Android system battery
     * percentage — fine for most users but a real loss on the R1 where a low battery
     * means a hard shutdown mid-control. When this flag is also on, the chrome row
     * renders a tiny "85%" pill on the right side, polled from the BatteryManager
     * sticky broadcast every 30 s. Off by default so users who hide the status bar
     * for the pure-card aesthetic don't get unwanted clutter back.
     *
     * No effect when [hideStatusBar] is off — in that case the system bar already
     * shows the battery so duplicating it would be busy.
     */
    val showBatteryWhenStatusBarHidden: Boolean = false,
    /**
     * When on, the app opens on the TODAY dashboard rather than the
     * card stack. Useful for wall-mounted / kiosk R1 setups where the
     * device's primary purpose is information radiation (weather,
     * who's home, calendar) rather than active control. Defaults to
     * off because the card stack is the more-frequent use case for
     * handheld R1s.
     */
    val startOnDashboard: Boolean = false,
    /**
     * When on (the default), scrolling the wheel on a non-scalar card (lock,
     * cover-without-position, vacuum, plain switch) flips it on/off — wheel-up =
     * on, wheel-down = off. Earlier versions flipped this to off after one user
     * report of accidental fires, but a follow-up made it clear that the wheel
     * toggling switches was the intended behaviour — the accidental-fire concern
     * was actually about action cards (scenes / scripts / buttons), which have
     * their own no-wheel guard. Users who DO want the wheel inert on switch cards
     * (so a brush doesn't relock a door) can still turn this off in
     * Settings → Behaviour.
     */
    val wheelTogglesSwitches: Boolean = true,
    /**
     * One-shot flag flipped true after the first wheel event the user fires.
     * Drives a small "↻ WHEEL TO ADJUST" hint on the first sensor card so
     * fresh installs aren't confronted with a static stack of cards and no
     * obvious way to interact. Not surfaced in the Settings registry — it's
     * pure onboarding state, not a user-tunable preference.
     */
    val wheelTutorialSeen: Boolean = false,
    /**
     * versionCode of the build whose what's-new overlay this device has
     * resolved (shown, or stamped silently on first install). 0 = never
     * stamped. Per-device launch state like [wheelTutorialSeen]: not synced,
     * not user-tunable, not part of the Settings registry.
     */
    val lastSeenVersionCode: Int = 0,
    /**
     * "Show what's new after updates": when off, upgrades stamp
     * [lastSeenVersionCode] silently and the overlay never appears. Surfaced
     * next to the updater in About and via the overlay's own ⋯ affordance.
     */
    val showWhatsNew: Boolean = true,
    /**
     * Level threshold for the in-app diagnostic toast feed. OFF (default) is a clean
     * UI — no toasts unless the user explicitly opts in. WARN is the friendly
     * diagnostic level: failures, decoder drops, settings-save fallbacks pop up as
     * tappable expanding toasts. DEBUG shows everything R1Log emits.
     */
    val toastLogLevel: ToastLogLevel = ToastLogLevel.OFF,
    /**
     * The entity_id bound to the Android Quick Settings tile. When non-empty,
     * `HaQuickTileService` (the system-provided tile that lives in the
     * notification shade's quick-settings panel) reads this entity_id, fetches
     * its current state to populate the tile label + on/off mode, and dispatches
     * a toggle service call when the user taps it. Empty/null = the tile shows
     * a 'tap to set up' placeholder.
     *
     * Limited to one entity at a time because Android lets each app declare a
     * single TileService instance; the HA Companion app's 40-tile fan-out
     * needs 40 separately-named services which is excessive plumbing for the
     * common 'one toggle I want everywhere' use case the R1 client serves.
     */
    val quickTileEntityId: String? = null,
    /** Slots B, C, D — additional Quick Settings tiles bound to extra entities.
     *  The HA Companion app supports 40 tiles via 40 declared TileService
     *  classes; we cap at four (A + B + C + D) which covers the common
     *  "morning/evening/away/quick scene" mental model without bloating the
     *  manifest or the picker. Empty/null = the corresponding tile shows a
     *  'tap to set up' placeholder. */
    val quickTileEntityIdB: String? = null,
    val quickTileEntityIdC: String? = null,
    val quickTileEntityIdD: String? = null,
    /**
     * When on, opening the Assist screen immediately focuses the
     * input field — which pops up the soft keyboard on devices
     * with one. Off by default: the user reported the auto-open
     * being intrusive on phones (the empty-state recenters
     * jarringly when the IME shrinks the transcript area). With
     * this off they tap the input field themselves to start
     * typing; voice input via the 🎤 button always works without
     * the keyboard.
     */
    val assistAutoOpenKeyboard: Boolean = false,
    /**
     * Conversation-agent ID passed to HA's `conversation/process` endpoint. Null = let
     * HA pick its default agent (the normal Assist behaviour). When set, every Assist
     * request routes to this specific agent — useful for installs with multiple agents
     * configured (OpenAI / local Llama / Google) so the user can pick which back-end
     * answers without round-tripping into HA's web UI to flip the default. Stored as
     * a free-form string because HA accepts both legacy agent IDs (`"homeassistant"`,
     * `"conversation.openai_conversation"`) and pipeline UUIDs; we don't second-guess
     * the value.
     */
    val assistAgentId: String? = null,
    /**
     * Assist pipeline id the Voice Satellite runs against. Null = HA's default
     * (preferred) pipeline, the original behaviour. Set via the satellite's
     * pipeline picker so a user whose default pipeline has no speech-to-text
     * engine can point the satellite at one that does, rather than hitting HA's
     * "the pipeline does not support speech-to-text" error every run.
     */
    val voiceSatellitePipelineId: String? = null,
    /**
     * User-saved Assist prompt macros — quick-fire chips above the Assist input
     * that send the saved text on a single tap. Useful for repeat queries
     * ("what's the temperature?", "lock everything", "turn off all lights")
     * and for kiosk installs where the operator picks from a curated set
     * rather than typing. Stored newline-separated in DataStore; capped at a
     * reasonable number of entries by the UI so the chip row doesn't grow
     * unbounded.
     */
    val assistMacros: List<String> = emptyList(),
    /**
     * Whether the app follows the device rotation sensor or locks to portrait.
     * Defaults to FOLLOW_DEVICE so tablets and phones in landscape work out of
     * the box after the orientation-lock was removed. Users who prefer portrait
     * (R1, one-handed phone use) can switch to PORTRAIT_ONLY here.
     */
    val orientationMode: OrientationMode = OrientationMode.FOLLOW_DEVICE,
)

/**
 * Per-section visibility + behaviour for the TODAY dashboard. Every
 * section is on by default; users with installs that don't expose a
 * particular HA domain (no cameras, no person entities, no power
 * sensors) can hide the corresponding tile so the dashboard isn't
 * dotted with empty stubs.
 *
 * Thresholds (battery low %, power amber/red W) are also configurable
 * here because the right values are install-specific — a flat with
 * one PC pulls ~200 W idle; a house with EV charging needs 5+ kW
 * before the red tile means anything.
 *
 * Refresh intervals are exposed so kiosk-mounted R1s can dial them
 * down for less network churn (a wall-mounted weather display
 * doesn't need 60 s refresh — 5 min is fine).
 */
@Immutable
@kotlinx.serialization.Serializable
data class DashboardSettings(
    /** Show / hide each section. */
    val showGreeting: Boolean = true,
    val showWeather: Boolean = true,
    val showSun: Boolean = true,
    val showTimers: Boolean = true,
    val showMedia: Boolean = true,
    val showPersons: Boolean = true,
    val showNextEvent: Boolean = true,
    val showPower: Boolean = true,
    val showMetrics: Boolean = true,
    val showLowBattery: Boolean = true,
    val showInlineAlerts: Boolean = true,
    /** Auto-refresh cadence in seconds. 0 = no auto-refresh (pull-down only). */
    val refreshIntervalSec: Int = 60,
    /** Battery-low threshold for the dashboard's BATTERIES LOW alert
     *  card. Sensors with device_class='battery' under this percentage
     *  are listed. Default 20 % matches HA's convention. */
    val lowBatteryThresholdPct: Int = 20,
    /** Total-power threshold (Watts) above which the DRAW tile goes
     *  amber. Default 500 W catches a couple of active appliances. */
    val powerAmberThresholdW: Int = 500,
    /** Total-power threshold (Watts) above which the DRAW tile goes
     *  red. Default 2000 W = serious appliance running (kettle, oven,
     *  EV charger). */
    val powerRedThresholdW: Int = 2000,
    /** Max inline-alert previews under the dashboard's METRICS row. */
    val inlineAlertsCount: Int = 2,
    /** Max media-player rows shown when playing/paused. */
    val mediaSummaryCount: Int = 3,
    /**
     * Render order for the dashboard tile groups, top to bottom. Stored as a list
     * of string ids (matching the [DashboardTile] enum's `name`) rather than the
     * enum directly so an unknown id from a future build deserialises cleanly via
     * `ignoreUnknownKeys = true` and skips that entry. The Greeting always sits
     * first regardless of this list because it's the at-a-glance header; this
     * list controls the order of every section beneath it.
     */
    val tileOrder: List<String> = DEFAULT_TILE_ORDER,
) {
    companion object {
        /** Canonical default order — matches the layout shipped before custom
         *  reorder was introduced, so users who don't touch the setting see no
         *  change. [DashboardTile.entries] order also seeds the picker UI. */
        val DEFAULT_TILE_ORDER: List<String> = listOf(
            "WEATHER_PERSONS", "SUN_CALENDAR", "TIMERS", "MEDIA",
            "METRICS", "LOW_BATTERY", "INLINE_ALERTS",
        )
    }
}

/**
 * Re-orderable tile groups on the TODAY dashboard. Names persist in
 * [DashboardSettings.tileOrder] verbatim, so renaming an entry is a schema
 * change — add new entries by appending here (back-compat) rather than
 * renaming existing ones.
 *
 * Some entries are pair-cards (WEATHER_PERSONS, SUN_CALENDAR) that render two
 * tiles side-by-side on tablet width tiers; the visibility of each side is
 * still controlled by the per-card [DashboardSettings.showWeather] etc. flags.
 */
enum class DashboardTile(val label: String) {
    WEATHER_PERSONS("Weather + People"),
    SUN_CALENDAR("Sun + Next event"),
    TIMERS("Timers"),
    MEDIA("Now Playing"),
    METRICS("Metrics row"),
    LOW_BATTERY("Low-battery alerts"),
    INLINE_ALERTS("Inline alert previews"),
}

/** Stable ids for the top-level navigation destinations. Used as the keys in
 *  [NavPanelSettings.hiddenNavItems] so the persisted set is decoupled from the
 *  nav-route string constants. "home" and "settings" are intentionally absent:
 *  they are never hideable (there must always be a route home and to settings). */
object NavItemId {
    const val TODAY = "today"
    const val SEARCH = "search"
    const val ASSIST = "assist"

    /** The items a user is allowed to hide, in display order. */
    val HIDEABLE = listOf(TODAY, SEARCH, ASSIST)
}

/**
 * How the card-stack hamburger behaves on portrait phone tiers (R1 / COMPACT), where
 * there is no permanent side panel.
 *
 * [SLIDEOUT] (default) slides the same navigation panel the tablet rail / drawer shows
 * in from the leading edge, over the card stack. [MODAL] opens the full-screen
 * QuickActions sheet instead (the original phone behaviour). Both remain available: a
 * long-press on the hamburger always opens the QuickActions sheet regardless of this
 * setting, so the rich sheet is never out of reach.
 */
@kotlinx.serialization.Serializable
enum class PhoneNavStyle {
    SLIDEOUT,
    MODAL,
}

/**
 * Controls the side navigation panel. On MEDIUM+ window tiers AdaptiveNavShell renders
 * it as a permanent rail / drawer; on portrait phone tiers (R1 / COMPACT) the same panel
 * is available as a hamburger-triggered slide-out when [phoneNavStyle] is
 * [PhoneNavStyle.SLIDEOUT].
 *
 * [sidePanelEnabled] = false forces the no-panel passthrough layout on every tier,
 * reverting tablets to the card-stack experience (Settings stays reachable via the
 * card-stack chrome's gear) and disabling the phone slide-out (the hamburger then always
 * opens the QuickActions sheet). [hiddenNavItems] holds [NavItemId] values that should
 * be omitted from the panel when it is shown.
 */
@Immutable
@kotlinx.serialization.Serializable
data class NavPanelSettings(
    val sidePanelEnabled: Boolean = true,
    /** Phone-tier hamburger behaviour: slide-out panel (default) vs the QuickActions modal. */
    val phoneNavStyle: PhoneNavStyle = PhoneNavStyle.SLIDEOUT,
    val hiddenNavItems: Set<String> = emptySet(),
    /**
     * User-pinned surfaces shown in the side navigation rail / drawer, in display
     * order, BELOW the always-present core destinations (Home, Today, Search, Assist,
     * Settings). Each entry is a [com.github.itskenny0.r1ha.nav.Routes] string constant
     * resolved to a label + glyph via [com.github.itskenny0.r1ha.nav.PinnableSurfaces].
     * The user adds an entry by tapping the pin affordance in any pinnable surface's
     * top bar and removes it by tapping again (or via the side panel itself).
     *
     * Stored as a list (not a set) so the order the user pins surfaces in is the order
     * they appear. Unknown / future route ids decode cleanly (the registry lookup
     * simply skips them) so a settings blob from a newer build never crashes an older
     * one. Default is a small sensible starter set covering the most-reached surfaces.
     */
    val pinnedSurfaces: List<String> = DEFAULT_PINNED_SURFACES,
    /**
     * User-pinned Lovelace dashboard VIEWS, shown in the side navigation rail /
     * drawer (and the phone card-stack QuickActions drawer) BELOW the pinned
     * surfaces. Unlike [pinnedSurfaces] these aren't fixed nav surfaces — each is
     * a concrete dashboards-view route the user pinned from the dashboards list or
     * a dashboard view's top bar, carrying its own title (and optional mdi icon
     * slug) so the rail/drawer can label it without re-fetching the Lovelace config.
     *
     * Stored as a list so the user's pin order is the display order. Empty by
     * default (a fresh install has no dashboards pinned). Forward-compatible: an
     * unknown / future field on [PinnedDashboard] decodes cleanly via the
     * ignore-unknown-keys JSON config used for the whole struct.
     */
    val pinnedDashboards: List<PinnedDashboard> = emptyList(),
    /**
     * User-pinned HA sidebar panels shown in the side navigation rail / drawer
     * BELOW the pinned surfaces and dashboards. Each entry is a [PinnedPanel]
     * carrying the panel's url_path (the stable HA-assigned identifier) plus
     * a display title and optional MDI icon slug.
     *
     * Pins are discovered from the server's `get_panels` WS reply, filtered to
     * exclude panels R1HA renders natively (lovelace, config, energy, etc.), and
     * opened in the authenticated WebView when tapped. Empty by default: no panels
     * are pre-pinned because panel availability is install-specific and we cannot
     * know what custom integrations the user has installed.
     *
     * Forward-compatible: unknown fields on [PinnedPanel] decode cleanly via the
     * ignore-unknown-keys config, so a future extension (e.g. a "type" field) from
     * a newer build won't crash an older one during a backup restore.
     */
    val pinnedPanels: List<PinnedPanel> = emptyList(),
) {
    companion object {
        /** Sensible starter pins: the surfaces users reach most. Route-id strings
         *  rather than [com.github.itskenny0.r1ha.nav.Routes] references to keep this
         *  prefs module free of a nav dependency; they MUST match the Routes constants
         *  ("automations", "energy", "cameras", "areas", "logbook"). */
        val DEFAULT_PINNED_SURFACES: List<String> = listOf(
            "automations", "energy", "cameras", "areas", "logbook",
        )
    }
}

/**
 * One user-pinned Lovelace dashboard view (see [NavPanelSettings.pinnedDashboards]).
 *
 * [route] is the full concrete dashboards-view route built by
 * [com.github.itskenny0.r1ha.nav.Routes.dashboardsViewRoute] (so the shell / drawer
 * can navigate straight to it without re-deriving the path); it doubles as the stable
 * id the pin/unpin mutators key on. [title] is the view (or dashboard) title shown in
 * the rail / drawer / phone drawer. [icon] is an optional Material Design Icons slug
 * (e.g. "mdi:view-dashboard") carried from the view config when present; null falls
 * back to a generic dashboard glyph at render time.
 */
@Immutable
@kotlinx.serialization.Serializable
data class PinnedDashboard(
    val route: String,
    val title: String,
    val icon: String? = null,
)

/**
 * One user-pinned HA sidebar panel (see [NavPanelSettings.pinnedPanels]).
 *
 * [urlPath] is HA's stable panel identifier (e.g. "hacs", "esphome",
 * "zigbee2mqtt") and is the value used to build the panel URL at render time:
 * `<serverBase>/<urlPath>`. It doubles as the stable id the pin/unpin mutators
 * key on. [title] is the label shown in the rail / drawer; sourced from the
 * panel registration at pin time and refreshed on subsequent settings-open
 * fetches when the panel title changes. [icon] is an optional MDI slug from the
 * panel descriptor; null falls back to a generic glyph at render time.
 */
@Immutable
@kotlinx.serialization.Serializable
data class PinnedPanel(
    val urlPath: String,
    val title: String,
    val icon: String? = null,
)

/**
 * Connection-hardening preferences surfaced under Settings → Connection & server. These tune the
 * shared REST circuit breaker ([com.github.itskenny0.r1ha.core.ha.AuthThrottle]) and the polling
 * cadence so a strict Home Assistant install — one that IP-bans a device after a handful of failed
 * logins — doesn't get tripped when the app's session goes bad and every polling surface fires a
 * burst of 401s at once.
 *
 * Two tiers:
 *  - The breaker dials ([maxConcurrentRequests], [breakerFailureThreshold], [breakerCooldownSec],
 *    [maxAuthRetries]) apply on EVERY install. Their defaults are deliberately conservative (one
 *    request at a time, trip on the first 401) so a normal user is protected from the ban path
 *    without touching anything, while staying configurable for anyone who wants snappier fan-out.
 *  - The slowdown dials ([minCameraRefreshSec], [backgroundRefreshMultiplier]) only take effect
 *    when [strictMode] is on, because they trade freshness for fewer requests and most users
 *    shouldn't pay that cost unprovoked.
 *
 * See [com.github.itskenny0.r1ha.core.ha.ConnectionTuning] for the pure mapping from this struct
 * to the effective runtime values (which gates the slowdown dials behind [strictMode]).
 */
@Immutable
@kotlinx.serialization.Serializable
data class ConnectionSettings(
    /**
     * Master opt-in for strict connection mode. Off by default. When on, the Settings UI reveals
     * the full set of limiting dials and the two slowdown dials below begin to apply. The breaker
     * dials apply regardless of this flag — strict mode is about the extra, freshness-costing
     * limits, not about whether the breaker protects you.
     */
    val strictMode: Boolean = false,
    /**
     * Max gated REST/camera/image requests allowed in flight at once, per client. Lower means
     * fewer 401s can escape in a single burst before the breaker opens. Default 1 (one at a time)
     * is the safest setting and the reason a strict install stops getting banned; raise toward 4
     * for a snappier dashboard fan-out on a lax HA. Coerced to 1..8 by the tuning mapper.
     */
    val maxConcurrentRequests: Int = 1,
    /**
     * Number of auth failures (HTTP 401) inside the rolling window that trips the breaker.
     * Default 1 so the very first stale-token 401 short-circuits the rest of the burst. Raise to
     * tolerate a transient blip before the breaker engages. Coerced to 1..10.
     */
    val breakerFailureThreshold: Int = 1,
    /**
     * How long (seconds) the breaker stays open before admitting a single recovery probe. Grows
     * exponentially per consecutive reopen, so this is the FIRST cooldown, not the only one.
     * Default 15 s recovers a transient blip quickly; strict installs may prefer longer so a
     * genuinely-broken session reprobes less often. Coerced to 5..900.
     */
    val breakerCooldownSec: Int = 60,
    /**
     * Max consecutive auth-recovery attempts (token refresh + reconnect) before the app pauses
     * auto-retry and waits for a manual retry. Each attempt POSTs to `/auth/token`, which a strict
     * HA also counts, so capping this bounds the recovery-path request count too. Default 3;
     * strict installs may prefer 1-2. Coerced to 1..10. Only applied when [strictMode] is on
     * (a normal install keeps the repository's built-in cap).
     */
    val maxAuthRetries: Int = 2,
    /**
     * Floor (seconds) applied to every camera snapshot poll interval. The camera's own configured
     * cadence still wins when it is already slower than this. 0 disables the floor. Only applied
     * when [strictMode] is on — this is a freshness-for-requests trade. Default 20 s when strict.
     * Coerced to 0..120.
     */
    val minCameraRefreshSec: Int = 20,
    /**
     * Multiplier applied to background polling cadences — the WS-silent REST heartbeat and the
     * per-surface integration auto-refresh intervals. 1 = unchanged. Only applied when [strictMode]
     * is on. Default 2 (poll half as often) when strict. Coerced to 1..6.
     */
    val backgroundRefreshMultiplier: Int = 2,
)

/**
 * Per-surface refresh intervals + integration tweaks. Each value is
 * the auto-refresh period in seconds; 0 disables auto-refresh on
 * that surface entirely.
 *
 * Defaults match the hand-tuned cadences from the AutoRefresh
 * refactor — change them if you want quieter polling on a metered
 * connection or snappier updates on a fast LAN.
 */
@Immutable
@kotlinx.serialization.Serializable
data class IntegrationsSettings(
    val notificationsRefreshSec: Int = 30,
    val logbookRefreshSec: Int = 90,
    val personsRefreshSec: Int = 120,
    val weatherRefreshSec: Int = 300,
    val calendarsRefreshSec: Int = 300,
    /** Camera detail-overlay snapshot polling interval (seconds). */
    val cameraOverlayPollSec: Int = 4,
    /** Camera GRID tile snapshot polling interval (seconds). Slower
     *  by default because N tiles each polling at this cadence is
     *  N requests per interval. */
    val cameraGridPollSec: Int = 8,
    /** Default time window for the Logbook on entry (hours). 1 h: a busy
     *  install produces enough events per hour that the wider windows took
     *  noticeably long to fetch and parse on entry, and the 12 h / 24 h /
     *  3 d / 7 d chips stay one tap away. Users who explicitly set a wider
     *  default keep it (the stored value wins over this constant). */
    val logbookDefaultWindowHours: Int = 1,
    /** Camera grid default — open in GRID view rather than LIST. Off
     *  by default because the polling stampede on big installs
     *  surprised early testers. */
    val camerasDefaultGrid: Boolean = false,
    /** Universal Search result cap. Higher = scroll further on a big
     *  install; lower = snappier on a slow renderer. */
    val searchResultCap: Int = 80,
    /** In-memory RECENT history size for Templates / Service Caller. */
    val recentHistoryDepth: Int = 5,
    /** Calendar drill-down — how many days ahead to fetch from
     *  /api/calendars. */
    val calendarLookaheadDays: Int = 14,
    /**
     * Opt-in: mirror this device's settings to/from Home Assistant so multiple
     * R1 / phone installs sharing the same HA user converge on the same
     * preferences (theme, card stack pages + favourites, name + entity
     * overrides, key bindings, etc.). Storage is HA's per-user
     * `frontend/set_user_data` bucket — no add-on or custom integration to
     * install. Off by default so single-device installs incur zero WS chatter.
     *
     * Device-local fields (server URL + tokens, iBeacon major/minor/UUID,
     * webhook port + id, MQTT host + auth) are NEVER synced regardless of
     * this toggle — every R1 keeps its own network identity.
     */
    val haSyncEnabled: Boolean = false,
    /** How often to pull the latest snapshot from HA when sync is on, in
     *  seconds. Pushes on local edits fire independently (debounced ~5 s).
     *  300 s = 5 min default; the user can dial down on a busy multi-device
     *  household or up on a quiet one. Range coerced to 30..3600 by the
     *  writer so a stray 0 doesn't burst-pull. */
    val haSyncIntervalSec: Int = 300,
    /**
     * Manual-only sync mode. When true, the periodic pull AND the
     * automatic push-on-edit are both suppressed — sync only runs when
     * the user explicitly taps PULL NOW / PUSH NOW from the Sync
     * settings screen. Useful for users who want sync available (manual
     * import from another device, manual checkpoint of a known-good
     * config) without the per-edit network chatter or the periodic
     * background fetch.
     *
     * No effect when [haSyncEnabled] is false (sync is off entirely).
     */
    val haSyncManualOnly: Boolean = false,
    /**
     * One-shot flag flipped true after the user has seen (and either accepted
     * or dismissed) the HA-sync first-run prompt. Off by default so existing
     * installs surface the prompt once after upgrading to a build that ships
     * this feature; once true, the prompt never re-fires (the user can still
     * flip [haSyncEnabled] manually from Settings → Integrations).
     */
    val haSyncPromptSeen: Boolean = false,
    /**
     * Sync categories the user has explicitly opted OUT of. Stored as
     * [com.github.itskenny0.r1ha.core.sync.SyncCategory] enum names.
     * Adding an entry preserves that category's local values across pull/push
     * cycles (Sync UI surfaces this as a switch per category).
     *
     * Default: `WHEEL_INPUT` is excluded. Wheel step size, acceleration
     * curve, invert direction, and key bindings are physical preferences
     * that vary device-to-device — a hardware wheel on the R1 wants a
     * different step than a fingertip-driven phone install, and key bindings
     * tend to be tied to the specific hardware buttons available. Users who
     * want to share these can flip the WHEEL_INPUT switch back on in
     * Settings → Sync (or during the onboarding "pick what to sync" step).
     *
     * Unknown names (added/removed across versions) are silently ignored by
     * the sync filter, so a future build that introduces a new category
     * decodes old settings cleanly.
     */
    val haSyncExcludedCategories: Set<String> = setOf("WHEEL_INPUT"),
)

/**
 * Knobs surfaced through the dev menu (About → Dev menu). Most are wired into real
 * code paths; a handful are placeholders for future feature flags so the dev menu
 * has enough to feel like a real diagnostic surface rather than a placeholder
 * screen. Treat unfamiliar fields as 'reserved for future use' rather than fully
 * exercised — the dev menu is for power users diagnosing live behaviour.
 */
@Immutable
@kotlinx.serialization.Serializable
data class AdvancedSettings(
    /** Trailing-edge debounce window for service calls. Lower = faster wire updates
     *  during in-flight gestures, higher = fewer HA round-trips. */
    val serviceDebounceMs: Int = 60,
    /** Force-fire window — submit calls hold at most this long during a continuous
     *  gesture before the latest value gets flushed to HA. */
    val serviceMaxIntervalMs: Int = 150,
    /** Sliding-window for wheel rate (events/sec) used by the acceleration ramp. */
    val wheelRateWindowMs: Int = 250,
    /** Maximum 'cards per wheel detent' clamp for the nav acceleration ramp. */
    val navAccelCap: Int = 8,
    /** Long-press threshold (ms) for the drag-reorder gesture and other long-press
     *  affordances. Compose default is 500 ms; some users want snappier. */
    val longPressMs: Int = 500,
    /** Hours of history fetched by the sensor card. */
    val sensorHistoryHours: Int = 24,
    /** Cap on reconnect backoff exponent. WS reconnect doubles each failure up to
     *  this many seconds between attempts. */
    val reconnectBackoffMaxSec: Int = 30,
    /** Override the WebSocket ping interval (seconds). Used to keep the WS warm on
     *  flaky networks. 0 = use OkHttp default (30 s). */
    val wsPingIntervalSec: Int = 0,
    /** REST timeout for /api/states + /api/history (seconds). */
    val restTimeoutSec: Int = 30,
    /** When on, R1Log entries also append to a process-scope ring buffer that's
     *  surfaced in the dev menu's log viewer. Always on currently — flip to off if
     *  the buffer's GC pressure ever becomes a concern on the R1's tight heap. */
    val keepLogBuffer: Boolean = true,
    /** When on, the picker drops rows that fail to construct an EntityState rather
     *  than logging at WARN and continuing. Off (the lenient default) is friendlier
     *  for diagnosing 'where's my entity?' issues. */
    val strictEntityDecode: Boolean = false,
    /** When on, the optimistic UI override never auto-clears — useful for debugging
     *  the reconcile path. */
    val pinOptimistic: Boolean = false,
    /** When on, swipes between cards animate longer for a more 'physical' feel. */
    val slowPagerTransitions: Boolean = false,
    /** Show the entity_id below the friendly name on every card. */
    val showEntityIdOnCards: Boolean = false,
    /** Log every HA service-call payload at INFO so the toast feed shows them. */
    val verboseServiceCalls: Boolean = false,
    /** Verbose HTTP logging — every REST request/response is logged via R1Log. */
    val verboseHttp: Boolean = false,
    /** Verbose WS — every inbound/outbound frame is logged at DEBUG. Off in
     *  release-style builds because the volume is enormous on busy HA installs. */
    val verboseWebSocket: Boolean = false,
    /** Bypass the pre-emptive token-refresh before REST calls. Off (refresh
     *  attempted) is the friendly default; on lets developers test the 401-retry
     *  self-heal path in isolation. */
    val skipPreflightRefresh: Boolean = false,
    /** Treat any HA service-call rejection as if the optimistic UI override should
     *  STAY (rather than rolling back). Useful when HA's reject behaviour is
     *  flaky. */
    val keepOptimisticOnFailure: Boolean = false,
    /** Show a small per-card debug strip in the bottom-right with the cached
     *  percent / supportsScalar / raw state. */
    val showDebugStripOnCards: Boolean = false,
    /**
     * Opt-in: persist the HA entity cache to disk so the card stack paints
     * with last-known state at cold start, before the WS even connects.
     * Disabled by default while the rehydrate path is being hardened — an
     * early-2026 build had it on by default and a crash report came in
     * that pointed at the rehydrated-entity-with-null-fields surface. The
     * file is small (~5 KB / 50 entities) and self-healing on schema
     * mismatch; users who want the cold-start speedup can opt in here.
     */
    val persistCacheToDisk: Boolean = false,
    /**
     * Opt-in: allow third-party automation apps (Tasker, MacroDroid, Automate)
     * to fire HA service calls through this app by broadcasting
     * [com.github.itskenny0.r1ha.core.extern.AutomationReceiver.ACTION_HA_SERVICE_CALL].
     * Off by default because every installed app on the device can broadcast
     * intents — flipping it on widens the attack surface from "this app's UI"
     * to "anyone with the action string and the right domain/service extras."
     * Power-user feature; documented in the dev menu so the casual user never
     * accidentally exposes their HA install.
     */
    val externalAutomationEnabled: Boolean = false,
    /**
     * Opt-in: schedule a periodic JobService that warms the HA entity cache via
     * /api/states every ~15 minutes (the Android platform's minimum periodic
     * interval). Off by default because the foreground card stack already
     * keeps the cache current via the WS; the background job mostly benefits
     * Quick Tile freshness and the cold-start paint after device sleep.
     * Disabling cancels the scheduled job on the next App.onCreate cycle.
     */
    val backgroundRefreshEnabled: Boolean = false,
    /**
     * Opt-in: mirror HA's persistent notifications into the Android system
     * notification shade so they're visible without opening the app. Posts one
     * Android notification per HA notification id; the DISMISS action button
     * fires persistent_notification.dismiss against the server.
     *
     * Off by default — pre-empts permission-prompt fatigue for users who never
     * intended to use the feature. Android 13+ asks for POST_NOTIFICATIONS at
     * runtime the first time the user flips this on.
     */
    val mirrorHaNotifications: Boolean = false,
    /**
     * Opt-in: when the app is in the foreground, engage NFC reader mode and
     * fire HA's `tag_scanned` event with the tag UID as `tag_id`. Useful for
     * NFC-trigger automations the user has configured server-side.
     *
     * Foreground-only by design — we don't register the manifest's
     * ACTION_NDEF_DISCOVERED filter because that would compete with the user's
     * existing tag-scan handler (Companion app, NFC Tools, etc.) and force a
     * "choose which app" prompt on every tag tap.
     */
    val nfcTagScannerEnabled: Boolean = false,
    /**
     * Opt-in: broadcast an iBeacon advertisement so HA's iBeacon integration
     * picks the device up as a device_tracker for presence / proximity
     * automations. The advertised UUID + major + minor identify the device
     * to HA; you'll typically use the same UUID across an HA install and
     * differentiate devices by major/minor.
     *
     * Off by default — advertising consumes a bit of battery (low-latency
     * mode at high TX power) and requests the BLUETOOTH_ADVERTISE runtime
     * permission on Android 12+ the first time it's enabled.
     */
    val iBeaconEnabled: Boolean = false,
    /** 128-bit beacon UUID, formatted per RFC 4122. The HA iBeacon
     *  integration expects this verbatim; any random UUID works as long as
     *  HA's configured filter matches. Default is a benign placeholder; the
     *  user is expected to override. */
    val iBeaconUuid: String = "12345678-1234-1234-1234-123456789abc",
    /** uint16, 0..65535. Differentiates beacons sharing a UUID (e.g. one R1
     *  per room). */
    val iBeaconMajor: Int = 1,
    /** uint16, 0..65535. Sub-differentiator under [iBeaconMajor]. */
    val iBeaconMinor: Int = 1,
    /**
     * Opt-in: run a small HTTP listener so HA can fire webhooks at the device.
     * The listener binds to all interfaces on [webhookPort] and responds to
     * POST `/webhook/<webhookId>`; the body is surfaced as an expandable toast.
     * Off by default — opening a port + holding a foreground-service notification
     * is the kind of thing the user should opt into knowing why.
     */
    val webhookEnabled: Boolean = false,
    /** TCP port to listen on. 1024-65535 to stay in the unprivileged range. */
    val webhookPort: Int = 8765,
    /** Path id — HA's webhook automation fires at `/webhook/<this>`. Plain
     *  ASCII (letters / digits / dash / underscore); shorter is friendlier in
     *  the HA UI. */
    val webhookId: String = "r1",
    /**
     * MQTT broker host. Empty disables the publish action entirely. We don't
     * run a long-lived MQTT client — the publish surface in the dev menu and
     * any future automation use the bare-bones one-shot publish in
     * [com.github.itskenny0.r1ha.core.mqtt.MqttPublisher]. Plain TCP by default;
     * flip [mqttUseTls] for an SSL socket on the usual 8883.
     */
    val mqttHost: String = "",
    val mqttPort: Int = 1883,
    /** Optional broker auth — username + password sent in the CONNECT packet.
     *  Empty strings = anonymous (the broker decides whether to accept). */
    val mqttUsername: String = "",
    val mqttPassword: String = "",
    /** When on, wrap the socket in the default SSLSocketFactory. No client cert
     *  support — the HA REST mTLS plumbing isn't shared with MQTT yet. */
    val mqttUseTls: Boolean = false,
    /** Client id sent in CONNECT. Empty = auto-generated per publish (no
     *  session continuity needed; we're publish-only QoS-0). */
    val mqttClientId: String = "",
)

@Immutable
data class ServerConfig(
    val url: String,
    val haVersion: String? = null,
)

/**
 * IoT Camera Mode — turn this device into a camera entity that Home Assistant
 * can render in its frontend. Two parallel sinks, each independently
 * toggleable so the user can run MJPEG without MQTT or vice versa:
 *
 *   - MJPEG: an HTTP server on the device exposes `multipart/x-mixed-replace`
 *     at `http://device:<port>/stream` and a single-frame JPEG at `/snapshot`.
 *     HA's `generic` camera platform consumes the stream URL; users add it
 *     once and HA paints a true low-latency live view as long as the device
 *     is reachable on the LAN. Always Basic-auth'd — the device is a camera,
 *     the LAN is not always trusted.
 *
 *   - MQTT: with auto-discovery on, the device publishes a one-shot retained
 *     config payload to `<discoveryPrefix>/camera/<uniqueId>/config` so HA
 *     auto-registers the camera entity, then publishes raw JPEG bytes per
 *     frame to the image topic. Works through NAT (device is the publisher),
 *     trades latency for reach — practical at the few-fps end of the dial.
 *
 * Everything in this struct is per-device: ports, credentials, lens choice,
 * broker auth are all things you tune for the hardware in front of you, not
 * mirror across installs. The whole block is excluded from [AppBackup] /
 * settings sync for that reason (an MJPEG password leaving the device would
 * be a real security regression).
 *
 * Default is OFF across the board: enabling consumes the camera + opens a
 * foreground notification + binds a TCP port, so the user should opt in
 * deliberately. Sub-toggles default off so an over-eager master flip doesn't
 * spray frames at a broker the user hasn't configured yet.
 */
@Immutable
@kotlinx.serialization.Serializable
data class IotCameraSettings(
    /** Master switch. Off → service stops, camera released, sinks torn down. */
    val enabled: Boolean = false,
    /**
     * Camera2 logical-camera id selected by the user. Empty = pick the first
     * back-facing camera at start time. Identifiers are the `cameraIdList`
     * strings produced by the device's `CameraManager`; they're stable across
     * boots on a given device but vary across OEMs so we don't hardcode.
     *
     * Multi-camera devices (phones with wide + tele + ultrawide on the back,
     * tablets with stereo fronts) expose every lens as its own logical id;
     * the settings picker walks the list with friendly labels so the user
     * can pick the exact lens rather than just FRONT/BACK.
     */
    val cameraId: String = "",
    /** Output JPEG width (px). The picker offers the configured camera's
     *  supported output sizes; freeform integer here so a future build that
     *  expands the picker doesn't need a schema change. */
    val width: Int = 1280,
    val height: Int = 720,
    /**
     * Target frames-per-second. Combined with width/height/quality this is
     * effectively the bitrate dial: the encoder runs at this rate, the sinks
     * fan out each encoded frame. No upper cap — the user asked for full
     * control over what the hardware will attempt, even on R1 (which will
     * heat up at 30 fps but that's their call).
     */
    val fps: Int = 10,
    /** JPEG quality 1..100 — the second half of the bitrate dial. 70 is a
     *  reasonable visual/byte trade-off for surveillance-ish streams. */
    val jpegQuality: Int = 70,
    /**
     * Sender-side rotation applied to every encoded frame before fan-out
     * to sinks. Multiples of 90 (0 / 90 / 180 / 270). Useful when the
     * device is physically mounted upside down or on its side and you'd
     * rather burn the rotation in at the source than ask every HA viewer
     * to compensate.
     *
     * Cost is a per-frame decode + Matrix transform + re-encode at high
     * rotation; 0 is the no-op fast path. Users who care about peak fps
     * should leave this at 0 and rotate at the consumer instead (the
     * Cameras viewer has its own per-view rotate button).
     */
    val rotationDegrees: Int = 0,

    /** MJPEG sink: HTTP server with optional Basic auth on the device. */
    val mjpegEnabled: Boolean = false,
    /** Unprivileged TCP port. 8181 to stay clear of the webhook default
     *  (8765) and the usual web/dev ports. */
    val mjpegPort: Int = 8181,
    /**
     * Require HTTP Basic authentication on every request. Default ON
     * because the LAN isn't always trusted — a shared Wi-Fi network turns
     * an unauthenticated stream into a privacy regression. Turning it off
     * is supported (some users want an open URL HA can hit without
     * embedding credentials, or a dashboard tile that can't store auth);
     * we just don't recommend it.
     */
    val mjpegAuthEnabled: Boolean = true,
    /** Basic-auth credentials surfaced in HA's generic camera URL field. The
     *  password is generated on first enable (so a freshly-flipped install
     *  isn't broadcasting an open camera onto the LAN) and the user can
     *  rotate it from the settings screen. Ignored when [mjpegAuthEnabled]
     *  is off. */
    val mjpegUsername: String = "r1ha",
    val mjpegPassword: String = "",

    /** MQTT sink: publish frames to a topic with HA auto-discovery config. */
    val mqttEnabled: Boolean = false,
    /** Broker config — reuses the same MQTT host/port/auth as the existing
     *  publish surface (under Advanced → MQTT) so the user configures the
     *  broker once. This struct only carries the camera-specific bits below;
     *  the actual `mqttHost`/`mqttPort` etc. live on [AdvancedSettings]. */
    val mqttDiscoveryPrefix: String = "homeassistant",
    /** Stable id under the discovery prefix. Combined with [mqttObjectId] to
     *  form the discovery topic; defaults to a per-install random suffix so
     *  two devices on the same broker don't collide unless the user
     *  explicitly aligns them. Materialised on first enable. */
    val mqttNodeId: String = "",
    val mqttObjectId: String = "camera",
    /** Friendly name HA shows for the auto-discovered entity. Empty =
     *  derive from `Build.MODEL` at start time. */
    val entityName: String = "",
)

/**
 * One tab on the card stack — a named page of entity IDs that get rendered as a
 * vertical deck of cards. The user can swipe left/right between pages to switch
 * decks; within a deck, swipe up/down navigates cards as before. Pages let users
 * organise larger HA installs by room / scenario / time-of-day without all the
 * favourites collapsing into one long scroll.
 *
 * Identity is by [id] (a stable random string), not by [name] — renaming a page
 * doesn't reset its order or contents. [favorites] is a list of HA entity IDs in
 * the user's desired display order, identical in shape to the legacy single-
 * page [AppSettings.favorites] list it migrates from.
 */
@Immutable
@kotlinx.serialization.Serializable
data class FavoritePage(
    val id: String,
    val name: String,
    val favorites: List<String> = emptyList(),
    /** Optional per-page accent colour as an ARGB int. Null = inherit the
     *  global warm accent. Painted onto the active tab chip and (future) any
     *  page-scoped chrome. Defaulted nullable + additive so older settings
     *  blobs deserialize without migration. */
    val accentArgb: Int? = null,
    /** Optional per-page icon — single Unicode glyph rendered before the page
     *  name in the tab strip. Null = no icon, just the name. Picked from a
     *  curated preset list in [TabManageDialog]; storing as String rather
     *  than a constrained type means a future build can add new presets
     *  without a schema bump. Additive + nullable for back-compat. */
    val icon: String? = null,
)

/**
 * @Immutable: every field is `val` and the nested data classes are themselves
 * @Immutable. Tells Compose to use equals() for recomposition skipping rather
 * than the conservative default that treats the Map fields as unstable.
 * Without this, every screen reading `appSettings by collectAsStateWithLifecycle`
 * was force-recomposing on every settings flow emission even when its slice
 * (e.g. just `appSettings.wheel.acceleration`) hadn't changed.
 */
@Immutable
data class AppSettings(
    val server: ServerConfig? = null,
    /**
     * Legacy single-page favourites list. Pre-tabs builds wrote here directly. New
     * builds keep this as a flat union of every page's [FavoritePage.favorites]
     * so any code path that still reads [favorites] (About, picker filters that
     * predate the schema, etc.) sees a coherent list without needing to know
     * about pages. The authoritative source is [pages]; this field is derived
     * from it on every save.
     */
    val favorites: List<String> = emptyList(),
    /**
     * Tabs on the card stack — at least one page is always present (the migration
     * path materialises a 'HOME' page from legacy [favorites] on first read).
     * Empty in storage triggers the migration; the [SettingsRepository] flow
     * never emits an [AppSettings] with an empty pages list.
     */
    val pages: List<FavoritePage> = emptyList(),
    /** [FavoritePage.id] of the currently-displayed tab, persisted so reopening the
     *  app lands on the user's last-viewed page. Falls back to the first page on
     *  load when the saved id no longer exists. */
    val activePageId: String = "",
    val wheel: WheelSettings = WheelSettings(),
    val ui: UiOptions = UiOptions(),
    val behavior: Behavior = Behavior(),
    val theme: ThemeId = ThemeId.PRAGMATIC_HYBRID,
    /**
     * Time-of-day automatic theme switching. When [autoThemeEnabled] is true the
     * app uses [theme] during the day and [nightTheme] between [nightStartHour]
     * and [nightEndHour] (24 h, local time). Defaults match the convention of
     * "darker UI after 10 PM, normal UI from 6 AM" — most kiosk users want the
     * minimal-dark theme overnight so the wall-mounted R1 doesn't light up the
     * room while no-one's looking at it.
     */
    val autoThemeEnabled: Boolean = false,
    val nightTheme: ThemeId = ThemeId.MINIMAL_DARK,
    /** Hour (0..23 local) at which the night theme begins. Default 22 (10 PM). */
    val nightStartHour: Int = 22,
    /** Hour (0..23 local) at which the day theme resumes. Default 6 (6 AM). */
    val nightEndHour: Int = 6,
    /**
     * Optional global accent colour override (ARGB int). When set, replaces
     * every theme's domain-derived accent role (WARM / COOL / GREEN /
     * NEUTRAL) with this single colour. Individual cards can still override
     * via [EntityOverride.accentColor]. Null = use the theme's native
     * accent palette unchanged. Lets the user re-skin a theme without
     * editing one card at a time.
     */
    val themeAccentArgb: Int? = null,
    /**
     * Read-only "guest" mode. When true, the app refuses every outbound
     * service call (lights, switches, locks, media transport, scripts) and
     * surfaces a small banner so the user knows why. State observation
     * keeps working; only the dispatch path is gated. Toggleable from
     * Settings; persisted alongside the other behaviour flags so a guest
     * handing the device back doesn't have to remember.
     */
    val guestModeEnabled: Boolean = false,
    /**
     * Client-side display-name overrides keyed by entity_id. When present, the UI prefers
     * this label to HA's `friendly_name` for that entity. Persistent (lives in DataStore)
     * but never synced back to HA — the override is local-only so users can disambiguate
     * "Office light strip front" vs "back" without touching their HA setup.
     */
    val nameOverrides: Map<String, String> = emptyMap(),
    /** Per-entity card customization (text scale, visibility toggles, long-press action).
     *  Independent of [nameOverrides] so the rename feature (shipped earlier) keeps its
     *  storage format untouched. */
    val entityOverrides: Map<String, EntityOverride> = emptyMap(),
    /** Power-user knobs surfaced via About → Dev menu. */
    val advanced: AdvancedSettings = AdvancedSettings(),
    /** Per-section dashboard visibility + thresholds. */
    val dashboard: DashboardSettings = DashboardSettings(),
    /** Large-screen side navigation panel enable + per-item visibility. */
    val navPanel: NavPanelSettings = NavPanelSettings(),
    /** Per-surface refresh intervals + integration tuning. */
    val integrations: IntegrationsSettings = IntegrationsSettings(),
    /** Circuit-breaker + polling-cadence hardening so a strict HA doesn't IP-ban the device. */
    val connection: ConnectionSettings = ConnectionSettings(),
    /**
     * User-configurable hardware-key bindings. Keys are
     * [com.github.itskenny0.r1ha.core.input.KeyAction] enum names (string form so an
     * unknown action from a future build decodes as no-op rather than crashing);
     * values are Android `KeyEvent.KEYCODE_*` integer codes. Empty map = use the
     * built-in [com.github.itskenny0.r1ha.core.input.DEFAULT_KEY_BINDINGS]; a present
     * (possibly empty) value for a given action overrides the default for that
     * specific action — letting the user explicitly clear a default they don't
     * want without resetting everything else.
     *
     * Persisted as JSON in DataStore (`keybindings.json`). Surfaced in
     * Settings → Behaviour → Key bindings with a press-to-bind dialog
     * that captures the next physical key press via [KeyCaptureBus].
     */
    val keyBindings: Map<String, List<Int>> = emptyMap(),
    /**
     * IoT Camera Mode — turn the device's camera into an HA camera entity
     * via MJPEG over HTTP and/or MQTT auto-discovery. Per-device by design
     * (ports + credentials + lens choice are physical) so this block is
     * excluded from the sync payload + portable [AppBackup] — copying an
     * MJPEG password between devices would be a regression.
     */
    val iotCamera: IotCameraSettings = IotCameraSettings(),
    /**
     * IoT Sensors Mode — expose device hardware (battery, light, accelerometer-
     * derived vibration) and a handful of controls (flashlight, brightness,
     * volume, lock screen) to HA via MQTT auto-discovery. Per-device for the
     * same reasons as [iotCamera]: physical hardware varies and the node id
     * shouldn't collide if the user runs the app on multiple devices against
     * the same broker.
     */
    val iotSensors: IotSensorsSettings = IotSensorsSettings(),
    /**
     * Round-robin cursor for the Settings "Featured" spotlight. The Settings root
     * advances it by the featured-group size (modulo the catalogue size) once per
     * launch and persists the result, so each launch deterministically shows the
     * next group. Absent defaults to 0 (migration-safe: a fresh install or a
     * pre-feature upgrade starts at the first group).
     */
    val featuredRotationIndex: Int = 0,
)

/**
 * IoT Sensors Mode config. Off by default — turning it on starts a foreground
 * service that opens an MQTT session, publishes HA auto-discovery payloads for
 * each enabled entity, and registers SensorManager listeners + a battery
 * BroadcastReceiver. Each sensor and control is its own opt-in so a user who
 * only wants the battery on HA doesn't end up with five extra entities.
 *
 * Reuses the same broker config as [IotCameraSettings] (read from
 * [AdvancedSettings.mqttHost] etc.) so the broker only has to be configured
 * once for both features.
 */
@Immutable
@kotlinx.serialization.Serializable
data class IotSensorsSettings(
    val enabled: Boolean = false,
    /** Stable id under the discovery prefix — kept in sync with the camera's
     *  [IotCameraSettings.mqttNodeId] when both features are on so HA groups
     *  all entities under one device. Materialised on first enable. */
    val nodeId: String = "",
    val discoveryPrefix: String = "homeassistant",
    /** How often to push read-only sensor values to the broker. Battery + WiFi
     *  rarely change; light + vibration are event-driven and bypass this. */
    val publishIntervalSec: Int = 60,
    // ── Read-only sensors ───────────────────────────────────────────────
    val publishBattery: Boolean = true,
    val publishCharging: Boolean = true,
    val publishLightSensor: Boolean = true,
    /** Software vibration detector — high-passes the accelerometer and
     *  fires a binary_sensor when magnitude exceeds [vibrationThresholdG].
     *  Off by default because a phone-on-a-desk reports constant micro-
     *  vibrations that would spam HA without a tuned threshold. */
    val publishVibration: Boolean = false,
    val publishScreenOn: Boolean = true,
    /** Off by default — SSID is privacy-sensitive (reveals home location). */
    val publishWifiSsid: Boolean = false,
    // ── Controllable entities (HA → device) ────────────────────────────
    val controlFlashlight: Boolean = true,
    val controlBrightness: Boolean = true,
    val controlVolume: Boolean = true,
    /** Lock screen button — defaults OFF because it requires the Device
     *  Admin grant, which is a high-friction permission the user should
     *  opt into deliberately. */
    val controlLockScreen: Boolean = false,
    /** Vibration threshold in g (1g = 9.81 m/s²). Raise to suppress
     *  false positives from desk taps; lower to catch gentler shakes. */
    val vibrationThresholdG: Float = 1.5f,
)
