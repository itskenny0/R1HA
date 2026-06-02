package com.github.itskenny0.r1ha.feature.floors

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Floors registry surface. HA's floor primitive groups areas — a "main
 * floor" might contain Kitchen / Living Room / Office; "basement" might
 * contain Garage / Laundry. Each floor lists its constituent areas with
 * the entity count rolled up per area so the user can see "which floor
 * has the most going on".
 *
 * Driven by the `/api/template` endpoint:
 *
 *     {% for floor in floors() %}
 *       floor_name(floor) → human label
 *       floor_attr(floor, "level") → building level (number, may be null)
 *       floor_attr(floor, "icon") → user-set mdi icon (may be null)
 *       floor_areas(floor) → list of area_ids
 *       area_entities(area_id) → entities in each constituent area
 *
 * HA orders floors by building [Floor.level] (basement below ground floor
 * below upper storeys), matching the canonical frontend, so we sort on
 * level first and fall back to name for floors that share (or omit) one.
 */
class FloorsViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class AreaInFloor(
        /** Stable HA area_id (e.g. "kitchen"), used as the list key. */
        val areaId: String,
        val name: String,
        val entityCount: Int,
    )

    @androidx.compose.runtime.Stable
    data class Floor(
        /** Stable HA floor_id (e.g. "ground_floor"), used as the list key. */
        val floorId: String,
        val name: String,
        /** Building level: lower is further down. Null when unset in HA. */
        val level: Int?,
        /** User-set mdi icon slug (e.g. "mdi:home-floor-1"), null when unset. */
        val icon: String?,
        val areas: List<AreaInFloor>,
    )

    @androidx.compose.runtime.Stable
    data class UiState(
        /** True only on the initial load (full-screen spinner). Subsequent
         *  pull-to-refresh passes set [refreshing] instead so the list stays
         *  on screen rather than blanking back to the spinner. */
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val floors: List<Floor> = emptyList(),
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            // First load shows the full-screen spinner; a re-fetch over an
            // already-populated list keeps the rows on screen and drives the
            // pull-to-refresh indicator instead.
            val firstLoad = _ui.value.floors.isEmpty()
            _ui.value = _ui.value.copy(
                loading = firstLoad,
                refreshing = !firstLoad,
                error = null,
            )
            // Single template pass: for each floor, its id/name/level/icon plus
            // each constituent area's id, name and entity count. One round trip
            // avoids the N+1 we'd otherwise pay per floor and per area.
            val tpl = """
                {%- set out = namespace(items=[]) -%}
                {%- for floor in floors() -%}
                  {%- set areas_list = namespace(items=[]) -%}
                  {%- for a in floor_areas(floor) -%}
                    {%- set _ = areas_list.items.append({"id": a, "name": area_name(a), "count": area_entities(a) | length}) -%}
                  {%- endfor -%}
                  {%- set _ = out.items.append({"id": floor, "name": floor_name(floor), "level": floor_attr(floor, "level"), "icon": floor_attr(floor, "icon"), "areas": areas_list.items}) -%}
                {%- endfor -%}
                {{ out.items | tojson }}
            """.trimIndent()
            haRepository.renderTemplate(tpl).fold(
                onSuccess = { rendered ->
                    runCatching {
                        val root = Json.parseToJsonElement(rendered)
                        val arr = root as? JsonArray
                            ?: error("Unexpected template response shape. Not an array")
                        val list = arr.mapNotNull { el ->
                            val obj = el as? JsonObject ?: return@mapNotNull null
                            val floorId = (obj["id"] as? JsonPrimitive)?.content
                                ?: return@mapNotNull null
                            val name = (obj["name"] as? JsonPrimitive)?.content ?: floorId
                            // level/icon come back as JSON null when unset in HA;
                            // toIntOrNull also guards a stray non-numeric level.
                            val level = (obj["level"] as? JsonPrimitive)
                                ?.takeUnless { it.content == "null" }
                                ?.content?.toIntOrNull()
                            val icon = (obj["icon"] as? JsonPrimitive)
                                ?.content?.takeIf { it.isNotBlank() && it != "null" }
                            val areasArr = obj["areas"] as? JsonArray
                            val areas = areasArr?.mapNotNull { a ->
                                val aObj = a as? JsonObject ?: return@mapNotNull null
                                val aId = (aObj["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                                val aName = (aObj["name"] as? JsonPrimitive)?.content ?: aId
                                val cnt = (aObj["count"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                                AreaInFloor(areaId = aId, name = aName, entityCount = cnt)
                            }.orEmpty()
                            Floor(
                                floorId = floorId,
                                name = name,
                                level = level,
                                icon = icon,
                                areas = areas.sortedBy { it.name.lowercase() },
                            )
                        }
                            // Canonical HA ordering: by building level (lowest
                            // first), floors with no level last, name as the
                            // tie-break so the list is deterministic.
                            .sortedWith(
                                compareBy(
                                    { it.level == null },
                                    { it.level ?: 0 },
                                    { it.name.lowercase() },
                                ),
                            )
                        R1Log.i("Floors", "loaded ${list.size}")
                        _ui.value = _ui.value.copy(
                            loading = false, refreshing = false, floors = list, error = null,
                        )
                    }.onFailure { t ->
                        R1Log.w("Floors", "parse failed: ${t.message}")
                        Toaster.error("Floors parse failed. Try Templates to debug")
                        _ui.value = _ui.value.copy(loading = false, refreshing = false, error = t.message)
                    }
                },
                onFailure = { t ->
                    R1Log.w("Floors", "fetch failed: ${t.message}")
                    Toaster.error("Floors load failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(loading = false, refreshing = false, error = t.message)
                },
            )
        }
    }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { FloorsViewModel(haRepository) }
        }
    }
}
