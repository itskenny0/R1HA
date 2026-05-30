package com.github.itskenny0.r1ha.feature.settings

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FeaturedTest {

    private fun catalogue(size: Int): List<FeaturedItem> =
        (0 until size).map { i ->
            FeaturedItem(glyph = "g$i", title = "t$i", blurb = "b$i", onOpen = {})
        }

    @Test
    fun `returns count items for a roomy catalogue`() {
        val result = featuredSlice(catalogue(10), index = 0, count = 3)
        assertThat(result).hasSize(3)
    }

    @Test
    fun `deterministic for the same index`() {
        val c = catalogue(8)
        val a = featuredSlice(c, index = 5, count = 3).map { it.title }
        val b = featuredSlice(c, index = 5, count = 3).map { it.title }
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `window starts at index modulo size and wraps`() {
        val c = catalogue(6)
        assertThat(featuredSlice(c, index = 0, count = 3).map { it.title })
            .containsExactly("t0", "t1", "t2").inOrder()
        // index 5 wraps the tail around to the front: 5, 0, 1
        assertThat(featuredSlice(c, index = 5, count = 3).map { it.title })
            .containsExactly("t5", "t0", "t1").inOrder()
    }

    @Test
    fun `trio has no duplicates across the whole catalogue cycle`() {
        val c = catalogue(7)
        for (index in 0 until 20) {
            val titles = featuredSlice(c, index, count = 3).map { it.title }
            assertThat(titles.toSet()).hasSize(titles.size)
        }
    }

    @Test
    fun `advancing the index walks the whole catalogue before repeating the start`() {
        val c = catalogue(6)
        val starts = (0 until c.size).map { featuredSlice(c, it, count = 3).first().title }
        assertThat(starts.toSet()).hasSize(c.size)
        // One full cycle later, the start repeats.
        assertThat(featuredSlice(c, c.size, count = 3).first().title)
            .isEqualTo(featuredSlice(c, 0, count = 3).first().title)
    }

    @Test
    fun `catalogue smaller than count returns every item once with no repeats`() {
        val c = catalogue(2)
        val result = featuredSlice(c, index = 0, count = 3)
        assertThat(result).hasSize(2)
        assertThat(result.map { it.title }.toSet()).hasSize(2)
    }

    @Test
    fun `single item catalogue yields that one item`() {
        val result = featuredSlice(catalogue(1), index = 9, count = 3)
        assertThat(result.map { it.title }).containsExactly("t0")
    }

    @Test
    fun `empty catalogue yields empty`() {
        assertThat(featuredSlice(emptyList(), index = 3, count = 3)).isEmpty()
    }

    @Test
    fun `non-positive count yields empty`() {
        assertThat(featuredSlice(catalogue(5), index = 0, count = 0)).isEmpty()
    }

    @Test
    fun `negative index is normalised without crashing`() {
        val c = catalogue(6)
        val result = featuredSlice(c, index = -1, count = 3).map { it.title }
        // -1 normalises to start at the last slot, then wraps.
        assertThat(result).containsExactly("t5", "t0", "t1").inOrder()
    }

    // ── nextRotationIndex ────────────────────────────────────────────────────

    @Test
    fun `next index advances by the group size`() {
        assertThat(nextRotationIndex(current = 0, count = 3, catalogueSize = 10)).isEqualTo(3)
        assertThat(nextRotationIndex(current = 3, count = 3, catalogueSize = 10)).isEqualTo(6)
    }

    @Test
    fun `next index wraps round-robin and stays in range`() {
        // 9 + 3 = 12, modulo 10 -> 2; never grows unbounded.
        assertThat(nextRotationIndex(current = 9, count = 3, catalogueSize = 10)).isEqualTo(2)
    }

    @Test
    fun `next index walks the catalogue then returns to start`() {
        // catalogue of 6, group of 3: 0 -> 3 -> 0 -> 3 ... two distinct windows.
        val first = nextRotationIndex(current = 0, count = 3, catalogueSize = 6)
        val second = nextRotationIndex(current = first, count = 3, catalogueSize = 6)
        assertThat(first).isEqualTo(3)
        assertThat(second).isEqualTo(0)
    }

    @Test
    fun `next index over an empty catalogue clamps to zero`() {
        assertThat(nextRotationIndex(current = 5, count = 3, catalogueSize = 0)).isEqualTo(0)
    }

    @Test
    fun `next index normalises a negative or wrapped current value`() {
        assertThat(nextRotationIndex(current = -1, count = 3, catalogueSize = 6)).isEqualTo(2)
    }

    @Test
    fun `next index treats a non-positive count as a single step`() {
        assertThat(nextRotationIndex(current = 0, count = 0, catalogueSize = 6)).isEqualTo(1)
    }

    @Test
    fun `next index when catalogue smaller than the group still cycles`() {
        // catalogue of 2, group of 3: step is the group size, modulo 2 keeps it in range.
        assertThat(nextRotationIndex(current = 0, count = 3, catalogueSize = 2)).isEqualTo(1)
        assertThat(nextRotationIndex(current = 1, count = 3, catalogueSize = 2)).isEqualTo(0)
    }
}
