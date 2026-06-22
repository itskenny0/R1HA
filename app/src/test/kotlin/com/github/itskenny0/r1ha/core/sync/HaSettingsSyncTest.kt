package com.github.itskenny0.r1ha.core.sync

import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.Behavior
import com.github.itskenny0.r1ha.core.prefs.ServerConfig
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HaSettingsSyncTest {

    /**
     * Regression: the one-shot "wheel to adjust" hint reappeared after every
     * sync. Its seen-flag lives in [Behavior], so a pull that included the
     * Behaviour category overwrote it with a remote value. It is per-device
     * onboarding state and must survive a remote apply unconditionally.
     */
    @Test fun `preserveDeviceLocal keeps the device-local wheel tutorial flag`() {
        val prev = AppSettings(
            server = ServerConfig(url = "http://ha.local:8123"),
            behavior = Behavior(wheelTutorialSeen = true),
        )
        // Remote backup carries the flag at false (pushed by a device that
        // never fired a wheel event) and a sanitized null server.
        val applied = AppSettings(
            server = null,
            behavior = Behavior(wheelTutorialSeen = false),
        )

        val merged = preserveDeviceLocal(applied, prev)

        assertThat(merged.behavior.wheelTutorialSeen).isTrue()
        // Sanity: the other device-local restore (server) still works.
        assertThat(merged.server?.url).isEqualTo("http://ha.local:8123")
    }

    /**
     * The preservation is surgical: genuinely-synced Behaviour fields from the
     * remote still apply, only the onboarding flag is pinned to the local value.
     */
    @Test fun `preserveDeviceLocal does not revert real synced behaviour fields`() {
        val prev = AppSettings(
            behavior = Behavior(wheelTutorialSeen = true, startOnDashboard = false),
        )
        val applied = AppSettings(
            behavior = Behavior(wheelTutorialSeen = false, startOnDashboard = true),
        )

        val merged = preserveDeviceLocal(applied, prev)

        // Device-local onboarding flag preserved from prev.
        assertThat(merged.behavior.wheelTutorialSeen).isTrue()
        // A real synced behaviour change from the remote is honoured.
        assertThat(merged.behavior.startOnDashboard).isTrue()
    }

    /**
     * The what's-new stamp is per-device launch state like the wheel hint: a
     * remote value would re-show (or wrongly suppress) the overlay after sync.
     */
    @Test fun `preserveDeviceLocal keeps the device-local whats-new stamp`() {
        val prev = AppSettings(
            behavior = Behavior(lastSeenVersionCode = 250609),
        )
        val applied = AppSettings(
            behavior = Behavior(lastSeenVersionCode = 0),
        )

        val merged = preserveDeviceLocal(applied, prev)

        assertThat(merged.behavior.lastSeenVersionCode).isEqualTo(250609)
    }

    /**
     * Regression (the "tab loop"): activePageId is the currently-open tab,
     * ephemeral per-device UI state, not a shared preference. A pull must not
     * overwrite it with another device's open tab. On a receive-only device,
     * which can never push its own selection back, a foreign activePageId the
     * card stack can't reconcile drove a ~5x/second settings-write ping-pong
     * (pager bouncing between the foreign page and the local one).
     */
    @Test fun `preserveDeviceLocal keeps the device-local active tab`() {
        val prev = AppSettings(activePageId = "home")
        // Remote backup was authored on a device whose open tab was a different
        // page id.
        val applied = AppSettings(activePageId = "p9b79711d")

        val merged = preserveDeviceLocal(applied, prev)

        assertThat(merged.activePageId).isEqualTo("home")
    }

    /**
     * The sync configuration is each device's own stance and must never be
     * adopted from a remote payload: whether sync is on, its direction
     * (receive-only), cadence, manual-only flag, and the seen-prompt flag all
     * stay local regardless of the Integrations category opt-in. Syncing
     * haSyncEnabled in particular could cascade a single device's "off" toggle
     * across the whole fleet.
     */
    @Test fun `preserveDeviceLocal keeps the device-local sync configuration`() {
        val base = AppSettings()
        val prev = base.copy(
            integrations = base.integrations.copy(
                haSyncEnabled = true,
                haSyncReadOnly = true,
                haSyncManualOnly = true,
                haSyncIntervalSec = 60,
                haSyncPromptSeen = true,
            ),
        )
        // Remote authored by a two-way, faster-polling device that had sync off
        // and never saw the prompt.
        val applied = base.copy(
            integrations = base.integrations.copy(
                haSyncEnabled = false,
                haSyncReadOnly = false,
                haSyncManualOnly = false,
                haSyncIntervalSec = 300,
                haSyncPromptSeen = false,
            ),
        )

        val merged = preserveDeviceLocal(applied, prev)

        assertThat(merged.integrations.haSyncEnabled).isTrue()
        assertThat(merged.integrations.haSyncReadOnly).isTrue()
        assertThat(merged.integrations.haSyncManualOnly).isTrue()
        assertThat(merged.integrations.haSyncIntervalSec).isEqualTo(60)
        assertThat(merged.integrations.haSyncPromptSeen).isTrue()
    }
}
