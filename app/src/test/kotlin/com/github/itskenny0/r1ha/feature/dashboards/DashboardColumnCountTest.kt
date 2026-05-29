package com.github.itskenny0.r1ha.feature.dashboards

import com.github.itskenny0.r1ha.ui.components.WindowTier
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure responsive-layout decisions that back the
 * native Lovelace view renderer. These are the rules that stop a desktop
 * authored dashboard from cramming several cards into one row on a phone.
 */
class DashboardColumnCountTest {

    private lateinit var previousLocale: Locale

    @BeforeEach
    fun pinLocale() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @AfterEach
    fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun `r1 panel is always a single column`() {
        assertThat(dashboardColumnCount(WindowTier.R1, requestedColumns = null)).isEqualTo(1)
        assertThat(dashboardColumnCount(WindowTier.R1, requestedColumns = 4)).isEqualTo(1)
        assertThat(dashboardColumnCount(WindowTier.R1, requestedColumns = 1)).isEqualTo(1)
    }

    @Test
    fun `compact phone collapses to a single column even when more is requested`() {
        assertThat(dashboardColumnCount(WindowTier.Compact, requestedColumns = null)).isEqualTo(1)
        assertThat(dashboardColumnCount(WindowTier.Compact, requestedColumns = 3)).isEqualTo(1)
        assertThat(dashboardColumnCount(WindowTier.Compact, requestedColumns = 99)).isEqualTo(1)
    }

    @Test
    fun `medium width defaults to two columns`() {
        assertThat(dashboardColumnCount(WindowTier.Medium, requestedColumns = null)).isEqualTo(2)
    }

    @Test
    fun `medium width clamps a larger request down to two`() {
        assertThat(dashboardColumnCount(WindowTier.Medium, requestedColumns = 4)).isEqualTo(2)
    }

    @Test
    fun `medium width honours a smaller request`() {
        assertThat(dashboardColumnCount(WindowTier.Medium, requestedColumns = 1)).isEqualTo(1)
    }

    @Test
    fun `expanded width defaults to three columns`() {
        assertThat(dashboardColumnCount(WindowTier.Expanded, requestedColumns = null)).isEqualTo(3)
    }

    @Test
    fun `expanded width honours a four column request up to its ceiling`() {
        assertThat(dashboardColumnCount(WindowTier.Expanded, requestedColumns = 4)).isEqualTo(4)
        assertThat(dashboardColumnCount(WindowTier.Expanded, requestedColumns = 8)).isEqualTo(4)
    }

    @Test
    fun `non positive requests fall back to the tier default`() {
        assertThat(dashboardColumnCount(WindowTier.Expanded, requestedColumns = 0)).isEqualTo(3)
        assertThat(dashboardColumnCount(WindowTier.Expanded, requestedColumns = -2)).isEqualTo(3)
        assertThat(dashboardColumnCount(WindowTier.Medium, requestedColumns = 0)).isEqualTo(2)
    }

    @Test
    fun `every tier always yields at least one column`() {
        for (tier in WindowTier.entries) {
            assertThat(dashboardColumnCount(tier, requestedColumns = null)).isAtLeast(1)
            assertThat(dashboardColumnCount(tier, requestedColumns = -5)).isAtLeast(1)
        }
    }

    @Test
    fun `cards distribute round robin into balanced lanes`() {
        val lanes = distributeCardsIntoLanes(count = 5, columns = 2)
        assertThat(lanes).hasSize(2)
        assertThat(lanes[0]).containsExactly(0, 2, 4).inOrder()
        assertThat(lanes[1]).containsExactly(1, 3).inOrder()
    }

    @Test
    fun `distribution yields the requested lane count even with fewer cards`() {
        val lanes = distributeCardsIntoLanes(count = 1, columns = 3)
        assertThat(lanes).hasSize(3)
        assertThat(lanes[0]).containsExactly(0)
        assertThat(lanes[1]).isEmpty()
        assertThat(lanes[2]).isEmpty()
    }

    @Test
    fun `distribution clamps a non positive column count to one lane`() {
        val lanes = distributeCardsIntoLanes(count = 3, columns = 0)
        assertThat(lanes).hasSize(1)
        assertThat(lanes[0]).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun `distribution of zero cards yields empty lanes`() {
        val lanes = distributeCardsIntoLanes(count = 0, columns = 2)
        assertThat(lanes).hasSize(2)
        assertThat(lanes[0]).isEmpty()
        assertThat(lanes[1]).isEmpty()
    }
}
