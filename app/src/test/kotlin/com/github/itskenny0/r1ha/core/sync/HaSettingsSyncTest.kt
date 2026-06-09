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
}
