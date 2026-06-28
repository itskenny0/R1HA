package com.github.itskenny0.r1ha.feature.widget

/** Which favorite-card face the renderer paints for a given cell size. */
internal enum class RenderTier { COMPACT, MEDIUM, FULL }

/**
 * Pick the face for a widget cell measured in dp. Cells small in both axes get
 * the glyph-and-tint COMPACT face (legible at a single launcher cell); short
 * cells that still have width get the MEDIUM face (glyph + name + value);
 * everything roomy keeps the FULL card. Degenerate sizes (zero or negative,
 * which some launchers report before the first layout pass) fall back to FULL
 * so a freshly placed widget never renders the most cramped face by accident.
 */
internal fun widgetRenderTier(widthDp: Int, heightDp: Int): RenderTier {
    if (widthDp <= 0 || heightDp <= 0) return RenderTier.FULL
    return when {
        heightDp < 80 && widthDp < 130 -> RenderTier.COMPACT
        heightDp < 110 -> RenderTier.MEDIUM
        else -> RenderTier.FULL
    }
}
