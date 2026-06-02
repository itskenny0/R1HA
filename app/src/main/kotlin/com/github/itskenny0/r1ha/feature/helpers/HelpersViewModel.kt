package com.github.itskenny0.r1ha.feature.helpers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import com.github.itskenny0.r1ha.core.ha.parseHaInstant
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant

/**
 * Drives the Helpers surface — Home Assistant's catch-all for
 * user-defined state holders: `input_boolean`, `input_number`,
 * `input_select`, `input_text`, `input_datetime`, `input_button`,
 * `counter`, `timer`.
 *
 * Most of these are scalar / on-off and would naturally fit the card
 * stack, but the discoverability story is different — users typically
 * want to *find* and *manipulate* a helper once in a while (kitchen
 * timer, away mode toggle, holiday-mode select) rather than have it
 * pinned to a card. This surface gives them a single list view to
 * browse + control without committing to a favourite slot.
 */
class HelpersViewModel(
    private val haRepository: HaRepository,
    private val settings: com.github.itskenny0.r1ha.core.prefs.SettingsRepository,
) : ViewModel() {

    /** One bucket per supported HA helper domain. Filter chips at the
     *  top of the screen flip between these; ALL is the union. */
    enum class Bucket(val label: String, val domains: Set<String>) {
        ALL("ALL", emptySet()), // sentinel — UI shows the union
        TOGGLES("TOGGLES", setOf("input_boolean")),
        NUMBERS("NUMBERS", setOf("input_number", "counter")),
        SELECTS("SELECTS", setOf("input_select")),
        TEXT("TEXT", setOf("input_text", "input_datetime")),
        BUTTONS("BUTTONS", setOf("input_button")),
        TIMERS("TIMERS", setOf("timer")),
    }

    /** Domain-specific render kind. The screen branches on this to
     *  decide which control widget to draw on each row. */
    enum class Kind { BOOLEAN, NUMBER, SELECT, TEXT, DATETIME, BUTTON, COUNTER, TIMER, UNKNOWN }

    @androidx.compose.runtime.Stable
    data class Entry(
        val id: EntityId,
        val name: String,
        val state: String,
        val kind: Kind,
        /** Numeric value for input_number / counter — already parsed
         *  from the state string. Null when not numeric or out of range. */
        val numericValue: Double? = null,
        val min: Double? = null,
        val max: Double? = null,
        val step: Double? = null,
        val unit: String? = null,
        /** Options for input_select. Empty for everything else. */
        val options: List<String> = emptyList(),
        /** Timer's finishes_at — null for paused / idle timers. */
        val finishesAt: Instant? = null,
        /** input_button's state is the ISO timestamp of its last press (or
         *  `unknown` if never pressed). Parsed so the row can show a relative
         *  "pressed N ago" the way HA's button row surfaces last-changed. */
        val pressedAt: Instant? = null,
        /** Timer's `remaining` attribute (HH:MM:SS). Useful when
         *  paused — `finishes_at` is stale at that point. */
        val remaining: String? = null,
        /** input_text length window (HA defaults 0..100). Used to clamp /
         *  validate the inline editor before dispatching set_value. */
        val textMin: Int? = null,
        val textMax: Int? = null,
        /** input_datetime shape — which of date / time the helper carries.
         *  Drives which editor fields the row shows and which keys land in
         *  the set_datetime payload. */
        val hasDate: Boolean = false,
        val hasTime: Boolean = false,
        /** input_text mode: "password" masks the displayed value the way HA's
         *  password-mode text field does. Null / "text" shows it plainly. */
        val mode: String? = null,
        /** True when the helper isn't reporting an actionable value
         *  (`unavailable` / `unknown` / blank). The row renders read-only in
         *  that case, mirroring HA which disables the control. */
        val inactive: Boolean = false,
    )

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val all: List<Entry> = emptyList(),
        val bucket: Bucket = Bucket.ALL,
        val query: String = "",
        val error: String? = null,
    ) {
        val entries: List<Entry>
            get() {
                val byBucket = if (bucket == Bucket.ALL) all
                else all.filter { entryDomain(it.id) in bucket.domains }
                if (query.isBlank()) return byBucket
                return byBucket.filter {
                    HelpersLogic.matchesQuery(it.name, it.id.value, query)
                }
            }

        /** Per-bucket counts driven from `all` — chip labels show the
         *  count so the user can see which buckets are populated. */
        val counts: Map<Bucket, Int>
            get() = buildMap {
                put(Bucket.ALL, all.size)
                Bucket.entries.filter { it != Bucket.ALL }.forEach { b ->
                    put(b, all.count { entryDomain(it.id) in b.domains })
                }
            }
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun setBucket(b: Bucket) {
        if (_ui.value.bucket == b) return
        _ui.value = _ui.value.copy(bucket = b)
    }

    fun setQuery(q: String) {
        if (_ui.value.query == q) return
        _ui.value = _ui.value.copy(query = q)
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            // Fetch each helper domain in parallel. HA exposes them as
            // separate top-level domains so a single /api/states pull
            // would also work, but the per-domain endpoint lets us
            // narrow the response payload and is the same shape the
            // rest of the app uses.
            val results = HELPER_DOMAINS.map { dom ->
                dom to haRepository.listRawEntitiesByDomain(dom)
            }
            val anyFailure = results.firstOrNull { it.second.isFailure }
            if (anyFailure != null) {
                val t = anyFailure.second.exceptionOrNull()
                R1Log.w("Helpers", "load failed: ${t?.message}")
                Toaster.error("Helpers load failed: ${t?.message ?: "unknown"}")
                _ui.value = _ui.value.copy(loading = false, error = t?.message)
                return@launch
            }
            val rows = results.flatMap { (_, r) -> r.getOrNull().orEmpty() }
            val entries = rows.map { row ->
                val attrs = row.attributes
                val kind = kindOf(row.entityId.substringBefore('.'))
                Entry(
                    id = EntityId(row.entityId),
                    name = row.friendlyName,
                    state = row.state,
                    kind = kind,
                    numericValue = row.state.toDoubleOrNull(),
                    min = (attrs["min"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                    max = (attrs["max"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                    step = (attrs["step"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                    unit = (attrs["unit_of_measurement"] as? JsonPrimitive)?.content,
                    options = (attrs["options"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
                    finishesAt = (attrs["finishes_at"] as? JsonPrimitive)?.content
                        ?.let { parseHaInstant(it) },
                    pressedAt = if (kind == Kind.BUTTON) parseHaInstant(row.state) else null,
                    remaining = (attrs["remaining"] as? JsonPrimitive)?.content,
                    textMin = (attrs["min"] as? JsonPrimitive)?.content?.toIntOrNull(),
                    textMax = (attrs["max"] as? JsonPrimitive)?.content?.toIntOrNull(),
                    hasDate = (attrs["has_date"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
                    hasTime = (attrs["has_time"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
                    mode = (attrs["mode"] as? JsonPrimitive)?.content,
                    // input_button has no meaningful "inactive" state (its
                    // state is the last-press timestamp), and a freshly-created
                    // input_datetime / input_text legitimately reads blank, so
                    // only gate the value-bearing scalar/select/timer kinds on
                    // the unavailable/unknown sentinels.
                    inactive = kind != Kind.BUTTON &&
                        kind != Kind.TEXT &&
                        kind != Kind.DATETIME &&
                        HelpersLogic.isInactiveState(row.state),
                )
            }.sortedWith(
                // Group by kind first (so the eye finds e.g. all the
                // timers together), then by name within each kind.
                compareBy<Entry> { it.kind.ordinal }.thenBy { it.name.lowercase() },
            )
            R1Log.i("Helpers", "loaded ${entries.size} across ${HELPER_DOMAINS.size} domains")
            _ui.value = _ui.value.copy(loading = false, all = entries, error = null)
        }
    }

    /** Toggle an `input_boolean` between on/off. HA's `toggle` service
     *  flips whichever state the entity is currently in. */
    fun toggleBoolean(entry: Entry) {
        viewModelScope.launch {
            haRepository.call(
                ServiceCall(target = entry.id, service = "toggle", data = JsonObject(emptyMap())),
            ).onFailure { Toaster.error("Toggle failed: ${it.message ?: "unknown"}") }
            // Settle delay so the freshly-toggled state lands before
            // refresh re-queries — matches the pattern used by the
            // dashboard timer transport and the automations panel.
            kotlinx.coroutines.delay(400L)
            refresh()
        }
    }

    /** Set an `input_number` to a specific value via `set_value`. Counter
     *  rows never reach this path: the screen drives them through
     *  [counterIncrement] / [counterDecrement] / [counterReset] so HA's
     *  configured step is respected. `counter.set_value` is also valid, so
     *  routing a counter here would still work, but the wheel/stepper UI
     *  only ever passes input_number entries. */
    fun setNumber(entry: Entry, newValue: Double) {
        viewModelScope.launch {
            haRepository.call(
                ServiceCall(
                    target = entry.id,
                    service = "set_value",
                    data = buildJsonObject {
                        put("value", JsonPrimitive(newValue))
                    },
                ),
            ).onFailure { Toaster.error("Set failed: ${it.message ?: "unknown"}") }
            kotlinx.coroutines.delay(400L)
            refresh()
        }
    }

    /** Counter-specific increment / decrement / reset. */
    fun counterIncrement(entry: Entry) = counterService(entry, "increment")
    fun counterDecrement(entry: Entry) = counterService(entry, "decrement")
    fun counterReset(entry: Entry) = counterService(entry, "reset")

    private fun counterService(entry: Entry, service: String) {
        viewModelScope.launch {
            haRepository.call(
                ServiceCall(target = entry.id, service = service, data = JsonObject(emptyMap())),
            ).onFailure { Toaster.error("$service failed: ${it.message ?: "unknown"}") }
            kotlinx.coroutines.delay(300L)
            refresh()
        }
    }

    /** Select an option on an `input_select`. */
    fun selectOption(entry: Entry, option: String) {
        viewModelScope.launch {
            haRepository.call(
                ServiceCall(
                    target = entry.id,
                    service = "select_option",
                    data = buildJsonObject {
                        put("option", JsonPrimitive(option))
                    },
                ),
            ).onFailure { Toaster.error("Select failed: ${it.message ?: "unknown"}") }
            kotlinx.coroutines.delay(400L)
            refresh()
        }
    }

    /** Set an `input_text` value via `set_value`. The text is clamped to the
     *  helper's max length client-side; HA still rejects values shorter than
     *  the configured min, in which case the failure path surfaces a toast. */
    fun setText(entry: Entry, newValue: String) {
        viewModelScope.launch {
            val clamped = HelpersLogic.clampText(newValue, entry.textMin, entry.textMax)
            haRepository.call(
                ServiceCall(
                    target = entry.id,
                    service = "set_value",
                    data = buildJsonObject {
                        put("value", JsonPrimitive(clamped))
                    },
                ),
            ).onFailure { Toaster.error("Set failed: ${it.message ?: "unknown"}") }
            kotlinx.coroutines.delay(400L)
            refresh()
        }
    }

    /** Set an `input_datetime` via `set_datetime`. Only the fields the helper
     *  carries are sent: a date-only helper gets `date`, a time-only helper
     *  gets `time`, a combined helper gets both. Blank / null fields are
     *  omitted so HA keeps the existing component. */
    fun setDateTime(entry: Entry, date: String?, time: String?) {
        viewModelScope.launch {
            val sendDate = entry.hasDate && !date.isNullOrBlank()
            val sendTime = entry.hasTime && !time.isNullOrBlank()
            if (!sendDate && !sendTime) {
                Toaster.error("Nothing to set")
                return@launch
            }
            haRepository.call(
                ServiceCall(
                    target = entry.id,
                    service = "set_datetime",
                    data = buildJsonObject {
                        if (sendDate) put("date", JsonPrimitive(date))
                        if (sendTime) put("time", JsonPrimitive(HelpersLogic.normaliseTime(time!!)))
                    },
                ),
            ).onFailure { Toaster.error("Set failed: ${it.message ?: "unknown"}") }
            kotlinx.coroutines.delay(400L)
            refresh()
        }
    }

    /** Press an `input_button` — fires the press service, no payload. */
    fun pressButton(entry: Entry) {
        viewModelScope.launch {
            haRepository.call(
                ServiceCall(target = entry.id, service = "press", data = JsonObject(emptyMap())),
            ).fold(
                onSuccess = { Toaster.show("Pressed '${entry.name}'") },
                onFailure = { Toaster.error("Press failed: ${it.message ?: "unknown"}") },
            )
            kotlinx.coroutines.delay(300L)
            refresh()
        }
    }

    /** Start / pause / cancel a timer. */
    fun timerService(entry: Entry, service: String) {
        viewModelScope.launch {
            haRepository.call(
                ServiceCall(target = entry.id, service = service, data = JsonObject(emptyMap())),
            ).onFailure { Toaster.error("$service failed: ${it.message ?: "unknown"}") }
            kotlinx.coroutines.delay(500L)
            refresh()
        }
    }

    companion object {
        /** Every HA helper domain we list on this surface. Order is
         *  preserved so the per-kind sort lands in a sensible UI order
         *  (toggles first, timers last). */
        private val HELPER_DOMAINS = listOf(
            "input_boolean",
            "input_number",
            "counter",
            "input_select",
            "input_text",
            "input_datetime",
            "input_button",
            "timer",
        )

        /** Map an entity_id's domain prefix to our [Kind] enum. Delegates to
         *  [HelpersLogic.kindForDomain] so the mapping is unit-tested in one place. */
        private fun kindOf(domain: String): Kind = HelpersLogic.kindForDomain(domain)

        private fun entryDomain(id: EntityId): String = id.value.substringBefore('.')

        fun factory(
            haRepository: HaRepository,
            settings: com.github.itskenny0.r1ha.core.prefs.SettingsRepository,
        ) = viewModelFactory {
            initializer { HelpersViewModel(haRepository, settings) }
        }
    }

    /** Pin a helper to the active page's favourites. Most helpers
     *  map to a sensible card archetype (input_boolean → SwitchCard,
     *  input_number → SliderCard, timer → TimerCard) so adding them
     *  to the deck just works. */
    fun addToFavorites(entry: Entry) {
        viewModelScope.launch {
            settings.updateActivePage { page ->
                if (entry.id.value in page.favorites) page
                else page.copy(favorites = page.favorites + entry.id.value)
            }
            R1Log.i("Helpers", "favourited ${entry.id.value}")
            Toaster.show("Added '${entry.name}' to favourites")
        }
    }
}
