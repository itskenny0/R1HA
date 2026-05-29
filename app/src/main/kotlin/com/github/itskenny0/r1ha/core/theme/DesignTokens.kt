package com.github.itskenny0.r1ha.core.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Mission Control" design tokens. Sharp-edged industrial dashboard language: orange-on-near-
 * black, monospace numerals for readouts, uppercase letter-spaced labels for chrome, 1dp
 * hairline rules. Every screen pulls type and color from here so nothing drifts.
 *
 * Naming: by *role* (`labelMicro`, `numeralXl`, `accentWarm`) so a future palette swap
 * doesn't need to touch call sites.
 */
object R1 {

    // ── Palette ──────────────────────────────────────────────────────────────────────────
    /** Window background; matches `colors.xml/window_bg` so cold-start doesn't flash. */
    val Bg = Color(0xFF0A0A0A)
    /** One step lighter — surface for cards / inputs / dividers backgrounds. */
    val Surface = Color(0xFF141414)
    /** Two steps lighter — used for dim ticks, off-track slider rails, disabled. */
    val SurfaceMuted = Color(0xFF1F1F1F)
    /** Hairline dividers and rule strokes. */
    val Hairline = Color(0xFF2A2A2A)

    /** Primary readable text. */
    val Ink = Color(0xFFEDEDED)
    /** Secondary body — 70% over Bg roughly. */
    val InkSoft = Color(0xFFA8A8A8)
    /** Muted callouts (labels, sub-text). */
    val InkMuted = Color(0xFF6E6E6E)

    /** The R1 orange. Used sparingly — accent only. */
    val AccentWarm = Color(0xFFF36F21)
    /** Domain-cool — media players. */
    val AccentCool = Color(0xFF41BDF5)
    /** Domain-green — fans / fresh-air. */
    val AccentGreen = Color(0xFF52C77F)
    /** Domain-neutral — covers / blinds. */
    val AccentNeutral = Color(0xFFB0B0B0)

    /** Status: connecting / authenticating (amber). */
    val StatusAmber = Color(0xFFFFB300)
    /** Status: disconnected / auth-lost (red). */
    val StatusRed = Color(0xFFE53935)

    // ── Spacing scale ────────────────────────────────────────────────────────────────────
    /**
     * The single spacing ramp. Every gap, gutter, and inset on a polished R1 surface should
     * resolve to one of these so nothing drifts to an ad-hoc dp value. Steps roughly double:
     * fine internal nudges at the bottom, screen-level gutters at the top.
     *
     * Intended use:
     *  - [space.xxs] (2dp): hairline nudges; the gap between a title and its sub-label.
     *  - [space.xs]  (4dp): chip-internal vertical padding; spacing between list items.
     *  - [space.s]   (8dp): chip-internal horizontal padding; small inline gaps.
     *  - [space.m]   (12dp): row internal padding; list contentPadding gutter.
     *  - [space.l]   (16dp): comfortable block padding; button horizontal inset.
     *  - [space.xl]  (24dp): section top breathing room; screen gutter on roomy surfaces.
     *  - [space.xxl] (32dp): large empty-state / hero padding.
     *
     * The legacy "22dp gutter" some settings rows used is intentionally dropped in favour of
     * [space.xl] (24dp); sweep agents should map the old 22 to 24.
     */
    val space = Space

    object Space {
        val xxs = 2.dp
        val xs = 4.dp
        val s = 8.dp
        val m = 12.dp
        val l = 16.dp
        val xl = 24.dp
        val xxl = 32.dp
    }

    /** Minimum interactive target height. Every tappable row/control honours this. */
    val MinTarget = 48.dp

    // ── Shapes ───────────────────────────────────────────────────────────────────────────
    /** Default radius for cards & chips. Brutalist: only mild softening. */
    val ShapeS = RoundedCornerShape(2.dp)
    val ShapeM = RoundedCornerShape(4.dp)
    /** Pills (on/off) — fully round. */
    val ShapeRound = RoundedCornerShape(999.dp)

    // ── Type ramp ────────────────────────────────────────────────────────────────────────
    /**
     * One consolidated type ramp, grouped by role. Pick the role that matches the *meaning*
     * of the text, not its size, so a palette/scale tweak stays in one place:
     *
     *  Numerals (monospace readouts):
     *   - [numeralXl] big card percentage / hero readout
     *   - [numeralM]  unit suffixes, medium readouts
     *   - [numeralS]  entity IDs, tick labels, dense mono fragments
     *
     *  Chrome labels (uppercase, letter-spaced):
     *   - [sectionHeader] the title of a grouped section (via [R1Section])
     *   - [label]         a standard uppercase chip / field label
     *   - [labelMicro]    domain badges, status-pill text, the smallest callout
     *
     *  Titles:
     *   - [screenTitle] the top-bar / screen title (via [R1TopBar])
     *   - [titleCard]   a friendly-name title on a card or prominent row
     *
     *  Body:
     *   - [body]     standard reading text, secondary row text
     *   - [bodyEmph] primary row label, interactive emphasis
     */
    /** A monospace numeric readout — punchy, big. Used for the percentage on cards. */
    val numeralXl: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 72.sp,
        letterSpacing = (-2).sp,
        lineHeight = 72.sp,
    )

    /** Medium monospace numeric — used for unit suffixes and small readouts. */
    val numeralM: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
    )

    /** Small monospace — used for entity IDs, tick labels. */
    val numeralS: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp,
    )

    /** All-caps section header — letter-spaced, mid-weight. Section dividers on most screens. */
    val sectionHeader: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = 2.5.sp,
    )

    /** All-caps micro callout — used for domain badges, status pill text. */
    val labelMicro: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 9.sp,
        letterSpacing = 2.sp,
    )

    /**
     * All-caps standard label — one step up from [labelMicro] for chip text and field
     * captions that need to read at arm's length without shouting. Used by [R1Chip].
     */
    val label: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        letterSpacing = 1.5.sp,
    )

    /** Friendly-name title on the card. */
    val titleCard: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        letterSpacing = 0.sp,
    )

    /** Standard body — settings rows, info text. */
    val body: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    )

    /** Stronger body — interactive rows, primary screen titles. */
    val bodyEmph: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    )

    /** Top-bar / screen-title style — bigger than body, still sentence-case. */
    val screenTitle: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        letterSpacing = 0.sp,
    )

    /** Span used inline for monospace fragments inside body text (e.g. "PORT 8123"). */
    val monoSpan: SpanStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        textDecoration = TextDecoration.None,
    )
}
