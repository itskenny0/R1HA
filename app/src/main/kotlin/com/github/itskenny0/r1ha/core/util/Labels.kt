package com.github.itskenny0.r1ha.core.util

import java.util.Locale

/**
 * Display label for an HA area name on a card / chip: underscores read as spaces and the
 * whole thing upper-cases ("living_room" -> "LIVING ROOM"). Pinned to US so an area with an
 * 'i' upper-cases to an ASCII "I" rather than a dotted "İ" on Turkish / Azeri locales, and
 * so the half-dozen card renderers that show the area chip all format it identically.
 */
fun areaLabel(raw: String): String = raw.replace('_', ' ').uppercase(Locale.US)
