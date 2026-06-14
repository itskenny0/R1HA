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
    "Lovelace cards are first-class deck cards now: they mix with favourites on any page and look native.",
    "Add cards from your HA dashboards (browse, multi-select) or import a whole dashboard as pages.",
    "New-card editor with forms: entity picker, iframe url, markdown body; raw JSON stays one tap away.",
    "Broadlink IR/RF console under Settings: guided learn, test-fire, manage; commands live in HA as tagged automations.",
    "Pin IR commands or automations to the deck and fire them with one tap.",
    "Deck layout setting (Auto, Dynamic, Fullscreen): Dynamic flows cards at their content height and snaps card-to-card.",
    "Dynamic deck: focused card centres, one tap fires any card, cards snap with a stronger magnet and lock tick.",
    "Entity cards show all their controls with a full-height value-bar slider; Lovelace and IR cards wear their titles.",
    "IR and action buttons fire with style: a radiating signal pulse, an accent flare, a SENT flash and a haptic buzz.",
    "About: install any GitHub release from a version list; thanks to Home Assistant, Nabu Casa and Open Home Foundation.",
    "Energy view: long-press a top consumer to exclude a mis-reporting sensor from every total; restore it from EXCLUDED.",
)
