package com.github.itskenny0.r1ha.feature.energy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Energy summary surface — mirrors a slim slice of HA's Energy panel.
 *
 * HA's full Energy dashboard has per-hour bar charts, source breakdown
 * (grid / solar / battery), gas + water meters, and per-device
 * consumption. Re-implementing that on the R1's small screen would
 * trade legibility for completeness, so this surface picks the four
 * numbers a user actually wants at a glance:
 *
 *   1. Current power draw (W) — sum of every `device_class=power` sensor
 *      with positive state (negative-sign sensors are battery export /
 *      solar production and are excluded from the consumption total)
 *   2. Solar production (W) — sum of `device_class=power` sensors whose
 *      entity_id matches `*solar*` / `*pv*` / `*grid_export*` /
 *      `*production*` heuristics
 *   3. Today's energy (kWh) — sum of every `device_class=energy` sensor
 *      whose `state_class=total_increasing` and whose `last_reset`
 *      lands today (the standard HA pattern for "kWh since midnight"
 *      sensors)
 *   4. Top 5 current consumers — sorted descending by instantaneous W
 *
 * Everything is fetched via `/api/template` so we never have to ship
 * a full /api/states pull just to aggregate.
 */
class EnergyViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class Consumer(
        val entityId: String,
        val name: String,
        /** Instantaneous power in watts. */
        val watts: Double,
    )

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        /** Sum of every `device_class=power` sensor's positive state in
         *  W. Negative sensors (battery export, grid export) are
         *  excluded so the figure is "what's being USED right now". */
        val currentDrawW: Double? = null,
        /** Production estimate in W from sensors with solar/pv/export
         *  hints in the entity_id. Conservative — only known
         *  patterns count. */
        val productionW: Double? = null,
        /** Today's energy total in kWh — sum of every
         *  `device_class=energy` total_increasing sensor that resets
         *  daily. */
        val todayKwh: Double? = null,
        /** Top consumers by current W draw. Empty when no data. */
        val topConsumers: List<Consumer> = emptyList(),
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            // Four templates in parallel — each one is cheap server-side
            // (a single Jinja pass over states.sensor), but firing them
            // serially would gate every refresh on the slowest. await
            // them all so the UI flips loading → ready in one render.
            val drawJob = async { haRepository.renderTemplate(SUM_POWER_DRAW) }
            val prodJob = async { haRepository.renderTemplate(SUM_PRODUCTION) }
            val kwhJob = async { haRepository.renderTemplate(SUM_TODAY_KWH) }
            val topJob = async { haRepository.renderTemplate(TOP_CONSUMERS_JSON) }
            awaitAll(drawJob, prodJob, kwhJob, topJob)

            val drawRaw = drawJob.await().getOrNull()?.trim()
            val prodRaw = prodJob.await().getOrNull()?.trim()
            val kwhRaw = kwhJob.await().getOrNull()?.trim()
            val topRaw = topJob.await().getOrNull()?.trim()

            // Any single template failure shouldn't tank the whole
            // surface — we render whatever did succeed and the other
            // tiles show '—'. The user can still glean useful info.
            val anyFailed = listOf(drawJob, prodJob, kwhJob, topJob)
                .any { it.await().isFailure }
            if (anyFailed) {
                val firstError = listOf(drawJob, prodJob, kwhJob, topJob)
                    .firstNotNullOfOrNull { it.await().exceptionOrNull()?.message }
                R1Log.w("Energy", "partial load failure: $firstError")
                // Don't toast — partial failure is normal on installs
                // that don't have any power-class sensors yet.
            }

            val top = topRaw?.let { parseTopConsumers(it) }.orEmpty()
            R1Log.i(
                "Energy",
                "draw=$drawRaw prod=$prodRaw kwh=$kwhRaw consumers=${top.size}",
            )
            _ui.value = UiState(
                loading = false,
                currentDrawW = drawRaw?.toDoubleOrNull(),
                productionW = prodRaw?.toDoubleOrNull(),
                todayKwh = kwhRaw?.toDoubleOrNull(),
                topConsumers = top,
                error = if (anyFailed && drawRaw == null && prodRaw == null &&
                    kwhRaw == null && top.isEmpty()) {
                    "All energy templates returned errors. Does HA have any " +
                        "device_class=power or device_class=energy sensors?"
                } else null,
            )
        }
    }

    /** Parse the JSON array of [entity_id, name, watts] triples that
     *  the TOP_CONSUMERS_JSON template emits. Robust to malformed
     *  rows: a single bad triple drops out without breaking the rest. */
    private fun parseTopConsumers(raw: String): List<Consumer> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = Json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
            arr.mapNotNull { row ->
                val r = row as? JsonArray ?: return@mapNotNull null
                if (r.size < 3) return@mapNotNull null
                val id = (r[0] as? JsonPrimitive)?.contentOrNull() ?: return@mapNotNull null
                val name = (r[1] as? JsonPrimitive)?.contentOrNull() ?: id
                val watts = (r[2] as? JsonPrimitive)?.contentOrNull()?.toDoubleOrNull()
                    ?: return@mapNotNull null
                Consumer(entityId = id, name = name, watts = watts)
            }
        }.onFailure { R1Log.w("Energy", "top-consumers parse failed: ${it.message}") }
            .getOrNull().orEmpty()
    }

    /** Compose's runtime doesn't expose JsonPrimitive.contentOrNull
     *  on every minSdk we target — pull `.content` and handle null
     *  manually. */
    private fun JsonPrimitive.contentOrNull(): String? =
        runCatching { content }.getOrNull()?.takeIf { it != "null" }

    companion object {
        /** Sum positive-state device_class=power sensors. Excludes
         *  unavailable / unknown / non-numeric, and excludes the
         *  production-heuristic entities (solar / pv / grid_export /
         *  production): inverters that report production as a positive
         *  power value would otherwise be counted as consumption here
         *  AND as production in SUM_PRODUCTION, double-counting them.
         *  The reject mirrors SUM_PRODUCTION's select so the two slices
         *  stay disjoint. */
        private const val SUM_POWER_DRAW = "{{ states.sensor " +
            "| selectattr('attributes.device_class','eq','power') " +
            "| rejectattr('state','in',['unavailable','unknown','none']) " +
            "| rejectattr('entity_id','search','solar|pv|grid_export|production') " +
            "| map(attribute='state') | map('float',0) " +
            "| select('>',0) | sum | round(0) }}"

        /** Production heuristic — sum power sensors whose entity_id
         *  contains 'solar', 'pv', 'production', or 'grid_export'. */
        private const val SUM_PRODUCTION = "{{ states.sensor " +
            "| selectattr('attributes.device_class','eq','power') " +
            "| rejectattr('state','in',['unavailable','unknown','none']) " +
            "| selectattr('entity_id','search','solar|pv|grid_export|production') " +
            "| map(attribute='state') | map('float',0) " +
            "| map('abs') | sum | round(0) }}"

        /** Sum every device_class=energy sensor whose state_class is
         *  total_increasing — those are the per-day-resetting kWh
         *  meters HA uses for the Energy dashboard. */
        private const val SUM_TODAY_KWH = "{{ states.sensor " +
            "| selectattr('attributes.device_class','eq','energy') " +
            "| selectattr('attributes.state_class','eq','total_increasing') " +
            "| rejectattr('state','in',['unavailable','unknown','none']) " +
            "| map(attribute='state') | map('float',0) | sum | round(2) }}"

        /** JSON array of [entity_id, friendly_name, watts] triples for
         *  the top 5 current consumers. We pass a list comprehension
         *  through to_json so the parsing on the client side is one
         *  Json.parseToJsonElement call. */
        private const val TOP_CONSUMERS_JSON = "{%- set out = namespace(items=[]) -%}" +
            "{%- for s in (states.sensor " +
            "| selectattr('attributes.device_class','eq','power') " +
            "| rejectattr('state','in',['unavailable','unknown','none']) " +
            "| sort(attribute='state', reverse=True))[:8] -%}" +
            "{%- set w = s.state | float(0) -%}" +
            "{%- if w > 0 -%}" +
            "{%- set _ = out.items.append([s.entity_id, s.name, w]) -%}" +
            "{%- endif -%}" +
            "{%- endfor -%}" +
            "{{ out.items | tojson }}"

        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { EnergyViewModel(haRepository) }
        }
    }
}
