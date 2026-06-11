package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * Circular HS colour picker, HA-more-info style. The disc is a clockwise hue sweep
 * (red at 3 o'clock; same convention as [wheelHsAt]) under a white-centre radial
 * fade that encodes saturation: centre = white (sat 0), rim = fully saturated. A
 * ring-stroked thumb marks the current (hue, sat).
 *
 * Live-drag contract: [onHsChange] fires for every tracked position (throttle at
 * the call site; see the DebouncedCaller wiring in MoreInfoSheet / the card-stack
 * VM); [onHsChangeFinished] fires once on release / tap with the exact final pair.
 * While a finger is down the thumb follows LOCAL drag state, not the [hue]/
 * [saturation] props, so the HA echo can't yank the thumb mid-gesture; on release
 * the local state clears and the thumb reconciles to whatever the entity reports.
 *
 * Recomposition discipline: the two gradient brushes are remembered per measured
 * size; only the thumb position invalidates the draw during a drag.
 */
@Composable
fun ColorWheel(
    hue: Float,
    saturation: Float,
    onHsChange: (Float, Float) -> Unit,
    onHsChangeFinished: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragHs by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val shownHue = dragHs?.first ?: hue
    val shownSat = dragHs?.second ?: saturation

    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    // Brushes depend only on the measured size, never on the thumb position;
    // remembering them here keeps a 60 Hz drag from re-allocating two gradients
    // per frame.
    val brushes = remember(sizePx) {
        if (sizePx.width <= 0 || sizePx.height <= 0) {
            null
        } else {
            val center = Offset(sizePx.width / 2f, sizePx.height / 2f)
            val radius = minOf(sizePx.width, sizePx.height) / 2f
            // 13 stops (every 30 degrees, wrapping back to red); enough that the
            // sweep interpolation stays visually smooth; fewer stops band visibly
            // in the cyan-blue region.
            val hueRing = Brush.sweepGradient(
                colors = (0..12).map { Color.hsv((it * 30 % 360).toFloat(), 1f, 1f) },
                center = center,
            )
            val satFade = Brush.radialGradient(
                colors = listOf(Color.White, Color.White.copy(alpha = 0f)),
                center = center,
                radius = radius,
            )
            Triple(hueRing, satFade, center to radius)
        }
    }

    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .onSizeChanged { sizePx = it }
            .semantics { contentDescription = "Colour wheel" }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val r = minOf(size.width, size.height) / 2f
                    wheelHsAt(pos.x, pos.y, size.width / 2f, size.height / 2f, r)?.let { (h, s) ->
                        onHsChange(h, s)
                        onHsChangeFinished(h, s)
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val r = minOf(size.width, size.height) / 2f
                        wheelHsAt(pos.x, pos.y, size.width / 2f, size.height / 2f, r)?.let { hs ->
                            dragHs = hs
                            onHsChange(hs.first, hs.second)
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val r = minOf(size.width, size.height) / 2f
                        wheelHsAt(
                            change.position.x, change.position.y,
                            size.width / 2f, size.height / 2f, r,
                        )?.let { hs ->
                            dragHs = hs
                            onHsChange(hs.first, hs.second)
                        }
                    },
                    onDragEnd = {
                        dragHs?.let { onHsChangeFinished(it.first, it.second) }
                        dragHs = null
                    },
                    onDragCancel = { dragHs = null },
                )
            },
    ) {
        val painted = brushes ?: return@Canvas
        val (hueRing, satFade, geo) = painted
        val (center, radius) = geo
        drawCircle(brush = hueRing, radius = radius, center = center)
        drawCircle(brush = satFade, radius = radius, center = center)
        // Hairline rim so the disc reads as a control against the sheet surface.
        drawCircle(color = Color.Black.copy(alpha = 0.35f), radius = radius, center = center, style = Stroke(1.dp.toPx()))
        // Thumb: filled with the picked colour, double-ringed (white inner, dark
        // outer) so it stays visible over both the white centre and saturated rim.
        val (tx, ty) = wheelOffsetFor(shownHue, shownSat, center.x, center.y, radius)
        val thumbCenter = Offset(tx, ty)
        drawCircle(color = Color.hsv(shownHue.coerceIn(0f, 360f), shownSat.coerceIn(0f, 1f), 1f), radius = 9.dp.toPx(), center = thumbCenter)
        drawCircle(color = Color.White, radius = 9.dp.toPx(), center = thumbCenter, style = Stroke(2.dp.toPx()))
        drawCircle(color = Color.Black.copy(alpha = 0.45f), radius = 10.5f.dp.toPx(), center = thumbCenter, style = Stroke(1.dp.toPx()))
    }
}

