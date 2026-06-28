package com.github.itskenny0.r1ha.feature.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Paints a [FavoriteCardModel] into a Bitmap with android.graphics so the
 * widget can echo the in-app card idiom despite RemoteViews having no Compose
 * surface. The face adapts to the cell size (see [widgetRenderTier]): a roomy
 * cell gets the full card; a short cell gets a glyph + name + value row; a
 * single-cell tile gets a centred glyph tinted by state, with a tiny value for
 * read-only entities. Colours are literal copies of the R1 design tokens
 * because the RemoteViews path has no composition alive.
 */
internal object FavoriteCardRenderer {

    private const val SURFACE = 0xFF141414.toInt()
    private const val HAIRLINE = 0xFF2A2A2A.toInt()
    private const val INK = 0xFFEDEDED.toInt()
    private const val INK_SOFT = 0xFFA8A8A8.toInt()
    private const val INK_MUTED = 0xFF6E6E6E.toInt()

    /**
     * Render the card at [widthPx] x [heightPx]. [density] converts the dp
     * design measurements; callers clamp the pixel size before invoking so a
     * giant resize can't allocate a RemoteViews-rejecting bitmap. [cornerPx] is
     * the launcher's widget corner radius (see the provider) so the card's own
     * corners match the launcher clip.
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

        val accent = if (model.available) model.accentArgb else INK_MUTED
        val corner = cornerPx.coerceIn(0f, minOf(w, h) / 2f)
        drawCardChrome(canvas, w, h, density, corner, accent, model.available)

        val wDp = (w / density).toInt()
        val hDp = (h / density).toInt()
        when (widgetRenderTier(wDp, hDp)) {
            RenderTier.COMPACT -> drawCompact(canvas, w, h, density, model, accent)
            RenderTier.MEDIUM -> drawMedium(canvas, w, h, density, model, accent)
            RenderTier.FULL -> drawFull(canvas, w, h, density, model, accent)
        }
        return bitmap
    }

    /** Near-black rounded card + accent-tinted border, shared by every face. */
    private fun drawCardChrome(
        canvas: Canvas,
        w: Int,
        h: Int,
        density: Float,
        corner: Float,
        accent: Int,
        available: Boolean,
    ) {
        val card = RectF(0.5f * density, 0.5f * density, w - 0.5f * density, h - 0.5f * density)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = SURFACE
        }
        canvas.drawRoundRect(card, corner, corner, fill)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            color = if (available) withAlpha(accent, 0.45f) else HAIRLINE
        }
        canvas.drawRoundRect(card, corner, corner, border)
    }

    /** The CardIconDisc idiom: 18% accent fill, 40% ring, accent glyph centred. */
    private fun drawGlyphDisc(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        density: Float,
        glyph: String,
        accent: Int,
        glyphSizePx: Float,
    ) {
        val discFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = withAlpha(accent, 0.18f)
        }
        canvas.drawCircle(cx, cy, radius, discFill)
        val discRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            color = withAlpha(accent, 0.4f)
        }
        canvas.drawCircle(cx, cy, radius, discRing)
        val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            textSize = glyphSizePx
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(glyph, cx, cy - (glyphPaint.ascent() + glyphPaint.descent()) / 2f, glyphPaint)
    }

    /** Full card: disc + name top-left, big monospace readout bottom-left. */
    private fun drawFull(canvas: Canvas, w: Int, h: Int, density: Float, model: FavoriteCardModel, accent: Int) {
        fun dp(value: Float) = value * density
        val nameInk = if (model.available) INK else INK_SOFT
        val stateInk = if (model.available) accent else INK_MUTED
        val pad = dp(10f)

        val discR = dp(13f)
        val discCx = pad + discR
        val discCy = pad + discR
        drawGlyphDisc(canvas, discCx, discCy, discR, density, model.glyph, accent, dp(14f))

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
    }

    /** Short row: disc on the left, name over value on the right. */
    private fun drawMedium(canvas: Canvas, w: Int, h: Int, density: Float, model: FavoriteCardModel, accent: Int) {
        fun dp(value: Float) = value * density
        val nameInk = if (model.available) INK else INK_SOFT
        val stateInk = if (model.available) accent else INK_MUTED
        val pad = dp(8f)

        val discR = (h * 0.30f).coerceIn(dp(10f), dp(16f))
        val discCx = pad + discR
        val discCy = h / 2f
        drawGlyphDisc(canvas, discCx, discCy, discR, density, model.glyph, accent, discR * 1.05f)

        val textX = discCx + discR + dp(8f)
        val textAvail = w - pad - textX
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = nameInk
            textSize = dp(12f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val statePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stateInk
            textSize = dp(13f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        var stateSize = dp(13f)
        val floor = dp(10f)
        while (statePaint.measureText(model.stateText) > textAvail && stateSize > floor) {
            stateSize = (stateSize - dp(1f)).coerceAtLeast(floor)
            statePaint.textSize = stateSize
        }

        val nameH = namePaint.descent() - namePaint.ascent()
        val stateH = statePaint.descent() - statePaint.ascent()
        val gap = dp(2f)
        val blockTop = (h - (nameH + gap + stateH)) / 2f
        canvas.drawText(
            ellipsize(model.name, namePaint, textAvail),
            textX,
            blockTop - namePaint.ascent(),
            namePaint,
        )
        canvas.drawText(
            ellipsize(model.stateText, statePaint, textAvail),
            textX,
            blockTop + nameH + gap - statePaint.ascent(),
            statePaint,
        )
    }

    /**
     * Single-cell tile: a centred glyph tinted by state (accent when a read-only
     * value or an on-toggle; muted when an off-toggle or unavailable). Read-only
     * entities (sensors) also get a tiny value line, since their tint can't
     * convey a number; toggles and actions are glyph + tint only.
     */
    private fun drawCompact(canvas: Canvas, w: Int, h: Int, density: Float, model: FavoriteCardModel, accent: Int) {
        fun dp(value: Float) = value * density
        val active = model.available && (model.isAction || !model.actsInPlace || model.isOn)
        val tint = if (active) accent else INK_MUTED
        val showValue = model.available && !model.actsInPlace

        val cx = w / 2f
        val discR = (minOf(w, h) * 0.26f).coerceIn(dp(10f), dp(22f))
        val discCy = if (showValue) h * 0.40f else h / 2f
        drawGlyphDisc(canvas, cx, discCy, discR, density, model.glyph, tint, discR * 1.05f)

        if (showValue) {
            val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = INK
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
            val avail = w - dp(8f)
            var size = (h * 0.22f).coerceIn(dp(9f), dp(15f))
            valuePaint.textSize = size
            val floor = dp(8f)
            while (valuePaint.measureText(model.stateText) > avail && size > floor) {
                size = (size - dp(1f)).coerceAtLeast(floor)
                valuePaint.textSize = size
            }
            val top = discCy + discR + dp(3f)
            canvas.drawText(
                ellipsize(model.stateText, valuePaint, avail),
                cx,
                top - valuePaint.ascent(),
                valuePaint,
            )
        }
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
