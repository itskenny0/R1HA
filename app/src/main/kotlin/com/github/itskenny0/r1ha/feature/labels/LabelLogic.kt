package com.github.itskenny0.r1ha.feature.labels

import androidx.compose.ui.graphics.Color
import java.util.Locale

/**
 * Pure, side-effect-free helpers for the Labels surface. Kept out of the
 * ViewModel so they can be unit-tested without a coroutine / repository
 * harness: color parsing, label-membership grouping, and search filtering.
 *
 * A "member" is anything that carries a label_id: an entity, a device, or an
 * area. HA models the same label across all three registries, so a label's
 * full footprint is the union of the three sets. We resolve each set into a
 * [LabelMember] carrying a stable id, a human display name, and which kind of
 * registry row it came from so the drill-in can group them.
 */
object LabelLogic {

    enum class MemberKind { ENTITY, DEVICE, AREA }

    data class LabelMember(
        val id: String,
        val name: String,
        val kind: MemberKind,
    )

    /**
     * The grouped footprint of a single label. Each list is sorted by display
     * name (case-insensitive, [Locale.US]) so the drill-in is stable.
     */
    data class LabelMembership(
        val entities: List<LabelMember>,
        val devices: List<LabelMember>,
        val areas: List<LabelMember>,
    ) {
        val total: Int get() = entities.size + devices.size + areas.size
        val isEmpty: Boolean get() = total == 0
    }

    /**
     * Build a [LabelMembership] from the three raw id->name maps the template
     * resolves. Blank ids are dropped; a missing name falls back to the id so
     * nothing renders as an empty row. Each kind is sorted independently.
     */
    fun groupMembership(
        entities: Map<String, String>,
        devices: Map<String, String>,
        areas: Map<String, String>,
    ): LabelMembership {
        fun build(src: Map<String, String>, kind: MemberKind): List<LabelMember> =
            src.entries
                .mapNotNull { (id, name) ->
                    val cleanId = id.trim()
                    if (cleanId.isEmpty()) return@mapNotNull null
                    val display = name.trim().ifEmpty { cleanId }
                    LabelMember(id = cleanId, name = display, kind = kind)
                }
                .sortedBy { it.name.lowercase(Locale.US) }

        return LabelMembership(
            entities = build(entities, MemberKind.ENTITY),
            devices = build(devices, MemberKind.DEVICE),
            areas = build(areas, MemberKind.AREA),
        )
    }

    /**
     * Case-insensitive substring filter over a label's name plus the names of
     * everything it carries, so searching "kitchen" surfaces a label that
     * tags the kitchen area even when the label itself is named differently. A
     * blank query keeps everything.
     */
    fun matchesQuery(
        query: String,
        labelName: String,
        memberNames: List<String> = emptyList(),
    ): Boolean {
        val q = query.trim().lowercase(Locale.US)
        if (q.isEmpty()) return true
        if (labelName.lowercase(Locale.US).contains(q)) return true
        return memberNames.any { it.lowercase(Locale.US).contains(q) }
    }

    /**
     * Resolve a label's color into a Compose [Color] used as the row accent.
     *
     * HA exposes label color two ways depending on version / config: a named
     * theme color ("red", "light-blue", "deep-purple", "primary") or, more
     * rarely, a raw hex string. We handle both and fall back to [fallback]
     * (the R1 warm accent) for null / unknown values so a label always has a
     * usable accent.
     */
    fun parseLabelColor(raw: String?, fallback: Color): Color {
        val v = raw?.trim()?.lowercase(Locale.US).orEmpty()
        if (v.isEmpty()) return fallback
        parseHex(v)?.let { return it }
        return NAMED_COLORS[v.replace('_', '-')] ?: fallback
    }

    /**
     * Accent legible against the dark Mission Control surface. HA contrasts a
     * label color against light/dark backgrounds; we only ever paint onto
     * [com.github.itskenny0.r1ha.core.theme.R1.SurfaceMuted], so a near-black
     * label color (HA's "black", a dark custom hex) would render an invisible
     * swatch and an unreadable count. When the resolved color is too dark to
     * read on the dark surface we substitute [fallback] (the warm accent) so the
     * label still reads. Pure: the threshold is perceptual luminance.
     */
    fun accentOnDark(resolved: Color, fallback: Color): Color =
        if (relativeLuminance(resolved) < MIN_ON_DARK_LUMINANCE) fallback else resolved

    /** Rec. 709 relative luminance of an sRGB color in 0f..1f. */
    private fun relativeLuminance(c: Color): Float =
        0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

