package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.LogbookEntry
import com.github.itskenny0.r1ha.core.ha.StatisticsBucket
import com.github.itskenny0.r1ha.core.lovelace.EntityRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NewCardHelpersTest {

    private fun row(id: String) = EntityRow(entityId = id, name = null, icon = null, secondaryInfo = null)

    // --- entity-filter: filterEntityRows ---

    @Test fun `entity-filter keeps only matching states`() {
        val rows = listOf(row("light.a"), row("light.b"), row("light.c"))
        val states = mapOf("light.a" to "on", "light.b" to "off", "light.c" to "on")
        val out = filterEntityRows(rows, listOf("on")) { states[it] }
        assertEquals(listOf("light.a", "light.c"), out.map { it.entityId })
    }

    @Test fun `entity-filter is case insensitive`() {
        val rows = listOf(row("device_tracker.phone"))
        val out = filterEntityRows(rows, listOf("HOME")) { "home" }
        assertEquals(1, out.size)
    }

    @Test fun `entity-filter with empty filter keeps everything`() {
        val rows = listOf(row("light.a"), row("light.b"))
        val out = filterEntityRows(rows, emptyList()) { null }
        assertEquals(2, out.size)
    }

    @Test fun `entity-filter drops rows with no resolvable state when filtering`() {
        val rows = listOf(row("light.a"), row("light.gone"))
        val out = filterEntityRows(rows, listOf("on")) { if (it == "light.a") "on" else null }
        assertEquals(listOf("light.a"), out.map { it.entityId })
    }

    // --- statistic: reduceStatistic / formatStatistic ---

    private fun bucket(
        mean: Double? = null,
        min: Double? = null,
        max: Double? = null,
        sum: Double? = null,
        state: Double? = null,
        change: Double? = null,
    ) = StatisticsBucket(
        start = Instant.EPOCH,
        end = Instant.EPOCH,
        mean = mean,
        min = min,
        max = max,
        sum = sum,
        state = state,
        change = change,
    )

    @Test fun `reduceStatistic averages mean across buckets`() {
        val buckets = listOf(bucket(mean = 10.0), bucket(mean = 20.0))
        assertEquals(15.0, reduceStatistic(buckets, "mean")!!, 1e-9)
    }

    @Test fun `reduceStatistic takes min and max across buckets`() {
        val buckets = listOf(bucket(min = 3.0, max = 8.0), bucket(min = 1.0, max = 12.0))
        assertEquals(1.0, reduceStatistic(buckets, "min")!!, 1e-9)
        assertEquals(12.0, reduceStatistic(buckets, "max")!!, 1e-9)
    }

    @Test fun `reduceStatistic sums change and takes last sum`() {
        val buckets = listOf(bucket(sum = 100.0, change = 5.0), bucket(sum = 130.0, change = 30.0))
        assertEquals(35.0, reduceStatistic(buckets, "change")!!, 1e-9)
        assertEquals(130.0, reduceStatistic(buckets, "sum")!!, 1e-9)
    }

    @Test fun `reduceStatistic returns null for empty or missing aggregate`() {
        assertNull(reduceStatistic(emptyList(), "mean"))
        assertNull(reduceStatistic(listOf(bucket(mean = 1.0)), "sum"))
    }

    @Test fun `formatStatistic trims trailing zeros`() {
        assertEquals("21", formatStatistic(21.0))
        assertEquals("21.5", formatStatistic(21.5))
        assertEquals("21.34", formatStatistic(21.337))
    }

    // --- logbook: filterLogbook ---

    private fun entry(id: String?, ts: Instant) = LogbookEntry(
        timestamp = ts,
        name = id ?: "x",
        message = "changed",
        entityId = id?.let { EntityId(it) },
        domain = null,
        state = null,
    )

    @Test fun `filterLogbook scopes to entity set and sorts newest first`() {
        val t0 = Instant.ofEpochSecond(100)
        val t1 = Instant.ofEpochSecond(200)
        val t2 = Instant.ofEpochSecond(300)
        val entries = listOf(
            entry("light.a", t0),
            entry("switch.b", t1),
            entry("light.a", t2),
        )
        val out = filterLogbook(entries, listOf("light.a"))
        assertEquals(2, out.size)
        assertEquals(t2, out[0].timestamp)
        assertEquals(t0, out[1].timestamp)
    }

    @Test fun `filterLogbook with empty set keeps all sorted newest first`() {
        val t0 = Instant.ofEpochSecond(100)
        val t1 = Instant.ofEpochSecond(200)
        val entries = listOf(entry("light.a", t0), entry("switch.b", t1))
        val out = filterLogbook(entries, emptyList())
        assertEquals(2, out.size)
        assertEquals(t1, out[0].timestamp)
    }

    @Test fun `filterLogbook drops entries with null entity when scoped`() {
        val entries = listOf(entry(null, Instant.ofEpochSecond(100)), entry("light.a", Instant.ofEpochSecond(200)))
        val out = filterLogbook(entries, listOf("light.a"))
        assertEquals(1, out.size)
        assertTrue(out.all { it.entityId?.value == "light.a" })
    }
}
