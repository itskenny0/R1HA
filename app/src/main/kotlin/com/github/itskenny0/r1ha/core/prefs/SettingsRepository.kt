package com.github.itskenny0.r1ha.core.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.preferencesDataStoreFile
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Datastore-singleton property delegate. Using the `preferencesDataStore` delegate (instead of
 * `PreferenceDataStoreFactory.create`) guarantees one DataStore instance per file, per process,
 * regardless of how many SettingsRepository instances exist.
 */
private val Context.r1haSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "r1ha_settings")

/**
 * SharedPreferences shadow store. DataStore is the canonical source of truth, but if it ever
 * returns a stale or empty read (which has been observed on some custom-ROM device builds),
 * the SharedPreferences shadow provides a bulletproof fallback for the few critical fields —
 * the server URL above all, since losing it strands the user.
 */
private const val SHADOW_PREFS = "r1ha_shadow"
private const val SHADOW_SERVER_URL = "server.url"
private const val SHADOW_HA_VERSION = "server.ha_version"
private const val SHADOW_FAVORITES = "favorites" // newline-separated, same format as DataStore

/**
 * Marker set on every successful shadow write so reads can distinguish "shadow never written
 * yet — fall back to DataStore" from "shadow explicitly says no server URL" (which must take
 * priority over a stale DataStore value, otherwise sign-out doesn't stick when the DataStore
 * delete silently fails).
 */
private const val SHADOW_INITIALIZED = "_initialized"

