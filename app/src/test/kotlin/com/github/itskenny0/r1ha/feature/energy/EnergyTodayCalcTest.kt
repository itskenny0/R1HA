package com.github.itskenny0.r1ha.feature.energy

import com.github.itskenny0.r1ha.core.ha.HistoryPoint
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Pure tests for the real-time "energy used today" delta math: the rise in each
 * cumulative meter's reading since local midnight, summed and unit-normalised.
 * No HA, no Compose — just [HistoryPoint] lists and a midnight Instant.
 */
class EnergyTodayCalcTest {

    private val midnight = Instant.parse("2026-06-17T00:00:00Z")

    /** A reading [state] at [minutesFromMidnight] minutes after local midnight
     *  (negative = before midnight). */
    private fun p(state: String, minutesFromMidnight: Long) =
        HistoryPoint.fromRaw(state, midnight.plusSeconds(minutesFromMidnight * 60))

    @Test fun `consumption is the rise from the pre-midnight baseline`() {
        // Meter reads 100.0 just before midnight, climbs to 102.5 during the day.
        val points = listOf(
            p("100.0", -30),
            p("100.5", 60),
            p("101.0", 120),
            p("102.5", 600),
        )
        assertThat(EnergyTodayCalc.sensorConsumptionSinceMidnight(points, midnight))
            .isWithin(1e-9).of(2.5)
    }

    @Test fun `a meter reset contributes only its positive increments`() {
        // 100 -> 103 (today's +3), resets to 0, then 0 -> 2 (+2). Total 5, the
        // reset drop counts as 0 (matches HA's `change` for total_increasing).
        val points = listOf(
            p("100.0", -10),
            p("103.0", 100),
            p("0.0", 200),
            p("2.0", 300),
        )
        assertThat(EnergyTodayCalc.sensorConsumptionSinceMidnight(points, midnight))
            .isWithin(1e-9).of(5.0)
    }

    @Test fun `with no pre-midnight sample the first today reading seeds the baseline`() {
        val points = listOf(p("50.0", 30), p("51.2", 300))
        assertThat(EnergyTodayCalc.sensorConsumptionSinceMidnight(points, midnight))
            .isWithin(1e-9).of(1.2)
    }

    @Test fun `a flat baseline with no change today is zero`() {
        assertThat(EnergyTodayCalc.sensorConsumptionSinceMidnight(listOf(p("100.0", -30)), midnight))
            .isEqualTo(0.0)
    }

    @Test fun `non-numeric samples and empty history are zero`() {
        assertThat(EnergyTodayCalc.sensorConsumptionSinceMidnight(emptyList(), midnight)).isEqualTo(0.0)
        val junk = listOf(p("unavailable", -10), p("unknown", 60), p("none", 120))
        assertThat(EnergyTodayCalc.sensorConsumptionSinceMidnight(junk, midnight)).isEqualTo(0.0)
    }

    @Test fun `points need not be pre-sorted`() {
        // Same data as the baseline test, shuffled — the function sorts internally.
        val points = listOf(
            p("102.5", 600),
            p("100.0", -30),
            p("101.0", 120),
            p("100.5", 60),
        )
        assertThat(EnergyTodayCalc.sensorConsumptionSinceMidnight(points, midnight))
            .isWithin(1e-9).of(2.5)
    }

    @Test fun `todayKwh sums every meter and normalises units to kWh`() {
        val whMeter = listOf(p("1000.0", -5), p("3500.0", 120)) // +2500 Wh = 2.5 kWh
        val kwhMeter = listOf(p("10.0", -5), p("11.0", 120)) // +1.0 kWh
        val byId = mapOf("sensor.a" to whMeter, "sensor.b" to kwhMeter)
        val units = mapOf("sensor.a" to "Wh", "sensor.b" to "kWh")
        val total = EnergyTodayCalc.todayKwh(byId, units, midnight) { u ->
            when (u?.trim()?.lowercase()) {
                "wh" -> 0.001
                "mwh" -> 1_000.0
                "gwh" -> 1_000_000.0
                else -> 1.0
            }
        }
        assertThat(total).isNotNull()
        assertThat(total!!).isWithin(1e-9).of(3.5)
    }

    @Test fun `todayKwh is null when every meter history is empty`() {
        val byId = mapOf("sensor.a" to emptyList<HistoryPoint>(), "sensor.b" to emptyList())
        assertThat(EnergyTodayCalc.todayKwh(byId, emptyMap(), midnight) { 1.0 }).isNull()
    }

    @Test fun `todayKwh is zero (not null) when meters are present but idle`() {
        // One meter with a flat baseline (no consumption) — present data, zero use.
        val byId = mapOf("sensor.a" to listOf(p("100.0", -30)))
        assertThat(EnergyTodayCalc.todayKwh(byId, mapOf("sensor.a" to "kWh"), midnight) { 1.0 })
            .isEqualTo(0.0)
    }
}
