package com.github.itskenny0.r1ha.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [ImageEngine] -- the pure logic helpers behind the HuiImage
 * composable. JVM-only (no Compose / Android UI required).
 */
class HuiImageEngineTest {

    // ── aspect-ratio parsing ─────────────────────────────────────────────────

    @Test fun `parseAspectRatio parses colon form`() {
        val (w, h) = ImageEngine.parseAspectRatio("16:9")!!
        assertThat(w).isEqualTo(16f)
        assertThat(h).isEqualTo(9f)
    }

    @Test fun `parseAspectRatio parses x form`() {
        val (w, h) = ImageEngine.parseAspectRatio("16x9")!!
        assertThat(w).isEqualTo(16f)
        assertThat(h).isEqualTo(9f)
    }

    @Test fun `parseAspectRatio parses decimal scalar`() {
        val (w, h) = ImageEngine.parseAspectRatio("1.78")!!
        assertThat(w).isWithin(0.001f).of(1.78f)
        assertThat(h).isEqualTo(1f)
    }

    @Test fun `parseAspectRatio parses percentage`() {
        // "50%" means height is 50% of width => w:h = 100:50
        val (w, h) = ImageEngine.parseAspectRatio("50%")!!
        assertThat(w).isEqualTo(100f)
        assertThat(h).isEqualTo(50f)
    }

    @Test fun `parseAspectRatio rejects blank`() {
        assertThat(ImageEngine.parseAspectRatio(null)).isNull()
        assertThat(ImageEngine.parseAspectRatio("")).isNull()
        assertThat(ImageEngine.parseAspectRatio("   ")).isNull()
    }

    @Test fun `parseAspectRatio rejects non-numeric`() {
        assertThat(ImageEngine.parseAspectRatio("abc")).isNull()
        assertThat(ImageEngine.parseAspectRatio("16:abc")).isNull()
    }

    @Test fun `parseAspectRatio rejects zero or negative`() {
        assertThat(ImageEngine.parseAspectRatio("0")).isNull()
        assertThat(ImageEngine.parseAspectRatio("-1:2")).isNull()
    }

    @Test fun `aspectRatioFloat returns width over height`() {
        val ratio = ImageEngine.aspectRatioFloat("16:9")
        assertThat(ratio).isWithin(0.001f).of(16f / 9f)
    }

    // ── filter-string parsing ────────────────────────────────────────────────

    @Test fun `parseFilterArg handles percent`() {
        assertThat(ImageEngine.parseFilterArg("100%")).isEqualTo(100f)
        assertThat(ImageEngine.parseFilterArg("50%")).isEqualTo(50f)
    }

    @Test fun `parseFilterArg handles bare decimal`() {
        assertThat(ImageEngine.parseFilterArg("1")).isWithin(0.001f).of(100f)
        assertThat(ImageEngine.parseFilterArg("0.5")).isWithin(0.001f).of(50f)
    }

    @Test fun `parseFilterArg returns null for garbage`() {
        assertThat(ImageEngine.parseFilterArg("abc")).isNull()
        assertThat(ImageEngine.parseFilterArg("")).isNull()
    }

    @Test fun `parseFilterString returns null for blank input`() {
        assertThat(ImageEngine.parseFilterString(null)).isNull()
        assertThat(ImageEngine.parseFilterString("")).isNull()
        assertThat(ImageEngine.parseFilterString("   ")).isNull()
    }

    @Test fun `parseFilterString returns null for unsupported-only input`() {
        // contrast and blur are unsupported; should yield null so the caller
        // falls through to the off-state default grayscale rule.
        assertThat(ImageEngine.parseFilterString("contrast(50%) blur(4px)")).isNull()
    }

    @Test fun `parseFilterString parses grayscale 100 percent`() {
        val arr = ImageEngine.parseFilterString("grayscale(100%)")
        assertThat(arr).isNotNull()
        assertThat(arr!!.size).isEqualTo(20)
        // Full grayscale: R and G channels of the R-output row should have equal luminance contribution
        assertThat(arr[0]).isWithin(0.01f).of(arr[5])
    }

    @Test fun `parseFilterString parses brightness`() {
        val arr = ImageEngine.parseFilterString("brightness(50%)")
        assertThat(arr).isNotNull()
        // brightness(0.5): scale R,G,B by 0.5
        assertThat(arr!![0]).isWithin(0.001f).of(0.5f)
        assertThat(arr[6]).isWithin(0.001f).of(0.5f)
        assertThat(arr[12]).isWithin(0.001f).of(0.5f)
        // alpha unchanged
        assertThat(arr[18]).isWithin(0.001f).of(1f)
    }

    @Test fun `parseFilterString parses opacity`() {
        val arr = ImageEngine.parseFilterString("opacity(50%)")
        assertThat(arr).isNotNull()
        // opacity(0.5): alpha scale = 0.5
        assertThat(arr!![18]).isWithin(0.001f).of(0.5f)
    }

    @Test fun `parseFilterString combines multiple functions`() {
        val arr = ImageEngine.parseFilterString("grayscale(100%) brightness(50%)")
        assertThat(arr).isNotNull()
        assertThat(arr!!.size).isEqualTo(20)
    }

    @Test fun `parseFilterString ignores unsupported functions but applies supported ones`() {
        // blur is unsupported; grayscale should still apply
        val arr = ImageEngine.parseFilterString("blur(4px) grayscale(100%)")
        assertThat(arr).isNotNull()
    }

