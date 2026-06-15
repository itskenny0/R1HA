package com.github.itskenny0.r1ha.core.theme

import com.github.itskenny0.r1ha.core.prefs.ColorfulPaletteSet

/**
 * Pure palette data + selection math for the "Colourful Cards" theme, lifted out of the
 * Compose theme object so the per-entity mapping unit-tests on a plain JVM (no Compose,
 * no Android). Colours are packed ARGB ints here; [ColorfulCardsTheme] wraps them in
 * Compose `Color` at render time.
 *
 * Each SET is six 3-stop gradients in the SAME hue order (warm, cool, green, violet,
 * teal, rose). The per-entity hash maps an entity_id onto one of the six SLOTS; keeping
 * the slot order aligned across sets means an entity that lands on slot 0 ("warm") stays
 * the warm card whichever set the user picks, so switching sets re-skins the wall without
 * reshuffling which tile is which colour. Each stop runs bright → base → deep so the
 * darkest anchor seats white text in the card's bottom-right (matching [overrideGradientArgb]).
 */
object ColorfulPalettes {

    /** The shipped saturated set (was the inline `palette` list in [ColorfulCardsTheme]).
     *  Kept ARGB-identical so VIVID is a true no-op for existing installs. */
    val VIVID: List<IntArray> = listOf(
        intArrayOf(0xFFFFB347.toInt(), 0xFFFF6B1A.toInt(), 0xFFA62B7C.toInt()), // warm: amber → orange → deep magenta
        intArrayOf(0xFF41BDF5.toInt(), 0xFF1B7BB8.toInt(), 0xFF0D3B66.toInt()), // cool: sky → azure → navy
        intArrayOf(0xFF52C77F.toInt(), 0xFF2C8B5A.toInt(), 0xFF154A35.toInt()), // green: mint → leaf → forest
        intArrayOf(0xFF9B6BD8.toInt(), 0xFF5B3B9E.toInt(), 0xFF2E2057.toInt()), // violet: lilac → purple → indigo
        intArrayOf(0xFF3FD8C2.toInt(), 0xFF169E8F.toInt(), 0xFF0B4F4A.toInt()), // teal: turquoise → teal → deep sea
        intArrayOf(0xFFFF7E79.toInt(), 0xFFE03E63.toInt(), 0xFF6E1B45.toInt()), // rose: coral → raspberry → wine
    )

    /** A softer, chalkier sky: the same six hue families lifted toward white and slightly
     *  desaturated, with mid anchors kept deep enough that white text still reads. Easier on
     *  the eye on an always-on wall panel; the bright stops are noticeably paler than VIVID,
     *  so the per-palette top scrim leans heavier here (its alpha is read from the bright stop). */
    val PASTEL: List<IntArray> = listOf(
        intArrayOf(0xFFFFD8A8.toInt(), 0xFFF0A36B.toInt(), 0xFF7C5149.toInt()), // warm: peach → apricot → clay
        intArrayOf(0xFFB8E1F5.toInt(), 0xFF7FB4D8.toInt(), 0xFF3F6080.toInt()), // cool: ice → powder → slate
        intArrayOf(0xFFBDE6C6.toInt(), 0xFF86C49A.toInt(), 0xFF466B54.toInt()), // green: sage → meadow → fern
        intArrayOf(0xFFD9C2F0.toInt(), 0xFFA888CC.toInt(), 0xFF5A4880.toInt()), // violet: lavender → lilac → plum
        intArrayOf(0xFFB4ECE2.toInt(), 0xFF7FC8BC.toInt(), 0xFF38635D.toInt()), // teal: seafoam → aqua → spruce
        intArrayOf(0xFFFFC9C4.toInt(), 0xFFE8929A.toInt(), 0xFF7A4A55.toInt()), // rose: blush → dusty rose → mauve
    )

    /** Punchy electric stops over near-black anchors for a high-contrast synth look. The
     *  bright stops are vivid but not near-white, and the deep anchors drop almost to black,
     *  so the cards read as glowing panels against a dark frame. */
    val NEON: List<IntArray> = listOf(
        intArrayOf(0xFFFFA12E.toInt(), 0xFFFF2E63.toInt(), 0xFF1A0014.toInt()), // warm: amber → hot pink → near-black
        intArrayOf(0xFF21E1FF.toInt(), 0xFF1670FF.toInt(), 0xFF030A28.toInt()), // cool: cyan → electric blue → ink
        intArrayOf(0xFF3CFF8E.toInt(), 0xFF12C46B.toInt(), 0xFF021A0F.toInt()), // green: laser green → emerald → ink
        intArrayOf(0xFFC65CFF.toInt(), 0xFF7A1EFF.toInt(), 0xFF0F0220.toInt()), // violet: ultraviolet → purple → ink
        intArrayOf(0xFF2EFFE0.toInt(), 0xFF12B8C4.toInt(), 0xFF021A1C.toInt()), // teal: aqua-laser → teal → ink
        intArrayOf(0xFFFF4D8D.toInt(), 0xFFFF1E5A.toInt(), 0xFF1A0210.toInt()), // rose: magenta → crimson → ink
    )

    /** Resolve a palette SET enum to its list of six 3-stop ARGB gradients. */
    fun setFor(set: ColorfulPaletteSet): List<IntArray> = when (set) {
        ColorfulPaletteSet.VIVID -> VIVID
        ColorfulPaletteSet.PASTEL -> PASTEL
        ColorfulPaletteSet.NEON -> NEON
    }

    /**
     * Stable slot index (0..5) for an entity_id. Mirrors HA's `hashCode % size` mapping with
     * a positive-remainder fix so a negative hashCode doesn't throw / pick a negative slot.
     * SET-independent on purpose: every set has six slots, so the same entity keeps its slot
     * (and thus its hue family) across sets. [size] is always 6 for the stock sets but is
     * passed explicitly so the function is total and testable against any set length.
     */
    fun paletteIndexFor(entityId: String, size: Int): Int {
        val n = size.coerceAtLeast(1)
        return (entityId.hashCode().rem(n) + n) % n
    }

    /** The 3-stop ARGB gradient an entity maps to within [set]. */
    fun paletteArgbFor(entityId: String, set: ColorfulPaletteSet): IntArray {
        val palettes = setFor(set)
        return palettes[paletteIndexFor(entityId, palettes.size)]
    }
}
