package com.github.itskenny0.r1ha.feature.energy

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Covers the pure [energyCsv] helper: CSV structure, header rows, number
 * formatting, RFC-4180 field escaping, and graceful empty states. No Android
 * APIs, no coroutines - just the deterministic string builder.
 */
class EnergyCsvTest {

    private val fixedNow = Instant.parse("2024-06-01T12:00:00Z")

    private val consumer = EnergyViewModel.Consumer(
        entityId = "sensor.fridge_power",
        name = "Fridge",
        watts = 120.5,
    )

    private val bar = EnergyViewModel.HistoryBar(
        timestamp = Instant.parse("2024-06-01T10:00:00Z"),
        kwh = 0.5,
    )

    // ---- header & section markers --------------------------------------------

    @Test fun `output starts with a comment line`() {
        val csv = energyCsv(null, null, null, emptyList(), emptyList(), fixedNow)
        assertThat(csv.lines().first()).startsWith("# R1HA energy export")
    }

    @Test fun `summary section header present`() {
        val csv = energyCsv(null, null, null, emptyList(), emptyList(), fixedNow)
        assertThat(csv).contains("section,summary")
        assertThat(csv).contains("field,value")
    }

    @Test fun `consumers section header present`() {
        val csv = energyCsv(null, null, null, emptyList(), emptyList(), fixedNow)
        assertThat(csv).contains("section,top_consumers")
        assertThat(csv).contains("entity_id,name,watts")
    }

    @Test fun `history section header present`() {
        val csv = energyCsv(null, null, null, emptyList(), emptyList(), fixedNow)
        assertThat(csv).contains("section,history")
        assertThat(csv).contains("timestamp_utc,kwh")
    }

    // ---- summary rows --------------------------------------------------------

    @Test fun `summary row values use dot-decimal Locale US`() {
        val csv = energyCsv(1234.5, 678.9, 12.34, emptyList(), emptyList(), fixedNow)
        assertThat(csv).contains("draw_w,1234.5000")
        assertThat(csv).contains("production_w,678.9000")
        assertThat(csv).contains("today_kwh,12.3400")
    }

    @Test fun `null summary values produce empty fields not n-a or dashes`() {
        val csv = energyCsv(null, null, null, emptyList(), emptyList(), fixedNow)
        assertThat(csv).contains("draw_w,")
        // the field exists but the value part after the comma is empty
        val drawLine = csv.lines().first { it.startsWith("draw_w,") }
        assertThat(drawLine).isEqualTo("draw_w,")
    }

    // ---- consumer rows -------------------------------------------------------

    @Test fun `consumer row has entity_id name and watts`() {
        val csv = energyCsv(null, null, null, listOf(consumer), emptyList(), fixedNow)
        assertThat(csv).contains("sensor.fridge_power,Fridge,120.5000")
    }

    @Test fun `multiple consumers appear in order`() {
        val c2 = EnergyViewModel.Consumer("sensor.tv_power", "TV", 80.0)
        val csv = energyCsv(null, null, null, listOf(consumer, c2), emptyList(), fixedNow)
        val lines = csv.lines()
        val fridgeIdx = lines.indexOfFirst { it.startsWith("sensor.fridge_power") }
        val tvIdx = lines.indexOfFirst { it.startsWith("sensor.tv_power") }
        assertThat(fridgeIdx).isLessThan(tvIdx)
    }

    // ---- history rows --------------------------------------------------------

    @Test fun `history row has ISO-8601 UTC timestamp and kwh`() {
        val csv = energyCsv(null, null, null, emptyList(), listOf(bar), fixedNow)
        assertThat(csv).contains("2024-06-01T10:00:00Z,0.5000")
    }

    @Test fun `history bars appear in the order supplied`() {
        val bar2 = EnergyViewModel.HistoryBar(Instant.parse("2024-06-01T11:00:00Z"), 1.0)
        val csv = energyCsv(null, null, null, emptyList(), listOf(bar, bar2), fixedNow)
        val lines = csv.lines()
        val idx1 = lines.indexOfFirst { it.startsWith("2024-06-01T10:00:00Z") }
        val idx2 = lines.indexOfFirst { it.startsWith("2024-06-01T11:00:00Z") }
        assertThat(idx1).isLessThan(idx2)
    }

    // ---- CSV escaping (RFC 4180) ---------------------------------------------

    @Test fun `consumer name with comma is double-quoted`() {
        val c = EnergyViewModel.Consumer("sensor.x", "Washer, Dryer", 500.0)
        val csv = energyCsv(null, null, null, listOf(c), emptyList(), fixedNow)
        assertThat(csv).contains("\"Washer, Dryer\"")
    }

    @Test fun `consumer name with double-quote is escaped`() {
        val c = EnergyViewModel.Consumer("sensor.y", "My \"Smart\" Plug", 30.0)
        val csv = energyCsv(null, null, null, listOf(c), emptyList(), fixedNow)
        assertThat(csv).contains("\"My \"\"Smart\"\" Plug\"")
    }

    @Test fun `entity_id with no special chars is unquoted`() {
        val c = EnergyViewModel.Consumer("sensor.simple", "Simple", 10.0)
        val csv = energyCsv(null, null, null, listOf(c), emptyList(), fixedNow)
        assertThat(csv).contains("sensor.simple,Simple,10.0000")
    }

    @Test fun `csvEscape plain value passes through unchanged`() {
        assertThat(csvEscape("hello")).isEqualTo("hello")
    }

    @Test fun `csvEscape wraps comma-containing value`() {
        assertThat(csvEscape("a,b")).isEqualTo("\"a,b\"")
    }

    @Test fun `csvEscape doubles embedded quotes`() {
        assertThat(csvEscape("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"")
    }

    // ---- empty state ---------------------------------------------------------

    @Test fun `empty state produces structural headers but no data rows`() {
        val csv = energyCsv(null, null, null, emptyList(), emptyList(), fixedNow)
        // Three section headers should be present.
        assertThat(csv).contains("section,summary")
        assertThat(csv).contains("section,top_consumers")
        assertThat(csv).contains("section,history")
        // No sensor entity-id lines.
        assertThat(csv.lines().none { it.startsWith("sensor.") }).isTrue()
    }

    // ---- UiState convenience overload ----------------------------------------

    @Test fun `UiState overload exports the recorder statsTodayKwh`() {
        val ui = EnergyViewModel.UiState(
            loading = false,
            // todayKwh is the cumulative template sum and must NEVER reach the CSV.
            todayKwh = 5.0,
            statsTodayKwh = 6.0,
            currentDrawW = null,
            productionW = null,
        )
        val csv = energyCsv(ui)
        assertThat(csv).contains("today_kwh,6.0000")
        assertThat(csv).doesNotContain("today_kwh,5.0000")
    }

    @Test fun `UiState overload does NOT fall back to the cumulative todayKwh`() {
        // statsTodayKwh null (recorder not yet populated) must export an EMPTY
        // today_kwh, never the cumulative lifetime sum in todayKwh (the old
        // fallback showed a nonsensical "today" total).
        val ui = EnergyViewModel.UiState(
            loading = false,
            todayKwh = 3.5,
            statsTodayKwh = null,
        )
        val csv = energyCsv(ui)
        assertThat(csv).contains("today_kwh,")
        assertThat(csv).doesNotContain("today_kwh,3.5000")
    }
}
