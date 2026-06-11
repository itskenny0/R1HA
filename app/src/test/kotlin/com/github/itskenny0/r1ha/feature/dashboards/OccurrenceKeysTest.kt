package com.github.itskenny0.r1ha.feature.dashboards

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression for the edit-mode reorder crash: a Lovelace view holding the same
 * card twice produced colliding LazyColumn keys (content hashes alone) and
 * threw 'Key "(h1, h2)" was already used'. Keys must be unique for ANY input.
 */
class OccurrenceKeysTest {

    @Test fun `unique hashes pass through with a zero suffix`() {
        assertThat(occurrenceKeys(listOf(1, 2, 3)))
            .containsExactly("1#0", "2#0", "3#0")
            .inOrder()
    }

    @Test fun `duplicate hashes get distinct occurrence suffixes`() {
        assertThat(occurrenceKeys(listOf(7, 9, 7, 7)))
            .containsExactly("7#0", "9#0", "7#1", "7#2")
            .inOrder()
    }

    @Test fun `keys are unique even when every hash collides`() {
        val keys = occurrenceKeys(List(50) { 42 })
        assertThat(keys.toSet()).hasSize(50)
    }

    @Test fun `empty input yields empty output`() {
        assertThat(occurrenceKeys(emptyList())).isEmpty()
    }
}
