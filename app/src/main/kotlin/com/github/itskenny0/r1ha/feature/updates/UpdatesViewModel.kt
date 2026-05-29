package com.github.itskenny0.r1ha.feature.updates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * Drives the HA Updates surface — lists every `update.*` entity (HA Core,
 * Supervisor, OS, add-ons, integration firmware) with installed → latest
 * version, release notes link, and an install dispatcher.
 *
 * HA exposes the following attributes on update entities:
 *  - `installed_version` (string)
 *  - `latest_version` (string)
 *  - `title` (friendly, often more descriptive than `friendly_name`)
 *  - `release_summary` (markdown blurb, usually a few sentences)
 *  - `release_url` (link to the full changelog / release notes)
 *  - `auto_update` (bool — whether HA will install automatically)
 *  - `in_progress` (bool — true while an install is running)
 *  - `update_percentage` (0..100, sparsely populated; null mid-install on
 *    integrations that don't expose granular progress)
 *  - `supported_features` (bitmask: 1 = install, 2 = specific_version,
 *    4 = progress, 8 = backup, 16 = release_notes)
 *
 * No state subscription — we pull on every refresh (mirrors HelpersScreen /
 * AutomationsScreen which use the same REST-fetch pattern). Manual refresh
 * after every install dispatch picks up the new in_progress + version
 * fields without waiting for HA's natural state broadcast.
 */
class UpdatesViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    /** Group bucket for the row's category chip + section ordering on screen.
     *  HA doesn't expose a typed category, so we infer from the entity_id
     *  prefix: `update.home_assistant_*` and `update.<supervisor>_*` are the
     *  core platform, `update.<addon_slug>_update` is an add-on, everything
     *  else is an integration / device firmware. */
    enum class Bucket(val label: String) {
        CORE("CORE"),
        ADDON("ADD-ON"),
        INTEGRATION("INTEGRATION"),
    }

    @androidx.compose.runtime.Stable
    data class Entry(
        val id: EntityId,
        /** Best human-readable title — falls back to friendly_name, then
         *  the prettified entity_id. */
        val title: String,
        val bucket: Bucket,
        /** True when HA reports an update is available (state == "on"). */
        val updateAvailable: Boolean,
        val installedVersion: String?,
        val latestVersion: String?,
        val releaseSummary: String?,
        val releaseUrl: String?,
        /** Pre-install backup support — drives whether the install dialog
         *  offers the "Back up first" toggle. Derived from the
         *  supported_features bitmask (bit 3 / value 8). */
        val supportsBackup: Boolean,
        /** True while the install is running. Disables the install button
         *  and renders a progress chip on the row. */
        val inProgress: Boolean,
        /** 0..100, or null when HA doesn't report granular progress. */
        val progressPercent: Int?,
        /** Whether HA's `auto_update` flag is set — purely informational
         *  badge ("AUTO") so the user understands no manual install is
         *  required for this entity. */
        val autoUpdate: Boolean,
        /** True when the user has previously skipped the currently-offered
         *  version: HA keeps the entity "available" but sets `skipped_version`
         *  to the latest. Drives a SKIPPED badge plus the clear-skip action so
         *  a skip isn't a one-way door. */
        val skipped: Boolean = false,
    ) {
        val hasReleaseNotes: Boolean get() =
            !releaseUrl.isNullOrBlank() || !releaseSummary.isNullOrBlank()
    }

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val all: List<Entry> = emptyList(),
        val error: String? = null,
    ) {
        /** Sorted view: in-progress first (the user wants to see "installing
         *  HA core…" at the top), then available updates, then up-to-date.
         *  Within each tier, sorted by bucket (Core → Add-on → Integration)
         *  then alphabetically by title so the same install always shows in
         *  the same slot. */
        val ordered: List<Entry> get() = all.sortedWith(
            compareBy<Entry> { !it.inProgress }
                .thenBy { !it.updateAvailable }
                .thenBy { it.bucket.ordinal }
                .thenBy { it.title.lowercase() },
        )

        val availableCount: Int get() = all.count { it.updateAvailable && !it.inProgress }
        val inProgressCount: Int get() = all.count { it.inProgress }
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            haRepository.listRawEntitiesByDomain("update").fold(
                onSuccess = { rows ->
                    val entries = rows.map { row ->
                        val attrs = row.attributes
                        val title = (attrs["title"] as? JsonPrimitive)?.content
                            ?.takeIf { it.isNotBlank() }
                            ?: row.friendlyName.takeIf { it.isNotBlank() }
                            ?: row.entityId.substringAfter('.').replace('_', ' ')
                        val installed = (attrs["installed_version"] as? JsonPrimitive)?.content
                            ?.takeIf { it.isNotBlank() }
                        val latest = (attrs["latest_version"] as? JsonPrimitive)?.content
                            ?.takeIf { it.isNotBlank() }
                        val summary = (attrs["release_summary"] as? JsonPrimitive)?.content
                            ?.takeIf { it.isNotBlank() }
                        val url = (attrs["release_url"] as? JsonPrimitive)?.content
                            ?.takeIf { it.isNotBlank() }
                        val features = (attrs["supported_features"] as? JsonPrimitive)?.content
                            ?.toIntOrNull() ?: 0
                        val inProgressRaw = attrs["in_progress"]
                        val inProgress = when (val p = inProgressRaw) {
                            is JsonPrimitive -> p.content == "true" || (p.content.toIntOrNull() ?: 0) > 0
                            else -> false
                        }
                        // update_percentage is sometimes an int, sometimes the literal `null`
                        // even when an install is running — only show the chip when we have
                        // a real number. Negative values from broken integrations are clamped.
                        val pct = (attrs["update_percentage"] as? JsonPrimitive)?.content?.toIntOrNull()
                            ?.coerceIn(0, 100)
                        val autoUpdate = (attrs["auto_update"] as? JsonPrimitive)?.content == "true"
                        // HA marks a skipped update by stamping skipped_version with
                        // the version that was skipped; it matches latest_version
                        // until a newer release lands. Treat any non-blank
                        // skipped_version that equals the offered latest as skipped.
                        val skippedVersion = (attrs["skipped_version"] as? JsonPrimitive)?.content
                            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                        val skipped = skippedVersion != null &&
                            (latest == null || skippedVersion == latest)
                        Entry(
                            id = EntityId(row.entityId),
                            title = title,
                            bucket = bucketFor(row.entityId),
                            updateAvailable = row.state.equals("on", ignoreCase = true),
                            installedVersion = installed,
                            latestVersion = latest,
                            releaseSummary = summary,
                            releaseUrl = url,
                            // Bit 3 (value 8) in HA's update entity supported_features.
                            // 0x01 install / 0x02 specific_version / 0x04 progress /
                            // 0x08 backup / 0x10 release_notes.
                            supportsBackup = (features and 0x08) != 0,
                            inProgress = inProgress,
                            progressPercent = pct,
                            autoUpdate = autoUpdate,
                            skipped = skipped,
                        )
                    }
                    R1Log.i("Updates", "loaded ${entries.size} update entities")
                    _ui.value = _ui.value.copy(loading = false, all = entries, error = null)
                },
                onFailure = { t ->
                    R1Log.w("Updates", "load failed: ${t.message}")
                    Toaster.error("Updates load failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(loading = false, error = t.message)
                },
            )
        }
    }

    /**
     * Classify an update entity into one of the three buckets by its entity_id
     * prefix. HA's convention is reliable across recent versions; integrations
     * that pre-date the convention land in INTEGRATION which is the right
     * default. The list of CORE prefixes mirrors what `update.home_assistant_*`
     * and `update.supervisor` install when present.
     */
    private fun bucketFor(entityId: String): Bucket {
        val tail = entityId.substringAfter('.')
        return when {
            tail.startsWith("home_assistant_core") ||
                tail.startsWith("home_assistant_supervisor") ||
                tail.startsWith("home_assistant_operating_system") ||
                tail == "supervisor" -> Bucket.CORE
            // HA's add-on update entities all end in "_update"; the prefix is
            // the add-on slug. Discriminate against integrations that happen to
            // include "_update" by also requiring the prefix to NOT match any
            // CORE pattern.
            tail.endsWith("_update") -> Bucket.ADDON
            else -> Bucket.INTEGRATION
        }
    }

    /**
     * Fire `update.install` with optional backup. HA enforces the
     * supported_features bitmask: passing backup=true on an entity without
     * SUPPORT_BACKUP is silently ignored; passing version on an entity without
     * SUPPORT_SPECIFIC_VERSION is rejected with an HA-side error which our
     * service-failure path will surface as a toast.
     */
    fun install(entry: Entry, backup: Boolean) {
        viewModelScope.launch {
            val call = ServiceCall.installUpdate(entry.id, version = null, backup = backup)
            haRepository.call(call).fold(
                onSuccess = {
                    R1Log.i("Updates", "install dispatched for ${entry.id.value} (backup=$backup)")
                    Toaster.show("Installing '${entry.title}'…")
                },
                onFailure = { t ->
                    R1Log.w("Updates", "install ${entry.id.value} failed: ${t.message}")
                    Toaster.error("Install failed: ${t.message ?: "unknown"}")
                },
            )
            // Settle delay before refresh — HA flips `in_progress` to true
            // asynchronously on its side once the integration starts the
            // install. Without the delay we'd often miss the flip and the
            // row would briefly look unchanged.
            kotlinx.coroutines.delay(800L)
            refresh()
        }
    }

    fun skip(entry: Entry) {
        viewModelScope.launch {
            haRepository.call(ServiceCall.skipUpdate(entry.id)).fold(
                onSuccess = {
                    R1Log.i("Updates", "skipped ${entry.id.value}")
                    Toaster.show("Skipped '${entry.title}'")
                },
                onFailure = { t ->
                    R1Log.w("Updates", "skip ${entry.id.value} failed: ${t.message}")
                    Toaster.error("Skip failed: ${t.message ?: "unknown"}")
                },
            )
            kotlinx.coroutines.delay(400L)
            refresh()
        }
    }

    /** Un-skip a previously-skipped update so it re-surfaces for install. The
     *  inverse of [skip]; uses HA's `update.clear_skipped`. */
    fun clearSkipped(entry: Entry) {
        viewModelScope.launch {
            haRepository.call(ServiceCall.clearSkippedUpdate(entry.id)).fold(
                onSuccess = {
                    R1Log.i("Updates", "cleared skip for ${entry.id.value}")
                    Toaster.show("Restored '${entry.title}'")
                },
                onFailure = { t ->
                    R1Log.w("Updates", "clear-skip ${entry.id.value} failed: ${t.message}")
                    Toaster.error("Restore failed: ${t.message ?: "unknown"}")
                },
            )
            kotlinx.coroutines.delay(400L)
            refresh()
        }
    }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { UpdatesViewModel(haRepository) }
        }
    }
}