class SettingsRepository private constructor(
    private val store: DataStore<Preferences>,
    private val shadow: SharedPreferences,
) {

    // Single JSON instance for AdvancedSettings persistence. Lenient + ignoring
    // unknown keys so older saves (with fewer fields) and newer saves (with extra
    // fields the running build doesn't know about yet) both decode cleanly.
    private val advancedJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Tick channel that fires whenever the shadow store is written. Combined with
     * `store.data` so the public `settings` Flow re-emits even when a write only landed
     * in the shadow (DataStore commit failed for whatever reason on the device).
     */
    private val shadowChanges = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    ).also { it.tryEmit(Unit) }

    /**
     * Serialises [update] so concurrent callers don't read-modify-write on top of each other.
     * Without this, two fast taps on a favourites toggle would both read the pre-tap value,
     * each apply their delta to it, and the second write would clobber the first.
     */
    private val updateMutex = Mutex()

    /** Production constructor: uses the singleton DataStore delegate and a stable shadow file. */
    constructor(context: Context) : this(
        store = context.applicationContext.r1haSettingsStore,
        shadow = context.applicationContext.getSharedPreferences(SHADOW_PREFS, Context.MODE_PRIVATE),
    )

    companion object {
        /**
         * Test-only factory. Each invocation gets an isolated DataStore file plus an isolated
         * SharedPreferences instance, so tests don't share state with production or with each
         * other. Not intended for production callers.
         */
        fun forTesting(
            context: Context,
            datastoreName: String,
            shadowName: String = "${datastoreName}_shadow",
        ): SettingsRepository {
            val appContext = context.applicationContext
            return SettingsRepository(
                store = PreferenceDataStoreFactory.create(
                    produceFile = { appContext.preferencesDataStoreFile(datastoreName) },
                ),
                shadow = appContext.getSharedPreferences(shadowName, Context.MODE_PRIVATE),
            )
        }
    }

    private object K {
        val serverUrl = stringPreferencesKey("server.url")
        val haVersion = stringPreferencesKey("server.ha_version")
        val favorites = stringPreferencesKey("favorites")

        val wheelStep = intPreferencesKey("wheel.step")
        val wheelAccel = booleanPreferencesKey("wheel.accel")
        val wheelInvert = booleanPreferencesKey("wheel.invert")
        val wheelKeySource = stringPreferencesKey("wheel.key_source")
        val wheelAccelCurve = stringPreferencesKey("wheel.accel_curve")

        val uiDisplayMode = stringPreferencesKey("ui.display_mode")
        val uiShowPill = booleanPreferencesKey("ui.show_pill")
        val uiShowArea = booleanPreferencesKey("ui.show_area")
        val uiShowDots = booleanPreferencesKey("ui.show_dots")

        val behaviorHaptics = booleanPreferencesKey("behavior.haptics")
        val behaviorKeepOn = booleanPreferencesKey("behavior.keep_on")
        val behaviorTapToggle = booleanPreferencesKey("behavior.tap_toggle")
        val behaviorHideStatus = booleanPreferencesKey("behavior.hide_status_bar")
        val behaviorShowBatteryWhenHidden = booleanPreferencesKey("behavior.show_battery_when_status_bar_hidden")
        val behaviorStartOnDashboard = booleanPreferencesKey("behavior.start_on_dashboard")
        val behaviorWheelTogglesSwitches = booleanPreferencesKey("behavior.wheel_toggles_switches")
        val behaviorWheelTutorialSeen = booleanPreferencesKey("behavior.wheel_tutorial_seen")
        val behaviorToastLogLevel = stringPreferencesKey("behavior.toast_log_level")
        /** entity_id bound to the Android Quick Settings tile. Empty
         *  string sentinel = unbound (a null-equivalent that the
         *  preferences API can store; we map empty → null at read
         *  time). */
        val behaviorQuickTileEntityId = stringPreferencesKey("behavior.quick_tile_entity_id")
        val behaviorAssistAutoOpenKeyboard = booleanPreferencesKey("behavior.assist_auto_open_keyboard")
        val behaviorOrientationMode = stringPreferencesKey("behavior.orientation_mode")
        val advancedJson = stringPreferencesKey("advanced.json")
        val dashboardJson = stringPreferencesKey("dashboard.json")
        val integrationsJson = stringPreferencesKey("integrations.json")
        val pagesJson = stringPreferencesKey("pages.json")
        val activePageId = stringPreferencesKey("active_page_id")
        val uiTextHistoryLen = intPreferencesKey("ui.text_history_length")
        val uiHideCardTail = booleanPreferencesKey("ui.hide_card_tail")
        val uiMaxDecimals = intPreferencesKey("ui.max_decimals")
        val uiTempUnit = stringPreferencesKey("ui.temp_unit")
        val uiInfiniteScroll = booleanPreferencesKey("ui.infinite_scroll")
        // Chrome-row button order + visibility — stored as a JSON-encoded list of
        // {ref, enabled} entries. JSON-shape rather than parallel per-button keys
        // because the list both reorders AND toggles, and storing the order as a
        // canonical string is simpler than juggling N integer-keyed slots whose
        // semantics change on every reorder.
        val uiChromeButtons = stringPreferencesKey("ui.chrome_buttons.json")
        val uiShowZeroPercentWhenOff = booleanPreferencesKey("ui.show_zero_percent_when_off")

        val theme = stringPreferencesKey("theme")
        /** Optional global accent ARGB override (Int.MIN_VALUE sentinel = unset). */
        val themeAccentArgb = intPreferencesKey("theme.accent_argb")
        /** "Read-only guest mode" toggle — refuses outbound service calls. */
        val guestModeEnabled = booleanPreferencesKey("guest_mode_enabled")
        /**
         * Encoded as a single newline-separated string of `entityId=customName` pairs;
         * names are URL-encoded so newlines/equals inside a name can't break the
         * separator scheme. Kept in one preference key (vs a key per entity) so the
         * preference file stays manageable and migrations are easy.
         */
        val nameOverrides = stringPreferencesKey("name_overrides")
        /**
         * Per-entity customization map. Same newline-separated URL-encoded encoding as
         * [nameOverrides], but each value is `scale|pill|area|longpress` (with `?` for
         * "inherit" on the nullable fields). Kept compact so the preference file stays
         * small even with hundreds of customized cards.
         */
        val entityOverrides = stringPreferencesKey("entity_overrides")
    }

    val settings: Flow<AppSettings> = combine(
        store.data
            .catch { t ->
                R1Log.e("SettingsRepo", "store.data threw, emitting emptyPreferences()", t)
                emit(emptyPreferences())
            },
        shadowChanges,
    ) { p, _ -> p }
        .map { p ->
            // Once the shadow has been written at least once it becomes the authoritative
            // source for `server` and `favorites`. update() writes the shadow synchronously
            // before kicking off the asynchronous DataStore write, so a shadow with the
            // initialized marker is always at-least-as-fresh as DataStore — and crucially the
            // shadow ALSO authoritatively reports "no server" / "no favourites" when the user
            // signed out, even if the DataStore delete silently failed.
            val shadowInit = shadow.getBoolean(SHADOW_INITIALIZED, false)
            val url = if (shadowInit) {
                shadow.getString(SHADOW_SERVER_URL, null)
            } else {
                p[K.serverUrl] ?: shadow.getString(SHADOW_SERVER_URL, null)
            }
            val haVersion = if (shadowInit) {
                shadow.getString(SHADOW_HA_VERSION, null)
            } else {
                p[K.haVersion] ?: shadow.getString(SHADOW_HA_VERSION, null)
            }
            val server = url?.takeIf { it.isNotBlank() }?.let { ServerConfig(url = it, haVersion = haVersion) }
            val favorites = if (shadowInit) {
                shadow.getString(SHADOW_FAVORITES, null)?.takeIf { it.isNotBlank() }?.split('\n').orEmpty()
            } else {
                p[K.favorites]?.takeIf { it.isNotBlank() }?.split('\n')
                    ?: shadow.getString(SHADOW_FAVORITES, null)?.takeIf { it.isNotBlank() }?.split('\n').orEmpty()
            }
            AppSettings(
                server = server,
                favorites = favorites,
                wheel = WheelSettings(
                    stepPercent = (p[K.wheelStep] ?: 2).coerceIn(1, 10),
                    acceleration = p[K.wheelAccel] ?: true,
                    invertDirection = p[K.wheelInvert] ?: false,
                    keySource = p[K.wheelKeySource]?.let { runCatching { WheelKeySource.valueOf(it) }.getOrNull() } ?: WheelKeySource.AUTO,
                    accelerationCurve = p[K.wheelAccelCurve]?.let { runCatching { AccelerationCurve.valueOf(it) }.getOrNull() } ?: AccelerationCurve.MEDIUM,
                ),
                ui = UiOptions(
                    displayMode = p[K.uiDisplayMode]?.let { runCatching { DisplayMode.valueOf(it) }.getOrNull() } ?: DisplayMode.PERCENT,
                    showOnOffPill = p[K.uiShowPill] ?: true,
                    showAreaLabel = p[K.uiShowArea] ?: true,
                    showPositionDots = p[K.uiShowDots] ?: true,
                    textHistoryLength = (p[K.uiTextHistoryLen] ?: 20).coerceIn(5, 100),
                    hideCardTailAbove = p[K.uiHideCardTail] ?: true,
                    maxDecimalPlaces = (p[K.uiMaxDecimals] ?: 2).coerceIn(0, 6),
                    tempUnit = p[K.uiTempUnit]?.let { runCatching { TemperatureUnit.valueOf(it) }.getOrNull() } ?: TemperatureUnit.CELSIUS,
                    infiniteScroll = p[K.uiInfiniteScroll] ?: false,
                    chromeButtons = decodeChromeButtons(p[K.uiChromeButtons]),
                    showZeroPercentWhenOff = p[K.uiShowZeroPercentWhenOff] ?: false,
                ),
                behavior = Behavior(
                    haptics = p[K.behaviorHaptics] ?: true,
                    keepScreenOn = p[K.behaviorKeepOn] ?: true,
                    tapToToggle = p[K.behaviorTapToggle] ?: false,
                    hideStatusBar = p[K.behaviorHideStatus] ?: false,
                    showBatteryWhenStatusBarHidden = p[K.behaviorShowBatteryWhenHidden] ?: false,
                    startOnDashboard = p[K.behaviorStartOnDashboard] ?: false,
                    wheelTogglesSwitches = p[K.behaviorWheelTogglesSwitches] ?: true,
                    wheelTutorialSeen = p[K.behaviorWheelTutorialSeen] ?: false,
                    toastLogLevel = p[K.behaviorToastLogLevel]
                        ?.let { runCatching { ToastLogLevel.valueOf(it) }.getOrNull() }
                        ?: ToastLogLevel.OFF,
                    quickTileEntityId = p[K.behaviorQuickTileEntityId]?.takeIf { it.isNotBlank() },
                    assistAutoOpenKeyboard = p[K.behaviorAssistAutoOpenKeyboard] ?: false,
                    orientationMode = p[K.behaviorOrientationMode]
                        ?.let { runCatching { OrientationMode.valueOf(it) }.getOrNull() }
                        ?: OrientationMode.FOLLOW_DEVICE,
                ),
                theme = p[K.theme]?.let { runCatching { ThemeId.valueOf(it) }.getOrNull() } ?: ThemeId.PRAGMATIC_HYBRID,
                themeAccentArgb = p[K.themeAccentArgb],
                guestModeEnabled = p[K.guestModeEnabled] ?: false,
                nameOverrides = decodeNameOverrides(p[K.nameOverrides]),
                entityOverrides = decodeEntityOverrides(p[K.entityOverrides]),
                advanced = p[K.advancedJson]
                    ?.let {
                        runCatching {
                            advancedJson.decodeFromString(AdvancedSettings.serializer(), it)
                        }.getOrNull()
                    }
                    ?: AdvancedSettings(),
                dashboard = p[K.dashboardJson]
                    ?.let {
                        runCatching {
                            advancedJson.decodeFromString(DashboardSettings.serializer(), it)
                        }.getOrNull()
                    }
                    ?: DashboardSettings(),
                integrations = p[K.integrationsJson]
                    ?.let {
                        runCatching {
                            advancedJson.decodeFromString(IntegrationsSettings.serializer(), it)
                        }.getOrNull()
                    }
                    ?: IntegrationsSettings(),
                pages = decodePages(p[K.pagesJson], favorites),
                activePageId = p[K.activePageId].orEmpty(),
            )
        }
        .onEach { s ->
            R1Log.d("SettingsRepo.settings.emit", "server=${s.server?.url ?: "null"} favorites=${s.favorites.size} theme=${s.theme}")
        }

    suspend fun update(transform: (AppSettings) -> AppSettings): Unit = updateMutex.withLock {
        val current = currentBlocking()
        val transformed = transform(current)
        // Reconcile pages ↔ favorites so the legacy single-list reader stays in
        // sync without the caller needing to know about both. Two cases:
        //  1. Caller mutated `pages` (or `pages` was empty pre-migration) — derive
        //     favorites from the union.
        //  2. Caller mutated only the legacy `favorites` field (older call sites,
        //     tests) — push those favorites into the active page and rebuild the
        //     union. Without this branch a caller that copies just `favorites`
        //     would silently lose the write because [pages] already covered it
        //     before with empty contents.
        // Also clamp [activePageId] to an actually-existing page so an old saved id
        // (deleted page) doesn't leave the card stack pointing at nothing.
        val pagesUntouched = transformed.pages == current.pages
        val favoritesChanged = transformed.favorites != current.favorites
        val seededPages = transformed.pages.ifEmpty {
            listOf(FavoritePage("home", "HOME", transformed.favorites))
        }
        val activeIdResolved = seededPages.firstOrNull { it.id == transformed.activePageId }?.id
            ?: seededPages.first().id
        val effectivePages = if (pagesUntouched && favoritesChanged) {
            // Legacy-style write — only `favorites` changed. Treat it as a write
            // to the active page so the data lands in the new schema cleanly.
            seededPages.map { p ->
                if (p.id == activeIdResolved) p.copy(favorites = transformed.favorites) else p
            }
        } else {
            seededPages
        }
        val unionFavorites = effectivePages.flatMap { it.favorites }.distinct()
        val next = transformed.copy(
            pages = effectivePages,
            favorites = unionFavorites,
            activePageId = activeIdResolved,
        )
        R1Log.i("SettingsRepo.update", "current.server=${current.server?.url ?: "null"} -> next.server=${next.server?.url ?: "null"}")

        // Write shadow synchronously FIRST so a SharedPreferences commit lands even if the
        // DataStore edit below fails for any reason. The synchronous commit() can block on
        // disk I/O so we move it off whatever dispatcher the caller is on.
        withContext(Dispatchers.IO) { writeShadow(next.server, next.favorites) }

        try {
            store.edit { p ->
                next.server?.let { server ->
                    p[K.serverUrl] = server.url
                    if (server.haVersion != null) p[K.haVersion] = server.haVersion
                    else p.remove(K.haVersion)
                } ?: run {
                    p.remove(K.serverUrl); p.remove(K.haVersion)
                }
                p[K.favorites] = next.favorites.joinToString("\n")
                p[K.wheelStep] = next.wheel.stepPercent
                p[K.wheelAccel] = next.wheel.acceleration
                p[K.wheelInvert] = next.wheel.invertDirection
                p[K.wheelKeySource] = next.wheel.keySource.name
                p[K.wheelAccelCurve] = next.wheel.accelerationCurve.name
                p[K.uiDisplayMode] = next.ui.displayMode.name
                p[K.uiShowPill] = next.ui.showOnOffPill
                p[K.uiShowArea] = next.ui.showAreaLabel
                p[K.uiShowDots] = next.ui.showPositionDots
                p[K.behaviorHaptics] = next.behavior.haptics
                p[K.behaviorKeepOn] = next.behavior.keepScreenOn
                p[K.behaviorTapToggle] = next.behavior.tapToToggle
                p[K.behaviorHideStatus] = next.behavior.hideStatusBar
                p[K.behaviorShowBatteryWhenHidden] = next.behavior.showBatteryWhenStatusBarHidden
                p[K.behaviorStartOnDashboard] = next.behavior.startOnDashboard
                p[K.behaviorWheelTogglesSwitches] = next.behavior.wheelTogglesSwitches
                p[K.behaviorWheelTutorialSeen] = next.behavior.wheelTutorialSeen
                p[K.behaviorToastLogLevel] = next.behavior.toastLogLevel.name
                p[K.behaviorQuickTileEntityId] = next.behavior.quickTileEntityId.orEmpty()
                p[K.behaviorAssistAutoOpenKeyboard] = next.behavior.assistAutoOpenKeyboard
                p[K.behaviorOrientationMode] = next.behavior.orientationMode.name
                p[K.uiTextHistoryLen] = next.ui.textHistoryLength
                p[K.uiHideCardTail] = next.ui.hideCardTailAbove
                p[K.uiMaxDecimals] = next.ui.maxDecimalPlaces
                p[K.uiTempUnit] = next.ui.tempUnit.name
                p[K.uiInfiniteScroll] = next.ui.infiniteScroll
                p[K.uiChromeButtons] = encodeChromeButtons(next.ui.chromeButtons)
                p[K.uiShowZeroPercentWhenOff] = next.ui.showZeroPercentWhenOff
                p[K.theme] = next.theme.name
                val accent = next.themeAccentArgb
                if (accent == null) p.remove(K.themeAccentArgb) else p[K.themeAccentArgb] = accent
                p[K.guestModeEnabled] = next.guestModeEnabled
                p[K.nameOverrides] = encodeNameOverrides(next.nameOverrides)
                p[K.entityOverrides] = encodeEntityOverrides(next.entityOverrides)
                p[K.advancedJson] = advancedJson.encodeToString(
                    AdvancedSettings.serializer(),
                    next.advanced,
                )
                p[K.dashboardJson] = advancedJson.encodeToString(
                    DashboardSettings.serializer(),
                    next.dashboard,
                )
                p[K.integrationsJson] = advancedJson.encodeToString(
                    IntegrationsSettings.serializer(),
                    next.integrations,
                )
                // Pages — encoded as JSON. Keep next.pages canonical and recompute
                // [favorites] as their flat union before writing so any legacy
                // single-list reader sees a consistent fallback.
                p[K.pagesJson] = advancedJson.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(FavoritePage.serializer()),
                    next.pages,
                )
                p[K.activePageId] = next.activePageId
            }
            R1Log.i("SettingsRepo.update", "DataStore edit completed; next.server=${next.server?.url ?: "null"}")
        } catch (t: Throwable) {
            R1Log.e("SettingsRepo.update", "DataStore edit threw; shadow has the value as a fallback", t)
            // Only toast on failure (and the shadow store will keep things working).
            Toaster.error("Settings save failed. Using fallback storage")
            // Don't rethrow — the shadow store has the critical bits, and the caller (typically
            // OnboardingViewModel) should not be forced to error out the user's flow if only the
            // DataStore commit failed.
        }
    }

    /**
     * Mutate the currently-active page in place. Most favourites-list operations
     * (toggle, reorder, move) historically targeted [AppSettings.favorites] as a
     * single global list; with tabs they target the active page only. This helper
     * keeps the call sites unchanged in shape while threading the right page id.
     * No-op when the active page id doesn't resolve (rare race during page delete).
     */
    suspend fun updateActivePage(transform: (FavoritePage) -> FavoritePage) {
        update { s ->
            val idx = s.pages.indexOfFirst { it.id == s.activePageId }
            if (idx < 0) return@update s
            val updated = s.pages.toMutableList()
            updated[idx] = transform(updated[idx])
            s.copy(pages = updated)
        }
    }

    /**
     * Append one [FavoritePage] per HA area, each pre-populated with the
     * caller-supplied entity ids for that area. Existing pages are left
     * intact; the new pages are appended at the end and the first newly-
     * created page becomes the active tab so the user can see the result
     * immediately. Empty areas are skipped so a HA install with 30
     * declared areas but only 5 with entities doesn't produce 25 empty
     * tabs. Returns the count of pages actually created.
     */
    suspend fun generatePagesFromAreas(areas: List<Pair<String, List<String>>>): Int {
        val nonEmpty = areas.filter { it.second.isNotEmpty() }
        if (nonEmpty.isEmpty()) return 0
        val newPages = nonEmpty.map { (area, entityIds) ->
            val newId = "p" + java.util.UUID.randomUUID().toString().replace("-", "").take(8)
            FavoritePage(
                id = newId,
                name = area.replace('_', ' ').uppercase().take(20),
                favorites = entityIds,
            )
        }
        update { s ->
            s.copy(
                pages = s.pages + newPages,
                activePageId = newPages.first().id,
            )
        }
        return newPages.size
    }

    /** Append a fresh empty page and switch the active id to it. Returns the new
     *  page's id so callers can immediately render its (empty) deck. */
    suspend fun addPage(name: String): String {
        val newId = "p" + java.util.UUID.randomUUID().toString().replace("-", "").take(8)
        update { s ->
            s.copy(
                pages = s.pages + FavoritePage(id = newId, name = name, favorites = emptyList()),
                activePageId = newId,
            )
        }
        return newId
    }

    /** Rename [pageId] to [name]. No-op when the id doesn't exist. */
    /** Update the per-page icon (single Unicode glyph). Null clears it.
     *  Mutates the named page in place; no-op when the id doesn't resolve. */
    suspend fun setPageIcon(pageId: String, icon: String?) {
        update { s ->
            val idx = s.pages.indexOfFirst { it.id == pageId }
            if (idx < 0) return@update s
            val updated = s.pages.toMutableList()
            updated[idx] = updated[idx].copy(icon = icon)
            s.copy(pages = updated)
        }
    }

    /** Update the per-page accent colour (ARGB int). Null = inherit the
     *  default warm accent. Mutates the named page in place; no-op when the
     *  id doesn't resolve. */
    suspend fun setPageAccent(pageId: String, accentArgb: Int?) {
        update { s ->
            val idx = s.pages.indexOfFirst { it.id == pageId }
            if (idx < 0) return@update s
            val updated = s.pages.toMutableList()
            updated[idx] = updated[idx].copy(accentArgb = accentArgb)
            s.copy(pages = updated)
        }
    }

    suspend fun renamePage(pageId: String, name: String) {
        update { s ->
            val idx = s.pages.indexOfFirst { it.id == pageId }
            if (idx < 0) return@update s
            val updated = s.pages.toMutableList()
            updated[idx] = updated[idx].copy(name = name)
            s.copy(pages = updated)
        }
    }

    /**
     * Delete [pageId]. Refuses to delete the only page (every install always has
     * at least one). If the deleted page was active, the previous page becomes
     * active (or the first one when there's no previous).
     */
    suspend fun deletePage(pageId: String) {
        update { s ->
            if (s.pages.size <= 1) return@update s
            val idx = s.pages.indexOfFirst { it.id == pageId }
            if (idx < 0) return@update s
            val updated = s.pages.toMutableList().apply { removeAt(idx) }
            val newActive = if (s.activePageId == pageId) {
                updated.getOrNull(idx - 1)?.id ?: updated.first().id
            } else s.activePageId
            s.copy(pages = updated, activePageId = newActive)
        }
    }

    /** Move the page at [from] to [to] in the page list. Used by a future
     *  page-reorder UI; safe no-op when indices are out of range or equal. */
    suspend fun reorderPages(from: Int, to: Int) {
        update { s ->
            if (from !in s.pages.indices) return@update s
            val clamped = to.coerceIn(0, s.pages.size - 1)
            if (from == clamped) return@update s
            val moved = s.pages.toMutableList()
            val item = moved.removeAt(from)
            moved.add(clamped, item)
            s.copy(pages = moved)
        }
    }

    /** Set [pageId] as the active tab. Persisted so a relaunch lands on the same
     *  page; clamped to a valid page in [update]. No-op when [pageId] already
     *  equals the current active id so we don't re-emit a fresh AppSettings
     *  for a redundant write — the previous behaviour caused a feedback loop
     *  on the horizontal pager when an external observer (PageDeck's
     *  snapshotFlow) pushed the already-current page id back to the VM. */
    suspend fun setActivePage(pageId: String) {
        update { s ->
            if (s.activePageId == pageId) s else s.copy(activePageId = pageId)
        }
    }

    /**
     * Atomically restore an [AppBackup] on top of the current settings. Wraps
     * [AppBackup.applyOnto] in a single [update] call so the favourites union
     * + activePageId clamp logic runs once on the merged result; no half-
     * applied state is ever visible to the rest of the app.
     */
    suspend fun applyBackup(backup: AppBackup) {
        update { current -> backup.applyOnto(current) }
    }

    /**
     * Decode the persisted chrome-button list, falling back to the canonical
     * default order on any failure (legacy installs, JSON that doesn't parse,
     * empty value). After decode we also REPAIR the list:
     *   - any [ChromeButtonRef] not present in the stored list gets appended at
     *     the end so a future migration that adds a new button shows up for
     *     existing users without losing their custom order;
     *   - GEAR is force-enabled regardless of what's stored, matching the
     *     UI's required-on rule so the user can never lose the path back to
     *     Settings even if a corrupt write disabled it.
     *   - Duplicate refs (which the UI shouldn't produce but defensive code
     *     can't assume) collapse to the first occurrence.
     */
    private fun decodeChromeButtons(raw: String?): List<ChromeButtonConfig> {
        val parsed = if (raw.isNullOrBlank()) emptyList() else runCatching {
            advancedJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(ChromeButtonConfig.serializer()),
                raw,
            )
        }.getOrElse { emptyList() }
        // De-dupe by ref, preserving first occurrence.
        val seen = HashSet<ChromeButtonRef>()
        val deduped = parsed.filter { seen.add(it.ref) }.toMutableList()
        // Append any missing refs at the end with their default enabled state.
        for (ref in ChromeButtonRef.entries) {
            if (ref !in seen) deduped += ChromeButtonConfig(ref, enabled = true)
        }
        // GEAR is always-on at the persistence layer; the Settings UI also
        // refuses to flip it, but a hostile manual edit shouldn't be able to
        // strand the user.
        return deduped.map {
            if (it.ref == ChromeButtonRef.GEAR) it.copy(enabled = true) else it
        }
    }

    /** Inverse of [decodeChromeButtons]. */
    private fun encodeChromeButtons(list: List<ChromeButtonConfig>): String =
        advancedJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(ChromeButtonConfig.serializer()),
            list,
        )

    private fun writeShadow(server: ServerConfig?, favorites: List<String>) {
        val editor = shadow.edit()
        if (server != null) {
            editor.putString(SHADOW_SERVER_URL, server.url)
            if (server.haVersion != null) editor.putString(SHADOW_HA_VERSION, server.haVersion)
            else editor.remove(SHADOW_HA_VERSION)
        } else {
            editor.remove(SHADOW_SERVER_URL)
            editor.remove(SHADOW_HA_VERSION)
        }
        if (favorites.isNotEmpty()) {
            editor.putString(SHADOW_FAVORITES, favorites.joinToString("\n"))
        } else {
            editor.remove(SHADOW_FAVORITES)
        }
        // Mark the shadow as initialized so the read path treats "absence of values" as
        // intentional (signed out / no favourites) rather than "fall back to DataStore".
        editor.putBoolean(SHADOW_INITIALIZED, true)
        val ok = editor.commit() // synchronous; we want to know if it actually wrote
        R1Log.i("SettingsRepo.writeShadow", "server=${server?.url ?: "null"} favorites=${favorites.size} commit=$ok")
        if (!ok) {
            // Only toast on FAILURE; success would otherwise spam the user on every settings edit.
            Toaster.error("Storage failed. Please reboot the device")
        }
        // Tick the settings Flow so observers re-read — even if the DataStore commit below
        // fails, observers see the updated shadow values.
        shadowChanges.tryEmit(Unit)
    }

    private suspend fun currentBlocking(): AppSettings = settings.first()
}

