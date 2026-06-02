package com.github.itskenny0.r1ha.feature.scenes

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.Domain
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
 * Drives the Scenes & Scripts launcher. Pulls the full HA entity list
 * (the same REST call the favourites picker uses) and surfaces every
 * `scene.*` / `script.*` as a tappable row.
 *
 * Fire dispatch is the same `ServiceCall` shape the card stack uses:
 * scene activation is `scene.turn_on`, script execution is
 * `script.turn_on` (HA accepts that as an alias for the per-script
 * `script.<id>` service, with the bonus that we don't have to derive a
 * service name from the entity id). Failures get force-shown via
 * [Toaster.error] because firing a scene is an intentional action and
 * the user needs to know if it didn't land.
 */
class ScenesViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    enum class Kind { SCENE, SCRIPT }

    /** Filter chip selection. */
    enum class Filter { ALL, SCENES, SCRIPTS }

    @Stable
    data class Entry(
        val id: EntityId,
        val name: String,
        val kind: Kind,
        /**
         * When this scene / script last fired, derived from HA state. Scenes set
         * their `state` to the last-activated ISO-8601 timestamp; scripts keep
         * `state` as on/off and report the time in a `last_triggered` attribute.
         * Null when the entity has never run (HA reports `unknown`) or the
         * timestamp couldn't be parsed. Drives the subtle 'activated <relative>'
         * label on the row.
         */
        val lastActivated: Instant? = null,
        /**
         * Script-only: HA reports a script as `state: on` for the whole time it's
         * actually executing (scenes never have a meaningful on-state, their
         * `state` is the last-fired timestamp). Surfaces a persistent "RUNNING"
         * indicator so a long-running script doesn't look idle. Always false for
         * scenes. Distinct from the transient [UiState.firing] spinner, which only
         * covers the brief turn_on round-trip.
         */
        val running: Boolean = false,
        /**
         * For a `queued` / `parallel` mode script that's running, how many copies
         * are in flight (HA's `current` attribute). Null when not running, when the
         * script is `single` / `restart` mode (no meaningful count), or for scenes.
         * Shown next to the RUNNING badge as e.g. "RUNNING x2".
         */
        val runningCount: Int? = null,
        /**
         * False when HA reports the entity as `unavailable` (integration offline,
         * not yet loaded). Tapping an unavailable scene / script would fail, so the
         * row dims itself, drops the spinner, and announces the unavailable state
         * rather than firing. Mirrors HA's own `canRun` gate.
         */
        val available: Boolean = true,
    )

    @Stable
    data class UiState(
        val loading: Boolean = true,
        /** All loaded entries (full set); [entries] applies kind + query filters. */
        val all: List<Entry> = emptyList(),
        val filter: Filter = Filter.ALL,
        /** Free-text search query; substring-matched against the entry name
         *  and entity_id, case-insensitive. Empty = no text filter. */
        val query: String = "",
        /** Per-filter counts for the chip labels. ALL is the full size. */
        val counts: Map<Filter, Int> = emptyMap(),
        /** True while the "All Lights Off" master action is in flight; the
         *  button disables itself to prevent double-tap re-fires. */
        val masterActionInFlight: Boolean = false,
        /** Entity ids of scenes / scripts whose `turn_on` is currently in
         *  flight. The row renders a spinner and ignores re-taps while its id
         *  is present so a slow HA round-trip can't be double-fired. */
        val firing: Set<EntityId> = emptySet(),
        /** Last load failure message, or null when the most recent load
         *  succeeded. Drives a dedicated error state (distinct from empty) so a
         *  failed /api/states fetch reads as "couldn't load" rather than "you
         *  have no scenes". Cleared on the next successful refresh. */
        val error: String? = null,
    ) {
        /** Subset visible under the current filter + search query. Counts are
         *  small (typically <50) so the in-place filter is trivial. */
        val entries: List<Entry> get() {
            val byKind = when (filter) {
                Filter.ALL -> all
                Filter.SCENES -> all.filter { it.kind == Kind.SCENE }
                Filter.SCRIPTS -> all.filter { it.kind == Kind.SCRIPT }
            }
            if (query.isBlank()) return byKind
            val q = query.trim().lowercase()
            return byKind.filter {
                it.name.lowercase().contains(q) || it.id.value.lowercase().contains(q)
            }
        }
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true)
            haRepository.listAllEntities().fold(
                onSuccess = { entities ->
                    val entries = entities.mapNotNull { es ->
                        val kind = when (es.id.domain) {
                            Domain.SCENE -> Kind.SCENE
                            Domain.SCRIPT -> Kind.SCRIPT
                            else -> null
                        } ?: return@mapNotNull null
                        // A running script reports state: on; HA carries the in-flight
                        // copy count in `current` for queued / parallel mode scripts.
                        val running = kind == Kind.SCRIPT && es.isOn
                        val current = (es.attributesJson?.get("current") as? JsonPrimitive)
                            ?.content?.toIntOrNull()
                        Entry(
                            id = es.id,
                            name = es.friendlyName,
                            kind = kind,
                            lastActivated = lastActivatedOf(
                                kind = kind,
                                rawState = es.rawState,
                                lastTriggeredAttr = (es.attributesJson?.get("last_triggered")
                                    as? JsonPrimitive)?.content,
                            ),
                            running = running,
                            // Only surface a count when it actually adds information:
                            // more than one concurrent copy. single / restart scripts
                            // report current=1 while running, which the RUNNING badge
                            // already conveys.
                            runningCount = if (running && current != null && current > 1) current else null,
                            available = es.isAvailable,
                        )
                    }.sortedBy { it.name.lowercase() }
                    val counts = mapOf(
                        Filter.ALL to entries.size,
                        Filter.SCENES to entries.count { it.kind == Kind.SCENE },
                        Filter.SCRIPTS to entries.count { it.kind == Kind.SCRIPT },
                    )
                    R1Log.i(
                        "Scenes",
                        "loaded scenes=${counts[Filter.SCENES]} scripts=${counts[Filter.SCRIPTS]}",
                    )
                    // Preserve the user's current filter + query across a refresh so a
                    // periodic / pull reload doesn't reset what they were looking at.
                    _ui.value = UiState(
                        loading = false,
                        all = entries,
                        filter = _ui.value.filter,
                        query = _ui.value.query,
                        counts = counts,
                        error = null,
                    )
                },
                onFailure = { t ->
                    R1Log.w("Scenes", "list failed: ${t.message}")
                    val msg = t.message ?: "unknown"
                    Toaster.error("Scenes load failed: $msg")
                    // Keep any previously-loaded entries on screen (a transient fetch
                    // failure shouldn't blank a working list) but record the error so
                    // a first-load failure shows a distinct error state instead of the
                    // "you have no scenes" empty copy.
                    _ui.value = _ui.value.copy(loading = false, error = msg)
                },
            )
        }
    }

    fun setFilter(filter: Filter) {
        if (_ui.value.filter == filter) return
        _ui.value = _ui.value.copy(filter = filter)
    }

    fun setQuery(query: String) {
        if (_ui.value.query == query) return
        _ui.value = _ui.value.copy(query = query)
    }

    /**
     * Surface the entity_id + a hint at how to wire this into an HA
     * automation. Useful when the user wants to reference a script from
     * Node-RED, an automation YAML, or a webhook. Long-press is the
     * non-destructive gesture (tap fires the entity) so it's the right
     * place for an information affordance.
     */
    fun showDetail(entry: Entry) {
        val short = entry.id.value
        val full = buildString {
            append(entry.name).append('\n')
            append(entry.id.value).append('\n')
            append(
                when (entry.kind) {
                    Kind.SCENE -> "service: scene.turn_on"
                    Kind.SCRIPT -> "service: script.turn_on"
                },
            )
        }
        Toaster.showExpandable(shortText = short, fullText = full)
    }

    /**
     * Master "all lights off": dispatches `light.turn_off` with
     * `entity_id: "all"`, which HA treats as "every entity in the
     * light domain". Same trick HA's own frontend dashboards use for
     * the "All Lights Off" tile.
     *
     * Why these master actions live on the Scenes screen: same
     * conceptual surface as scene activation: fire-and-forget mass
     * actions. Saves a navigation hop when the user just wants
     * "everything off" or "stop the music".
     */
    fun allLightsOff() = fireMasterOff(
        domain = Domain.LIGHT,
        service = "turn_off",
        emptyMessage = "No light entities. Nothing to turn off",
        successMessage = "All lights off",
        failurePrefix = "All-lights-off",
    )

    /** Master "all lights on": opposite end of allLightsOff. Useful
     *  for kiosk-mode wake-up sequences. Single tap, no brightness
     *  ramp (HA's turn_on with no payload restores last-known state). */
    fun allLightsOn() = fireMasterOff(
        domain = Domain.LIGHT,
        service = "turn_on",
        emptyMessage = "No light entities. Nothing to turn on",
        successMessage = "All lights on",
        failurePrefix = "All-lights-on",
    )

    /** Master "all media pause": fires `media_player.media_pause` for
     *  every media_player entity. Some integrations honour pause as
     *  stop; that's HA's responsibility, not ours. */
    fun allMediaPause() = fireMasterOff(
        domain = Domain.MEDIA_PLAYER,
        service = "media_pause",
        emptyMessage = "No media players. Nothing to pause",
        successMessage = "All media paused",
        failurePrefix = "All-media-pause",
    )

    /** Master "all switches off": fires `switch.turn_off` across the
     *  switch domain. Useful for "kill the plugs" before bed. */
    fun allSwitchesOff() = fireMasterOff(
        domain = Domain.SWITCH,
        service = "turn_off",
        emptyMessage = "No switches. Nothing to turn off",
        successMessage = "All switches off",
        failurePrefix = "All-switches-off",
    )

    /** Common dispatcher for the "all X off" master buttons. Picks any
     *  entity in [domain] from the cached states so the [ServiceCall]
     *  constructor can carry a target: the actual scope is set via
     *  the `entity_id: "all"` data field which HA recognises as
     *  "every entity in this domain". */
    private fun fireMasterOff(
        domain: Domain,
        service: String,
        emptyMessage: String,
        successMessage: String,
        failurePrefix: String,
    ) {
        if (_ui.value.masterActionInFlight) return
        _ui.value = _ui.value.copy(masterActionInFlight = true)
        viewModelScope.launch {
            val anyEntity = haRepository.listAllEntities().getOrNull()
                ?.firstOrNull { it.id.domain == domain }
                ?.id
            if (anyEntity == null) {
                Toaster.show(emptyMessage)
                _ui.value = _ui.value.copy(masterActionInFlight = false)
                return@launch
            }
            val call = ServiceCall(
                target = anyEntity,
                service = service,
                data = buildJsonObject {
                    put("entity_id", JsonPrimitive("all"))
                },
            )
            haRepository.call(call).fold(
                onSuccess = {
                    R1Log.i("Scenes", "$failurePrefix dispatched")
                    Toaster.show(successMessage)
                },
                onFailure = { t ->
                    R1Log.w("Scenes", "$failurePrefix failed: ${t.message}")
                    Toaster.error("$failurePrefix failed: ${t.message ?: "unknown"}")
                },
            )
            _ui.value = _ui.value.copy(masterActionInFlight = false)
        }
    }

    /**
     * Fire a scene or script. Both use `turn_on` with no payload: HA
     * treats `scene.turn_on` / `script.turn_on` as the activation
     * service for these action-only domains.
     */
    fun fire(entry: Entry) {
        // Ignore a re-tap while this entity's turn_on is still in flight so a
        // slow HA round-trip can't double-fire the scene.
        if (entry.id in _ui.value.firing) return
        // An unavailable entity (integration offline) can't be activated; HA's own
        // frontend gates this. Tell the user rather than firing into the void.
        if (!entry.available) {
            Toaster.show("'${entry.name}' is unavailable")
            return
        }
        _ui.value = _ui.value.copy(firing = _ui.value.firing + entry.id)
        viewModelScope.launch {
            val call = ServiceCall(
                target = entry.id,
                service = "turn_on",
                data = JsonObject(emptyMap()),
            )
            haRepository.call(call).fold(
                onSuccess = {
                    R1Log.i("Scenes", "fired ${entry.id.value}")
                    Toaster.show("Fired '${entry.name}'")
                },
                onFailure = { t ->
                    R1Log.w("Scenes", "fire ${entry.id.value} failed: ${t.message}")
                    Toaster.error("Fire failed: ${t.message ?: "unknown"}")
                },
            )
            _ui.value = _ui.value.copy(firing = _ui.value.firing - entry.id)
        }
    }

    companion object {
        /**
         * Derive the last-activated [Instant] for a scene / script from its HA
         * state. Two sources, because HA models them differently:
         *
         *  * SCENE: the entity `state` itself is the last-activated ISO-8601
         *    timestamp (e.g. `2026-05-29T08:13:04.123456+00:00`). Before a scene
         *    has ever run HA reports `unknown` / `unavailable`, which won't parse
         *    and so yields null.
         *  * SCRIPT: `state` is on/off; the time lives in the `last_triggered`
         *    attribute (same field automations use), null until first run.
         *
         * Pure + side-effect free so it's unit-testable without a repository.
         * Any unparseable value (placeholder, blank, `unknown`) maps to null so
         * the caller simply omits the freshness label.
         */
        fun lastActivatedOf(
            kind: Kind,
            rawState: String?,
            lastTriggeredAttr: String?,
        ): Instant? {
            val candidate = when (kind) {
                Kind.SCENE -> rawState
                Kind.SCRIPT -> lastTriggeredAttr
            }?.trim().orEmpty()
            if (candidate.isEmpty() ||
                candidate.equals("unknown", ignoreCase = true) ||
                candidate.equals("unavailable", ignoreCase = true) ||
                candidate.equals("none", ignoreCase = true)
            ) {
                return null
            }
            return parseHaInstant(candidate)
        }

        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { ScenesViewModel(haRepository) }
        }
    }
}
