package com.github.itskenny0.r1ha.feature.widget

import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.FavoritePage
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks in [buildWidgetFavoritePages], the configuration activity's listing
 * source: page grouping and order, the rename-override > friendly-name >
 * prettified-object-id name precedence, empty-page skipping, and the legacy
 * flat-favorites fallback for a settings snapshot that predates pages.
 */
class WidgetFavoriteListingTest {

    @Test fun `groups favorites by page in page order`() {
        val settings = AppSettings(
            pages = listOf(
                FavoritePage(id = "p1", name = "HOME", favorites = listOf("light.a", "switch.b")),
                FavoritePage(id = "p2", name = "BEDROOM", favorites = listOf("sensor.c")),
            ),
        )
        val pages = buildWidgetFavoritePages(settings)
        assertThat(pages.map { it.pageName }).containsExactly("HOME", "BEDROOM").inOrder()
        assertThat(pages[0].entries.map { it.entityId })
            .containsExactly("light.a", "switch.b").inOrder()
        assertThat(pages[1].entries.map { it.entityId }).containsExactly("sensor.c")
    }

    @Test fun `empty pages are skipped`() {
        val settings = AppSettings(
            pages = listOf(
                FavoritePage(id = "p1", name = "HOME", favorites = listOf("light.a")),
                FavoritePage(id = "p2", name = "EMPTY", favorites = emptyList()),
            ),
        )
        assertThat(buildWidgetFavoritePages(settings).map { it.pageName })
            .containsExactly("HOME")
    }

    @Test fun `rename override beats friendly name beats object id`() {
        val settings = AppSettings(
            pages = listOf(
                FavoritePage(
                    id = "p1",
                    name = "HOME",
                    favorites = listOf("light.a", "light.b", "light.ceiling_lamp"),
                ),
            ),
            nameOverrides = mapOf("light.a" to "Renamed"),
        )
        val friendly = mapOf("light.a" to "Friendly A", "light.b" to "Friendly B")
        val entries = buildWidgetFavoritePages(settings, friendly).single().entries
        assertThat(entries.map { it.displayName })
            .containsExactly("Renamed", "Friendly B", "Ceiling lamp").inOrder()
    }

    @Test fun `legacy flat favorites stand in when pages are absent`() {
        val settings = AppSettings(favorites = listOf("light.a", "sensor.b"))
        val pages = buildWidgetFavoritePages(settings)
        assertThat(pages).hasSize(1)
        assertThat(pages.single().pageName).isEqualTo("HOME")
        assertThat(pages.single().entries.map { it.entityId })
            .containsExactly("light.a", "sensor.b").inOrder()
    }

    @Test fun `no favorites anywhere yields an empty listing`() {
        assertThat(buildWidgetFavoritePages(AppSettings())).isEmpty()
    }

    @Test fun `page accent is carried through for the group header`() {
        val settings = AppSettings(
            pages = listOf(
                FavoritePage(
                    id = "p1",
                    name = "NIGHT",
                    favorites = listOf("light.a"),
                    accentArgb = 0xFFB388FF.toInt(),
                ),
            ),
        )
        assertThat(buildWidgetFavoritePages(settings).single().accentArgb)
            .isEqualTo(0xFFB388FF.toInt())
    }
}
