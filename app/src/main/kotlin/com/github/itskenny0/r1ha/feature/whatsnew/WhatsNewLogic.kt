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
fun whatsNewAction(
    lastSeen: Int,
    current: Int,
    configured: Boolean,
    /** The "show what's new after updates" preference. When off, upgrades
     *  stamp silently so re-enabling later doesn't dump a backlog overlay. */
    enabled: Boolean = true,
): WhatsNewAction = when {
    lastSeen == current -> WhatsNewAction.NOTHING
    !configured || !enabled -> WhatsNewAction.STAMP_SILENTLY
    lastSeen < current -> WhatsNewAction.SHOW
    // lastSeen > current: sideloaded downgrade. Stay quiet and keep the
    // high-water stamp: the user already saw those releases' notes, so
    // re-upgrading past this build shouldn't re-present them as new.
    else -> WhatsNewAction.NOTHING
}

/**
 * The bullets the overlay renders for the current release. Hand-curated per
 * release: lead with what the user feels, skip internals. Kept here (not in
 * the composable) so a unit test can keep entries terse enough for the small
 * portrait panel.
 */
val WHATS_NEW_ENTRIES: List<String> = listOf(
    "Full Lovelace parity: every dashboard card, row, badge, and feature from the HA frontend now renders natively.",
    "Auto dashboards work: strategy views (the default HA dashboard, Areas, Home) expand right on the device.",
    "Energy arrives in dashboards: usage graphs, distribution circles, gauges, and a shared period picker.",
    "Hold and double-tap fire everywhere, with HA's confirmation dialogs and full action targets.",
    "Live camera cards, picture-elements floorplans, Jinja markdown, calendars, and todo editing.",
    "More-info grows history and logbook embeds, favourites editing, and every remaining domain control.",
    "Pin any favourite to your home screen as a live widget, and pin HA panels like HACS to the sidebar.",
    "HA themes tint your dashboards; per-card and per-view theme options are honoured.",
)
