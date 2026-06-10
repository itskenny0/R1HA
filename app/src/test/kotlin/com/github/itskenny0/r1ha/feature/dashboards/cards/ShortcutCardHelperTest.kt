package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import org.junit.Assert.assertEquals
import org.junit.Test

class ShortcutCardHelperTest {

    // ── shortcutLabelFor ─────────────────────────────────────────────────────

    @Test fun `navigate action extracts last path segment prettified`() {
        assertEquals("Lights", shortcutLabelFor(LovelaceAction.Navigate("/lovelace/lights")))
    }

    @Test fun `navigate action replaces hyphens and underscores with spaces`() {
        // Only the first character is capitalised; this matches the implementation.
        assertEquals("Living room", shortcutLabelFor(LovelaceAction.Navigate("/lovelace/living-room")))
        assertEquals("Guest bedroom", shortcutLabelFor(LovelaceAction.Navigate("/lovelace/guest_bedroom")))
    }

    @Test fun `navigate action trailing slash is stripped before segment extraction`() {
        assertEquals("Lights", shortcutLabelFor(LovelaceAction.Navigate("/lovelace/lights/")))
    }

    @Test fun `navigate action root path falls back to Shortcut`() {
        assertEquals("Shortcut", shortcutLabelFor(LovelaceAction.Navigate("/")))
    }

    @Test fun `url action returns Open link`() {
        assertEquals("Open link", shortcutLabelFor(LovelaceAction.Url("https://example.com")))
    }

    @Test fun `null action returns Shortcut`() {
        assertEquals("Shortcut", shortcutLabelFor(null))
    }

    @Test fun `builtin action returns Shortcut`() {
        assertEquals("Shortcut", shortcutLabelFor(LovelaceAction.Builtin("more-info", null)))
    }
}
