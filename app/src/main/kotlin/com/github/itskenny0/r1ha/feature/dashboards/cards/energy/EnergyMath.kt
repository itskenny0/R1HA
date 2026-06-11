package com.github.itskenny0.r1ha.feature.dashboards.cards.energy

import com.github.itskenny0.r1ha.core.ha.EnergyPreferences
import com.github.itskenny0.r1ha.core.ha.EnergySource
import com.github.itskenny0.r1ha.core.ha.StatisticsBucket
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure energy-aggregation math, a faithful port of the consumption model in
 * HA's src/data/energy.ts (`getSummedData`, `computeConsumptionSingle`,
 * `computeConsumptionData`, the gauge calculators, and `formatConsumptionShort`).
 *
 * Everything here is offline-testable: it consumes already-fetched recorder
 * buckets (per-statistic-id `change` series) and energy preferences, and emits
 * the totals the circles view, gauges, sources table, and flow list render.
 *
 * The math is verified against fixtures derived from the HA source; the exact
 * recorder bucket shapes need a live HA energy setup to confirm end to end.
 */

/** Per-statistic-id series of `change` (consumption during the bucket) keyed by
 *  bucket-start instant. We normalise [StatisticsBucket.change] into this map up
 *  front so the math stays in plain Doubles. */
typealias ChangeSeries = Map<Instant, Double>

/** Reduce a list of recorder buckets to its per-instant `change` series,
 *  dropping buckets the recorder left without a finite change. */
fun changeSeriesOf(buckets: List<StatisticsBucket>?): ChangeSeries {
    if (buckets.isNullOrEmpty()) return emptyMap()
    val out = LinkedHashMap<Instant, Double>(buckets.size)
    for (b in buckets) {
        val c = b.change?.takeIf { it.isFinite() } ?: continue
        out[b.start] = (out[b.start] ?: 0.0) + c
    }
    return out
}

/**
 * The summed-by-route data, the R1 mirror of HA's `EnergySumData`. Each route's
 * map is keyed by bucket-start instant; [total] is the summed scalar per route.
 * [timestamps] is the sorted union of every route's instants, the timeline the
 * consumption computation iterates.
 */
data class EnergySumData(
    val fromGrid: Map<Instant, Double> = emptyMap(),
    val toGrid: Map<Instant, Double> = emptyMap(),
    val solar: Map<Instant, Double> = emptyMap(),
    val toBattery: Map<Instant, Double> = emptyMap(),
    val fromBattery: Map<Instant, Double> = emptyMap(),
    val total: EnergyRouteTotals = EnergyRouteTotals(),
    val timestamps: List<Instant> = emptyList(),
) {
    val hasGrid: Boolean get() = fromGrid.isNotEmpty() || toGrid.isNotEmpty()
    val hasSolar: Boolean get() = solar.isNotEmpty()
    val hasBattery: Boolean get() = toBattery.isNotEmpty() || fromBattery.isNotEmpty()
}

data class EnergyRouteTotals(
    val fromGrid: Double = 0.0,
    val toGrid: Double = 0.0,
    val solar: Double = 0.0,
    val toBattery: Double = 0.0,
    val fromBattery: Double = 0.0,
)

/**
 * Sum the per-route change series across every configured source, the port of
 * HA's `getSummedDataPartial`. [statsById] maps every referenced statistic id
 * to its already-fetched bucket list. Sources are grouped by route exactly as
 * HA does: grid `stat_energy_from` -> from_grid, grid `stat_energy_to` ->
 * to_grid, solar `stat_energy_from` -> solar, battery to/from.
 */
