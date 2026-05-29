package com.github.itskenny0.r1ha.feature.energy

import com.github.itskenny0.r1ha.core.ha.StatisticId
import com.github.itskenny0.r1ha.core.ha.StatisticsBucket
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Covers the pure history-aggregation logic backing the EnergyScreen
 * consumption chart: which recorder statistic ids count as energy meters,
 * and how their per-bucket `change` columns fold into one consumption
 * series. No coroutines / repository here, just the deterministic maths.
 */
class EnergyHistoryTest {

    private fun id(
        statisticId: String,
        unit: String?,
        hasSum: Boolean,
        hasMean: Boolean = false,
    ) = StatisticId(
        statisticId = statisticId,
        name = statisticId,
        source = "recorder",
        unitOfMeasurement = unit,
        hasMean = hasMean,
        hasSum = hasSum,
    )

    private fun bucket(start: Instant, change: Double?) = StatisticsBucket(
        start = start,
        end = start.plusSeconds(3600),
        mean = null,
        min = null,
        max = null,
        sum = null,
        state = null,
        change = change,
    )

    @Test fun `selectEnergyStatisticIds keeps only summed energy-unit meters`() {
        val rows = listOf(
            id("sensor.grid_import", "kWh", hasSum = true),
            id("sensor.solar_total", "Wh", hasSum = true),
            id("sensor.house_big", "MWh", hasSum = true),
            // Rejected: temperature mean sensor (no sum, non-energy unit).
            id("sensor.kitchen_temp", "°C", hasSum = false, hasMean = true),
            // Rejected: power sensor records a sum but in watts, not energy.
            id("sensor.live_power", "W", hasSum = true),
            // Rejected: water meter sums volume, not energy.
            id("sensor.water_total", "m³", hasSum = true),
            // Rejected: energy unit but recorder tracks no sum.
            id("sensor.broken_energy", "kWh", hasSum = false),
        )
        assertThat(EnergyViewModel.selectEnergyStatisticIds(rows))
            .containsExactly("sensor.grid_import", "sensor.solar_total", "sensor.house_big")
            .inOrder()
    }

    @Test fun `selectEnergyStatisticIds is case insensitive on unit`() {
        val rows = listOf(
            id("sensor.a", "KWH", hasSum = true),
            id("sensor.b", " kwh ", hasSum = true),
        )
        assertThat(EnergyViewModel.selectEnergyStatisticIds(rows))
            .containsExactly("sensor.a", "sensor.b")
    }

    @Test fun `aggregateConsumption sums change across meters per bucket and sorts by start`() {
        val t0 = Instant.parse("2024-01-01T00:00:00Z")
        val t1 = Instant.parse("2024-01-01T01:00:00Z")
        val t2 = Instant.parse("2024-01-01T02:00:00Z")
        val byId = mapOf(
            // Deliberately out of order to prove the sort.
            "sensor.a" to listOf(bucket(t2, 0.5), bucket(t0, 1.0), bucket(t1, 2.0)),
            "sensor.b" to listOf(bucket(t0, 0.25), bucket(t1, 0.75)),
        )
        val bars = EnergyViewModel.aggregateConsumption(byId)
        assertThat(bars.map { it.timestamp }).containsExactly(t0, t1, t2).inOrder()
        assertThat(bars[0].kwh).isEqualTo(1.25) // 1.0 + 0.25
        assertThat(bars[1].kwh).isEqualTo(2.75) // 2.0 + 0.75
        assertThat(bars[2].kwh).isEqualTo(0.5)  // only sensor.a
    }

    @Test fun `aggregateConsumption skips null and non-finite change`() {
        val t0 = Instant.parse("2024-01-01T00:00:00Z")
        val t1 = Instant.parse("2024-01-01T01:00:00Z")
        val byId = mapOf(
            "sensor.a" to listOf(
                bucket(t0, null),                 // first bucket has no prior delta
                bucket(t1, Double.NaN),           // recorder gap
            ),
        )
        // Both buckets drop out, leaving no bars rather than zero-height noise.
        assertThat(EnergyViewModel.aggregateConsumption(byId)).isEmpty()
    }

    @Test fun `aggregateConsumption on empty map yields no bars`() {
        assertThat(EnergyViewModel.aggregateConsumption(emptyMap())).isEmpty()
    }
}
