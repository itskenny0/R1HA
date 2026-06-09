package com.github.itskenny0.r1ha.feature.whatsnew

/** What the app should do with the what's-new overlay on this launch. */
enum class WhatsNewAction {
    /** Upgrade detected: surface the overlay once. */
    SHOW,

    /**
     * First run on this device: write the current versionCode without showing
     * anything, so the overlay starts firing from the next release onward.
     */
    STAMP_SILENTLY,

    /** Already seen (or a downgrade): stay quiet. */
    NOTHING,
}

/**
 * Decide whether to show the one-shot what's-new overlay.
 *
 * [lastSeen] is the versionCode stamped on the last launch that resolved this
 * (0 = never stamped), [current] is BuildConfig.VERSION_CODE, and [configured]
 * is whether a server is set up. The configured flag is what separates a fresh
 * install (no stamp, no server: don't pile an overlay on top of onboarding)
 * from an upgrade off a build that predates the stamp field (no stamp, but a
 * configured server: exactly who the overlay is for).
 */
fun whatsNewAction(lastSeen: Int, current: Int, configured: Boolean): WhatsNewAction = when {
    lastSeen == current -> WhatsNewAction.NOTHING
    !configured -> WhatsNewAction.STAMP_SILENTLY
    lastSeen < current -> WhatsNewAction.SHOW
    // lastSeen > current: sideloaded downgrade. Stamp quietly so the next real
    // upgrade shows again, but never present old features as new.
    else -> WhatsNewAction.NOTHING
}

/**
 * The bullets the overlay renders for the current release. Hand-curated per
 * release: lead with what the user feels, skip internals. Kept here (not in
 * the composable) so a unit test can keep entries terse enough for the small
 * portrait panel.
 */
val WHATS_NEW_ENTRIES: List<String> = listOf(
    "Tablets get two-pane browsing: Devices and Areas keep the list beside the detail.",
    "Scenes and Helpers flow into multiple columns on wide screens.",
    "Charts grow with the screen, and History pairs its chart with the stats on wide panels.",
    "Empty and error screens now say what happened and offer RETRY.",
    "Calendars no longer blanks its list while refreshing.",
)
