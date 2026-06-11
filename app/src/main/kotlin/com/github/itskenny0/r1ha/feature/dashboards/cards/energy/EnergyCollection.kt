package com.github.itskenny0.r1ha.feature.dashboards.cards.energy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.github.itskenny0.r1ha.core.ha.EnergyInfo
import com.github.itskenny0.r1ha.core.ha.EnergyPreferences
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.StatisticsBucket
import com.github.itskenny0.r1ha.core.theme.LocalHaRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.ui.components.bucketForSpan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The shared, process-level energy collection registry: the R1 mirror of HA's
 * connection-scoped `EnergyCollection` keyed by `collection_key`. Every energy
 * card on a dashboard that carries the same key looks up the SAME
 * [EnergyCollectionState], so moving the date-range selector reflows all of
 * them. Cards with no explicit key share the default
 * [EnergyPeriodEngine.DEFAULT_COLLECTION_KEY].
 *
 * The registry holds state, not coroutines: a single owner (whichever card
 * mounts first) drives the fetch via [rememberEnergyCollection]; the rest are
 * read-only observers that reuse the owner's fetched data.
 */
object EnergyCollections {
    private val states = ConcurrentHashMap<String, EnergyCollectionState>()

    fun forKey(key: String?): EnergyCollectionState {
        val k = key ?: EnergyPeriodEngine.DEFAULT_COLLECTION_KEY
        return states.getOrPut(k) { EnergyCollectionState() }
    }

    /** Test seam: drop all collections. */
    internal fun clear() = states.clear()
}

/** A snapshot of one collection's fetched data plus its period. */
data class EnergyCollectionData(
    val period: EnergyPeriod,
    val prefs: EnergyPreferences? = null,
    val info: EnergyInfo = EnergyInfo(),
    /** Recorder buckets for the period, keyed by statistic id. */
    val stats: Map<String, List<StatisticsBucket>> = emptyMap(),
    /** Recorder buckets for the comparison window (compare mode), keyed by id. */
    val statsCompare: Map<String, List<StatisticsBucket>> = emptyMap(),
    /** `energy/fossil_energy_consumption` reply (period-start -> fossil kWh) when
     *  a CO2 signal source is configured; null when there is none (the
     *  carbon-consumed gauge then stays on its needs-source note). */
    val fossilEnergyConsumption: Map<String, Double>? = null,
    val loading: Boolean = true,
    /** True once a fetch has resolved (success or empty). */
    val loaded: Boolean = false,
    /** Set when the energy integration is unconfigured / prefs failed. */
    val error: String? = null,
) {
    val summed: EnergySumData by lazy { prefs?.let { summedData(it, stats) } ?: EnergySumData() }
    val summedCompare: EnergySumData? by lazy {
        if (period.compare && prefs != null) summedData(prefs, statsCompare) else null
    }
}

/** Mutable shared state for one collection key. */
class EnergyCollectionState {
    private val _data = MutableStateFlow(
        EnergyCollectionData(period = EnergyPeriodEngine.defaultPeriod(Instant.now())),
    )
    val data: StateFlow<EnergyCollectionData> = _data.asStateFlow()

    /** Generation counter bumped on every period / compare change so the owner's
     *  fetch effect refetches. Starts at 0 so the first collect kicks the load. */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    /** Identity of the card currently responsible for fetching. The first card
     *  to mount claims it; it is released when that card leaves the composition,
     *  letting a surviving card take over. */
    @Volatile
    private var owner: Any? = null

    @Synchronized
    fun claimOwner(token: Any): Boolean {
        if (owner == null) { owner = token; return true }
        return owner === token
    }

    @Synchronized
    fun releaseOwner(token: Any) {
        if (owner === token) owner = null
    }

    @Synchronized
    fun isOwner(token: Any): Boolean = owner === token

    fun setPeriod(period: EnergyPeriod) {
        val cur = _data.value
        if (cur.period == period) return
        _data.value = cur.copy(period = period, loading = true)
        _generation.value += 1
    }

    fun setCompare(compare: Boolean) {
        val cur = _data.value
        if (cur.period.compare == compare) return
        _data.value = cur.copy(period = cur.period.copy(compare = compare), loading = true)
        _generation.value += 1
    }

    fun publish(data: EnergyCollectionData) {
        _data.value = data
    }
}

/** The recorder bucket size to request for a period's span, reusing the shared
 *  statistic-period bucket chooser so energy charts match the rest of the app. */
