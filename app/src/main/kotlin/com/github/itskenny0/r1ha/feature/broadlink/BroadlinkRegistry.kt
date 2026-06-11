package com.github.itskenny0.r1ha.feature.broadlink

import com.github.itskenny0.r1ha.core.prefs.BroadlinkCommand
import com.github.itskenny0.r1ha.core.prefs.BroadlinkDevice
import com.github.itskenny0.r1ha.core.prefs.BroadlinkSettings

/**
 * Pure mutations over the app-side Broadlink registry. Every function is
 * a value transform on [BroadlinkSettings]; callers persist the result via
 * SettingsRepository.update. Device identity is the (remoteEntityId, name)
 * pair and command identity is the HA-side name, both case-sensitive
 * because HA's stored-code keys are.
 */
object BroadlinkRegistry {

    fun devicesFor(s: BroadlinkSettings, remoteEntityId: String): List<BroadlinkDevice> =
        s.devices.filter { it.remoteEntityId == remoteEntityId }

    fun device(s: BroadlinkSettings, remoteEntityId: String, deviceName: String): BroadlinkDevice? =
        s.devices.firstOrNull { it.remoteEntityId == remoteEntityId && it.name == deviceName }

    /**
     * Add or replace [command] under (remoteEntityId, deviceName), creating
     * the device entry when absent. Replacing an existing command keeps its
     * lastFiredAt when the incoming one is null, so re-learning a code
     * doesn't erase the fire history.
     */
    fun upsertCommand(
        s: BroadlinkSettings,
        remoteEntityId: String,
        deviceName: String,
        command: BroadlinkCommand,
    ): BroadlinkSettings {
        val existingDevice = device(s, remoteEntityId, deviceName)
            ?: return s.copy(
                devices = s.devices + BroadlinkDevice(
                    name = deviceName,
                    remoteEntityId = remoteEntityId,
                    commands = listOf(command),
                ),
            )
        val prior = existingDevice.commands.firstOrNull { it.name == command.name }
        val merged = if (prior != null && command.lastFiredAt == null) {
            command.copy(lastFiredAt = prior.lastFiredAt)
        } else command
        val newCommands = if (prior == null) {
            existingDevice.commands + merged
        } else {
            existingDevice.commands.map { if (it.name == command.name) merged else it }
        }
        return replaceDevice(s, existingDevice.copy(commands = newCommands))
    }

    /** Drop one command from the registry. The device entry survives even
     *  when emptied so the user's catalog structure persists; removing the
     *  device itself is an explicit [removeDevice]. */
    fun removeCommand(
        s: BroadlinkSettings,
        remoteEntityId: String,
        deviceName: String,
        commandName: String,
    ): BroadlinkSettings {
        val dev = device(s, remoteEntityId, deviceName) ?: return s
        return replaceDevice(s, dev.copy(commands = dev.commands.filterNot { it.name == commandName }))
    }

    fun removeDevice(
        s: BroadlinkSettings,
        remoteEntityId: String,
        deviceName: String,
    ): BroadlinkSettings = s.copy(
        devices = s.devices.filterNot { it.remoteEntityId == remoteEntityId && it.name == deviceName },
    )

    /**
     * Set the display label only. The HA-side [BroadlinkCommand.name] stays
     * untouched: HA keys the stored code by device + command name and the
     * API offers no rename, so a registry-side rename must never drift the
     * key the fire path sends.
     */
    fun relabelCommand(
        s: BroadlinkSettings,
        remoteEntityId: String,
        deviceName: String,
        commandName: String,
        label: String,
    ): BroadlinkSettings = mapCommand(s, remoteEntityId, deviceName, commandName) {
        it.copy(label = label.trim())
    }

    /** Stamp the last in-app fire time (ISO-8601 UTC string). */
    fun markFired(
        s: BroadlinkSettings,
        remoteEntityId: String,
        deviceName: String,
        commandName: String,
        firedAt: String,
    ): BroadlinkSettings = mapCommand(s, remoteEntityId, deviceName, commandName) {
        it.copy(lastFiredAt = firedAt)
    }

    private fun mapCommand(
        s: BroadlinkSettings,
        remoteEntityId: String,
        deviceName: String,
        commandName: String,
        transform: (BroadlinkCommand) -> BroadlinkCommand,
    ): BroadlinkSettings {
        val dev = device(s, remoteEntityId, deviceName) ?: return s
        if (dev.commands.none { it.name == commandName }) return s
        return replaceDevice(
            s,
            dev.copy(
                commands = dev.commands.map {
                    if (it.name == commandName) transform(it) else it
                },
            ),
        )
    }

    private fun replaceDevice(s: BroadlinkSettings, updated: BroadlinkDevice): BroadlinkSettings =
        s.copy(
            devices = s.devices.map {
                if (it.remoteEntityId == updated.remoteEntityId && it.name == updated.name) updated else it
            },
        )
}
