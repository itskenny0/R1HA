package com.github.itskenny0.r1ha.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ScreenStatesA11yTest {

    @Test fun `title is sentence-cased for speech`() {
        assertThat(stateTitleSpoken("COULDN'T LOAD DEVICES")).isEqualTo("Couldn't load devices")
        assertThat(stateTitleSpoken("NO AREAS")).isEqualTo("No areas")
    }

    @Test fun `announcement folds title and body into one phrase`() {
        assertThat(
            stateAnnouncement(
                title = "NO AREAS",
                body = "Define areas under Settings in HA's web UI.",
            ),
        ).isEqualTo("No areas. Define areas under Settings in HA's web UI.")
    }

    @Test fun `announcement is just the title when body is missing`() {
        assertThat(stateAnnouncement("NO DEVICES", null)).isEqualTo("No devices")
        assertThat(stateAnnouncement("NO DEVICES", "   ")).isEqualTo("No devices")
    }

    @Test fun `retry chip says what it retries`() {
        assertThat(retryActionDescription("COULDN'T LOAD DEVICES"))
            .isEqualTo("Retry, couldn't load devices")
    }

    @Test fun `generic action chip is sentence-cased`() {
        assertThat(stateActionDescription("OPEN SETTINGS")).isEqualTo("Open settings")
        assertThat(stateActionDescription("RETRY")).isEqualTo("Retry")
    }
}
