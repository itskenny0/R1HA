package com.github.itskenny0.r1ha.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Drives the Universal Search surface. Pulls every entity HA exposes
 * via the supported Domain enum (the same listAllEntities call the
 * favourites picker uses), filters by substring on friendlyName +
 * entity_id, and returns the matches grouped by domain.
 *
 * Tap action depends on the entity's domain:
 *  - Scenes / scripts / buttons → fire via the appropriate service
 *  - On/off entities (light / switch / fan / cover / lock) → toggle
 *  - Everything else → surface a detail toast with state + area
 *
 * Acts as a complement to the Favourites Picker (which is for
 * managing the card stack) and the Scenes screen (which is scene/
 * script-only). This surface is for "I know I have an entity called
 * 'Bedroom Light' — find it and fire it" without configuring it as
 * a favourite first.
 */
class SearchViewModel(
    private val haRepository: HaRepository,
    private val settings: com.github.itskenny0.r1ha.core.prefs.SettingsRepository,
) : ViewModel() {

    @Volatile
    private var resultCap: Int = 80

    /** The result cap currently in force (snapshotted from settings on [refresh]). Exposed so the
     *  results header can tell the user when the list was truncated ("showing the first N")
     *  rather than letting matches silently vanish past the cap. */
    val currentResultCap: Int get() = resultCap

    /** Coarse-grained domain bucket for the filter chips. Maps the
     *  Domain enum into the four user-facing groupings the chips
     *  expose; "ALL" disables the kind filter entirely. */
    enum class Bucket { ALL, CONTROLS, SENSORS, ACTIONS, OTHER }

    /** Visible so the chip row can compute per-bucket counts without
     *  re-implementing the domain→bucket mapping. Single source of truth. */
    fun bucketOf(d: Domain): Bucket = when (d) {
        Domain.LIGHT, Domain.SWITCH, Domain.FAN, Domain.COVER, Domain.MEDIA_PLAYER,
        Domain.LOCK, Domain.INPUT_BOOLEAN, Domain.HUMIDIFIER, Domain.CLIMATE,
        Domain.WATER_HEATER, Domain.VACUUM, Domain.LAWN_MOWER, Domain.VALVE, Domain.NUMBER,
        Domain.INPUT_NUMBER, Domain.SELECT, Domain.INPUT_SELECT,
        Domain.ALARM_CONTROL_PANEL,
        // Counter + timer are controllable too (counter.increment etc.,
        // timer.start etc.) so they live under CONTROLS for filtering.
        Domain.COUNTER, Domain.TIMER -> Bucket.CONTROLS
        Domain.SENSOR, Domain.BINARY_SENSOR,
        // input_text / input_datetime are read-only from this app's
        // perspective; group them under SENSORS so they match the
        // 'just a value to display' user mental model.
        Domain.INPUT_TEXT, Domain.INPUT_DATETIME,
        // Person presence + weather are read-only info entities — group
        // them under SENSORS alongside the other display-only readouts.
        Domain.PERSON, Domain.WEATHER -> Bucket.SENSORS
        Domain.SCENE, Domain.SCRIPT, Domain.BUTTON, Domain.INPUT_BUTTON,
        Domain.AUTOMATION -> Bucket.ACTIONS
        // Update entities live on the dedicated Updates screen; route them
        // into OTHER so the CONTROLS/SENSORS/ACTIONS chips don't pollute
        // with them, but they remain findable via the ALL chip + free-text
        // search for users who know the entity_id.
        Domain.UPDATE -> Bucket.OTHER
        // Remote/IR blasters control devices via send_command — sit them
        // under CONTROLS alongside switches and the like.
        Domain.REMOTE -> Bucket.CONTROLS
        // Catch-all domains with no archetype (device_tracker, zone, calendar, ...) are
        // search-only, read-only results. They bucket under OTHER.
        Domain.OTHER -> Bucket.OTHER
    }

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        /** All entities loaded once on entry. Search filters this in
         *  memory so keystrokes don't hit the network. */
        val all: List<EntityState> = emptyList(),
        val query: String = "",
        val bucket: Bucket = Bucket.ALL,
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    /**
     * Filtered+sorted results derived from [ui]. Pushed to Dispatchers.Default via
     * [flowOn] so a 2000-entity registry re-filters off the main thread; typing one
     * character no longer blocks Compose recomposition while we iterate everything.
     * stateIn keeps the latest value warm so the screen reads it synchronously.
     *
     * Keystrokes are debounced via [QUERY_DEBOUNCE_MILLIS] so a burst of fast typing
     * runs the filter once on the settled query rather than once per character. The
     * debounce is keyed on the query alone: bucket-chip taps and the registry refresh
     * are dropped through immediately (timeoutMillis = 0) so those stay snappy. The
     * eventual results are identical to the un-debounced path: debounce only collapses
     * intermediate query states, it never changes what matches or the ordering.
     */
    @OptIn(FlowPreview::class)
    val results: StateFlow<List<EntityState>> =
        _ui.debounce { s -> if (s.query == debouncedFrom) QUERY_DEBOUNCE_MILLIS else 0L }
            .map { s ->
                debouncedFrom = s.query
                filterAndSort(s)
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Last query the [results] pipeline actually filtered on. Lets the debounce skip
     *  the wait when the user is changing a bucket (or the registry refreshed) rather
     *  than typing, so only genuine keystroke bursts pay the debounce window. */
    @Volatile
    private var debouncedFrom: String = ""

    /**
     * Per-bucket entity counts, precomputed off Main so the BucketChips row doesn't
     * eachCount() the entire registry on every recomposition. Keyed on the underlying
     * [UiState.all] list reference: the chip counts only need recompute when the
     * registry itself changes, not on every keystroke.
     */
    val bucketCounts: StateFlow<Map<Bucket, Int>> =
        _ui.map { it.all }
            .distinctUntilChanged()
            .map { all ->
                buildMap<Bucket, Int> {
                    put(Bucket.ALL, all.size)
                    Bucket.entries.filter { it != Bucket.ALL }.forEach { b ->
                        put(b, all.count { e -> bucketOf(e.id.domain) == b })
                    }
                }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private fun filterAndSort(s: UiState): List<EntityState> =
        SearchRanker.filter(
            all = s.all,
            query = s.query,
            bucket = s.bucket,
            bucketOf = ::bucketOf,
            resultCap = resultCap,
        )

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            // Snapshot the result cap from settings so the .take() below
            // reflects user prefs without each call paying for a flow
            // collection.
            resultCap = settings.settings.first().integrations.searchResultCap.coerceIn(1, 1000)
            haRepository.listAllEntitiesForSearch().fold(
                onSuccess = { entities ->
                    R1Log.i("Search", "loaded ${entities.size} entities")
                    _ui.value = _ui.value.copy(loading = false, all = entities, error = null)
                },
                onFailure = { t ->
                    R1Log.w("Search", "list failed: ${t.message}")
                    Toaster.error("Search load failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(loading = false, error = t.message)
                },
            )
        }
    }

    fun setQuery(q: String) {
        if (_ui.value.query == q) return
        _ui.value = _ui.value.copy(query = q)
    }

    fun setBucket(b: Bucket) {
        if (_ui.value.bucket == b) return
        _ui.value = _ui.value.copy(bucket = b)
    }

    /**
     * Tap action — dispatches the appropriate service for the entity's
     * domain. Action-only entities (scene / script / button) fire;
     * controllable entities (light / switch / fan / etc.) toggle;
     * everything else surfaces a detail toast.
     */
    fun activate(entity: EntityState) {
        viewModelScope.launch {
            val target = entity.id
            when {
                target.domain == Domain.SCENE -> {
                    haRepository.call(ServiceCall(target, "turn_on", JsonObject(emptyMap())))
                    Toaster.show("Fired scene '${entity.friendlyName}'")
                }
                target.domain == Domain.SCRIPT -> {
                    haRepository.call(ServiceCall(target, "turn_on", JsonObject(emptyMap())))
                    Toaster.show("Fired script '${entity.friendlyName}'")
                }
                target.domain == Domain.BUTTON || target.domain == Domain.INPUT_BUTTON -> {
                    haRepository.call(ServiceCall(target, "press", JsonObject(emptyMap())))
                    Toaster.show("Pressed '${entity.friendlyName}'")
                }
                // For toggleable entities (lights / switches / fans /
                // covers / locks / media_players) use ServiceCall.tapAction
                // which encodes the right on→off, off→on semantics per
                // domain.
                target.domain == Domain.LIGHT || target.domain == Domain.SWITCH ||
                    target.domain == Domain.FAN || target.domain == Domain.COVER ||
                    target.domain == Domain.LOCK || target.domain == Domain.MEDIA_PLAYER ||
                    target.domain == Domain.INPUT_BOOLEAN || target.domain == Domain.AUTOMATION ||
                    target.domain == Domain.HUMIDIFIER || target.domain == Domain.CLIMATE ||
                    target.domain == Domain.WATER_HEATER || target.domain == Domain.VACUUM ||
                    target.domain == Domain.LAWN_MOWER ||
                    target.domain == Domain.VALVE -> {
                    haRepository.call(ServiceCall.tapAction(target, entity.isOn))
                    Toaster.show("${if (entity.isOn) "Off" else "On"}: ${entity.friendlyName}")
                }
                else -> {
                    // Sensors / numbers / selects — read-only or not
                    // tap-toggle-friendly. Surface a detail toast instead.
                    val parts = buildString {
                        append(entity.friendlyName).append('\n')
                        append(entity.id.value).append('\n')
                        append("state: ").append(entity.rawState ?: if (entity.isOn) "on" else "off")
                        if (entity.area != null) append("\narea: ").append(entity.area)
                    }
                    Toaster.showExpandable(shortText = entity.friendlyName, fullText = parts)
                }
            }
        }
    }

    /** Add an entity to the active page's favourites set. Used by the
     *  Search screen's star affordance — turns "I just found this
     *  entity by name" into "now it's a card on my home stack" in one
     *  tap. Idempotent (re-adding does nothing). */
    fun addToFavorites(entityId: EntityId) {
        viewModelScope.launch {
            settings.updateActivePage { page ->
                if (entityId.value in page.favorites) page
                else page.copy(favorites = page.favorites + entityId.value)
            }
            R1Log.i("Search", "favourited ${entityId.value}")
            Toaster.show("Added '${entityId.value}' to favourites")
        }
    }

    companion object {
        /** Quiet window after the last keystroke before the filter runs. Long enough to
         *  collapse a fast typist's burst into a single filter pass, short enough that
         *  the result list still feels live. */
        private const val QUERY_DEBOUNCE_MILLIS = 120L

        fun factory(
            haRepository: HaRepository,
            settings: com.github.itskenny0.r1ha.core.prefs.SettingsRepository,
        ) = viewModelFactory {
            initializer { SearchViewModel(haRepository, settings) }
        }
    }
}
