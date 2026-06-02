package com.github.itskenny0.r1ha.core.ha

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant

/**
 * Contract for [parseHaInstant]. The real-world failure this guards against is that the
 * desugared `java.time.Instant.parse` (shipped under core-library desugaring for minSdk 23)
 * rejects HA's numeric `+00:00` offset, accepting only the bare `Z` form. That divergence is
 * invisible on the host JVM these tests run on (its `Instant.parse` accepts both), so the
 * desugared behaviour was confirmed separately against desugar_jdk_libs directly; these cases
 * lock the helper's parsing contract for every timestamp shape HA emits.
 */
class HaTimeTest {
    @Test fun `parses numeric UTC offset, the form HA actually emits`() {
        assertThat(parseHaInstant("2026-06-01T22:11:44+00:00"))
            .isEqualTo(Instant.parse("2026-06-01T22:11:44Z"))
    }

    @Test fun `parses fractional seconds with numeric offset`() {
        assertThat(parseHaInstant("2026-06-01T22:12:17.511064+00:00"))
            .isEqualTo(Instant.parse("2026-06-01T22:12:17.511064Z"))
    }

    @Test fun `parses bare Z`() {
        assertThat(parseHaInstant("2026-06-01T22:11:44Z"))
            .isEqualTo(Instant.parse("2026-06-01T22:11:44Z"))
    }

    @Test fun `parses a non-UTC offset and normalises to UTC`() {
        assertThat(parseHaInstant("2026-06-01T22:12:17.511064+02:00"))
            .isEqualTo(Instant.parse("2026-06-01T20:12:17.511064Z"))
    }

    @Test fun `returns null for blank or unparseable input`() {
        assertThat(parseHaInstant(null)).isNull()
        assertThat(parseHaInstant("")).isNull()
        assertThat(parseHaInstant("   ")).isNull()
        assertThat(parseHaInstant("not a timestamp")).isNull()
    }
}