fun summedData(
    prefs: EnergyPreferences,
    statsById: Map<String, List<StatisticsBucket>>,
): EnergySumData {
    val fromGridIds = mutableListOf<String>()
    val toGridIds = mutableListOf<String>()
    val solarIds = mutableListOf<String>()
    val toBatteryIds = mutableListOf<String>()
    val fromBatteryIds = mutableListOf<String>()

    for (source in prefs.sources) {
        when (source.type) {
            "solar" -> source.statEnergyFrom?.let { solarIds += it }
            "battery" -> {
                source.statEnergyTo?.let { toBatteryIds += it }
                source.statEnergyFrom?.let { fromBatteryIds += it }
            }
            "grid" -> {
                source.statEnergyFrom?.let { fromGridIds += it }
                source.statEnergyTo?.let { toGridIds += it }
            }
        }
    }

    val timestamps = sortedSetOf<Instant>()
    fun route(ids: List<String>): Pair<Map<Instant, Double>, Double> {
        if (ids.isEmpty()) return emptyMap<Instant, Double>() to 0.0
        val acc = LinkedHashMap<Instant, Double>()
        var sum = 0.0
        for (id in ids) {
            val series = changeSeriesOf(statsById[id])
            for ((t, v) in series) {
                acc[t] = (acc[t] ?: 0.0) + v
                sum += v
                timestamps += t
            }
        }
        return acc to sum
    }

    val (fromGrid, fromGridSum) = route(fromGridIds)
    val (toGrid, toGridSum) = route(toGridIds)
    val (solar, solarSum) = route(solarIds)
    val (toBattery, toBatterySum) = route(toBatteryIds)
    val (fromBattery, fromBatterySum) = route(fromBatteryIds)

    return EnergySumData(
        fromGrid = fromGrid,
        toGrid = toGrid,
        solar = solar,
        toBattery = toBattery,
        fromBattery = fromBattery,
        total = EnergyRouteTotals(
            fromGrid = fromGridSum,
            toGrid = toGridSum,
            solar = solarSum,
            toBattery = toBatterySum,
            fromBattery = fromBatterySum,
        ),
        timestamps = timestamps.toList(),
    )
}

/** The per-route consumption split at one bucket, mirror of HA's
 *  `computeConsumptionSingle` return. */
data class ConsumptionSplit(
    val usedSolar: Double,
    val usedGrid: Double,
    val usedBattery: Double,
    val usedTotal: Double,
    val gridToBattery: Double,
    val batteryToGrid: Double,
    val solarToBattery: Double,
    val solarToGrid: Double,
)

/**
 * Split one bucket's routes into where the energy actually went, an exact port
 * of HA's `computeConsumptionSingle` (the priority-ordered flow allocation).
 * Null inputs read as zero; negatives are clamped to zero (HA's `Math.max`).
 */
fun computeConsumptionSingle(
    fromGrid: Double?,
    toGrid: Double?,
    solar: Double?,
    toBattery: Double?,
    fromBattery: Double?,
): ConsumptionSplit {
    var toGridV = max(toGrid ?: 0.0, 0.0)
    var toBatteryV = max(toBattery ?: 0.0, 0.0)
    var solarV = max(solar ?: 0.0, 0.0)
    var fromGridV = max(fromGrid ?: 0.0, 0.0)
    var fromBatteryV = max(fromBattery ?: 0.0, 0.0)

    val usedTotal = fromGridV + solarV + fromBatteryV - toGridV - toBatteryV

    var gridToBattery = 0.0
    var usedTotalRemaining = max(usedTotal, 0.0)

    // Grid_In -> Battery_In (first pass: excess grid beyond consumption).
    val excessGridIn = max(0.0, min(toBatteryV, fromGridV - usedTotalRemaining))
    gridToBattery += excessGridIn
    toBatteryV -= excessGridIn
    fromGridV -= excessGridIn

    // Solar -> Battery_In.
    val solarToBattery = min(solarV, toBatteryV)
    toBatteryV -= solarToBattery
    solarV -= solarToBattery

    // Solar -> Grid_Out.
    val solarToGrid = min(solarV, toGridV)
    toGridV -= solarToGrid
    solarV -= solarToGrid

    // Battery_Out -> Grid_Out.
    val batteryToGrid = min(fromBatteryV, toGridV)
    fromBatteryV -= batteryToGrid

    // Grid_In -> Battery_In (second pass).
    val gridToBattery2 = min(fromGridV, toBatteryV)
    gridToBattery += gridToBattery2
    fromGridV -= gridToBattery2

    // Solar -> Consumption.
    val usedSolar = min(usedTotalRemaining, solarV)
    usedTotalRemaining -= usedSolar

    // Battery_Out -> Consumption.
    val usedBattery = min(fromBatteryV, usedTotalRemaining)
    usedTotalRemaining -= usedBattery

    // Grid_In -> Consumption.
    val usedGrid = min(usedTotalRemaining, fromGridV)

    return ConsumptionSplit(
        usedSolar = usedSolar,
        usedGrid = usedGrid,
        usedBattery = usedBattery,
        usedTotal = usedTotal,
        gridToBattery = gridToBattery,
        batteryToGrid = batteryToGrid,
        solarToBattery = solarToBattery,
        solarToGrid = solarToGrid,
    )
}

