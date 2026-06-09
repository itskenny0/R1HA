package com.github.itskenny0.r1ha.ui.components

import java.util.Locale

/**
 * Render a raw HA state string for display. If the string parses as a number, round to
 * [maxDecimals] decimal places and strip trailing zeros so "21.74321" → "21.74", "21.70"
 * → "21.7", and "21.00" → "21". Non-numeric states (e.g. an enum sensor reporting
 * "Heating" or a binary sensor reporting "on") pass through unchanged, except HA's
 * non-value sentinels "unknown" / "unavailable" render as a dash rather than the literal
 * lowercase word.
 *
 * A value that rounds to zero from below (e.g. -0.002 at two decimals, or -0.4 at zero)
 * is emitted as "0", not "-0": the "%f" formatter keeps the sign even when the magnitude
 * rounds away, and a "-0" readout on a sensor hovering just under zero reads as a glitch.
 *
 * Locale-pinned to US so we always parse and emit dot-separated decimals; HA's REST
 * surface uses dot separators regardless of the server's display locale, and the R1
 * device locale isn't always reliable.
 *
 * [maxDecimals] is the user's configurable cap (global UiOptions setting + future
 * per-card override). Clamped to [0, 6] — beyond 6 the rounding doesn't add information,
 * it just makes the readout fight for screen space.
 */
fun formatSensorValue(raw: String?, maxDecimals: Int = 2): String {
    if (raw.isNullOrBlank()) return "—"
    val trimmedRaw = raw.trim()
    if (trimmedRaw.equals("unknown", ignoreCase = true) ||
        trimmedRaw.equals("unavailable", ignoreCase = true)
    ) {
        return "—"
    }
    val num = raw.toDoubleOrNull() ?: return raw
    // Filter out NaN / Infinity — those should never come from HA but we'd rather show a
    // dash than the literal "NaN" string in a numeric readout.
    if (num.isNaN() || num.isInfinite()) return "—"
    val places = maxDecimals.coerceIn(0, 6)
    val rounded = "%.${places}f".format(Locale.US, num)
    val trimmed = if (places == 0) rounded else rounded.trimEnd('0').trimEnd('.')
    // Drop a leading minus when the rounded magnitude is zero, so "-0" / "-0.0" → "0".
    val signed = if (trimmed.startsWith("-") && trimmed.drop(1).all { it == '0' || it == '.' }) {
        trimmed.drop(1)
    } else {
        trimmed
    }
    return groupThousands(signed)
}

/**
 * Insert thousands separators into the integer part of an already-formatted numeric string
 * so a large measurement ("1234567" → "1,234,567") is legible on the R1's narrow readout,
 * matching HA's own frontend. Only kicks in at 5+ integer digits, so four-digit values that
 * are usually years or short codes ("2026") stay untouched. The sign and any decimal part
 * are preserved; a non-numeric integer part (shouldn't happen post-format) is returned as-is.
 */
/**
 * Format [value] to [decimals] fixed decimal places with a dot separator regardless of
 * the device locale. The platform `"%.1f".format(x)` honours the default locale, so on a
 * comma-decimal device (de, fr, ...) it emits "21,5" — which clashes with HA's dot-based
 * numbers and the rest of this app's US-pinned readouts. Pin US here so every fixed-decimal
 * readout (meter tick labels, helper values, ...) stays consistent.
 */
internal fun formatFixed(value: Double, decimals: Int): String =
    "%.${decimals.coerceAtLeast(0)}f".format(Locale.US, value)

/**
 * Apply HA's `display_precision` / `suggested_display_precision` rounding to a raw
 * sensor state string. When [precision] is non-null and [raw] parses as a finite number,
 * the value is rounded to exactly [precision] decimal places using [groupThousands] for
 * thousands-separator formatting (matching [formatSensorValue]'s output style). Non-
 * numeric states, HA sentinels (unknown / unavailable), and a null [precision] all pass
 * through to [formatSensorValue] unchanged so the existing rounding / dash logic applies.
 *
 * Locale-pinned to US (dot separator) for the same reasons as [formatFixed].
 */
fun formatWithPrecision(raw: String?, precision: Int?): String {
    if (precision == null) return formatSensorValue(raw)
    if (raw.isNullOrBlank()) return "—"
    val trimmed = raw.trim()
    if (trimmed.equals("unknown", ignoreCase = true) ||
        trimmed.equals("unavailable", ignoreCase = true)
    ) return "—"
    val num = trimmed.toDoubleOrNull() ?: return raw
    if (num.isNaN() || num.isInfinite()) return "—"
    val places = precision.coerceIn(0, 6)
    val rounded = String.format(Locale.US, "%.${places}f", num)
    val trimmedZeros = if (places == 0) rounded else rounded.trimEnd('0').trimEnd('.')
    val signed = if (trimmedZeros.startsWith("-") &&
        trimmedZeros.drop(1).all { it == '0' || it == '.' }
    ) trimmedZeros.drop(1) else trimmedZeros
    return groupThousands(signed)
}

internal fun groupThousands(s: String): String {
    val neg = s.startsWith("-")
    val body = if (neg) s.substring(1) else s
    val dot = body.indexOf('.')
    val intPart = if (dot >= 0) body.substring(0, dot) else body
    val fracPart = if (dot >= 0) body.substring(dot) else ""
    if (intPart.length < 5 || !intPart.all { it.isDigit() }) return s
    val grouped = intPart.reversed().chunked(3).joinToString(",").reversed()
    return (if (neg) "-" else "") + grouped + fracPart
}
