package com.github.itskenny0.r1ha.feature.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Paints a [FavoriteCardModel] into a Bitmap with android.graphics so the
 * widget can echo the in-app card idiom (near-black rounded card, accent-
 * tinted glyph disc, ink name, big monospace readout) despite RemoteViews
 * having no Compose surface. Colours are literal copies of the R1 design
 * tokens rather than reads of the Compose objects: the renderer must work
 * on the RemoteViews path with no composition alive, and the card-surface
 * greys are static in DesignTokens anyway.
 */
internal object FavoriteCardRenderer {

    // R1 palette mirror (DesignTokens.kt). Surface/hairline/ink are static
    // there; the accent arrives per-card via the model.
    private const val SURFACE = 0xFF141414.toInt()
    private const val HAIRLINE = 0xFF2A2A2A.toInt()
    private const val INK = 0xFFEDEDED.toInt()
    private const val INK_SOFT = 0xFFA8A8A8.toInt()
    private const val INK_MUTED = 0xFF6E6E6E.toInt()

    /**
     * Render the card at [widthPx] x [heightPx]. [density] converts the dp
     * design measurements; callers clamp the pixel size before invoking so a
     * giant resize can't allocate a RemoteViews-rejecting bitmap.
     *
     * [cornerPx] is the launcher's widget corner radius: Android 12+ clips every
     * widget to `system_app_widget_background_radius`, so the card's own corners
     * must match or the border gets sliced off at the corners and reads broken.
     * Callers resolve it from the system resource (see the provider) and pass a
     * pre-31 fallback elsewhere.
     */
    fun render(
        model: FavoriteCardModel,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        cornerPx: Float = 4f * density,
    ): Bitmap {
        val w = widthPx.coerceAtLeast(48)
        val h = heightPx.coerceAtLeast(48)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        fun dp(value: Float) = value * density

        // Unavailable cards keep their layout but drop to the muted greys, the
        // same dimmed treatment the in-app deck applies.
        val accent = if (model.available) model.accentArgb else INK_MUTED
        val nameInk = if (model.available) INK else INK_SOFT
        val stateInk = if (model.available) accent else INK_MUTED

        val pad = dp(10f)
        val corner = cornerPx.coerceIn(0f, minOf(w, h) / 2f)
        val card = RectF(dp(0.5f), dp(0.5f), w - dp(0.5f), h - dp(0.5f))

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = SURFACE
        }
        canvas.drawRoundRect(card, corner, corner, fill)

        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
            color = if (model.available) withAlpha(accent, 0.45f) else HAIRLINE
        }
        canvas.drawRoundRect(card, corner, corner, border)

        // Accent glyph disc, top-left — the CardIconDisc idiom (18% fill, 40%
        // ring, accent glyph centred).
        val discR = dp(13f)
        val discCx = pad + discR
        val discCy = pad + discR
        val discFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = withAlpha(accent, 0.18f)
        }
        canvas.drawCircle(discCx, discCy, discR, discFill)
        val discRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
            color = withAlpha(accent, 0.4f)
        }
        canvas.drawCircle(discCx, discCy, discR, discRing)

        val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            textSize = dp(14f)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            model.glyph,
            discCx,
            discCy - (glyphPaint.ascent() + glyphPaint.descent()) / 2f,
            glyphPaint,
        )

        // Display name, vertically centred on the disc, ellipsized to the card edge.
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = nameInk
            textSize = dp(13f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val nameX = discCx + discR + dp(8f)
        val nameAvail = w - pad - nameX
        canvas.drawText(
            ellipsize(model.name, namePaint, nameAvail),
            nameX,
            discCy - (namePaint.ascent() + namePaint.descent()) / 2f,
            namePaint,
        )

        // Big monospace readout, bottom-left — shrinks to fit the card width
        // (long sensor strings) but never below a legible floor, then ellipsizes.
        val statePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stateInk
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val stateAvail = w - 2 * pad
        var stateSize = (h * 0.30f).coerceIn(dp(14f), dp(32f))
        statePaint.textSize = stateSize
        val floor = dp(11f)
        while (statePaint.measureText(model.stateText) > stateAvail && stateSize > floor) {
            stateSize = (stateSize - dp(1f)).coerceAtLeast(floor)
            statePaint.textSize = stateSize
        }
        canvas.drawText(
            ellipsize(model.stateText, statePaint, stateAvail),
            pad,
            h - pad - statePaint.descent(),
            statePaint,
        )

        return bitmap
    }

    private fun withAlpha(argb: Int, alpha: Float): Int {
        val a = ((argb ushr 24).toFloat() * alpha.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return (a shl 24) or (argb and 0x00FFFFFF)
    }

    /** Manual tail-ellipsis: Paint has no built-in and StaticLayout is overkill for one line. */
    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (maxWidth <= 0f) return ""
        if (paint.measureText(text) <= maxWidth) return text
        var keep = text.length
        while (keep > 1 && paint.measureText(text.substring(0, keep) + "…") > maxWidth) {
            keep--
        }
        return text.substring(0, keep) + "…"
    }
}
