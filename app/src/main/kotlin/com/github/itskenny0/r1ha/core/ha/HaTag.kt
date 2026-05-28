package com.github.itskenny0.r1ha.core.ha

import java.time.Instant

/**
 * One entry from HA's `tag/list` reply. HA's tag registry is the source-of-
 * truth for NFC tags + QR codes that fire `tag_scanned` events; the tag
 * registry editor on the HA frontend creates / renames / deletes entries
 * and surfaces the last-scanned timestamp so users can audit usage.
 *
 * The Tags screen mirrors that view read-mostly: tap a row to rename,
 * long-press to delete. Creation isn't included because tags are usually
 * registered by the integration on first scan, so a "make a new tag"
 * affordance from the device would invite the user to type a tag_id that
 * doesn't correspond to a real piece of hardware.
 */
data class HaTag(
    /** Stable server-assigned id, also the value the NFC tag broadcasts.
     *  Used as both the route key and the API call target. */
    val id: String,
    /** Friendly label the user assigned, e.g. "Living-room remote". May
     *  be blank — the row falls back to the [id] in that case so every
     *  row has something readable. */
    val name: String?,
    /** Free-form description the user typed. Optional, often blank. */
    val description: String?,
    /** Last time HA recorded a tag_scanned event for this tag. Null
     *  means the tag has been registered but never scanned. */
    val lastScanned: Instant?,
)