/**
 * Serialise the override map to a single newline-separated string of `entityId=name`
 * pairs, with both the entity_id and the name URL-encoded so the separators can't appear
 * inside a value. URL-encoding is far cheaper than a real serializer here — the map
 * stays small (one entry per renamed entity, never more than a few dozen) and the
 * format round-trips cleanly via [decodeNameOverrides].
 */
/**
 * Decode the persisted [AppSettings.pages] list. Three branches:
 *  1. Stored JSON exists → decode and return as-is.
 *  2. No stored JSON but legacy [legacyFavorites] is non-empty → migrate to a
 *     single 'HOME' page so the user keeps their existing list.
 *  3. Nothing at all → return a single empty 'HOME' page so downstream code
 *     never has to handle the empty-pages case.
 * The id 'home' is reserved for the migration page so even users who later
 * delete and re-create get a stable default id.
 */
private fun decodePages(raw: String?, legacyFavorites: List<String>): List<FavoritePage> {
    val parser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    val parsed = raw
        ?.takeIf { it.isNotBlank() }
        ?.let {
            runCatching {
                parser.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(FavoritePage.serializer()),
                    it,
                )
            }.getOrNull()
        }
    return when {
        !parsed.isNullOrEmpty() -> parsed
        legacyFavorites.isNotEmpty() -> listOf(FavoritePage("home", "HOME", legacyFavorites))
        else -> listOf(FavoritePage("home", "HOME", emptyList()))
    }
}

