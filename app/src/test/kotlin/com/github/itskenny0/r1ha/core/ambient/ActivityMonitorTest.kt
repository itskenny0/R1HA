package com.github.itskenny0.r1ha.core.ambient

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ActivityMonitorTest {

    @Test fun `markInteraction publishes the latest timestamp`() {
        ActivityMonitor.markInteraction(1_000L)
        assertThat(ActivityMonitor.lastInteractionAt.value).isEqualTo(1_000L)
        ActivityMonitor.markInteraction(2_500L)
        assertThat(ActivityMonitor.lastInteractionAt.value).isEqualTo(2_500L)
    }
}
