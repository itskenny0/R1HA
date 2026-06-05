package com.github.itskenny0.r1ha.feature.weather

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Locks in the weather-screen condition display label: hyphenated slugs become spaced
 * words, missing states read as "UNKNOWN", and the upper-casing stays ASCII even under a
 * locale (Turkish) whose default upper-case would turn "i" into a dotted "İ".
 */
class WeatherScreenTest {
    @Test fun `hyphenated slugs become spaced upper case words`() {
        assertThat(conditionDisplayLabel("clear-night")).isEqualTo("CLEAR NIGHT")
        assertThat(conditionDisplayLabel("snowy-rainy")).isEqualTo("SNOWY RAINY")
        assertThat(conditionDisplayLabel("sunny")).isEqualTo("SUNNY")
    }

    @Test fun `missing states read as UNKNOWN or UNAVAILABLE`() {
        assertThat(conditionDisplayLabel("")).isEqualTo("UNKNOWN")
        assertThat(conditionDisplayLabel("unknown")).isEqualTo("UNKNOWN")
        assertThat(conditionDisplayLabel("unavailable")).isEqualTo("UNAVAILABLE")
    }

    @Test fun `i-bearing conditions stay ASCII under a Turkish locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            // Default-locale upper-casing would yield "RAİNY" / "LİGHTNİNG" / "WİNDY".
            assertThat(conditionDisplayLabel("rainy")).isEqualTo("RAINY")
            assertThat(conditionDisplayLabel("lightning")).isEqualTo("LIGHTNING")
            assertThat(conditionDisplayLabel("windy")).isEqualTo("WINDY")
        } finally {
            Locale.setDefault(previous)
        }
    }
}
