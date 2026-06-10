package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.ui.components.attrString
import com.github.itskenny0.r1ha.ui.components.formatFixed

/**
 * Pure aggregation + alert logic for the area card, mirroring HA's
 * hui-area-card. Split out so the median/sum maths, the climate/humidifier
 * attribute fallbacks, the device dedupe, and the alert-class matching are
 * unit-tested without a Compose or registry harness.
 */

/** device_class names HA aggregates by SUM rather than median (power totals). */
private val SUM_CLASSES = setOf("power", "energy")

/**
 * The area card's default alert binary_sensor classes (HA's `DEFAULT_ASPECT_RATIO`
 * sibling, `DEVICE_CLASSES.binary_sensor` alert subset) plus the common safety
 * ones. Kept lower-case for a direct compare against device_class.
 */
val AREA_DEFAULT_ALERT_CLASSES = setOf(
    "motion", "moisture", "smoke", "gas", "safety", "tamper",
    "co", "problem", "door", "window",
)

/** The area card's default summary sensor classes when none are configured. */
val AREA_DEFAULT_SENSOR_CLASSES = listOf("temperature", "humidity")

/**
 * One contributing reading for a sensor class: its numeric value, its display
 * unit, and the device it came from (for dedupe). HA aggregates one reading per
 * device so a multi-sensor device (a climate exposing both its own temperature
 * sensor and a current_temperature attribute) doesn't double-count.
 */
private data class ClassReading(val value: Double, val unit: String?, val deviceKey: String)

/**
 * Collect the readings for one device-class across an area's member states.
 *
 * Sources, in HA's order:
 *  - `sensor.*` entities of the matching device_class with a numeric state.
 *  - For "temperature": a climate / water_heater `current_temperature` attribute.
 *  - For "humidity": a climate / humidifier `current_humidity` attribute.
 *
 * Readings are deduped per device so the same physical device contributes once.
 * [deviceKeyOf] maps an entity to its owning device id (or the entity id when
 * the registry didn't supply one).
 */
private fun classReadings(
    deviceClass: String,
    states: List<EntityState>,
    deviceKeyOf: (EntityState) -> String,
): List<ClassReading> {
    val out = ArrayList<ClassReading>()
    val seenDevices = HashSet<String>()
    fun add(value: Double, unit: String?, key: String) {
        if (seenDevices.add(key)) out.add(ClassReading(value, unit, key))
    }
    for (s in states) {
        if (!s.isAvailable) continue
        when {
            s.id.domain == Domain.SENSOR && s.deviceClass?.lowercase() == deviceClass -> {
                val v = s.rawState?.trim()?.toDoubleOrNull() ?: continue
                add(v, s.unit, deviceKeyOf(s))
            }
            deviceClass == "temperature" &&
                (s.id.domain == Domain.CLIMATE || s.id.domain == Domain.WATER_HEATER) -> {
                val v = s.attrString("current_temperature")?.trim()?.toDoubleOrNull() ?: continue
                add(v, s.unit, deviceKeyOf(s))
            }
            deviceClass == "humidity" &&
                (s.id.domain == Domain.CLIMATE || s.id.domain == Domain.HUMIDIFIER) -> {
                val v = s.attrString("current_humidity")?.trim()?.toDoubleOrNull() ?: continue
                // current_humidity carries no unit attribute; HA's humidity
                // device class always reads in percent.
                add(v, "%", deviceKeyOf(s))
            }
        }
    }
    return out
}

private fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val s = values.sorted()
    val mid = s.size / 2
    return if (s.size % 2 == 0) (s[mid - 1] + s[mid]) / 2.0 else s[mid]
}

private fun formatReadingNumber(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else formatFixed(d, 1)

/**
 * The area card's secondary summary line. For each configured device class (or
 * HA's [temperature, humidity] default) it aggregates the area's readings: SUM
 * for power/energy, median otherwise. The default class unit is taken from the
 * first contributing reading. Returns null when nothing readable contributes.
 *
 * [preferredEntity] maps a class to the registry's `temperature_entity_id` /
 * `humidity_entity_id` so a configured representative sensor wins over the
 * median when present.
 */
fun areaSensorSummary(
    sensorClasses: List<String>,
    states: List<EntityState>,
    deviceKeyOf: (EntityState) -> String = { it.id.value },
    preferredEntity: (String) -> String? = { null },
): String? {
    val classes = sensorClasses.ifEmpty { AREA_DEFAULT_SENSOR_CLASSES }
    val parts = classes.mapNotNull { dc ->
        val cls = dc.lowercase()
        // A registry-preferred entity short-circuits to that single sensor.
        val preferred = preferredEntity(cls)?.let { pid ->
            states.firstOrNull { it.id.value == pid && it.isAvailable }
                ?.rawState?.trim()?.toDoubleOrNull()?.let { v -> v to states.first { it.id.value == pid }.unit }
        }
        val (value, unit) = if (preferred != null) {
            preferred
        } else {
            val readings = classReadings(cls, states, deviceKeyOf)
            if (readings.isEmpty()) return@mapNotNull null
            val agg = if (cls in SUM_CLASSES) {
                readings.sumOf { it.value }
            } else {
                median(readings.map { it.value }) ?: return@mapNotNull null
            }
            agg to readings.firstNotNullOfOrNull { it.unit }
        }
        val num = formatReadingNumber(value)
        if (unit.isNullOrBlank()) num else "$num$unit"
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * Whether [state] is an active alert for the area card: an `on` binary_sensor
 * whose device_class is one of [alertClasses] (or the default alert set when
 * none are configured).
 */
fun isAreaActiveAlert(state: EntityState, alertClasses: Set<String>): Boolean {
    if (state.id.domain != Domain.BINARY_SENSOR) return false
    if (!state.isAvailable || !state.isOn) return false
    val classes = alertClasses.ifEmpty { AREA_DEFAULT_ALERT_CLASSES }
    return state.deviceClass?.lowercase() in classes
}

/**
 * The distinct alert device classes currently active in the area, in a stable
 * order, so the card can show one chip per active class (HA's alert badges).
 */
fun areaActiveAlertClasses(states: List<EntityState>, alertClasses: Set<String>): List<String> =
    states.filter { isAreaActiveAlert(it, alertClasses) }
        .mapNotNull { it.deviceClass?.lowercase() }
        .distinct()
        .sorted()
