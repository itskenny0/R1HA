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
}
