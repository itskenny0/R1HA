package com.github.itskenny0.r1ha.feature.mediabrowse

import com.github.itskenny0.r1ha.core.ha.MediaBrowseEntry
import com.github.itskenny0.r1ha.core.ha.MediaBrowseResult

/**
 * Pure (testable) helpers for the Media Browse back-stack and child handling.
 *
 * The screen's viewmodel keeps a list of [Crumb]s describing the path from the
 * player's root down to the folder currently on screen. These functions own the
 * arithmetic of growing and shrinking that path so the navigation rules stay in
 * one place (and stay unit-testable without a running viewmodel or HA backend).
 */
object MediaBrowseNav {

    /** One step in the browse path. A null [mediaContentId] means "the player's
     *  default root", which is what HA returns when browse_media is called with
     *  no content id/type. */
    data class Crumb(
        val title: String,
        val mediaContentId: String?,
        val mediaContentType: String?,
    )

    /**
     * Crumbs for the freshly-opened root of a player. We use the title HA
     * reports for the root folder but keep the ids null so a refresh re-browses
     * the default root rather than a stale content id.
     */
    fun rootCrumbs(result: MediaBrowseResult): List<Crumb> =
        listOf(Crumb(title = result.current.title, mediaContentId = null, mediaContentType = null))

    /**
     * Append the folder we just drilled into. Called after a successful browse
     * triggered by tapping an expandable child; [result] carries the canonical
     * title + ids for that folder.
     */
    fun pushCrumb(current: List<Crumb>, result: MediaBrowseResult): List<Crumb> =
        current + Crumb(
            title = result.current.title,
            mediaContentId = result.current.mediaContentId,
            mediaContentType = result.current.mediaContentType,
        )

    /**
     * The crumb one level up from the current folder, or null when we're
     * already at the root (nothing to pop to). The viewmodel re-browses this
     * crumb to render the parent folder.
     */
    fun parentCrumb(current: List<Crumb>): Crumb? =
        if (current.size <= 1) null else current[current.size - 2]

    /**
     * The crumb list to hold *while* a back navigation is in flight. We drop
     * the last two entries so that re-browsing the parent (which re-appends one
     * via [pushCrumb]) lands the path back at exactly one shorter than before.
     * Returns an empty list when there is nothing above the current folder.
     */
    fun crumbsForBack(current: List<Crumb>): List<Crumb> =
        if (current.size <= 1) current else current.dropLast(2)

    /** True when a hardware-back press should pop a browse level rather than
     *  leave the screen. */
    fun canPopLevel(current: List<Crumb>): Boolean = current.size > 1

    /**
     * Order children for display: folders (expand-only) first, then playable
     * items, with everything else last; ties broken by case-insensitive title.
     * HA returns children in integration-defined order which is often unsorted;
     * a stable folder-then-track grouping reads better on the R1's small list.
     */
    fun sortChildren(children: List<MediaBrowseEntry>): List<MediaBrowseEntry> =
        children.sortedWith(
            compareBy<MediaBrowseEntry> { rank(it) }
                .thenBy { it.title.lowercase(java.util.Locale.US) },
        )

    private fun rank(entry: MediaBrowseEntry): Int = when {
        entry.canExpand && !entry.canPlay -> 0
        entry.canPlay -> 1
        else -> 2
    }
}
