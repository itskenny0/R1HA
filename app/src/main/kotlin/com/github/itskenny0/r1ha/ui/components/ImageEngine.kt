package com.github.itskenny0.r1ha.ui.components

import java.util.Locale

/**
 * Pure (no-Compose, no-Android-UI) helpers for the HuiImage engine. Extracted
 * so unit tests can exercise them without a Compose runtime.
 *
 * Mirrors the logic inside HA's hui-image.ts for:
 *  - aspect_ratio string parsing (16x9, 16:9, 1.78, 50%)
 *  - CSS-ish filter string parsing to a 4x5 FloatArray (ColorMatrix row-major)
 *  - per-state image selection
 *  - off-state detection for default grayscale
 *  - camera-mode decision (auto / live / static)
 *
 * Any CSS filter function not in the supported subset is silently ignored so an
 * advanced desktop config still renders something on R1HA rather than failing.
 *
 * Filter result is a plain FloatArray (20 values, row-major 4x5) so unit tests
 * run on the JVM without an Android runtime. HuiImage.kt wraps it into an
 * android.graphics.ColorMatrix before constructing the Compose ColorFilter.
 */
object ImageEngine {

    // States HA's hui-image considers "off" for the default grayscale rule.
    // Matches STATES_OFF in src/common/const.ts ("closed", "locked", "off")
    // plus "unavailable" / "unknown" for the missing-entity case.
    val STATES_OFF: Set<String> = setOf("off", "closed", "locked", "unavailable", "unknown")

