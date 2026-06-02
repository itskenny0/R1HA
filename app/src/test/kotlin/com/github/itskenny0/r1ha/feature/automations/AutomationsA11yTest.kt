package com.github.itskenny0.r1ha.feature.automations

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

class AutomationsA11yTest {

    @Test fun `mode word spells out each known mode`() {
        val prev = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertThat(automationModeWord(AutomationsViewModel.Mode.SINGLE)).isEqualTo("single mode")
            assertThat(automationModeWord(AutomationsViewModel.Mode.PARALLEL)).isEqualTo("parallel mode")
            assertThat(automationModeWord(AutomationsViewModel.Mode.QUEUED)).isEqualTo("queued mode")
            assertThat(automationModeWord(AutomationsViewModel.Mode.RESTART)).isEqualTo("restart mode")
        } finally {
            Locale.setDefault(prev)
        }
    }

    @Test fun `mode word is blank for unknown so nothing dangles`() {
        assertThat(automationModeWord(AutomationsViewModel.Mode.UNKNOWN)).isEmpty()
    }

    @Test fun `state word conveys enabled and disabled in words`() {
        assertThat(automationStateWord(true)).isEqualTo("enabled")
        assertThat(automationStateWord(false)).isEqualTo("disabled")
    }

    @Test fun `run action label adds now to distinguish from toggle`() {
        assertThat(automationRunActionLabel("Front Door Lights"))
            .isEqualTo("Run Front Door Lights now")
    }

    @Test fun `run in-flight label uses Running verb`() {
        assertThat(automationRunInFlightLabel("Front Door Lights"))
            .isEqualTo("Running Front Door Lights")
    }

    @Test fun `favourite label reflects pinned state`() {
        assertThat(automationFavoriteLabel("Front Door Lights", isFavorite = false))
            .isEqualTo("Pin Front Door Lights to favourites")
        assertThat(automationFavoriteLabel("Front Door Lights", isFavorite = true))
            .isEqualTo("Front Door Lights pinned to favourites")
    }

    @Test fun `row label for enabled automation frames the toggle as Disable`() {
        assertThat(
            automationRowLabel(
                name = "Morning Routine",
                enabled = true,
                mode = AutomationsViewModel.Mode.SINGLE,
                runningInstances = 0,
                lastTriggeredSpoken = null,
            ),
        ).isEqualTo("Disable Morning Routine, currently enabled, single mode")
    }

    @Test fun `row label for disabled automation frames the toggle as Enable`() {
        assertThat(
            automationRowLabel(
                name = "Morning Routine",
                enabled = false,
                mode = AutomationsViewModel.Mode.QUEUED,
                runningInstances = 0,
                lastTriggeredSpoken = null,
            ),
        ).isEqualTo("Enable Morning Routine, currently disabled, queued mode")
    }

    @Test fun `row label omits unknown mode`() {
        assertThat(
            automationRowLabel(
                name = "Morning Routine",
                enabled = true,
                mode = AutomationsViewModel.Mode.UNKNOWN,
                runningInstances = 0,
                lastTriggeredSpoken = null,
            ),
        ).isEqualTo("Disable Morning Routine, currently enabled")
    }

    @Test fun `row label appends singular running instance`() {
        assertThat(
            automationRowLabel(
                name = "Morning Routine",
                enabled = true,
                mode = AutomationsViewModel.Mode.PARALLEL,
                runningInstances = 1,
                lastTriggeredSpoken = null,
            ),
        ).isEqualTo("Disable Morning Routine, currently enabled, parallel mode, 1 running instance")
    }

    @Test fun `row label appends plural running instances`() {
        assertThat(
            automationRowLabel(
                name = "Morning Routine",
                enabled = true,
                mode = AutomationsViewModel.Mode.PARALLEL,
                runningInstances = 3,
                lastTriggeredSpoken = null,
            ),
        ).isEqualTo("Disable Morning Routine, currently enabled, parallel mode, 3 running instances")
    }

    @Test fun `row label appends last triggered when present`() {
        assertThat(
            automationRowLabel(
                name = "Morning Routine",
                enabled = true,
                mode = AutomationsViewModel.Mode.SINGLE,
                runningInstances = 0,
                lastTriggeredSpoken = "5 minutes ago",
            ),
        ).isEqualTo("Disable Morning Routine, currently enabled, single mode, last triggered 5 minutes ago")
    }

    @Test fun `row label omits blank last triggered`() {
        assertThat(
            automationRowLabel(
                name = "Morning Routine",
                enabled = false,
                mode = AutomationsViewModel.Mode.SINGLE,
                runningInstances = 0,
                lastTriggeredSpoken = "   ",
            ),
        ).isEqualTo("Enable Morning Routine, currently disabled, single mode")
    }

    @Test fun `row label folds everything in order`() {
        assertThat(
            automationRowLabel(
                name = "Morning Routine",
                enabled = true,
                mode = AutomationsViewModel.Mode.RESTART,
                runningInstances = 2,
                lastTriggeredSpoken = "an hour ago",
            ),
        ).isEqualTo(
            "Disable Morning Routine, currently enabled, restart mode, 2 running instances, last triggered an hour ago",
        )
    }

    @Test fun `unavailable row label reads as read-only status not a toggle`() {
        assertThat(
            automationRowLabel(
                name = "Morning Routine",
                enabled = false,
                mode = AutomationsViewModel.Mode.SINGLE,
                runningInstances = 0,
                lastTriggeredSpoken = null,
                available = false,
            ),
        ).isEqualTo("Morning Routine, unavailable")
    }

    @Test fun `unavailable row label still surfaces last triggered`() {
        assertThat(
            automationRowLabel(
                name = "Morning Routine",
                enabled = false,
                mode = AutomationsViewModel.Mode.SINGLE,
                runningInstances = 0,
                lastTriggeredSpoken = "an hour ago",
                available = false,
            ),
        ).isEqualTo("Morning Routine, unavailable, last triggered an hour ago")
    }
}
