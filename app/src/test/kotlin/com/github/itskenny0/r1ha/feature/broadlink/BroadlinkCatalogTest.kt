package com.github.itskenny0.r1ha.feature.broadlink

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class BroadlinkCatalogTest {

    private val remote = "remote.living_room_rm4"

    private fun configBody(
        alias: String,
        description: String,
    ): String = """{"alias":"$alias","description":"${description.replace("\"", "\\\"")}","trigger":[],"action":[]}"""

    private fun marked(device: String, command: String, type: String = "ir"): String =
        BroadlinkMarker.encode(
            BroadlinkMarker.CommandMeta(
                remote = remote,
                device = device,
                command = command,
                type = type,
            ),
        )

    private fun entry(
        device: String,
        command: String,
        alias: String = "$device $command",
        id: String = BroadlinkMarker.automationIdFor(remote, device, command),
    ): BroadlinkCatalog.Entry = BroadlinkCatalog.parseEntry(
        automationId = id,
        entityId = "automation.${device}_$command",
        configJson = configBody(alias, marked(device, command)),
    )!!

    @Test fun `marked bodies parse into full entries`() {
        val e = BroadlinkCatalog.parseEntry(
            automationId = "abc",
            entityId = "automation.tv_power",
            configJson = configBody("TV Power", marked("tv", "power")),
            lastTriggered = "2026-06-11T10:00:00+00:00",
        )
        assertThat(e).isNotNull()
        assertThat(e!!.alias).isEqualTo("TV Power")
        assertThat(e.meta?.device).isEqualTo("tv")
        assertThat(e.meta?.command).isEqualTo("power")
        assertThat(e.readOnly).isFalse()
        assertThat(e.lastTriggered).isEqualTo("2026-06-11T10:00:00+00:00")
    }

    @Test fun `unmarked automations are not catalog entries`() {
        val e = BroadlinkCatalog.parseEntry(
            automationId = "abc",
            entityId = "automation.lights",
            configJson = """{"alias":"Lights","description":"hand made","action":[]}""",
        )
        assertThat(e).isNull()
    }

    @Test fun `missing description is not a catalog entry`() {
        assertThat(
            BroadlinkCatalog.parseEntry("abc", "automation.x", """{"alias":"X"}"""),
        ).isNull()
    }

    @Test fun `broken config bodies cannot carry a marker`() {
        assertThat(BroadlinkCatalog.parseEntry("abc", "automation.x", "{ nope")).isNull()
        assertThat(BroadlinkCatalog.parseEntry("abc", "automation.x", "[]")).isNull()
    }

    @Test fun `unknown marker versions surface as read-only entries`() {
        val e = BroadlinkCatalog.parseEntry(
            automationId = "abc",
            entityId = "automation.future",
            configJson = configBody("Future cmd", "R1HA|Broadlink|v9|{}"),
        )
        assertThat(e).isNotNull()
        assertThat(e!!.readOnly).isTrue()
        assertThat(e.markerVersion).isEqualTo("v9")
        assertThat(e.alias).isEqualTo("Future cmd")
    }

    @Test fun `blank alias falls back to the default label`() {
        val e = BroadlinkCatalog.parseEntry(
            automationId = "abc",
            entityId = "automation.tv_power",
            configJson = configBody("", marked("tv", "power")),
        )
        assertThat(e!!.alias).isEqualTo("tv · power (R1HA IR)")
    }

    @Test fun `devicesFor groups by device on one blaster only`() {
        val other = BroadlinkCatalog.parseEntry(
            automationId = "zz",
            entityId = "automation.amp_mute",
            configJson = configBody(
                "Amp mute",
                BroadlinkMarker.encode(
                    BroadlinkMarker.CommandMeta(
                        remote = "remote.bedroom_rm4",
                        device = "amp",
                        command = "mute",
                    ),
                ),
            ),
        )!!
        val entries = listOf(entry("tv", "power"), entry("tv", "vol_up"), other)
        val groups = BroadlinkCatalog.devicesFor(entries, remote)
        assertThat(groups).hasSize(1)
        assertThat(groups.single().name).isEqualTo("tv")
        assertThat(groups.single().entries).hasSize(2)
        assertThat(BroadlinkCatalog.devicesFor(entries, "remote.bedroom_rm4").single().name)
            .isEqualTo("amp")
    }

    @Test fun `allDevices spans blasters and keeps them distinct`() {
        val sameNameOtherRemote = BroadlinkCatalog.parseEntry(
            automationId = "zz",
            entityId = "automation.tv_power_2",
            configJson = configBody(
                "TV power (bedroom)",
                BroadlinkMarker.encode(
                    BroadlinkMarker.CommandMeta(
                        remote = "remote.bedroom_rm4",
                        device = "tv",
                        command = "power",
                    ),
                ),
            ),
        )!!
        val groups = BroadlinkCatalog.allDevices(listOf(entry("tv", "power"), sameNameOtherRemote))
        // Same device name on two blasters is two distinct catalogs.
        assertThat(groups).hasSize(2)
        assertThat(groups.map { it.remoteEntityId }).containsExactly(
            remote, "remote.bedroom_rm4",
        )
    }

    @Test fun `read-only entries are excluded from device groups but listed`() {
        val ro = BroadlinkCatalog.parseEntry(
            automationId = "ro",
            entityId = "automation.future",
            configJson = configBody("Future", "R1HA|Broadlink|v9|{}"),
        )!!
        val entries = listOf(entry("tv", "power"), ro)
        assertThat(BroadlinkCatalog.devicesFor(entries, remote).single().entries).hasSize(1)
        assertThat(BroadlinkCatalog.readOnlyEntries(entries)).containsExactly(ro)
    }

    @Test fun `deviceNamesFor backs the existing-device chips`() {
        val entries = listOf(entry("tv", "power"), entry("amp", "mute"))
        assertThat(BroadlinkCatalog.deviceNamesFor(entries, remote))
            .containsExactly("amp", "tv").inOrder()
    }
}
