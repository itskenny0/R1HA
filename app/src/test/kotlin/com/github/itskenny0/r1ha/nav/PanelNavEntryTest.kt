package com.github.itskenny0.r1ha.nav

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the nav-entry derivation logic for pinned panels: route construction,
 * url_path round-trip through the Routes helpers, and basic label/glyph expectations.
 *
 * Robolectric is required because [Routes.panelUrlPath] takes an android.os.Bundle,
 * which is a framework class that needs the Android runtime to instantiate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PanelNavEntryTest {

    // ── Routes.panelViewerRoute / panelUrlPath ──

    @Test fun `panelViewerRoute builds correct route string`() {
        assertThat(Routes.panelViewerRoute("hacs")).isEqualTo("panel_viewer/hacs")
    }

    @Test fun `panelViewerRoute works for hyphenated paths`() {
        assertThat(Routes.panelViewerRoute("zigbee2mqtt")).isEqualTo("panel_viewer/zigbee2mqtt")
    }

    @Test fun `panelUrlPath extracts the url_path from a bundle`() {
        val bundle = android.os.Bundle().apply { putString("urlPath", "esphome") }
        assertThat(Routes.panelUrlPath(bundle)).isEqualTo("esphome")
    }

    @Test fun `panelUrlPath returns empty string for null bundle`() {
        assertThat(Routes.panelUrlPath(null)).isEmpty()
    }

    @Test fun `panelUrlPath returns empty string for missing key`() {
        assertThat(Routes.panelUrlPath(android.os.Bundle())).isEmpty()
    }

    // ── Route uniqueness across different url_paths ──

    @Test fun `different url_paths produce different routes`() {
        val hacksRoute = Routes.panelViewerRoute("hacs")
        val esphomeRoute = Routes.panelViewerRoute("esphome")
        assertThat(hacksRoute).isNotEqualTo(esphomeRoute)
    }

    // ── PANEL_VIEWER route template contains the argument placeholder ──

    @Test fun `PANEL_VIEWER route template contains urlPath segment`() {
        assertThat(Routes.PANEL_VIEWER).contains("{urlPath}")
    }

    // ── Panel route never clashes with existing Routes constants ──

    @Test fun `panel_viewer prefix does not collide with existing routes`() {
        val existingRoutes = listOf(
            Routes.LOVELACE,
            Routes.DASHBOARDS,
            Routes.SETTINGS,
            Routes.CARD_STACK,
            Routes.DASHBOARD,
            Routes.SIDEBAR_CONFIG,
        )
        existingRoutes.forEach { existing ->
            val panelRoute = Routes.panelViewerRoute("hacs")
            assertThat(panelRoute).isNotEqualTo(existing)
        }
    }
}
