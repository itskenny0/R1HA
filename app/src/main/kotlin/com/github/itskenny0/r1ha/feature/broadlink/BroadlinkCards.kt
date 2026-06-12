package com.github.itskenny0.r1ha.feature.broadlink

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Pure JSON builders for the Broadlink console's two export shapes:
 *  - Lovelace button-card configs appended to FavoritePage.pinnedCards
 *    (rendered by the existing pinned-cards deck, untouched here), and
 *  - automation config bodies POSTed to /api/config/automation/config/<id>.
 * Built with the kotlinx DSL rather than string templates so names with
 * quotes / unicode round-trip safely.
 */
object BroadlinkCards {

    /** Button card whose tap fires `remote.send_command` for one learned
     *  command. [repeats] below 2 is omitted (HA's default is one send). */
    fun commandButtonCard(
        remoteEntityId: String,
        deviceName: String,
        commandName: String,
        label: String,
        repeats: Int = 1,
    ): JsonObject = buildJsonObject {
        put("type", "button")
        put("name", label)
        put("icon", "mdi:remote")
        put("show_state", false)
        putJsonObject("tap_action") {
            put("action", "call-service")
            put("service", "remote.send_command")
            putJsonObject("target") { put("entity_id", remoteEntityId) }
            putJsonObject("data") {
                put("device", deviceName)
                put("command", commandName)
                if (repeats > 1) put("num_repeats", repeats)
            }
        }
    }

    /** Button card whose tap manually triggers an automation. */
    fun automationButtonCard(
        automationEntityId: String,
        label: String,
    ): JsonObject = buildJsonObject {
        put("type", "button")
        put("name", label)
        put("icon", "mdi:robot")
        put("show_state", false)
        putJsonObject("tap_action") {
            put("action", "call-service")
            put("service", "automation.trigger")
            putJsonObject("target") { put("entity_id", automationEntityId) }
            putJsonObject("data") { put("skip_condition", true) }
        }
    }

    /** Trigger shapes the focused builder offers. The classic `platform:`
     *  key is emitted (HA 2024.10 renamed it to `trigger:` but keeps the
     *  old key working) so older servers accept the config too. */
    sealed interface Trigger {
        /** Daily wall-clock trigger; [time] is "HH:MM:SS" local. */
        data class AtTime(val time: String) : Trigger

        /** Entity state-change trigger; [to] empty means "any change". */
        data class EntityState(val entityId: String, val to: String) : Trigger
    }

    /** Full automation config body for the common Broadlink case: one
     *  trigger, no conditions, one `remote.send_command` action. */
    fun automationConfig(
        alias: String,
        trigger: Trigger,
        remoteEntityId: String,
        deviceName: String,
        commandName: String,
        repeats: Int = 1,
    ): JsonObject = buildJsonObject {
        put("alias", alias)
        put("description", "IR/RF command automation created on this device")
        putJsonArray("trigger") { add(triggerJson(trigger)) }
        put("condition", JsonArray(emptyList()))
        putJsonArray("action") {
            add(
                buildJsonObject {
                    put("service", "remote.send_command")
                    putJsonObject("target") { put("entity_id", remoteEntityId) }
                    putJsonObject("data") {
                        put("device", deviceName)
                        put("command", commandName)
                        if (repeats > 1) put("num_repeats", repeats)
                    }
                },
            )
        }
        put("mode", "single")
    }

    private fun triggerJson(trigger: Trigger): JsonObject = when (trigger) {
        is Trigger.AtTime -> buildJsonObject {
            put("platform", "time")
            put("at", trigger.time)
        }
        is Trigger.EntityState -> buildJsonObject {
            put("platform", "state")
            put("entity_id", trigger.entityId)
            if (trigger.to.isNotBlank()) put("to", trigger.to)
        }
    }

    /**
     * Decide whether an automation belongs on the BROADLINK filter tab.
     * With a config body available (UI-managed automations), this is a
     * substring scan for `remote.send_command` or any known remote entity
     * id; the scan is shape-agnostic on purpose so trigger/action key
     * renames across HA versions can't break it. Without a body (YAML
     * automations expose no config over the API) it falls back to a name
     * heuristic, which the UI flags as such.
     */
    fun isBroadlinkRelated(
        configJson: String?,
        name: String,
        knownRemoteEntityIds: Set<String>,
    ): Boolean {
        if (configJson != null) {
            if (configJson.contains("remote.send_command")) return true
            return knownRemoteEntityIds.any { configJson.contains(it) }
        }
        val lower = name.lowercase()
        if ("broadlink" in lower) return true
        return knownRemoteEntityIds.any { remote ->
            val objectId = remote.substringAfter('.').replace('_', ' ')
            objectId.isNotBlank() && objectId in lower
        }
    }

    /** Fresh config-API id for a created automation. HA's own editor uses
     *  epoch millis; mirroring that keeps ids unique and inoffensive. */
    fun newAutomationId(nowMillis: Long): String = nowMillis.toString()
}