private fun encodeNameOverrides(map: Map<String, String>): String {
    if (map.isEmpty()) return ""
    return map.entries.joinToString("\n") { (id, name) ->
        "${java.net.URLEncoder.encode(id, "UTF-8")}=${java.net.URLEncoder.encode(name, "UTF-8")}"
    }
}

private fun decodeNameOverrides(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split('\n').mapNotNull { line ->
        val eq = line.indexOf('=')
        if (eq < 0) return@mapNotNull null
        runCatching {
            val id = java.net.URLDecoder.decode(line.substring(0, eq), "UTF-8")
            val name = java.net.URLDecoder.decode(line.substring(eq + 1), "UTF-8")
            if (id.isBlank() || name.isBlank()) null else id to name
        }.getOrNull()
    }.toMap()
}

/**
 * Per-entity customization map. Format per line: `urlEncodedId=scale|pill|area|longpress`
 * where `pill` and `area` are "0"/"1"/"?" (false / true / inherit) and `longpress` is
 * URL-encoded (or empty for "no action"). Parser is forgiving — missing trailing fields
 * default to inherit, malformed lines are skipped with a log. Future fields append after
 * `longpress` and stay backward-compatible by virtue of being absent on old saves.
 */
// Visible to tests so we can round-trip the encoding format without going through
// DataStore. Kept package-private (file-level) so production callers still go through
// SettingsRepository.update / settings to read/write.
internal fun encodeEntityOverrides_visibleForTesting(map: Map<String, EntityOverride>): String =
    encodeEntityOverrides(map)
