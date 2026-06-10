package com.github.itskenny0.r1ha.core.lovelace

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import java.time.Instant

/**
 * Parser coverage for every new key Batch G adds to the four graph cards:
 * sensor limits / state_color / attribute, history-graph split / colours /
 * axis options / fractional hours, statistic period objects / icon / unit /
 * energy seam, statistics-graph chart_type / multi stat_types / per-entity
 * overrides / y-axis / legend / period bucket.
 */
class LovelaceGraphCardParserTest {

    private fun card(raw: String): LovelaceCard =
        LovelaceParser.parseCard(Json.parseToJsonElement(raw) as JsonObject)

    // --- sensor -----------------------------------------------------------

    @Test fun `sensor parses limits state_color and attribute`() {
        val c = card(
            """
            {"type":"sensor","entity":"sensor.temp","graph":"line","hours_to_show":12,
             "detail":2,"limits":{"min":0,"max":40},"state_color":true,
             "attribute":"battery_level"}
            """.trimIndent(),
        ) as LovelaceCard.Sensor
        assertThat(c.graph).isTrue()
        assertThat(c.hoursToShow).isEqualTo(12)
        assertThat(c.detail).isEqualTo(2)
        assertThat(c.limitMin).isEqualTo(0.0)
        assertThat(c.limitMax).isEqualTo(40.0)
        assertThat(c.stateColor).isTrue()
        assertThat(c.attribute).isEqualTo("battery_level")
    }

    // --- history-graph ----------------------------------------------------

    @Test fun `history-graph parses split colours axis options and fractional hours`() {
        val c = card(
            """
            {"type":"history-graph","title":"Temps","hours_to_show":0.5,
             "split_device_classes":true,"show_names":false,"logarithmic_scale":true,
             "min_y_axis":-10,"max_y_axis":50,"fit_y_data":true,"expand_legend":true,
             "entities":[
               {"entity":"sensor.a","color":"red"},
               "switch.b"
             ]}
            """.trimIndent(),
        ) as LovelaceCard.HistoryGraph
        assertThat(c.hoursToShowExact).isEqualTo(0.5)
        assertThat(c.hoursToShow).isEqualTo(1) // coerced floor of fractional hours
        assertThat(c.splitDeviceClasses).isTrue()
        assertThat(c.showNames).isFalse()
        assertThat(c.logarithmicScale).isTrue()
        assertThat(c.minYAxis).isEqualTo(-10.0)
        assertThat(c.maxYAxis).isEqualTo(50.0)
        assertThat(c.fitYData).isTrue()
        assertThat(c.expandLegend).isTrue()
        assertThat(c.entityColors["sensor.a"]).isEqualTo("red")
        assertThat(c.entityColors).doesNotContainKey("switch.b")
        assertThat(c.entities.map { it.entityId }).containsExactly("sensor.a", "switch.b").inOrder()
    }

    // --- statistic --------------------------------------------------------

    @Test fun `statistic parses calendar period with offset plus icon and unit`() {
        val c = card(
            """
            {"type":"statistic","entity":"sensor.energy","stat_type":"sum",
             "icon":"mdi:flash","unit":"kWh",
             "period":{"calendar":{"period":"month","offset":-1}}}
            """.trimIndent(),
        ) as LovelaceCard.Statistic
        assertThat(c.statType).isEqualTo("sum")
        assertThat(c.icon).isEqualTo("mdi:flash")
        assertThat(c.unit).isEqualTo("kWh")
        val spec = c.periodSpec as StatisticPeriodConfig.Calendar
        assertThat(spec.period).isEqualTo("month")
        assertThat(spec.offset).isEqualTo(-1)
    }

    @Test fun `statistic parses fixed_period instants`() {
        val c = card(
            """
            {"type":"statistic","entity":"sensor.energy","stat_type":"change",
             "period":{"fixed_period":{"start":"2026-01-01T00:00:00+00:00",
                                       "end":"2026-02-01T00:00:00+00:00"}}}
            """.trimIndent(),
        ) as LovelaceCard.Statistic
        val spec = c.periodSpec as StatisticPeriodConfig.Fixed
        assertThat(spec.startMillis).isEqualTo(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli())
        assertThat(spec.endMillis).isEqualTo(Instant.parse("2026-02-01T00:00:00Z").toEpochMilli())
    }

    @Test fun `statistic parses rolling_window duration and offset`() {
        val c = card(
            """
            {"type":"statistic","entity":"sensor.energy","stat_type":"mean",
             "period":{"rolling_window":{"duration":{"hours":24},"offset":{"days":1}}}}
            """.trimIndent(),
        ) as LovelaceCard.Statistic
        val spec = c.periodSpec as StatisticPeriodConfig.Rolling
        assertThat(spec.durationMillis).isEqualTo(24L * 3_600_000L)
        assertThat(spec.offsetMillis).isEqualTo(86_400_000L)
    }

    @Test fun `statistic parses the energy date-selection seam`() {
        val c = card(
            """{"type":"statistic","entity":"sensor.energy","stat_type":"sum",
                "energy_date_selection":true}""",
        ) as LovelaceCard.Statistic
        assertThat(c.collectionKey).isEqualTo("energy_date_selection")
    }

    // --- statistics-graph -------------------------------------------------

    @Test fun `statistics-graph parses chart_type multi stat_types and per-entity overrides`() {
        val c = card(
            """
            {"type":"statistics-graph","chart_type":"bar","period":"day",
             "days_to_show":14,"stat_types":["min","max"],
             "min_y_axis":0,"max_y_axis":100,"fit_y_data":true,
             "logarithmic_scale":true,"unit":"°C","hide_legend":true,"expand_legend":true,
             "entities":[
               {"entity":"sensor.a","name":"Alpha","color":"blue"},
               "sensor.b"
             ]}
            """.trimIndent(),
        ) as LovelaceCard.StatisticsGraph
        assertThat(c.chartType).isEqualTo("bar")
        assertThat(c.period).isEqualTo("day")
        assertThat(c.daysToShow).isEqualTo(14)
        assertThat(c.statTypes).containsExactly("min", "max").inOrder()
        assertThat(c.minYAxis).isEqualTo(0.0)
        assertThat(c.maxYAxis).isEqualTo(100.0)
        assertThat(c.fitYData).isTrue()
        assertThat(c.logarithmicScale).isTrue()
        assertThat(c.unit).isEqualTo("°C")
        assertThat(c.hideLegend).isTrue()
        assertThat(c.expandLegend).isTrue()
        assertThat(c.entityNames["sensor.a"]).isEqualTo("Alpha")
        assertThat(c.entityColors["sensor.a"]).isEqualTo("blue")
        assertThat(c.entityIds).containsExactly("sensor.a", "sensor.b").inOrder()
    }

    @Test fun `statistics-graph accepts a single stat_types string`() {
        val c = card(
            """{"type":"statistics-graph","stat_types":"mean","entities":["sensor.a"]}""",
        ) as LovelaceCard.StatisticsGraph
        assertThat(c.statTypes).containsExactly("mean")
        assertThat(c.chartType).isEqualTo("line") // default
        assertThat(c.period).isEqualTo("hour") // default bucket
    }
}
