package com.github.itskenny0.r1ha.feature.broadlink

import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.BroadlinkCommand
import com.github.itskenny0.r1ha.core.prefs.BroadlinkDevice
import com.github.itskenny0.r1ha.core.prefs.BroadlinkSettings
import com.github.itskenny0.r1ha.core.prefs.applyOnto
import com.github.itskenny0.r1ha.core.prefs.decodeBackup
import com.github.itskenny0.r1ha.core.prefs.encodeBackup
import com.github.itskenny0.r1ha.core.prefs.toBackup
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class BroadlinkRegistryTest {

    private val remote = "remote.living_room_rm4"
    private val power = BroadlinkCommand(name = "power", type = "ir")

    @Test fun `upsert creates the device when absent`() {
        val out = BroadlinkRegistry.upsertCommand(BroadlinkSettings(), remote, "tv", power)
        assertThat(out.devices).hasSize(1)
        assertThat(out.devices.first().name).isEqualTo("tv")
        assertThat(out.devices.first().remoteEntityId).isEqualTo(remote)
        assertThat(out.devices.first().commands).containsExactly(power)
    }

    @Test fun `upsert appends a new command to an existing device`() {
        val s0 = BroadlinkRegistry.upsertCommand(BroadlinkSettings(), remote, "tv", power)
        val volUp = BroadlinkCommand(name = "vol_up", type = "ir")
        val out = BroadlinkRegistry.upsertCommand(s0, remote, "tv", volUp)
        assertThat(out.devices).hasSize(1)
        assertThat(out.devices.first().commands.map { it.name })
            .containsExactly("power", "vol_up").inOrder()
    }

    @Test fun `upsert replaces a same-name command but keeps fire history`() {
        val fired = power.copy(lastFiredAt = "2026-06-11T10:00:00Z")
        val s0 = BroadlinkRegistry.upsertCommand(BroadlinkSettings(), remote, "tv", fired)
        val relearned = BroadlinkCommand(name = "power", type = "rf", notes = "relearned")
        val out = BroadlinkRegistry.upsertCommand(s0, remote, "tv", relearned)
        val cmd = out.devices.first().commands.single()
        assertThat(cmd.type).isEqualTo("rf")
        assertThat(cmd.notes).isEqualTo("relearned")
        assertThat(cmd.lastFiredAt).isEqualTo("2026-06-11T10:00:00Z")
    }

    @Test fun `same device name on two remotes stays two catalogs`() {
        val other = "remote.bedroom_rm4"
        var s = BroadlinkRegistry.upsertCommand(BroadlinkSettings(), remote, "tv", power)
        s = BroadlinkRegistry.upsertCommand(s, other, "tv", BroadlinkCommand(name = "mute"))
        assertThat(s.devices).hasSize(2)
        assertThat(BroadlinkRegistry.devicesFor(s, remote).single().commands.map { it.name })
            .containsExactly("power")
        assertThat(BroadlinkRegistry.devicesFor(s, other).single().commands.map { it.name })
            .containsExactly("mute")
    }

    @Test fun `removeCommand drops the command but keeps the device entry`() {
        val s0 = BroadlinkRegistry.upsertCommand(BroadlinkSettings(), remote, "tv", power)
        val out = BroadlinkRegistry.removeCommand(s0, remote, "tv", "power")
        assertThat(out.devices).hasSize(1)
        assertThat(out.devices.first().commands).isEmpty()
    }

    @Test fun `removeDevice drops the whole catalog entry`() {
        val s0 = BroadlinkRegistry.upsertCommand(BroadlinkSettings(), remote, "tv", power)
        val out = BroadlinkRegistry.removeDevice(s0, remote, "tv")
        assertThat(out.devices).isEmpty()
    }

    @Test fun `relabel changes the display label and never the HA name`() {
        val s0 = BroadlinkRegistry.upsertCommand(BroadlinkSettings(), remote, "tv", power)
        val out = BroadlinkRegistry.relabelCommand(s0, remote, "tv", "power", "  TV POWER ")
        val cmd = out.devices.first().commands.single()
        assertThat(cmd.name).isEqualTo("power")
        assertThat(cmd.label).isEqualTo("TV POWER")
        assertThat(cmd.displayLabel).isEqualTo("TV POWER")
    }

    @Test fun `displayLabel falls back to the HA name when label is blank`() {
        assertThat(power.displayLabel).isEqualTo("power")
    }

    @Test fun `markFired stamps the command`() {
        val s0 = BroadlinkRegistry.upsertCommand(BroadlinkSettings(), remote, "tv", power)
        val out = BroadlinkRegistry.markFired(s0, remote, "tv", "power", "2026-06-11T12:34:56Z")
        assertThat(out.devices.first().commands.single().lastFiredAt)
            .isEqualTo("2026-06-11T12:34:56Z")
    }

    @Test fun `mutations on unknown keys are no-ops`() {
        val s0 = BroadlinkRegistry.upsertCommand(BroadlinkSettings(), remote, "tv", power)
        assertThat(BroadlinkRegistry.removeCommand(s0, remote, "amp", "power")).isEqualTo(s0)
        assertThat(BroadlinkRegistry.relabelCommand(s0, remote, "tv", "nope", "X")).isEqualTo(s0)
        assertThat(BroadlinkRegistry.markFired(s0, "remote.other", "tv", "power", "t")).isEqualTo(s0)
    }

    @Test fun `registry rides the backup codec and survives an empty payload`() {
        val populated = AppSettings(
            broadlink = BroadlinkSettings(
                devices = listOf(
                    BroadlinkDevice(name = "tv", remoteEntityId = remote, commands = listOf(power)),
                ),
            ),
        )
        val backup = decodeBackup(
            encodeBackup(populated.toBackup(createdAt = "2026-06-11T00:00:00Z")),
        )
        val restored = backup.applyOnto(AppSettings())
        assertThat(restored.broadlink).isEqualTo(populated.broadlink)

        // A pre-feature backup decodes with an empty registry; restoring it
        // over a populated install must not wipe learned commands.
        val preFeature = AppSettings().toBackup(createdAt = "2026-06-11T00:00:00Z")
        val applied = preFeature.applyOnto(populated)
        assertThat(applied.broadlink).isEqualTo(populated.broadlink)
    }
}
