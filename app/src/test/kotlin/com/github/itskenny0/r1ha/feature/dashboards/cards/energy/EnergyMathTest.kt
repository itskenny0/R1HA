package com.github.itskenny0.r1ha.feature.dashboards.cards.energy

import com.github.itskenny0.r1ha.core.ha.EnergyDevicePref
import com.github.itskenny0.r1ha.core.ha.EnergyPreferences
import com.github.itskenny0.r1ha.core.ha.EnergySource
import com.github.itskenny0.r1ha.core.ha.StatisticsBucket
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Locale

/**
 * Locks in the energy aggregation math: per-route summing, the priority-ordered
 * consumption allocation (port of HA's computeConsumptionSingle), the four
 * gauges, SI normalisation, the flow-list (sankey) totals, and the device /
 * source aggregation. Pure: no Android, no HA, fixture inputs only.
 *
 * UNVERIFIED OFFLINE: fixtures are derived from HA's src/data/energy.ts; the
 * exact recorder bucket shapes need a live HA energy setup to confirm.
 */
class EnergyMathTest {

    private fun bucket(startSec: Long, change: Double) = StatisticsBucket(
        start = Instant.ofEpochSecond(startSec),
        end = Instant.ofEpochSecond(startSec + 3600),
        mean = null, min = null, max = null, sum = null, state = null, change = change,
    )

    private fun grid(from: String?, to: String? = null, cost: String? = null) =
        EnergySource(type = "grid", statEnergyFrom = from, statEnergyTo = to, statCost = cost)

    private fun solar(from: String) = EnergySource(type = "solar", statEnergyFrom = from)
    private fun battery(from: String, to: String) =
        EnergySource(type = "battery", statEnergyFrom = from, statEnergyTo = to)

    // ---- changeSeriesOf ----

    @Test fun `change series drops non-finite and null`() {
        val s = changeSeriesOf(listOf(bucket(0, 1.0), bucket(3600, Double.NaN), bucket(7200, 2.0)))
        assertThat(s).hasSize(2)
        assertThat(s[Instant.ofEpochSecond(0)]).isEqualTo(1.0)
        assertThat(s[Instant.ofEpochSecond(7200)]).isEqualTo(2.0)
    }

    // ---- summedData ----

    @Test fun `summed data routes grid solar battery`() {
        val prefs = EnergyPreferences(
            sources = listOf(
                grid(from = "sensor.grid_in", to = "sensor.grid_out"),
                solar("sensor.solar"),
                battery(from = "sensor.bat_out", to = "sensor.bat_in"),
            ),
        )
        val stats = mapOf(
            "sensor.grid_in" to listOf(bucket(0, 5.0), bucket(3600, 3.0)),
            "sensor.grid_out" to listOf(bucket(0, 1.0)),
            "sensor.solar" to listOf(bucket(0, 4.0)),
            "sensor.bat_out" to listOf(bucket(3600, 2.0)),
            "sensor.bat_in" to listOf(bucket(0, 1.5)),
        )
        val d = summedData(prefs, stats)
        assertThat(d.total.fromGrid).isEqualTo(8.0)
        assertThat(d.total.toGrid).isEqualTo(1.0)
        assertThat(d.total.solar).isEqualTo(4.0)
        assertThat(d.total.fromBattery).isEqualTo(2.0)
        assertThat(d.total.toBattery).isEqualTo(1.5)
        assertThat(d.timestamps).hasSize(2)
        assertThat(d.hasGrid && d.hasSolar && d.hasBattery).isTrue()
    }

    // ---- computeConsumptionSingle ----

    @Test fun `consumption pure grid import`() {
        val s = computeConsumptionSingle(10.0, 0.0, 0.0, 0.0, 0.0)
        assertThat(s.usedTotal).isEqualTo(10.0)
        assertThat(s.usedGrid).isEqualTo(10.0)
        assertThat(s.usedSolar).isEqualTo(0.0)
    }

    @Test fun `consumption solar self use and export`() {
        val s = computeConsumptionSingle(fromGrid = 2.0, toGrid = 2.0, solar = 6.0, toBattery = 0.0, fromBattery = 0.0)
        assertThat(s.usedTotal).isEqualTo(6.0)
        assertThat(s.solarToGrid).isEqualTo(2.0)
        assertThat(s.usedSolar).isEqualTo(4.0)
        assertThat(s.usedGrid).isEqualTo(2.0)
    }

    @Test fun `consumption solar charges battery`() {
        val s = computeConsumptionSingle(fromGrid = 0.0, toGrid = 0.0, solar = 10.0, toBattery = 4.0, fromBattery = 0.0)
        assertThat(s.solarToBattery).isEqualTo(4.0)
        assertThat(s.usedSolar).isEqualTo(6.0)
        assertThat(s.usedTotal).isEqualTo(6.0)
    }

