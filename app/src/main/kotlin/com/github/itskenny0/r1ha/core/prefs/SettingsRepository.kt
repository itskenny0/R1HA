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
import com.github.itskenny0.r1ha.core.util.areaLabel
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
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
        val wheelAccelCurve = stringPreferencesKey("wheel.accel_curve")

        val uiDisplayMode = stringPreferencesKey("ui.display_mode")
        val uiShowPill = booleanPreferencesKey("ui.show_pill")
        val uiShowArea = booleanPreferencesKey("ui.show_area")
        val uiCardStackIcons = booleanPreferencesKey("ui.card_stack_icons")
        // Main-view glance / face affordances (2026-06 deep-integration sprint).
        val uiShowSparkline = booleanPreferencesKey("ui.show_sparkline")
        val uiShowStatusBadges = booleanPreferencesKey("ui.show_status_badges")
        val uiFaceQuickControls = booleanPreferencesKey("ui.face_quick_controls")
        val uiSecondaryInfoDefault = stringPreferencesKey("ui.secondary_info_default")
        val uiDoubleTapMoreInfo = booleanPreferencesKey("ui.double_tap_more_info")
        val uiHardwareLongPressTarget = stringPreferencesKey("ui.hw_long_press_target")
        /** Legacy boolean for the position pip — preserved for back-compat
         *  reads only. Writes go to [uiPositionDotLocation] now. true →
         *  TOP_CENTER, false → HIDDEN at the migration site. */
        val uiShowDots = booleanPreferencesKey("ui.show_dots")
        /** Current preference: where the position pip sits. Stored as the
         *  [PositionDotLocation] enum name. Absent → migrate from the
         *  legacy boolean above, otherwise default to TOP_CENTER. */
        val uiPositionDotLocation = stringPreferencesKey("ui.position_dot_location")
        /** Where the main value bar (brightness / volume / setpoint slider)
         *  sits on every card. Stored as the [ValueBarLocation] enum name.
         *  Absent → default RIGHT (the historical right-edge layout). */
        val uiValueBarLocation = stringPreferencesKey("ui.value_bar_location")

        val behaviorHaptics = booleanPreferencesKey("behavior.haptics")
        val behaviorKeepOn = booleanPreferencesKey("behavior.keep_on")
        val behaviorTapToggle = booleanPreferencesKey("behavior.tap_toggle")
        val behaviorHideStatus = booleanPreferencesKey("behavior.hide_status_bar")
        val behaviorShowBatteryWhenHidden = booleanPreferencesKey("behavior.show_battery_when_status_bar_hidden")
        val behaviorStartOnDashboard = booleanPreferencesKey("behavior.start_on_dashboard")
        val behaviorWheelTogglesSwitches = booleanPreferencesKey("behavior.wheel_toggles_switches")
        val behaviorWheelTutorialSeen = booleanPreferencesKey("behavior.wheel_tutorial_seen")
        val behaviorLastSeenVersionCode = intPreferencesKey("behavior.last_seen_version_code")
        val behaviorShowWhatsNew = booleanPreferencesKey("behavior.show_whats_new")
        val behaviorToastLogLevel = stringPreferencesKey("behavior.toast_log_level")
        /** entity_id bound to the Android Quick Settings tile. Empty
         *  string sentinel = unbound (a null-equivalent that the
         *  preferences API can store; we map empty → null at read
         *  time). */
        val behaviorQuickTileEntityId = stringPreferencesKey("behavior.quick_tile_entity_id")
        val behaviorQuickTileEntityIdB = stringPreferencesKey("behavior.quick_tile_entity_id_b")
        val behaviorQuickTileEntityIdC = stringPreferencesKey("behavior.quick_tile_entity_id_c")
        val behaviorQuickTileEntityIdD = stringPreferencesKey("behavior.quick_tile_entity_id_d")
        val behaviorAssistAutoOpenKeyboard = booleanPreferencesKey("behavior.assist_auto_open_keyboard")
        val behaviorAssistAgentId = stringPreferencesKey("behavior.assist_agent_id")
        val behaviorVoiceSatellitePipelineId =
            stringPreferencesKey("behavior.voice_satellite_pipeline_id")
        val behaviorAssistMacros = stringPreferencesKey("behavior.assist_macros")
        val behaviorOrientationMode = stringPreferencesKey("behavior.orientation_mode")
        val advancedJson = stringPreferencesKey("advanced.json")
        val dashboardJson = stringPreferencesKey("dashboard.json")
        val navpanelJson = stringPreferencesKey("navpanel.json")
        val integrationsJson = stringPreferencesKey("integrations.json")
        /** One-shot guard for the 12 h -> 1 h logbook default change. The
         *  integrations blob is written with encodeDefaults, so every
         *  pre-change install carries an explicit 12 that is
         *  indistinguishable from a deliberate choice; until the first
         *  post-change settings write lands (which records the user's real
         *  current value and sets this flag), reads remap stored 12 -> 1. */
        val logbookWindowMigrated = booleanPreferencesKey("integrations.logbook_window_default_migrated")
        val connectionJson = stringPreferencesKey("connection.json")
        val logShippingJson = stringPreferencesKey("logshipping.json")
        val pagesJson = stringPreferencesKey("pages.json")
        val activePageId = stringPreferencesKey("active_page_id")
        /**
         * Round-robin cursor for the Settings "Featured" spotlight. Each app launch
         * advances it by the featured-group size (modulo the catalogue size) so the
         * trio cycles deterministically launch-to-launch. Absent → 0 (the same trio a
         * fresh install would show), so pre-feature installs see no surprise jump.
         */
        val featuredRotationIndex = intPreferencesKey("featured.rotation_index")
        /**
         * User-configurable hardware key bindings. JSON map of
         * `KeyAction.name -> [keycode]`. Empty / missing falls back to
         * [com.github.itskenny0.r1ha.core.input.DEFAULT_KEY_BINDINGS]; presence
         * (even with an empty list) overrides the default for that action so
         * users can intentionally unbind a key without resetting the rest.
         */
        val keyBindingsJson = stringPreferencesKey("input.key_bindings.json")
        val iotCameraJson = stringPreferencesKey("iot_camera.json")
        val iotSensorsJson = stringPreferencesKey("iot_sensors.json")
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
        /** Card-stack peek-deck presentation. Stored as the [CardPeekMode]
         *  enum name. Absent / unknown → AUTO (peek only on phone-portrait),
         *  so existing installs on the R1 / sub-compact tier see no change. */
        val uiCardPeekMode = stringPreferencesKey("ui.card_peek_mode")
        val uiLowPerfMode = stringPreferencesKey("ui.low_perf_mode")
        /** Width (dp) of the card value bar's invisible touch hit area. Absent → 24
         *  (the historical fixed hit-area width). Coerced to 24..72 on read so a stray
         *  value can't crowd out the card body or shrink below the original width. */
        val uiValueBarTapTargetDp = intPreferencesKey("ui.value_bar_tap_target_dp")
        /** Card-stack deck layout. Stored as the [DeckLayoutMode] enum name.
         *  Absent / unknown decodes as AUTO (full-viewport on R1 / compact,
         *  content-height DYNAMIC on medium+) via [DeckLayoutMode.fromStored],
         *  so existing small-screen installs see no change. */
        val uiDeckLayoutMode = stringPreferencesKey("ui.deck_layout_mode")
        /** Card-stack scroll sensitivity as a 0..100 percentage; 80 is the
         *  default and reproduces the stock fling feel. Absent → 80. */
        val uiCardScrollSensitivity = intPreferencesKey("ui.card_scroll_sensitivity")
        /** Deck-wide default for whether the ultra-detail more-info sheet is
         *  offered. Absent → true (the affordance is shown). */
        val uiMoreInfoEnabledDefault = booleanPreferencesKey("ui.more_info_enabled_default")
        /** Global text-size step. Stored as the [UiTextScale] enum name.
         *  Absent / unknown → DEFAULT (1.0×) so existing installs render
         *  byte-for-byte unchanged. */
        val uiTextScale = stringPreferencesKey("ui.text_scale")
        /** Legacy global font face from the fixed eight-face era. Stored as
         *  the [FontFace] enum name. Never written any more; read only as the
         *  migration source when [uiFontFamilyName] is absent. */
        val uiFontFace = stringPreferencesKey("ui.font_face")
        /** Global font family name ("" = the stock monospace-numerals + sans
         *  mix). Absent → fall back to mapping the legacy [uiFontFace] value,
         *  so eight-face-era installs keep their chosen look on upgrade. */
        val uiFontFamilyName = stringPreferencesKey("ui.font_family")
        /** 12/24-hour clock style for app-composed time readouts. Stored as
         *  the [ClockFormat] enum name. Absent / unknown → AUTO (follow the
         *  Android system setting, the historical behaviour). */
        val uiClockFormat = stringPreferencesKey("ui.clock_format")
        /** Shared list-row density. Stored as the [ListDensity] enum name.
         *  Absent / unknown → COMFORTABLE (the historical 48 dp rows). */
        val uiListDensity = stringPreferencesKey("ui.list_density")
        /** Relative ('5m ago') vs absolute ('14:32') timestamps. Stored as
         *  the [TimestampStyle] enum name. Absent / unknown → RELATIVE. */
        val uiTimestampStyle = stringPreferencesKey("ui.timestamp_style")
        /** Skip nav transitions + skeleton pulse. Absent → false (full motion). */
        val uiReduceMotion = booleanPreferencesKey("ui.reduce_motion")

        val theme = stringPreferencesKey("theme")
        val autoThemeEnabled = booleanPreferencesKey("theme.auto_enabled")
        val nightTheme = stringPreferencesKey("theme.night")
        val nightStartHour = intPreferencesKey("theme.night_start_hour")
        val nightEndHour = intPreferencesKey("theme.night_end_hour")
        /** Optional global accent ARGB override (Int.MIN_VALUE sentinel = unset). */
        val themeAccentArgb = intPreferencesKey("theme.accent_argb")
        /** "Colourful Cards" palette set + background design. Stored as the enum
         *  names; absent / unknown decode to VIVID / GRADIENT (the shipped look) via
         *  their `fromStored`, so existing installs see no change. Same string-name
         *  scheme as [uiDeckLayoutMode]. */
        val themeColorfulPaletteSet = stringPreferencesKey("theme.colorful_palette_set")
        val themeColorfulBackgroundDesign = stringPreferencesKey("theme.colorful_background_design")
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
        /**
         * Energy-view excluded power sensors — newline-separated entity ids. Each id
         * is URL-encoded on write (defensive; entity ids never contain newlines, but
         * the encode keeps the separator scheme uniform with [nameOverrides]). Absent
         * / empty = no exclusions, so existing installs see every power sensor counted.
         */
        val energyExcludedSensors = stringPreferencesKey("energy.excluded_sensors")
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
                    accelerationCurve = p[K.wheelAccelCurve]?.let { runCatching { AccelerationCurve.valueOf(it) }.getOrNull() } ?: AccelerationCurve.MEDIUM,
                ),
                ui = UiOptions(
                    displayMode = p[K.uiDisplayMode]?.let { runCatching { DisplayMode.valueOf(it) }.getOrNull() } ?: DisplayMode.PERCENT,
                    showOnOffPill = p[K.uiShowPill] ?: true,
                    showAreaLabel = p[K.uiShowArea] ?: true,
                    cardStackIcons = p[K.uiCardStackIcons] ?: true,
                    // New enum slot wins; legacy boolean is consulted only as
                    // a migration path. true → TOP_CENTER (the historical
                    // chrome-row position), false → HIDDEN.
                    positionDotLocation = p[K.uiPositionDotLocation]
                        ?.let { runCatching { PositionDotLocation.valueOf(it) }.getOrNull() }
                        ?: when (p[K.uiShowDots]) {
                            false -> PositionDotLocation.HIDDEN
                            else -> PositionDotLocation.TOP_CENTER
                        },
                    // Where the main value bar sits. Absent / unknown name
                    // falls back to RIGHT (the historical right-edge layout)
                    // so pre-feature installs see no change.
                    valueBarLocation = p[K.uiValueBarLocation]
                        ?.let { runCatching { ValueBarLocation.valueOf(it) }.getOrNull() }
                        ?: ValueBarLocation.RIGHT,
                    textHistoryLength = (p[K.uiTextHistoryLen] ?: 20).coerceIn(5, 100),
                    hideCardTailAbove = p[K.uiHideCardTail] ?: true,
                    maxDecimalPlaces = (p[K.uiMaxDecimals] ?: 2).coerceIn(0, 6),
                    tempUnit = p[K.uiTempUnit]?.let { runCatching { TemperatureUnit.valueOf(it) }.getOrNull() } ?: TemperatureUnit.CELSIUS,
                    infiniteScroll = p[K.uiInfiniteScroll] ?: false,
                    chromeButtons = decodeChromeButtons(p[K.uiChromeButtons]),
                    showZeroPercentWhenOff = p[K.uiShowZeroPercentWhenOff] ?: false,
                    // Absent / unknown name → AUTO. Existing installs have no
                    // key written, so they decode as AUTO and the R1 / sub-
                    // compact tier keeps full-viewport (AUTO never enables peek
                    // there) — no behaviour change on upgrade.
                    cardPeekMode = p[K.uiCardPeekMode]
                        ?.let { runCatching { CardPeekMode.valueOf(it) }.getOrNull() }
                        ?: CardPeekMode.AUTO,
                    lowPerfMode = p[K.uiLowPerfMode]
                        ?.let { runCatching { LowPerfMode.valueOf(it) }.getOrNull() }
                        ?: LowPerfMode.AUTO,
                    valueBarTapTargetDp = (p[K.uiValueBarTapTargetDp] ?: 24).coerceIn(24, 72),
                    // Lenient decode (fromStored): absent / unknown names fall back
                    // to AUTO, which keeps the R1 / compact tier on the historical
                    // full-viewport pager.
                    deckLayoutMode = DeckLayoutMode.fromStored(p[K.uiDeckLayoutMode]),
                    cardScrollSensitivity = (p[K.uiCardScrollSensitivity] ?: 80).coerceIn(0, 100),
                    moreInfoEnabledDefault = p[K.uiMoreInfoEnabledDefault] ?: true,
                    textScale = p[K.uiTextScale]
                        ?.let { runCatching { UiTextScale.valueOf(it) }.getOrNull() }
                        ?: UiTextScale.DEFAULT,
                    fontFamilyName = resolveFontFamilyName(
                        stored = p[K.uiFontFamilyName],
                        legacyFaceName = p[K.uiFontFace],
                    ),
                    clockFormat = p[K.uiClockFormat]
                        ?.let { runCatching { ClockFormat.valueOf(it) }.getOrNull() }
                        ?: ClockFormat.AUTO,
                    listDensity = p[K.uiListDensity]
                        ?.let { runCatching { ListDensity.valueOf(it) }.getOrNull() }
                        ?: ListDensity.COMFORTABLE,
                    timestampStyle = p[K.uiTimestampStyle]
                        ?.let { runCatching { TimestampStyle.valueOf(it) }.getOrNull() }
                        ?: TimestampStyle.RELATIVE,
                    reduceMotion = p[K.uiReduceMotion] ?: false,
                    showFaceSparkline = p[K.uiShowSparkline] ?: true,
                    showStatusBadges = p[K.uiShowStatusBadges] ?: true,
                    faceQuickControls = p[K.uiFaceQuickControls] ?: true,
                    secondaryInfoDefault = p[K.uiSecondaryInfoDefault]
                        ?.let { runCatching { SecondaryInfo.valueOf(it) }.getOrNull() }
                        ?: SecondaryInfo.LAST_CHANGED,
                    doubleTapMoreInfoDefault = p[K.uiDoubleTapMoreInfo] ?: false,
                    hardwareLongPressTarget = p[K.uiHardwareLongPressTarget]?.takeIf { it.isNotBlank() },
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
                    lastSeenVersionCode = p[K.behaviorLastSeenVersionCode] ?: 0,
                    showWhatsNew = p[K.behaviorShowWhatsNew] ?: true,
                    toastLogLevel = p[K.behaviorToastLogLevel]
                        ?.let { runCatching { ToastLogLevel.valueOf(it) }.getOrNull() }
                        ?: ToastLogLevel.OFF,
                    quickTileEntityId = p[K.behaviorQuickTileEntityId]?.takeIf { it.isNotBlank() },
                    quickTileEntityIdB = p[K.behaviorQuickTileEntityIdB]?.takeIf { it.isNotBlank() },
                    quickTileEntityIdC = p[K.behaviorQuickTileEntityIdC]?.takeIf { it.isNotBlank() },
                    quickTileEntityIdD = p[K.behaviorQuickTileEntityIdD]?.takeIf { it.isNotBlank() },
                    assistAutoOpenKeyboard = p[K.behaviorAssistAutoOpenKeyboard] ?: false,
                    assistAgentId = p[K.behaviorAssistAgentId]?.takeIf { it.isNotBlank() },
                    voiceSatellitePipelineId =
                        p[K.behaviorVoiceSatellitePipelineId]?.takeIf { it.isNotBlank() },
                    assistMacros = p[K.behaviorAssistMacros]
                        ?.split('\n')
                        ?.mapNotNull { line ->
                            // URL-decoded to recover newlines / equals inside the saved
                            // macro text. Empty lines are skipped so a trailing newline
                            // in the stored string doesn't surface as a blank chip.
                            runCatching {
                                java.net.URLDecoder.decode(line, Charsets.UTF_8.name())
                            }.getOrNull()?.takeIf { it.isNotBlank() }
                        }
                        ?: emptyList(),
                    orientationMode = p[K.behaviorOrientationMode]
                        ?.let { runCatching { OrientationMode.valueOf(it) }.getOrNull() }
                        ?: OrientationMode.FOLLOW_DEVICE,
                ),
                theme = p[K.theme]?.let { runCatching { ThemeId.valueOf(it) }.getOrNull() } ?: ThemeId.PRAGMATIC_HYBRID,
                autoThemeEnabled = p[K.autoThemeEnabled] ?: false,
                nightTheme = p[K.nightTheme]?.let { runCatching { ThemeId.valueOf(it) }.getOrNull() } ?: ThemeId.MINIMAL_DARK,
                nightStartHour = (p[K.nightStartHour] ?: 22).coerceIn(0, 23),
                nightEndHour = (p[K.nightEndHour] ?: 6).coerceIn(0, 23),
                themeAccentArgb = p[K.themeAccentArgb],
                colorfulPaletteSet = ColorfulPaletteSet.fromStored(p[K.themeColorfulPaletteSet]),
                colorfulBackgroundDesign = ColorfulBackgroundDesign.fromStored(p[K.themeColorfulBackgroundDesign]),
                guestModeEnabled = p[K.guestModeEnabled] ?: false,
                nameOverrides = decodeNameOverrides(p[K.nameOverrides]),
                entityOverrides = decodeEntityOverrides(p[K.entityOverrides]),
                energyExcludedSensors = decodeEnergyExcluded(p[K.energyExcludedSensors]),
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
                navPanel = p[K.navpanelJson]
                    ?.let {
                        runCatching {
                            advancedJson.decodeFromString(NavPanelSettings.serializer(), it)
                        }.getOrNull()
                    }
                    ?: NavPanelSettings(),
                integrations = (
                    p[K.integrationsJson]
                        ?.let {
                            runCatching {
                                advancedJson.decodeFromString(IntegrationsSettings.serializer(), it)
                            }.getOrNull()
                        }
                        ?: IntegrationsSettings()
                    ).let { integ ->
                        // See K.logbookWindowMigrated: remap the old baked-in
                        // 12 h default to the new 1 h until a post-change
                        // write records the user's actual choice.
                        if (p[K.logbookWindowMigrated] != true && integ.logbookDefaultWindowHours == 12) {
                            integ.copy(logbookDefaultWindowHours = 1)
                        } else integ
                    },
                connection = p[K.connectionJson]
                    ?.let {
                        runCatching {
                            advancedJson.decodeFromString(ConnectionSettings.serializer(), it)
                        }.getOrNull()
                    }
                    ?: ConnectionSettings(),
                logShipping = p[K.logShippingJson]
                    ?.let {
                        runCatching {
                            advancedJson.decodeFromString(LogShippingSettings.serializer(), it)
                        }.getOrNull()
                    }
                    ?: LogShippingSettings(),
                pages = decodePages(p[K.pagesJson], favorites),
                activePageId = p[K.activePageId].orEmpty(),
                // Absent → 0: a fresh install (or a pre-feature upgrade) starts the
                // rotation at the first group. Coerced non-negative so a corrupt
                // value can't break the modulo selection downstream.
                featuredRotationIndex = (p[K.featuredRotationIndex] ?: 0).coerceAtLeast(0),
                keyBindings = decodeKeyBindings(p[K.keyBindingsJson]),
                iotCamera = p[K.iotCameraJson]
                    ?.let {
                        runCatching {
                            advancedJson.decodeFromString(IotCameraSettings.serializer(), it)
                        }.getOrNull()
                    }
                    ?: IotCameraSettings(),
                iotSensors = p[K.iotSensorsJson]
                    ?.let {
                        runCatching {
                            advancedJson.decodeFromString(IotSensorsSettings.serializer(), it)
                        }.getOrNull()
                    }
                    ?: IotSensorsSettings(),
            )
        }
        .onEach { s ->
            R1Log.d("SettingsRepo.settings.emit", "server=${s.server?.url ?: "null"} favorites=${s.favorites.size} theme=${s.theme}")
        }
        // The map block above decodes ~10 JSON payloads (advanced, dashboard,
        // integrations, pages, iotCamera, iotSensors, chrome buttons, key bindings,
        // name + entity overrides) and rebuilds the whole AppSettings on every emit.
        // Most collectors are ViewModels whose flows run on Dispatchers.Main.immediate;
        // without flowOn that decode work runs on the main thread on every settings
        // change and every shadow tick. Move it to Default so the UI thread only sees
        // the finished AppSettings. flowOn affects only the upstream operators, so each
        // collector still receives emissions on its own dispatcher.
        .flowOn(Dispatchers.Default)

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
                p[K.wheelAccelCurve] = next.wheel.accelerationCurve.name
                p[K.uiDisplayMode] = next.ui.displayMode.name
                p[K.uiShowPill] = next.ui.showOnOffPill
                p[K.uiShowArea] = next.ui.showAreaLabel
                p[K.uiCardStackIcons] = next.ui.cardStackIcons
                // Write the new enum slot; also mirror to the legacy
                // boolean so an older build that ever rolls back can still
                // read a sane "show / hide" intent. true = anything except
                // HIDDEN counts as "show somewhere".
                p[K.uiPositionDotLocation] = next.ui.positionDotLocation.name
                p[K.uiShowDots] = next.ui.positionDotLocation != PositionDotLocation.HIDDEN
                p[K.uiValueBarLocation] = next.ui.valueBarLocation.name
                p[K.behaviorHaptics] = next.behavior.haptics
                p[K.behaviorKeepOn] = next.behavior.keepScreenOn
                p[K.behaviorTapToggle] = next.behavior.tapToToggle
                p[K.behaviorHideStatus] = next.behavior.hideStatusBar
                p[K.behaviorShowBatteryWhenHidden] = next.behavior.showBatteryWhenStatusBarHidden
                p[K.behaviorStartOnDashboard] = next.behavior.startOnDashboard
                p[K.behaviorWheelTogglesSwitches] = next.behavior.wheelTogglesSwitches
                p[K.behaviorWheelTutorialSeen] = next.behavior.wheelTutorialSeen
                p[K.behaviorLastSeenVersionCode] = next.behavior.lastSeenVersionCode
                p[K.behaviorShowWhatsNew] = next.behavior.showWhatsNew
                p[K.behaviorToastLogLevel] = next.behavior.toastLogLevel.name
                p[K.behaviorQuickTileEntityId] = next.behavior.quickTileEntityId.orEmpty()
                p[K.behaviorQuickTileEntityIdB] = next.behavior.quickTileEntityIdB.orEmpty()
                p[K.behaviorQuickTileEntityIdC] = next.behavior.quickTileEntityIdC.orEmpty()
                p[K.behaviorQuickTileEntityIdD] = next.behavior.quickTileEntityIdD.orEmpty()
                p[K.behaviorAssistAutoOpenKeyboard] = next.behavior.assistAutoOpenKeyboard
                p[K.behaviorAssistAgentId] = next.behavior.assistAgentId.orEmpty()
                p[K.behaviorVoiceSatellitePipelineId] =
                    next.behavior.voiceSatellitePipelineId.orEmpty()
                p[K.behaviorAssistMacros] = next.behavior.assistMacros
                    .filter { it.isNotBlank() }
                    .joinToString("\n") { line ->
                        // URL-encoded so a macro containing a literal newline or '='
                        // doesn't break the separator scheme on the next decode.
                        java.net.URLEncoder.encode(line, Charsets.UTF_8.name())
                    }
                p[K.behaviorOrientationMode] = next.behavior.orientationMode.name
                p[K.uiTextHistoryLen] = next.ui.textHistoryLength
                p[K.uiHideCardTail] = next.ui.hideCardTailAbove
                p[K.uiMaxDecimals] = next.ui.maxDecimalPlaces
                p[K.uiTempUnit] = next.ui.tempUnit.name
                p[K.uiInfiniteScroll] = next.ui.infiniteScroll
                p[K.uiChromeButtons] = encodeChromeButtons(next.ui.chromeButtons)
                p[K.uiShowZeroPercentWhenOff] = next.ui.showZeroPercentWhenOff
                p[K.uiCardPeekMode] = next.ui.cardPeekMode.name
                p[K.uiLowPerfMode] = next.ui.lowPerfMode.name
                p[K.uiValueBarTapTargetDp] = next.ui.valueBarTapTargetDp
                p[K.uiDeckLayoutMode] = next.ui.deckLayoutMode.name
                p[K.uiCardScrollSensitivity] = next.ui.cardScrollSensitivity
                p[K.uiMoreInfoEnabledDefault] = next.ui.moreInfoEnabledDefault
                p[K.uiTextScale] = next.ui.textScale.name
                p[K.uiFontFamilyName] = next.ui.fontFamilyName
                p[K.uiClockFormat] = next.ui.clockFormat.name
                p[K.uiListDensity] = next.ui.listDensity.name
                p[K.uiTimestampStyle] = next.ui.timestampStyle.name
                p[K.uiReduceMotion] = next.ui.reduceMotion
                p[K.uiShowSparkline] = next.ui.showFaceSparkline
                p[K.uiShowStatusBadges] = next.ui.showStatusBadges
                p[K.uiFaceQuickControls] = next.ui.faceQuickControls
                p[K.uiSecondaryInfoDefault] = next.ui.secondaryInfoDefault.name
                p[K.uiDoubleTapMoreInfo] = next.ui.doubleTapMoreInfoDefault
                val hwLp = next.ui.hardwareLongPressTarget
                if (hwLp.isNullOrBlank()) p.remove(K.uiHardwareLongPressTarget) else p[K.uiHardwareLongPressTarget] = hwLp
                p[K.theme] = next.theme.name
                p[K.autoThemeEnabled] = next.autoThemeEnabled
                p[K.nightTheme] = next.nightTheme.name
                p[K.nightStartHour] = next.nightStartHour
                p[K.nightEndHour] = next.nightEndHour
                val accent = next.themeAccentArgb
                if (accent == null) p.remove(K.themeAccentArgb) else p[K.themeAccentArgb] = accent
                p[K.themeColorfulPaletteSet] = next.colorfulPaletteSet.name
                p[K.themeColorfulBackgroundDesign] = next.colorfulBackgroundDesign.name
                p[K.guestModeEnabled] = next.guestModeEnabled
                p[K.nameOverrides] = encodeNameOverrides(next.nameOverrides)
                p[K.entityOverrides] = encodeEntityOverrides(next.entityOverrides)
                p[K.energyExcludedSensors] = encodeEnergyExcluded(next.energyExcludedSensors)
                p[K.advancedJson] = advancedJson.encodeToString(
                    AdvancedSettings.serializer(),
                    next.advanced,
                )
                p[K.dashboardJson] = advancedJson.encodeToString(
                    DashboardSettings.serializer(),
                    next.dashboard,
                )
                p[K.navpanelJson] = advancedJson.encodeToString(
                    NavPanelSettings.serializer(),
                    next.navPanel,
                )
                p[K.logbookWindowMigrated] = true
                p[K.integrationsJson] = advancedJson.encodeToString(
                    IntegrationsSettings.serializer(),
                    next.integrations,
                )
                p[K.connectionJson] = advancedJson.encodeToString(
                    ConnectionSettings.serializer(),
                    next.connection,
                )
                p[K.logShippingJson] = advancedJson.encodeToString(
                    LogShippingSettings.serializer(),
                    next.logShipping,
                )
                // Pages — encoded as JSON. Keep next.pages canonical and recompute
                // [favorites] as their flat union before writing so any legacy
                // single-list reader sees a consistent fallback.
                p[K.pagesJson] = advancedJson.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(FavoritePage.serializer()),
                    next.pages,
                )
                p[K.activePageId] = next.activePageId
                p[K.featuredRotationIndex] = next.featuredRotationIndex
                p[K.keyBindingsJson] = encodeKeyBindings(next.keyBindings)
                p[K.iotCameraJson] = advancedJson.encodeToString(
                    IotCameraSettings.serializer(),
                    next.iotCamera,
                )
                p[K.iotSensorsJson] = advancedJson.encodeToString(
                    IotSensorsSettings.serializer(),
                    next.iotSensors,
                )
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
                name = areaLabel(area).take(20),
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
     * Pin a surface ([Routes] route-id string) to the side navigation rail / drawer.
     * Appends to the end of [NavPanelSettings.pinnedSurfaces] so newly-pinned surfaces
     * land at the bottom of the user's pinned list. No-op when already pinned (keeps
     * its position rather than bouncing to the end on a redundant tap).
     */
    suspend fun pinSurface(routeId: String) {
        update { s ->
            val current = s.navPanel.pinnedSurfaces
            if (routeId in current) s
            else s.copy(navPanel = s.navPanel.copy(pinnedSurfaces = current + routeId))
        }
    }

    /** Remove a surface from the pinned side-panel list. No-op when not pinned. */
    suspend fun unpinSurface(routeId: String) {
        update { s ->
            val current = s.navPanel.pinnedSurfaces
            if (routeId !in current) s
            else s.copy(navPanel = s.navPanel.copy(pinnedSurfaces = current.filterNot { it == routeId }))
        }
    }

    /** Replace the whole pinned-surface list (used by a reorder / bulk-edit UI).
     *  De-duplicates while preserving first-seen order so a drag-reorder that
     *  momentarily double-lists an item can't persist a dupe. */
    suspend fun setPinnedSurfaces(routeIds: List<String>) {
        update { s -> s.copy(navPanel = s.navPanel.copy(pinnedSurfaces = routeIds.distinct())) }
    }

    /**
     * Pin a Lovelace dashboard VIEW (a full [Routes.dashboardsViewRoute] string) to the
     * side navigation rail / drawer + the phone card-stack drawer. Appends to the end of
     * [NavPanelSettings.pinnedDashboards] so newly-pinned views land at the bottom of the
     * pinned list. No-op when the same [route] is already pinned (keeps its position
     * rather than bouncing it to the end on a redundant tap); when already present we DO
     * refresh the stored title/icon so a renamed view's pin label stays current.
     */
    suspend fun pinDashboard(route: String, title: String, icon: String? = null) {
        update { s ->
            val current = s.navPanel.pinnedDashboards
            val existingIdx = current.indexOfFirst { it.route == route }
            val next = if (existingIdx >= 0) {
                // Already pinned — refresh its label/icon in place.
                current.toMutableList().also {
                    it[existingIdx] = it[existingIdx].copy(title = title, icon = icon)
                }
            } else {
                current + PinnedDashboard(route = route, title = title, icon = icon)
            }
            s.copy(navPanel = s.navPanel.copy(pinnedDashboards = next))
        }
    }

    /** Remove a pinned dashboard view from the side panel / phone drawer. No-op when not pinned. */
    suspend fun unpinDashboard(route: String) {
        update { s ->
            val current = s.navPanel.pinnedDashboards
            if (current.none { it.route == route }) s
            else s.copy(navPanel = s.navPanel.copy(pinnedDashboards = current.filterNot { it.route == route }))
        }
    }

    /** Replace the whole pinned-dashboard list (used by a reorder / bulk-edit UI).
     *  De-duplicates by route while preserving first-seen order so a drag-reorder that
     *  momentarily double-lists an item can't persist a dupe. */
    suspend fun setPinnedDashboards(dashboards: List<PinnedDashboard>) {
        update { s ->
            val deduped = dashboards.distinctBy { it.route }
            s.copy(navPanel = s.navPanel.copy(pinnedDashboards = deduped))
        }
    }

    /**
     * Exclude a `device_class=power` sensor from every Energy-view aggregate. No-op
     * when the id is blank or already excluded. The id is NOT validated here against
     * the safe-id pattern — the Jinja list builder
     * ([com.github.itskenny0.r1ha.feature.energy.EnergyTemplates.jinjaIdList]) drops
     * any id that doesn't match `^[a-z0-9_.]+$` at render time, so a stored junk id can
     * never reach the template; storing it lets the management UI still surface and
     * re-include it.
     */
    suspend fun excludeEnergySensor(entityId: String) {
        val id = entityId.trim()
        if (id.isEmpty()) return
        update { s ->
            if (s.energyExcludedSensors.contains(id)) s
            else s.copy(energyExcludedSensors = s.energyExcludedSensors + id)
        }
    }

    /** Re-include a previously-excluded Energy power sensor. No-op when not excluded. */
    suspend fun includeEnergySensor(entityId: String) {
        val id = entityId.trim()
        update { s ->
            if (!s.energyExcludedSensors.contains(id)) s
            else s.copy(energyExcludedSensors = s.energyExcludedSensors - id)
        }
    }

    /**
     * Pin an HA sidebar panel (identified by [urlPath]) to the side navigation
     * rail / drawer. Appends to the end of [NavPanelSettings.pinnedPanels] so
     * newly-pinned panels land at the bottom. When [urlPath] is already pinned,
     * refreshes its [title] and [icon] in place (so a renamed integration panel
     * stays current) without changing its position.
     */
    suspend fun pinPanel(urlPath: String, title: String, icon: String? = null) {
        update { s ->
            val current = s.navPanel.pinnedPanels
            val existingIdx = current.indexOfFirst { it.urlPath == urlPath }
            val next = if (existingIdx >= 0) {
                current.toMutableList().also {
                    it[existingIdx] = it[existingIdx].copy(title = title, icon = icon)
                }
            } else {
                current + PinnedPanel(urlPath = urlPath, title = title, icon = icon)
            }
            s.copy(navPanel = s.navPanel.copy(pinnedPanels = next))
        }
    }

    /** Remove a pinned HA panel from the side panel list. No-op when not pinned. */
    suspend fun unpinPanel(urlPath: String) {
        update { s ->
            val current = s.navPanel.pinnedPanels
            if (current.none { it.urlPath == urlPath }) s
            else s.copy(navPanel = s.navPanel.copy(pinnedPanels = current.filterNot { it.urlPath == urlPath }))
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

    /**
     * Decode the user's hardware key bindings — a JSON object mapping
     * `KeyAction.name` → list of Android `KeyEvent.KEYCODE_*` integer codes.
     * Returns an empty map for any malformed payload; the dispatcher then
     * falls back to [com.github.itskenny0.r1ha.core.input.DEFAULT_KEY_BINDINGS].
     * Unknown action names (added/removed across versions) are silently
     * dropped — this is the same forwards-compat strategy used for the
     * dashboard tile order list.
     */
    private fun decodeKeyBindings(raw: String?): Map<String, List<Int>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            advancedJson.decodeFromString(KEY_BINDINGS_SERIALIZER, raw)
        }.getOrElse { emptyMap() }
    }

    /** Inverse of [decodeKeyBindings]. */
    private fun encodeKeyBindings(map: Map<String, List<Int>>): String =
        advancedJson.encodeToString(KEY_BINDINGS_SERIALIZER, map)

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
private val pagesJsonParser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/**
 * Read-time resolution of [UiOptions.fontFamilyName]. The new `ui.font_family`
 * key wins whenever it has been written (including an explicit "" = stock mix);
 * otherwise the legacy eight-face `ui.font_face` enum name is mapped through
 * [fontFaceToFamilyName] so an upgrade keeps the user's chosen look without a
 * one-shot rewrite pass. Absent / unrecognised everything → "" (stock mix),
 * so fresh installs render byte-for-byte unchanged.
 */
