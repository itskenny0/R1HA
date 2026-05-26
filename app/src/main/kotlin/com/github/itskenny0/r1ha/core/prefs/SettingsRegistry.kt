package com.github.itskenny0.r1ha.core.prefs

/**
 * Catalogue of user-facing settings entries, used by:
 *   - the "Modified settings" diff subscreen, which iterates this list and shows
 *     entries where the current value differs from the constructor-default;
 *   - the Settings search overlay, which fuzzy-matches user queries against each
 *     entry's [label] and [description].
 *
 * Keeping this metadata separate from the [AppSettings] tree (rather than as
 * annotations on the data-class fields) means:
 *   1. The data classes stay pure plain-old-Kotlin without runtime metadata
 *      reflection, which keeps R8 minification happy in release builds.
 *   2. Adding a brand-new setting to [AppSettings] without registering it here
 *      is a no-op for the diff / search surfaces — it just doesn't appear there
 *      until someone deliberately wires the registry entry, which is the right
 *      trade for catching incompletely-surfaced new settings during code review.
 *   3. The label / description copy that the user actually sees lives next to
 *      the search-index entry, not buried in the data-class KDoc that the user
 *      never reads.
 *
 * If a setting is missing from this registry, the diff / search surfaces won't
 * show it; they're best-effort, not exhaustive. Add an entry whenever a new
 * user-facing setting lands.
 */

/**
 * Coarse buckets used to group entries in the diff panel and to scope the
 * Settings search results. These mirror the section headers on the existing
 * flat Settings screen so the same mental model carries across.
 */
enum class SettingCategory(val label: String) {
    SERVER("Server"),
    INPUT("Scroll wheel"),
    CARD_UI("Card UI"),
    BEHAVIOUR("Behaviour"),
    APPEARANCE("Appearance"),
    INTEGRATIONS("Integrations"),
    DASHBOARD("Dashboard"),
    // DATA intentionally absent: backup/restore is actions not settings. A
    // test asserts every category is populated, so a drive-by enum addition
    // without a matching entry won't merge.
}

/**
 * One user-facing setting, described once and consumed by every diff / search
 * surface that needs to enumerate settings.
 *
 * [isDefault] runs against the live [AppSettings] tree and returns true when
 * the current value matches the value [AppSettings] gives without any
 * customisation. The diff panel filters by `!isDefault(current)` to show only
 * modified entries.
 *
 * [currentDisplay] renders the setting's current value as a single short
 * string suitable for a row's right-edge value chip. Returning empty is fine
 * for switches whose status is already implied by 'present in the diff list =
 * not default'; the diff panel may still render the empty string verbatim, so
 * keep the value short.
 */
data class SettingEntry(
    val id: String,
    val category: SettingCategory,
    val label: String,
    val description: String,
    val isDefault: (AppSettings) -> Boolean,
    val currentDisplay: (AppSettings) -> String,
)

private val defaults = AppSettings()

/**
 * Curated list of user-facing settings. Order roughly follows the current
 * Settings screen's section order so the diff panel reads top-to-bottom in a
 * familiar shape.
 */
