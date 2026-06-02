package com.github.itskenny0.r1ha.feature.energy

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Render an [EnergyViewModel.UiState] snapshot into a square PNG suitable for
 * sharing into a message thread / Slack / a screenshot album. Deliberately
 * Canvas-backed rather than a Compose graphics-layer capture so the rendering
 * is deterministic and independent of the on-screen layout, font scaling, or
 * device pixel density. 1080×1080 hits a sweet spot between file size (~50 KB
 * jpeg-equivalent) and legibility when forwarded through a messaging app's
 * downscale pipeline.
 *
 * Layout (top to bottom):
 *  - "R1HA · ENERGY" header + ISO-8601 timestamp
 *  - DRAW + PRODUCTION numbers paired on one row
 *  - TODAY kWh on its own line
 *  - TOP CONSUMERS table (entity + watts), up to 6 rows
 *
 * The styling mirrors the in-app palette (R1.Bg + R1.AccentWarm) so the
 * shared image is recognisably from this app.
 */
internal object EnergyShareSnapshot {

    private const val SIZE = 1080
    private const val PADDING = 56f
    private val BG = Color.parseColor("#0A0A0A")
    private val INK = Color.parseColor("#EDEDED")
    private val INK_MUTED = Color.parseColor("#6E6E6E")
    private val ACCENT_WARM = Color.parseColor("#F36F21")
    private val ACCENT_COOL = Color.parseColor("#41BDF5")
    private val HAIRLINE = Color.parseColor("#2A2A2A")

    /** Build the snapshot bitmap. The caller owns the bitmap and must recycle. */
    fun render(state: EnergyViewModel.UiState): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(BG)

        val titlePaint = paint(ACCENT_WARM, 56f, Typeface.DEFAULT_BOLD)
        val tsPaint = paint(INK_MUTED, 28f, Typeface.DEFAULT)
        val labelPaint = paint(INK_MUTED, 30f, Typeface.DEFAULT_BOLD)
        val bigPaint = paint(INK, 84f, Typeface.DEFAULT_BOLD)
        val rowPaint = paint(INK, 34f, Typeface.DEFAULT)
        val rowMutedPaint = paint(INK_MUTED, 30f, Typeface.DEFAULT)
        val hairlinePaint = Paint().apply {
            color = HAIRLINE; strokeWidth = 2f
            style = Paint.Style.STROKE
        }

        var y = PADDING + 56f
        canvas.drawText("R1HA · ENERGY", PADDING, y, titlePaint)
        y += 44f
        canvas.drawText(
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
            PADDING, y, tsPaint,
        )
        y += 28f
        canvas.drawLine(PADDING, y, SIZE - PADDING, y, hairlinePaint)
        y += 72f

        // DRAW (left) + PRODUCTION (right) paired row. The values already
        // carry their unit (W or kW) so the share image matches the in-app
        // tiles exactly, including PRODUCTION, which previously rendered as a
        // bare number with no unit.
        val halfWidth = (SIZE - PADDING * 2) / 2
        canvas.drawText("DRAW", PADDING, y, labelPaint)
        canvas.drawText("PRODUCTION", PADDING + halfWidth, y, labelPaint)
        y += 80f
        canvas.drawText(formatW(state.currentDrawW), PADDING, y, bigPaint)
        canvas.drawText(formatW(state.productionW), PADDING + halfWidth, y, bigPaint.apply { color = ACCENT_COOL })
        // Reset bigPaint colour for downstream uses (it'd otherwise stay cool).
        bigPaint.color = INK
        y += 70f

        // TODAY kWh — single row. Prefer the recorder-derived figure so the
        // share image carries the same authoritative TODAY total as the tile.
        val today = state.statsTodayKwh ?: state.todayKwh
        canvas.drawText("TODAY", PADDING, y, labelPaint)
        y += 80f
        canvas.drawText(formatKwh(today), PADDING, y, bigPaint)
        y += 70f
        canvas.drawLine(PADDING, y, SIZE - PADDING, y, hairlinePaint)
        y += 72f

        // TOP CONSUMERS table — up to 6 rows.
        canvas.drawText("TOP CONSUMERS", PADDING, y, labelPaint)
        y += 56f
        val rows = state.topConsumers.take(6)
        if (rows.isEmpty()) {
            canvas.drawText("(no power sensors)", PADDING, y, rowMutedPaint)
        } else {
            for (row in rows) {
                val w = formatW(row.watts)
                canvas.drawText(row.name.take(28), PADDING, y, rowPaint)
                val wWidth = textWidth(w, rowPaint)
                canvas.drawText(w, SIZE - PADDING - wWidth, y, rowPaint.apply { color = ACCENT_WARM })
                rowPaint.color = INK
                y += 50f
            }
        }

        // Footer — app + timestamp again at the bottom so even a tight crop
        // keeps a "what is this" attribution visible.
        val footer = "rabbit r1 · home assistant client"
        canvas.drawText(footer, PADDING, SIZE - PADDING + 8f, rowMutedPaint)

        return bmp
    }

    /** Write [bitmap] to the app's `cache/share/` directory and fire a
     *  send-intent so the user can drop it into any app. Returns the URI
     *  for diagnostic purposes; the share sheet is what the user actually
     *  interacts with. */
    fun shareAsPng(context: Context, bitmap: Bitmap, stem: String = "r1ha-energy"): android.net.Uri? {
        val cache = context.cacheDir.resolve("share")
        if (!cache.exists()) cache.mkdirs()
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = File(cache, "$stem-$stamp.png")
        return runCatching {
            FileOutputStream(file).use { out ->
                // Lossless PNG so the dark background stays artifact-free.
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val authority = "${context.packageName}.updates"
            val uri = FileProvider.getUriForFile(context, authority, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, "R1HA energy snapshot")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Share energy snapshot").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            uri
        }.onFailure { t ->
            R1Log.w("EnergyShare", "share failed: ${t.message}")
            Toaster.error("Share failed: ${t.message ?: "unknown"}")
        }.getOrNull()
    }

    private fun paint(color: Int, size: Float, typeface: Typeface): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        this.typeface = typeface
    }

    private fun textWidth(text: String, paint: Paint): Float = paint.measureText(text)

    // Locale.US so comma-decimal locales (de, fr) still see "1234" / "12.34"
    // in the shared image — matching the in-app readout, which uses the same
    // approach for the live tile values. The shared PNG is a snapshot of the
    // UI; preserving its number formatting keeps the artefact consistent.
    private val FMT = java.util.Locale.US

    // Match EnergyScreen.formatWatts / formatKwh so the snapshot reads the
    // same as the live tiles: W up to ~999, kW above; sub-10 kWh keeps two
    // decimals, larger one. "n/a" rather than an em-dash keeps the artefact
    // em-dash-free and screen-reader friendly.
    private fun formatW(w: Double?): String = when {
        w == null -> "n/a"
        kotlin.math.abs(w) >= 1000 -> String.format(FMT, "%.1f kW", w / 1000.0)
        else -> String.format(FMT, "%.0f W", w)
    }

    private fun formatKwh(k: Double?): String = when {
        k == null -> "n/a"
        kotlin.math.abs(k) < 10 -> String.format(FMT, "%.2f kWh", k)
        else -> String.format(FMT, "%.1f kWh", k)
    }
}
