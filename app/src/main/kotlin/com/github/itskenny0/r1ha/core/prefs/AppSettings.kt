package com.github.itskenny0.r1ha.core.prefs

import androidx.compose.runtime.Stable

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
 * Display unit for temperature readouts. AUTO follows HA's reported unit
 * (`temperature_unit` attribute on climate entities, defaults to Celsius); CELSIUS and
 * FAHRENHEIT force the display + conversion regardless of HA's setting.
 */
enum class TemperatureUnit { AUTO, CELSIUS, FAHRENHEIT }

/** What the wheel keycodes actually arrive as on this device. */
enum class WheelKeySource { AUTO, DPAD, VOLUME }

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
enum class ChromeButtonRef { BATTERY, ASSIST_MIC, EDIT, GEAR }

/**
 * Per-button configuration for the chrome row's right cluster. The list order
 * in [UiOptions.chromeButtons] is the render order (left → right); each entry's
 * [enabled] decides whether the button renders at all. GEAR is forced-on by the
 * settings UI so the user can never lose their way back to Settings.
 */
@Stable
@kotlinx.serialization.Serializable
data class ChromeButtonConfig(
    val ref: ChromeButtonRef,
    val enabled: Boolean = true,
)

@Stable
data class WheelSettings(
    val stepPercent: Int = 2,           // 1, 2, 5, or 10
    val acceleration: Boolean = true,
    val invertDirection: Boolean = false,
    val keySource: WheelKeySource = WheelKeySource.AUTO,
    /** Slope of the acceleration curve when [acceleration] is on. */
    val accelerationCurve: AccelerationCurve = AccelerationCurve.MEDIUM,
)

@Stable
data class UiOptions(
    val displayMode: DisplayMode = DisplayMode.PERCENT,
    val showOnOffPill: Boolean = true,
    val showAreaLabel: Boolean = true,
    val showPositionDots: Boolean = true,
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
)

@Stable
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
@Stable
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
@Stable
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
    /** Default time window for the Logbook on entry (hours). The
     *  user can still flip between 12 h / 24 h / 3 d window chips. */
    val logbookDefaultWindowHours: Int = 12,
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
)

/**
 * Knobs surfaced through the dev menu (About → Dev menu). Most are wired into real
 * code paths; a handful are placeholders for future feature flags so the dev menu
 * has enough to feel like a real diagnostic surface rather than a placeholder
 * screen. Treat unfamiliar fields as 'reserved for future use' rather than fully
 * exercised — the dev menu is for power users diagnosing live behaviour.
 */
@Stable
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
)

@Stable
data class ServerConfig(
    val url: String,
    val haVersion: String? = null,
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
@Stable
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
 * @Stable: every field is `val` and the nested data classes are themselves
 * @Stable. Tells Compose to use equals() for recomposition skipping rather
 * than the conservative default that treats the Map fields as unstable.
 * Without this, every screen reading `appSettings by collectAsStateWithLifecycle`
 * was force-recomposing on every settings flow emission even when its slice
 * (e.g. just `appSettings.wheel.acceleration`) hadn't changed.
 */
@Stable
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
    /** Per-surface refresh intervals + integration tuning. */
    val integrations: IntegrationsSettings = IntegrationsSettings(),
)
