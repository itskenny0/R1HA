package com.github.itskenny0.r1ha.core.prefs

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Portable backup of every user-tunable setting + favourites list. JSON-encoded
 * into a file the user picks via the system file picker (SAF) so they can
 * archive a known-good config, restore after a factory reset, or move their
 * setup between R1 devices.
 *
 * **What's in the backup:** server URL + HA version, every wheel / UI /
 * behaviour / advanced setting, the full theme choice, the pages list with
 * favourites + ordering, per-entity name overrides + customization overrides.
 *
 * **What's NOT in the backup:** the OAuth tokens. Tokens are device-bound (the
 * `client_id` registered with HA is per-install) and copying them across
 * installs is a security smell. Restoring a backup keeps the server URL so the
 * user is one tap away from re-signing-in to the same HA instance — but each
 * device gets its own token, by design.
 *
 * The [version] field exists so a future schema change can migrate older
 * backups instead of refusing them outright. v1 is the initial format; bump
 * when the shape diverges and add a `migrate(from)` helper here.
 */
@Serializable
data class AppBackup(
    val version: Int = SCHEMA_VERSION,
    /** ISO-8601 UTC timestamp captured at export. Cosmetic — shown in the
     *  import preview so the user can confirm they're restoring the right
     *  snapshot. Not used for ordering or anything functional. */
    val createdAt: String,

    val serverUrl: String? = null,
    val haVersion: String? = null,

    val theme: ThemeId = ThemeId.PRAGMATIC_HYBRID,
    val autoThemeEnabled: Boolean = false,
    val nightTheme: ThemeId = ThemeId.MINIMAL_DARK,
    val nightStartHour: Int = 22,
    val nightEndHour: Int = 6,

    val wheelStepPercent: Int = 2,
    val wheelAcceleration: Boolean = true,
    val wheelInvertDirection: Boolean = false,
    val wheelAccelerationCurve: AccelerationCurve = AccelerationCurve.MEDIUM,
    /**
     * User key bindings. `KeyAction.name` → list of `KeyEvent.KEYCODE_*` codes.
     * Captured on backup so a restore preserves user-customised hardware
     * mappings; an empty map means the running build's defaults apply.
     */
    val keyBindings: Map<String, List<Int>> = emptyMap(),

    val uiDisplayMode: DisplayMode = DisplayMode.PERCENT,
    val uiShowOnOffPill: Boolean = true,
    val uiShowAreaLabel: Boolean = true,
    /** Legacy boolean kept for back-compat reads of older backup files. New
     *  writes round-trip via [uiPositionDotLocation] below; this field is
     *  materialised on backup as `location != HIDDEN` so an older build can
     *  still tell whether the user wanted the pip visible at all. */
    val uiShowPositionDots: Boolean = true,
    /** Where the position pip sits — wins over [uiShowPositionDots] on
     *  restore. Older backup files without this field decode as TOP_CENTER
     *  / HIDDEN based on the legacy boolean above. */
    val uiPositionDotLocation: PositionDotLocation = PositionDotLocation.TOP_CENTER,
    /** Where the main value bar sits. Older backup files without this field
     *  decode as RIGHT (the historical right-edge layout). */
    val uiValueBarLocation: ValueBarLocation = ValueBarLocation.RIGHT,
    val uiTextHistoryLength: Int = 20,
    val uiHideCardTailAbove: Boolean = true,
    val uiMaxDecimalPlaces: Int = 2,
    val uiTempUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
    val uiInfiniteScroll: Boolean = false,
    /** Card-stack peek-deck mode. Older backup files without this field decode
     *  as AUTO (peek only on phone-portrait), matching the live default. */
    val uiCardPeekMode: CardPeekMode = CardPeekMode.AUTO,
    /** Card-stack scroll sensitivity (0..100). Older backups without this field
     *  decode as 80, the default that reproduces the stock fling feel. */
    val uiCardScrollSensitivity: Int = 80,
    /** Deck-wide default for whether the ultra-detail more-info sheet is offered.
     *  Older backups without this field decode as true, the default. */
    val uiMoreInfoEnabledDefault: Boolean = true,

    val behaviorHaptics: Boolean = true,
    val behaviorKeepScreenOn: Boolean = true,
    val behaviorTapToToggle: Boolean = false,
    val behaviorHideStatusBar: Boolean = false,
    val behaviorShowBatteryWhenStatusBarHidden: Boolean = false,
    val behaviorStartOnDashboard: Boolean = false,
    val behaviorWheelTogglesSwitches: Boolean = true,
    val behaviorToastLogLevel: ToastLogLevel = ToastLogLevel.OFF,

    val advanced: AdvancedSettings = AdvancedSettings(),
    val dashboard: DashboardSettings = DashboardSettings(),
    /** Side navigation panel enable + per-item visibility. Older backup files
     *  without this field decode as the default (panel on, nothing hidden). */
    val navPanel: NavPanelSettings = NavPanelSettings(),
    val integrations: IntegrationsSettings = IntegrationsSettings(),
    /** Connection-hardening (breaker + polling) prefs. Older backups without this
     *  field decode as the default (strict mode off, conservative breaker dials). */
    val connection: ConnectionSettings = ConnectionSettings(),

    val pages: List<FavoritePage> = emptyList(),
    val activePageId: String = "",
    /** Legacy single-list favourites union. Captured for back-compat readers
     *  that haven't been migrated to pages; restoration re-derives it from
     *  the union of pages, so the field is informational on import. */
    val favorites: List<String> = emptyList(),

    val nameOverrides: Map<String, String> = emptyMap(),
    val entityOverrides: Map<String, EntityOverride> = emptyMap(),
) {
    companion object {
        /** Current schema version. Bump when the on-disk shape changes in a
         *  non-additive way (renaming a field, changing semantics). */
        const val SCHEMA_VERSION = 1

        /** Configured kotlinx-serialization JSON: pretty-printed for human
         *  inspectability when the user opens the file in a text editor, and
         *  lenient on read so older / newer-but-compatible backups still load
         *  without forcing a manual migration step. */
        val json: Json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

/** Build an [AppBackup] from the live [AppSettings] (and a wall-clock
 *  timestamp generated by the caller — kept as a parameter so test code can
 *  pin it). Tokens are not read from [AppSettings] and so are never written
 *  to the backup. */
fun AppSettings.toBackup(createdAt: String): AppBackup = AppBackup(
    createdAt = createdAt,
    serverUrl = server?.url,
    haVersion = server?.haVersion,
    theme = theme,
    autoThemeEnabled = autoThemeEnabled,
    nightTheme = nightTheme,
    nightStartHour = nightStartHour,
    nightEndHour = nightEndHour,
    wheelStepPercent = wheel.stepPercent,
    wheelAcceleration = wheel.acceleration,
    wheelInvertDirection = wheel.invertDirection,
    wheelAccelerationCurve = wheel.accelerationCurve,
    keyBindings = keyBindings,
    uiDisplayMode = ui.displayMode,
    uiShowOnOffPill = ui.showOnOffPill,
    uiShowAreaLabel = ui.showAreaLabel,
    uiShowPositionDots = ui.positionDotLocation != PositionDotLocation.HIDDEN,
    uiPositionDotLocation = ui.positionDotLocation,
    uiValueBarLocation = ui.valueBarLocation,
    uiTextHistoryLength = ui.textHistoryLength,
    uiHideCardTailAbove = ui.hideCardTailAbove,
    uiMaxDecimalPlaces = ui.maxDecimalPlaces,
    uiTempUnit = ui.tempUnit,
    uiInfiniteScroll = ui.infiniteScroll,
    uiCardPeekMode = ui.cardPeekMode,
    uiCardScrollSensitivity = ui.cardScrollSensitivity,
    uiMoreInfoEnabledDefault = ui.moreInfoEnabledDefault,
    behaviorHaptics = behavior.haptics,
    behaviorKeepScreenOn = behavior.keepScreenOn,
    behaviorTapToToggle = behavior.tapToToggle,
    behaviorHideStatusBar = behavior.hideStatusBar,
    behaviorShowBatteryWhenStatusBarHidden = behavior.showBatteryWhenStatusBarHidden,
    behaviorStartOnDashboard = behavior.startOnDashboard,
    behaviorWheelTogglesSwitches = behavior.wheelTogglesSwitches,
    behaviorToastLogLevel = behavior.toastLogLevel,
    advanced = advanced,
    dashboard = dashboard,
    navPanel = navPanel,
    integrations = integrations,
    connection = connection,
    pages = pages,
    activePageId = activePageId,
    favorites = favorites,
    nameOverrides = nameOverrides,
    entityOverrides = entityOverrides,
)

/**
 * Apply [backup] on top of [prev], producing the new [AppSettings] to persist.
 * Everything in [backup] wins over [prev]; the server section is replaced as
 * a unit (URL + version together) so a partially-filled backup can't leave
 * the user with a URL but no version or vice versa.
 *
 * The activePageId is clamped to a present page id at restore time so a
 * deleted page can't strand the deck — same invariant the live update path
 * enforces.
 */
fun AppBackup.applyOnto(prev: AppSettings): AppSettings {
    val newServer = serverUrl?.let { url -> ServerConfig(url = url, haVersion = haVersion) }
    // Pre-tabs backups (and hand-edited files) carry favourites only in the
    // legacy flat [favorites] list with an empty [pages]. Materialise the same
    // single 'HOME' page the live migration creates so the restore doesn't
    // silently drop the user's favourites — mirrors the id/name convention in
    // SettingsRepository's pages migration so a later edit reuses the slot.
    val effectivePages = pages.ifEmpty {
        if (favorites.isNotEmpty()) listOf(FavoritePage("home", "HOME", favorites)) else emptyList()
    }
    val activeId = effectivePages.firstOrNull { it.id == activePageId }?.id
        ?: effectivePages.firstOrNull()?.id
        ?: ""
    return prev.copy(
        server = newServer ?: prev.server,
        theme = theme,
        autoThemeEnabled = autoThemeEnabled,
        nightTheme = nightTheme,
        nightStartHour = nightStartHour,
        nightEndHour = nightEndHour,
        wheel = WheelSettings(
            stepPercent = wheelStepPercent,
            acceleration = wheelAcceleration,
            invertDirection = wheelInvertDirection,
            accelerationCurve = wheelAccelerationCurve,
        ),
        keyBindings = keyBindings,
        ui = UiOptions(
            displayMode = uiDisplayMode,
            showOnOffPill = uiShowOnOffPill,
            showAreaLabel = uiShowAreaLabel,
            // Prefer the explicit enum slot when present; fall back to the
            // legacy boolean for backups produced before the enum landed
            // (true → TOP_CENTER, false → HIDDEN).
            positionDotLocation = uiPositionDotLocation
                .takeIf { it != PositionDotLocation.TOP_CENTER || uiShowPositionDots }
                ?: if (uiShowPositionDots) PositionDotLocation.TOP_CENTER
                   else PositionDotLocation.HIDDEN,
            valueBarLocation = uiValueBarLocation,
            textHistoryLength = uiTextHistoryLength,
            hideCardTailAbove = uiHideCardTailAbove,
            maxDecimalPlaces = uiMaxDecimalPlaces,
            tempUnit = uiTempUnit,
            infiniteScroll = uiInfiniteScroll,
            cardPeekMode = uiCardPeekMode,
            cardScrollSensitivity = uiCardScrollSensitivity,
            moreInfoEnabledDefault = uiMoreInfoEnabledDefault,
        ),
        behavior = Behavior(
            haptics = behaviorHaptics,
            keepScreenOn = behaviorKeepScreenOn,
            tapToToggle = behaviorTapToToggle,
            hideStatusBar = behaviorHideStatusBar,
            showBatteryWhenStatusBarHidden = behaviorShowBatteryWhenStatusBarHidden,
            startOnDashboard = behaviorStartOnDashboard,
            wheelTogglesSwitches = behaviorWheelTogglesSwitches,
            toastLogLevel = behaviorToastLogLevel,
        ),
        advanced = advanced,
        dashboard = dashboard,
        navPanel = navPanel,
        integrations = integrations,
        connection = connection,
        pages = effectivePages,
        activePageId = activeId,
        favorites = effectivePages.flatMap { it.favorites }.distinct(),
        nameOverrides = nameOverrides,
        entityOverrides = entityOverrides,
    )
}

/** Encode a backup to a pretty-printed JSON string. */
fun encodeBackup(backup: AppBackup): String =
    AppBackup.json.encodeToString(AppBackup.serializer(), backup)

/** Parse a backup JSON string. Throws on malformed input — callers should
 *  wrap in runCatching and surface a friendly toast on failure. */
fun decodeBackup(raw: String): AppBackup =
    AppBackup.json.decodeFromString(AppBackup.serializer(), raw)

// The serializer for nameOverrides is just the default Map<String, String>;
// keep this reference handy so test code can spot-check encoding without
// reaching back into kotlinx.serialization internals.
internal val NAME_OVERRIDES_SERIALIZER = MapSerializer(String.serializer(), String.serializer())
