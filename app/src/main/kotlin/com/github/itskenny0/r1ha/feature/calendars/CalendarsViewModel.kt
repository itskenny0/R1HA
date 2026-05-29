package com.github.itskenny0.r1ha.feature.calendars

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Drives the Calendars surface. For each `calendar.*` entity HA
 * exposes, surfaces the entity state ("on" = an event is happening
 * right now, "off" = next event is in the future) plus the upcoming
 * event's title, location, start time and end time from the
 * attributes object.
 *
 * Full event-list browsing (HA's `/api/calendars/<id>?start=...`
 * endpoint) is a richer follow-up; this is the
 * "what's-on-the-agenda-next" surface that maps onto an at-a-glance
 * R1 view.
 */
class CalendarsViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class Calendar(
        val entityId: String,
        val name: String,
        /** "on" if there's an event happening right now, "off" otherwise. */
        val state: String,
        val eventMessage: String?,
        val eventLocation: String?,
        val eventStart: Instant?,
        val eventEnd: Instant?,
        val eventDescription: String?,
        /** All-day event detected via start_time being a bare YYYY-MM-DD
         *  (length ≤ 10, no time component). The UI tags these with an
         *  ALL-DAY pill instead of a "in 2 h" countdown that would be
         *  misleading for events without a specific start time. */
        val allDay: Boolean,
    )

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val calendars: List<Calendar> = emptyList(),
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            haRepository.listRawEntitiesByDomain("calendar").fold(
                onSuccess = { rows ->
                    val list = rows.map { row ->
                        val attrs = row.attributes
                        val startRaw = (attrs["start_time"] as? JsonPrimitive)?.content
                        Calendar(
                            entityId = row.entityId,
                            name = row.friendlyName,
                            state = row.state,
                            eventMessage = (attrs["message"] as? JsonPrimitive)?.content,
                            eventLocation = (attrs["location"] as? JsonPrimitive)?.content,
                            eventStart = startRaw?.let { parseLooseTime(it) },
                            eventEnd = (attrs["end_time"] as? JsonPrimitive)?.content
                                ?.let { parseLooseTime(it) },
                            eventDescription = (attrs["description"] as? JsonPrimitive)?.content,
                            allDay = startRaw != null && startRaw.length <= 10,
                        )
                    }
                    // Currently-happening calendars first (state=on), then
                    // by next start time, then alphabetical.
                    val sorted = list.sortedWith(
                        compareByDescending<Calendar> { it.state == "on" }
                            .thenBy { it.eventStart ?: Instant.MAX }
                            .thenBy { it.name.lowercase() },
                    )
                    R1Log.i("Calendars", "loaded ${sorted.size}")
                    _ui.value = _ui.value.copy(loading = false, calendars = sorted, error = null)
                },
                onFailure = { t ->
                    R1Log.w("Calendars", "list failed: ${t.message}")
                    Toaster.error("Calendars load failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(loading = false, error = t.message)
                },
            )
        }
    }

    private fun parseLooseTime(raw: String): Instant? = parseCalendarInstant(raw)

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { CalendarsViewModel(haRepository) }
        }
    }
}

/**
 * Resolves a Home Assistant calendar `start_time` / `end_time` string to an
 * [Instant]. HA emits a mix of forms depending on the integration:
 *  - ISO-8601 with an offset or 'Z' for timed events (e.g. 2026-05-15T09:00:00+02:00),
 *  - an offset-less local datetime (e.g. "2026-05-15 08:30:00" or "2026-05-15T08:30:00"),
 *  - a bare date for all-day events (e.g. "2026-05-15").
 *
 * Timezone policy (explicit): values resolve in the device-local zone.
 *  - Offset-bearing datetimes are honoured exactly.
 *  - Offset-less datetimes are treated as local wall-clock time.
 *  - A bare date resolves to local midnight of that day, so a same-day all-day
 *    event sorts ahead of later timed events instead of falling back to
 *    [Instant.MAX] (which previously hid all-day events from the Calendars
 *    sort and the Dashboard "next event" tile).
 *
 * Returns null on a blank or unparseable value.
 */
internal fun parseCalendarInstant(
    raw: String?,
    zone: ZoneId = ZoneId.systemDefault(),
): Instant? {
    val value = raw?.trim().orEmpty()
    if (value.isBlank()) return null

    // Offset-aware datetime (carries an explicit offset or 'Z').
    runCatching { return OffsetDateTime.parse(value).toInstant() }

    // Offset-less local datetime; tolerate a space separator in place of 'T'.
    val normalised = if (value.contains('T')) value else value.replace(' ', 'T')
    runCatching { return LocalDateTime.parse(normalised).atZone(zone).toInstant() }

    // Bare date (all-day event): local midnight of that day.
    runCatching { return LocalDate.parse(value).atStartOfDay(zone).toInstant() }

    return null
}
