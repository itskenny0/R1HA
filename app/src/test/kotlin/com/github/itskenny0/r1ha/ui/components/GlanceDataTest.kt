package com.github.itskenny0.r1ha.ui.components

import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.prefs.SecondaryInfo
import com.github.itskenny0.r1ha.core.prefs.TimestampStyle
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Unit tests for [computeTrend] and [secondaryInfoText]. All clock inputs are fixed
 * constants so results are deterministic regardless of wall-clock time.
 */
class GlanceDataTest {

    // -------------------------------------------------------------------------
    // computeTrend
    // -------------------------------------------------------------------------

    @Test fun `computeTrend rising series returns UP with positive delta text`() {
        val result = computeTrend(listOf(1f, 2f, 5f), unit = "°C", decimals = 1)
        assertThat(result.arrow).isEqualTo(TrendArrow.UP)
        assertThat(result.deltaText).isEqualTo("+4.0 °C")
    }

    @Test fun `computeTrend falling series returns DOWN with negative delta text`() {
        val result = computeTrend(listOf(10f, 8f, 7f), unit = "%", decimals = 0)
        assertThat(result.arrow).isEqualTo(TrendArrow.DOWN)
        assertThat(result.deltaText).isEqualTo("-3 %")
    }

    @Test fun `computeTrend equal points returns FLAT with empty delta text`() {
        val result = computeTrend(listOf(5f, 5f), unit = "°C", decimals = 1)
        assertThat(result.arrow).isEqualTo(TrendArrow.FLAT)
        assertThat(result.deltaText).isEmpty()
    }

    @Test fun `computeTrend empty list returns FLAT with empty delta text`() {
        val result = computeTrend(emptyList(), unit = "°C", decimals = 1)
        assertThat(result.arrow).isEqualTo(TrendArrow.FLAT)
        assertThat(result.deltaText).isEmpty()
    }

    @Test fun `computeTrend single point returns FLAT with empty delta text`() {
        val result = computeTrend(listOf(3f), unit = "W", decimals = 0)
        assertThat(result.arrow).isEqualTo(TrendArrow.FLAT)
        assertThat(result.deltaText).isEmpty()
    }

    @Test fun `computeTrend null unit produces no trailing space`() {
        val result = computeTrend(listOf(0f, 2.5f), unit = null, decimals = 1)
        assertThat(result.arrow).isEqualTo(TrendArrow.UP)
        assertThat(result.deltaText).isEqualTo("+2.5")
        // No unit, so no space at end.
        assertThat(result.deltaText).doesNotContain(" ")
    }

    @Test fun `computeTrend blank unit produces no trailing space`() {
        val result = computeTrend(listOf(0f, 3f), unit = "", decimals = 0)
        assertThat(result.arrow).isEqualTo(TrendArrow.UP)
        assertThat(result.deltaText).isEqualTo("+3")
        assertThat(result.deltaText).doesNotContain(" ")
    }

    @Test fun `computeTrend decimals param controls decimal places`() {
        // 4.00f delta, 2 decimal places, with unit.
        val result = computeTrend(listOf(1f, 5f), unit = "W", decimals = 2)
        assertThat(result.arrow).isEqualTo(TrendArrow.UP)
        assertThat(result.deltaText).isEqualTo("+4.00 W")
    }

    @Test fun `computeTrend uses Locale US decimal separator`() {
        // 1.5f delta: must always render with "." not "," regardless of device locale.
        val result = computeTrend(listOf(0f, 1.5f), unit = null, decimals = 1)
        assertThat(result.deltaText).contains(".")
        assertThat(result.deltaText).doesNotContain(",")
    }

    // -------------------------------------------------------------------------
    // secondaryInfoText helpers
    // -------------------------------------------------------------------------

    private val fixedNow: Instant = Instant.parse("2026-06-26T12:00:00Z")