    /**
     * Parse HA's aspect_ratio string into a (width, height) pair. Returns null
     * on anything unparseable.
     *
     * Accepted shapes:
     *   "16x9"  / "16:9"   -> 16f to 9f
     *   "1.78"  / "1.78x1" -> 1.78f to 1f
     *   "50%"              -> 100f to 50f  (HA: % means height/width)
     */
    fun parseAspectRatio(raw: String?): Pair<Float, Float>? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        return when {
            s.endsWith("%") -> {
                val pct = s.dropLast(1).trim().toFloatOrNull() ?: return null
                if (pct <= 0f) return null
                Pair(100f, pct)
            }
            ':' in s || 'x' in s -> {
                val parts = s.replace(':', 'x').split('x')
                val w = parts.getOrNull(0)?.trim()?.toFloatOrNull() ?: return null
                // If a second segment is present it must parse as a positive float;
                // a non-numeric second segment ("16:abc") is rejected rather than
                // silently defaulting to 1f.
                val hStr = parts.getOrNull(1)?.trim()
                val h = if (hStr.isNullOrEmpty()) 1f else hStr.toFloatOrNull() ?: return null
                if (w <= 0f || h <= 0f) return null
                Pair(w, h)
            }
            else -> {
                val ratio = s.toFloatOrNull() ?: return null
                if (ratio <= 0f) return null
                Pair(ratio, 1f)
            }
        }
    }

    /**
     * Returns the aspect ratio as a float (w/h), or null when unparseable.
     */
    fun aspectRatioFloat(raw: String?): Float? {
        val (w, h) = parseAspectRatio(raw) ?: return null
        return w / h
    }

    /**
     * Parse a CSS-ish filter string into a 4x5 row-major FloatArray (20 values)
     * representing a color-matrix transform, ready to be wrapped in an
     * android.graphics.ColorMatrix or androidx.compose.ui.graphics.ColorMatrix.
     *
     * Supported functions (space-separated, combinable):
     *   grayscale(N%)    -- 0=color, 100%=full gray
     *   brightness(N%)   -- 100%=identity, <100 darker, >100 brighter
     *   saturate(N%)     -- 100%=identity, 0=gray, >100 hypersaturated
     *   sepia(N%)        -- 0=none, 100%=full sepia
     *   opacity(N%)      -- 100%=opaque, 0=transparent
     *
     * All other functions (contrast, blur, drop-shadow, hue-rotate, etc.) are
     * silently skipped; the remaining transforms still apply in declaration order.
     *
     * Returns null when [filter] is blank or every function is unsupported.
     */
    fun parseFilterString(filter: String?): FloatArray? {
        if (filter.isNullOrBlank()) return null
        // Start with the identity matrix.
        var result = identityMatrix()
        var applied = false
        val funcRegex = Regex("""([a-zA-Z\-]+)\s*\(([^)]*)\)""")
        funcRegex.findAll(filter).forEach { match ->
            val name = match.groupValues[1].trim().lowercase(Locale.US)
            val arg = match.groupValues[2].trim()
            val value = parseFilterArg(arg) ?: return@forEach
            when (name) {
                "grayscale" -> {
                    result = multiplyMatrices(result, grayscaleMatrix((value / 100f).coerceIn(0f, 1f)))
                    applied = true
                }
                "brightness" -> {
                    result = multiplyMatrices(result, brightnessMatrix((value / 100f).coerceAtLeast(0f)))
                    applied = true
                }
                "saturate", "saturatevideo" -> {
                    result = multiplyMatrices(result, saturateMatrix((value / 100f).coerceAtLeast(0f)))
                    applied = true
                }
                "sepia" -> {
                    result = multiplyMatrices(result, sepiaMatrix((value / 100f).coerceIn(0f, 1f)))
                    applied = true
                }
                "opacity" -> {
                    result = multiplyMatrices(result, opacityMatrix((value / 100f).coerceIn(0f, 1f)))
                    applied = true
                }
                // contrast, blur, hue-rotate, drop-shadow, invert: not expressible
                // as a ColorMatrix; ignored so unknown configs degrade gracefully.
                else -> Unit
            }
        }
        return if (applied) result else null
    }

    // ── Matrix builders — all return FloatArray (20 values, row-major 4x5) ──────

    /** 4x5 identity color matrix. */
    private fun identityMatrix(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    private fun grayscaleMatrix(amount: Float): FloatArray {
        val inv = 1f - amount
        // Luminance weights per ITU-R BT.601
        val rW = 0.2126f; val gW = 0.7152f; val bW = 0.0722f
        return floatArrayOf(
            inv + amount * rW, amount * gW,       amount * bW,       0f, 0f,
            amount * rW,       inv + amount * gW, amount * bW,       0f, 0f,
            amount * rW,       amount * gW,       inv + amount * bW, 0f, 0f,
            0f,                0f,                0f,                1f, 0f,
        )
    }

    private fun brightnessMatrix(scale: Float): FloatArray = floatArrayOf(
        scale, 0f,    0f,    0f, 0f,
        0f,    scale, 0f,    0f, 0f,
        0f,    0f,    scale, 0f, 0f,
        0f,    0f,    0f,    1f, 0f,
    )

    /**
     * Saturation matrix using the ITU-R BT.601 luminance weights, matching
     * Android's ColorMatrix.setSaturation() formula.
     */
    private fun saturateMatrix(scale: Float): FloatArray {
        val invSat = 1f - scale
        val rW = 0.213f; val gW = 0.715f; val bW = 0.072f
        return floatArrayOf(
            invSat * rW + scale, invSat * gW,         invSat * bW,         0f, 0f,
            invSat * rW,         invSat * gW + scale, invSat * bW,         0f, 0f,
            invSat * rW,         invSat * gW,         invSat * bW + scale, 0f, 0f,
            0f,                  0f,                  0f,                  1f, 0f,
        )
    }

    private fun sepiaMatrix(amount: Float): FloatArray {
        val inv = 1f - amount
        return floatArrayOf(
            inv + 0.393f * amount, 0.769f * amount,       0.189f * amount,       0f, 0f,
            0.349f * amount,       inv + 0.686f * amount, 0.168f * amount,       0f, 0f,
            0.272f * amount,       0.534f * amount,       inv + 0.131f * amount, 0f, 0f,
            0f,                    0f,                    0f,                    1f, 0f,
        )
    }

    private fun opacityMatrix(alpha: Float): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, alpha, 0f,
    )

    /**
     * Multiply two 4x5 color matrices as if they were augmented 5x5 matrices
     * (the 5th row is implicitly [0,0,0,0,1]). This replicates the postConcat
     * semantics of android.graphics.ColorMatrix without depending on Android.
     */
    private fun multiplyMatrices(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(20)
        // Rows 0-3 of the result
        for (row in 0..3) {
            for (col in 0..4) {
                var sum = 0f
                for (k in 0..3) {
                    sum += a[row * 5 + k] * b[k * 5 + col]
                }
                // For col < 4: implicit b[4*5+col]=0; for col==4: implicit b[4*5+4]=1
                if (col == 4) sum += a[row * 5 + 4]
                out[row * 5 + col] = sum
            }
        }
        return out
    }

    /**
     * Parse a CSS filter argument to a 0-100 percent float.
     * "100%" -> 100.0; "1" -> 100.0 (CSS bare-decimal convention).
     */
    internal fun parseFilterArg(arg: String): Float? {
        val s = arg.trim()
        return if (s.endsWith("%")) {
            s.dropLast(1).trim().toFloatOrNull()
        } else {
            val v = s.toFloatOrNull() ?: return null
            v * 100f
        }
    }

    /**
     * True when [entityState] matches HA's STATES_OFF set or the entity is absent.
     * Mirrors hui-image.ts: "!stateObj || STATES_OFF.includes(entityState)".
     */
    fun isOffState(entityState: String?): Boolean =
        entityState == null || entityState in STATES_OFF

    /**
     * Return the URL from [stateImage] that matches [entityState], or null when
     * no entry matches so the caller can fall back to the static image.
     */
    fun resolveStateImage(stateImage: kotlin.collections.Map<String, String>?, entityState: String?): String? {
        if (stateImage.isNullOrEmpty() || entityState == null) return null
        return stateImage[entityState]
    }

    /**
     * Decide the camera-mode to use.
     *
     * Native HLS/WebRTC is deliberately out of scope: the R1 GSI's power
     * management kills long-running HTTP sockets mid-stream, and WebRTC
     * requires TURN infrastructure. JPEG polling gives "live enough" for the
     * kiosk use-case at much lower complexity. See CameraSnapshot.kt.
     */
    fun cameraMode(cameraImage: String?, cameraView: String?): CameraMode {
        if (cameraImage.isNullOrBlank()) return CameraMode.Static
        return when (cameraView?.lowercase(Locale.US)) {
            "live" -> CameraMode.Live
            else -> CameraMode.Auto
        }
    }

    /**
     * True when [entityId] is an image.* entity. Such entities should have their
     * image URL refreshed whenever the entity's state (a capture timestamp) changes.
     */
    fun isImageDomain(entityId: String?): Boolean =
        entityId?.startsWith("image.") == true

    enum class CameraMode { Static, Auto, Live }
}
