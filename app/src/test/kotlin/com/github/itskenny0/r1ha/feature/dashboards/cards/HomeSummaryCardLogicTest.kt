package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.google.common.truth.Truth.assertThat
import com.github.itskenny0.r1ha.nav.Routes
import org.junit.Test

class HomeSummaryCardLogicTest {

    // ── labelFor ─────────────────────────────────────────────────────────────

    @Test fun `labelFor maps all seven known summaries`() {
        assertThat(HomeSummaryCardLogic.labelFor("light")).isEqualTo("Lights")
        assertThat(HomeSummaryCardLogic.labelFor("climate")).isEqualTo("Climate")
        assertThat(HomeSummaryCardLogic.labelFor("security")).isEqualTo("Security")
        assertThat(HomeSummaryCardLogic.labelFor("media_players")).isEqualTo("Media")
        assertThat(HomeSummaryCardLogic.labelFor("maintenance")).isEqualTo("Maintenance")
        assertThat(HomeSummaryCardLogic.labelFor("energy")).isEqualTo("Energy")
        assertThat(HomeSummaryCardLogic.labelFor("persons")).isEqualTo("People")
    }

    @Test fun `labelFor falls back to prettified value for unknown summary`() {
        assertThat(HomeSummaryCardLogic.labelFor("custom_thing")).isEqualTo("Custom thing")
    }

    // ── iconFor ──────────────────────────────────────────────────────────────

    @Test fun `iconFor returns an mdi prefix for all known summaries`() {
        for (summary in HomeSummaryCardLogic.KNOWN_SUMMARIES) {
            assertThat(HomeSummaryCardLogic.iconFor(summary)).startsWith("mdi:")
        }
    }

    @Test fun `iconFor falls back to mdi home for unknown summary`() {
        assertThat(HomeSummaryCardLogic.iconFor("unknown")).isEqualTo("mdi:home")
    }

    // ── navRouteFor ───────────────────────────────────────────────────────────

    @Test fun `navRouteFor maps maintenance energy persons to dedicated screens`() {
        assertThat(HomeSummaryCardLogic.navRouteFor("maintenance")).isEqualTo(Routes.UPDATES)
        assertThat(HomeSummaryCardLogic.navRouteFor("energy")).isEqualTo(Routes.ENERGY)
        assertThat(HomeSummaryCardLogic.navRouteFor("persons")).isEqualTo(Routes.PERSONS)
    }

    @Test fun `navRouteFor maps domain categories to search screen`() {
        assertThat(HomeSummaryCardLogic.navRouteFor("light")).isEqualTo(Routes.SEARCH)
        assertThat(HomeSummaryCardLogic.navRouteFor("climate")).isEqualTo(Routes.SEARCH)
        assertThat(HomeSummaryCardLogic.navRouteFor("security")).isEqualTo(Routes.SEARCH)
        assertThat(HomeSummaryCardLogic.navRouteFor("media_players")).isEqualTo(Routes.SEARCH)
    }

    @Test fun `navRouteFor falls back to search for unknown summary`() {
        assertThat(HomeSummaryCardLogic.navRouteFor("unknown")).isEqualTo(Routes.SEARCH)
    }

    // ── statusLine ───────────────────────────────────────────────────────────

    @Test fun `statusLine returns loading placeholder when activeCount is null`() {
        assertThat(HomeSummaryCardLogic.statusLine("light", null, 5)).isEqualTo("...")
    }

    @Test fun `statusLine light zero returns All off, nonzero returns N on`() {
        assertThat(HomeSummaryCardLogic.statusLine("light", 0, 5)).isEqualTo("All off")
        assertThat(HomeSummaryCardLogic.statusLine("light", 3, 5)).isEqualTo("3 on")
    }

    @Test fun `statusLine persons zero returns Nobody home, nonzero returns N home`() {
        assertThat(HomeSummaryCardLogic.statusLine("persons", 0, 3)).isEqualTo("Nobody home")
        assertThat(HomeSummaryCardLogic.statusLine("persons", 2, 3)).isEqualTo("2 home")
    }

    @Test fun `statusLine maintenance zero returns All good, singular and plural`() {
        assertThat(HomeSummaryCardLogic.statusLine("maintenance", 0, 0)).isEqualTo("All good")
        assertThat(HomeSummaryCardLogic.statusLine("maintenance", 1, 1)).isEqualTo("1 issue")
        assertThat(HomeSummaryCardLogic.statusLine("maintenance", 3, 3)).isEqualTo("3 issues")
    }

    @Test fun `statusLine security zero returns All secure, nonzero returns N open`() {
        assertThat(HomeSummaryCardLogic.statusLine("security", 0, 2)).isEqualTo("All secure")
        assertThat(HomeSummaryCardLogic.statusLine("security", 1, 2)).isEqualTo("1 open")
    }

    @Test fun `statusLine falls back to device count for unknown summary`() {
        assertThat(HomeSummaryCardLogic.statusLine("custom", 0, 0)).isEqualTo("No devices")
        assertThat(HomeSummaryCardLogic.statusLine("custom", 0, 1)).isEqualTo("1 device")
        assertThat(HomeSummaryCardLogic.statusLine("custom", 0, 3)).isEqualTo("3 devices")
    }

    // ── KNOWN_SUMMARIES completeness ──────────────────────────────────────────

    @Test fun `KNOWN_SUMMARIES contains all seven categories`() {
        assertThat(HomeSummaryCardLogic.KNOWN_SUMMARIES).containsExactly(
            "light", "climate", "security", "media_players",
            "maintenance", "energy", "persons",
        )
    }
}
