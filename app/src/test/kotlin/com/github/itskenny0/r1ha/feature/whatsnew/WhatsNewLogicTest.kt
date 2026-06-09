package com.github.itskenny0.r1ha.feature.whatsnew

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WhatsNewLogicTest {

    // ── Fresh installs ───────────────────────────────────────────────────────

    @Test fun `fresh install stamps silently instead of showing`() {
        // No server configured yet: nothing has "changed" for this user, so the
        // overlay would be noise on top of onboarding. Stamp so the NEXT release
        // is the first one that shows.
        assertThat(whatsNewAction(lastSeen = 0, current = 250609, configured = false))
            .isEqualTo(WhatsNewAction.STAMP_SILENTLY)
    }

    @Test fun `fresh install already stamped does nothing`() {
        assertThat(whatsNewAction(lastSeen = 250609, current = 250609, configured = false))
            .isEqualTo(WhatsNewAction.NOTHING)
    }

    // ── Upgrades ─────────────────────────────────────────────────────────────

    @Test fun `upgrade from a pre-feature version shows`() {
        // Configured install with no stamp: the user upgraded from a build that
        // predates the stamp field. Exactly the audience the overlay exists for.
        assertThat(whatsNewAction(lastSeen = 0, current = 250609, configured = true))
            .isEqualTo(WhatsNewAction.SHOW)
    }

    @Test fun `upgrade from an older stamped version shows`() {
        assertThat(whatsNewAction(lastSeen = 250101, current = 250609, configured = true))
            .isEqualTo(WhatsNewAction.SHOW)
    }

    @Test fun `same version does nothing`() {
        assertThat(whatsNewAction(lastSeen = 250609, current = 250609, configured = true))
            .isEqualTo(WhatsNewAction.NOTHING)
    }

    @Test fun `downgrade does nothing`() {
        // Sideloaded an older APK over a newer stamp: showing "what's new" for
        // things that just disappeared would be confusing.
        assertThat(whatsNewAction(lastSeen = 250609, current = 250101, configured = true))
            .isEqualTo(WhatsNewAction.NOTHING)
    }

    // ── Suppression toggle ───────────────────────────────────────────────────

    @Test fun `suppressed upgrade stamps silently instead of showing`() {
        // The user opted out (About toggle or the overlay's own affordance):
        // keep the stamp moving so re-enabling later doesn't dump a backlog.
        assertThat(whatsNewAction(lastSeen = 250101, current = 250609, configured = true, enabled = false))
            .isEqualTo(WhatsNewAction.STAMP_SILENTLY)
    }

    @Test fun `suppressed same version does nothing`() {
        assertThat(whatsNewAction(lastSeen = 250609, current = 250609, configured = true, enabled = false))
            .isEqualTo(WhatsNewAction.NOTHING)
    }

    // ── Changelog content sanity ─────────────────────────────────────────────

    @Test fun `changelog has entries and stays terse`() {
        assertThat(WHATS_NEW_ENTRIES).isNotEmpty()
        WHATS_NEW_ENTRIES.forEach { entry ->
            assertThat(entry).isNotEmpty()
            // The overlay is a small portrait panel; long paragraphs wrap badly.
            assertThat(entry.length).isLessThan(120)
        }
    }
}
