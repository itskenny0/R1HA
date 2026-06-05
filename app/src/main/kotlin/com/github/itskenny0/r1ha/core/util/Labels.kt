package com.github.itskenny0.r1ha.core.util

import java.util.Locale

/**
 * Display label for an HA area name on a card / chip: underscores read as spaces and the
 * whole thing upper-cases ("living_room" -> "LIVING ROOM"). Pinned to US so an area with an
 * 'i' upper-cases to an ASCII "I" rather than a dotted "İ" on Turkish / Azeri locales, and
 * so the half-dozen card renderers that show the area chip all format it identically.
 */
fun areaLabel(raw: String): String = raw.replace('_', ' ').uppercase(Locale.US)

/**
 * Chip label for an HA option / mode / effect id: underscores read as spaces and the whole
 * thing upper-cases, so "color_loop" -> "COLOR LOOP" and "eco_mode" -> "ECO MODE" scan as
 * words rather than code. Pinned to US so an 'i' stays ASCII on Turkish / Azeri locales. The
 * raw id is still what the caller's service call dispatches; this is display-only. Shared by
 * the select / effect chips and the climate / fan control panels so they all read the same.
 */
fun optionLabel(raw: String): String = raw.replace('_', ' ').uppercase(Locale.US)