    private fun makeState(
        lastChanged: Instant = Instant.parse("2026-06-26T10:00:00Z"),
        lastTriggered: Instant? = null,
        mediaTitle: String? = null,
        mediaArtist: String? = null,
        deviceClass: String? = null,
        percent: Int? = null,
        raw: Number? = null,
        attributesJson: JsonObject? = null,
    ): EntityState = EntityState(
        id = EntityId("sensor.test"),
        friendlyName = "Test",
        area = null,
        isOn = true,
        percent = percent,
        raw = raw,
        lastChanged = lastChanged,
        lastTriggered = lastTriggered,
        isAvailable = true,
        deviceClass = deviceClass,
        mediaTitle = mediaTitle,
        mediaArtist = mediaArtist,
        attributesJson = attributesJson,
    )

    // -------------------------------------------------------------------------
    // secondaryInfoText - NONE
    // -------------------------------------------------------------------------

    @Test fun `secondaryInfoText NONE always returns null`() {
        val result = secondaryInfoText(makeState(), SecondaryInfo.NONE, fixedNow, TimestampStyle.RELATIVE)
        assertThat(result).isNull()
    }

    // -------------------------------------------------------------------------
    // secondaryInfoText - LAST_CHANGED
    // -------------------------------------------------------------------------

    @Test fun `secondaryInfoText LAST_CHANGED RELATIVE returns relative string`() {
        // 5 minutes before fixedNow.
        val changed = Instant.parse("2026-06-26T11:55:00Z")
        val result = secondaryInfoText(
            makeState(lastChanged = changed),
            SecondaryInfo.LAST_CHANGED,
            fixedNow,
            TimestampStyle.RELATIVE,
        )
        assertThat(result).isEqualTo("5m ago")
    }

    @Test fun `secondaryInfoText LAST_CHANGED ABSOLUTE returns wall-clock string`() {
        // Same local date as fixedNow in UTC, so HH:mm format applies.
        val changed = Instant.parse("2026-06-26T10:30:00Z")
        val result = secondaryInfoText(
            makeState(lastChanged = changed),
            SecondaryInfo.LAST_CHANGED,
            fixedNow,
            TimestampStyle.ABSOLUTE,
            zone = ZoneId.of("UTC"),
            use24h = true,
        )
        assertThat(result).isEqualTo("10:30")
    }

    // -------------------------------------------------------------------------
    // secondaryInfoText - LAST_TRIGGERED
    // -------------------------------------------------------------------------

    @Test fun `secondaryInfoText LAST_TRIGGERED absent returns null`() {
        val result = secondaryInfoText(
            makeState(lastTriggered = null),
            SecondaryInfo.LAST_TRIGGERED,
            fixedNow,
            TimestampStyle.RELATIVE,
        )
        assertThat(result).isNull()
    }

    @Test fun `secondaryInfoText LAST_TRIGGERED present returns relative string`() {
        // 3 hours before fixedNow.
        val triggered = Instant.parse("2026-06-26T09:00:00Z")
        val result = secondaryInfoText(
            makeState(lastTriggered = triggered),
            SecondaryInfo.LAST_TRIGGERED,
            fixedNow,
            TimestampStyle.RELATIVE,
        )
        assertThat(result).isEqualTo("3h ago")
    }

    // -------------------------------------------------------------------------
    // secondaryInfoText - CHANGED_BY
    // -------------------------------------------------------------------------

    @Test fun `secondaryInfoText CHANGED_BY present returns attribute value`() {
        val json = JsonObject(mapOf("changed_by" to JsonPrimitive("alice")))
        val result = secondaryInfoText(
            makeState(attributesJson = json),
            SecondaryInfo.CHANGED_BY,
            fixedNow,
            TimestampStyle.RELATIVE,
        )
        assertThat(result).isEqualTo("alice")
    }

    @Test fun `secondaryInfoText CHANGED_BY absent returns null`() {
        val result = secondaryInfoText(
            makeState(attributesJson = null),
            SecondaryInfo.CHANGED_BY,
            fixedNow,
            TimestampStyle.RELATIVE,
        )
        assertThat(result).isNull()
    }

    @Test fun `secondaryInfoText CHANGED_BY blank returns null`() {
        val json = JsonObject(mapOf("changed_by" to JsonPrimitive("   ")))
        val result = secondaryInfoText(
            makeState(attributesJson = json),
            SecondaryInfo.CHANGED_BY,
            fixedNow,
            TimestampStyle.RELATIVE,
        )
        assertThat(result).isNull()
    }

