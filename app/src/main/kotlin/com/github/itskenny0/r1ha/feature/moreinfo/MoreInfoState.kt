package com.github.itskenny0.r1ha.feature.moreinfo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.HistoryPoint
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.groupThousands
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * Live state for the [MoreInfoSheet]. A small self-contained holder rather than a
 * full ViewModel: the sheet is short-lived (open → interact → dismiss) and only ever
 * scopes one entity, so a [produceState]-driven holder keeps the wiring local to the
 * feature module without a factory / Application plumbing dance.
 *
 * Three phases:
 *  - [loading] true until the first live snapshot (or the one-shot seed) lands.
 *  - [error] set when neither the live observe nor the seed produced the entity.
 *  - otherwise [entity] is non-null and the sheet renders content.
 *
 * History is fetched lazily and independently so a slow `/api/history` call never
 * blocks the controls from rendering.
 */
class MoreInfoState {
    var loading: Boolean = true
        internal set
    var error: String? = null
        internal set
    var entity: EntityState? = null
        internal set
}

/**
 * Drives a [MoreInfoState] for [entityId]:
 *  - seeds immediately from a one-shot `/api/states` fetch (covers domains the typed
 *    cache can't hold, and gives the sheet content before the first WS snapshot),
 *  - then collects live updates via [HaRepository.observeRaw] so controls reflect the
 *    result of the service calls the user fires.
 */
@Composable
fun rememberMoreInfoState(
    haRepository: HaRepository,
    entityId: String,
): State<MoreInfoState> = produceState(
    initialValue = MoreInfoState(),
    key1 = entityId,
) {
    // Helper to publish a fresh immutable snapshot. produceState compares by reference,
    // so a new instance per change guarantees the sheet recomposes.
    fun publish(entity: EntityState?, loading: Boolean, error: String?) {
        value = MoreInfoState().also {
            it.entity = entity
            it.loading = loading
            it.error = error
        }
    }

    // One-shot seed so the sheet has something to render within a frame or two even
    // when the WS cache hasn't been primed for this entity yet. Search variant so an
    // unmodelled-domain entity (device_tracker, sun, ...) still resolves a header +
    // attributes. Only fills the gap before the first live row; never overwrites it.
    launch {
        val seed = haRepository.listAllEntitiesForSearch()
            .getOrNull()
            ?.firstOrNull { it.id.value == entityId }
        when {
            seed != null && value.entity == null -> publish(seed, loading = false, error = null)
            seed == null && value.entity == null ->
                publish(null, loading = false, error = "Entity not found: $entityId")
        }
    }

    // Live stream. observeRaw emits the full EntityState for supported domains; an
    // unmodelled domain is simply absent, which we treat as "keep the seeded copy".
    haRepository.observeRaw(setOf(entityId))
        .catch { /* transport hiccup — keep the last good snapshot */ }
        .collect { byString ->
            val live = byString[entityId]
            if (live != null) publish(live, loading = false, error = null)
        }
}

/**
 * Lazily fetch history for [entityId] when [enabled]. Returns null while in-flight or
 * when disabled; an empty list when the fetch failed (the chart degrades to a hint).
 */
@Composable
fun rememberHistory(
    haRepository: HaRepository,
    entityId: String,
    enabled: Boolean,
    hours: Int = 24,
): State<List<HistoryPoint>?> = produceState<List<HistoryPoint>?>(
    initialValue = null,
    key1 = entityId,
    key2 = enabled,
) {
    if (!enabled) {
        value = null
        return@produceState
    }
    value = haRepository.fetchHistory(EntityId(entityId), hours).getOrElse { emptyList() }
}

/**
 * Accent colour for [domain], mirroring the card stack's role mapping so the sheet
 * reads as the same surface family the user tapped from. Self-contained copy (the
 * card-stack mapping lives behind private helpers in EntityCard.kt).
 */
