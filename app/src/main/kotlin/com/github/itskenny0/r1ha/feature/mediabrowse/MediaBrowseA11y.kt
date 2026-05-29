package com.github.itskenny0.r1ha.feature.mediabrowse

import com.github.itskenny0.r1ha.core.ha.MediaBrowseEntry

/**
 * Pure, Compose-free helpers that build the spoken labels for the Media Browse
 * surface. Kept out of [MediaBrowseScreen] so they can be unit tested without a
 * Compose runtime, and so the exact wording lives in one auditable place.
 *
 * A browse row is otherwise distinguished only by a leading glyph (folder arrow
 * vs play triangle) and colour. These helpers convey, in words, whether a row is
 * a folder you can open, an item you can play, or an inert entry, plus the media
 * class, so a screen reader user gets the same information a sighted user reads
 * from the icon.
 */

/** What kind of row this is, in words, conveying the otherwise glyph-only cue. */
internal fun mediaEntryKindWord(canExpand: Boolean, canPlay: Boolean): String =
    when {
        // An expandable AND playable item (e.g. a smart playlist) is presented to
        // the user primarily as something to open; the tap navigates in.
        canExpand -> "folder"
        canPlay -> "playable item"
        else -> "item"
    }

/**
 * Spoken description for a browse row. Folds the title, kind, and media class
 * (when present) into one phrase, and states the tap action so the reader knows
 * what a tap will do (open vs play vs nothing).
 *
 * [mediaClass] is the raw HA media class (e.g. "album", "artist"); it is
 * lower-cased and appended when present. The visible row uppercases it.
 */
internal fun mediaEntryRowLabel(
    title: String,
    mediaClass: String?,
    canExpand: Boolean,
    canPlay: Boolean,
): String {
    val parts = mutableListOf<String>()
    parts += title
    parts += mediaEntryKindWord(canExpand, canPlay)
    if (!mediaClass.isNullOrBlank()) {
        parts += mediaClass.trim().lowercase()
    }
    val action = when {
        canExpand -> "tap to open"
        canPlay -> "tap to play"
        else -> "not playable or openable"
    }
    parts += action
    return parts.joinToString(separator = ", ")
}

/** Convenience overload taking a [MediaBrowseEntry] so the screen can build the
 *  label without re-listing every field at the call site. */
internal fun mediaEntryRowLabel(entry: MediaBrowseEntry): String =
    mediaEntryRowLabel(
        title = entry.title,
        mediaClass = entry.mediaClass,
        canExpand = entry.canExpand,
        canPlay = entry.canPlay,
    )

/** Spoken label for the dedicated PLAY button on a playable row. */
internal fun mediaPlayActionLabel(title: String): String = "Play $title"

/**
 * Spoken status while a play_media call is in flight, e.g. "Playing Bohemian
 * Rhapsody". Drives the polite live region so the user hears that their tap
 * registered.
 */
internal fun mediaPlayInFlightLabel(title: String): String = "Playing $title"

/**
 * Spoken description of the breadcrumb path, e.g. "Library, then Artists, then
 * Queen. Currently in Queen." The visible strip relies on position and colour to
 * mark the current folder; this spells the path out and names where you are.
 * Returns an empty string for an empty path.
 */
internal fun mediaBreadcrumbLabel(titles: List<String>): String {
    val clean = titles.filter { it.isNotBlank() }
    if (clean.isEmpty()) return ""
    if (clean.size == 1) return "In ${clean.first()}"
    val path = clean.joinToString(separator = ", then ")
    return "$path. Currently in ${clean.last()}"
}