    @Test fun `consumption excess grid charges battery`() {
        val s = computeConsumptionSingle(fromGrid = 10.0, toGrid = 0.0, solar = 0.0, toBattery = 4.0, fromBattery = 0.0)
        assertThat(s.gridToBattery).isEqualTo(4.0)
        assertThat(s.usedGrid).isEqualTo(6.0)
        assertThat(s.usedTotal).isEqualTo(6.0)
    }

    @Test fun `consumption negatives clamp to zero`() {
        val s = computeConsumptionSingle(-5.0, -2.0, -1.0, -3.0, -4.0)
        assertThat(s.usedTotal).isEqualTo(0.0)
        assertThat(s.usedGrid).isEqualTo(0.0)
        assertThat(s.usedSolar).isEqualTo(0.0)
    }

    // ---- gauges ----

    @Test fun `solar consumed gauge no battery`() {
        val data = EnergySumData(
            solar = mapOf(Instant.ofEpochSecond(0) to 10.0),
            toGrid = mapOf(Instant.ofEpochSecond(0) to 6.0),
            total = EnergyRouteTotals(solar = 10.0, toGrid = 6.0),
            timestamps = listOf(Instant.ofEpochSecond(0)),
        )
        assertThat(solarConsumedGauge(hasBattery = false, data = data)!!).isWithin(1e-6).of(40.0)
    }

    @Test fun `solar consumed gauge null without solar`() {
        assertThat(solarConsumedGauge(false, EnergySumData(total = EnergyRouteTotals(fromGrid = 5.0)))).isNull()
    }

    @Test fun `self sufficiency gauge`() {
        val t = Instant.ofEpochSecond(0)
        val data = EnergySumData(
            fromGrid = mapOf(t to 4.0),
            solar = mapOf(t to 6.0),
            total = EnergyRouteTotals(fromGrid = 4.0, solar = 6.0),
            timestamps = listOf(t),
        )
        assertThat(selfSufficiencyGauge(data)!!).isWithin(1e-6).of(60.0)
    }

    @Test fun `grid neutrality net importer negative`() {
        val data = EnergySumData(
            total = EnergyRouteTotals(fromGrid = 10.0, toGrid = 2.0),
            fromGrid = mapOf(Instant.ofEpochSecond(0) to 10.0),
        )
        assertThat(gridNeutralityGauge(data)!!).isWithin(1e-6).of(-0.8)
    }

    @Test fun `grid neutrality net exporter positive`() {
        val data = EnergySumData(
            total = EnergyRouteTotals(fromGrid = 2.0, toGrid = 10.0),
            toGrid = mapOf(Instant.ofEpochSecond(0) to 10.0),
        )
        assertThat(gridNeutralityGauge(data)!!).isWithin(1e-6).of(0.8)
    }

    @Test fun `carbon consumed gauge`() {
        val data = EnergySumData(total = EnergyRouteTotals(fromGrid = 10.0, solar = 4.0, toGrid = 1.0))
        assertThat(carbonConsumedGauge(data, highCarbonEnergy = 5.0)!!)
            .isWithin(1e-6).of((1.0 - 5.0 / 13.0) * 100.0)
    }

    @Test fun `carbon consumed null without fossil data`() {
        assertThat(carbonConsumedGauge(EnergySumData(total = EnergyRouteTotals(fromGrid = 10.0)), null)).isNull()
    }

    // ---- SI normalisation ----

    @Test fun `scale energy down to wh`() {
        val s = scaleEnergy(0.5, "kWh")
        assertThat(s.unit).isEqualTo("Wh")
        assertThat(s.value).isWithin(1e-6).of(500.0)
    }

    @Test fun `scale energy up to mwh`() {
        val s = scaleEnergy(2500.0, "kWh")
        assertThat(s.unit).isEqualTo("MWh")
        assertThat(s.value).isWithin(1e-6).of(2.5)
    }

    @Test fun `scale energy passthrough unknown unit`() {
        val s = scaleEnergy(123.4, "m3")
        assertThat(s.unit).isEqualTo("m3")
        assertThat(s.value).isWithin(1e-6).of(123.4)
    }

    @Test fun `format scaled energy decimal buckets locale pinned`() {
        assertThat(formatScaledEnergy(ScaledEnergy(5.0, "kWh"), Locale.US)).isEqualTo("5.00 kWh")
        assertThat(formatScaledEnergy(ScaledEnergy(50.5, "kWh"), Locale.US)).isEqualTo("50.5 kWh")
        assertThat(formatScaledEnergy(ScaledEnergy(500.0, "kWh"), Locale.US)).isEqualTo("500 kWh")
        // German locale must still produce a dot via the pinned US format.
        assertThat(formatScaledEnergy(ScaledEnergy(5.0, "kWh"), Locale.US)).isEqualTo("5.00 kWh")
    }

