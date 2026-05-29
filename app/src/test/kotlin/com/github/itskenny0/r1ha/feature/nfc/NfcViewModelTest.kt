package com.github.itskenny0.r1ha.feature.nfc

import com.github.itskenny0.r1ha.core.ha.HaTag
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Pure-helper coverage for the NFC surface: display-name fallback, tag-id
 * normalisation (what we feed `tag_scanned`), and the newest-scan-first sort.
 * No repository / Android dependencies.
 */
class NfcViewModelTest {

    private fun tag(
        id: String,
        name: String? = null,
        description: String? = null,
        lastScanned: Instant? = null,
    ) = HaTag(id = id, name = name, description = description, lastScanned = lastScanned)

    // --- displayName -----------------------------------------------------

    @Test fun `displayName prefers a non-blank name`() {
        assertThat(NfcViewModel.displayName(tag("04a1", name = "Front door")))
            .isEqualTo("Front door")
    }

    @Test fun `displayName falls back to id for null or blank name`() {
        assertThat(NfcViewModel.displayName(tag("04a1", name = null))).isEqualTo("04a1")
        assertThat(NfcViewModel.displayName(tag("04a1", name = "   "))).isEqualTo("04a1")
    }

    // --- normalizeTagId --------------------------------------------------

    @Test fun `normalizeTagId strips separators and lowercases`() {
        assertThat(NfcViewModel.normalizeTagId("AA:BB:CC")).isEqualTo("aabbcc")
        assertThat(NfcViewModel.normalizeTagId("AA-BB-CC")).isEqualTo("aabbcc")
        assertThat(NfcViewModel.normalizeTagId("  Ab 12  ")).isEqualTo("ab12")
    }

    @Test fun `normalizeTagId yields empty for blank input`() {
        for (s in listOf("", "   ", "\t")) {
            assertThat(NfcViewModel.normalizeTagId(s)).isEmpty()
        }
    }

    @Test fun `normalizeTagId leaves non-hex registry ids intact apart from casing`() {
        // Some QR-derived ids carry words; we only drop separators, not content.
        assertThat(NfcViewModel.normalizeTagId("Living_Room")).isEqualTo("living_room")
    }

    // --- sortTags --------------------------------------------------------

    private val ts1 = Instant.parse("2026-05-01T10:00:00Z")
    private val ts2 = Instant.parse("2026-05-29T10:00:00Z")

    @Test fun `sortTags orders newest scan first`() {
        val older = tag("a", name = "Older", lastScanned = ts1)
        val newer = tag("b", name = "Newer", lastScanned = ts2)
        assertThat(NfcViewModel.sortTags(listOf(older, newer)).map { it.id })
            .containsExactly("b", "a").inOrder()
    }

    @Test fun `sortTags sinks never-scanned tags below scanned ones`() {
        val scanned = tag("a", name = "Scanned", lastScanned = ts1)
        val never = tag("b", name = "Never", lastScanned = null)
        assertThat(NfcViewModel.sortTags(listOf(never, scanned)).map { it.id })
            .containsExactly("a", "b").inOrder()
    }

    @Test fun `sortTags tie-breaks equal timestamps by display name`() {
        val zed = tag("z", name = "Zed", lastScanned = ts1)
        val abe = tag("a", name = "Abe", lastScanned = ts1)
        assertThat(NfcViewModel.sortTags(listOf(zed, abe)).map { it.id })
            .containsExactly("a", "z").inOrder()
    }

    @Test fun `sortTags tie-breaks two never-scanned tags by display name`() {
        val zed = tag("z", name = "Zed", lastScanned = null)
        val abe = tag("a", name = "Abe", lastScanned = null)
        assertThat(NfcViewModel.sortTags(listOf(zed, abe)).map { it.id })
            .containsExactly("a", "z").inOrder()
    }

    @Test fun `sortTags is stable on an empty list`() {
        assertThat(NfcViewModel.sortTags(emptyList())).isEmpty()
    }
}
