package com.github.itskenny0.r1ha.feature.mediabrowse

import com.github.itskenny0.r1ha.core.ha.MediaBrowseEntry
import com.github.itskenny0.r1ha.core.ha.MediaBrowseResult
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MediaBrowseNavTest {

    private fun entry(
        title: String,
        id: String = title,
        type: String = "music",
        canPlay: Boolean = false,
        canExpand: Boolean = false,
        thumbnail: String? = null,
    ) = MediaBrowseEntry(
        title = title,
        mediaClass = null,
        mediaContentId = id,
        mediaContentType = type,
        canPlay = canPlay,
        canExpand = canExpand,
        thumbnail = thumbnail,
    )

    private fun result(current: MediaBrowseEntry, children: List<MediaBrowseEntry> = emptyList()) =
        MediaBrowseResult(current = current, children = children)

    @Test fun `rootCrumbs keeps title but null ids so refresh re-browses default root`() {
        val root = result(entry("Library", id = "library", type = "app", canExpand = true))
        val crumbs = MediaBrowseNav.rootCrumbs(root)
        assertThat(crumbs).hasSize(1)
        assertThat(crumbs[0].title).isEqualTo("Library")
        assertThat(crumbs[0].mediaContentId).isNull()
        assertThat(crumbs[0].mediaContentType).isNull()
    }

    @Test fun `pushCrumb appends folder with its canonical ids`() {
        val start = MediaBrowseNav.rootCrumbs(result(entry("Library", canExpand = true)))
        val folder = result(entry("Playlists", id = "pl-1", type = "playlist", canExpand = true))
        val next = MediaBrowseNav.pushCrumb(start, folder)
        assertThat(next).hasSize(2)
        assertThat(next[1].title).isEqualTo("Playlists")
        assertThat(next[1].mediaContentId).isEqualTo("pl-1")
        assertThat(next[1].mediaContentType).isEqualTo("playlist")
    }

    @Test fun `parentCrumb is null at root`() {
        val root = MediaBrowseNav.rootCrumbs(result(entry("Library")))
        assertThat(MediaBrowseNav.parentCrumb(root)).isNull()
    }

    @Test fun `parentCrumb returns the level above the current folder`() {
        var crumbs = MediaBrowseNav.rootCrumbs(result(entry("Library")))
        crumbs = MediaBrowseNav.pushCrumb(crumbs, result(entry("Artists", id = "artists", canExpand = true)))
        crumbs = MediaBrowseNav.pushCrumb(crumbs, result(entry("Queen", id = "queen", canExpand = true)))
        val parent = MediaBrowseNav.parentCrumb(crumbs)
        assertThat(parent).isNotNull()
        assertThat(parent!!.title).isEqualTo("Artists")
        assertThat(parent.mediaContentId).isEqualTo("artists")
    }

    @Test fun `back then re-browse lands exactly one level shorter`() {
        var crumbs = MediaBrowseNav.rootCrumbs(result(entry("Library")))
        crumbs = MediaBrowseNav.pushCrumb(crumbs, result(entry("Artists", id = "artists", canExpand = true)))
        crumbs = MediaBrowseNav.pushCrumb(crumbs, result(entry("Queen", id = "queen", canExpand = true)))
        assertThat(crumbs).hasSize(3)

        val parent = MediaBrowseNav.parentCrumb(crumbs)!!
        // Truncated path held during the in-flight back navigation.
        val truncated = MediaBrowseNav.crumbsForBack(crumbs)
        assertThat(truncated).hasSize(1)
        // Re-browsing the parent re-appends one crumb.
        val landed = MediaBrowseNav.pushCrumb(
            truncated,
            result(entry(parent.title, id = parent.mediaContentId ?: "", canExpand = true)),
        )
        assertThat(landed).hasSize(2)
        assertThat(landed.last().title).isEqualTo("Artists")
    }

    @Test fun `crumbsForBack on root is a no-op`() {
        val root = MediaBrowseNav.rootCrumbs(result(entry("Library")))
        assertThat(MediaBrowseNav.crumbsForBack(root)).isEqualTo(root)
    }

    @Test fun `canPopLevel false at root, true once nested`() {
        var crumbs = MediaBrowseNav.rootCrumbs(result(entry("Library")))
        assertThat(MediaBrowseNav.canPopLevel(crumbs)).isFalse()
        crumbs = MediaBrowseNav.pushCrumb(crumbs, result(entry("Artists", canExpand = true)))
        assertThat(MediaBrowseNav.canPopLevel(crumbs)).isTrue()
    }

    @Test fun `sortChildren puts folders before tracks before inert, then alpha`() {
        val children = listOf(
            entry("zeta track", canPlay = true),
            entry("Beta folder", canExpand = true),
            entry("alpha track", canPlay = true),
            entry("inert thing"),
            entry("Alpha folder", canExpand = true),
        )
        val sorted = MediaBrowseNav.sortChildren(children).map { it.title }
        assertThat(sorted).containsExactly(
            "Alpha folder",
            "Beta folder",
            "alpha track",
            "zeta track",
            "inert thing",
        ).inOrder()
    }

    @Test fun `sortChildren treats expand-and-play item as playable, not folder`() {
        val children = listOf(
            entry("Plain folder", canExpand = true),
            entry("Smart playlist", canExpand = true, canPlay = true),
        )
        val sorted = MediaBrowseNav.sortChildren(children).map { it.title }
        // canPlay (even with canExpand) ranks after pure folders.
        assertThat(sorted).containsExactly("Plain folder", "Smart playlist").inOrder()
    }
}
