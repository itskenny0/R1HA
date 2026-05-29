package com.github.itskenny0.r1ha.feature.dashboard

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks the tile → column distribution the multi-column tablet dashboard uses. The worry the
 * round-robin guards against: tiles silently dropping or duplicating when the count doesn't
 * divide evenly into columns. Every index 0..count-1 must appear exactly once, columns stay
 * balanced, and source order is preserved top-to-bottom within a column.
 */
class DashboardColumnsTest {

    @Test fun `single column returns all indices in order`() {
        assertThat(distributeIntoColumns(4, 1)).containsExactly(listOf(0, 1, 2, 3))
    }

    @Test fun `even split across two columns`() {
        assertThat(distributeIntoColumns(4, 2)).containsExactly(
            listOf(0, 2),
            listOf(1, 3),
        ).inOrder()
    }

    @Test fun `odd count favours earlier columns`() {
        // count = 5, columns = 2 -> first column gets the extra tile.
        assertThat(distributeIntoColumns(5, 2)).containsExactly(
            listOf(0, 2, 4),
            listOf(1, 3),
        ).inOrder()
    }

    @Test fun `three columns deal round robin`() {
        assertThat(distributeIntoColumns(7, 3)).containsExactly(
            listOf(0, 3, 6),
            listOf(1, 4),
            listOf(2, 5),
        ).inOrder()
    }

    @Test fun `every index appears exactly once for many shapes`() {
        for (count in 0..20) {
            for (columns in 1..5) {
                val buckets = distributeIntoColumns(count, columns)
                val flat = buckets.flatten().sorted()
                assertThat(flat).isEqualTo((0 until count).toList())
            }
        }
    }

    @Test fun `column lengths differ by at most one`() {
        for (count in 0..20) {
            for (columns in 2..5) {
                val buckets = distributeIntoColumns(count, columns)
                val sizes = buckets.map { it.size }
                assertThat(sizes.max() - sizes.min()).isAtMost(1)
            }
        }
    }

    @Test fun `source order preserved within each column`() {
        val buckets = distributeIntoColumns(9, 3)
        for (bucket in buckets) {
            assertThat(bucket).isInOrder()
        }
    }

    @Test fun `zero tiles yields empty buckets`() {
        assertThat(distributeIntoColumns(0, 3)).containsExactly(
            emptyList<Int>(),
            emptyList<Int>(),
            emptyList<Int>(),
        )
    }
}
