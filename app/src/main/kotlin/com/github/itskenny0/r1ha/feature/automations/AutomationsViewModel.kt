package com.github.itskenny0.r1ha.feature.automations

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant

/**
 * Drives the Automations surface. Lists every `automation.*` entity HA
 * exposes with its current enabled state, mode, last-triggered timestamp,
 * and any running instance count. Mirrors the parity HA's own frontend
 * Automations panel offers: list + RUN + enable/disable + reload.
 *
 * Three service dispatches per row:
 *  - `automation.trigger`: manually fire the automation's actions,
 *    bypassing its triggers; same as the ▶ button in HA's UI.
 *  - `automation.turn_on` / `automation.turn_off`: toggle whether the
 *    triggers fire automatically (enabled/disabled in HA's terminology).
 *  - `automation.reload`: re-read every automation from automations.yaml
 *    (and the UI editor's storage), useful after editing in the HA web UI.
 */
class AutomationsViewModel(
    private val haRepository: HaRepository,
    private val settings: com.github.itskenny0.r1ha.core.prefs.SettingsRepository,
) : ViewModel() {

    /** Per-mode UI badge. The four modes HA exposes are mutually
     *  exclusive and surface different runtime behaviours; the chip on
     *  each row makes the active mode obvious at a glance. */
    enum class Mode(val label: String) {
        SINGLE("SINGLE"),
        PARALLEL("PARALLEL"),
        QUEUED("QUEUED"),
        RESTART("RESTART"),
        UNKNOWN("—"),
    }

    @androidx.compose.runtime.Stable
    data class Entry(
        val id: EntityId,
        val name: String,
        /** "on" = enabled (triggers fire automatically); "off" =
         *  disabled. Independent of `current` running instances. */
        val enabled: Boolean,
        /** False when HA reports the automation as `unavailable` /
         *  `unknown` (integration not loaded, config error). HA's own
         *  frontend greys these out and tints the icon red; we surface
         *  an UNAVAIL badge and suppress the toggle / RUN gestures since
         *  both would silently no-op against an unavailable entity.
         *  Defaults to available so existing call sites / tests that
         *  don't model availability keep the prior behaviour. */
        val available: Boolean = true,
        val mode: Mode,
        /** Number of currently-running instances (relevant for
         *  parallel / queued modes). 0 most of the time. */
        val currentRunning: Int,
        /** When the automation last fired. Drives the relative-time
         *  label on the right of each row; null means "never since
         *  HA started" (typically a freshly created automation). */
        val lastTriggered: Instant?,
    )

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val all: List<Entry> = emptyList(),
        val query: String = "",
        /** True while the bulk RELOAD action is in flight. The chip
         *  disables itself to prevent double-tap re-fires. */
        val reloading: Boolean = false,
        /** Automations whose manual `automation.trigger` is currently in
         *  flight. Drives the per-row RUN spinner / "RUN…" affordance so the
         *  indicator tracks the real dispatch (set before the call, cleared on
         *  success or failure) rather than a fixed screen-local timer that
         *  would lie on a slow or failed call. Mirrors how Scenes tracks its
         *  `firing` set. */
        val firing: Set<EntityId> = emptySet(),
        val error: String? = null,
    ) {
        /** Substring filter against name + entity_id, case-insensitive.
         *  Lists are typically <50 rows so an in-place filter is
         *  cheap; no need for an indexed search structure. */
        val entries: List<Entry>
            get() {
                if (query.isBlank()) return all
                val q = query.trim().lowercase()
                return all.filter {
                    it.name.lowercase().contains(q) || it.id.value.lowercase().contains(q)
                }
            }
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun setQuery(q: String) {
        if (_ui.value.query == q) return
        _ui.value = _ui.value.copy(query = q)
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            haRepository.listRawEntitiesByDomain("automation").fold(
                onSuccess = { rows ->
                    val entries = rows.map { row ->
                        Entry(
                            id = EntityId(row.entityId),
                            name = row.friendlyName,
                            enabled = enabledFrom(row.state),
                            available = availableFrom(row.state),
                            mode = modeOf((row.attributes["mode"] as? JsonPrimitive)?.content),
                            currentRunning = (row.attributes["current"] as? JsonPrimitive)?.content
                                ?.toIntOrNull() ?: 0,
                            lastTriggered = lastTriggeredOf(
                                (row.attributes["last_triggered"] as? JsonPrimitive)?.content,
                            ),
                        )
                    }.sortedBy { it.name.lowercase() }
                    R1Log.i("Automations", "loaded ${entries.size}")
                    _ui.value = _ui.value.copy(
                        loading = false,
                        all = entries,
                        error = null,
                    )
                },
                onFailure = { t ->
                    R1Log.w("Automations", "load failed: ${t.message}")
                    Toaster.error("Automations load failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(loading = false, error = t.message)
                },
            )
        }
    }

    /**
     * Manually fire the automation's actions, bypassing its triggers.
     * Same as the ▶ button in HA's frontend Automations panel. We post
     * `skip_condition: true` so the conditions block (often: only at
     * night, only when away, etc.) doesn't block the manual trigger:
     * which is what the user typically wants when they're testing.
     */
    fun trigger(entry: Entry) {
        // An unavailable automation can't be fired; tell the user rather than
        // dispatching a call HA will reject silently.
        if (!entry.available) {
            Toaster.error("'${entry.name}' is unavailable")
            return
        }
        // Ignore a re-tap while this automation's trigger is still in flight so
        // a slow HA round-trip can't double-fire it.
        if (entry.id in _ui.value.firing) return
        // Mark in-flight up front so the row's RUN affordance reflects the real
        // dispatch. Cleared on both success and failure below, so a slow or
        // failed call can't leave the indicator stuck (the old screen-local
        // 1.5s timer lied here).
        _ui.value = _ui.value.copy(firing = _ui.value.firing + entry.id)
        viewModelScope.launch {
            val call = ServiceCall(
                target = entry.id,
                service = "trigger",
                data = buildJsonObject {
                    put("skip_condition", JsonPrimitive(true))
                },
            )
            haRepository.call(call).fold(
                onSuccess = {
                    R1Log.i("Automations", "triggered ${entry.id.value}")
                    Toaster.show("Triggered '${entry.name}'")
                },
                onFailure = { t ->
                    R1Log.w("Automations", "trigger ${entry.id.value} failed: ${t.message}")
                    Toaster.error("Trigger failed: ${t.message ?: "unknown"}")
                },
            )
            _ui.value = _ui.value.copy(firing = _ui.value.firing - entry.id)
            // Pull a fresh state shortly after firing so the
            // last_triggered + current counts update immediately
            // instead of waiting for the next manual refresh.
            kotlinx.coroutines.delay(600L)
            refresh()
        }
    }

    /**
     * Surface why an unavailable automation is inert. Tapping an unavailable
     * row's body can't toggle or run it (both would no-op against HA), so the
     * screen routes the tap here to toast the reason rather than silently
     * opening History as if the tap did nothing.
     */
    fun explainUnavailable(entry: Entry) {
        Toaster.show("'${entry.name}' is unavailable")
    }

    /** Enable / disable the automation (whether its triggers fire
     *  automatically). HA uses `automation.turn_on` / `turn_off` which
     *  feels backwards in a UI sense, but it matches the underlying
     *  service contract: the state attribute reflects this exactly. */
    fun setEnabled(entry: Entry, enabled: Boolean) {
        // An unavailable automation has no meaningful on/off to flip; skip the
        // optimistic update and the doomed dispatch, and surface why.
        if (!entry.available) {
            Toaster.error("'${entry.name}' is unavailable")
            return
        }
        // Optimistic update: flip the row's enabled flag in-place right away so
        // the inline toggle reflects the intent without waiting on the round
        // trip. The follow-up refresh reconciles against HA's authoritative
        // state; on a failed dispatch the row snaps back to its real value once
        // that refresh lands.
        _ui.value = _ui.value.copy(all = withEnabled(_ui.value.all, entry.id, enabled))
        viewModelScope.launch {
            val call = ServiceCall(
                target = entry.id,
                service = if (enabled) "turn_on" else "turn_off",
                data = JsonObject(emptyMap()),
            )
            haRepository.call(call).fold(
                onSuccess = {
                    R1Log.i("Automations", "${if (enabled) "enabled" else "disabled"} ${entry.id.value}")
                    Toaster.show(
                        "${if (enabled) "Enabled" else "Disabled"} '${entry.name}'",
                    )
                },
                onFailure = { t ->
                    R1Log.w("Automations", "toggle ${entry.id.value} failed: ${t.message}")
                    Toaster.error("Toggle failed: ${t.message ?: "unknown"}")
                    // Revert the optimistic flip immediately on failure; the
                    // refresh below still runs but this keeps the row honest
                    // even if the network is down and the refresh also fails.
                    _ui.value = _ui.value.copy(
                        all = withEnabled(_ui.value.all, entry.id, !enabled),
                    )
                },
            )
            kotlinx.coroutines.delay(400L)
            refresh()
        }
    }

    /** `automation.reload`: re-read every automation from
     *  automations.yaml (and the UI editor's storage). Useful after
     *  editing in HA's web UI to pick up changes without restarting
     *  HA. Dispatches with no target since reload is global. */
    fun reload() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(reloading = true)
            // automation.reload is a domain-level service that takes no
            // entity_id, so dispatch it via callRawService rather than the
            // entity-targeted call path. The old approach piggybacked on
            // all.firstOrNull(), which silently no-opped when the list was
            // empty: exactly the case (a config error left no automations
            // loaded) where the user most needs reload to work. Going
            // domain-level means reload fires regardless of the current list.
            haRepository.callRawService(
                domain = "automation",
                service = "reload",
                data = JsonObject(emptyMap()),
            ).fold(
                onSuccess = {
                    R1Log.i("Automations", "reload dispatched")
                    Toaster.show("Automations reloaded")
                },
                onFailure = { t ->
                    R1Log.w("Automations", "reload failed: ${t.message}")
                    Toaster.error("Reload failed: ${t.message ?: "unknown"}")
                },
            )
            kotlinx.coroutines.delay(800L)
            refresh()
            _ui.value = _ui.value.copy(reloading = false)
        }
    }

    /** Pin this automation to the active page's favourites. Same
     *  shape as SearchViewModel.addToFavorites: adds the entity_id
     *  to settings.activePage.favorites if it isn't there already.
     *  Reads as 'star this automation onto my home stack'. */
    fun addToFavorites(entry: Entry) {
        viewModelScope.launch {
            settings.updateActivePage { page ->
                if (entry.id.value in page.favorites) page
                else page.copy(favorites = page.favorites + entry.id.value)
            }
            R1Log.i("Automations", "favourited ${entry.id.value}")
            Toaster.show("Added '${entry.name}' to favourites")
        }
    }

    companion object {
        /** Derive the enabled flag from an automation's raw state string.
         *  HA reports "on" for an enabled automation (its triggers fire) and
         *  "off" for a disabled one. Anything else (unavailable, unknown,
         *  blank) is treated as not-enabled so the toggle reads off rather
         *  than guessing. Case-insensitive to match HA's loose casing. */
        internal fun enabledFrom(rawState: String?): Boolean =
            rawState?.trim()?.equals("on", ignoreCase = true) == true

        /** True when HA reports a real on/off state. "unavailable" (the
         *  integration failed to load / the automation has a config error)
         *  and "unknown" both mean the automation can't be toggled or run,
         *  so we treat anything that isn't a clean on/off as unavailable.
         *  Case-insensitive to match HA's loose casing. */
        internal fun availableFrom(rawState: String?): Boolean =
            when (rawState?.trim()?.lowercase()) {
                "on", "off" -> true
                else -> false
            }

        /** Map HA's `mode` attribute to one of our [Mode] enum cases. A null
         *  attribute or a future HA mode we haven't enumerated falls through
         *  to [Mode.UNKNOWN] so the badge still renders. */
        internal fun modeOf(raw: String?): Mode = when (raw?.trim()?.lowercase()) {
            "single" -> Mode.SINGLE
            "parallel" -> Mode.PARALLEL
            "queued" -> Mode.QUEUED
            "restart" -> Mode.RESTART
            else -> Mode.UNKNOWN
        }

        /** Parse the `last_triggered` attribute into an [Instant]. Returns
         *  null for a never-fired automation (null/blank attribute) or a
         *  malformed timestamp, so the relative-time label silently hides
         *  rather than throwing. */
        internal fun lastTriggeredOf(raw: String?): Instant? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            return parseHaInstant(trimmed)
        }

        /** Return a copy of [all] with the entry matching [id] flipped to
         *  [enabled]. Pure list transform backing the optimistic toggle so it
         *  can be exercised without a live ViewModel / repository. */
        internal fun withEnabled(
            all: List<Entry>,
            id: EntityId,
            enabled: Boolean,
        ): List<Entry> = all.map { if (it.id == id) it.copy(enabled = enabled) else it }

        fun factory(
            haRepository: HaRepository,
            settings: com.github.itskenny0.r1ha.core.prefs.SettingsRepository,
        ) = viewModelFactory {
            initializer { AutomationsViewModel(haRepository, settings) }
        }
    }
}
