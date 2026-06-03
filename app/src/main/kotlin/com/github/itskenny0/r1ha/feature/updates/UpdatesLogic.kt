package com.github.itskenny0.r1ha.feature.updates

import androidx.compose.ui.graphics.vector.ImageVector
import com.github.itskenny0.r1ha.ui.icons.R1IconSet
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * Pure, Android-free derivations for the Updates surface. All parsing of HA's
 * `update.*` attribute soup lives here so the [UpdatesViewModel] stays a thin
 * dispatcher and the tricky bits (the supported_features bitmask, the
 * sometimes-int / sometimes-bool / sometimes-null `in_progress`, the
 * skipped_version reconciliation, version ordering) are unit-testable without a
 * Compose or repository harness.
 *
 * Mirrors the HelpersLogic / PersonPresence idiom: a stateless object of small
 * functions, each with a single documented contract.
 */
object UpdatesLogic {

    /** HA update entity supported_features bitmask (see homeassistant
     *  components/update). We only act on INSTALL, SPECIFIC_VERSION, PROGRESS,
     *  BACKUP and RELEASE_NOTES. */
    const val FEATURE_INSTALL = 0x01
    const val FEATURE_SPECIFIC_VERSION = 0x02
    const val FEATURE_PROGRESS = 0x04
    const val FEATURE_BACKUP = 0x08
    const val FEATURE_RELEASE_NOTES = 0x10