/**
 * Colour-temperature slider: a rounded bar painted with the actual black-body
 * gradient between [minKelvin] and [maxKelvin] (so a bulb limited to 2200-4000 K
 * shows only its reachable band, not a generic amber-to-blue ramp), with a
 * circular thumb. Same live-drag contract and local-state-while-dragging
 * behaviour as [ColorWheel]; the whole strip is 48 dp tall so the touch target
 * meets the house minimum even though the painted bar is slimmer.
 */
@Composable
fun ColorTempSlider(
    kelvin: Int,
    minKelvin: Int,
    maxKelvin: Int,
    onKelvinChange: (Int) -> Unit,
    onKelvinChangeFinished: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragKelvin by remember { mutableStateOf<Int?>(null) }
    val shownKelvin = (dragKelvin ?: kelvin).coerceIn(minKelvin, maxKelvin)

    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    // Five stops across the entity's own kelvin range; the black-body curve is
    // smooth enough that linear interpolation between five samples is
    // indistinguishable from the true curve at slider size.
    val gradient = remember(sizePx, minKelvin, maxKelvin) {
        if (sizePx.width <= 0) {
            null
        } else {
            Brush.horizontalGradient(
                colors = (0..4).map { i ->
                    Color(kelvinToArgb(kelvinFromFraction(i / 4f, minKelvin, maxKelvin)))
                },
            )
        }
    }

    fun kelvinAt(x: Float, widthPx: Float, thumbRadiusPx: Float): Int {
        val track = (widthPx - 2f * thumbRadiusPx).coerceAtLeast(1f)
        return kelvinFromFraction((x - thumbRadiusPx) / track, minKelvin, maxKelvin)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .onSizeChanged { sizePx = it }
            .semantics { contentDescription = "Colour temperature" }
            .pointerInput(minKelvin, maxKelvin) {
                detectTapGestures { pos ->
                    val k = kelvinAt(pos.x, size.width.toFloat(), 12.dp.toPx())
                    onKelvinChange(k)
                    onKelvinChangeFinished(k)
                }
            }
            .pointerInput(minKelvin, maxKelvin) {
                detectHorizontalDragGestures(
                    onDragStart = { pos ->
                        val k = kelvinAt(pos.x, size.width.toFloat(), 12.dp.toPx())
                        dragKelvin = k
                        onKelvinChange(k)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val k = kelvinAt(change.position.x, size.width.toFloat(), 12.dp.toPx())
                        dragKelvin = k
                        onKelvinChange(k)
                    },
                    onDragEnd = {
                        dragKelvin?.let { onKelvinChangeFinished(it) }
                        dragKelvin = null
                    },
                    onDragCancel = { dragKelvin = null },
                )
            },
    ) {
        val brush = gradient ?: return@Canvas
        val thumbRadius = 12.dp.toPx()
        val barHeight = 24.dp.toPx()
        val barTop = (size.height - barHeight) / 2f
        drawRoundRect(
            brush = brush,
            topLeft = Offset(0f, barTop),
            size = Size(size.width, barHeight),
            cornerRadius = CornerRadius(barHeight / 2f),
        )
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.35f),
            topLeft = Offset(0f, barTop),
            size = Size(size.width, barHeight),
            cornerRadius = CornerRadius(barHeight / 2f),
            style = Stroke(1.dp.toPx()),
        )
        val track = (size.width - 2f * thumbRadius).coerceAtLeast(1f)
        val cx = thumbRadius + fractionFromKelvin(shownKelvin, minKelvin, maxKelvin) * track
        val thumbCenter = Offset(cx, size.height / 2f)
        drawCircle(color = Color(kelvinToArgb(shownKelvin)), radius = thumbRadius - 2.dp.toPx(), center = thumbCenter)
        drawCircle(color = Color.White, radius = thumbRadius - 2.dp.toPx(), center = thumbCenter, style = Stroke(2.dp.toPx()))
        drawCircle(color = Color.Black.copy(alpha = 0.45f), radius = thumbRadius, center = thumbCenter, style = Stroke(1.dp.toPx()))
    }
}

/**
 * Screen-level colour-wheel overlay for light cards; same presentation shape as
 * the card stack's EffectPickerSheet (dim full-bleed backdrop, header + CLOSE
 * chip, system back dismisses) so the user only learns one overlay convention.
 * Hosts a [ColorWheel] sized to the smaller screen axis, capped so it stays a
 * comfortable thumb reach on phones.
 *
 * Works identically on wheel-less phones (touch drives everything). On the R1
 * the card underneath stays in HUE wheel mode while this overlay is up, so the
 * physical scroll wheel keeps cycling hue at the same time; both inputs write
 * the same hs_color and the thumb tracks whichever moved last via the entity
 * echo in [hue]/[saturation].
 */
