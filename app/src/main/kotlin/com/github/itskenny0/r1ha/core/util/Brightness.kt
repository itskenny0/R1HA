package com.github.itskenny0.r1ha.core.util

import kotlin.math.roundToInt

/**
 * Scale a 0..255 brightness byte (Android's system screen-brightness setting) to a 0..100
 * percentage, rounding to the nearest percent rather than truncating. Truncation biased the
 * readout low: a near-max 254/255 showed 99% instead of 100%, and mid-points always rounded
 * down. Input is coerced into 0..255 so a manufacturer-specific out-of-range value is safe.
 */
fun brightness255ToPct(raw: Int): Int =
    (raw.coerceIn(0, 255) * 100.0 / 255.0).roundToInt()