/** Summed consumption split across the whole period, mirror of HA's
 *  `computeConsumptionData` totals. */
data class ConsumptionTotals(
    val usedTotal: Double = 0.0,
    val usedSolar: Double = 0.0,
    val usedGrid: Double = 0.0,
    val usedBattery: Double = 0.0,
    val gridToBattery: Double = 0.0,
    val batteryToGrid: Double = 0.0,
    val solarToBattery: Double = 0.0,
    val solarToGrid: Double = 0.0,
)

/** Sum [computeConsumptionSingle] over every timestamp in [data]. */
fun computeConsumptionData(data: EnergySumData): ConsumptionTotals {
    var t = ConsumptionTotals()
    for (ts in data.timestamps) {
        val s = computeConsumptionSingle(
            fromGrid = data.fromGrid[ts],
            toGrid = data.toGrid[ts],
            solar = data.solar[ts],
            toBattery = data.toBattery[ts],
            fromBattery = data.fromBattery[ts],
        )
        t = ConsumptionTotals(
            usedTotal = t.usedTotal + s.usedTotal,
            usedSolar = t.usedSolar + s.usedSolar,
            usedGrid = t.usedGrid + s.usedGrid,
            usedBattery = t.usedBattery + s.usedBattery,
            gridToBattery = t.gridToBattery + s.gridToBattery,
            batteryToGrid = t.batteryToGrid + s.batteryToGrid,
            solarToBattery = t.solarToBattery + s.solarToBattery,
            solarToGrid = t.solarToGrid + s.solarToGrid,
        )
    }
    return t
}

// ---- Gauges -----------------------------------------------------------------

/**
 * Solar-consumed gauge: percentage of produced solar consumed on-site rather
 * than exported. Port of HA's `calculateSolarConsumedGauge` (no-battery branch;
 * the battery LIFO branch is below). Returns null when there's no solar.
 */
fun solarConsumedGauge(hasBattery: Boolean, data: EnergySumData): Double? {
    if (data.total.solar <= 0.0) return null
    val consumption = computeConsumptionData(data)
    if (!hasBattery) {
        return consumption.usedSolar / data.total.solar * 100.0
    }
    // Battery present: track solar energy through the battery LIFO, exactly as
    // HA does, attributing battery discharge to solar/grid in last-in-first-out
    // order. Energy drained when the stack is empty is from a prior period and
    // ignored.
    var solarConsumed = 0.0
    var solarReturned = 0.0
    val lifo = ArrayDeque<Pair<String, Double>>() // type ("solar"/"grid") to value

    for (ts in data.timestamps) {
        val s = computeConsumptionSingle(
            fromGrid = data.fromGrid[ts],
            toGrid = data.toGrid[ts],
            solar = data.solar[ts],
            toBattery = data.toBattery[ts],
            fromBattery = data.fromBattery[ts],
        )
        solarConsumed += s.usedSolar
        solarReturned += s.solarToGrid
        if (s.gridToBattery > 0.0) lifo.addLast("grid" to s.gridToBattery)
        if (s.solarToBattery > 0.0) lifo.addLast("solar" to s.solarToBattery)

        var batteryToGrid = s.batteryToGrid
        var usedBattery = s.usedBattery

        fun drain(amount: Double): Pair<Double, String> {
            val last = lifo.last()
            return if (amount >= last.second) {
                lifo.removeLast()
                last.second to last.first
            } else {
                lifo[lifo.size - 1] = last.first to (last.second - amount)
                amount to last.first
            }
        }

        while (usedBattery > 0.0 && lifo.isNotEmpty()) {
            val (energy, type) = drain(usedBattery)
            if (type == "solar") solarConsumed += energy
            usedBattery -= energy
        }
        while (batteryToGrid > 0.0 && lifo.isNotEmpty()) {
            val (energy, type) = drain(batteryToGrid)
            if (type == "solar") solarReturned += energy
            batteryToGrid -= energy
        }
    }
    val totalProduction = solarConsumed + solarReturned
    return if (totalProduction > 0.0) solarConsumed / totalProduction * 100.0 else null
}