internal fun decodeEntityOverrides_visibleForTesting(raw: String?): Map<String, EntityOverride> =
    decodeEntityOverrides(raw)

private fun encodeEntityOverrides(map: Map<String, EntityOverride>): String {
    if (map.isEmpty()) return ""
    return map.entries.joinToString("\n") { (id, o) ->
        val idEnc = java.net.URLEncoder.encode(id, "UTF-8")
        val pillStr = when (o.showOnOffPill) { true -> "1"; false -> "0"; null -> "?" }
        val areaStr = when (o.showAreaLabel) { true -> "1"; false -> "0"; null -> "?" }
        val lpEnc = o.longPressTarget?.let { java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
        val decStr = o.maxDecimalPlaces?.toString() ?: "?"
        val accStr = o.accentColor?.toString() ?: "?"
        val ctStr = o.lightColorTempK?.toString() ?: "?"
        // Text size: stored as integer sp (e.g. "28"). "?" = inherit theme default.
        val sizeStr = o.textSizeSp?.toString() ?: "?"
        // Hidden light buttons — concatenated single-char codes (BWHF) for each
        // hidden button. Empty = nothing hidden (all supported buttons visible).
        val btnsStr = if (o.lightButtonsHidden.isEmpty()) ""
            else o.lightButtonsHidden.map { it.code }.sorted().joinToString("")
        // Per-card tap-to-toggle override. Same tri-state encoding as pill/area.
        val tapStr = when (o.tapToToggle) { true -> "1"; false -> "0"; null -> "?" }
        // Per-card wheel-enabled override. Tri-state shape mirrors tap.
        val whStr = when (o.wheelEnabled) { true -> "1"; false -> "0"; null -> "?" }
        "$idEnc=$sizeStr|$pillStr|$areaStr|$lpEnc|$decStr|$accStr|$ctStr|$btnsStr|$tapStr|$whStr"
    }
}

private fun decodeEntityOverrides(raw: String?): Map<String, EntityOverride> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split('\n').mapNotNull { line ->
        val eq = line.indexOf('=')
        if (eq < 0) return@mapNotNull null
        runCatching {
            val id = java.net.URLDecoder.decode(line.substring(0, eq), "UTF-8")
            if (id.isBlank()) return@runCatching null
            val parts = line.substring(eq + 1).split('|')
            // Legacy migration: the first slot used to hold a 0.1..2.0 float multiplier.
            // New format stores integer sp (e.g. "28"). Detect format by whether the
            // string parses as Int first; fall back to Float-scale × 72 sp default.
            val sizeRaw = parts.getOrNull(0)
            val size: Int? = when {
                sizeRaw == null || sizeRaw == "?" || sizeRaw.isBlank() -> null
                sizeRaw.toIntOrNull() != null -> sizeRaw.toInt().coerceIn(1, 256)
                sizeRaw.toFloatOrNull() != null -> {
                    val legacyScale = sizeRaw.toFloat().coerceIn(0.1f, 2.0f)
                    // Map the old multiplier into absolute sp via the default 72 sp.
                    // Don't reject scale=1.0 as "no override" because the user may have
                    // explicitly selected it; preserve the explicit value.
                    (legacyScale * EntityOverride.DEFAULT_TEXT_SIZE_SP).toInt().coerceIn(1, 256)
                }
                else -> null
            }
            val pill = when (parts.getOrNull(1)) { "1" -> true; "0" -> false; else -> null }
            val area = when (parts.getOrNull(2)) { "1" -> true; "0" -> false; else -> null }
            val lpRaw = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
            val lp = lpRaw?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            val dec = parts.getOrNull(4)?.toIntOrNull()?.coerceIn(0, 6)
            val acc = parts.getOrNull(5)?.toIntOrNull()
            val ct = parts.getOrNull(6)?.toIntOrNull()?.coerceIn(1000, 10000)
            // Hidden light buttons — each char in the blob is a button code (B/W/H/F).
            // Unknown chars are silently ignored so future codes don't crash older
            // builds that ever decode a newer save.
            val btnsBlob = parts.getOrNull(7).orEmpty()
            val buttons = if (btnsBlob.isBlank()) emptySet()
                else btnsBlob.mapNotNull { com.github.itskenny0.r1ha.core.prefs.LightCardButton.fromCode(it) }.toSet()
            val tap = when (parts.getOrNull(8)) { "1" -> true; "0" -> false; else -> null }
            val wheel = when (parts.getOrNull(9)) { "1" -> true; "0" -> false; else -> null }
            id to EntityOverride(
                textSizeSp = size,
                showOnOffPill = pill,
                showAreaLabel = area,
                longPressTarget = lp?.takeIf { it.isNotBlank() },
                maxDecimalPlaces = dec,
                accentColor = acc,
                lightColorTempK = ct,
                lightButtonsHidden = buttons,
                tapToToggle = tap,
                wheelEnabled = wheel,
            )
        }.getOrNull()
    }.toMap()
}