fun accentForDomain(domain: Domain, deviceClass: String?): Color = when (domain) {
    Domain.LIGHT, Domain.SWITCH, Domain.INPUT_BOOLEAN, Domain.AUTOMATION,
    Domain.CLIMATE, Domain.BUTTON, Domain.INPUT_BUTTON, Domain.NUMBER,
    Domain.INPUT_NUMBER, Domain.WATER_HEATER, Domain.ALARM_CONTROL_PANEL,
    // Siren: high-attention safety device; warm accent.
    Domain.SIREN,
    -> R1.AccentWarm
    Domain.FAN, Domain.SCENE, Domain.VACUUM, Domain.LAWN_MOWER -> R1.AccentGreen
    Domain.MEDIA_PLAYER, Domain.SCRIPT, Domain.HUMIDIFIER, Domain.VALVE,
    Domain.SELECT, Domain.INPUT_SELECT, Domain.UPDATE, Domain.REMOTE,
    -> R1.AccentCool
    Domain.SENSOR, Domain.BINARY_SENSOR -> accentForDeviceClass(deviceClass)
    // New read-only domains: neutral accent.
    Domain.TEXT, Domain.DATE, Domain.DATETIME, Domain.TIME,
    Domain.IMAGE, Domain.EVENT -> R1.AccentNeutral
    else -> R1.AccentNeutral
}

private fun accentForDeviceClass(deviceClass: String?): Color = when (deviceClass?.lowercase()) {
    "temperature", "humidity", "pressure", "atmospheric_pressure", "water" -> R1.AccentCool
    "power", "energy", "current", "voltage", "gas", "frequency" -> R1.AccentWarm
    "illuminance", "wind_speed", "speed", "battery" -> R1.AccentGreen
    // New device_class accent buckets.
    "data_size", "data_rate" -> R1.AccentCool
    "irradiance" -> R1.AccentWarm
    "sound_pressure" -> R1.AccentNeutral
    "absolute_humidity" -> R1.AccentCool
    else -> R1.AccentNeutral
}

/**
 * Humanise an HA attribute key for display: `current_temperature` -> "Current temperature",
 * `hs_color` -> "Hs color". Acronyms aren't special-cased (HA's own keys are snake_case
 * words), just split on underscores and sentence-case the first word.
 */
fun humanizeKey(key: String): String {
    val words = key.split('_').filter { it.isNotEmpty() }
    if (words.isEmpty()) return key
    return words.joinToString(" ") { it.lowercase() }
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
}

/**
 * Format an arbitrary JSON attribute value for a single-line readout. Lists and objects
 * collapse to a compact comma-joined / `{n fields}` summary so a sprawling `forecast`
 * array doesn't blow the row height; the row ellipsizes whatever this returns.
 *
 * Large bare numbers are run through [groupThousands] so an attribute reading like
 * "1234567" shows as "1,234,567", matching the sensor cards and HA's own frontend. The
 * grouping is precision-preserving: it only touches the integer part of a 5+ digit number
 * and leaves decimals, version strings, IDs with separators, and timestamps untouched.
 */
fun formatAttributeValue(value: kotlinx.serialization.json.JsonElement): String = when (value) {
    is JsonNull -> "—"
    is JsonPrimitive -> groupThousands(value.content).ifBlank { "—" }
    is JsonArray -> {
        if (value.isEmpty()) {
            "[]"
        } else {
            val flat = value.joinToString(", ") { el ->
                when (el) {
                    is JsonPrimitive -> groupThousands(el.content)
                    is JsonArray -> "[${el.size}]"
                    is JsonObject -> "{${el.size}}"
                    else -> el.toString()
                }
            }
            flat
        }
    }
    is JsonObject -> if (value.isEmpty()) "{}" else "{${value.size} fields}"
    else -> value.toString()
}

/**
 * Attribute keys that are noise in the more-info list because they're either rendered
 * elsewhere on the sheet (friendly_name in the header) or are internal HA bookkeeping
 * the user can't act on. Everything else is surfaced verbatim.
 */
val SUPPRESSED_ATTRIBUTE_KEYS = setOf(
    "friendly_name",
    "icon",
    "entity_picture",
)
