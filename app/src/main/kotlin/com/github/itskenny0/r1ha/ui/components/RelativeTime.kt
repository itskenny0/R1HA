package com.github.itskenny0.r1ha.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import java.time.Instant
import kotlin.math.abs

/**
 * Format an [Instant] as a short human-readable relative-time string. Past instants read
 * 'just now', '5m ago', '12h ago', '3d ago'; future instants read 'in 5m', 'in 2h', 'in 3d'.
 * Drives both the freshness label on cards (state age, always past) and forward-looking
 * labels like the sun card's next rise/set, a running timer's finish, and the next calendar
 * event (all future) — which previously all collapsed to 'just now' because the future side
 * was clamped.
 *
 * Magnitude buckets (same in both directions, sign decides the prefix/suffix):
 *  * < 30 s → 'just now'
 *  * < 60 s → '<seconds>s'
 *  * < 60 min → '<minutes>m'
 *  * < 24 h → '<hours>h'
 *  * < 7 d → '<days>d'
 *  * < 30 d → '<weeks>w'
 *  * < 365 d → '<months>mo'
 *  * older → '<years>y'
 *
 * Months use 'mo' (not 'm', which is already minutes) and approximate a month as 30 days /
 * a year as 365 days — these are coarse labels, not a calendar, so the rounding is fine. The
 * cap used to be weeks, which turned a long-stale reading (an automation that last ran months
 * ago, a sensor unavailable for a season) into an unreadable '13w ago'.
 */
internal fun formatRelativeTime(at: Instant, now: Instant): String {
    val deltaMs = now.toEpochMilli() - at.toEpochMilli()
    val past = deltaMs >= 0
    val sec = abs(deltaMs) / 1000
    if (sec < 30) return "just now"
    val mag = when {
        sec < 60 -> "${sec}s"
        sec < 3600 -> "${sec / 60}m"
        sec < 86_400 -> "${sec / 3600}h"
        sec < 7 * 86_400 -> "${sec / 86_400}d"
        sec < 30 * 86_400 -> "${sec / (7 * 86_400)}w"
        sec < 365 * 86_400 -> "${sec / (30 * 86_400)}mo"
        else -> "${sec / (365 * 86_400)}y"
    }
    return if (past) "$mag ago" else "in $mag"
}

/**
 * Live-ticking relative-time label backed by [produceState]. Subscribers
 * automatically recompose when the rendered string would change; between
 * boundary crossings the state stays put. Tick cadence scales with the
 * elapsed time so we don't burn frames recomposing 'just now' every
 * second indefinitely:
 *
 *  * < 60 s old → tick every 5 s
 *  * < 60 min old → tick every 30 s
 *  * < 24 h old → tick every 10 min
 *  * older → tick hourly
 *
 * Returns "" when [at] is null so callers can render unconditionally with
 * `Text(text = rememberRelativeTime(at))` and have the label silently
 * disappear for entities that haven't been observed yet.
 */
@Composable
fun rememberRelativeTime(at: Instant?): String {
    if (at == null) return ""
    // Defensive: an Instant from a malformed HA timestamp (or one populated
    // by a rehydrated persister with a placeholder epoch) could in theory
    // overflow toEpochMilli(). Wrap in runCatching so any arithmetic
    // problem renders an empty string rather than crashing the whole
    // composable tree. Caller renders unconditionally with `if (rel
    // .isNotEmpty())` so an empty string just hides the label.
    val initial = runCatching { formatRelativeTime(at, Instant.now()) }.getOrDefault("")
    val text by produceState(initialValue = initial, at) {
        while (true) {
            val r = runCatching {
                val now = Instant.now()
                val s = formatRelativeTime(at, now)
                val ageSec = abs(now.toEpochMilli() - at.toEpochMilli()) / 1000
                val nextTickMs = when {
                    ageSec < 60 -> 5_000L
                    ageSec < 3600 -> 30_000L
                    ageSec < 86_400 -> 600_000L
                    else -> 3_600_000L
                }
                s to nextTickMs
            }.getOrNull()
            if (r == null) {
                value = ""
                return@produceState
            }
            value = r.first
            delay(r.second)
        }
    }
    return text
}

/**
 * Localised relative-time label. Renders nothing when [at] is null. The
 * point of bundling this into its own composable (instead of letting
 * callers do `Text(text = rememberRelativeTime(at))`) is to confine the
 * State read to a single small composable — when the ticker emits, only
 * the surrounding [RelativeTimeLabel] re-runs, not the whole card body
 * that uses it. With many cards alive (HorizontalPager peek + a
 * VerticalPager full of cards), this was recomposing the entire deck on
 * every 5 s tick.
 *
 * Pass [color], [style] in from the call site so the label fits each
 * theme's palette without growing a per-theme variant.
 */
@Composable
fun RelativeTimeLabel(
    at: Instant?,
    color: androidx.compose.ui.graphics.Color,
    style: androidx.compose.ui.text.TextStyle,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
) {
    val rel = rememberRelativeTime(at)
    if (rel.isNotEmpty()) {
        androidx.compose.material3.Text(
            text = rel,
            style = style,
            color = color,
            modifier = modifier,
        )
    }
}