    /** Pull a non-blank string attribute, treating the literal "null" HA
     *  sometimes serialises as absent. */
    fun stringAttr(attrs: JsonObject, key: String): String? =
        (attrs[key] as? JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

    /** supported_features is an int but arrives as a JSON number we read as a
     *  string; default 0 (no capabilities) when missing or unparseable. */
    fun supportedFeatures(attrs: JsonObject): Int =
        (attrs["supported_features"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

    fun hasFeature(features: Int, flag: Int): Boolean = (features and flag) != 0

    fun supportsBackup(features: Int): Boolean = hasFeature(features, FEATURE_BACKUP)

    fun supportsInstall(features: Int): Boolean = hasFeature(features, FEATURE_INSTALL)

    fun supportsProgress(features: Int): Boolean = hasFeature(features, FEATURE_PROGRESS)

    /**
     * Parse HA's `auto_update` flag. It arrives as a JSON bool we read as a
     * string, and can be the literal `null` on integrations that don't model
     * it; both non-"true" and null mean "not auto-updating".
     */
    fun autoUpdate(attrs: JsonObject): Boolean =
        (attrs["auto_update"] as? JsonPrimitive)?.content.equals("true", ignoreCase = true)

    /**
     * Whether HA can actually drive the install from the device: it must report
     * an update available AND advertise the INSTALL supported_feature. Mirrors
     * the frontend's `updateCanInstall`. Entities that surface a newer version
     * but lack INSTALL (some read-only firmware sensors) get NOTES but no
     * INSTALL button so we don't fire a service HA will reject.
     */
    fun canInstall(updateAvailable: Boolean, features: Int): Boolean =
        updateAvailable && supportsInstall(features)

    /**
     * Show a determinate progress bar only when HA both advertises PROGRESS and
     * has a concrete percentage; otherwise the install is indeterminate and we
     * render a spinning bar instead of a misleading 0 %. Mirrors the frontend's
     * `updateUsesProgress`.
     */
    fun usesProgress(features: Int, progressPercent: Int?): Boolean =
        supportsProgress(features) && progressPercent != null

    /**
     * `in_progress` is wildly inconsistent across integrations: a bool on some,
     * an int percentage on others, the literal `null` mid-install on a few.
     * Treat `true` or any positive number as "installing".
     */
    fun inProgress(value: JsonElement?): Boolean = when (value) {
        is JsonPrimitive -> value.content.equals("true", ignoreCase = true) ||
            (value.content.toIntOrNull() ?: 0) > 0
        else -> false
    }

    /**
     * Resolve the install progress percentage. HA exposes it on either the
     * dedicated `update_percentage` attribute or (older integrations) as an int
     * directly in `in_progress`. Clamped to 0..100; null when neither yields a
     * real number (so the UI shows an indeterminate "INSTALLING…" instead of a
     * bogus 0 %).
     */
    fun progressPercent(attrs: JsonObject): Int? {
        val direct = (attrs["update_percentage"] as? JsonPrimitive)?.content?.toIntOrNull()
        if (direct != null) return direct.coerceIn(0, 100)
        val fromInProgress = (attrs["in_progress"] as? JsonPrimitive)?.content?.toIntOrNull()
        return fromInProgress?.takeIf { it in 1..100 }?.coerceIn(0, 100)
    }

    /**
     * HA marks a skipped update by stamping `skipped_version` with the version
     * that was skipped; it stays equal to `latest_version` until a newer release
     * lands (at which point the skip no longer applies). Treat a non-blank
     * skipped_version that matches the offered latest (or when latest is
     * unknown) as skipped.
     */
    fun isSkipped(skippedVersion: String?, latestVersion: String?): Boolean {
        val skip = skippedVersion?.takeIf {
            it.isNotBlank() && !it.equals("null", ignoreCase = true)
        } ?: return false
        return latestVersion == null || skip == latestVersion
    }

    /**
     * Whether an update is actually available to install: HA reports the entity
     * state as "on" when latest > installed. We defend against integrations that
     * leave the state stale by also confirming the versions differ when both are
     * known.
     */
    fun updateAvailable(state: String, installed: String?, latest: String?): Boolean {
        val stateOn = state.equals("on", ignoreCase = true)
        if (!stateOn) return false
        // State says available; if both versions are present and identical the
        // entity is mis-reporting, so suppress the false positive.
        if (installed != null && latest != null) return installed != latest
        return true
    }

    /**
     * Classify an update entity into a section bucket by its entity_id suffix.
     * HA has no typed category, but the id convention is stable: the core
     * platform entities have fixed prefixes, add-on update entities end in
     * `_update`, everything else is an integration / device firmware.
     */
    fun bucketFor(entityId: String): UpdatesViewModel.Bucket {
        val tail = entityId.substringAfter('.')
        return when {
            tail.startsWith("home_assistant_core") ||
                tail.startsWith("home_assistant_supervisor") ||
                tail.startsWith("home_assistant_operating_system") ||
                tail == "supervisor" -> UpdatesViewModel.Bucket.CORE
            tail.endsWith("_update") -> UpdatesViewModel.Bucket.ADDON
            else -> UpdatesViewModel.Bucket.INTEGRATION
        }
    }

    /**
     * Category glyph for the row's bucket badge: the update arrow for the system
     * CORE platform, the script glyph for add-ons (managed mini-apps), and the
     * generic mark for the catch-all integration / firmware bucket. Tinted at the
     * call site to the badge colour.
     */
    fun bucketIcon(bucket: UpdatesViewModel.Bucket): ImageVector = when (bucket) {
        UpdatesViewModel.Bucket.CORE -> R1IconSet.Update
        UpdatesViewModel.Bucket.ADDON -> R1IconSet.Script
        UpdatesViewModel.Bucket.INTEGRATION -> R1IconSet.Generic
    }

    /** Best human-readable title: HA's `title` attribute, then friendly_name,
     *  then the prettified entity_id tail. */
    fun titleFor(titleAttr: String?, friendlyName: String, entityId: String): String =
        titleAttr?.takeIf { it.isNotBlank() }
            ?: friendlyName.takeIf { it.isNotBlank() }
            ?: entityId.substringAfter('.').replace('_', ' ')

    /**
     * Compare two dotted version strings (e.g. "2024.1.3" vs "2024.12.0").
     * Numeric segments compare numerically; non-numeric segments (rc, beta,
     * git hashes) fall back to a case-insensitive lexical compare, with a pure
     * release ranked above any pre-release suffix on the same numeric prefix.
     * Returns <0 when [a] is older, 0 when equal, >0 when [a] is newer. Either
     * argument null sorts as "older" (null < anything, two nulls equal).
     */
    fun compareVersions(a: String?, b: String?): Int {
        if (a == null && b == null) return 0
        if (a == null) return -1
        if (b == null) return 1
        if (a == b) return 0
        val sa = splitVersion(a)
        val sb = splitVersion(b)
        val n = maxOf(sa.size, sb.size)
        for (i in 0 until n) {
            val pa = sa.getOrNull(i)
            val pb = sb.getOrNull(i)
            // A missing trailing segment ("2.0" vs "2.0.1"): the shorter, when
            // the longer's extra segment is a release number, is older; when the
            // extra is a pre-release tag the shorter (release) is newer.
            if (pa == null) return if (pb!!.numeric != null) -1 else 1
            if (pb == null) return if (pa.numeric != null) 1 else -1
            val cmp = comparePart(pa, pb)
            if (cmp != 0) return cmp
        }
        return 0
    }

    private data class Part(val numeric: Long?, val text: String)

    private fun splitVersion(v: String): List<Part> =
        v.lowercase(Locale.US)
            .split('.', '-', '+', '_')
            .filter { it.isNotEmpty() }
            .map { seg -> Part(seg.toLongOrNull(), seg) }

    private fun comparePart(a: Part, b: Part): Int = when {
        a.numeric != null && b.numeric != null -> a.numeric.compareTo(b.numeric)
        // A numeric segment outranks a textual pre-release tag at the same
        // position ("2.0" release > "2.0rc1"; but those split differently,
        // here e.g. "1" vs "rc"): the number is the real release.
        a.numeric != null -> 1
        b.numeric != null -> -1
        else -> a.text.compareTo(b.text)
    }
}
