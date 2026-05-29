package com.github.itskenny0.r1ha.feature.updates

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test

/**
 * Pure-logic coverage for the Updates surface: supported_features bitmask
 * decoding, the polymorphic in_progress / update_percentage fields, the
 * skipped_version reconciliation, entity_id bucketing, title fallback, the
 * update-available guard, and the dotted version comparator. No Compose or
 * repository dependencies.
 */
class UpdatesLogicTest {

    private fun attrs(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    }

    // ── supported_features ──────────────────────────────────────────────────

    @Test
    fun `supportedFeatures parses the bitmask and defaults to zero`() {
        assertThat(UpdatesLogic.supportedFeatures(attrs("supported_features" to "29"))).isEqualTo(29)
        assertThat(UpdatesLogic.supportedFeatures(attrs())).isEqualTo(0)
        assertThat(UpdatesLogic.supportedFeatures(attrs("supported_features" to "nope"))).isEqualTo(0)
    }

    @Test
    fun `feature flags decode independently`() {
        // 0x01 install + 0x08 backup + 0x10 release_notes = 25
        val f = 0x01 or 0x08 or 0x10
        assertThat(UpdatesLogic.supportsInstall(f)).isTrue()
        assertThat(UpdatesLogic.supportsBackup(f)).isTrue()
        assertThat(UpdatesLogic.supportsProgress(f)).isFalse()
        assertThat(UpdatesLogic.hasFeature(f, UpdatesLogic.FEATURE_RELEASE_NOTES)).isTrue()
    }

    @Test
    fun `backup support is off when the bit is clear`() {
        assertThat(UpdatesLogic.supportsBackup(0x01 or 0x04)).isFalse()
        assertThat(UpdatesLogic.supportsBackup(0)).isFalse()
    }

    // ── in_progress ─────────────────────────────────────────────────────────

    @Test
    fun `inProgress accepts bool and positive-int forms and rejects the rest`() {
        assertThat(UpdatesLogic.inProgress(JsonPrimitive(true))).isTrue()
        assertThat(UpdatesLogic.inProgress(JsonPrimitive("true"))).isTrue()
        assertThat(UpdatesLogic.inProgress(JsonPrimitive(42))).isTrue()
        assertThat(UpdatesLogic.inProgress(JsonPrimitive(false))).isFalse()
        assertThat(UpdatesLogic.inProgress(JsonPrimitive(0))).isFalse()
        assertThat(UpdatesLogic.inProgress(null)).isFalse()
    }

    // ── update_percentage ───────────────────────────────────────────────────

    @Test
    fun `progressPercent prefers update_percentage and clamps`() {
        assertThat(UpdatesLogic.progressPercent(attrs("update_percentage" to "37"))).isEqualTo(37)
        assertThat(UpdatesLogic.progressPercent(attrs("update_percentage" to "150"))).isEqualTo(100)
        assertThat(UpdatesLogic.progressPercent(attrs("update_percentage" to "-5"))).isEqualTo(0)
    }

    @Test
    fun `progressPercent falls back to an int in_progress`() {
        assertThat(UpdatesLogic.progressPercent(attrs("in_progress" to "60"))).isEqualTo(60)
    }

    @Test
    fun `progressPercent is null when no real number is reported`() {
        assertThat(UpdatesLogic.progressPercent(attrs())).isNull()
        assertThat(UpdatesLogic.progressPercent(attrs("in_progress" to "true"))).isNull()
        assertThat(UpdatesLogic.progressPercent(attrs("update_percentage" to "null"))).isNull()
    }

    // ── skipped_version ─────────────────────────────────────────────────────

    @Test
    fun `isSkipped matches the offered latest`() {
        assertThat(UpdatesLogic.isSkipped("2024.5.0", "2024.5.0")).isTrue()
        // a newer release supersedes the skip
        assertThat(UpdatesLogic.isSkipped("2024.5.0", "2024.6.0")).isFalse()
        // latest unknown: treat the recorded skip as active
        assertThat(UpdatesLogic.isSkipped("2024.5.0", null)).isTrue()
    }

    @Test
    fun `isSkipped ignores blank and literal-null skipped_version`() {
        assertThat(UpdatesLogic.isSkipped(null, "2024.5.0")).isFalse()
        assertThat(UpdatesLogic.isSkipped("", "2024.5.0")).isFalse()
        assertThat(UpdatesLogic.isSkipped("null", "2024.5.0")).isFalse()
    }

