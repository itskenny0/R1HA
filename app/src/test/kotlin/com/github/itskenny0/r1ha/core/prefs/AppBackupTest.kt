package com.github.itskenny0.r1ha.core.prefs

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppBackupTest {

    /**
     * Regression: applyOnto rebuilt Behavior() from scratch, so every field the
     * backup format doesn't serialize snapped back to its default on restore
     * AND on every HA settings-sync pull (sync rides the same codec). The
     * what's-new opt-out silently reverted to ON; quick-tile bindings and the
     * per-device version stamp were wiped the same way.
     */
    @Test fun `applyOnto preserves behavior fields the backup does not carry`() {
        val prev = AppSettings(
            behavior = Behavior(
                lastSeenVersionCode = 250609,
                quickTileEntityId = "light.kitchen",
                wheelTutorialSeen = true,
            ),
        )
        val backup = AppSettings().toBackup(createdAt = "2026-06-10T00:00:00Z")

        val applied = backup.applyOnto(prev)

        assertThat(applied.behavior.lastSeenVersionCode).isEqualTo(250609)
        assertThat(applied.behavior.quickTileEntityId).isEqualTo("light.kitchen")
        assertThat(applied.behavior.wheelTutorialSeen).isTrue()
    }

    /** The opt-out is an explicit preference, so it travels with the backup. */
    @Test fun `showWhatsNew round-trips through encode and apply`() {
        val source = AppSettings(behavior = Behavior(showWhatsNew = false))
        val raw = encodeBackup(source.toBackup(createdAt = "2026-06-10T00:00:00Z"))

        val applied = decodeBackup(raw).applyOnto(AppSettings())

        assertThat(applied.behavior.showWhatsNew).isFalse()
    }

    /** Old backups predate the field; they must decode as ON, not flip users off. */
    @Test fun `backups without showWhatsNew decode as enabled`() {
        val raw = encodeBackup(AppSettings().toBackup(createdAt = "2026-06-10T00:00:00Z"))
        val stripped = raw.replace(Regex("\"behaviorShowWhatsNew\"\\s*:\\s*(true|false),?"), "")

        val applied = decodeBackup(stripped).applyOnto(AppSettings(behavior = Behavior(showWhatsNew = true)))

        assertThat(applied.behavior.showWhatsNew).isTrue()
    }

    /** The font family is an explicit preference, so it travels with the backup. */
    @Test fun `fontFamilyName round-trips through encode and apply`() {
        val source = AppSettings(ui = UiOptions(fontFamilyName = "serif"))
        val raw = encodeBackup(source.toBackup(createdAt = "2026-06-10T00:00:00Z"))

        val applied = decodeBackup(raw).applyOnto(AppSettings())

        assertThat(applied.ui.fontFamilyName).isEqualTo("serif")
    }

    /** Vendor families have no legacy face; they still round-trip via the new slot. */
    @Test fun `vendor fontFamilyName round-trips even though the legacy face cannot carry it`() {
        val source = AppSettings(ui = UiOptions(fontFamilyName = "vendor-grotesk"))
        val raw = encodeBackup(source.toBackup(createdAt = "2026-06-10T00:00:00Z"))

        val applied = decodeBackup(raw).applyOnto(AppSettings())

        assertThat(applied.ui.fontFamilyName).isEqualTo("vendor-grotesk")
    }

    /**
     * Backups from the eight-face era carry only the legacy uiFontFace slot;
     * restore must map it onto the family-name model instead of dropping it.
     */
    @Test fun `eight-face-era backups map the legacy uiFontFace onto a family name`() {
        val raw = encodeBackup(AppSettings().toBackup(createdAt = "2026-06-10T00:00:00Z"))
        // Simulate an old file: no new field, an explicit legacy face.
        val legacy = raw
            .replace(Regex("\"uiFontFamilyName\"\\s*:\\s*\"[^\"]*\",?"), "")
            .replace("\"uiFontFace\": \"DEFAULT\"", "\"uiFontFace\": \"SERIF\"")
        assertThat(legacy).doesNotContain("uiFontFamilyName")

        val applied = decodeBackup(legacy).applyOnto(AppSettings())

        assertThat(applied.ui.fontFamilyName).isEqualTo("serif")
    }

    /** Old backups predate both font fields; they must decode as "" (the stock mix). */
    @Test fun `backups without any font field decode as the stock mix`() {
        val raw = encodeBackup(AppSettings().toBackup(createdAt = "2026-06-10T00:00:00Z"))
        val stripped = raw
            .replace(Regex("\"uiFontFace\"\\s*:\\s*\"\\w+\",?"), "")
            .replace(Regex("\"uiFontFamilyName\"\\s*:\\s*\"[^\"]*\",?"), "")
        assertThat(stripped).doesNotContain("uiFontFace")
        assertThat(stripped).doesNotContain("uiFontFamilyName")

        val applied = decodeBackup(stripped).applyOnto(AppSettings())

        assertThat(applied.ui.fontFamilyName).isEmpty()
    }

    /** New backups materialise the legacy slot so older builds restore close. */
    @Test fun `new backups write a best-effort legacy uiFontFace for older builds`() {
        val backup = AppSettings(ui = UiOptions(fontFamilyName = "monospace"))
            .toBackup(createdAt = "2026-06-10T00:00:00Z")

        assertThat(backup.uiFontFace).isEqualTo(FontFace.MONO)
        // No legacy equivalent collapses to DEFAULT, never to a wrong face.
        val vendor = AppSettings(ui = UiOptions(fontFamilyName = "vendor-grotesk"))
            .toBackup(createdAt = "2026-06-10T00:00:00Z")
        assertThat(vendor.uiFontFace).isEqualTo(FontFace.DEFAULT)
    }
}
