package com.github.itskenny0.r1ha.feature.labels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * Labels registry surface. Labels are a post-2024 HA primitive that lets users
 * tag entities, devices, AND areas with arbitrary cross-axis categories
 * ("daily routine", "needs batteries", "rec room AV"). Surfacing them lets the
 * user browse by label the same way they browse by area, and drill into a
 * label to see its full footprint across all three registries.
 *
 * Driven entirely via the `/api/template` endpoint the repository already
 * exposes (no WebSocket protocol additions, no core/ha signature changes). The
 * template resolves, per label:
 *
 *     label_name(label)     -> human label
 *     label_color(label)    -> named theme color or hex (row accent)
 *     label_icon(label)     -> mdi slug
 *     label_entities(label) -> entity_ids (resolved to friendly names)
 *     label_devices(label)  -> device_ids (resolved to device names)
 *     label_areas(label)    -> area_ids   (resolved to area names)
 */
class LabelsViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class Label(
        val id: String,
        val name: String,
        val color: String?,
        val icon: String?,
        val entities: Map<String, String>,
        val devices: Map<String, String>,
        val areas: Map<String, String>,
    ) {
        /** Total tagged things across all three registries. */
        val memberCount: Int get() = entities.size + devices.size + areas.size
    }

    enum class Sort { ALPHA, COUNT }

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val labels: List<Label> = emptyList(),
        val error: String? = null,
        val sort: Sort = Sort.ALPHA,
        val query: String = "",
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    /**
     * Sorted + search-filtered labels for the list. Filtering matches the label
     * name or any member name so "kitchen" surfaces a label that tags the
     * kitchen area even when named otherwise.
     */
    val visibleLabels: StateFlow<List<Label>> = _ui
        .map { s ->
            val filtered = s.labels.filter { label ->
                LabelLogic.matchesQuery(
                    query = s.query,
                    labelName = label.name,
                    memberNames = label.entities.values +
                        label.devices.values +
                        label.areas.values,
                )
            }
            when (s.sort) {
                Sort.ALPHA -> filtered.sortedBy { it.name.lowercase(Locale.US) }
                Sort.COUNT -> filtered.sortedByDescending { it.memberCount }
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            // Resolve member names inline so the drill-in needs no extra round
            // trips. label_color/label_icon are wrapped in defaults() so older
            // HA cores that lack them still render (color falls back in the UI).
            val tpl = """
                {%- set out = namespace(items=[]) -%}
                {%- for label in labels() -%}
                  {%- set ents = namespace(m={}) -%}
                  {%- for e in label_entities(label) -%}
                    {%- set _ = ents.m.update({e: states[e].name | default(e)}) -%}
                  {%- endfor -%}
                  {%- set devs = namespace(m={}) -%}
                  {%- for d in label_devices(label) -%}
                    {%- set _ = devs.m.update({d: device_attr(d, 'name_by_user') or device_attr(d, 'name') or d}) -%}
                  {%- endfor -%}
                  {%- set ars = namespace(m={}) -%}
                  {%- for a in label_areas(label) -%}
                    {%- set _ = ars.m.update({a: area_name(a) | default(a)}) -%}
                  {%- endfor -%}
                  {%- set _ = out.items.append({
                    "id": label,
                    "name": label_name(label),
                    "color": label_color(label) | default(none, true),
                    "icon": label_icon(label) | default(none, true),
                    "entities": ents.m,
                    "devices": devs.m,
                    "areas": ars.m
                  }) -%}
                {%- endfor -%}
                {{ out.items | tojson }}
            """.trimIndent()
            haRepository.renderTemplate(tpl).fold(
                onSuccess = { rendered ->
                    runCatching {
                        val list = parse(rendered)
                        R1Log.i("Labels", "loaded ${list.size}")
                        _ui.value = _ui.value.copy(loading = false, labels = list, error = null)
                    }.onFailure { t ->
                        R1Log.w("Labels", "parse failed: ${t.message}")
                        Toaster.error("Labels parse failed. Try Templates to debug")
                        _ui.value = _ui.value.copy(loading = false, error = t.message)
                    }
                },
                onFailure = { t ->
                    R1Log.w("Labels", "fetch failed: ${t.message}")
                    Toaster.error("Labels load failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(loading = false, error = t.message)
                },
            )
        }
    }

    fun setSort(s: Sort) {
        if (_ui.value.sort == s) return
        _ui.value = _ui.value.copy(sort = s)
    }

    fun setQuery(q: String) {
        if (_ui.value.query == q) return
        _ui.value = _ui.value.copy(query = q)
    }

    fun label(id: String): Label? = _ui.value.labels.firstOrNull { it.id == id }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parse the template payload into [Label]s. Pure: testable in isolation. */
        fun parse(rendered: String): List<Label> {
            val root = json.parseToJsonElement(rendered)
            val arr = root as? JsonArray
                ?: error("Unexpected template response shape. Not an array")
            return arr.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val id = (obj["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val name = (obj["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: id
                Label(
                    id = id,
                    name = name,
                    color = (obj["color"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() },
                    icon = (obj["icon"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() },
                    entities = stringMap(obj["entities"]),
                    devices = stringMap(obj["devices"]),
                    areas = stringMap(obj["areas"]),
                )
            }
        }

        private fun stringMap(el: kotlinx.serialization.json.JsonElement?): Map<String, String> {
            val obj = (el as? JsonObject) ?: return emptyMap()
            return obj.entries.associate { (k, v) ->
                k to ((v as? JsonPrimitive)?.content ?: k)
            }
        }

        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { LabelsViewModel(haRepository) }
        }
    }
}
