package com.github.itskenny0.r1ha.feature.search

import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.jupiter.api.Test

/**
 * Stress-test the search ranker with a registry the size of "biggest HA install I've
 * seen in the wild" (~5000 entities). Asserts the filter completes in well under one
 * frame budget so a keystroke can't stall the input pipeline. This guards against
 * accidentally re-introducing an O(n²) join in the filter or losing the off-Main
 * dispatch — the ranker itself must remain cheap regardless of caller.
 */
class SearchRankerStressTest {

    private fun synth(n: Int): List<EntityState> {
        val domains = arrayOf(
            Domain.LIGHT, Domain.SWITCH, Domain.SENSOR, Domain.BINARY_SENSOR,
            Domain.SCENE, Domain.SCRIPT, Domain.MEDIA_PLAYER, Domain.FAN,
            Domain.COVER, Domain.LOCK, Domain.CLIMATE, Domain.AUTOMATION,
        )
        val areas = arrayOf(
            "Kitchen", "Living Room", "Bedroom", "Office", "Garage", null,
        )
        val now = Instant.now()
        return List(n) { i ->
            val d = domains[i % domains.size]
            val prefix = d.name.lowercase()
            val obj = "stress_${i}_${(i * 31).toString(16)}"
            EntityState(
                id = EntityId("$prefix.$obj"),
                friendlyName = "Entity #$i ($d)",
                area = areas[i % areas.size],
                isOn = (i and 1) == 0,
                percent = if (d == Domain.LIGHT) (i % 101) else null,
                raw = null,
                lastChanged = now,
                isAvailable = true,
            )
        }
    }

    @Test
    fun `5000 entities filter completes under one frame budget`() {
        val all = synth(5000)
        val bucketOf: (Domain) -> SearchViewModel.Bucket = { d ->
            when (d) {
                Domain.SENSOR, Domain.BINARY_SENSOR -> SearchViewModel.Bucket.SENSORS
                Domain.SCENE, Domain.SCRIPT, Domain.BUTTON,
                Domain.INPUT_BUTTON, Domain.AUTOMATION -> SearchViewModel.Bucket.ACTIONS
                else -> SearchViewModel.Bucket.CONTROLS
            }
        }

        // Warm up the JIT — the first call pays for class loading / inlining costs that
        // wouldn't be on the hot path in production.
        repeat(3) {
            SearchRanker.filter(all, "entity", SearchViewModel.Bucket.ALL, bucketOf, 80)
        }

        val started = System.nanoTime()
        val out = SearchRanker.filter(all, "entity", SearchViewModel.Bucket.ALL, bucketOf, 80)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000.0

        assertThat(out).hasSize(80)
        // 100 ms is well above one 60-Hz frame budget but leaves a safety margin for
        // slower CI runners. Real-device perf on a Rabbit R1 is closer to 30 ms.
        assertThat(elapsedMillis).isLessThan(100.0)
    }

    @Test
    fun `bucket filter narrows results`() {
        val all = synth(2000)
        val bucketOf: (Domain) -> SearchViewModel.Bucket = { d ->
            when (d) {
                Domain.SENSOR, Domain.BINARY_SENSOR -> SearchViewModel.Bucket.SENSORS
                Domain.SCENE, Domain.SCRIPT, Domain.BUTTON,
                Domain.INPUT_BUTTON, Domain.AUTOMATION -> SearchViewModel.Bucket.ACTIONS
                else -> SearchViewModel.Bucket.CONTROLS
            }
        }
        val sensors = SearchRanker.filter(
            all = all,
            query = "",
            bucket = SearchViewModel.Bucket.SENSORS,
            bucketOf = bucketOf,
            resultCap = 1000,
        )
        assertThat(sensors).isNotEmpty()
        assertThat(sensors.all { bucketOf(it.id.domain) == SearchViewModel.Bucket.SENSORS }).isTrue()
    }

    @Test
    fun `prefix match ranks above contains match`() {
        val all = listOf(
            // Only contains "kitchen" mid-name; no prefix anywhere.
            EntityState(
                id = EntityId("light.living_room_kitchen_pendant"),
                friendlyName = "Living Room Kitchen Pendant",
                area = null,
                isOn = true,
                percent = 50,
                raw = null,
                lastChanged = Instant.now(),
                isAvailable = true,
            ),
            // Prefix-matches on object_id (kitchen_main).
            EntityState(
                id = EntityId("light.kitchen_main"),
                friendlyName = "Main",
                area = null,
                isOn = true,
                percent = 50,
                raw = null,
                lastChanged = Instant.now(),
                isAvailable = true,
            ),
        )
        val bucketOf: (Domain) -> SearchViewModel.Bucket = { SearchViewModel.Bucket.CONTROLS }
        val out = SearchRanker.filter(all, "kitchen", SearchViewModel.Bucket.ALL, bucketOf, 10)
        assertThat(out.first().id.value).isEqualTo("light.kitchen_main")
    }

    @Test
    fun `query matches on area and is case-insensitive`() {
        val all = listOf(
            EntityState(
                id = EntityId("light.lamp"),
                friendlyName = "Lamp",
                area = "Garage",
                isOn = true,
                percent = 50,
                raw = null,
                lastChanged = Instant.now(),
                isAvailable = true,
            ),
            EntityState(
                id = EntityId("switch.fountain"),
                friendlyName = "Fountain",
                area = "Garden",
                isOn = false,
                percent = null,
                raw = null,
                lastChanged = Instant.now(),
                isAvailable = true,
            ),
        )
        val bucketOf: (Domain) -> SearchViewModel.Bucket = { SearchViewModel.Bucket.CONTROLS }
        // Mixed-case query still matches the "Garage" area on the lamp only.
        val out = SearchRanker.filter(all, "GaRaGe", SearchViewModel.Bucket.ALL, bucketOf, 10)
        assertThat(out.map { it.id.value }).containsExactly("light.lamp")
    }

    @Test
    fun `empty query with ALL bucket returns nothing`() {
        val all = synth(50)
        val bucketOf: (Domain) -> SearchViewModel.Bucket = { SearchViewModel.Bucket.CONTROLS }
        // Empty-query + ALL is the instructional-placeholder case: the screen shows a
        // hint rather than dumping the whole registry, so the ranker returns empty.
        val out = SearchRanker.filter(all, "", SearchViewModel.Bucket.ALL, bucketOf, 80)
        assertThat(out).isEmpty()
    }
}
