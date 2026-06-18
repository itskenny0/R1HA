package com.github.itskenny0.r1ha.core.update

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the tag → versionCode derivation against the release workflow's bash math.
 * Any drift here breaks the self-update check (it'd either fire on an already-
 * installed build or refuse to update to a strictly-newer one), so the math is
 * worth a couple of small regression tests.
 */
class AppUpdaterTest {

    @Test fun `date-only tag derives versionCode for midnight UTC`() {
        // Workflow path for legacy r1ha-YYYYMMDD tags — defaults HHmm = 0000.
        // 2026-05-13 00:00 UTC is exactly 2324 days × 1440 min past 2020-01-01
        // (2020 + 2024 are leap years, 2026 itself is not).
        val days = java.time.Duration.between(
            java.time.LocalDateTime.of(2020, 1, 1, 0, 0),
            java.time.LocalDateTime.of(2026, 5, 13, 0, 0),
        ).toMinutes() / 1440L
        val expected = 100_000_000L + days * 1440L
        assertThat(AppUpdater.versionCodeFromTag("r1ha-20260513")).isEqualTo(expected)
    }

    @Test fun `date-plus-time tag derives strictly larger versionCode than midnight`() {
        val midnight = AppUpdater.versionCodeFromTag("r1ha-20260513")!!
        val later = AppUpdater.versionCodeFromTag("r1ha-20260513-1409")!!
        assertThat(later).isGreaterThan(midnight)
        // 14:09 UTC = 849 minutes past midnight.
        assertThat(later - midnight).isEqualTo(849L)
    }

    @Test fun `malformed tag returns null instead of throwing`() {
        assertThat(AppUpdater.versionCodeFromTag("not-an-r1ha-tag")).isNull()
        assertThat(AppUpdater.versionCodeFromTag("r1ha-NOPE")).isNull()
        assertThat(AppUpdater.versionCodeFromTag("r1ha-20260513-XXYY")).isNull()
    }

    @Test fun `versionCode floor matches workflow constant`() {
        // 100M floor is the contract between defaultVersionCode(), the workflow,
        // and this updater. Document it as a test so a future change has to also
        // update the documented floor.
        val anyTag = AppUpdater.versionCodeFromTag("r1ha-20200101")!!
        assertThat(anyTag).isEqualTo(100_000_000L)
    }

    // ── Flavour asset selection ──────────────────────────────────────────────

    private fun asset(name: String, url: String = "https://github.com/itskenny0/R1HA/releases/download/t/$name") =
        AppUpdater.GhAsset(name = name, browser_download_url = url, size = 1_000L)

    @Test fun `github flavour picks the bare apk, never fdroid or legacy`() {
        // All three flavours present, legacy listed first so a naive firstOrNull
        // that only filters -fdroid- would wrongly grab it.
        val assets = listOf(
            asset("r1ha-legacy-2026.05.13.1409.apk"),
            asset("r1ha-fdroid-2026.05.13.1409.apk"),
            asset("r1ha-2026.05.13.1409.apk"),
        )
        val picked = AppUpdater.flavorAssetFor(assets, "github")
        assertThat(picked?.name).isEqualTo("r1ha-2026.05.13.1409.apk")
    }

    @Test fun `legacy flavour picks the legacy apk, never the github one`() {
        // Regression: R1HAL (applicationId ...r1ha.legacy) was installing the
        // github APK and landing as a SEPARATE app instead of updating itself.
        // github asset listed first to prove the legacy build doesn't grab it.
        val assets = listOf(
            asset("r1ha-2026.05.13.1409.apk"),
            asset("r1ha-fdroid-2026.05.13.1409.apk"),
            asset("r1ha-legacy-2026.05.13.1409.apk"),
        )
        val picked = AppUpdater.flavorAssetFor(assets, "legacy")
        assertThat(picked?.name).isEqualTo("r1ha-legacy-2026.05.13.1409.apk")
    }

    @Test fun `legacy flavour returns null when only a github apk is present`() {
        // A legacy build must NOT fall back to the github APK.
        val assets = listOf(asset("r1ha-2026.05.13.1409.apk"))
        assertThat(AppUpdater.flavorAssetFor(assets, "legacy")).isNull()
    }

    @Test fun `fdroid flavour picks the fdroid apk`() {
        val assets = listOf(
            asset("r1ha-2026.05.13.1409.apk"),
            asset("r1ha-fdroid-2026.05.13.1409.apk"),
        )
        val picked = AppUpdater.flavorAssetFor(assets, "fdroid")
        assertThat(picked?.name).isEqualTo("r1ha-fdroid-2026.05.13.1409.apk")
    }