/**
 * Modal HS colour PICKER: unlike [ColorWheelOverlaySheet] nothing fires while
 * dragging; the wheel edits local state behind a live preview swatch and the
 * caller only hears about the colour when USE is tapped. Used by the
 * favourite-colour editor, where dragging must not recolour the actual bulb.
 */
@Composable
fun ColorPickerOverlaySheet(
    title: String,
    initialHue: Float,
    initialSaturation: Float,
    onConfirm: (hue: Float, saturation: Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var hue by remember { mutableStateOf(initialHue) }
    var sat by remember { mutableStateOf(initialSaturation) }
    PickerOverlayScaffold(
        title = title,
        previewColor = Color.hsv(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), 1f),
        onConfirm = { onConfirm(hue, sat) },
        onDismiss = onDismiss,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val wheelSize = minOf(maxWidth, maxHeight, 240.dp)
            ColorWheel(
                hue = hue,
                saturation = sat,
                onHsChange = { h, s -> hue = h; sat = s },
                onHsChangeFinished = { h, s -> hue = h; sat = s },
                modifier = Modifier.size(wheelSize),
            )
        }
    }
}

/**
 * Modal colour-temperature PICKER: the kelvin twin of [ColorPickerOverlaySheet],
 * so editing a kelvin favourite keeps it a kelvin entry instead of silently
 * converting it to RGB.
 */
@Composable
fun KelvinPickerOverlaySheet(
    title: String,
    initialKelvin: Int,
    minKelvin: Int,
    maxKelvin: Int,
    onConfirm: (kelvin: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var kelvin by remember { mutableStateOf(initialKelvin.coerceIn(minKelvin, maxKelvin)) }
    PickerOverlayScaffold(
        title = title,
        previewColor = Color(kelvinToArgb(kelvin)),
        previewLabel = "$kelvin K",
        onConfirm = { onConfirm(kelvin) },
        onDismiss = onDismiss,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ColorTempSlider(
                kelvin = kelvin,
                minKelvin = minKelvin,
                maxKelvin = maxKelvin,
                onKelvinChange = { kelvin = it },
                onKelvinChangeFinished = { kelvin = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}

/** Shared chrome for the modal pickers: dim backdrop, header with a preview
 *  swatch, USE confirm chip + CLOSE/back dismissal (the overlay convention
 *  [ColorWheelOverlaySheet] established). */
@Composable
private fun PickerOverlayScaffold(
    title: String,
    previewColor: Color,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    previewLabel: String? = null,
    content: @Composable () -> Unit,
) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.96f))
            .r1Pressable(onClick = onDismiss, hapticOnClick = false, contentDescription = "Close colour picker"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(R1.ShapeS)
                        .background(previewColor)
                        .semantics { contentDescription = "Picked colour preview" },
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = previewLabel ?: "PICK A COLOUR",
                    style = R1.sectionHeader,
                    color = R1.Ink,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .r1Pressable(onClick = onConfirm)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(text = "USE", style = R1.labelMicro, color = R1.Ink)
                }
                Spacer(Modifier.size(6.dp))
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .r1Pressable(onClick = onDismiss)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(text = "CLOSE", style = R1.labelMicro, color = R1.InkSoft)
                }
            }
            Text(
                text = title,
                style = R1.labelMicro,
                color = R1.InkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Swallow stray taps inside the content so a near-miss doesn't
                    // dismiss; backdrop + CLOSE + back all still do.
                    .r1Pressable(onClick = {}, hapticOnClick = false),
            ) {
                content()
            }
        }
    }
}

@Composable
fun ColorWheelOverlaySheet(
    title: String,
    hue: Float,
    saturation: Float,
    onHsChange: (Float, Float) -> Unit,
    onHsChangeFinished: (Float, Float) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.96f))
            .r1Pressable(onClick = onDismiss, hapticOnClick = false, contentDescription = "Close colour wheel"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "COLOUR",
                    style = R1.sectionHeader,
                    color = R1.Ink,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .r1Pressable(onClick = onDismiss)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(text = "CLOSE", style = R1.labelMicro, color = R1.InkSoft)
                }
            }
            Text(
                text = title,
                style = R1.labelMicro,
                color = R1.InkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    // Swallow taps around the wheel inside the content column so a
                    // near-miss doesn't dismiss; the backdrop + CLOSE chip + back
                    // all still do.
                    .r1Pressable(onClick = {}, hapticOnClick = false),
                contentAlignment = Alignment.Center,
            ) {
                val wheelSize = minOf(maxWidth, maxHeight, 260.dp)
                ColorWheel(
                    hue = hue,
                    saturation = saturation,
                    onHsChange = onHsChange,
                    onHsChangeFinished = onHsChangeFinished,
                    modifier = Modifier.size(wheelSize),
                )
            }
        }
    }
}
