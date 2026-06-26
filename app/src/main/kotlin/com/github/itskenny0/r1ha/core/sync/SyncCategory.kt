package com.github.itskenny0.r1ha.core.sync

import com.github.itskenny0.r1ha.core.prefs.AdvancedSettings
import com.github.itskenny0.r1ha.core.prefs.AppSettings

/**
 * Logical groupings of [AppSettings] fields, exposed in the Sync UI so the
 * user can opt out of mirroring specific bits of state (theme but not pages,
 * pages but not card overrides, etc.). Default = every category included.
 *
 * Each entry knows how to "preserve" its own fields when applying a remote
 * payload — when the user has un-ticked a category, that category's fields
 * survive the remote apply by being copied back from the local snapshot
 * after [AppBackup.applyOnto] overwrites them. The same preservation runs
 * on push (via [toSyncBackup]) so an excluded category doesn't leak to HA.
 *
 * Adding a category later: append a new entry here, implement `preserve()`
 * to copy the relevant fields, and the UI + storage paths pick it up
 * automatically — the persisted excluded-set uses string names so stale
 * entries from old builds decode harmlessly.
 */
enum class SyncCategory(val displayLabel: String, val description: String) {
    THEME(
        "Theme & appearance",
        "Theme, night theme, accent colour, time-of-day switching",
    ) {
        override fun preserve(applied: AppSettings, source: AppSettings): AppSettings =
            applied.copy(
                theme = source.theme,
                autoThemeEnabled = source.autoThemeEnabled,
                nightTheme = source.nightTheme,
                nightStartHour = source.nightStartHour,
                nightEndHour = source.nightEndHour,
                themeAccentArgb = source.themeAccentArgb,
            )
    },
    WHEEL_INPUT(
        "Wheel & input",
        "Step size, acceleration curve, invert, hardware key bindings",
    ) {
        override fun preserve(applied: AppSettings, source: AppSettings): AppSettings =
            applied.copy(
                wheel = source.wheel,
                keyBindings = source.keyBindings,
            )
    },
    CARD_UI(
        "Card UI",
        "Display mode, on/off pill, area label, dots, decimals, units, chrome buttons",
    ) {
        override fun preserve(applied: AppSettings, source: AppSettings): AppSettings =
            applied.copy(ui = source.ui)
    },
    BEHAVIOUR(
        "Behaviour",
        "Haptics, keep-on, tap-to-toggle, kiosk + guest mode, Assist macros",
    ) {
        override fun preserve(applied: AppSettings, source: AppSettings): AppSettings =
            applied.copy(
                behavior = source.behavior,
                guestModeEnabled = source.guestModeEnabled,
            )
    },
    DASHBOARD(
        "Dashboard",
        "Today screen tiles, thresholds, tile order, refresh interval",
    ) {
        override fun preserve(applied: AppSettings, source: AppSettings): AppSettings =
            applied.copy(dashboard = source.dashboard)
    },
    INTEGRATIONS(
        "Integrations refresh",
        "Per-surface auto-refresh intervals + camera polling cadences",
    ) {
        override fun preserve(applied: AppSettings, source: AppSettings): AppSettings {
            // Sync the per-surface refresh cadences only. The sync meta itself
            // (haSyncEnabled / ReadOnly / ManualOnly / Interval / PromptSeen /
            // ExcludedCategories) is device-local regardless of this category's
            // opt-in and is pinned to the live value by preserveDeviceLocal()
            // in HaSettingsSync, so it is deliberately not copied here.
            val srcInt = source.integrations
            val appInt = applied.integrations
            return applied.copy(
                integrations = appInt.copy(
                    notificationsRefreshSec = srcInt.notificationsRefreshSec,
                    logbookRefreshSec = srcInt.logbookRefreshSec,
                    personsRefreshSec = srcInt.personsRefreshSec,
                    weatherRefreshSec = srcInt.weatherRefreshSec,
                    calendarsRefreshSec = srcInt.calendarsRefreshSec,
                    cameraOverlayPollSec = srcInt.cameraOverlayPollSec,
                    cameraGridPollSec = srcInt.cameraGridPollSec,
                    logbookDefaultWindowHours = srcInt.logbookDefaultWindowHours,
                    camerasDefaultGrid = srcInt.camerasDefaultGrid,
                    searchResultCap = srcInt.searchResultCap,
                    recentHistoryDepth = srcInt.recentHistoryDepth,
                    calendarLookaheadDays = srcInt.calendarLookaheadDays,
                ),
            )
        }
    },
    PAGES(
        "Pages & favourites",
        "Card stack tabs and the pinned entities on each tab",
    ) {
        override fun preserve(applied: AppSettings, source: AppSettings): AppSettings =
            applied.copy(
                pages = source.pages,
                // activePageId (the open tab) is NOT handled here: it is
                // device-local regardless of this category's opt-in, pinned to
                // the live value by preserveDeviceLocal() in HaSettingsSync.
                favorites = source.favorites,
            )
    },
    OVERRIDES(
        "Card customization",
        "Per-entity renames and customisations (scale, accent, long-press, hidden buttons)",
    ) {
        override fun preserve(applied: AppSettings, source: AppSettings): AppSettings =
            applied.copy(
                nameOverrides = source.nameOverrides,
                entityOverrides = source.entityOverrides,
                // The Energy-view excluded power sensors ride this category too:
                // they are per-entity, install-specific choices, so opting out of
                // "Card customization" should keep them local like the renames.
                energyExcludedSensors = source.energyExcludedSensors,
            )
    },
    ADVANCED(
        "Advanced / power tools",
        "Dev menu knobs, debounce timings, optional integrations (NFC, webhooks, MQTT)",
    ) {
        override fun preserve(applied: AppSettings, source: AppSettings): AppSettings {
            // Keep applied's iBeacon / webhook / MQTT fields — those are
            // overridden by preserveDeviceLocal() in HaSettingsSync regardless
            // of this category's opt-in state. So preserve only the
            // sync-eligible subset of advanced.
            val srcAdv = source.advanced
            val appAdv = applied.advanced
            return applied.copy(
                advanced = AdvancedSettings(
                    serviceDebounceMs = srcAdv.serviceDebounceMs,
                    serviceMaxIntervalMs = srcAdv.serviceMaxIntervalMs,
                    wheelRateWindowMs = srcAdv.wheelRateWindowMs,
                    navAccelCap = srcAdv.navAccelCap,
                    longPressMs = srcAdv.longPressMs,
                    sensorHistoryHours = srcAdv.sensorHistoryHours,
                    reconnectBackoffMaxSec = srcAdv.reconnectBackoffMaxSec,
                    wsPingIntervalSec = srcAdv.wsPingIntervalSec,
                    restTimeoutSec = srcAdv.restTimeoutSec,
                    keepLogBuffer = srcAdv.keepLogBuffer,
                    strictEntityDecode = srcAdv.strictEntityDecode,
                    pinOptimistic = srcAdv.pinOptimistic,
                    slowPagerTransitions = srcAdv.slowPagerTransitions,
                    showEntityIdOnCards = srcAdv.showEntityIdOnCards,
                    verboseServiceCalls = srcAdv.verboseServiceCalls,
                    verboseHttp = srcAdv.verboseHttp,
                    verboseWebSocket = srcAdv.verboseWebSocket,
                    skipPreflightRefresh = srcAdv.skipPreflightRefresh,
                    keepOptimisticOnFailure = srcAdv.keepOptimisticOnFailure,
                    showDebugStripOnCards = srcAdv.showDebugStripOnCards,
                    persistCacheToDisk = srcAdv.persistCacheToDisk,
                    externalAutomationEnabled = srcAdv.externalAutomationEnabled,
                    backgroundRefreshEnabled = srcAdv.backgroundRefreshEnabled,
                    mirrorHaNotifications = srcAdv.mirrorHaNotifications,
                    nfcTagScannerEnabled = srcAdv.nfcTagScannerEnabled,
                    iBeaconEnabled = srcAdv.iBeaconEnabled,
                    // Device-local fields below get re-overlaid downstream;
                    // we keep applied's values here as a no-op since
                    // preserveDeviceLocal will clobber them anyway.
                    iBeaconUuid = appAdv.iBeaconUuid,
                    iBeaconMajor = appAdv.iBeaconMajor,
                    iBeaconMinor = appAdv.iBeaconMinor,
                    webhookEnabled = srcAdv.webhookEnabled,
                    webhookPort = appAdv.webhookPort,
                    webhookId = appAdv.webhookId,
                    mqttHost = appAdv.mqttHost,
                    mqttPort = appAdv.mqttPort,
                    mqttUsername = appAdv.mqttUsername,
                    mqttPassword = appAdv.mqttPassword,
                    mqttUseTls = appAdv.mqttUseTls,
                    mqttClientId = appAdv.mqttClientId,
                ),
            )
        }
    },
    AMBIENT(
        "Ambient display",
        "Ambient screensaver: enable, timeout, brightness, content toggles",
    ) {
        override fun preserve(applied: AppSettings, source: AppSettings): AppSettings =
            applied.copy(ambient = source.ambient)
    };

    /**
     * Copy this category's fields from [source] onto [applied], returning
     * the merged AppSettings. Used in two places:
     *   - On pull: if the user has excluded this category, call
     *     `preserve(applied = remoteApplied, source = localSnapshot)` so
     *     the remote's values for this category don't overwrite local.
     *   - On push: call `preserve(applied = liveSettings, source = defaults)`
     *     so the excluded category's fields land at their defaults in the
     *     uploaded payload, leaking nothing about local values.
     */
    abstract fun preserve(applied: AppSettings, source: AppSettings): AppSettings
}
