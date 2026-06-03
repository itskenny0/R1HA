package com.github.itskenny0.r1ha.feature.helpers

import java.util.Locale

/**
 * Pure, side-effect-free helpers backing the Helpers surface.
 *
 * Everything here is a plain function of its inputs (no Compose, no
 * coroutines, no repository) so the per-domain control selection, value
 * clamping, and search-filter rules can be unit-tested without standing up
 * a ViewModel. The screen and the ViewModel delegate to these so the rules
 * live in exactly one place.
 */
object HelpersLogic {

    /** Map an entity_id's domain prefix to the render [HelpersViewModel.Kind].
     *  Covers both the helper twins (`input_*`) and the general HA domains
     *  (`select`, `button`) that share the same service shape, so a `select.*`
     *  or `button.*` entity surfaced on this screen gets the right control. */
    fun kindForDomain(domain: String): HelpersViewModel.Kind = when (domain) {
        "input_boolean" -> HelpersViewModel.Kind.BOOLEAN
        "input_number", "number" -> HelpersViewModel.Kind.NUMBER
        "counter" -> HelpersViewModel.Kind.COUNTER
        "input_select", "select" -> HelpersViewModel.Kind.SELECT
        "input_text" -> HelpersViewModel.Kind.TEXT
        "input_datetime" -> HelpersViewModel.Kind.DATETIME
        "input_button", "button" -> HelpersViewModel.Kind.BUTTON
        "timer" -> HelpersViewModel.Kind.TIMER
        else -> HelpersViewModel.Kind.UNKNOWN
    }

    /**
     * Clamp a candidate number value into the entity's [min]..[max] window and
     * snap it onto the nearest [step] offset from [min] (HA's input_number only
     * accepts values on the step grid). Null bounds are treated as open. A
     * non-positive or null step disables snapping. The result is always inside
     * the bounds even after snapping (snapping never pushes past [max]).
     */
    fun clampNumber(
        value: Double,
        min: Double?,
        max: Double?,
        step: Double?,
    ): Double {
        val lo = min ?: Double.NEGATIVE_INFINITY
        val hi = max ?: Double.POSITIVE_INFINITY
        var v = value.coerceIn(lo, hi)
        if (step != null && step > 0.0 && min != null) {
            val steps = Math.round((v - min) / step).toDouble()
            v = min + steps * step
            // Re-clamp: rounding up near the ceiling could overshoot max.
            v = v.coerceIn(lo, hi)
        }
        return v
    }

    /** One detent of the wheel / one tap of the stepper. [up] true = +step. */
    fun stepNumber(
        current: Double,
        up: Boolean,
        min: Double?,
        max: Double?,
        step: Double?,
    ): Double {
        val s = (step?.takeIf { it > 0.0 }) ?: 1.0
        return clampNumber(current + (if (up) s else -s), min, max, step)
    }

    /**
     * Clamp free-form text to an input_text helper's configured length window.
     * HA defaults are 0..100; we mirror those when [min]/[max] are absent.
     * Over-long input is truncated; the caller decides whether short input is a
     * hard error (HA rejects below `min`).
     */
    fun clampText(value: String, min: Int?, max: Int?): String {
        val hi = (max ?: 100).coerceAtLeast(0)
        return if (value.length > hi) value.substring(0, hi) else value
    }

    /** True when text satisfies the helper's `min` length (HA rejects shorter). */
    fun textMeetsMinLength(value: String, min: Int?): Boolean =
        value.length >= (min ?: 0)

    /**
     * Filter predicate shared by the list view. Case-insensitive substring
     * match against the friendly name and the entity_id. Blank query matches
     * everything. [Locale.US] keeps lowercasing deterministic across devices.
     */
    fun matchesQuery(name: String, entityId: String, query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase(Locale.US)
        return name.lowercase(Locale.US).contains(q) ||
            entityId.lowercase(Locale.US).contains(q)
    }

    /**
     * Next option index when cycling an input_select / select. Wraps both ways.
     * Returns 0 for an empty list so callers can guard before dispatching.
     */
    fun cycleSelectIndex(currentIndex: Int, size: Int, forward: Boolean): Int {
        if (size <= 0) return 0
        val cur = currentIndex.coerceIn(0, size - 1)
        return if (forward) (cur + 1) % size else (cur - 1 + size) % size
    }

