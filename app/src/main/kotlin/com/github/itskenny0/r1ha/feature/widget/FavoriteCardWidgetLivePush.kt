package com.github.itskenny0.r1ha.feature.widget

import android.content.Context
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Live refresh for the favorite-card widgets. The launcher's own refresh is
 * the provider XML's updatePeriodMillis, which Android floors at 30 minutes,
 * batches lazily, and skips in Doze — so on its own a widget can sit stale for
 * half an hour or more. This collector runs for the life of the app process
 * (started from App.onCreate) and repaints exactly the affected widget
 * instances the moment a bound entity changes state, riding the same
 * WebSocket cache the in-app cards use.
 *
 * On the always-on R1 the process never dies, so widgets are effectively
 * realtime. On a phone the collector covers whenever the app is alive (plus
 * an immediate full repaint at every process start, which also fixes the
 * "stale until the next half-hour tick" cold case); a dead process still
 * falls back to the 30-minute tick — anything tighter from the background
 * would need WorkManager or an exact alarm, which is a battery trade-off.
 *
 * Repaints go through [FavoriteCardWidgetProvider.requestUpdate] (the same
 * broadcast the host sends) so the provider's existing fetch + render path
 * stays the single source of truth; the 500 ms debounce coalesces bursts so
 * a flapping sensor can't turn into a broadcast storm.
 */
object FavoriteCardWidgetLivePush {

    private const val TAG = "FavoriteCardWidget.live"

    @OptIn(FlowPreview::class)
    suspend fun run(context: Context, repository: HaRepository) {
        // collectLatest: a binding change (widget added / removed / re-bound)
        // tears down the observation and rebuilds it over the new entity set.
        FavoriteCardWidgetStore.bindingsFlow(context)
            .distinctUntilChanged()
            .collectLatest { bindings ->
                if (bindings.isEmpty()) return@collectLatest
                val widgetsByEntity: Map<String, List<Int>> =
                    bindings.entries.groupBy({ it.value }, { it.key })
                val ids = widgetsByEntity.keys
                    .mapNotNull { runCatching { EntityId(it) }.getOrNull() }
                    .toSet()
                if (ids.isEmpty()) return@collectLatest
                R1Log.i(TAG, "watching ${ids.size} entities for ${bindings.size} widgets")
                var previous: Map<String, String>? = null
                repository.observe(ids)
                    .debounce(500)
                    .collect { states ->
                        // Compare on a render-relevant fingerprint rather than
                        // the full EntityState so attribute churn that can't
                        // change the painted card doesn't trigger repaints.
                        val current = states.entries.associate { (id, s) ->
                            id.value to "${s.isOn}|${s.percent}|${s.raw}|${s.isAvailable}|${s.friendlyName}"
                        }
                        val targets = changedWidgetIds(previous, current, widgetsByEntity)
                        previous = current
                        if (targets.isNotEmpty()) {
                            FavoriteCardWidgetProvider.requestUpdate(context, targets.toIntArray())
                        }
                    }
            }
    }
}

/**
 * Which widget instances need a repaint, given the previous and current
 * fingerprints of every bound entity. A null [previous] is process start:
 * everything bound gets one repaint so widgets recover from cold-process
 * staleness immediately rather than at the next half-hour tick. Entities
 * that appear or disappear (entity removed server-side) count as changed.
 */
internal fun <S> changedWidgetIds(
    previous: Map<String, S>?,
    current: Map<String, S>,
    widgetsByEntity: Map<String, List<Int>>,
): List<Int> {
    val changed: Collection<String> = if (previous == null) {
        widgetsByEntity.keys
    } else {
        (previous.keys + current.keys).filter { previous[it] != current[it] }
    }
    return changed.flatMap { widgetsByEntity[it].orEmpty() }.distinct()
}