internal fun resolveFontFamilyName(stored: String?, legacyFaceName: String?): String =
    stored
        ?: legacyFaceName
            ?.let { runCatching { FontFace.valueOf(it) }.getOrNull() }
            ?.let(::fontFaceToFamilyName)
        ?: ""

private fun decodePages(raw: String?, legacyFavorites: List<String>): List<FavoritePage> {
    val parsed = raw
        ?.takeIf { it.isNotBlank() }
        ?.let {
            runCatching {
                pagesJsonParser.decodeFromString(
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
 * Energy-view excluded power sensors. Stored as a newline-separated list of
 * URL-encoded entity ids; the set is deduplicated and blank ids dropped so a
 * stray empty line can't smuggle "" into the exclusion set. The URL-encode is
 * defensive (entity ids never carry a newline) but keeps the on-disk format
 * uniform with the other newline-separated keys. Empty set -> empty string.
 */
internal fun encodeEnergyExcluded(ids: Set<String>): String =
    ids.filter { it.isNotBlank() }
        .joinToString("\n") { java.net.URLEncoder.encode(it, "UTF-8") }

internal fun decodeEnergyExcluded(raw: String?): Set<String> {
    if (raw.isNullOrBlank()) return emptySet()
    return raw.split('\n').mapNotNull { line ->
        runCatching { java.net.URLDecoder.decode(line, "UTF-8") }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }.toSet()
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
        // Per-card hide-when-unavailable. Tri-state same as the other booleans.
        val hideStr = when (o.hideWhenUnavailable) { true -> "1"; false -> "0"; null -> "?" }
        // Custom action buttons — JSON-encoded then URL-encoded so the inner
        // payload's arbitrary characters (commas, quotes, pipes, the user's
        // service_data JSON) survive the pipe-separated row format. Empty list
        // collapses to "" so the existing parser's getOrNull stays safe.
        val customStr = if (o.customActions.isEmpty()) ""
            else java.net.URLEncoder.encode(
                kotlinx.serialization.json.Json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(CustomAction.serializer()),
                    o.customActions,
                ),
                "UTF-8",
            )
        // Per-card 'Require PIN to unlock' gate for lock entities (and the
        // hashed PIN itself). Tri-state for the gate slot mirrors the other
        // booleans; the hash slot is plain hex (never the raw PIN) and may
        // be blank to mean "any non-empty digit sequence accepted".
        val pinReqStr = when (o.requirePinToUnlock) { true -> "1"; false -> "0"; null -> "?" }
        val pinHashStr = o.requirePinHash.orEmpty()
        // Per-card position-pip slot — single-char code from PositionDotLocation
        // (same compactness trick LightCardButton uses), "?" = inherit global.
        val pipStr = o.positionDotLocation?.code?.toString() ?: "?"
        // Per-card glyph override — URL-encoded so emoji / spaces / pipes can't
        // break the row separator. Empty = inherit the domain default glyph.
        val glyphStr = o.glyphOverride
            ?.takeIf { it.isNotBlank() }
            ?.let { java.net.URLEncoder.encode(it, "UTF-8") }
            .orEmpty()
        // Per-card tap / wheel-press action overrides — single-char TapAction
        // code, "?" = inherit the card's default behaviour for that surface.
        val tapActionStr = o.actionOnTap?.code?.toString() ?: "?"
        val wheelPressStr = o.actionOnWheelPress?.code?.toString() ?: "?"
        // Per-card value-bar slot — single-char ValueBarLocation code,
        // "?" = inherit the global setting.
        val valueBarStr = o.valueBarLocation?.code?.toString() ?: "?"
        // Per-card ultra-detail more-info slot. Tri-state same as the other
        // booleans; "?" = inherit the global moreInfoEnabledDefault.
        val moreInfoStr = when (o.moreInfoEnabled) { true -> "1"; false -> "0"; null -> "?" }
        // Slots 20 / 21 — favourite light colours (ARGB ints) and favourite
        // cover/valve positions (0..100). Comma-joined; empty = "".
        val favColorsStr = o.favoriteColors.joinToString(",") { it.toString() }
        val favPosStr = o.favoritePositions.joinToString(",") { it.toString() }
        // Slots 22..25 — main-view glance / face affordances. Tri-state booleans
        // ("1"/"0"/"?") for sparkline / face-controls / double-tap-more-info;
        // single-char SecondaryInfo code ("?" = inherit) for the secondary line.
        val sparkStr = when (o.sparkline) { true -> "1"; false -> "0"; null -> "?" }
        val secInfoStr = o.secondaryInfo?.code?.toString() ?: "?"
        val faceCtlStr = when (o.faceControls) { true -> "1"; false -> "0"; null -> "?" }
        val dblTapStr = when (o.doubleTapMoreInfo) { true -> "1"; false -> "0"; null -> "?" }
        "$idEnc=$sizeStr|$pillStr|$areaStr|$lpEnc|$decStr|$accStr|$ctStr|$btnsStr|$tapStr|$whStr|$hideStr|$customStr|$pinReqStr|$pinHashStr|$pipStr|$glyphStr|$tapActionStr|$wheelPressStr|$valueBarStr|$moreInfoStr|$favColorsStr|$favPosStr|$sparkStr|$secInfoStr|$faceCtlStr|$dblTapStr"
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
            val hideUnavail = when (parts.getOrNull(10)) { "1" -> true; "0" -> false; else -> null }
            // Custom actions — URL-decoded JSON list. Decode failures fall
            // back to an empty list so a malformed save doesn't drop the rest
            // of the override's fields with it. Older saves (no slot 11)
            // also land here as empty via the same path.
            val customRaw = parts.getOrNull(11).orEmpty()
            val customActions: List<CustomAction> = if (customRaw.isBlank()) emptyList()
                else runCatching {
                    kotlinx.serialization.json.Json.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(CustomAction.serializer()),
                        java.net.URLDecoder.decode(customRaw, "UTF-8"),
                    )
                }.getOrDefault(emptyList())
            // Per-card lock PIN gate. Older saves without slots 12/13 land
            // here as null / blank via getOrNull and keep the previous
            // direct-toggle behaviour.
            val pinReq = when (parts.getOrNull(12)) { "1" -> true; "0" -> false; else -> null }
            val pinHash = parts.getOrNull(13)?.takeIf { it.isNotBlank() }
            // Per-card position-pip override. Slot 14 — single-char
            // PositionDotLocation code; unknown / blank / "?" = inherit.
            // Defensive: older saves without slot 14 decode as null and
            // keep the inherit-global behaviour.
            val pipChar = parts.getOrNull(14)?.firstOrNull()
            val pip = pipChar?.takeIf { it != '?' }?.let {
                com.github.itskenny0.r1ha.core.prefs.PositionDotLocation.fromCode(it)
            }
            // Slot 15 — URL-encoded glyph override. Blank = inherit
            // domain default. Decode failures fall back to null so a
            // garbled save doesn't kill the rest of the row.
            val glyphRaw = parts.getOrNull(15)?.takeIf { it.isNotBlank() }
            val glyph = glyphRaw?.let {
                runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull()
            }?.takeIf { it.isNotBlank() }
            // Slot 16 / 17 — per-card tap and wheel-press action codes.
            // Single-char TapAction codes; unknown / blank / "?" = inherit.
            val tapActionChar = parts.getOrNull(16)?.firstOrNull()
            val tapAction = tapActionChar?.takeIf { it != '?' }?.let {
                com.github.itskenny0.r1ha.core.prefs.TapAction.fromCode(it)
            }
            val wheelPressChar = parts.getOrNull(17)?.firstOrNull()
            val wheelPress = wheelPressChar?.takeIf { it != '?' }?.let {
                com.github.itskenny0.r1ha.core.prefs.TapAction.fromCode(it)
            }
            // Slot 18 — per-card value-bar location. Single-char
            // ValueBarLocation code; unknown / blank / "?" = inherit.
            // Older saves without slot 18 decode as null and keep the
            // inherit-global behaviour.
            val valueBarChar = parts.getOrNull(18)?.firstOrNull()
            val valueBar = valueBarChar?.takeIf { it != '?' }?.let {
                com.github.itskenny0.r1ha.core.prefs.ValueBarLocation.fromCode(it)
            }
            // Slot 19 — per-card ultra-detail more-info override. Tri-state
            // boolean; "?" / blank / absent (older saves) = inherit the
            // global moreInfoEnabledDefault.
            val moreInfo = when (parts.getOrNull(19)) { "1" -> true; "0" -> false; else -> null }
            // Slots 20 / 21 — favourite colours / positions. Comma-joined ints;
            // absent (older saves) or blank decode to empty. Unparseable entries
            // are skipped so one bad token doesn't drop the whole list.
            val favColors = parts.getOrNull(20)?.takeIf { it.isNotBlank() }
                ?.split(',')?.mapNotNull { it.toIntOrNull() }
                ?: emptyList()
            val favPositions = parts.getOrNull(21)?.takeIf { it.isNotBlank() }
                ?.split(',')?.mapNotNull { it.toIntOrNull()?.coerceIn(0, 100) }
                ?: emptyList()
            // Slots 22..25 — main-view glance / face affordances. Tri-state
            // booleans for sparkline / face-controls / double-tap-more-info;
            // single-char SecondaryInfo for the secondary line. Older saves
            // without these slots decode as null (inherit the global) via
            // getOrNull; unknown SecondaryInfo codes also fall back to inherit.
            val sparkline = when (parts.getOrNull(22)) { "1" -> true; "0" -> false; else -> null }
            val secInfoChar = parts.getOrNull(23)?.firstOrNull()
            val secondaryInfo = secInfoChar?.takeIf { it != '?' }?.let {
                com.github.itskenny0.r1ha.core.prefs.SecondaryInfo.fromCode(it)
            }
            val faceControls = when (parts.getOrNull(24)) { "1" -> true; "0" -> false; else -> null }
            val doubleTapMoreInfo = when (parts.getOrNull(25)) { "1" -> true; "0" -> false; else -> null }
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
                hideWhenUnavailable = hideUnavail,
                customActions = customActions,
                requirePinToUnlock = pinReq,
                requirePinHash = pinHash,
                positionDotLocation = pip,
                glyphOverride = glyph,
                actionOnTap = tapAction,
                actionOnWheelPress = wheelPress,
                valueBarLocation = valueBar,
                moreInfoEnabled = moreInfo,
                favoriteColors = favColors,
                favoritePositions = favPositions,
                sparkline = sparkline,
                secondaryInfo = secondaryInfo,
                faceControls = faceControls,
                doubleTapMoreInfo = doubleTapMoreInfo,
            )
        }.getOrNull()
    }.toMap()
}

/** Shared serializer for the user key bindings map. Re-used by encode + decode. */
private val KEY_BINDINGS_SERIALIZER = MapSerializer(
    String.serializer(),
    ListSerializer(Int.serializer()),
)