    @Test fun `flavour selection returns null when no matching apk`() {
        // github build, only an fdroid asset present → no installable asset.
        val assets = listOf(asset("r1ha-fdroid-2026.05.13.1409.apk"))
        assertThat(AppUpdater.flavorAssetFor(assets, "github")).isNull()
        // Non-apk assets (e.g. a mapping.txt) are never picked.
        assertThat(AppUpdater.flavorAssetFor(listOf(asset("mapping.txt")), "github")).isNull()
    }

    // ── Trusted host gate ────────────────────────────────────────────────────

    @Test fun `only https github hosts are trusted`() {
        assertThat(AppUpdater.isTrustedAssetUrl("https://github.com/x/y/z.apk")).isTrue()
        assertThat(AppUpdater.isTrustedAssetUrl("https://objects.githubusercontent.com/z.apk")).isTrue()
        // http (not https) is rejected even on a github host.
        assertThat(AppUpdater.isTrustedAssetUrl("http://github.com/z.apk")).isFalse()
        // A look-alike host that isn't github.
        assertThat(AppUpdater.isTrustedAssetUrl("https://github.com.evil.test/z.apk")).isFalse()
        assertThat(AppUpdater.isTrustedAssetUrl("https://evil.test/z.apk")).isFalse()
    }

    // ── Release list mapping ─────────────────────────────────────────────────

    private fun release(tag: String, vararg assetNames: String) = AppUpdater.GhRelease(
        tag_name = tag,
        name = "R1HA $tag",
        body = "notes for $tag",
        published_at = "2026-05-13T14:09:00Z",
        assets = assetNames.map { asset(it) },
    )

    @Test fun `mapReleases keeps only matching-flavour installable releases sorted newest first`() {
        val releases = listOf(
            release("r1ha-20260510-0000", "r1ha-2026.05.10.0000.apk", "r1ha-fdroid-2026.05.10.0000.apk"),
            release("r1ha-20260513-1409", "r1ha-2026.05.13.1409.apk", "r1ha-fdroid-2026.05.13.1409.apk"),
            // No github asset → dropped for the github flavour.
            release("r1ha-20260511-0000", "r1ha-fdroid-2026.05.11.0000.apk"),
            // Unparseable tag → dropped.
            release("r1ha-NOPE", "r1ha-2026.05.12.0000.apk"),
        )
        val installed = AppUpdater.versionCodeFromTag("r1ha-20260510-0000")!!
        val mapped = AppUpdater.mapReleases(releases, "github", installed)

        // Two installable github releases, newest first.
        assertThat(mapped.map { it.tagName }).containsExactly(
            "r1ha-20260513-1409",
            "r1ha-20260510-0000",
        ).inOrder()
        // The installed one is flagged current.
        assertThat(mapped.first { it.tagName == "r1ha-20260510-0000" }.isCurrent).isTrue()
        assertThat(mapped.first { it.tagName == "r1ha-20260513-1409" }.isCurrent).isFalse()
        // Each carries the github asset, never the fdroid one.
        assertThat(mapped.all { !it.apkName.contains("-fdroid-") }).isTrue()
    }

    @Test fun `mapReleases for fdroid flavour selects fdroid assets only`() {
        val releases = listOf(
            release("r1ha-20260513-1409", "r1ha-2026.05.13.1409.apk", "r1ha-fdroid-2026.05.13.1409.apk"),
        )
        val mapped = AppUpdater.mapReleases(releases, "fdroid", installedVersionCode = 0L)
        assertThat(mapped).hasSize(1)
        assertThat(mapped.single().apkName).isEqualTo("r1ha-fdroid-2026.05.13.1409.apk")
    }

    @Test fun `mapReleases drops releases whose asset host is untrusted`() {
        val releases = listOf(
            AppUpdater.GhRelease(
                tag_name = "r1ha-20260513-1409",
                assets = listOf(asset("r1ha-2026.05.13.1409.apk", url = "https://evil.test/r1ha.apk")),
            ),
        )
        assertThat(AppUpdater.mapReleases(releases, "github", 0L)).isEmpty()
    }

    // ── Downgrade decision ───────────────────────────────────────────────────

    @Test fun `chosen older versionCode is a downgrade`() {
        val installed = AppUpdater.versionCodeFromTag("r1ha-20260513-1409")!!
        val older = AppUpdater.versionCodeFromTag("r1ha-20260510-0000")!!
        val newer = AppUpdater.versionCodeFromTag("r1ha-20260514-0000")!!
        // The UI's downgrade note keys off this same strict comparison.
        assertThat(older < installed).isTrue()
        assertThat(newer < installed).isFalse()
        assertThat(installed < installed).isFalse()
    }
}
