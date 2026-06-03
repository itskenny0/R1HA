package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.R1
import kotlin.math.cos
import kotlin.math.sin

/**
 * Five-point star drawn in the R1 idiom — the favourites mark for the chrome row. A
 * hairline-stroked outline (not a filled glyph) so the visual weight matches the
 * three-stroke [HamburgerGlyph] sitting immediately to its left. Same 1.5dp stroke,
 * butt/round joins so the points stay crisp against the sharp dashboard chrome.
 */
@Composable
fun FavoritesGlyph(
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    tint: Color = R1.Ink.copy(alpha = 0.85f),
) {
    Canvas(modifier = modifier.size(size)) {
        val sw = 1.5.dp.toPx()
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        // Outer radius leaves a hairline of breathing room inside the canvas; the inner
        // radius (~0.38 of outer) gives the classic five-point star proportion.
        val outer = this.size.minDimension / 2f - sw
        val inner = outer * 0.40f
        val path = Path()
        // Ten alternating outer/inner vertices, starting at the top point (-90°).
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) outer else inner
            val angle = (-90.0 + i * 36.0) * (Math.PI / 180.0)
            val x = cx + (r * cos(angle)).toFloat()
            val y = cy + (r * sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
