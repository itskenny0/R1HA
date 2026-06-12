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
    "Lovelace cards are now real deck cards: they mix with favourites on any page and look native.",
    "Add cards straight from your HA dashboards: browse, multi-select, done.",
    "Import a whole dashboard as pages, one tab per view, cards in order.",
    "New-card editor speaks forms now: entity picker, iframe url + aspect, markdown body. JSON stays one tap away.",
    "Reorder the mixed deck from the jump list; long-press a Lovelace card to edit or remove it.",
    "Broadlink IR/RF console under Settings: guided learn flow with live capture, test-fire, then save.",
    "Your IR catalog lives in HA as R1HA-tagged automations: it survives reinstalls and follows you between devices.",
    "Fire, rename, delete or pin commands to the deck; register existing codes and build simple IR automations.",
    "Deck card fixes: iframes load reliably, hidden conditional cards skip their slot, short cards hug their content.",
    "Pinned cards drop the bulky frame, and pinning a Broadlink button opens the full editor: name, icon, show toggles.",
    "Deck layout setting: Dynamic sizes Lovelace cards to their content and snaps card-to-card; Auto picks by screen.",
    "Dynamic deck polish: tab swipes work everywhere, entity cards flow compact, no blank tail, untitled cards get names.",
)
