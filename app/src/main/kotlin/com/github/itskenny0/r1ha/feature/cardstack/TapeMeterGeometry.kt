package com.github.itskenny0.r1ha.feature.cardstack

import kotlin.math.roundToInt

/**
 * Pure layout/value math for the card tape meters (vertical + horizontal). Lifted out
 * of the composables so the index↔percent mapping the tick labels rely on can be unit
 * tested directly: a regression here silently sends the wrong setpoint when a user taps
 * a tick label, which is invisible until someone notices their thermostat jumped to the
 * wrong temperature.
 *
 * Rendering-only helper: it never touches the value-bar data model or any persisted
 * preference, it just turns a tick index into the 0..100 percent the setter expects.
 */
internal object TapeMeterGeometry {
    /**
     * Percent (0..100) for the tick at [idx] in a vertical meter whose label list is
     * top→bottom (index 0 = top = highest value). The top tick maps to 100, the bottom
     * to 0, evenly spaced in between. Single-element (or empty) lists collapse to 100 so
     * a degenerate meter still has a sensible jump target.
     *
     * This is the inverse of [com.github.itskenny0.r1ha.ui.components] meter-label
     * generation, which lays the labels out at fractions 1.0, 0.75 … 0.0 of the
     * entity's min..max. Because the wheel/setter maps percent linearly onto that same
     * min..max, tapping the top label jumps to max, the bottom to min, and each middle
     * label to its own native value: with the nearest-integer rounding below the round
     * trip is exact whenever a tick's fraction lands on a whole percent, and within half
     * a percent of the label's native value otherwise. (Truncating instead, as this once
     * did, biased every fractional tick low: a count-4 meter's 66.67% tick became 66, so
     * tapping it under-shot the label it sat on.)
     */
    fun verticalTickPercent(idx: Int, count: Int): Int =
        if (count <= 1) 100
        else (100f * (count - 1 - idx) / (count - 1)).roundToInt()

    /**
     * Percent (0..100) for the tick at [idx] in a horizontal meter whose label list is
     * left→right (index 0 = left = lowest value). Left maps to 0, right to 100. This is
     * the mirror of [verticalTickPercent] because the horizontal meter reverses the
     * caller's top→bottom list into low→high left→right before rendering.
     */
    fun horizontalTickPercent(idx: Int, count: Int): Int =
        if (count <= 1) 100
        else (100f * idx / (count - 1)).roundToInt()
}
