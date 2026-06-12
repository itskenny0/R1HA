package com.github.itskenny0.r1ha.feature.broadlink

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure read-side of the HA-resident command catalog: turn fetched
 * automation config bodies into catalog entries and group them for the
 * console's device list. No mutation lives here; writes go through the
 * repository (saveAutomationConfig / deleteAutomationConfig) from the
 * ViewModel.
 */
object BroadlinkCatalog {

    /**
     * One catalog row = one R1HA-tagged automation. [alias] is the
     * automation's alias and IS the user-facing (renameable) label.
     * [meta] is null for read-only rows (marker versions this app can't
     * rewrite); those still fire via automation.trigger but offer no
     * rename / delete / pin-with-repeats.
     */
    data class Entry(
        /** Config-store id (the REST path segment), not the entity_id. */
        val automationId: String,
        /** automation.<object_id>; the trigger / toggle target. */
        val entityId: String,
        val alias: String,
        val meta: BroadlinkMarker.CommandMeta?,
        /** Marker version verbatim; "v1" for fully-understood rows. */
        val markerVersion: String,
        /** ISO-8601 instant of the automation's last run (HA's
         *  last_triggered attribute), overlaid in-memory after an in-app
         *  fire. Null = never. */
        val lastTriggered: String? = null,
    ) {
        val readOnly: Boolean get() = meta == null
    }

    /** A device group in the catalog list: every v1 entry sharing
     *  (remote entity, device name). Groups are derived, never stored, so
     *  a device exists exactly as long as it has commands. */
    data class DeviceGroup(
        val remoteEntityId: String,
        val name: String,
        val entries: List<Entry>,
    )

    /**
     * Parse one automation into a catalog entry, or null when it isn't
     * R1HA-tagged. [configJson] is the raw body from
     * fetchAutomationConfig; an unparseable body can't carry our marker
     * and yields null rather than a read-only ghost.
     */
    fun parseEntry(
        automationId: String,
        entityId: String,
        configJson: String,
        lastTriggered: String? = null,
    ): Entry? {
        val obj = runCatching { Json.parseToJsonElement(configJson) as? JsonObject }.getOrNull()
            ?: return null
        val description = (obj["description"] as? JsonPrimitive)?.content
        val alias = (obj["alias"] as? JsonPrimitive)?.content
        return when (val parsed = BroadlinkMarker.parse(description)) {
            is BroadlinkMarker.Parsed.Marked -> Entry(
                automationId = automationId,
                entityId = entityId,
                alias = alias?.takeIf { it.isNotBlank() }
                    ?: BroadlinkMarker.defaultAlias(parsed.meta),
                meta = parsed.meta,
                markerVersion = "v1",
                lastTriggered = lastTriggered,
            )
            is BroadlinkMarker.Parsed.ReadOnly -> Entry(
                automationId = automationId,
                entityId = entityId,
                alias = alias?.takeIf { it.isNotBlank() } ?: entityId,
                meta = null,
                markerVersion = parsed.version,
                lastTriggered = lastTriggered,
            )
            BroadlinkMarker.Parsed.NotMarker -> null
        }
    }

    /** Device groups for one blaster, name-sorted, commands in alias
     *  order. Read-only rows are excluded (no device to group under). */
    fun devicesFor(entries: List<Entry>, remoteEntityId: String): List<DeviceGroup> =
        groupAll(entries.filter { it.meta?.remote == remoteEntityId })

    /** Device groups across every blaster (the automation builder's
     *  source list). */
    fun allDevices(entries: List<Entry>): List<DeviceGroup> = groupAll(entries)

    fun deviceNamesFor(entries: List<Entry>, remoteEntityId: String): List<String> =
        devicesFor(entries, remoteEntityId).map { it.name }

    /** Rows this app can list and fire but not rewrite. */
    fun readOnlyEntries(entries: List<Entry>): List<Entry> =
        entries.filter { it.readOnly }.sortedBy { it.alias.lowercase() }

    private fun groupAll(entries: List<Entry>): List<DeviceGroup> =
        entries
            .mapNotNull { e -> e.meta?.let { Triple(it.remote, it.device, e) } }
            .groupBy({ it.first to it.second }, { it.third })
            .map { (key, list) ->
                DeviceGroup(
                    remoteEntityId = key.first,
                    name = key.second,
                    entries = list.sortedBy { it.alias.lowercase() },
                )
            }
            .sortedBy { it.name.lowercase() }
}
