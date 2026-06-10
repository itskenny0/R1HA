package com.github.itskenny0.r1ha.feature.cameras

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CamerasA11yTest {

    @Test fun `summary reads as one sentence`() {
        assertThat(camerasSummaryDescription(total = 8, streaming = 3, recording = 1, offline = 2))
            .isEqualTo(
                "Cameras. 8 cameras, 3 streaming, 1 recording, 2 offline. " +
                    "Select a camera for a live view.",
            )
    }

    @Test fun `summary keeps zero status counts so the reader hears all-clear`() {
        assertThat(camerasSummaryDescription(total = 1, streaming = 0, recording = 0, offline = 0))
            .isEqualTo(
                "Cameras. 1 camera, 0 streaming, 0 recording, 0 offline. " +
                    "Select a camera for a live view.",
            )
    }

    @Test fun `summary handles no cameras`() {
        assertThat(camerasSummaryDescription(total = 0, streaming = 0, recording = 0, offline = 0))
            .isEqualTo(
                "Cameras. no cameras, 0 streaming, 0 recording, 0 offline. " +
                    "Select a camera for a live view.",
            )
    }
}
