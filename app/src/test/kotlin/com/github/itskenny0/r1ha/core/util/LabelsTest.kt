package com.github.itskenny0.r1ha.core.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Locks in [areaLabel]: underscores become spaces, the result upper-cases, and the casing
 * stays ASCII even under a Turkish locale whose default upper-case dots the "i".
 */
class LabelsTest {
    @Test fun `underscores become spaces and the label upper-cases`() {
        assertThat(areaLabel("living_room")).isEqualTo("LIVING ROOM")
        assertThat(areaLabel("garage")).isEqualTo("GARAGE")
        assertThat(areaLabel("master_bedroom_ensuite")).isEqualTo("MASTER BEDROOM ENSUITE")
    }

    @Test fun `optionLabel turns snake_case ids into spaced upper case`() {
        assertThat(optionLabel("color_loop")).isEqualTo("COLOR LOOP")
        assertThat(optionLabel("eco_mode")).isEqualTo("ECO MODE")
        assertThat(optionLabel("auto")).isEqualTo("AUTO")
        assertThat(optionLabel("Night")).isEqualTo("NIGHT")
    }

    @Test fun `i-bearing area names stay ASCII under a Turkish locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            // Default-locale upper-casing would yield "KİTCHEN" / "LİVİNG ROOM".
            assertThat(areaLabel("kitchen")).isEqualTo("KITCHEN")
            assertThat(areaLabel("living_room")).isEqualTo("LIVING ROOM")
        } finally {
            Locale.setDefault(previous)
        }
    }
}
