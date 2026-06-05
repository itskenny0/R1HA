package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Locks in [relativeTimeShort], the entities-card secondary_info age affix. The buckets
 * mirror the shared RelativeTime component (without its "ago"/"in"), so an entity idle for
 * months or years reads "3mo" / "1y" rather than the old days-capped "90d" / "365d".
 */
class RelativeTimeShortTest {
    private val now: Instant = Instant.parse("2024-06-01T00:00:00Z")
    private fun ago(seconds: Long) = relativeTimeShort(now.minusSeconds(seconds), now)

    @Test fun `sub-day buckets`() {
        assertThat(ago(30)).isEqualTo("30s")
        assertThat(ago(5 * 60)).isEqualTo("5m")
        assertThat(ago(3 * 3600)).isEqualTo("3h")
    }

    @Test fun `days through years`() {
        assertThat(ago(3 * 86_400)).isEqualTo("3d")
        assertThat(ago(20 * 86_400)).isEqualTo("2w")
        assertThat(ago(45 * 86_400)).isEqualTo("1mo")
        assertThat(ago(200 * 86_400)).isEqualTo("6mo")
        assertThat(ago(400 * 86_400)).isEqualTo("1y")
    }

    @Test fun `a future instant clamps to zero seconds`() {
        assertThat(relativeTimeShort(now.plusSeconds(5_000), now)).isEqualTo("0s")
    }
}