    @Test fun `parseFilterString parses sepia`() {
        assertThat(ImageEngine.parseFilterString("sepia(100%)")).isNotNull()
    }

    @Test fun `parseFilterString parses saturate`() {
        assertThat(ImageEngine.parseFilterString("saturate(50%)")).isNotNull()
    }

    // ── off-state detection ──────────────────────────────────────────────────

    @Test fun `isOffState true for null entity`() {
        assertThat(ImageEngine.isOffState(null)).isTrue()
    }

    @Test fun `isOffState true for STATES_OFF members`() {
        for (s in listOf("off", "closed", "locked", "unavailable", "unknown")) {
            assertThat(ImageEngine.isOffState(s)).isTrue()
        }
    }

    @Test fun `isOffState false for on states`() {
        for (s in listOf("on", "open", "unlocked", "home", "idle", "playing")) {
            assertThat(ImageEngine.isOffState(s)).isFalse()
        }
    }

    // ── state-image resolution ───────────────────────────────────────────────

    @Test fun `resolveStateImage returns matching URL`() {
        val map = mapOf("on" to "/local/on.png", "off" to "/local/off.png")
        assertThat(ImageEngine.resolveStateImage(map, "on")).isEqualTo("/local/on.png")
        assertThat(ImageEngine.resolveStateImage(map, "off")).isEqualTo("/local/off.png")
    }

    @Test fun `resolveStateImage returns null for no match`() {
        val map = mapOf("on" to "/local/on.png")
        assertThat(ImageEngine.resolveStateImage(map, "idle")).isNull()
    }

    @Test fun `resolveStateImage returns null for null state`() {
        val map = mapOf("on" to "/local/on.png")
        assertThat(ImageEngine.resolveStateImage(map, null)).isNull()
    }

    @Test fun `resolveStateImage returns null for empty map`() {
        assertThat(ImageEngine.resolveStateImage(emptyMap(), "on")).isNull()
        assertThat(ImageEngine.resolveStateImage(null, "on")).isNull()
    }

    // ── camera-mode decision ─────────────────────────────────────────────────

    @Test fun `cameraMode Static when no camera entity`() {
        assertThat(ImageEngine.cameraMode(null, null)).isEqualTo(ImageEngine.CameraMode.Static)
        assertThat(ImageEngine.cameraMode("", null)).isEqualTo(ImageEngine.CameraMode.Static)
        assertThat(ImageEngine.cameraMode("  ", null)).isEqualTo(ImageEngine.CameraMode.Static)
    }

    @Test fun `cameraMode Auto when camera entity with no cameraView`() {
        assertThat(ImageEngine.cameraMode("camera.front_door", null))
            .isEqualTo(ImageEngine.CameraMode.Auto)
    }

    @Test fun `cameraMode Auto when camera entity with cameraView auto`() {
        assertThat(ImageEngine.cameraMode("camera.front_door", "auto"))
            .isEqualTo(ImageEngine.CameraMode.Auto)
    }

    @Test fun `cameraMode Live when cameraView live`() {
        assertThat(ImageEngine.cameraMode("camera.front_door", "live"))
            .isEqualTo(ImageEngine.CameraMode.Live)
    }

    @Test fun `cameraMode Live is case-insensitive`() {
        assertThat(ImageEngine.cameraMode("camera.front_door", "LIVE"))
            .isEqualTo(ImageEngine.CameraMode.Live)
        assertThat(ImageEngine.cameraMode("camera.front_door", "Live"))
            .isEqualTo(ImageEngine.CameraMode.Live)
    }

    // ── image domain ─────────────────────────────────────────────────────────

    @Test fun `isImageDomain true for image entities`() {
        assertThat(ImageEngine.isImageDomain("image.doorbell")).isTrue()
        assertThat(ImageEngine.isImageDomain("image.cat_photo")).isTrue()
    }

    @Test fun `isImageDomain false for other domains`() {
        assertThat(ImageEngine.isImageDomain("camera.front_door")).isFalse()
        assertThat(ImageEngine.isImageDomain("sensor.temperature")).isFalse()
        assertThat(ImageEngine.isImageDomain(null)).isFalse()
    }

    // ── resolveHuiUrl helper ─────────────────────────────────────────────────

    @Test fun `resolveHuiUrl passes absolute http urls`() {
        assertThat(resolveHuiUrl("https://example.com/img.jpg", null))
            .isEqualTo("https://example.com/img.jpg")
    }

    @Test fun `resolveHuiUrl resolves relative paths against server`() {
        assertThat(resolveHuiUrl("/api/camera_proxy/camera.front", "http://ha.local:8123"))
            .isEqualTo("http://ha.local:8123/api/camera_proxy/camera.front")
    }

    @Test fun `resolveHuiUrl returns null for media-source`() {
        assertThat(resolveHuiUrl("media-source://media_source/my/image.jpg", "http://ha.local"))
            .isNull()
    }

    @Test fun `resolveHuiUrl returns null for relative path without server`() {
        assertThat(resolveHuiUrl("/local/image.png", null)).isNull()
        assertThat(resolveHuiUrl("/local/image.png", "")).isNull()
    }

    @Test fun `resolveHuiUrl returns null for blank`() {
        assertThat(resolveHuiUrl(null, "http://ha.local")).isNull()
        assertThat(resolveHuiUrl("", "http://ha.local")).isNull()
    }

    @Test fun `resolveHuiUrl passes data URIs`() {
        val dataUri = "data:image/png;base64,abc123"
        assertThat(resolveHuiUrl(dataUri, null)).isEqualTo(dataUri)
    }
}
