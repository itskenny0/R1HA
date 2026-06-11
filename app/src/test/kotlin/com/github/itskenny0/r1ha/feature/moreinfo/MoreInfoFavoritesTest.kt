package com.github.itskenny0.r1ha.feature.moreinfo

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for [MoreInfoFavorites]: position normalisation, default-vs-stored
 * resolution, and computed default favourite colours, all mirroring HA's
 * favorites.ts / favorite_positions.ts / computeDefaultFavoriteColors.
 */
class MoreInfoFavoritesTest {

    @Test fun `default positions match HA`() {
        assertThat(MoreInfoFavorites.DEFAULT_POSITIONS).containsExactly(0, 25, 75, 100).inOrder()
    }

    @Test fun `normalize clamps and dedupes preserving order`() {
        val out = MoreInfoFavorites.normalizePositions(listOf(120, 50, 50, -5, 75))
        // 120 -> 100, 50 (kept once), -5 -> 0, 75.
        assertThat(out).containsExactly(100, 50, 0, 75).inOrder()
    }

    @Test fun `normalize null is empty`() {
        assertThat(MoreInfoFavorites.normalizePositions(null)).isEmpty()
    }

    @Test fun `resolve uses stored when present`() {
        val out = MoreInfoFavorites.resolvePositions(listOf(10, 90), supportsPosition = true)
        assertThat(out).containsExactly(10, 90).inOrder()
    }

    @Test fun `resolve falls back to defaults when stored empty`() {
        val out = MoreInfoFavorites.resolvePositions(emptyList(), supportsPosition = true)
        assertThat(out).containsExactly(0, 25, 75, 100).inOrder()
    }

    @Test fun `resolve falls back to defaults when stored null`() {
        val out = MoreInfoFavorites.resolvePositions(null, supportsPosition = true)
        assertThat(out).containsExactly(0, 25, 75, 100).inOrder()
    }

    @Test fun `resolve empty when position unsupported`() {
        assertThat(MoreInfoFavorites.resolvePositions(listOf(10), supportsPosition = false)).isEmpty()
    }

    @Test fun `default colors empty for brightness-only bulb`() {
        val out = MoreInfoFavorites.computeDefaultFavoriteColors(
            supportsColorTemp = false,
            supportsColor = false,
            minColorTempK = null,
            maxColorTempK = null,
        )
        assertThat(out).isEmpty()
    }

    @Test fun `default colors for color-temp bulb gives four kelvin swatches`() {
        val out = MoreInfoFavorites.computeDefaultFavoriteColors(
            supportsColorTemp = true,
            supportsColor = false,
            minColorTempK = 2000,
            maxColorTempK = 6500,
        )
        assertThat(out).hasSize(4)
        assertThat(out).containsExactly(
            FavoriteColor.ColorTemp(2000),
            FavoriteColor.ColorTemp(3500),
            FavoriteColor.ColorTemp(5000),
            FavoriteColor.ColorTemp(6500),
        ).inOrder()
    }

    @Test fun `default colors for colour bulb gives four temp plus four rgb`() {
        val out = MoreInfoFavorites.computeDefaultFavoriteColors(
            supportsColorTemp = false,
            supportsColor = true,
            minColorTempK = null,
            maxColorTempK = null,
        )
        // 4 derived colour-temp swatches (across 2000..6500) + the 4 fixed RGB.
        assertThat(out).hasSize(8)
        val rgbTail = out.takeLast(4)
        assertThat(rgbTail).isEqualTo(
            MoreInfoFavorites.DEFAULT_COLORED_COLORS.map { FavoriteColor.Rgb(it) },
        )
    }

    @Test fun `default colors color-temp bulb that also supports colour appends rgb`() {
        val out = MoreInfoFavorites.computeDefaultFavoriteColors(
            supportsColorTemp = true,
            supportsColor = true,
            minColorTempK = 2200,
            maxColorTempK = 4000,
        )
        // 4 kelvin across the bulb's own range, then 4 RGB.
        assertThat(out).hasSize(8)
        assertThat(out.first()).isEqualTo(FavoriteColor.ColorTemp(2200))
        assertThat(out[3]).isEqualTo(FavoriteColor.ColorTemp(4000))
    }

    // ── Edit / reset / copy orchestration ────────────────────────────────────

    @Test fun `add position normalises and de-dupes`() {
        assertThat(MoreInfoFavorites.addPosition(listOf(0, 50), 50)).containsExactly(0, 50).inOrder()
        assertThat(MoreInfoFavorites.addPosition(listOf(0, 50), 75)).containsExactly(0, 50, 75).inOrder()
        assertThat(MoreInfoFavorites.addPosition(listOf(0), 150)).containsExactly(0, 100).inOrder()
    }

    @Test fun `remove position drops the matching value`() {
        assertThat(MoreInfoFavorites.removePosition(listOf(0, 25, 100), 25)).containsExactly(0, 100).inOrder()
    }

    @Test fun `remove colour at index drops one swatch`() {
        val colors = listOf(FavoriteColor.ColorTemp(3000), FavoriteColor.Rgb(0xFF112233.toInt()))
        assertThat(MoreInfoFavorites.removeColorAt(colors, 0)).containsExactly(colors[1])
        assertThat(MoreInfoFavorites.removeColorAt(colors, 5)).isEqualTo(colors)
    }

    @Test fun `copy compatibility requires same domain and capability`() {
        assertThat(MoreInfoFavorites.canCopyTo("light", "light", capabilityOk = true)).isTrue()
        assertThat(MoreInfoFavorites.canCopyTo("light", "light", capabilityOk = false)).isFalse()
        assertThat(MoreInfoFavorites.canCopyTo("light", "switch", capabilityOk = true)).isFalse()
    }

    @Test fun `copy summary partitions successes and failures in order`() {
        val report = MoreInfoFavorites.summariseCopy(
            listOf("light.a" to true, "light.b" to false, "light.c" to true),
        )
        assertThat(report.succeeded).containsExactly("light.a", "light.c").inOrder()
        assertThat(report.failed).containsExactly("light.b")
        assertThat(report.allOk).isFalse()
        assertThat(report.total).isEqualTo(3)
    }

    @Test fun `copy summary is all-ok when nothing failed`() {
        val report = MoreInfoFavorites.summariseCopy(listOf("light.a" to true))
        assertThat(report.allOk).isTrue()
    }
}