/**
 * Self-sufficiency gauge: percentage of home consumption NOT drawn from the
 * grid. Port of HA's `hui-energy-self-sufficiency-gauge-card` value formula.
 * Returns null when there's no home consumption to measure.
 */
fun selfSufficiencyGauge(data: EnergySumData): Double? {
    val consumption = computeConsumptionData(data)
    val totalHome = max(0.0, consumption.usedTotal)
    if (totalHome <= 0.0) return null
    val fromGrid = data.total.fromGrid
    return (1.0 - min(1.0, fromGrid / totalHome)) * 100.0
}

/**
 * Grid-neutrality gauge: net grid balance in [-1, 1]. Positive = net exporter
 * (returned more than consumed), negative = net importer. Port of HA's
 * `hui-energy-grid-neutrality-gauge-card`. Returns null when there's no grid.
 */
fun gridNeutralityGauge(data: EnergySumData): Double? {
    if (!data.hasGrid) return null
    val consumed = data.total.fromGrid
    val returned = data.total.toGrid
    return when {
        consumed > returned -> if (consumed == 0.0) 0.0 else (1.0 - returned / consumed) * -1.0
        consumed < returned -> if (returned == 0.0) 0.0 else 1.0 - consumed / returned
        else -> 0.0
    }
}

/**
 * Carbon-consumed (low-carbon) gauge: percentage of consumed energy that was
 * low-carbon. Port of HA's `hui-energy-carbon-consumed-gauge-card` formula.
 * [highCarbonEnergy] is the fossil-derived grid energy (from
 * `energy/fossil_energy_consumption`); when null the gauge is unavailable.
 */
/**
 * Sum the values of an `energy/fossil_energy_consumption` reply
 * (`Record<period -> kWh>`) into the total high-carbon (fossil) grid energy, the
 * `highCarbonEnergy` HA's carbon-consumed gauge derives by
 * `Object.values(...).reduce((s,a)=>s+a, 0)`. An empty/absent map sums to 0.0.
 */
fun sumFossilEnergyConsumption(consumption: Map<String, Double>?): Double =
    consumption?.values?.sum() ?: 0.0

/**
 * Pick the grid-import statistic ids the fossil-consumption call needs (HA's
 * `consumptionStatIDs`): every grid source's `stat_energy_from`. These are the
 * import meters whose fossil fraction the CO2 signal scales.
 */
fun gridConsumptionStatIds(prefs: EnergyPreferences): List<String> =
    prefs.sources
        .filter { it.type == "grid" }
        .mapNotNull { it.statEnergyFrom?.takeIf { id -> id.isNotBlank() } }
        .distinct()

fun carbonConsumedGauge(data: EnergySumData, highCarbonEnergy: Double?): Double? {
    if (highCarbonEnergy == null) return null
    val totalGridConsumption = data.total.fromGrid
    val totalSolarProduction = data.total.solar
    val totalGridReturned = data.total.toGrid
    val totalEnergyConsumed = totalGridConsumption +
        max(0.0, totalSolarProduction - totalGridReturned)
    if (totalEnergyConsumed <= 0.0) return null
    return (1.0 - highCarbonEnergy / totalEnergyConsumed) * 100.0
}

// ---- SI / unit normalisation ------------------------------------------------

/** An SI-normalised value: the scaled magnitude and the unit prefix it lands on. */
data class ScaledEnergy(val value: Double, val unit: String)

private val ENERGY_UNITS = listOf("Wh", "kWh", "MWh", "GWh", "TWh")

/**
 * Normalise an energy [value] in [unit] up or down the Wh..TWh ladder so the
 * displayed magnitude is readable, a port of HA's `formatConsumptionShort`
 * scaling loop. When the unit isn't on the energy ladder the value passes
 * through unchanged. [targetUnit] forces a specific prefix when set.
 */
