package com.github.itskenny0.r1ha.feature.broadlink

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The machine marker that makes a Home Assistant automation recognizable
 * as an R1HA Broadlink catalog entry. The catalog's source of truth is
 * HA itself: one tagged automation per learned command, so the registry
 * survives app reinstalls and is shared by every install pointed at the
 * same server.
 *
 * Marker format (the automation's `description` field, verbatim):
 *
 *     R1HA|Broadlink|v1|{"remote":"remote.x","device":"TV","command":"power","type":"ir"}
 *
 * Four pipe-separated fields: family tag, feature tag, format version,
 * compact-JSON payload. The split uses limit = 4 so pipes inside the JSON
 * payload (notes free-text) never break parsing. Parsing is defensive:
 * descriptions carrying the prefix but an unknown version or an unreadable
 * payload still surface as catalog rows, read-only, so a newer app's
 * entries are visible (and fireable via automation.trigger) but never
 * rewritten or deleted by an older app that can't understand them.
 */
object BroadlinkMarker {

    /** Everything before the version field; the cheap "is it ours" probe. */
    const val PREFIX = "R1HA|Broadlink|"

    private const val VERSION = "v1"

    /** The send_command identity carried inside a v1 marker. [remote] is the
     *  blaster's entity_id; [device] + [command] key HA's stored code (both
     *  case-sensitive, because HA's .storage keys are). */
    data class CommandMeta(
        val remote: String,
        val device: String,
        val command: String,
        /** "ir" or "rf"; cosmetic after capture but drives the type badge. */
        val type: String = "ir",
        val notes: String = "",
    )

    sealed interface Parsed {
        /** A v1 marker this app fully understands. */
        data class Marked(val meta: CommandMeta) : Parsed

        /** Our marker family, but a version (or payload) this app can't
         *  rewrite safely. Listed read-only; [version] is shown verbatim. */
        data class ReadOnly(val version: String) : Parsed

        /** Not an R1HA Broadlink marker at all. */
        data object NotMarker : Parsed
    }

    fun encode(meta: CommandMeta): String {
        val payload = buildJsonObject {
            put("remote", meta.remote)
            put("device", meta.device)
            put("command", meta.command)
            put("type", meta.type)
            // notes is the only optional slot; omitted when blank so the
            // common case stays minimal.
            if (meta.notes.isNotBlank()) put("notes", meta.notes)
        }
        return "$PREFIX$VERSION|$payload"
    }

    fun parse(description: String?): Parsed {
        if (description == null || !description.startsWith(PREFIX)) return Parsed.NotMarker
        val parts = description.split('|', limit = 4)
        // startsWith(PREFIX) guarantees parts[0..1]; a missing version or
        // payload is still "ours but broken" and must stay read-only rather
        // than vanish from the catalog.
        val version = parts.getOrNull(2).orEmpty()
        if (version != VERSION) return Parsed.ReadOnly(version.ifBlank { "?" })
        val payload = parts.getOrNull(3) ?: return Parsed.ReadOnly(version)
        val obj = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: return Parsed.ReadOnly(version)
        fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.content
        val remote = str("remote")
        val device = str("device")
        val command = str("command")
        if (remote.isNullOrBlank() || device.isNullOrBlank() || command.isNullOrBlank()) {
            return Parsed.ReadOnly(version)
        }
        return Parsed.Marked(
            CommandMeta(
                remote = remote,
                device = device,
                command = command,
                type = str("type")?.takeIf { it == "rf" } ?: "ir",
                notes = str("notes").orEmpty(),
            ),
        )
    }

    /**
     * Deterministic config-store id for a command's automation:
     *
     *     r1ha_broadlink_<slug>_<hash16>
     *
     * The slug (lowercased remote object id + device + command, non
     * [a-z0-9] runs collapsed to "_", capped at 48 chars) exists for human
     * readability only; identity rests on the 16-hex FNV-1a 64 hash of the
     * raw NUL-joined triple, so two triples whose slugs collide ("TV!" vs
     * "TV?") still get distinct ids, and boundary shifts ("ab"+"c" vs
     * "a"+"bc") can't alias. Determinism is the point: re-learning or
     * re-registering the same (remote, device, command) overwrites its own
     * automation instead of accreting duplicates.
     */
    fun automationIdFor(remote: String, device: String, command: String): String {
        val slug = listOf(remote.substringAfter('.'), device, command)
            .joinToString("_") { slugOf(it) }
            .take(48)
            .trimEnd('_')
        val hash = String.format(
            java.util.Locale.US,
            "%016x",
            fnv1a64("$remote\u0000$device\u0000$command"),
        )
        return "r1ha_broadlink_${slug}_$hash"
    }

    /** Default automation alias, the user-facing label: "TV · Power (R1HA IR)". */
    fun defaultAlias(meta: CommandMeta): String =
        "${meta.device} · ${meta.command} (R1HA ${meta.type.uppercase()})"

    private fun slugOf(raw: String): String {
        val sb = StringBuilder()
        var lastUnderscore = true
        for (ch in raw.lowercase()) {
            if (ch in 'a'..'z' || ch in '0'..'9') {
                sb.append(ch)
                lastUnderscore = false
            } else if (!lastUnderscore) {
                sb.append('_')
                lastUnderscore = true
            }
        }
        val out = sb.toString().trim('_')
        return out.ifEmpty { "x" }
    }

    private fun fnv1a64(s: String): Long {
        var h = -0x340d631b7bdddcdbL
        for (ch in s) {
            h = h xor ch.code.toLong()
            h *= 0x100000001b3L
        }
        return h
    }
}
