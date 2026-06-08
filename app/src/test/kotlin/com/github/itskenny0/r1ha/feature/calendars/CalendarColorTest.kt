package com.github.itskenny0.r1ha.feature.calendars

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class CalendarColorTest {
    private fun attrs(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

    @Test fun `reads a color attribute when present`() {
        assertThat(calendarColorOf(attrs("""{"color":"#ff8800"}"""))).isEqualTo("#ff8800")
    }

    @Test fun `returns null when no color attribute`() {
        assertThat(calendarColorOf(attrs("""{"message":"Meeting"}"""))).isNull()
    }
}