fun scaleEnergy(value: Double, unit: String, targetUnit: String? = null): ScaledEnergy {
    var idx = ENERGY_UNITS.indexOf(unit)
    if (idx < 0) return ScaledEnergy(value, unit)
    var v = value
    val targetIdx = targetUnit?.let { ENERGY_UNITS.indexOf(it) } ?: -1
    while (
        if (targetIdx > -1) targetIdx < idx else (abs(v) < 1.0 && idx > 0)
    ) {
        v *= 1000.0
        idx--
    }
    while (
        if (targetIdx > -1) targetIdx > idx else (abs(v) >= 1000.0 && idx < ENERGY_UNITS.size - 1)
    ) {
        v /= 1000.0
        idx++
    }
    return ScaledEnergy(v, ENERGY_UNITS[idx])
}

/** Format a scaled energy value the way HA's `formatConsumptionShort` does:
 *  2 decimals under 10, 1 under 100, 0 otherwise. Locale-pinned for the
 *  decimal separator so a comma-locale device doesn't mangle the number. */
fun formatScaledEnergy(scaled: ScaledEnergy, locale: Locale = Locale.US): String {
    val a = abs(scaled.value)
    val digits = when {
        a < 10.0 -> 2
        a < 100.0 -> 1
        else -> 0
    }
    return String.format(locale, "%,.${digits}f %s", scaled.value, scaled.unit)
}

/** Convenience: scale then format a kWh figure, the common case for the cards. */
fun formatEnergyKwh(valueKwh: Double, locale: Locale = Locale.US): String =
    formatScaledEnergy(scaleEnergy(valueKwh, "kWh"), locale)

// ---- Flow list (sankey adaptation) ------------------------------------------

/**
 * One row of the compact flow list, the R1 stand-in for HA's sankey diagram. A
 * full sankey is illegible at 640x480, so every sankey/power-sankey/water-sankey
 * card renders this shared "source -> bar -> sink" list instead: each row names
 * a flow, carries its value, and a [fraction] (0..1 of the largest flow) the
 * renderer turns into a proportional bar width.
 */
data class FlowRow(
    val source: String,
    val sink: String,
    val value: Double,
    /** 0..1 relative to the largest flow in the list, for bar width. */
    val fraction: Double,
)

/**
 * Build the energy flow list from a period's summed + consumption data, the
 * sankey adaptation. Models the same node graph HA's sankey draws (solar/grid/
 * battery sources into a home sink, plus battery charge and grid export sinks)
 * but as a proportional list. Flows below [minValue] are dropped to avoid
 * sub-noise rows. Empty when nothing flowed.
 */
fun energyFlowRows(data: EnergySumData, minValue: Double = 0.01): List<FlowRow> {
    val c = computeConsumptionData(data)
    val raw = buildList {
        add(Triple("Solar", "Home", c.usedSolar))
        add(Triple("Grid", "Home", c.usedGrid))
        add(Triple("Battery", "Home", c.usedBattery))
        add(Triple("Solar", "Battery", c.solarToBattery))
        add(Triple("Grid", "Battery", c.gridToBattery))
        add(Triple("Solar", "Grid", c.solarToGrid))
        add(Triple("Battery", "Grid", c.batteryToGrid))
    }.filter { it.third >= minValue }
    val maxFlow = raw.maxOfOrNull { it.third } ?: return emptyList()
    if (maxFlow <= 0.0) return emptyList()
    return raw.map { (src, sink, v) ->
        FlowRow(source = src, sink = sink, value = v, fraction = (v / maxFlow).coerceIn(0.0, 1.0))
    }
}

// ---- Devices / sources aggregation ------------------------------------------

/** A per-device period total for the devices graph (horizontal bars). */
data class DeviceTotal(val statId: String, val name: String?, val kwh: Double)

/**
 * Sum each configured device's `change` over the period and sort descending,
 * the devices-graph aggregation. Devices with no data sum to zero and sort last.
 */
fun deviceTotals(
    prefs: EnergyPreferences,
    statsById: Map<String, List<StatisticsBucket>>,
): List<DeviceTotal> =
    prefs.deviceConsumption.map { dev ->
        val total = changeSeriesOf(statsById[dev.statConsumption]).values.sum()
        DeviceTotal(dev.statConsumption, dev.name, total)
    }.sortedByDescending { it.kwh }

