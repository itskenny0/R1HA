package com.github.itskenny0.r1ha.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.toBackup
import com.github.itskenny0.r1ha.core.prefs.DisplayMode
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.ThemeId
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.prefs.WheelKeySource
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
) : ViewModel() {

    val state: StateFlow<AppSettings> = settings.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings(),
    )

    // ── Wheel ───────────────────────────────────────────────────────────────

    fun setWheelStep(step: Int) = update { it.copy(wheel = it.wheel.copy(stepPercent = step)) }
    fun setWheelAcceleration(enabled: Boolean) = update { it.copy(wheel = it.wheel.copy(acceleration = enabled)) }
    fun setWheelInvert(inverted: Boolean) = update { it.copy(wheel = it.wheel.copy(invertDirection = inverted)) }
    fun setWheelKeySource(source: WheelKeySource) = update { it.copy(wheel = it.wheel.copy(keySource = source)) }
    fun setAccelerationCurve(curve: com.github.itskenny0.r1ha.core.prefs.AccelerationCurve) =
        update { it.copy(wheel = it.wheel.copy(accelerationCurve = curve)) }

    // ── Card UI ─────────────────────────────────────────────────────────────

    fun setDisplayMode(mode: DisplayMode) = update { it.copy(ui = it.ui.copy(displayMode = mode)) }
    fun setShowOnOffPill(show: Boolean) = update { it.copy(ui = it.ui.copy(showOnOffPill = show)) }
    fun setShowAreaLabel(show: Boolean) = update { it.copy(ui = it.ui.copy(showAreaLabel = show)) }
    fun setShowPositionDots(show: Boolean) = update { it.copy(ui = it.ui.copy(showPositionDots = show)) }
    fun setHideCardTailAbove(hide: Boolean) = update { it.copy(ui = it.ui.copy(hideCardTailAbove = hide)) }
    fun setMaxDecimalPlaces(n: Int) = update { it.copy(ui = it.ui.copy(maxDecimalPlaces = n)) }
    fun setTempUnit(u: com.github.itskenny0.r1ha.core.prefs.TemperatureUnit) =
        update { it.copy(ui = it.ui.copy(tempUnit = u)) }
    fun setInfiniteScroll(enabled: Boolean) = update { it.copy(ui = it.ui.copy(infiniteScroll = enabled)) }
    fun setShowZeroPercentWhenOff(enabled: Boolean) =
        update { it.copy(ui = it.ui.copy(showZeroPercentWhenOff = enabled)) }

    /** Toggle a chrome-row button's visibility. The persistence layer force-
     *  enables GEAR regardless of what's passed here (so a hostile manual edit
     *  can't strand the user), but the UI also refuses to invoke this for GEAR,
     *  so the request never reaches here in practice. */
    fun setChromeButtonEnabled(
        ref: com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef,
        enabled: Boolean,
    ) = update { current ->
        val updated = current.ui.chromeButtons.map { cfg ->
            if (cfg.ref == ref) cfg.copy(enabled = enabled) else cfg
        }
        current.copy(ui = current.ui.copy(chromeButtons = updated))
    }

    /** Move the chrome-row button at [fromIndex] to [toIndex]. Used by the
     *  Settings → Chrome buttons reorder UI to commit each ↑ / ↓ tap. Bounds-
     *  checked here so a stale click against an out-of-date list shape can't
     *  throw IndexOutOfBounds. */
    fun moveChromeButton(fromIndex: Int, toIndex: Int) = update { current ->
        val list = current.ui.chromeButtons.toMutableList()
        if (fromIndex !in list.indices) return@update current
        val target = toIndex.coerceIn(0, list.size - 1)
        if (target == fromIndex) return@update current
        val moved = list.removeAt(fromIndex)
        list.add(target, moved)
        current.copy(ui = current.ui.copy(chromeButtons = list))
    }

    // ── Behavior ────────────────────────────────────────────────────────────

    fun setHaptics(enabled: Boolean) = update { it.copy(behavior = it.behavior.copy(haptics = enabled)) }
    fun setKeepScreenOn(enabled: Boolean) = update { it.copy(behavior = it.behavior.copy(keepScreenOn = enabled)) }
    fun setTapToToggle(enabled: Boolean) = update { it.copy(behavior = it.behavior.copy(tapToToggle = enabled)) }
    fun setHideStatusBar(enabled: Boolean) = update { it.copy(behavior = it.behavior.copy(hideStatusBar = enabled)) }
    fun setShowBatteryWhenStatusBarHidden(enabled: Boolean) = update {
        it.copy(behavior = it.behavior.copy(showBatteryWhenStatusBarHidden = enabled))
    }
    fun setStartOnDashboard(enabled: Boolean) = update {
        it.copy(behavior = it.behavior.copy(startOnDashboard = enabled))
    }
    fun setWheelTogglesSwitches(enabled: Boolean) =
        update { it.copy(behavior = it.behavior.copy(wheelTogglesSwitches = enabled)) }

    fun setToastLogLevel(level: com.github.itskenny0.r1ha.core.prefs.ToastLogLevel) =
        update { it.copy(behavior = it.behavior.copy(toastLogLevel = level)) }

    /** Bind one entity_id to the Android Quick Settings tile. Empty
     *  / blank clears the binding so the tile renders its 'tap to
     *  open app' placeholder. */
    fun setQuickTileEntityId(entityId: String?) =
        update {
            val cleaned = entityId?.trim()?.takeIf { it.isNotBlank() }
            it.copy(behavior = it.behavior.copy(quickTileEntityId = cleaned))
        }

    /** Whether opening Assist auto-focuses the input + pops the
     *  keyboard. Off by default; the user can flip this on if they
     *  prefer keyboard-first interaction. */
    fun setAssistAutoOpenKeyboard(enabled: Boolean) =
        update { it.copy(behavior = it.behavior.copy(assistAutoOpenKeyboard = enabled)) }

    fun setOrientationMode(mode: com.github.itskenny0.r1ha.core.prefs.OrientationMode) =
        update { it.copy(behavior = it.behavior.copy(orientationMode = mode)) }

    /**
     * Read-only "guest" mode. When set, every outbound service call is
     * refused at the repository so a guest holding the phone can't toggle
     * anything by accident.
     */
    fun setGuestModeEnabled(enabled: Boolean) =
        update { it.copy(guestModeEnabled = enabled) }

    /**
     * Generic mutator for the [AdvancedSettings] sub-struct. The dev-menu screen
     * uses this to update fields one at a time without each field needing its own
     * VM method — there are roughly two dozen advanced toggles and writing a setter
     * per knob would dwarf the rest of the VM.
     */
    fun updateAdvanced(transform: (com.github.itskenny0.r1ha.core.prefs.AdvancedSettings) -> com.github.itskenny0.r1ha.core.prefs.AdvancedSettings) =
        update { it.copy(advanced = transform(it.advanced)) }

    /** Generic mutator for the dashboard's per-section visibility +
     *  threshold settings. Used by the DASHBOARD section in
     *  SettingsScreen to flip any toggle / nudge any threshold without
     *  one setter per field. */
    fun updateDashboard(transform: (com.github.itskenny0.r1ha.core.prefs.DashboardSettings) -> com.github.itskenny0.r1ha.core.prefs.DashboardSettings) =
        update { it.copy(dashboard = transform(it.dashboard)) }

    /** Generic mutator for the per-surface refresh intervals +
     *  integration tweaks. Used by the INTEGRATIONS section in
     *  SettingsScreen. */
    fun updateIntegrations(transform: (com.github.itskenny0.r1ha.core.prefs.IntegrationsSettings) -> com.github.itskenny0.r1ha.core.prefs.IntegrationsSettings) =
        update { it.copy(integrations = transform(it.integrations)) }

    // ── Appearance ──────────────────────────────────────────────────────────

    fun setTheme(themeId: ThemeId) = update { it.copy(theme = themeId) }

    // ── Account ─────────────────────────────────────────────────────────────

    /**
     * Sign out: clears tokens and the server URL so the next launch routes back to onboarding.
     * Reports completion via toast so the user knows it landed.
     */
    fun signOut(onAfter: () -> Unit) {
        viewModelScope.launch {
            R1Log.i("Settings.signOut", "starting")
            tokens.clear()
            settings.update { it.copy(server = null) }
            // Wipe the WebView cookie jar so re-signing in doesn't silently land
            // on the previous HA session. Without this, the OAuth in-app WebView
            // sees the existing HA cookies and skips the login form entirely,
            // which is wrong after the user explicitly signed out.
            runCatching {
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                android.webkit.CookieManager.getInstance().flush()
                android.webkit.WebStorage.getInstance().deleteAllData()
            }
            R1Log.i("Settings.signOut", "done")
            Toaster.show("Signed out")
            onAfter()
        }
    }

    /**
     * Reset every user-tunable setting to its default. Preserves the user's
     * account (server URL + tokens), favourites lists, and pages so they don't
     * have to re-onboard. Surfaced as a two-stage confirm action in the
     * Settings screen — first tap arms, second tap commits.
     *
     * Implemented as a single [SettingsRepository.update] so the reset is
     * atomic; intermediate observers don't see half-reset state.
     */
    fun resetToDefaults() {
        viewModelScope.launch {
            settings.update { s ->
                AppSettings(
                    server = s.server,
                    favorites = s.favorites,
                    pages = s.pages,
                    activePageId = s.activePageId,
                    // Drop name overrides, entity overrides, custom wheel /
                    // ui / behavior / advanced settings, theme back to
                    // PragmaticHybridTheme (the post-onboarding default).
                )
            }
            Toaster.show("Settings reset to defaults")
            R1Log.i("Settings.reset", "wiped overrides + advanced settings; preserved server + favourites + pages")
        }
    }

    /**
     * Reset only one category's slice of settings to defaults. Bounded blast
     * radius — useful when a user accidentally messed up their wheel curve
     * and doesn't want to lose the rest of their tuning. Server, favourites
     * and pages are never touched regardless of which category resets.
     *
     * Categories that don't map to a discrete AppSettings sub-object (SERVER —
     * has its own sign-out path; DASHBOARD — lives partially in pages which we
     * preserve) are no-ops.
     */
    fun resetCategory(category: com.github.itskenny0.r1ha.core.prefs.SettingCategory) {
        viewModelScope.launch {
            val defaults = AppSettings()
            settings.update { s ->
                when (category) {
                    com.github.itskenny0.r1ha.core.prefs.SettingCategory.INPUT ->
                        s.copy(wheel = defaults.wheel)
                    com.github.itskenny0.r1ha.core.prefs.SettingCategory.CARD_UI ->
                        s.copy(ui = defaults.ui)
                    com.github.itskenny0.r1ha.core.prefs.SettingCategory.BEHAVIOUR ->
                        s.copy(behavior = defaults.behavior)
                    com.github.itskenny0.r1ha.core.prefs.SettingCategory.APPEARANCE ->
                        s.copy(theme = defaults.theme)
                    com.github.itskenny0.r1ha.core.prefs.SettingCategory.INTEGRATIONS ->
                        s.copy(integrations = defaults.integrations)
                    com.github.itskenny0.r1ha.core.prefs.SettingCategory.SERVER,
                    com.github.itskenny0.r1ha.core.prefs.SettingCategory.DASHBOARD,
                    -> s // server has its own sign-out path; dashboard lives in pages which we preserve.
                }
            }
            Toaster.show("${category.label} reset")
            R1Log.i("Settings.reset", "category=${category.name}")
        }
    }

    // ── Backup & restore ────────────────────────────────────────────────────

    /**
     * Capture the current settings as an [com.github.itskenny0.r1ha.core.prefs.AppBackup]
     * JSON blob and hand it to [onReady] on the main thread. The caller — the
     * Settings screen with a SAF CreateDocument launcher in hand — writes the
     * blob to the user-picked file.
     *
     * We read the latest settings synchronously from the flow's current value
     * rather than re-querying DataStore: the StateFlow has the up-to-date
     * snapshot and avoids a second async hop that would race against any
     * concurrent setting change.
     */
    fun exportBackupBlob(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val now = java.time.Instant.now().toString()
            val backup = state.value.toBackup(now)
            val raw = com.github.itskenny0.r1ha.core.prefs.encodeBackup(backup)
            onReady(raw)
        }
    }

    /**
     * Parse [raw] as an [com.github.itskenny0.r1ha.core.prefs.AppBackup] and
     * apply it atomically. On success surfaces a confirmation toast; on
     * malformed input fires an expandable failure toast with the parser's
     * message so the user knows why the import didn't take.
     */
    fun importBackupBlob(raw: String) {
        viewModelScope.launch {
            val parsed = runCatching {
                com.github.itskenny0.r1ha.core.prefs.decodeBackup(raw)
            }
            parsed.fold(
                onSuccess = { backup ->
                    settings.applyBackup(backup)
                    R1Log.i(
                        "Settings.importBackup",
                        "restored v${backup.version} from ${backup.createdAt} (${backup.pages.size} pages, ${backup.favorites.size} favourites)",
                    )
                    Toaster.show("Backup restored")
                },
                onFailure = { t ->
                    R1Log.w("Settings.importBackup", "parse failed: ${t.message}")
                    Toaster.errorExpandable(
                        shortText = "Restore failed: ${t.message?.take(28) ?: "bad JSON"}",
                        fullText = "Couldn't parse backup file.\n\n${t.message ?: t.toString()}",
                    )
                },
            )
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settings.update(transform) }
    }

    companion object {
        fun factory(settings: SettingsRepository, tokens: TokenStore) = viewModelFactory {
            initializer { SettingsViewModel(settings = settings, tokens = tokens) }
        }
    }
}
