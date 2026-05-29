package com.github.itskenny0.r1ha.feature.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the pure accessibility-description helpers extracted from the
 * Search screen. These build the spoken TalkBack strings for result rows and the
 * domain-filter chips, so locking their wording down keeps a refactor from quietly
 * regressing the screen-reader experience.
 */
internal class SearchAccessibilityTest {

    @Test
    fun `row description leads with name and ends with action verb`() {
        val desc = rowContentDescription(
            friendlyName = "Kitchen Light",
            domainPrefix = "light",
            rawState = "on",
            area = "Kitchen",
            actionLabel = "OFF",
        )
        assertTrue(desc.startsWith("Kitchen Light"))
        assertTrue(desc.contains("light"))
        assertTrue(desc.contains("on"))
        assertTrue(desc.contains("Kitchen"))
        assertTrue(desc.endsWith("Tap to turn off"))
    }

    @Test
    fun `row description maps each action label to its spoken verb`() {
        fun verb(label: String) = rowContentDescription("X", "scene", null, null, label)
            .substringAfterLast(". ")
        assertEquals("Tap to turn on", verb("ON"))
        assertEquals("Tap to turn off", verb("OFF"))
        assertEquals("Tap to fire", verb("FIRE"))
        assertEquals("Tap to press", verb("PRESS"))
        assertEquals("Tap for details", verb("INFO"))
    }

    @Test
    fun `row description omits blank state and area`() {
        val desc = rowContentDescription(
            friendlyName = "Front Door",
            domainPrefix = "lock",
            rawState = null,
            area = "",
            actionLabel = "ON",
        )
        // No stray empty segments: only name + kind precede the action sentence.
        assertEquals("Front Door, lock. Tap to turn on", desc)
    }

    @Test
    fun `row description spells underscores in the domain prefix as spaces`() {
        val desc = rowContentDescription(
            friendlyName = "Mode",
            domainPrefix = "input_boolean",
            rawState = "off",
            area = null,
            actionLabel = "ON",
        )
        assertTrue(desc.contains("input boolean"))
        assertFalse(desc.contains("input_boolean"))
    }

    @Test
    fun `chip description includes count and selection`() {
        assertEquals(
            "CONTROLS filter, 12 entities, selected",
            bucketChipContentDescription("CONTROLS", count = 12, selected = true),
        )
        assertEquals(
            "SENSORS filter, 1 entity",
            bucketChipContentDescription("SENSORS", count = 1, selected = false),
        )
    }

    @Test
    fun `chip description drops count while registry is loading`() {
        assertEquals(
            "ALL filter",
            bucketChipContentDescription("ALL", count = null, selected = false),
        )
        assertEquals(
            "ALL filter, selected",
            bucketChipContentDescription("ALL", count = null, selected = true),
        )
    }
}