/** A row of the sources table: a source with its energy total and optional cost. */
data class SourceTableRow(
    val label: String,
    val type: String,
    val energyKwh: Double,
    val cost: Double?,
)

/**
 * Build the sources/cost table rows, the sources-table aggregation. One row per
 * source (grid import, grid export, solar, battery in/out, gas, water), with the
 * cost column filled from an explicit `stat_cost`/`stat_compensation` meter or
 * the `energy/info` auto cost-sensor. Cost is null when no cost stat exists.
 */
fun sourceTableRows(
    prefs: EnergyPreferences,
    statsById: Map<String, List<StatisticsBucket>>,
    costSensors: Map<String, String> = emptyMap(),
): List<SourceTableRow> {
    fun energyOf(id: String?): Double =
        if (id == null) 0.0 else changeSeriesOf(statsById[id]).values.sum()
    fun costOf(energyId: String?, explicitCostId: String?): Double? {
        val costId = explicitCostId ?: energyId?.let { costSensors[it] } ?: return null
        val series = changeSeriesOf(statsById[costId])
        if (series.isEmpty()) return null
        return series.values.sum()
    }

    val rows = mutableListOf<SourceTableRow>()
    for (s in prefs.sources) {
        when (s.type) {
            "grid" -> {
                s.statEnergyFrom?.let {
                    rows += SourceTableRow(
                        label = s.name ?: "Grid import",
                        type = "grid",
                        energyKwh = energyOf(it),
                        cost = costOf(it, s.statCost),
                    )
                }
                s.statEnergyTo?.let {
                    rows += SourceTableRow(
                        label = "Grid export",
                        type = "grid",
                        energyKwh = energyOf(it),
                        cost = costOf(it, s.statCompensation),
                    )
                }
            }
            "solar" -> s.statEnergyFrom?.let {
                rows += SourceTableRow(s.name ?: "Solar", "solar", energyOf(it), null)
            }
            "battery" -> {
                s.statEnergyFrom?.let {
                    rows += SourceTableRow(s.name ?: "Battery out", "battery", energyOf(it), null)
                }
                s.statEnergyTo?.let {
                    rows += SourceTableRow("Battery in", "battery", energyOf(it), null)
                }
            }
            "gas", "water" -> s.statEnergyFrom?.let {
                rows += SourceTableRow(
                    label = s.name ?: s.type.replaceFirstChar(Char::uppercase),
                    type = s.type,
                    energyKwh = energyOf(it),
                    cost = costOf(it, s.statCost),
                )
            }
        }
    }
    return rows
}

/** Every statistic id a set of [prefs] references, the union the cards must
 *  fetch from the recorder, port of HA's `getReferencedStatisticIds` for the
 *  energy + device + cost set. */
fun referencedStatisticIds(
    prefs: EnergyPreferences,
    costSensors: Map<String, String> = emptyMap(),
): List<String> {
    val ids = LinkedHashSet<String>()
    fun addCost(energyId: String?, explicit: String?) {
        explicit?.let { ids += it }
        energyId?.let { costSensors[it]?.let { c -> ids += c } }
    }
    for (s in prefs.sources) {
        when (s.type) {
            "solar" -> s.statEnergyFrom?.let { ids += it }
            "battery" -> {
                s.statEnergyFrom?.let { ids += it }
                s.statEnergyTo?.let { ids += it }
            }
            "gas", "water" -> {
                s.statEnergyFrom?.let { ids += it }
                addCost(s.statEnergyFrom, s.statCost)
            }
            "grid" -> {
                s.statEnergyFrom?.let { ids += it }
                addCost(s.statEnergyFrom, s.statCost)
                s.statEnergyTo?.let { ids += it }
                addCost(s.statEnergyTo, s.statCompensation)
            }
        }
    }
    prefs.deviceConsumption.forEach { ids += it.statConsumption }
    prefs.deviceConsumptionWater.forEach { ids += it.statConsumption }
    return ids.toList()
}

/** Group sources by type, the R1 mirror of HA's `energySourcesByType`. */
fun sourcesByType(prefs: EnergyPreferences): Map<String, List<EnergySource>> =
    prefs.sources.groupBy { it.type }
