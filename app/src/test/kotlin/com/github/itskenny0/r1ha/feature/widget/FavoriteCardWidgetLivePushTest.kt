package com.github.itskenny0.r1ha.feature.widget

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FavoriteCardWidgetLivePushTest {

    private val widgetsByEntity = mapOf(
        "light.desk" to listOf(1),
        "switch.fan" to listOf(2, 3), // same entity bound to two instances
        "sensor.temp" to listOf(4),
    )

    @Test fun `first emission repaints every bound widget`() {
        val ids = changedWidgetIds(
            previous = null,
            current = mapOf("light.desk" to "on"),
            widgetsByEntity = widgetsByEntity,
        )
        assertThat(ids).containsExactly(1, 2, 3, 4)
    }

    @Test fun `only the changed entity's widgets repaint`() {
        val ids = changedWidgetIds(
            previous = mapOf("light.desk" to "off", "switch.fan" to "on"),
            current = mapOf("light.desk" to "on", "switch.fan" to "on"),
            widgetsByEntity = widgetsByEntity,
        )
        assertThat(ids).containsExactly(1)
    }

    @Test fun `an entity bound to several widgets repaints all of them`() {
        val ids = changedWidgetIds(
            previous = mapOf("switch.fan" to "on"),
            current = mapOf("switch.fan" to "off"),
            widgetsByEntity = widgetsByEntity,
        )
        assertThat(ids).containsExactly(2, 3)
    }

    @Test fun `no change means no repaint`() {
        val state = mapOf("light.desk" to "on", "sensor.temp" to "21.5")
        assertThat(changedWidgetIds(state, state, widgetsByEntity)).isEmpty()
    }

    @Test fun `an entity disappearing counts as changed`() {
        val ids = changedWidgetIds(
            previous = mapOf("sensor.temp" to "21.5"),
            current = emptyMap(),
            widgetsByEntity = widgetsByEntity,
        )
        assertThat(ids).containsExactly(4)
    }

    @Test fun `unbound entities never produce widget ids`() {
        val ids = changedWidgetIds(
            previous = mapOf("light.unbound" to "off"),
            current = mapOf("light.unbound" to "on"),
            widgetsByEntity = widgetsByEntity,
        )
        assertThat(ids).isEmpty()
    }
}
