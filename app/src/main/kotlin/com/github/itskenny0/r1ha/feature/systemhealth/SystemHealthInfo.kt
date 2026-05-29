package com.github.itskenny0.r1ha.feature.systemhealth

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Locale

/**
 * Pure model + parser for Home Assistant's `system_health/info` websocket
 * payload. HA returns one entry per integration domain that registered a
 * system-health callback, e.g.
 *
 * ```json
 * {
 *   "homeassistant": {
 *     "info": {
 *       "version": "2024.6.0",
 *       "installation_type": "Home Assistant OS",
 *       "dev": false,
 *       "hassio": true,
 *       "docker": true,
 *       "user": "root",
 *       "virtualenv": false,
 *       "python_version": "3.12.2",
 *       "os_name": "Linux",
 *       "arch": "x86_64",
 *       "timezone": "Europe/Berlin",
 *       "config_dir": "/config"
 *     }
 *   },
 *   "cloud": {
 *     "info": {
 *       "logged_in": true,
 *       "remote_enabled": true,
 *       "can_reach_cert_server": "ok",
 *       "can_reach_cloud": { "type": "failed", "error": "unreachable" }
 *     }
 *   },
 *   "lovelace": { "info": { "dashboards": 3, "mode": "auto-gen", "resources": 4 } }
 * }
 * ```
 *
 * Reachability / async detail values arrive either as bare strings (`"ok"`)
 * or as objects tagged with a `type` field (`{"type":"failed","error":...}`,
 * `{"type":"pending"}`). We normalise both into [HealthValue] so the UI can
 * render a clear OK / pending / failed indicator without re-sniffing JSON.
 *
 * Everything here is pure and side-effect free so it unit-tests without a
 * device or websocket.
 */

/** Outcome flavour for a single detail row, used to colour its indicator. */
enum class HealthStatus { OK, PENDING, FAILED, NEUTRAL }

/**
 * A normalised detail value. [display] is the human string to show; [status]
 * drives the indicator. Plain scalars resolve to [HealthStatus.NEUTRAL]; only
 * HA's tagged reachability values (or the bare `"ok"`/`"failed"` strings) carry
 * a non-neutral status. [error] holds the optional failure detail HA attaches.
 */
data class HealthValue(
    val display: String,
    val status: HealthStatus = HealthStatus.NEUTRAL,
    val error: String? = null,
)

/** One key/value detail row within a domain section. */
data class HealthRow(
    val key: String,
    val label: String,
    val value: HealthValue,
)

/** All detail rows HA reported for a single integration domain. */
data class HealthSection(
    val domain: String,
    val title: String,
    val rows: List<HealthRow>,
) {
    /** True when any row in the section reports a failed reachability check. */
    val hasFailure: Boolean get() = rows.any { it.value.status == HealthStatus.FAILED }

    /** True when any row is still resolving an async reachability check. */
    val hasPending: Boolean get() = rows.any { it.value.status == HealthStatus.PENDING }
}

object SystemHealthInfo {

    /**
     * Parse the raw `result` element of a `system_health/info` reply into
     * grouped, sorted sections. Unknown / malformed entries are skipped rather
     * than throwing so a single bad integration can't blank the whole screen.
     *
     * `homeassistant` always sorts first (it's the core section users look for);
     * the rest follow alphabetically by display title, case-insensitively.
     */
    fun parse(result: JsonElement?): List<HealthSection> {
        val root = result as? JsonObject ?: return emptyList()
        val sections = root.mapNotNull { (domain, entry) ->
            val entryObj = entry as? JsonObject ?: return@mapNotNull null
            // HA nests the detail map under "info"; older/odd payloads may put
            // the map directly under the domain key, so fall back to that.
            val info = (entryObj["info"] as? JsonObject) ?: entryObj
            val rows = info.entries
                .map { (key, value) -> HealthRow(key, humanizeKey(key), normalizeValue(value)) }
                .sortedBy { it.label.lowercase(Locale.US) }
            if (rows.isEmpty()) return@mapNotNull null
            HealthSection(domain = domain, title = humanizeDomain(domain), rows = rows)
        }
        return sections.sortedWith(
            compareByDescending<HealthSection> { it.domain == "homeassistant" }
                .thenBy { it.title.lowercase(Locale.US) },
        )
    }

    /**
     * Normalise a single JSON detail value into a [HealthValue]. Handles HA's
     * tagged reachability objects (`{"type":"ok|pending|failed", ...}`), the
     * bare `"ok"`/`"failed"`/`"pending"` string shorthands, primitives, and
     * arrays.
     */
    fun normalizeValue(value: JsonElement?): HealthValue = when (value) {
        null, is JsonNull -> HealthValue("unknown", HealthStatus.NEUTRAL)
        is JsonObject -> normalizeObject(value)
        is JsonArray -> HealthValue(
            value.joinToString(", ") { normalizeValue(it).display },
            HealthStatus.NEUTRAL,
        )
        is JsonPrimitive -> normalizePrimitive(value)
        else -> HealthValue(value.toString(), HealthStatus.NEUTRAL)
    }

