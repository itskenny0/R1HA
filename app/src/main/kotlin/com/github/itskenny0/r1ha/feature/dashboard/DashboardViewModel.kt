package com.github.itskenny0.r1ha.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.PersistentNotification
import com.github.itskenny0.r1ha.core.ha.parseHaInstant
import com.github.itskenny0.r1ha.feature.calendars.parseCalendarInstant
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * Drives the Today dashboard: a single at-a-glance home screen
 * composed of outdoor weather, persons home/away, the next calendar
 * event, camera count, and HA notification count.
 *
 * All five sections come from `/api/states` (filtered by domain prefix)
 * via [HaRepository.listRawEntitiesByDomain] in parallel. The user
 * sees partial data as soon as the first section lands. Sections
 * that fail their fetch surface as "—" rather than blowing up the
 * whole dashboard.
 */
class DashboardViewModel(
    private val haRepository: HaRepository,
    private val settings: com.github.itskenny0.r1ha.core.prefs.SettingsRepository,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class WeatherSummary(
        val name: String,
        val condition: String,
        val temperature: Double?,
        val temperatureUnit: String?,
        /** "Feels like" reading (HA `apparent_temperature`). Same unit as
         *  [temperature]; null when the integration doesn't report it. */
        val apparentTemperature: Double? = null,
        /** Relative humidity percentage (HA `humidity`); null when absent. */
        val humidity: Int? = null,
    )

    @androidx.compose.runtime.Stable
    data class PersonsSummary(
        val homeCount: Int,
        val awayCount: Int,
        /** Each entry is "name → state". Limited to 6 for layout. */
        val rows: List<Pair<String, String>>,
        /** Total person count; when > rows.size, the dashboard
         *  appends an 'and N more' affordance instead of silently
         *  truncating. */
        val total: Int,
    )

    @androidx.compose.runtime.Stable
    data class CalendarSummary(
        val calendarName: String,
        val eventTitle: String,
        val eventStart: Instant?,
        val happeningNow: Boolean,
        /** True when the upstream event is an all-day entry (HA emits
         *  these as date-only start strings without a time component).
         *  Lets the dashboard show an ALL-DAY pill so the user doesn't
         *  expect a precise time. */
        val allDay: Boolean,
    )

    @androidx.compose.runtime.Stable
    data class SunSummary(
        val state: String,
        val elevation: Double?,
        val nextRising: Instant?,
        val nextSetting: Instant?,
    )

    @androidx.compose.runtime.Stable
    data class MediaSummary(
        val entityId: String,
        val name: String,
        val state: String,
        val title: String?,
        val artist: String?,
        /** Whether the player advertises previous-track support
         *  (HA `supported_features` PREVIOUS_TRACK bit). The transport
         *  row hides the ◄◄ control when false so the dashboard never
         *  offers a control the player can't honour. */
        val canPrev: Boolean = true,
        /** Next-track support (NEXT_TRACK bit). */
        val canNext: Boolean = true,
        /** Play-or-pause support (PLAY or PAUSE bit). When false the
         *  play-pause control is hidden, leaving just the now-playing
         *  readout. */
        val canPlayPause: Boolean = true,
    )

    /** [MediaPlayerEntityFeature] bits we gate the transport row on.
     *  Mirrors HA's `MediaPlayerEntityFeature` enum so we only render a
     *  control when the player advertises support for it. */
    private object MediaFeature {
        const val PAUSE = 1
        const val PREVIOUS_TRACK = 16
        const val NEXT_TRACK = 32
        const val PLAY = 16384
    }

    @androidx.compose.runtime.Stable
    data class TimerSummary(
        val entityId: String,
        val name: String,
        val state: String, // active / paused / idle
        val finishesAt: Instant?,
        /** HH:MM:SS string from HA's `remaining` attribute; only
         *  meaningful when paused. For active timers HA exposes
         *  finishes_at and the UI ticks down off that instead. */
        val remaining: String? = null,
    )

    /** One low-battery row: the entity id (for the history drill-in), its
     *  friendly name (shown to the user), and the integer percent. */
    @androidx.compose.runtime.Stable
    data class LowBattery(
        val entityId: String,
        val name: String,
        val pct: Int,
    )

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        /** True while a user-initiated refresh (pull gesture) is in flight;
         *  drives the pull-to-refresh indicator. The periodic auto-refresh
         *  keeps it false so the indicator doesn't pop on every tick. */
        val refreshing: Boolean = false,
        val weather: WeatherSummary? = null,
        val persons: PersonsSummary? = null,
        val nextEvent: CalendarSummary? = null,
        val sun: SunSummary? = null,
        val cameraCount: Int = 0,
        val notifications: List<PersistentNotification> = emptyList(),
        /** Currently-playing or paused media players. Limited to 3 on
         *  the dashboard to keep the surface scannable. */
        val media: List<MediaSummary> = emptyList(),
        /** Currently-active (or paused) HA timer.* entities. Empty means
         *  no timers running. */
        val timers: List<TimerSummary> = emptyList(),
        /** Count of light.* entities currently in state='on'. -1 sentinel
         *  for "not loaded yet" so the UI can render '—' rather than zero. */
        val lightsOnCount: Int = -1,
        /** Total real-time power consumption from every sensor.* with
         *  device_class='power', in Watts. -1 sentinel for "not loaded
         *  yet" / "no power sensors". */
        val totalPowerW: Int = -1,
        /** Every battery sensor under the configured threshold, with its
         *  friendly name + percent. Empty list means all batteries healthy. */
        val lowBatteries: List<LowBattery> = emptyList(),
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh(indicate: Boolean = false) {
        // Network fan-out runs on Default but the post-await assembly (JSON walking,
        // Instant parsing, sorting) is non-trivial; keep it off Main so a refresh
        // doesn't jank the dashboard scroll. viewModelScope's default is Main, hence
        // the explicit withContext.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            _ui.value = _ui.value.copy(loading = true, refreshing = indicate, error = null)
            try {
                val weatherJob = async { haRepository.listRawEntitiesByDomain("weather") }
                val personJob = async { haRepository.listRawEntitiesByDomain("person") }
                val calendarJob = async { haRepository.listRawEntitiesByDomain("calendar") }
                val cameraJob = async { haRepository.listRawEntitiesByDomain("camera") }
                val notifJob = async { haRepository.listPersistentNotifications() }
                val sunJob = async { haRepository.listRawEntitiesByDomain("sun") }
                val mediaJob = async { haRepository.listRawEntitiesByDomain("media_player") }
                val timerJob = async { haRepository.listRawEntitiesByDomain("timer") }
                // Lightweight server-side count rather than transporting
                // every light entity's full row. The integer comes back
                // as plain text body from /api/template.
                val lightsJob = async {
                    haRepository.renderTemplate(
                        "{{ states.light | selectattr('state','eq','on') | list | count }}",
                    )
                }
                // Sum power-class sensor states. The rejectattr guards
                // against 'unavailable' / 'unknown' rows which would fail
                // the float() coercion. round() to whole watts because
                // the dashboard tile shows an integer.
                val powerJob = async {
                    haRepository.renderTemplate(
                        "{{ states.sensor " +
                            "| selectattr('attributes.device_class','eq','power') " +
                            "| rejectattr('state','in',['unavailable','unknown']) " +
                            "| map(attribute='state') | map('float',0) | sum | round(0) | int }}",
                    )
                }
                // Low-battery list: every battery-class sensor under the
                // configured threshold. Builds a JSON array of
                // "<entity_id>=<pct>" strings so we can parse it back
                // client-side. Threshold is read from settings each refresh.
                val lowBatteryPct = settings.settings.first().dashboard.lowBatteryThresholdPct
                // ^ first() on the StateFlow gives us the current snapshot
                //   without waiting for an emit. Safe because settings is a
                //   hot StateFlow seeded on the repo's init.
                val batteryJob = async {
                    // Emit a JSON array of {id, name, pct} objects so the
                    // dashboard can render the friendly name (the raw entity_id
                    // alone read as plumbing, e.g. "sensor.kitchen_motion_battery").
                    // name falls back to the entity_id inside the template when
                    // the sensor has no friendly_name set.
                    haRepository.renderTemplate(
                        "{%- set out = namespace(items=[]) -%}" +
                            "{%- for s in states.sensor " +
                            "| selectattr('attributes.device_class','eq','battery') " +
                            "| rejectattr('state','in',['unavailable','unknown']) -%}" +
                            "{%- set pct = s.state | float(101) -%}" +
                            "{%- if pct < $lowBatteryPct -%}" +
                            "{%- set out.items = out.items + [{" +
                            "'id': s.entity_id, " +
                            "'name': s.name, " +
                            "'pct': (pct | int)}] -%}" +
                            "{%- endif -%}" +
                            "{%- endfor -%}" +
                            "{{ out.items | tojson }}",
                    )
                }
                awaitAll(weatherJob, personJob, calendarJob, cameraJob, notifJob, sunJob, mediaJob, timerJob, lightsJob, powerJob, batteryJob)
                val weather = weatherJob.await().getOrNull()
                    // Prefer the first weather entity that is actually reporting a
                    // condition over a leading 'unavailable' / 'unknown' row, so a
                    // flaky secondary integration can't blank the tile.
                    ?.let { rows ->
                        rows.firstOrNull { it.state !in setOf("unavailable", "unknown") }
                            ?: rows.firstOrNull()
                    }
                    ?.let { row ->
                        WeatherSummary(
                            name = row.friendlyName,
                            condition = row.state,
                            temperature = (row.attributes["temperature"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                            temperatureUnit = (row.attributes["temperature_unit"] as? JsonPrimitive)?.content,
                            apparentTemperature = (row.attributes["apparent_temperature"] as? JsonPrimitive)
                                ?.content?.toDoubleOrNull(),
                            humidity = (row.attributes["humidity"] as? JsonPrimitive)
                                ?.content?.toDoubleOrNull()?.let { Math.round(it).toInt() },
                        )
                    }
                val persons = personJob.await().getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { rows ->
                        val homeCount = rows.count { it.state == "home" }
                        // Anyone reporting a non-home zone (HA shows the zone name,
                        // e.g. "Work") is away from home, not just literal not_home /
                        // away. Exclude unavailable / unknown so a dropped tracker
                        // doesn't inflate the away tally.
                        val awayCount = rows.count {
                            it.state != "home" && it.state != "unavailable" && it.state != "unknown"
                        }
                        PersonsSummary(
                            homeCount = homeCount,
                            awayCount = awayCount,
                            rows = rows.sortedBy { it.friendlyName.lowercase() }
                                .take(6)
                                .map { it.friendlyName to it.state },
                            total = rows.size,
                        )
                    }
                // Next event: pick the earliest start_time across all
                // calendar entities, or the first happening-now (state=on)
                // if one exists.
                val nextEvent = calendarJob.await().getOrNull()?.let { rows ->
                    val parsed = rows.mapNotNull { row ->
                        val title = (row.attributes["message"] as? JsonPrimitive)?.content
                            ?: return@mapNotNull null
                        val startRaw = (row.attributes["start_time"] as? JsonPrimitive)?.content
                        val start = parseCalendarInstant(startRaw)
                        // All-day events arrive as date-only strings (no T
                        // or space separator with a time component). Length
                        // 10 = 'YYYY-MM-DD'; longer = has time.
                        val allDay = startRaw != null && startRaw.length <= 10
                        CalendarSummary(
                            calendarName = row.friendlyName,
                            eventTitle = title,
                            eventStart = start,
                            happeningNow = row.state == "on",
                            allDay = allDay,
                        )
                    }
                    // A happening-now event always wins. Otherwise pick the soonest
                    // event whose start is still in the future, so a stale past-start
                    // row (HA occasionally lags clearing state=off) can't masquerade
                    // as "NEXT". Falls back to the soonest of whatever's left when no
                    // future event exists, rather than showing nothing.
                    val now = Instant.now()
                    parsed.firstOrNull { it.happeningNow }
                        ?: parsed.filter { (it.eventStart ?: Instant.MIN) >= now }
                            .minByOrNull { it.eventStart ?: Instant.MAX }
                        ?: parsed.minByOrNull { it.eventStart ?: Instant.MAX }
                }
                val cameras = cameraJob.await().getOrNull().orEmpty()
                val notifs = notifJob.await().getOrNull().orEmpty()
                val media = mediaJob.await().getOrNull()
                    ?.filter { it.state in setOf("playing", "paused", "buffering") }
                    ?.map { row ->
                        // supported_features is an int bitmask; tolerate a stray
                        // "59.0"-style float string by falling back through Double.
                        val features = (row.attributes["supported_features"] as? JsonPrimitive)
                            ?.content?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() } ?: 0
                        MediaSummary(
                            entityId = row.entityId,
                            name = row.friendlyName,
                            state = row.state,
                            title = (row.attributes["media_title"] as? JsonPrimitive)?.content,
                            artist = (row.attributes["media_artist"] as? JsonPrimitive)?.content,
                            // supported_features may be absent (0) on minimal players; treat 0
                            // as "advertises nothing" and hide all transport rather than offer
                            // controls that will no-op. The bits mirror HA's enum.
                            canPrev = (features and MediaFeature.PREVIOUS_TRACK) != 0,
                            canNext = (features and MediaFeature.NEXT_TRACK) != 0,
                            canPlayPause = (features and (MediaFeature.PLAY or MediaFeature.PAUSE)) != 0,
                        )
                    }
                    ?.sortedByDescending { it.state == "playing" }
                    ?.take(3)
                    .orEmpty()
                val lightsOn = lightsJob.await().getOrNull()?.trim()?.toIntOrNull() ?: -1
                val totalPower = powerJob.await().getOrNull()?.trim()?.toIntOrNull() ?: -1
                val lowBatteries = batteryJob.await().getOrNull()?.let { raw ->
                    runCatching {
                        // Reuse the HA-tolerant Json instance instead of the default singleton
                        // so the parse stays consistent with the rest of the codebase
                        // (ignoreUnknownKeys, explicitNulls=false).
                        val arr = com.github.itskenny0.r1ha.core.ha.HaJson.parseToJsonElement(raw)
                            as? kotlinx.serialization.json.JsonArray
                        arr?.mapNotNull { el ->
                            val obj = el as? kotlinx.serialization.json.JsonObject
                                ?: return@mapNotNull null
                            val id = (obj["id"] as? JsonPrimitive)?.content
                                ?: return@mapNotNull null
                            val pct = (obj["pct"] as? JsonPrimitive)?.content?.toIntOrNull()
                                ?: return@mapNotNull null
                            val name = (obj["name"] as? JsonPrimitive)?.content
                                ?.takeIf { it.isNotBlank() } ?: id
                            LowBattery(entityId = id, name = name, pct = pct)
                        }
                    }.getOrNull()
                }.orEmpty()
                val timers = timerJob.await().getOrNull()
                    ?.filter { it.state == "active" || it.state == "paused" }
                    ?.map { row ->
                        TimerSummary(
                            entityId = row.entityId,
                            name = row.friendlyName,
                            state = row.state,
                            finishesAt = (row.attributes["finishes_at"] as? JsonPrimitive)?.content
                                ?.let { parseHaInstant(it) },
                            remaining = (row.attributes["remaining"] as? JsonPrimitive)?.content,
                        )
                    }
                    ?.sortedBy { it.finishesAt ?: Instant.MAX }
                    .orEmpty()
                val sun = sunJob.await().getOrNull()?.firstOrNull()?.let { row ->
                    SunSummary(
                        state = row.state,
                        elevation = (row.attributes["elevation"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                        nextRising = (row.attributes["next_rising"] as? JsonPrimitive)?.content
                            ?.let { parseHaInstant(it) },
                        nextSetting = (row.attributes["next_setting"] as? JsonPrimitive)?.content
                            ?.let { parseHaInstant(it) },
                    )
                }
                R1Log.i(
                    "Dashboard",
                    "weather=${weather != null} persons=${persons?.rows?.size ?: 0} " +
                        "nextEvent=${nextEvent != null} cameras=${cameras.size} notifs=${notifs.size}",
                )
                _ui.value = _ui.value.copy(
                    loading = false,
                    refreshing = false,
                    weather = weather,
                    persons = persons,
                    nextEvent = nextEvent,
                    sun = sun,
                    cameraCount = cameras.size,
                    notifications = notifs,
                    media = media,
                    timers = timers,
                    lightsOnCount = lightsOn,
                    totalPowerW = totalPower,
                    lowBatteries = lowBatteries,
                    error = null,
                )
            } catch (t: Throwable) {
                R1Log.w("Dashboard", "refresh failed: ${t.message}")
                _ui.value = _ui.value.copy(loading = false, refreshing = false, error = t.message)
            }
        }
    }

    /** Master 'all lights off': fired from the LIGHTS ON tile's
     *  long-press affordance. Reuses the all-domain HA trick (target
     *  any light entity with entity_id='all' in data) so it scales to
     *  installs of any size in a single call. */
    fun timerService(entityId: String, service: String) {
        viewModelScope.launch {
            val target = runCatching {
                com.github.itskenny0.r1ha.core.ha.EntityId(entityId)
            }.getOrNull() ?: return@launch
            haRepository.call(
                com.github.itskenny0.r1ha.core.ha.ServiceCall(
                    target = target,
                    service = service,
                    data = kotlinx.serialization.json.JsonObject(emptyMap()),
                ),
            )
            kotlinx.coroutines.delay(600L)
            refresh()
        }
    }

    /** Dismiss a single persistent notification from the dashboard's
     *  inline alerts preview. Optimistically drops the row from the
     *  in-memory list so the dashboard updates without waiting for
     *  the next refresh tick. */
    fun dismissNotification(notificationId: String) {
        viewModelScope.launch {
            haRepository.dismissPersistentNotification(notificationId).fold(
                onSuccess = {
                    _ui.value = _ui.value.copy(
                        notifications = _ui.value.notifications.filterNot {
                            it.notificationId == notificationId
                        },
                    )
                },
                onFailure = { t ->
                    R1Log.w("Dashboard", "dismiss $notificationId failed: ${t.message}")
                    com.github.itskenny0.r1ha.core.util.Toaster.error(
                        "Dismiss failed: ${t.message ?: "unknown"}",
                    )
                },
            )
        }
    }

    fun allLightsOff() {
        viewModelScope.launch {
            val anyLight = haRepository.listAllEntities().getOrNull()
                ?.firstOrNull { it.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.LIGHT }
                ?.id ?: return@launch
            haRepository.call(
                com.github.itskenny0.r1ha.core.ha.ServiceCall(
                    target = anyLight,
                    service = "turn_off",
                    data = kotlinx.serialization.json.buildJsonObject {
                        put("entity_id", kotlinx.serialization.json.JsonPrimitive("all"))
                    },
                ),
            )
            com.github.itskenny0.r1ha.core.util.Toaster.show("All lights off")
            kotlinx.coroutines.delay(800L)
            refresh()
        }
    }

    /** Transport dispatch for the on-dashboard media card. Uses the
     *  existing ServiceCall.mediaTransport helper and haRepository.call
     *  WS path so the dispatch is debounced and coalesced like every
     *  other service call in the app. Triggers an immediate dashboard
     *  refresh after a short settle delay so the play/pause state
     *  reflects the new reality without waiting for the next 60s
     *  auto-refresh tick. Makes the buttons feel responsive. */
    /** Fire a HA timer service against the given entity. Used by the
     *  TimerCard's pause/resume/cancel controls on the dashboard.
     *  Refreshes the dashboard 600 ms later so the visible state
     *  flips immediately rather than waiting for the auto-refresh
     *  tick. */
    fun mediaTransport(
        entityId: String,
        action: com.github.itskenny0.r1ha.core.ha.MediaTransport,
    ) {
        viewModelScope.launch {
            val target = runCatching {
                com.github.itskenny0.r1ha.core.ha.EntityId(entityId)
            }.getOrNull() ?: return@launch
            haRepository.call(
                com.github.itskenny0.r1ha.core.ha.ServiceCall.mediaTransport(target, action),
            )
            // 800 ms settle delay: HA needs a moment for the media
            // entity to report its new state after a play/pause. Faster
            // and we'd refresh on the pre-action state and have to
            // refresh again on the next tick.
            kotlinx.coroutines.delay(800L)
            refresh()
        }
    }

    companion object {
        fun factory(
            haRepository: HaRepository,
            settings: com.github.itskenny0.r1ha.core.prefs.SettingsRepository,
        ) = viewModelFactory {
            initializer { DashboardViewModel(haRepository, settings) }
        }
    }
}