    // -------------------------------------------------------------------------
    // secondaryInfoText - BATTERY
    // -------------------------------------------------------------------------

    @Test fun `secondaryInfoText BATTERY reads battery_level attribute`() {
        val json = JsonObject(mapOf("battery_level" to JsonPrimitive("85")))
        val result = secondaryInfoText(
            makeState(attributesJson = json),
            SecondaryInfo.BATTERY,
            fixedNow,
            TimestampStyle.RELATIVE,
        )
        assertThat(result).isEqualTo("85%")
    }

    @Test fun `secondaryInfoText BATTERY falls back to percent for battery device class`() {
        val result = secondaryInfoText(
            makeState(deviceClass = "battery", percent = 42, attributesJson = null),
            SecondaryInfo.BATTERY,
            fixedNow,
            TimestampStyle.RELATIVE,
        )
        assertThat(result).isEqualTo("42%")
    }

    @Test fun `secondaryInfoText BATTERY absent on non-battery device class returns null`() {
        // percent is present but device_class is not "battery": must not fall back.
        val result = secondaryInfoText(
            makeState(deviceClass = "temperature", percent = 21, attributesJson = null),
            SecondaryInfo.BATTERY,
            fixedNow,
            TimestampStyle.RELATIVE,
        )
        assertThat(result).isNull()
    }

    @Test fun `secondaryInfoText BATTERY attr value is decimal and converts to int`() {
        val json = JsonObject(mapOf("battery_level" to JsonPrimitive("72.9")))
        val result = secondaryInfoText(
            makeState(attributesJson = json),
            SecondaryInfo.BATTERY,
            fixedNow,
            TimestampStyle.RELATIVE,
        )
        // toDoubleOrNull().toInt() truncates toward zero.
        assertThat(result).isEqualTo("72%")
    }

    // -------------------------------------------------------------------------
    // secondaryInfoText - MEDIA
    // -------------------------------------------------------------------------

    @Test fun `secondaryInfoText MEDIA joins artist and title with middle dot`() {
        val result = secondaryInfoText(
            makeState(mediaArtist = "Artist", mediaTitle = "Song"),
            SecondaryInfo.MEDIA,
            fixedNow,
            TimestampStyle.RELATIVE,
        )
        assertThat(result).isEqualTo("Artist · Song")
    }

    @Test fun `secondaryInfoText MEDIA separator is not an em dash`() {
        val result = secondaryInfoText(
            makeState(mediaArtist = "A", mediaTitle = "B"),
            SecondaryInfo.MEDIA,
            fixedNow,
            TimestampStyle.RELATIVE,
        )!!
        assertThat(result).doesNotContain("—") // em dash U+2014
        assertThat(result).contains("·")       // middle dot U+00B7
    }

    @Test fun `secondaryInfoText MEDIA title only`() {
        val result = secondaryInfoText(
            makeState(mediaTitle = "Track Only"),
            SecondaryInfo.MEDIA,
            fixedNow,
            TimestampStyle.RELATIVE,
        )
        assertThat(result).isEqualTo("Track Only")
    }

    @Test fun `secondaryInfoText MEDIA artist only`() {
        val result = secondaryInfoText(
            makeState(mediaArtist = "Band Name"),
            SecondaryInfo.MEDIA,
            fixedNow,
            TimestampStyle.RELATIVE,
        )
        assertThat(result).isEqualTo("Band Name")
    }

    @Test fun `secondaryInfoText MEDIA both null returns null`() {
        val result = secondaryInfoText(
            makeState(mediaArtist = null, mediaTitle = null),
            SecondaryInfo.MEDIA,
            fixedNow,
            TimestampStyle.RELATIVE,
        )
        assertThat(result).isNull()
    }

    @Test fun `secondaryInfoText MEDIA blank fields are treated as absent`() {
        val result = secondaryInfoText(
            makeState(mediaArtist = "  ", mediaTitle = ""),
            SecondaryInfo.MEDIA,
            fixedNow,
            TimestampStyle.RELATIVE,
        )
        assertThat(result).isNull()
    }
}
