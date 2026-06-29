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

/**
 * The widget's cell size in dp for the current orientation, from the host's
 * options. Android reports MIN_WIDTH x MAX_HEIGHT as the portrait extents and
 * MAX_WIDTH x MIN_HEIGHT as the landscape extents, so a landscape launcher (an
 * Echo Show, for example) must read the landscape pair: reading the portrait
 * pair on a wide cell sizes the card for a tall cell, and it then renders narrow
 * with empty margins on either side. Zero or missing values (older launchers
 * right after placement) fall back to the provider's default footprint.
 */
internal fun widgetCellDp(
    isLandscape: Boolean,
    minWidthDp: Int,
    maxWidthDp: Int,
    minHeightDp: Int,
    maxHeightDp: Int,
    defaultWidthDp: Int,
    defaultHeightDp: Int,
): Pair<Int, Int> {
    val wDp = (if (isLandscape) maxWidthDp else minWidthDp).takeIf { it > 0 } ?: defaultWidthDp
    val hDp = (if (isLandscape) minHeightDp else maxHeightDp).takeIf { it > 0 } ?: defaultHeightDp
    return wDp to hDp
}
