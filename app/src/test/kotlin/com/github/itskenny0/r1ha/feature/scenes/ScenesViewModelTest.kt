package com.github.itskenny0.r1ha.feature.scenes

import com.github.itskenny0.r1ha.core.ha.EntityId
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Pure-helper coverage for the Scenes surface: last-activated derivation and
 * the kind + free-text filter that backs the visible row list. Both run with
 * no repository / Android dependencies.
 */
class ScenesViewModelTest {

    private fun entry(
        id: String,
        name: String,
        kind: ScenesViewModel.Kind,
        lastActivated: Instant? = null,
    ) = ScenesViewModel.Entry(
        id = EntityId(id),
        name = name,
        kind = kind,
        lastActivated = lastActivated,
    )

    private fun stateWith(
        all: List<ScenesViewModel.Entry>,
        filter: ScenesViewModel.Filter = ScenesViewModel.Filter.ALL,
        query: String = "",
    ) = ScenesViewModel.UiState(loading = false, all = all, filter = filter, query = query)

    // --- lastActivatedOf -------------------------------------------------

    @Test fun `scene last-activated parses ISO state into instant`() {
        val ts = "2026-05-29T08:13:04.123456+00:00"
        val parsed = ScenesViewModel.lastActivatedOf(
            kind = ScenesViewModel.Kind.SCENE,
            rawState = ts,
            lastTriggeredAttr = null,
        )
        assertThat(parsed).isEqualTo(Instant.parse(ts))
    }

    @Test fun `scene never run yields null for unknown and unavailable states`() {
        for (s in listOf("unknown", "UNKNOWN", "unavailable", "none", "", "   ")) {
            assertThat(
                ScenesViewModel.lastActivatedOf(
                    kind = ScenesViewModel.Kind.SCENE,
                    rawState = s,
                    lastTriggeredAttr = null,
                ),
            ).isNull()
        }
    }

    @Test fun `scene ignores the script last_triggered attribute`() {
        val parsed = ScenesViewModel.lastActivatedOf(
            kind = ScenesViewModel.Kind.SCENE,
            rawState = "unknown",
            lastTriggeredAttr = "2026-05-29T08:13:04+00:00",
        )
        assertThat(parsed).isNull()
    }

    @Test fun `script last-activated reads the last_triggered attribute not the on-off state`() {
        val ts = "2026-05-29T09:00:00+00:00"
        val parsed = ScenesViewModel.lastActivatedOf(
            kind = ScenesViewModel.Kind.SCRIPT,
            rawState = "on",
            lastTriggeredAttr = ts,
        )
        assertThat(parsed).isEqualTo(Instant.parse(ts))
    }

    @Test fun `script with no last_triggered yields null`() {
        assertThat(
            ScenesViewModel.lastActivatedOf(
                kind = ScenesViewModel.Kind.SCRIPT,
                rawState = "off",
                lastTriggeredAttr = null,
            ),
        ).isNull()
    }

    @Test fun `garbage timestamp yields null rather than throwing`() {
        assertThat(
            ScenesViewModel.lastActivatedOf(
                kind = ScenesViewModel.Kind.SCENE,
                rawState = "not-a-timestamp",
                lastTriggeredAttr = null,
            ),
        ).isNull()
    }

    // --- filter (UiState.entries) ---------------------------------------

    private val movie = entry("scene.movie_night", "Movie Night", ScenesViewModel.Kind.SCENE)
    private val dinner = entry("scene.dinner", "Dinner Mode", ScenesViewModel.Kind.SCENE)
    private val goodnight = entry("script.goodnight", "Goodnight", ScenesViewModel.Kind.SCRIPT)
    private val sample = listOf(movie, dinner, goodnight)

    @Test fun `ALL filter with blank query returns everything`() {
        assertThat(stateWith(sample).entries).containsExactly(movie, dinner, goodnight)
    }

    @Test fun `SCENES filter keeps only scenes`() {
        val entries = stateWith(sample, filter = ScenesViewModel.Filter.SCENES).entries
        assertThat(entries).containsExactly(movie, dinner)
    }

    @Test fun `SCRIPTS filter keeps only scripts`() {
        val entries = stateWith(sample, filter = ScenesViewModel.Filter.SCRIPTS).entries
        assertThat(entries).containsExactly(goodnight)
    }

    @Test fun `query matches name case-insensitively`() {
        assertThat(stateWith(sample, query = "MOVIE").entries).containsExactly(movie)
    }

    @Test fun `query matches entity id`() {
        assertThat(stateWith(sample, query = "goodnight").entries).containsExactly(goodnight)
    }

    @Test fun `query is trimmed before matching`() {
        assertThat(stateWith(sample, query = "  dinner  ").entries).containsExactly(dinner)
    }

    @Test fun `kind filter and query compose`() {
        val entries = stateWith(
            sample,
            filter = ScenesViewModel.Filter.SCENES,
            query = "night",
        ).entries
        // 'night' matches Movie Night (scene) but the script Goodnight is excluded by the kind filter.
        assertThat(entries).containsExactly(movie)
    }

    @Test fun `no match returns empty`() {
        assertThat(stateWith(sample, query = "zzz").entries).isEmpty()
    }
}
