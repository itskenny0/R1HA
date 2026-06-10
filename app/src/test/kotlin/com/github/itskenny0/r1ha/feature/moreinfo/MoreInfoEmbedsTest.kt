package com.github.itskenny0.r1ha.feature.moreinfo

import com.github.itskenny0.r1ha.core.ha.StatisticsBucket
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Unit tests for [MoreInfoEmbeds]: the history-mode choice, statistics→series
 * mapping, statistics-bucket decode, and YAML-ish state rendering.
 */
class MoreInfoEmbedsTest {

    @Test fun `numeric with statistics chooses STATISTICS`() {
        val mode = MoreInfoEmbeds.chooseHistoryMode(
            numericNow = true, hasStatistics = true, supportsTimeline = false,
        )
        assertThat(mode).isEqualTo(MoreInfoEmbeds.HistoryMode.STATISTICS)
    }

    @Test fun `numeric without statistics chooses LINE`() {
        val mode = MoreInfoEmbeds.chooseHistoryMode(
            numericNow = true, hasStatistics = false, supportsTimeline = false,
        )
        assertThat(mode).isEqualTo(MoreInfoEmbeds.HistoryMode.LINE)
    }

    @Test fun `non-numeric with timeline chooses TIMELINE`() {
        val mode = MoreInfoEmbeds.chooseHistoryMode(
            numericNow = false, hasStatistics = false, supportsTimeline = true,
        )
        assertThat(mode).isEqualTo(MoreInfoEmbeds.HistoryMode.TIMELINE)
    }

    @Test fun `non-numeric without timeline chooses NONE`() {
        val mode = MoreInfoEmbeds.chooseHistoryMode(
            numericNow = false, hasStatistics = false, supportsTimeline = false,
        )
        assertThat(mode).isEqualTo(MoreInfoEmbeds.HistoryMode.NONE)
    }

    private fun bucket(
        startMs: Long, mean: Double? = null, min: Double? = null, max: Double? = null,
        state: Double? = null, change: Double? = null,
    ) = StatisticsBucket(
        start = Instant.ofEpochMilli(startMs),
        end = Instant.ofEpochMilli(startMs + 3_600_000),
        mean = mean, min = min, max = max, sum = null, state = state, change = change,
    )

    @Test fun `statistics series splits mean min max`() {
        val series = MoreInfoEmbeds.statisticsSeries(
            listOf(
                bucket(0, mean = 10.0, min = 8.0, max = 12.0),
                bucket(3_600_000, mean = 20.0, min = 18.0, max = 22.0),
            ),
        )
        assertThat(series.mean.map { it.value }).containsExactly(10.0, 20.0).inOrder()
        assertThat(series.min.map { it.value }).containsExactly(8.0, 18.0).inOrder()
        assertThat(series.max.map { it.value }).containsExactly(12.0, 22.0).inOrder()
    }

    @Test fun `statistics series falls back to state then change for mean`() {
        val series = MoreInfoEmbeds.statisticsSeries(
            listOf(
                bucket(0, state = 5.0),
                bucket(3_600_000, change = 7.0),
            ),
        )
        assertThat(series.mean.map { it.value }).containsExactly(5.0, 7.0).inOrder()
        assertThat(series.min).isEmpty()
        assertThat(series.max).isEmpty()
    }

    @Test fun `empty statistics series reports isEmpty`() {
        assertThat(MoreInfoEmbeds.statisticsSeries(emptyList()).isEmpty).isTrue()
    }

    @Test fun `parse statistics buckets reads epoch millis and aggregates`() {
        val arr = Json.parseToJsonElement(
            """[{"start":1000,"end":4600,"mean":10.5,"min":9.0,"max":12.0}]""",
        ) as kotlinx.serialization.json.JsonArray
        val out = parseStatisticsBuckets(arr)
        assertThat(out).hasSize(1)
        assertThat(out[0].start.toEpochMilli()).isEqualTo(1000)
        assertThat(out[0].mean).isEqualTo(10.5)
        assertThat(out[0].min).isEqualTo(9.0)
        assertThat(out[0].max).isEqualTo(12.0)
    }

    @Test fun `parse statistics buckets skips rows without start`() {
        val arr = Json.parseToJsonElement("""[{"mean":1.0}]""") as kotlinx.serialization.json.JsonArray
        assertThat(parseStatisticsBuckets(arr)).isEmpty()
    }

    @Test fun `render yaml puts state first then scalars`() {
        val attrs = Json.parseToJsonElement(
            """{"unit_of_measurement":"°C","friendly_name":"Kitchen"}""",
        ) as JsonObject
        val yaml = MoreInfoEmbeds.renderStateYaml("21.5", attrs)
        val lines = yaml.lines()
        assertThat(lines.first()).isEqualTo("state: 21.5")
        // °C contains no YAML indicator and no ": ", so it renders bare.
        assertThat(lines).contains("unit_of_measurement: °C")
        assertThat(lines).contains("friendly_name: Kitchen")
    }

    @Test fun `render yaml nests a list`() {
        val attrs = Json.parseToJsonElement("""{"options":["a","b"]}""") as JsonObject
        val yaml = MoreInfoEmbeds.renderStateYaml("on", attrs)
        assertThat(yaml).contains("options:")
        assertThat(yaml).contains("  - a")
        assertThat(yaml).contains("  - b")
    }

    @Test fun `render yaml quotes ambiguous scalars`() {
        val attrs = Json.parseToJsonElement("""{"a":"true","b":"null"}""") as JsonObject
        val yaml = MoreInfoEmbeds.renderStateYaml("", attrs)
        // empty state quotes to "" ; bare "true"/"null" strings quote so they
        // don't read as booleans / null.
        assertThat(yaml.lines().first()).isEqualTo("state: \"\"")
        assertThat(yaml).contains("a: \"true\"")
        assertThat(yaml).contains("b: \"null\"")
    }

    @Test fun `render yaml empty list and object`() {
        val attrs = Json.parseToJsonElement("""{"l":[],"o":{}}""") as JsonObject
        val yaml = MoreInfoEmbeds.renderStateYaml("x", attrs)
        assertThat(yaml).contains("l: []")
        assertThat(yaml).contains("o: {}")
    }
}