    private fun normalizeObject(obj: JsonObject): HealthValue {
        val type = (obj["type"] as? JsonPrimitive)?.content?.lowercase(Locale.US)
        if (type != null) {
            val error = (obj["error"] as? JsonPrimitive)?.content
            return when (type) {
                "failed" -> HealthValue(
                    display = error?.let { "failed: $it" } ?: "failed",
                    status = HealthStatus.FAILED,
                    error = error,
                )
                "pending" -> HealthValue("checking…", HealthStatus.PENDING)
                else -> HealthValue("ok", HealthStatus.OK)
            }
        }
        // Untagged object: present its keys compactly rather than dumping JSON.
        val rendered = obj.entries.joinToString(", ") { (k, v) ->
            "$k=${normalizeValue(v).display}"
        }
        return HealthValue(rendered.ifEmpty { "{}" }, HealthStatus.NEUTRAL)
    }

    private fun normalizePrimitive(p: JsonPrimitive): HealthValue {
        if (p.isString) {
            return when (p.content.lowercase(Locale.US)) {
                "ok" -> HealthValue("ok", HealthStatus.OK)
                "failed" -> HealthValue("failed", HealthStatus.FAILED)
                "pending" -> HealthValue("checking…", HealthStatus.PENDING)
                else -> HealthValue(p.content, HealthStatus.NEUTRAL)
            }
        }
        p.booleanOrNull?.let { return HealthValue(if (it) "yes" else "no", HealthStatus.NEUTRAL) }
        p.longOrNull?.let { return HealthValue(it.toString(), HealthStatus.NEUTRAL) }
        p.doubleOrNull?.let { return HealthValue(formatDouble(it), HealthStatus.NEUTRAL) }
        return HealthValue(p.content, HealthStatus.NEUTRAL)
    }

    private fun formatDouble(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString()
        else String.format(Locale.US, "%.2f", d)

    /**
     * Turn a snake_case domain id into a display title. A small lookup covers
     * the domains whose acronym/casing differs from naive title-casing
     * (`mqtt` -> `MQTT`, `homeassistant` -> `Home Assistant`); everything else
     * gets generic underscore-to-space title casing.
     */
    fun humanizeDomain(domain: String): String = DOMAIN_TITLES[domain] ?: titleCase(domain)

    /** Turn a snake_case detail key into a readable label. */
    fun humanizeKey(key: String): String = KEY_LABELS[key] ?: titleCase(key)

    private fun titleCase(raw: String): String = raw
        .split('_', '-', ' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        }

    private val DOMAIN_TITLES = mapOf(
        "homeassistant" to "Home Assistant",
        "cloud" to "Home Assistant Cloud",
        "mqtt" to "MQTT",
        "dhcp" to "DHCP",
        "ssdp" to "SSDP",
        "zha" to "ZHA",
        "hassio" to "Supervisor",
        "lovelace" to "Dashboards",
        "recorder" to "Recorder",
        "updater" to "Updater",
    )

    private val KEY_LABELS = mapOf(
        "version" to "Version",
        "installation_type" to "Installation type",
        "dev" to "Development",
        "hassio" to "Supervisor",
        "docker" to "Docker",
        "container_arch" to "Container arch",
        "supervisor_api" to "Supervisor API",
        "version_api" to "Version API",
        "user" to "User",
        "virtualenv" to "Virtualenv",
        "python_version" to "Python version",
        "os_name" to "OS name",
        "os_version" to "OS version",
        "arch" to "Architecture",
        "timezone" to "Time zone",
        "config_dir" to "Config directory",
        "logged_in" to "Logged in",
        "subscription_expiration" to "Subscription expiration",
        "relayer_connected" to "Relayer connected",
        "remote_enabled" to "Remote enabled",
        "remote_connected" to "Remote connected",
        "alexa_enabled" to "Alexa enabled",
        "google_enabled" to "Google enabled",
        "can_reach_cert_server" to "Reach cert server",
        "can_reach_cloud_auth" to "Reach cloud auth",
        "can_reach_cloud" to "Reach cloud",
        "update_channel" to "Update channel",
        "dashboards" to "Dashboards",
        "resources" to "Resources",
        "mode" to "Mode",
        "views" to "Views",
        "oldest_recorder_run" to "Oldest recorder run",
        "current_recorder_run" to "Current recorder run",
        "estimated_db_size" to "Estimated DB size",
        "database_engine" to "Database engine",
        "database_version" to "Database version",
    )
}
