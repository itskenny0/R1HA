package com.github.itskenny0.r1ha.core.ha

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The Home Assistant Energy dashboard preferences, mirroring the shape of
 * `energy/get_prefs` (see HA frontend src/data/energy.ts `EnergyPreferences`).
 *
 * Only the fields the R1 energy cards consume are decoded; the wire format
 * carries more (cost-adjustment knobs, power-config sensors) that the compact
 * R1 surfaces do not render. Every list defaults to empty so a partial or
 * absent payload degrades to "no sources" rather than throwing.
 *
 * UNVERIFIED OFFLINE: the field names follow HA's documented websocket API but
 * have not been exercised against a live HA energy setup. The parser is built
 * faithfully to the documented shape; verify on-device.
 */
data class EnergyPreferences(
    val sources: List<EnergySource> = emptyList(),
    /** Per-device electricity consumption meters (Energy panel "Individual
     *  devices"). Each carries an ever-increasing kWh statistic id. */
    val deviceConsumption: List<EnergyDevicePref> = emptyList(),
    /** Per-device water consumption meters (the water analogue). */
    val deviceConsumptionWater: List<EnergyDevicePref> = emptyList(),
)

/** One energy source row. The [type] discriminates which stat fields are set. */
data class EnergySource(
    /** grid / solar / battery / gas / water. */
    val type: String,
    /** Import / production meter (consumption-from for grid, production for
     *  solar/gas/water, discharge for battery). HA's `stat_energy_from`. */
    val statEnergyFrom: String? = null,
    /** Export / charge meter. HA's `stat_energy_to` (grid return, battery
     *  charge). Null for one-directional sources. */
    val statEnergyTo: String? = null,
    /** Import cost statistic id (`stat_cost`). */
    val statCost: String? = null,
    /** Export compensation statistic id (`stat_compensation`). */
    val statCompensation: String? = null,
    /** Display name override (`name`). */
    val name: String? = null,
    /** Unit override carried by gas / water sources (`unit_of_measurement`). */
    val unitOfMeasurement: String? = null,
)

/** One per-device consumption meter. */
data class EnergyDevicePref(
    /** Ever-increasing consumption statistic id (`stat_consumption`). */
    val statConsumption: String,
    /** Optional friendly name (`name`). */
    val name: String? = null,
    /** Parent statistic this device is a sub-meter of (`included_in_stat`);
     *  HA shows such devices as sub-tracked. Decoded but advisory. */
    val includedInStat: String? = null,
)

/**
 * `energy/info` reply: a map of energy statistic id to the cost statistic id HA
 * synthesises for it. The cost-sensor map lets the sources-table fill a cost
 * column for sources that only configured a price, not an explicit cost meter.
 */
data class EnergyInfo(
    /** energy stat id -> auto-generated cost stat id. */
    val costSensors: Map<String, String> = emptyMap(),
)

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.energyContentOrNull()?.takeIf { it.isNotBlank() }

private fun JsonPrimitive.energyContentOrNull(): String? =
    runCatching { content }.getOrNull()?.takeIf { it != "null" }

/**
 * Pure parser for the `energy/get_prefs` payload into an [EnergyPreferences].
 * Tolerant: a null / wrong-typed payload yields empty preferences; malformed
 * source or device rows are skipped individually so one bad row doesn't drop
 * the whole config.
 */
fun parseEnergyPreferences(payload: JsonElement?): EnergyPreferences {
    val obj = payload as? JsonObject ?: return EnergyPreferences()
    val sources = (obj["energy_sources"] as? JsonArray).orEmptyArray().mapNotNull { el ->
        val row = el as? JsonObject ?: return@mapNotNull null
        val type = row.str("type") ?: return@mapNotNull null
        EnergySource(
            type = type,
            statEnergyFrom = row.str("stat_energy_from"),
            statEnergyTo = row.str("stat_energy_to"),
            statCost = row.str("stat_cost"),
            statCompensation = row.str("stat_compensation"),
            name = row.str("name"),
            unitOfMeasurement = row.str("unit_of_measurement"),
        )
    }
    return EnergyPreferences(
        sources = sources,
        deviceConsumption = parseDeviceList(obj["device_consumption"]),
        deviceConsumptionWater = parseDeviceList(obj["device_consumption_water"]),
    )
}

/** Pure parser for the `energy/info` payload's `cost_sensors` map. */
fun parseEnergyInfo(payload: JsonElement?): EnergyInfo {
    val obj = payload as? JsonObject ?: return EnergyInfo()
    val costs = (obj["cost_sensors"] as? JsonObject)?.entries
        ?.mapNotNull { (k, v) ->
            val id = (v as? JsonPrimitive)?.energyContentOrNull()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            k to id
        }?.toMap()
        ?: emptyMap()
    return EnergyInfo(costSensors = costs)
}

private fun parseDeviceList(el: JsonElement?): List<EnergyDevicePref> =
    (el as? JsonArray).orEmptyArray().mapNotNull { item ->
        val row = item as? JsonObject ?: return@mapNotNull null
        val stat = row.str("stat_consumption") ?: return@mapNotNull null
        EnergyDevicePref(
            statConsumption = stat,
            name = row.str("name"),
            includedInStat = row.str("included_in_stat"),
        )
    }

private fun JsonArray?.orEmptyArray(): JsonArray = this ?: JsonArray(emptyList())
