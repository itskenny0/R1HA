package com.github.itskenny0.r1ha.feature.mediabrowse

import com.github.itskenny0.r1ha.core.ha.MediaBrowseEntry
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

class MediaBrowseA11yTest {

    private fun entry(
        title: String,
        mediaClass: String? = null,
        canPlay: Boolean = false,
        canExpand: Boolean = false,
    ) = MediaBrowseEntry(
        title = title,
        mediaClass = mediaClass,
        mediaContentId = title,
        mediaContentType = "music",
        canPlay = canPlay,
        canExpand = canExpand,
        thumbnail = null,
    )

    @Test fun `kind word marks an expandable row as a folder`() {
        assertThat(mediaEntryKindWord(canExpand = true, canPlay = false)).isEqualTo("folder")
    }

    @Test fun `kind word marks an expandable-and-playable row as a folder`() {
        // The tap opens it, so it reads as a folder to the user.
        assertThat(mediaEntryKindWord(canExpand = true, canPlay = true)).isEqualTo("folder")
    }

    @Test fun `kind word marks a play-only row as a playable item`() {
        assertThat(mediaEntryKindWord(canExpand = false, canPlay = true)).isEqualTo("playable item")
    }

    @Test fun `kind word marks an inert row as item`() {
        assertThat(mediaEntryKindWord(canExpand = false, canPlay = false)).isEqualTo("item")
    }

    @Test fun `row label for a folder states tap to open`() {
        assertThat(mediaEntryRowLabel(entry("Artists", canExpand = true)))
            .isEqualTo("Artists, folder, tap to open")
    }

    @Test fun `row label for a track states tap to play and includes media class`() {
        val prev = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertThat(
                mediaEntryRowLabel(entry("Bohemian Rhapsody", mediaClass = "Track", canPlay = true)),
            ).isEqualTo("Bohemian Rhapsody, playable item, track, tap to play")
        } finally {
            Locale.setDefault(prev)
        }
    }

    @Test fun `row label for an inert row states it cannot be opened or played`() {
        assertThat(mediaEntryRowLabel(entry("Separator")))
            .isEqualTo("Separator, item, not playable or openable")
    }

    @Test fun `row label omits a blank media class`() {
        assertThat(mediaEntryRowLabel(entry("Albums", mediaClass = "  ", canExpand = true)))
            .isEqualTo("Albums, folder, tap to open")
    }

    @Test fun `play action label names the title`() {
        assertThat(mediaPlayActionLabel("Bohemian Rhapsody")).isEqualTo("Play Bohemian Rhapsody")
    }

    @Test fun `play in-flight label uses Playing verb`() {
        assertThat(mediaPlayInFlightLabel("Bohemian Rhapsody")).isEqualTo("Playing Bohemian Rhapsody")
    }

    @Test fun `breadcrumb label is empty for an empty path`() {
        assertThat(mediaBreadcrumbLabel(emptyList())).isEmpty()
    }

    @Test fun `breadcrumb label for a single level just names the folder`() {
        assertThat(mediaBreadcrumbLabel(listOf("Library"))).isEqualTo("In Library")
    }

    @Test fun `breadcrumb label spells out the path and names current folder`() {
        assertThat(mediaBreadcrumbLabel(listOf("Library", "Artists", "Queen")))
            .isEqualTo("Library, then Artists, then Queen. Currently in Queen")
    }

    @Test fun `breadcrumb label skips blank crumbs`() {
        assertThat(mediaBreadcrumbLabel(listOf("Library", "  ", "Queen")))
            .isEqualTo("Library, then Queen. Currently in Queen")
    }
}