    /** Below this luminance a color is indistinguishable from the dark surface. */
    private const val MIN_ON_DARK_LUMINANCE = 0.16f

    /**
     * Parse a "#RRGGBB" / "#AARRGGBB" / bare-hex string into a [Color]. Returns
     * null for anything that is not a clean 6- or 8-digit hex value.
     */
    fun parseHex(raw: String?): Color? {
        val s = raw?.trim()?.removePrefix("#") ?: return null
        if (s.isEmpty()) return null
        if (!s.all { it in "0123456789abcdefABCDEF" }) return null
        return when (s.length) {
            6 -> {
                val rgb = s.toLong(16)
                Color(0xFF000000 or rgb)
            }
            8 -> Color(s.toLong(16))
            else -> null
        }
    }

    /**
     * Normalize HA's mdi icon string ("mdi:tag-outline") into the bare slug
     * ("tag-outline"). Returns null for null / blank so callers can fall back
     * to a default glyph. R1 has no full MDI font, so the slug is surfaced as
     * text metadata rather than rendered as a vector.
     */
    fun normalizeIcon(raw: String?): String? {
        val v = raw?.trim() ?: return null
        if (v.isEmpty() || v.equals("none", ignoreCase = true)) return null
        return v.removePrefix("mdi:").trim().ifEmpty { null }
    }

    /**
     * Merged spoken label for one label row. Speaks the label name, how many
     * things it tags (in words, not just a coloured badge), and whether the
     * drill-in is currently open. Pure so it can be unit-tested.
     */
    fun labelRowLabel(name: String, memberCount: Int, expanded: Boolean): String {
        val cleanName = name.trim().ifEmpty { "Unnamed label" }
        val members = when (memberCount) {
            0 -> "nothing tagged"
            1 -> "1 tagged item"
            else -> "$memberCount tagged items"
        }
        val action = if (expanded) "Expanded. Tap to collapse." else "Tap to expand."
        return "Label $cleanName. $members. $action"
    }

    /** Spoken label for a tappable member row inside a label drill-in. */
    fun memberRowLabel(name: String, kind: MemberKind, tappable: Boolean): String {
        val cleanName = name.trim().ifEmpty { "Unnamed" }
        val kindWord = when (kind) {
            MemberKind.ENTITY -> "Entity"
            MemberKind.DEVICE -> "Device"
            MemberKind.AREA -> "Area"
        }
        val suffix = if (tappable) ". Tap to open history in Home Assistant." else ""
        return "$kindWord $cleanName$suffix"
    }

    /**
     * HA's fixed palette of named label colors mapped to representative hex
     * values close to the Material tones HA uses. Keys are normalized to the
     * dash form HA emits ("deep-purple", "light-blue").
     */
    private val NAMED_COLORS: Map<String, Color> = mapOf(
        "primary" to Color(0xFF03A9F4),
        "accent" to Color(0xFFFF9800),
        "red" to Color(0xFFE53935),
        "pink" to Color(0xFFD81B60),
        "purple" to Color(0xFF8E24AA),
        "deep-purple" to Color(0xFF5E35B1),
        "indigo" to Color(0xFF3949AB),
        "blue" to Color(0xFF1E88E5),
        "light-blue" to Color(0xFF039BE5),
        "cyan" to Color(0xFF00ACC1),
        "teal" to Color(0xFF00897B),
        "green" to Color(0xFF43A047),
        "light-green" to Color(0xFF7CB342),
        "lime" to Color(0xFFC0CA33),
        "yellow" to Color(0xFFFDD835),
        "amber" to Color(0xFFFFB300),
        "orange" to Color(0xFFFB8C00),
        "deep-orange" to Color(0xFFF4511E),
        "brown" to Color(0xFF6D4C41),
        "light-grey" to Color(0xFFBDBDBD),
        "light-gray" to Color(0xFFBDBDBD),
        "grey" to Color(0xFF757575),
        "gray" to Color(0xFF757575),
        "dark-grey" to Color(0xFF616161),
        "dark-gray" to Color(0xFF616161),
        "blue-grey" to Color(0xFF546E7A),
        "blue-gray" to Color(0xFF546E7A),
        "black" to Color(0xFF000000),
        "white" to Color(0xFFFFFFFF),
        // HA's YAML-only theme colors that can appear as a label color.
        "primary-text" to Color(0xFFEDEDED),
        "secondary-text" to Color(0xFFA8A8A8),
        "disabled" to Color(0xFF9E9E9E),
    )
}
