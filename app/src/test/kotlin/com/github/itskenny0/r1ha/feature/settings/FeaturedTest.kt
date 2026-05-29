package com.github.itskenny0.r1ha.feature.settings

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FeaturedTest {

    private fun pool(size: Int): List<FeaturedItem> =
        (0 until size).map { i ->
            FeaturedItem(glyph = "g$i", title = "t$i", blurb = "b$i", onOpen = {})
        }

    @Test
    fun `returns count items for a roomy pool`() {
        val result = featuredFor(pool(10), index = 0, count = 3)
        assertThat(result).hasSize(3)
    }

    @Test
    fun `deterministic for the same index`() {
        val p = pool(8)
        val a = featuredFor(p, index = 5, count = 3).map { it.title }
        val b = featuredFor(p, index = 5, count = 3).map { it.title }
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `window starts at index modulo size and wraps`() {
        val p = pool(6)
        assertThat(featuredFor(p, index = 0, count = 3).map { it.title })
            .containsExactly("t0", "t1", "t2").inOrder()
        // index 5 wraps the tail around to the front: 5, 0, 1
        assertThat(featuredFor(p, index = 5, count = 3).map { it.title })
            .containsExactly("t5", "t0", "t1").inOrder()
    }

    @Test
    fun `trio has no duplicates across the whole pool cycle`() {
        val p = pool(7)
        for (index in 0 until 20) {
            val titles = featuredFor(p, index, count = 3).map { it.title }
            assertThat(titles.toSet()).hasSize(titles.size)
        }
    }

    @Test
    fun `advancing the index walks the whole pool before repeating the start`() {
        val p = pool(6)
        val starts = (0 until p.size).map { featuredFor(p, it, count = 3).first().title }
        assertThat(starts.toSet()).hasSize(p.size)
        // One full cycle later, the start repeats.
        assertThat(featuredFor(p, p.size, count = 3).first().title)
            .isEqualTo(featuredFor(p, 0, count = 3).first().title)
    }

    @Test
    fun `pool smaller than count returns every item once with no repeats`() {
        val p = pool(2)
        val result = featuredFor(p, index = 0, count = 3)
        assertThat(result).hasSize(2)
        assertThat(result.map { it.title }.toSet()).hasSize(2)
    }

    @Test
    fun `single item pool yields that one item`() {
        val result = featuredFor(pool(1), index = 9, count = 3)
        assertThat(result.map { it.title }).containsExactly("t0")
    }

    @Test
    fun `empty pool yields empty`() {
        assertThat(featuredFor(emptyList(), index = 3, count = 3)).isEmpty()
    }

    @Test
    fun `non-positive count yields empty`() {
        assertThat(featuredFor(pool(5), index = 0, count = 0)).isEmpty()
    }

    @Test
    fun `negative index is normalised without crashing`() {
        val p = pool(6)
        val result = featuredFor(p, index = -1, count = 3).map { it.title }
        // -1 normalises to start at the last slot, then wraps.
        assertThat(result).containsExactly("t5", "t0", "t1").inOrder()
    }
}
