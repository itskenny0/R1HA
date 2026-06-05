package com.github.itskenny0.r1ha.feature.moreinfo

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Locks in [formatAttributeValue]'s single-line rendering: thousands-grouping of large
 * numbers (so the attributes list matches the sensor cards), precision preservation for
 * decimals / IDs / timestamps, list and object collapsing, and null/blank handling.
 */
class MoreInfoStateTest {
    @Test fun `large bare numbers get thousands separators`() {
        assertThat(formatAttributeValue(JsonPrimitive("1234567"))).isEqualTo("1,234,567")
        assertThat(formatAttributeValue(JsonPrimitive(98765))).isEqualTo("98,765")
    }

    @Test fun `grouping preserves decimals without rounding`() {
        // latitude-style precision must survive untouched; only the integer part groups.
        assertThat(formatAttributeValue(JsonPrimitive("37.7749295"))).isEqualTo("37.7749295")
        assertThat(formatAttributeValue(JsonPrimitive("123456.789"))).isEqualTo("123,456.789")
    }

    @Test fun `small numbers and four-digit values are untouched`() {
        assertThat(formatAttributeValue(JsonPrimitive("42"))).isEqualTo("42")
        assertThat(formatAttributeValue(JsonPrimitive("2026"))).isEqualTo("2026")
    }

    @Test fun `non-numeric strings pass through unchanged`() {
        assertThat(formatAttributeValue(JsonPrimitive("Heating"))).isEqualTo("Heating")
        assertThat(formatAttributeValue(JsonPrimitive("2026-06-05T12:00:00+00:00")))
            .isEqualTo("2026-06-05T12:00:00+00:00")
        assertThat(formatAttributeValue(JsonPrimitive("AA:BB:CC:DD:EE:FF")))
            .isEqualTo("AA:BB:CC:DD:EE:FF")
    }

    @Test fun `null and blank collapse to a dash`() {
        assertThat(formatAttributeValue(JsonNull)).isEqualTo("—")
        assertThat(formatAttributeValue(JsonPrimitive(""))).isEqualTo("—")
    }

    @Test fun `primitive arrays group their numeric elements`() {
        val arr = JsonArray(listOf(JsonPrimitive(255), JsonPrimitive(0), JsonPrimitive(0)))
        assertThat(formatAttributeValue(arr)).isEqualTo("255, 0, 0")
        val big = JsonArray(listOf(JsonPrimitive("100000"), JsonPrimitive("200000")))
        assertThat(formatAttributeValue(big)).isEqualTo("100,000, 200,000")
    }

    @Test fun `empty and nested collections collapse compactly`() {
        assertThat(formatAttributeValue(JsonArray(emptyList()))).isEqualTo("[]")
        assertThat(formatAttributeValue(JsonObject(emptyMap()))).isEqualTo("{}")
        val nested = JsonArray(listOf(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2)))))
        assertThat(formatAttributeValue(nested)).isEqualTo("[2]")
    }
}