val SETTINGS_REGISTRY: List<SettingEntry> = listOf(
    // ── Server ──────────────────────────────────────────────────────────
    SettingEntry(
        id = "server.url",
        category = SettingCategory.SERVER,
        label = "Server URL",
        description = "Home Assistant base URL",
        isDefault = { it.server?.url == defaults.server?.url },
        currentDisplay = { it.server?.url ?: "(none)" },
    ),

    // ── Theme ───────────────────────────────────────────────────────────
    SettingEntry(
        id = "theme",
        category = SettingCategory.APPEARANCE,
        label = "Theme",
        description = "Card-stack visual theme",
        isDefault = { it.theme == defaults.theme },
        currentDisplay = { it.theme.name.lowercase().replace('_', ' ') },
    ),
    SettingEntry(
        id = "theme.autoEnabled",
        category = SettingCategory.APPEARANCE,
        label = "Auto night theme",
        description = "Swap to a different theme between the configured night hours",
        isDefault = { it.autoThemeEnabled == defaults.autoThemeEnabled },
        currentDisplay = { if (it.autoThemeEnabled) "ON" else "OFF" },
    ),
    SettingEntry(
        id = "theme.night",
        category = SettingCategory.APPEARANCE,
        label = "Night theme",
        description = "Theme used while inside the night window",
        isDefault = { it.nightTheme == defaults.nightTheme },
        currentDisplay = { it.nightTheme.name.lowercase().replace('_', ' ') },
    ),
    SettingEntry(
        id = "theme.nightHours",
        category = SettingCategory.APPEARANCE,
        label = "Night window",
        description = "Hours during which the night theme is active (local time)",
        isDefault = {
            it.nightStartHour == defaults.nightStartHour &&
                it.nightEndHour == defaults.nightEndHour
        },
        currentDisplay = { "${it.nightStartHour}:00 → ${it.nightEndHour}:00" },
    ),

    // ── Scroll wheel ────────────────────────────────────────────────────
    SettingEntry(
        id = "wheel.stepPercent",
        category = SettingCategory.INPUT,
        label = "Wheel step",
        description = "Percent change per detent (1, 2, 5, or 10)",
        isDefault = { it.wheel.stepPercent == defaults.wheel.stepPercent },
        currentDisplay = { "${it.wheel.stepPercent} %" },
    ),
    SettingEntry(
        id = "wheel.acceleration",
        category = SettingCategory.INPUT,
        label = "Wheel acceleration",
        description = "Boost the step on fast spins",
        isDefault = { it.wheel.acceleration == defaults.wheel.acceleration },
        currentDisplay = { if (it.wheel.acceleration) "ON" else "OFF" },
    ),
    SettingEntry(
        id = "wheel.accelerationCurve",
        category = SettingCategory.INPUT,
        label = "Acceleration curve",
        description = "Subtle / medium / aggressive boost shape",
        isDefault = { it.wheel.accelerationCurve == defaults.wheel.accelerationCurve },
        currentDisplay = { it.wheel.accelerationCurve.name },
    ),
    SettingEntry(
        id = "wheel.invertDirection",
        category = SettingCategory.INPUT,
        label = "Invert wheel direction",
        description = "Up = decrease, down = increase",
        isDefault = { it.wheel.invertDirection == defaults.wheel.invertDirection },
        currentDisplay = { if (it.wheel.invertDirection) "ON" else "OFF" },
    ),
    SettingEntry(
        id = "input.keyBindings",
        category = SettingCategory.INPUT,
        label = "Key bindings",
        description = "Per-action key map (press-to-bind for hardware keys)",
        isDefault = { it.keyBindings.isEmpty() },
        currentDisplay = {
            val overridden = it.keyBindings.size
            if (overridden == 0) "DEFAULT" else "$overridden CUSTOM"
        },
    ),

    // ── Card UI ─────────────────────────────────────────────────────────
    SettingEntry(
        id = "ui.displayMode",
        category = SettingCategory.CARD_UI,
        label = "Display mode",
        description = "Show scalar values as percent or raw value",
        isDefault = { it.ui.displayMode == defaults.ui.displayMode },
        currentDisplay = { it.ui.displayMode.name },
    ),
    SettingEntry(
        id = "ui.showOnOffPill",
        category = SettingCategory.CARD_UI,
        label = "Show on/off pill",
        description = "Tiny ON / OFF chip on every card's lower-left",
        isDefault = { it.ui.showOnOffPill == defaults.ui.showOnOffPill },
        currentDisplay = { if (it.ui.showOnOffPill) "ON" else "OFF" },
    ),
    SettingEntry(
        id = "ui.showAreaLabel",
        category = SettingCategory.CARD_UI,
        label = "Show area label",
        description = "Show the entity's HA area on the card header",
        isDefault = { it.ui.showAreaLabel == defaults.ui.showAreaLabel },
        currentDisplay = { if (it.ui.showAreaLabel) "ON" else "OFF" },
    ),
    SettingEntry(
        id = "ui.showPositionDots",
        category = SettingCategory.CARD_UI,
        label = "Show position pip",
        description = "Bar in the chrome that shows current card position",
        isDefault = { it.ui.showPositionDots == defaults.ui.showPositionDots },
        currentDisplay = { if (it.ui.showPositionDots) "ON" else "OFF" },
    ),
    SettingEntry(
        id = "ui.hideCardTailAbove",
        category = SettingCategory.CARD_UI,
        label = "Hide card tail above current",
        description = "Solid chrome backdrop covers the previous card's tail",
        isDefault = { it.ui.hideCardTailAbove == defaults.ui.hideCardTailAbove },
        currentDisplay = { if (it.ui.hideCardTailAbove) "ON" else "OFF" },
    ),
    SettingEntry(
        id = "ui.infiniteScroll",
        category = SettingCategory.CARD_UI,
        label = "Infinite scroll",
        description = "Wheel past the last card wraps to the first",
        isDefault = { it.ui.infiniteScroll == defaults.ui.infiniteScroll },
        currentDisplay = { if (it.ui.infiniteScroll) "ON" else "OFF" },
    ),
    SettingEntry(
        id = "ui.showZeroPercentWhenOff",
        category = SettingCategory.CARD_UI,
        label = "Show 0% arc when entity is off",
        description = "Clamp the brightness arc to zero for any off entity, overriding HA's stored brightness",
        isDefault = { it.ui.showZeroPercentWhenOff == defaults.ui.showZeroPercentWhenOff },
        currentDisplay = { if (it.ui.showZeroPercentWhenOff) "ON" else "OFF" },
    ),
    SettingEntry(
        id = "ui.textHistoryLength",
        category = SettingCategory.CARD_UI,
        label = "Sensor history length",
        description = "Recent state-change rows kept on text/categorical sensor cards",
        isDefault = { it.ui.textHistoryLength == defaults.ui.textHistoryLength },
        currentDisplay = { "${it.ui.textHistoryLength}" },
    ),
    SettingEntry(
        id = "ui.maxDecimalPlaces",
        category = SettingCategory.CARD_UI,
        label = "Sensor decimals",
        description = "Max decimal places shown for numeric sensors",
        isDefault = { it.ui.maxDecimalPlaces == defaults.ui.maxDecimalPlaces },
        currentDisplay = { if (it.ui.maxDecimalPlaces == 0) "INT" else "${it.ui.maxDecimalPlaces}" },
    ),
    SettingEntry(
        id = "ui.tempUnit",
        category = SettingCategory.CARD_UI,
        label = "Temperature unit",
        description = "Auto follows HA's reported unit; force Celsius or Fahrenheit",
        isDefault = { it.ui.tempUnit == defaults.ui.tempUnit },
        currentDisplay = {
            when (it.ui.tempUnit) {
                TemperatureUnit.AUTO -> "AUTO"
                TemperatureUnit.CELSIUS -> "°C"
                TemperatureUnit.FAHRENHEIT -> "°F"
            }
        },
    ),
    SettingEntry(
        id = "ui.chromeButtons",
        category = SettingCategory.CARD_UI,
        label = "Chrome buttons",
        description = "Right-cluster button order + visibility",
        isDefault = { it.ui.chromeButtons == defaults.ui.chromeButtons },
        currentDisplay = { s ->
            // Show the actual order of visible buttons as a compact arrow chain
            // (e.g. "BAT > MIC > GEAR"). The previous '4 / 4 visible' rendering
            // hid order changes — a pure reorder showed identical text against
            // the default state, even though isDefault correctly reported the
            // entry as modified. Strikethrough Unicode isn't an option in our
            // monospace font; instead, hidden buttons are simply omitted.
            val abbreviations = mapOf(
                ChromeButtonRef.BATTERY to "BAT",
                ChromeButtonRef.ASSIST_MIC to "MIC",
                ChromeButtonRef.EDIT to "EDIT",
                ChromeButtonRef.GEAR to "GEAR",
            )
            s.ui.chromeButtons
                .filter { it.enabled }
                .joinToString(" › ") { abbreviations[it.ref] ?: it.ref.name }
        },
    ),

    // ── Behaviour ───────────────────────────────────────────────────────
    SettingEntry(
        id = "behavior.haptics",
        category = SettingCategory.BEHAVIOUR,
        label = "Haptics",
        description = "Vibration on wheel detents and taps",
        isDefault = { it.behavior.haptics == defaults.behavior.haptics },
        currentDisplay = { if (it.behavior.haptics) "ON" else "OFF" },
    ),
    SettingEntry(
        id = "behavior.keepScreenOn",
        category = SettingCategory.BEHAVIOUR,
        label = "Keep screen on",
        description = "Prevent the display from sleeping while the app is foreground",
        isDefault = { it.behavior.keepScreenOn == defaults.behavior.keepScreenOn },
        currentDisplay = { if (it.behavior.keepScreenOn) "ON" else "OFF" },
    ),
    SettingEntry(
        id = "behavior.tapToToggle",
        category = SettingCategory.BEHAVIOUR,
        label = "Tap card to toggle",
        description = "Whole-card tap toggles the entity",
        isDefault = { it.behavior.tapToToggle == defaults.behavior.tapToToggle },
        currentDisplay = { if (it.behavior.tapToToggle) "ON" else "OFF" },
    ),
    SettingEntry(
        id = "behavior.hideStatusBar",
        category = SettingCategory.BEHAVIOUR,
        label = "Hide system status bar",
        description = "Hide Android's top bar for the pure-card aesthetic",
        isDefault = { it.behavior.hideStatusBar == defaults.behavior.hideStatusBar },
        currentDisplay = { if (it.behavior.hideStatusBar) "ON" else "OFF" },
    ),
    SettingEntry(
        id = "behavior.showBatteryWhenStatusBarHidden",
        category = SettingCategory.BEHAVIOUR,
        label = "Battery indicator on chrome",
        description = "Render a battery pill in the chrome when the status bar is hidden",
        isDefault = {
            it.behavior.showBatteryWhenStatusBarHidden ==
                defaults.behavior.showBatteryWhenStatusBarHidden
        },
        currentDisplay = { if (it.behavior.showBatteryWhenStatusBarHidden) "ON" else "OFF" },
    ),
    SettingEntry(
        id = "behavior.startOnDashboard",
        category = SettingCategory.BEHAVIOUR,
        label = "Start on dashboard",
        description = "Open on the TODAY dashboard rather than the card stack",
        isDefault = { it.behavior.startOnDashboard == defaults.behavior.startOnDashboard },
        currentDisplay = { if (it.behavior.startOnDashboard) "ON" else "OFF" },
    ),
    SettingEntry(
        id = "behavior.wheelTogglesSwitches",
        category = SettingCategory.BEHAVIOUR,
        label = "Wheel toggles switches",
        description = "Wheel up/down flips non-scalar cards (locks, plain switches)",
        isDefault = {
            it.behavior.wheelTogglesSwitches == defaults.behavior.wheelTogglesSwitches
        },
        currentDisplay = { if (it.behavior.wheelTogglesSwitches) "ON" else "OFF" },
    ),
    SettingEntry(
        id = "behavior.toastLogLevel",
        category = SettingCategory.BEHAVIOUR,
        label = "Toast log level",
        description = "Diagnostic-toast threshold (off / error / warn / info / debug)",
        isDefault = { it.behavior.toastLogLevel == defaults.behavior.toastLogLevel },
        currentDisplay = { it.behavior.toastLogLevel.name },
    ),
    SettingEntry(
        id = "behavior.quickTileEntityId",
        category = SettingCategory.BEHAVIOUR,
        label = "Quick Settings tile",
        description = "Entity bound to the Android notification-shade tile (slot A)",
        isDefault = { it.behavior.quickTileEntityId == defaults.behavior.quickTileEntityId },
        currentDisplay = { it.behavior.quickTileEntityId?.takeIf { v -> v.isNotBlank() } ?: "(unbound)" },
    ),
    SettingEntry(
        id = "behavior.quickTileEntityIdB",
        category = SettingCategory.BEHAVIOUR,
        label = "Quick Settings tile · slot B",
        description = "Entity bound to the 'HA Toggle 2' tile",
        isDefault = { it.behavior.quickTileEntityIdB == defaults.behavior.quickTileEntityIdB },
        currentDisplay = { it.behavior.quickTileEntityIdB?.takeIf { v -> v.isNotBlank() } ?: "(unbound)" },
    ),
    SettingEntry(
        id = "behavior.quickTileEntityIdC",
        category = SettingCategory.BEHAVIOUR,
        label = "Quick Settings tile · slot C",
        description = "Entity bound to the 'HA Toggle 3' tile",
        isDefault = { it.behavior.quickTileEntityIdC == defaults.behavior.quickTileEntityIdC },
        currentDisplay = { it.behavior.quickTileEntityIdC?.takeIf { v -> v.isNotBlank() } ?: "(unbound)" },
    ),
    SettingEntry(
        id = "behavior.quickTileEntityIdD",
        category = SettingCategory.BEHAVIOUR,
        label = "Quick Settings tile · slot D",
        description = "Entity bound to the 'HA Toggle 4' tile",
        isDefault = { it.behavior.quickTileEntityIdD == defaults.behavior.quickTileEntityIdD },
        currentDisplay = { it.behavior.quickTileEntityIdD?.takeIf { v -> v.isNotBlank() } ?: "(unbound)" },
    ),
    SettingEntry(
        id = "behavior.assistAutoOpenKeyboard",
        category = SettingCategory.BEHAVIOUR,
        label = "Assist auto-open keyboard",
        description = "Pop the keyboard when the Assist screen opens",
        isDefault = {
            it.behavior.assistAutoOpenKeyboard == defaults.behavior.assistAutoOpenKeyboard
        },
        currentDisplay = { if (it.behavior.assistAutoOpenKeyboard) "ON" else "OFF" },
    ),
    SettingEntry(
        id = "behavior.orientationMode",
        category = SettingCategory.BEHAVIOUR,
        label = "Screen orientation",
        description = "Follow device sensor or lock to portrait",
        isDefault = { it.behavior.orientationMode == defaults.behavior.orientationMode },
        currentDisplay = {
            when (it.behavior.orientationMode) {
                OrientationMode.FOLLOW_DEVICE -> "Follow device"
                OrientationMode.PORTRAIT_ONLY -> "Portrait only"
            }
        },
    ),

    // ── Integrations ────────────────────────────────────────────────────
    SettingEntry(
        id = "integrations.notificationsRefreshSec",
        category = SettingCategory.INTEGRATIONS,
        label = "Notifications refresh",
        description = "Auto-refresh cadence for persistent notifications (seconds)",
        isDefault = {
            it.integrations.notificationsRefreshSec == defaults.integrations.notificationsRefreshSec
        },
        currentDisplay = { "${it.integrations.notificationsRefreshSec} s" },
    ),
    SettingEntry(
        id = "integrations.logbookRefreshSec",
        category = SettingCategory.INTEGRATIONS,
        label = "Logbook refresh",
        description = "Auto-refresh cadence for Recent Activity (seconds)",
        isDefault = {
            it.integrations.logbookRefreshSec == defaults.integrations.logbookRefreshSec
        },
        currentDisplay = { "${it.integrations.logbookRefreshSec} s" },
    ),
    SettingEntry(
        id = "integrations.cameraOverlayPollSec",
        category = SettingCategory.INTEGRATIONS,
        label = "Camera detail polling",
        description = "Snapshot poll interval when a camera is open fullscreen (seconds)",
        isDefault = {
            it.integrations.cameraOverlayPollSec == defaults.integrations.cameraOverlayPollSec
        },
        currentDisplay = { "${it.integrations.cameraOverlayPollSec} s" },
    ),
    SettingEntry(
        id = "integrations.cameraGridPollSec",
        category = SettingCategory.INTEGRATIONS,
        label = "Camera grid polling",
        description = "Snapshot poll interval for camera grid tiles (seconds)",
        isDefault = {
            it.integrations.cameraGridPollSec == defaults.integrations.cameraGridPollSec
        },
        currentDisplay = { "${it.integrations.cameraGridPollSec} s" },
    ),
    SettingEntry(
        id = "integrations.camerasDefaultGrid",
        category = SettingCategory.INTEGRATIONS,
        label = "Cameras default to grid",
        description = "Open Cameras in GRID view rather than LIST",
        isDefault = {
            it.integrations.camerasDefaultGrid == defaults.integrations.camerasDefaultGrid
        },
        currentDisplay = { if (it.integrations.camerasDefaultGrid) "GRID" else "LIST" },
    ),
    SettingEntry(
        id = "integrations.searchResultCap",
        category = SettingCategory.INTEGRATIONS,
        label = "Quick Search result cap",
        description = "Max entity rows returned by Quick Search",
        isDefault = {
            it.integrations.searchResultCap == defaults.integrations.searchResultCap
        },
        currentDisplay = { "${it.integrations.searchResultCap}" },
    ),

    // ── Dashboard ───────────────────────────────────────────────────────
    // Two most-touched dashboard knobs: how often it polls, and where the
    // BATTERIES LOW threshold sits. Section-visibility toggles intentionally
    // omitted from the registry — 11 booleans is a lot of registry weight for
    // controls the user can already see in the Dashboard section's flat list.
    SettingEntry(
        id = "dashboard.refreshIntervalSec",
        category = SettingCategory.DASHBOARD,
        label = "Dashboard refresh",
        description = "Auto-refresh cadence for the TODAY dashboard (seconds, 0 disables)",
        isDefault = {
            it.dashboard.refreshIntervalSec == defaults.dashboard.refreshIntervalSec
        },
        currentDisplay = { if (it.dashboard.refreshIntervalSec == 0) "OFF" else "${it.dashboard.refreshIntervalSec} s" },
    ),
    SettingEntry(
        id = "dashboard.lowBatteryThresholdPct",
        category = SettingCategory.DASHBOARD,
        label = "Low-battery threshold",
        description = "Battery sensors below this percent surface on the BATTERIES LOW dashboard card",
        isDefault = {
            it.dashboard.lowBatteryThresholdPct == defaults.dashboard.lowBatteryThresholdPct
        },
        currentDisplay = { "${it.dashboard.lowBatteryThresholdPct} %" },
    ),
    SettingEntry(
        id = "dashboard.tileOrder",
        category = SettingCategory.DASHBOARD,
        label = "Tile order",
        description = "Top-to-bottom order of dashboard tiles (Settings → DASHBOARD → TILE ORDER)",
        isDefault = { it.dashboard.tileOrder == DashboardSettings.DEFAULT_TILE_ORDER },
        currentDisplay = {
            if (it.dashboard.tileOrder == DashboardSettings.DEFAULT_TILE_ORDER) "default"
            else "customised"
        },
    ),
    // Advanced / power-user toggles (background refresh, NFC scanner, notification
    // mirror, external automation intent) live under About > Dev menu rather than
    // the main Settings tree, so they're intentionally absent from this registry.
    // Surfacing them in the in-settings search would mislead users into expecting
    // a main-tree row they can tap; the dev-menu is the discoverable path.
)

/**
 * Return the subset of [SETTINGS_REGISTRY] whose [SettingEntry.isDefault] returns
 * false for [current], in registry order. Used by the diff subscreen.
 */
fun modifiedSettings(current: AppSettings): List<SettingEntry> =
    SETTINGS_REGISTRY.filterNot { it.isDefault(current) }

/**
 * Case-insensitive substring match against [SettingEntry.label],
 * [SettingEntry.description] and [SettingCategory.label]. Used by the Settings
 * search overlay. Including the category label lets the user type a section
 * name (e.g. 'behaviour', 'card ui') and have every entry under that section
 * surface in one shot, which is closer to the 'tiered menu' navigation
 * shape than a strict per-entry text match.
 */
fun searchSettings(query: String): List<SettingEntry> {
    if (query.isBlank()) return emptyList()
    val q = query.trim().lowercase()
    return SETTINGS_REGISTRY.filter {
        it.label.lowercase().contains(q) ||
            it.description.lowercase().contains(q) ||
            it.category.label.lowercase().contains(q)
    }
}