internal fun bucketForPeriod(period: EnergyPeriod): String =
    bucketForSpan(Duration.between(period.start, period.end))

/**
 * Mount one card into the collection for [collectionKey] and return its shared
 * state. The first caller per key becomes the fetch owner: it loads prefs +
 * info once, then refetches statistics whenever the period or compare flag
 * changes. Later callers for the same key are pure observers. Ownership
 * transfers if the owner leaves the composition.
 */
@Composable
fun rememberEnergyCollection(collectionKey: String?): EnergyCollectionState {
    val repo = LocalHaRepository.current
    val state = remember(collectionKey) { EnergyCollections.forKey(collectionKey) }
    val token = remember(state) { Any() }

    DisposableEffect(state, token) {
        state.claimOwner(token)
        onDispose { state.releaseOwner(token) }
    }

    if (repo != null) {
        LaunchedEffect(state, token, repo) {
            // The generation flow replays its current value on collect, so the
            // owner fetches immediately on mount and again on every change.
            state.generation.collect {
                if (state.claimOwner(token)) {
                    fetchInto(state, repo)
                }
            }
        }
    }
    return state
}

/**
 * Perform one full fetch into [state]: load prefs + info if absent, then the
 * statistics over the current period (and the comparison window when compare is
 * on). Faithful to HA's getEnergyData ordering: prefs -> info -> referenced ids
 * -> statistics requested with `change`.
 */
private suspend fun fetchInto(state: EnergyCollectionState, repo: HaRepository) {
    val cur = state.data.value
    val prefs = cur.prefs ?: repo.getEnergyPreferencesFull().getOrElse { t ->
        R1Log.w("Energy", "prefs load failed: ${t.message}")
        state.publish(cur.copy(loading = false, loaded = true, error = "Energy is not configured"))
        return
    }
    val info = if (cur.info.costSensors.isNotEmpty()) {
        cur.info
    } else {
        repo.getEnergyInfo().getOrElse { EnergyInfo() }
    }

    val ids = referencedStatisticIds(prefs, info.costSensors)
    if (ids.isEmpty()) {
        state.publish(
            cur.copy(
                prefs = prefs, info = info, stats = emptyMap(),
                loading = false, loaded = true, error = null,
            ),
        )
        return
    }
    val period = cur.period
    val bucket = bucketForPeriod(period)
    val stats = repo.getStatisticsDuringPeriod(ids, period.start, period.end, bucket)
        .getOrElse { t ->
            R1Log.w("Energy", "stats load failed: ${t.message}")
            emptyMap()
        }
    val compareStats = if (period.compare) {
        val cmp = EnergyPeriodEngine.compareWindow(period)
        repo.getStatisticsDuringPeriod(ids, cmp.start, cmp.end, bucket).getOrElse { emptyMap() }
    } else {
        emptyMap()
    }
    // Fossil-energy-consumption for the carbon-consumed gauge. Resolve the CO2
    // signal statistic (HA scans for a co2signal-platform % sensor); when none
    // exists the gauge keeps its needs-source note (fossilEnergyConsumption=null).
    val co2StatId = resolveCo2SignalStatId(repo)
    val gridIds = gridConsumptionStatIds(prefs)
    val fossil = if (co2StatId != null && gridIds.isNotEmpty()) {
        repo.getFossilEnergyConsumption(gridIds, co2StatId, period.start, period.end, bucket)
            .getOrNull()
    } else {
        null
    }
    state.publish(
        cur.copy(
            prefs = prefs,
            info = info,
            stats = stats,
            statsCompare = compareStats,
            fossilEnergyConsumption = fossil,
            loading = false,
            loaded = true,
            error = null,
        ),
    )
}

/**
 * Resolve the CO2-signal statistic id HA's fossil-consumption call needs: the
 * `co2signal`-platform entity reporting a `%` value (the grid fossil-fuel
 * percentage). Returns null when no such entity exists, so the carbon-consumed
 * gauge stays unavailable rather than guessing.
 */
private suspend fun resolveCo2SignalStatId(repo: HaRepository): String? {
    val registry = repo.listEntityRegistry().getOrNull() ?: return null
    val candidates = registry
        .filter { it.platform == "co2signal" }
        .map { it.entityId }
    if (candidates.isEmpty()) return null
    // Of the co2signal entities, pick the one whose live unit is "%".
    val states = repo.listAllEntities().getOrNull().orEmpty().associateBy { it.id.value }
    return candidates.firstOrNull { id ->
        states[id]?.unit?.trim() == "%"
    } ?: candidates.first()
}
