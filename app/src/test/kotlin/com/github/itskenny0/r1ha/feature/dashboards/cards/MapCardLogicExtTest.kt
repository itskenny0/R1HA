package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for the map card's cluster grouping and trail simplification.
 */
class MapCardLogicExtTest {

    // ── cluster grouping ─────────────────────────────────────────────────────

    @Test fun `distant points each form their own cluster`() {
        val points = listOf(
            PlotPoint(0, 0.1f, 0.1f),
            PlotPoint(1, 0.9f, 0.9f),
        )
        val clusters = clusterPlotPoints(points, radiusFrac = 0.05f)
        assertThat(clusters).hasSize(2)
        assertThat(clusters.all { it.members.size == 1 }).isTrue()
    }

    @Test fun `overlapping points merge into one cluster`() {
        val points = listOf(
            PlotPoint(0, 0.50f, 0.50f),
            PlotPoint(1, 0.51f, 0.51f),
            PlotPoint(2, 0.49f, 0.49f),
        )
        val clusters = clusterPlotPoints(points, radiusFrac = 0.05f)
        assertThat(clusters).hasSize(1)
        assertThat(clusters[0].members).containsExactly(0, 1, 2)
        // Centroid is the mean of the members.
        assertThat(clusters[0].xFrac).isWithin(1e-4f).of(0.50f)
    }

    @Test fun `mixed near and far points split correctly`() {
        val points = listOf(
            PlotPoint(0, 0.10f, 0.10f),
            PlotPoint(1, 0.11f, 0.10f),
            PlotPoint(2, 0.90f, 0.90f),
        )
        val clusters = clusterPlotPoints(points, radiusFrac = 0.05f)
        assertThat(clusters).hasSize(2)
        assertThat(clusters.first { it.members.size == 2 }.members).containsExactly(0, 1)
    }

    // ── trail simplification ─────────────────────────────────────────────────

    @Test fun `trail of two points is unchanged`() {
        val trail = listOf(TrailPoint(0.0, 0.0), TrailPoint(1.0, 1.0))
        assertThat(simplifyTrail(trail, 0.1)).isEqualTo(trail)
    }

    @Test fun `colinear points are dropped`() {
        val trail = listOf(
            TrailPoint(0.0, 0.0),
            TrailPoint(0.0, 1.0),
            TrailPoint(0.0, 2.0),
            TrailPoint(0.0, 3.0),
        )
        val simplified = simplifyTrail(trail, 0.001)
        assertThat(simplified).containsExactly(TrailPoint(0.0, 0.0), TrailPoint(0.0, 3.0)).inOrder()
    }

    @Test fun `a significant deviation is kept`() {
        val trail = listOf(
            TrailPoint(0.0, 0.0),
            TrailPoint(1.0, 0.5), // big perpendicular jog
            TrailPoint(0.0, 1.0),
        )
        val simplified = simplifyTrail(trail, 0.1)
        assertThat(simplified).hasSize(3)
    }
}