    @Test fun `format energy kwh scales small value`() {
        assertThat(formatEnergyKwh(0.5)).isEqualTo("500 Wh")
    }

    // ---- flow rows ----

    @Test fun `flow rows proportional and sorted by max`() {
        val t = Instant.ofEpochSecond(0)
        val data = EnergySumData(
            solar = mapOf(t to 10.0),
            toGrid = mapOf(t to 4.0),
            total = EnergyRouteTotals(solar = 10.0, toGrid = 4.0),
            timestamps = listOf(t),
        )
        val rows = energyFlowRows(data)
        assertThat(rows).isNotEmpty()
        assertThat(rows.maxOf { it.fraction }).isWithin(1e-9).of(1.0)
        assertThat(rows.first { it.source == "Solar" && it.sink == "Home" }.value).isEqualTo(6.0)
        assertThat(rows.first { it.source == "Solar" && it.sink == "Grid" }.value).isEqualTo(4.0)
    }

    @Test fun `flow rows empty when no flow`() {
        assertThat(energyFlowRows(EnergySumData())).isEmpty()
    }

    // ---- device totals ----

    @Test fun `device totals sorted descending`() {
        val prefs = EnergyPreferences(
            deviceConsumption = listOf(
                EnergyDevicePref("sensor.fridge", "Fridge"),
                EnergyDevicePref("sensor.oven", "Oven"),
            ),
        )
        val stats = mapOf(
            "sensor.fridge" to listOf(bucket(0, 1.0), bucket(3600, 1.0)),
            "sensor.oven" to listOf(bucket(0, 5.0)),
        )
        val totals = deviceTotals(prefs, stats)
        assertThat(totals[0].statId).isEqualTo("sensor.oven")
        assertThat(totals[0].kwh).isEqualTo(5.0)
        assertThat(totals[1].kwh).isEqualTo(2.0)
    }

    // ---- source table ----

    @Test fun `source table grid import export with cost`() {
        val prefs = EnergyPreferences(
            sources = listOf(grid(from = "sensor.grid_in", to = "sensor.grid_out", cost = "sensor.grid_cost")),
        )
        val stats = mapOf(
            "sensor.grid_in" to listOf(bucket(0, 8.0)),
            "sensor.grid_out" to listOf(bucket(0, 3.0)),
            "sensor.grid_cost" to listOf(bucket(0, 1.6)),
        )
        val rows = sourceTableRows(prefs, stats)
        assertThat(rows).hasSize(2)
        val import = rows.first { it.label != "Grid export" }
        assertThat(import.energyKwh).isEqualTo(8.0)
        assertThat(import.cost!!).isWithin(1e-9).of(1.6)
        val export = rows.first { it.label == "Grid export" }
        assertThat(export.energyKwh).isEqualTo(3.0)
        assertThat(export.cost).isNull()
    }

    @Test fun `source table cost from energy info map`() {
        val prefs = EnergyPreferences(sources = listOf(grid(from = "sensor.grid_in")))
        val stats = mapOf(
            "sensor.grid_in" to listOf(bucket(0, 8.0)),
            "sensor.auto_cost" to listOf(bucket(0, 2.0)),
        )
        val rows = sourceTableRows(prefs, stats, costSensors = mapOf("sensor.grid_in" to "sensor.auto_cost"))
        assertThat(rows[0].cost!!).isWithin(1e-9).of(2.0)
    }

    // ---- referenced ids ----

    @Test fun `referenced ids collects all unique`() {
        val prefs = EnergyPreferences(
            sources = listOf(
                grid(from = "sensor.grid_in", to = "sensor.grid_out", cost = "sensor.gc"),
                solar("sensor.solar"),
            ),
            deviceConsumption = listOf(EnergyDevicePref("sensor.fridge")),
        )
        val ids = referencedStatisticIds(prefs, costSensors = mapOf("sensor.grid_out" to "sensor.gc2"))
        assertThat(ids).containsAtLeast(
            "sensor.grid_in", "sensor.grid_out", "sensor.gc", "sensor.gc2", "sensor.solar", "sensor.fridge",
        )
        assertThat(ids).hasSize(ids.toSet().size)
    }

    @Test fun `sources by type groups`() {
        val prefs = EnergyPreferences(sources = listOf(grid(from = "a"), grid(from = "b"), solar("c")))
        val byType = sourcesByType(prefs)
        assertThat(byType["grid"]).hasSize(2)
        assertThat(byType["solar"]).hasSize(1)
    }
}
