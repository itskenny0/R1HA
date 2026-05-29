package com.github.itskenny0.r1ha.feature.logbook

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LogbookA11yTest {

    @BeforeEach
    fun setLocale() {
        Locale.setDefault(Locale.US)
    }

    @Test
    fun domainWord_humanizesSnakeCase() {
        assertThat(LogbookA11y.domainWord("binary_sensor")).isEqualTo("binary sensor")
        assertThat(LogbookA11y.domainWord("light")).isEqualTo("light")
    }

    @Test
    fun domainWord_blankBecomesEvent() {
        assertThat(LogbookA11y.domainWord(null)).isEqualTo("event")
        assertThat(LogbookA11y.domainWord("  ")).isEqualTo("event")
    }

    @Test
    fun row_fullEntry() {
        assertThat(
            LogbookA11y.rowDescription(
                domain = "light",
                name = "Kitchen light",
                message = "turned on",
                state = "on",
                triggeredBy = "by Evening automation",
                relativeTime = "2m",
            ),
        ).isEqualTo("light, Kitchen light, turned on, now on, by Evening automation, 2m ago")
    }

    @Test
    fun row_minimalEntryOmitsBlanks() {
        assertThat(
            LogbookA11y.rowDescription(
                domain = null,
                name = "Sun",
                message = null,
                state = null,
                triggeredBy = "  ",
                relativeTime = null,
            ),
        ).isEqualTo("event, Sun")
    }

    @Test
    fun row_messageWithoutState() {
        assertThat(
            LogbookA11y.rowDescription(
                domain = "automation",
                name = "Nightly",
                message = "triggered",
                state = null,
                triggeredBy = null,
                relativeTime = "1h",
            ),
        ).isEqualTo("automation, Nightly, triggered, 1h ago")
    }

    @Test
    fun actionLabel_namesHistory() {
        assertThat(LogbookA11y.rowActionLabel("Front door"))
            .isEqualTo("Open history for Front door")
    }
}