    /**
     * Pick the option index to advance / step back FROM, given the helper's
     * current [state]. When the state is one of the helper's [options] we cycle
     * relative to it. When it isn't (a fresh helper reports `unknown`, or the
     * options were edited out from under the state), [options.indexOf] returns
     * -1 and the old `coerceAtLeast(0)` quietly skipped `options[0]` on the
     * first forward tap and mislabelled the position counter. Returning -1 here
     * lets the caller treat "no current selection" distinctly: a forward step
     * then lands on `options[0]` and the counter can read 0 / N.
     */
    fun selectCurrentIndex(state: String, options: List<String>): Int =
        options.indexOf(state)

    /** Home Assistant's two non-actionable state sentinels. Controls should
     *  render read-only (HA disables them) rather than dispatch a service that
     *  would no-op or error against an entity that isn't reporting. */
    fun isInactiveState(state: String): Boolean =
        state.equals("unavailable", ignoreCase = true) ||
            state.equals("unknown", ignoreCase = true) ||
            state.isBlank()

    /** Mask a value for display when an input_text helper is in `password`
     *  mode (HA renders these as a password field). Length-preserving so the
     *  row still hints at "set vs empty" without leaking the secret. */
    fun maskText(value: String): String =
        if (value.isEmpty()) value else "•".repeat(value.length.coerceAtMost(32))

    /**
     * Split an input_datetime state string into the `date` / `time` fields HA's
     * `set_datetime` service expects. HA states look like:
     *   - date-only:     "2024-01-15"
     *   - time-only:     "14:30:00"
     *   - date + time:   "2024-01-15 14:30:00"
     * Returns (date, time); either may be null when the helper doesn't carry
     * that component. [hasDate]/[hasTime] come from the entity attributes and
     * take precedence over best-effort parsing of the state.
     */
    fun splitDateTimeState(
        state: String,
        hasDate: Boolean,
        hasTime: Boolean,
    ): Pair<String?, String?> {
        val parts = state.trim().split(' ', 'T').filter { it.isNotBlank() }
        var date: String? = null
        var time: String? = null
        for (p in parts) {
            when {
                p.contains('-') && date == null -> date = p
                p.contains(':') && time == null -> time = normaliseTime(p)
            }
        }
        return Pair(
            if (hasDate) date else null,
            if (hasTime) time else null,
        )
    }

    /** Normalise a time fragment to HH:MM:SS (HA accepts HH:MM but stores seconds). */
    fun normaliseTime(raw: String): String {
        val seg = raw.split(':')
        val h = seg.getOrNull(0)?.padStart(2, '0') ?: "00"
        val m = seg.getOrNull(1)?.padStart(2, '0') ?: "00"
        val s = seg.getOrNull(2)?.padStart(2, '0') ?: "00"
        return "$h:$m:$s"
    }

    /** True when a date string is a plausible YYYY-MM-DD. Lightweight: the HA
     *  backend is the real validator, this just blocks obvious garbage before
     *  we spend a round-trip. */
    fun isValidDate(date: String): Boolean {
        val m = Regex("""^(\d{4})-(\d{2})-(\d{2})$""").matchEntire(date.trim()) ?: return false
        val (yyyy, mm, dd) = m.destructured
        val year = yyyy.toInt()
        val month = mm.toInt()
        val day = dd.toInt()
        if (month !in 1..12 || day < 1) return false
        // Day-of-month must fit the actual month, accounting for leap years
        // (Feb 29 only on leap years). HA rejects e.g. 2023-02-29 outright, so
        // catch it here before spending a round-trip.
        val daysInMonth = when (month) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
            else -> return false
        }
        return day <= daysInMonth
    }

    /** True when a time string is a plausible HH:MM or HH:MM:SS. */
    fun isValidTime(time: String): Boolean {
        val m = Regex("""^(\d{1,2}):(\d{2})(?::(\d{2}))?$""").matchEntire(time.trim()) ?: return false
        val h = m.groupValues[1].toInt()
        val mins = m.groupValues[2].toInt()
        val secs = m.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
        return h in 0..23 && mins in 0..59 && secs in 0..59
    }
}
