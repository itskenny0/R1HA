package com.github.itskenny0.r1ha.feature.broadlink

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class BroadlinkMarkerTest {

    private val meta = BroadlinkMarker.CommandMeta(
        remote = "remote.living_room_rm4",
        device = "tv",
        command = "power",
        type = "ir",
    )

    // ── Marker round-trip ───────────────────────────────────────────────

    @Test fun `encode-parse round-trips the full metadata`() {
        val parsed = BroadlinkMarker.parse(BroadlinkMarker.encode(meta))
        assertThat(parsed).isEqualTo(BroadlinkMarker.Parsed.Marked(meta))
    }

    @Test fun `notes survive the round trip, including pipes and quotes`() {
        val noted = meta.copy(notes = "long-press | hold 2s \"exactly\"")
        val parsed = BroadlinkMarker.parse(BroadlinkMarker.encode(noted))
        assertThat(parsed).isEqualTo(BroadlinkMarker.Parsed.Marked(noted))
    }

    @Test fun `blank notes are omitted from the encoding`() {
        assertThat(BroadlinkMarker.encode(meta)).doesNotContain("notes")
    }

    @Test fun `encoded marker has the documented shape`() {
        val text = BroadlinkMarker.encode(meta)
        assertThat(text).startsWith("R1HA|Broadlink|v1|{")
        assertThat(text).contains("\"remote\":\"remote.living_room_rm4\"")
        assertThat(text).contains("\"device\":\"tv\"")
        assertThat(text).contains("\"command\":\"power\"")
        assertThat(text).contains("\"type\":\"ir\"")
    }

    // ── Defensive parsing ───────────────────────────────────────────────

    @Test fun `unknown versions parse as read-only, not as garbage`() {
        val parsed = BroadlinkMarker.parse("""R1HA|Broadlink|v9|{"future":"shape"}""")
        assertThat(parsed).isEqualTo(BroadlinkMarker.Parsed.ReadOnly("v9"))
    }

    @Test fun `v1 with an unreadable payload is read-only`() {
        assertThat(BroadlinkMarker.parse("R1HA|Broadlink|v1|not json"))
            .isEqualTo(BroadlinkMarker.Parsed.ReadOnly("v1"))
        assertThat(BroadlinkMarker.parse("""R1HA|Broadlink|v1|{"device":"tv"}"""))
            .isEqualTo(BroadlinkMarker.Parsed.ReadOnly("v1"))
        assertThat(BroadlinkMarker.parse("R1HA|Broadlink|v1|"))
            .isEqualTo(BroadlinkMarker.Parsed.ReadOnly("v1"))
    }

    @Test fun `truncated markers stay in the family as read-only`() {
        assertThat(BroadlinkMarker.parse("R1HA|Broadlink|"))
            .isEqualTo(BroadlinkMarker.Parsed.ReadOnly("?"))
    }

    @Test fun `ordinary descriptions are not markers`() {
        assertThat(BroadlinkMarker.parse(null)).isEqualTo(BroadlinkMarker.Parsed.NotMarker)
        assertThat(BroadlinkMarker.parse("")).isEqualTo(BroadlinkMarker.Parsed.NotMarker)
        assertThat(BroadlinkMarker.parse("Turns the TV on at 7"))
            .isEqualTo(BroadlinkMarker.Parsed.NotMarker)
        // Mentioning the words is not carrying the marker.
        assertThat(BroadlinkMarker.parse("R1HA Broadlink helper"))
            .isEqualTo(BroadlinkMarker.Parsed.NotMarker)
    }

    @Test fun `unknown type values fall back to ir`() {
        val parsed = BroadlinkMarker.parse(
            """R1HA|Broadlink|v1|{"remote":"remote.x","device":"tv","command":"power","type":"laser"}""",
        )
        assertThat((parsed as BroadlinkMarker.Parsed.Marked).meta.type).isEqualTo("ir")
    }

    // ── Id scheme ───────────────────────────────────────────────────────

    @Test fun `ids are deterministic`() {
        val a = BroadlinkMarker.automationIdFor("remote.rm4", "tv", "power")
        val b = BroadlinkMarker.automationIdFor("remote.rm4", "tv", "power")
        assertThat(a).isEqualTo(b)
    }

    @Test fun `ids carry the documented prefix and a readable slug`() {
        val id = BroadlinkMarker.automationIdFor("remote.living_room_rm4", "TV", "Vol +")
        assertThat(id).startsWith("r1ha_broadlink_living_room_rm4_tv_vol_")
        assertThat(id).matches("[a-z0-9_]+")
    }

    @Test fun `slug collisions still produce distinct ids`() {
        // Both devices slug to "tv"; the hash of the raw triple differs.
        val a = BroadlinkMarker.automationIdFor("remote.rm4", "TV!", "power")
        val b = BroadlinkMarker.automationIdFor("remote.rm4", "TV?", "power")
        assertThat(a).isNotEqualTo(b)
    }

    @Test fun `field boundary shifts cannot alias`() {
        val a = BroadlinkMarker.automationIdFor("remote.rm4", "ab", "c")
        val b = BroadlinkMarker.automationIdFor("remote.rm4", "a", "bc")
        assertThat(a).isNotEqualTo(b)
    }

    @Test fun `hostile names still yield a valid id`() {
        val id = BroadlinkMarker.automationIdFor("remote.rm4", "客厅电视", "←!")
        assertThat(id).matches("[a-z0-9_]+")
    }

    @Test fun `default alias matches the documented label shape`() {
        assertThat(BroadlinkMarker.defaultAlias(meta.copy(device = "TV", command = "Power")))
            .isEqualTo("TV · Power (R1HA IR)")
        assertThat(BroadlinkMarker.defaultAlias(meta.copy(type = "rf")))
            .isEqualTo("tv · power (R1HA RF)")
    }
}
