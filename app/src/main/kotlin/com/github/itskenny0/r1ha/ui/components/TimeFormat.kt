package com.github.itskenny0.r1ha.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.github.itskenny0.r1ha.core.prefs.ClockFormat
import com.github.itskenny0.r1ha.core.theme.LocalUiOptions
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Shared 12/24-hour clock formatting, backing the Settings → Appearance →
 * Clock format option ([com.github.itskenny0.r1ha.core.prefs.UiOptions.clockFormat]).
 *
 * Every clock-style readout the app composes itself routes through here — the
 * TODAY greeting clock, sensor-history row times, hourly forecast labels, the
 * History / Statistics / Energy chart time axes, and the absolute-timestamp
 * style — so flipping the setting changes them all consistently. Values that
 * mirror a Home Assistant server string verbatim (input_datetime raw values,
 * ISO timestamps on debug surfaces) deliberately do NOT route through here;
 * reformatting those would change what gets sent back to the server or hide
 * the exact wire value the user asked to see.
 *
 * The mapping functions are pure (inputs in, string out) so they unit-test
 * without a Compose harness; [rememberUse24HourClock] is the thin composable
 * sugar that resolves AUTO against the Android system setting.
 *
 * Locale notes: the 12-hour pattern pins Locale.US so the AM/PM marker is the
 * stable "AM"/"PM" across device locales (matching the repo-wide convention
 * of pinning locale-sensitive formatter text); the 24-hour pattern has no
 * locale-sensitive component but is pinned too for symmetry.
 */

private val H24_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
private val H12_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val DAY_H24: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.US)
private val DAY_H12: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM h:mm a", Locale.US)
private val DAY_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)

/**
 * Resolve the user's [ClockFormat] choice to a concrete 12/24-hour decision.
 * AUTO defers to [system24h] (the Android system clock setting) so existing
 * installs keep their current rendering; H12 / H24 force one style.
 */
fun use24HourClock(format: ClockFormat, system24h: Boolean): Boolean = when (format) {
    ClockFormat.AUTO -> system24h
    ClockFormat.H12 -> false
    ClockFormat.H24 -> true
}

/**
 * The `DateTimeFormatter.ofPattern` pattern for a plain time-of-day readout
 * in the resolved style — "HH:mm" or "h:mm a". Exposed as a pattern (rather
 * than only a formatter) because the chart-axis call sites build their own
 * zone-attached formatter from it.
 */
fun clockPattern(use24h: Boolean): String = if (use24h) "HH:mm" else "h:mm a"

/** Format [instant]'s local time-of-day in [zone] in the resolved style. */
fun formatClockTime(instant: Instant, zone: ZoneId, use24h: Boolean): String =
    instant.atZone(zone).format(if (use24h) H24_TIME else H12_TIME)

/**
 * Wall-clock rendering of a timestamp for the ABSOLUTE
 * [com.github.itskenny0.r1ha.core.prefs.TimestampStyle]. Buckets keep the
 * label as short as the precision still useful at that age:
 *  - same local date as [now] → time only ("14:32" / "2:32 PM"),
 *  - same year → day + time ("3 Jun 14:32"),
 *  - older / further → date only ("3 Jun 2025"); minute precision stops
 *    mattering across a year boundary and the label has to fit card chrome.
 * Future instants (sun rise/set, timer finish) bucket the same way, so an
 * upcoming sunrise renders as a plain clock time.
 */
fun formatAbsoluteTimestamp(at: Instant, now: Instant, zone: ZoneId, use24h: Boolean): String {
    val atZdt: ZonedDateTime = at.atZone(zone)
    val nowZdt: ZonedDateTime = now.atZone(zone)
    return when {
        atZdt.toLocalDate() == nowZdt.toLocalDate() ->
            atZdt.format(if (use24h) H24_TIME else H12_TIME)
        atZdt.year == nowZdt.year ->
            atZdt.format(if (use24h) DAY_H24 else DAY_H12)
        else -> atZdt.format(DAY_YEAR)
    }
}

/**
 * Composable sugar: the resolved 12/24-hour decision for the current
 * composition — the user's [ClockFormat] setting with AUTO resolved against
 * the Android system clock preference. Recomposes when the setting changes
 * (LocalUiOptions is a tracked CompositionLocal); a system-setting flip while
 * the app is alive is picked up on the next recomposition of the caller.
 */
@Composable
fun rememberUse24HourClock(): Boolean {
    val format = LocalUiOptions.current.clockFormat
    val context = LocalContext.current
    return remember(format, context) {
        use24HourClock(format, android.text.format.DateFormat.is24HourFormat(context))
    }
}
