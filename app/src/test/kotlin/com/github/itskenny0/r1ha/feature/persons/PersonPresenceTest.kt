package com.github.itskenny0.r1ha.feature.persons

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PersonPresenceTest {

    @Test fun `home state maps to HOME`() {
        val p = presenceLabel("home")
        assertThat(p.label).isEqualTo("HOME")
        assertThat(p.kind).isEqualTo(PresenceKind.HOME)
    }

    @Test fun `home is case insensitive`() {
        assertThat(presenceLabel("Home").kind).isEqualTo(PresenceKind.HOME)
        assertThat(presenceLabel("HOME").kind).isEqualTo(PresenceKind.HOME)
    }

    @Test fun `not_home and away both map to AWAY`() {
        assertThat(presenceLabel("not_home").label).isEqualTo("AWAY")
        assertThat(presenceLabel("not_home").kind).isEqualTo(PresenceKind.AWAY)
        assertThat(presenceLabel("away").kind).isEqualTo(PresenceKind.AWAY)
    }

    @Test fun `unknown unavailable and blank map to question mark`() {
        assertThat(presenceLabel("unknown").label).isEqualTo("?")
        assertThat(presenceLabel("unknown").kind).isEqualTo(PresenceKind.UNKNOWN)
        assertThat(presenceLabel("unavailable").kind).isEqualTo(PresenceKind.UNKNOWN)
        assertThat(presenceLabel("").kind).isEqualTo(PresenceKind.UNKNOWN)
        assertThat(presenceLabel("   ").kind).isEqualTo(PresenceKind.UNKNOWN)
    }

    @Test fun `named zone is upper-cased and bucketed as ZONE`() {
        val p = presenceLabel("Work")
        assertThat(p.label).isEqualTo("WORK")
        assertThat(p.kind).isEqualTo(PresenceKind.ZONE)
    }

    @Test fun `zone label preserves multi-word name`() {
        assertThat(presenceLabel("Grandma's House").label).isEqualTo("GRANDMA'S HOUSE")
    }

    @Test fun `surrounding whitespace is trimmed before matching`() {
        assertThat(presenceLabel("  home  ").kind).isEqualTo(PresenceKind.HOME)
        assertThat(presenceLabel("  Work  ").label).isEqualTo("WORK")
    }

    @Test fun `initials uses first letters of first two words`() {
        assertThat(initialsFor("Jane Doe")).isEqualTo("JD")
        assertThat(initialsFor("Mary Jane Watson")).isEqualTo("MJ")
    }

    @Test fun `initials uses first two letters of a single word`() {
        assertThat(initialsFor("Kenny")).isEqualTo("KE")
        assertThat(initialsFor("A")).isEqualTo("A")
    }

    @Test fun `initials collapses extra whitespace`() {
        assertThat(initialsFor("  Jane   Doe  ")).isEqualTo("JD")
    }

    @Test fun `initials falls back to question mark for empty name`() {
        assertThat(initialsFor("")).isEqualTo("?")
        assertThat(initialsFor("   ")).isEqualTo("?")
    }
}
