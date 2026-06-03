package com.github.itskenny0.r1ha.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Internal scaffolding for the R1HA in-house icon set.
 *
 * These are hand-authored, ORIGINAL public-domain Compose [ImageVector]s. No
 * MDI / Material / third-party SVG path data is copied here; every glyph is a
 * simple geometric construction authored from scratch so the set stays clean
 * under The Unlicense.
 *
 * Design language ("Mission Control"): monochrome LINE icons on a 24x24
 * viewport, drawn as STROKED paths (no fills) so they read as crisp hairlines
 * at small sizes on the R1's ~240 dp display. They carry no colour of their
 * own; callers tint them via [androidx.compose.material3.Icon]'s `tint`, which
 * substitutes the path tint for the icon's intrinsic colour (we declare the
 * stroke with [TINT] = [Color.Black] so the default `LocalContentColor`
 * pathway behaves, but in practice every call site passes an explicit tint).
 */

/** Standard R1 icon canvas: a 24x24 unit viewport at a 24.dp default size. */
internal const val VIEWPORT = 24f
private val DIM = 24.dp

/**
 * Placeholder stroke colour baked into the path geometry. Material's `Icon`
 * overrides this with the supplied `tint`, so the concrete value only matters
 * for callers that draw the vector raw (rare). Black is the conventional
 * "neutral, will be tinted" choice used by the Material icon DSL.
 */
internal val TINT: Color = Color.Black

/** Hairline weight, in viewport units, tuned to ~1.6 dp at 24 dp. */
internal const val STROKE_W = 1.7f

/**
 * Build a stroked line icon. The lambda receives a [PathBuilder] and should
 * describe the glyph with moveTo / lineTo / curveTo / arcTo / close. Every
 * path is stroked (round caps + round joins) with no fill, giving the uniform
 * hairline look across the whole set.
 */
internal fun lineIcon(
    name: String,
    block: PathBuilder.() -> Unit,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = DIM,
    defaultHeight = DIM,
    viewportWidth = VIEWPORT,
    viewportHeight = VIEWPORT,
).apply {
    path(
        stroke = SolidColor(TINT),
        strokeLineWidth = STROKE_W,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    )
}.build()

/**
 * Build a line icon from several independent stroked sub-paths. Useful when a
 * glyph has disjoint strokes (e.g. a sun's disc plus its rays) that should not
 * be joined into one continuous outline.
 */
internal fun lineIcon(
    name: String,
    vararg subPaths: PathBuilder.() -> Unit,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = DIM,
    defaultHeight = DIM,
    viewportWidth = VIEWPORT,
    viewportHeight = VIEWPORT,
).apply {
    for (sub in subPaths) {
        path(
            stroke = SolidColor(TINT),
            strokeLineWidth = STROKE_W,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = sub,
        )
    }
}.build()

/** A tiny filled dot (used by the generic fallback and a few accent dots). */
internal fun PathBuilder.dot(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
    arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
    close()
}

/** A full circle as a stroked sub-path centred at (cx, cy). */
internal fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
    arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
    close()
}

/** An axis-aligned rectangle as a stroked sub-path. */
internal fun PathBuilder.rect(left: Float, top: Float, right: Float, bottom: Float) {
    moveTo(left, top)
    lineTo(right, top)
    lineTo(right, bottom)
    lineTo(left, bottom)
    close()
}
