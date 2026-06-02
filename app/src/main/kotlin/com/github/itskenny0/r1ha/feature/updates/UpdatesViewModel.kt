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

/**
 * Drives the HA Updates surface: lists every `update.*` entity (HA Core,
 * Supervisor, OS, add-ons, integration firmware) with installed/latest
 * version, release notes link, and an install dispatcher.
 *
 * HA exposes the following attributes on update entities:
 *  - `installed_version` (string)
 *  - `latest_version` (string)
 *  - `title` (friendly, often more descriptive than `friendly_name`)
 *  - `release_summary` (markdown blurb, usually a few sentences)
 *  - `release_url` (link to the full changelog / release notes)
 *  - `auto_update` (bool: whether HA will install automatically)
 *  - `in_progress` (bool: true while an install is running)
 *  - `update_percentage` (0..100, sparsely populated; null mid-install on
 *    integrations that don't expose granular progress)
 *  - `supported_features` (bitmask: 1 = install, 2 = specific_version,
 *    4 = progress, 8 = backup, 16 = release_notes)
 *
 * No state subscription: we pull on every refresh (mirrors HelpersScreen /
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
        /** Best human-readable title: falls back to friendly_name, then
         *  the prettified entity_id. */
        val title: String,
        val bucket: Bucket,
        /** True when HA reports an update is available (state == "on"). */
        val updateAvailable: Boolean,
        val installedVersion: String?,
        val latestVersion: String?,
        val releaseSummary: String?,
        val releaseUrl: String?,
        /** HA `entity_picture` attribute: a relative `/api/...` path or an
         *  absolute URL pointing at the integration / brand icon. Rendered as
         *  the row thumbnail via the shared AsyncBitmap loader; null falls back
         *  to the bucket badge alone. */
        val entityPicture: String?,
        /** Pre-install backup support: drives whether the install dialog
         *  offers the "Back up first" toggle. Derived from the
         *  supported_features bitmask (bit 3 / value 8). */
        val supportsBackup: Boolean,
        /** Whether HA can drive the install from here: update available AND the
         *  INSTALL supported_feature is set. Read-only firmware entities report
         *  a newer version without INSTALL, so we offer NOTES but no INSTALL. */
        val canInstall: Boolean,
        /** True when HA reports a determinate install percentage (the PROGRESS
         *  feature is set and update_percentage is non-null). Drives a
         *  determinate vs indeterminate progress bar. */
        val determinateProgress: Boolean,
        /** True while the install is running. Disables the install button
         *  and renders a progress chip on the row. */
        val inProgress: Boolean,
        /** 0..100, or null when HA doesn't report granular progress. */
        val progressPercent: Int?,
        /** Whether HA's `auto_update` flag is set: purely informational
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
                .thenBy { !(it.updateAvailable && !it.skipped) }
                .thenBy { !it.updateAvailable }
                .thenBy { it.bucket.ordinal }
                .thenBy { it.title.lowercase() },
        )

        /** Count for the summary band: genuinely actionable updates only.
         *  Skipped updates are excluded (HA hides them from its default view
         *  until a newer version lands) so the band matches what the user can
         *  act on without first restoring a skip. */
        val availableCount: Int get() =
            all.count { it.updateAvailable && !it.inProgress && !it.skipped }
        val inProgressCount: Int get() = all.count { it.inProgress }
        /** Skipped-but-still-offered updates, surfaced as a secondary count so
         *  the user knows there's something parked behind a skip. */
        val skippedCount: Int get() =
            all.count { it.updateAvailable && !it.inProgress && it.skipped }
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
                        val installed = UpdatesLogic.stringAttr(attrs, "installed_version")
                        val latest = UpdatesLogic.stringAttr(attrs, "latest_version")
                        val features = UpdatesLogic.supportedFeatures(attrs)
                        val available = UpdatesLogic.updateAvailable(row.state, installed, latest)
                        val percent = UpdatesLogic.progressPercent(attrs)
                        Entry(
                            id = EntityId(row.entityId),
                            title = UpdatesLogic.titleFor(
                                titleAttr = UpdatesLogic.stringAttr(attrs, "title"),
                                friendlyName = row.friendlyName,
                                entityId = row.entityId,
                            ),
                            bucket = UpdatesLogic.bucketFor(row.entityId),
                            updateAvailable = available,
                            installedVersion = installed,
                            latestVersion = latest,
                            releaseSummary = UpdatesLogic.stringAttr(attrs, "release_summary"),
                            releaseUrl = UpdatesLogic.stringAttr(attrs, "release_url"),
                            entityPicture = UpdatesLogic.stringAttr(attrs, "entity_picture"),
                            supportsBackup = UpdatesLogic.supportsBackup(features),
                            canInstall = UpdatesLogic.canInstall(available, features),
                            inProgress = UpdatesLogic.inProgress(attrs["in_progress"]),
                            progressPercent = percent,
                            determinateProgress = UpdatesLogic.usesProgress(features, percent),
                            autoUpdate = UpdatesLogic.autoUpdate(attrs),
                            skipped = UpdatesLogic.isSkipped(
                                skippedVersion = UpdatesLogic.stringAttr(attrs, "skipped_version"),
                                latestVersion = latest,
                            ),
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
            // Settle delay before refresh: HA flips `in_progress` to true
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