    // ── update-available guard ──────────────────────────────────────────────

    @Test
    fun `updateAvailable honours state on with differing versions`() {
        assertThat(UpdatesLogic.updateAvailable("on", "1.0", "1.1")).isTrue()
        assertThat(UpdatesLogic.updateAvailable("on", null, "1.1")).isTrue()
        assertThat(UpdatesLogic.updateAvailable("off", "1.0", "1.1")).isFalse()
        // stale state with identical versions is suppressed
        assertThat(UpdatesLogic.updateAvailable("on", "1.1", "1.1")).isFalse()
    }

    // ── stringAttr ──────────────────────────────────────────────────────────

    @Test
    fun `stringAttr drops blank and literal-null values`() {
        assertThat(UpdatesLogic.stringAttr(attrs("title" to "Hub"), "title")).isEqualTo("Hub")
        assertThat(UpdatesLogic.stringAttr(attrs("title" to "  "), "title")).isNull()
        assertThat(UpdatesLogic.stringAttr(attrs("title" to "null"), "title")).isNull()
        assertThat(UpdatesLogic.stringAttr(attrs(), "title")).isNull()
    }

    // ── bucketing ───────────────────────────────────────────────────────────

    @Test
    fun `bucketFor classifies by entity_id convention`() {
        assertThat(UpdatesLogic.bucketFor("update.home_assistant_core_update"))
            .isEqualTo(UpdatesViewModel.Bucket.CORE)
        assertThat(UpdatesLogic.bucketFor("update.home_assistant_operating_system_update"))
            .isEqualTo(UpdatesViewModel.Bucket.CORE)
        assertThat(UpdatesLogic.bucketFor("update.supervisor"))
            .isEqualTo(UpdatesViewModel.Bucket.CORE)
        assertThat(UpdatesLogic.bucketFor("update.mosquitto_broker_update"))
            .isEqualTo(UpdatesViewModel.Bucket.ADDON)
        assertThat(UpdatesLogic.bucketFor("update.shelly_plug_firmware"))
            .isEqualTo(UpdatesViewModel.Bucket.INTEGRATION)
    }

    // ── title fallback ──────────────────────────────────────────────────────

    @Test
    fun `titleFor walks the fallback chain`() {
        assertThat(UpdatesLogic.titleFor("Home Assistant Core", "core", "update.ha_core"))
            .isEqualTo("Home Assistant Core")
        assertThat(UpdatesLogic.titleFor(null, "Friendly", "update.ha_core"))
            .isEqualTo("Friendly")
        assertThat(UpdatesLogic.titleFor("  ", "", "update.shelly_plug"))
            .isEqualTo("shelly plug")
    }

    // ── version comparison ──────────────────────────────────────────────────

    @Test
    fun `compareVersions orders numeric segments numerically`() {
        assertThat(UpdatesLogic.compareVersions("2024.1.3", "2024.12.0")).isLessThan(0)
        assertThat(UpdatesLogic.compareVersions("2024.12.0", "2024.1.3")).isGreaterThan(0)
        assertThat(UpdatesLogic.compareVersions("1.2.3", "1.2.3")).isEqualTo(0)
        // lexical compare would wrongly rank 9 > 10
        assertThat(UpdatesLogic.compareVersions("1.9.0", "1.10.0")).isLessThan(0)
    }

    @Test
    fun `compareVersions ranks release above pre-release on the same prefix`() {
        // a trailing release segment makes the longer version newer
        assertThat(UpdatesLogic.compareVersions("2.0", "2.0.1")).isLessThan(0)
        // a trailing pre-release tag makes the shorter (final) version newer
        assertThat(UpdatesLogic.compareVersions("2.0", "2.0-rc1")).isGreaterThan(0)
    }

    @Test
    fun `compareVersions treats nulls as oldest`() {
        assertThat(UpdatesLogic.compareVersions(null, "1.0")).isLessThan(0)
        assertThat(UpdatesLogic.compareVersions("1.0", null)).isGreaterThan(0)
        assertThat(UpdatesLogic.compareVersions(null, null)).isEqualTo(0)
    }
}
