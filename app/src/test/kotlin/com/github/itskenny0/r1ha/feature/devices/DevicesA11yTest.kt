package com.github.itskenny0.r1ha.feature.devices

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DevicesA11yTest {

    @BeforeEach
    fun setLocale() {
        Locale.setDefault(Locale.US)
    }

    @Test
    fun deviceRow_fullMetadata() {
        assertThat(
            DevicesA11y.deviceRowDescription(
                name = "Living room lamp",
                entityCount = 4,
                areaName = "Living Room",
                manufacturer = "Philips",
                model = "Hue",
                disabled = false,
            ),
        ).isEqualTo("Living room lamp, 4 entities, in Living Room, by Philips, Hue, opens device")
    }

    @Test
    fun deviceRow_disabledIsSpokenNotJustDimmed() {
        assertThat(
            DevicesA11y.deviceRowDescription(
                name = "Old hub",
                entityCount = 0,
                areaName = null,
                manufacturer = null,
                model = null,
                disabled = true,
            ),
        ).isEqualTo("Old hub, disabled, no entities, opens device")
    }

    @Test
    fun deviceRow_blankAreaAndMakerOmitted() {
        assertThat(
            DevicesA11y.deviceRowDescription(
                name = "Sensor",
                entityCount = 1,
                areaName = "  ",
                manufacturer = "",
                model = null,
                disabled = false,
            ),
        ).isEqualTo("Sensor, 1 entity, opens device")
    }

    @Test
    fun entityCountPhrase_pluralises() {
        assertThat(DevicesA11y.entityCountPhrase(0)).isEqualTo("no entities")
        assertThat(DevicesA11y.entityCountPhrase(1)).isEqualTo("1 entity")
        assertThat(DevicesA11y.entityCountPhrase(7)).isEqualTo("7 entities")
    }

    @Test
    fun sectionHeader_pluralises() {
        assertThat(DevicesA11y.sectionHeaderDescription("Kitchen", 1))
            .isEqualTo("Kitchen, 1 device")
        assertThat(DevicesA11y.sectionHeaderDescription("Kitchen", 3))
            .isEqualTo("Kitchen, 3 devices")
    }

    @Test
    fun entityRow_foldsStateAndTags() {
        assertThat(
            DevicesA11y.entityRowDescription(
                name = "Ceiling light",
                entityId = "light.ceiling",
                stateSpoken = "on",
                tags = listOf("HUE", "DISABLED"),
            ),
        ).isEqualTo("Ceiling light, on, light.ceiling, hue, disabled")
    }

    @Test
    fun entityRow_blankStateAndTagsOmitted() {
        assertThat(
            DevicesA11y.entityRowDescription(
                name = "Temp",
                entityId = "sensor.temp",
                stateSpoken = "  ",
                tags = listOf("", "  "),
            ),
        ).isEqualTo("Temp, sensor.temp")
    }

    @Test
    fun registrySummary_readsAsOneSentence() {
        assertThat(
            DevicesA11y.registrySummaryDescription(
                devices = 128,
                areas = 12,
                makers = 9,
                entities = 940,
            ),
        ).isEqualTo(
            "Device registry. 128 devices, 12 areas, 9 makers, 940 entities. " +
                "Select a device to inspect its entities.",
        )
    }

    @Test
    fun registrySummary_singularAndZeroCounts() {
        assertThat(
            DevicesA11y.registrySummaryDescription(
                devices = 1,
                areas = 0,
                makers = 1,
                entities = 0,
            ),
        ).isEqualTo(
            "Device registry. 1 device, no areas, 1 maker, no entities. " +
                "Select a device to inspect its entities.",
        )
    }
}
