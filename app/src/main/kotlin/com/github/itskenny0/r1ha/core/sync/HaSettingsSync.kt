package com.github.itskenny0.r1ha.core.sync

import com.github.itskenny0.r1ha.core.ha.ConnectionState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.prefs.AdvancedSettings
import com.github.itskenny0.r1ha.core.prefs.AppBackup
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.applyOnto
import com.github.itskenny0.r1ha.core.prefs.toBackup
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Mirrors the user's preferences to/from Home Assistant's per-user JSON
 * storage (the `frontend/get_user_data` + `frontend/set_user_data` WS
 * commands HA's own frontend uses for dashboard preferences). When the
 * user opts in via [AppSettings.integrations.haSyncEnabled], multiple
 * R1 / phone installs signed into the same HA user converge on the same
 * settings.
 *
 * Conflict model: last-write-wins via a wall-clock millis timestamp that
 * travels with every payload. On pull, the remote value is applied iff
 * its timestamp strictly exceeds the local "last known applied"
 * timestamp; on push, we stamp `System.currentTimeMillis()`.
 *
 * Device-local fields (server URL + tokens, iBeacon major/minor/UUID,
 * webhook port/id, MQTT host + auth) are filtered out on push and
 * preserved from the local snapshot on pull, so each physical device
 * keeps its own network identity regardless of the toggle.
 *
 * Lifecycle: [start] launches three coroutines on [scope] —
 *   1. enable observer — runs initial pull when [haSyncEnabled] flips on
 *      and a connected WS is available;
 *   2. periodic pull at `integrations.haSyncIntervalSec`;
 *   3. local-edit observer that diffs the synced subset against the last
 *      remote-known hash and pushes if changed (debounced).
 */
class HaSettingsSync(
    private val settings: SettingsRepository,
    private val haRepository: HaRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var lastAppliedHash: Int = 0
    @Volatile private var lastRemoteTimestamp: Long = 0L
    @Volatile private var pendingPushJob: Job? = null

    /**
     * Live diagnostic for the Settings → Sync screen. Updated on every
     * pull / push attempt so the user can see whether sync is healthy
     * without having to dig through logs. Counts reset on process start.
     */
    @androidx.compose.runtime.Stable
    data class Stats(
        val lastPullAtMillis: Long = 0L,
        val lastPushAtMillis: Long = 0L,
        val lastRemoteTimestampMillis: Long = 0L,
        val lastErrorMessage: String? = null,
        val lastErrorAtMillis: Long = 0L,
        val inProgress: Boolean = false,
        val pullCount: Int = 0,
        val pushCount: Int = 0,
    )

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    fun start() {
        // Enable observer — fires an immediate pull whenever the toggle
        // flips to ON, so flipping it on a fresh device pulls down the
        // already-shared preferences without waiting for the next
        // periodic tick. Skipped in manual-only mode: that user wants to
        // press PULL NOW themselves and shouldn't have a baseline land
        // unexpectedly when they toggle sync on.
        scope.launch {
            settings.settings
                .map { it.integrations.haSyncEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) {
                        val manualOnly = runCatching {
                            settings.settings.first().integrations.haSyncManualOnly
                        }.getOrDefault(false)
                        if (manualOnly) {
                            R1Log.i("HaSync", "enabled; manual-only mode (no auto-pull)")
                        } else {
                            R1Log.i("HaSync", "enabled; pulling baseline from HA")
                            awaitWsConnected()
                            pull()
                        }
                    }
                }
        }

        // Periodic pull at the user's chosen interval. We re-read the
        // interval each tick so flipping it down/up takes effect within
        // one cycle rather than waiting for app restart. Suppressed in
        // manual-only mode — pullNow() still works for explicit taps.
        scope.launch {
            while (true) {
                val s = runCatching { settings.settings.first() }.getOrNull()
                val intervalSec = (s?.integrations?.haSyncIntervalSec ?: 300)
                    .coerceIn(30, 3600)
                delay(intervalSec * 1000L)
                if (s?.integrations?.haSyncEnabled == true &&
                    s.integrations.haSyncManualOnly == false &&
                    haRepository.connection.value is ConnectionState.Connected
                ) {
                    pull()
                }
            }
        }

        // Local-edit observer. Compares a hash of the SYNCED subset
        // against the last value we either pushed or pulled. Different
        // → schedule a push (debounced 5 s so a wheel of changes from
        // a settings sweep coalesces into one upload). Suppressed in
        // manual-only mode so edits don't trigger network churn.
        scope.launch {
            settings.settings
                .distinctUntilChanged()
                .collect { s ->
                    if (!s.integrations.haSyncEnabled) return@collect
                    if (s.integrations.haSyncManualOnly) return@collect
                    val hash = syncedSubsetHash(s)
                    if (hash == lastAppliedHash) return@collect
                    pendingPushJob?.cancel()
                    pendingPushJob = scope.launch {
                        delay(PUSH_DEBOUNCE_MS)
                        push(s, hash)
                    }
                }
        }
    }

    /** Force an immediate pull, ignoring the interval timer. Surfaced via
     *  Settings → "SYNC NOW" so the user can converge manually after editing
     *  on another device. */
    fun pullNow() {
        scope.launch { pull() }
    }

    /** Force an immediate push, ignoring debounce. Mainly useful when the
     *  user is about to hand a freshly-configured device to someone else
     *  and wants the current local state mirrored before sleep / shutdown. */
    fun pushNow() {
        scope.launch {
            val s = settings.settings.first()
            push(s, syncedSubsetHash(s))
        }
    }

    /** Probe HA for an existing payload at [USER_DATA_KEY]. Returns the
     *  remote timestamp when one exists, null when nothing has been written
     *  yet (or the WS isn't reachable). Used by the first-run prompt to
     *  decide whether to offer "import from HA" alongside "push to HA".
     *  Does NOT apply the payload — purely a peek. */
    suspend fun probeRemoteExists(): Long? {
        val result = haRepository.getUserData(USER_DATA_KEY)
        val payload = result.getOrNull() as? JsonObject ?: return null
        return payload["timestampMillis"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
    }

    private suspend fun awaitWsConnected() {
        // Bounded wait — if the WS never connects we still want enable-time
        // logic to give up so the user's UI doesn't appear stuck. 30 s is
        // generous enough to cover the standard reconnect-backoff schedule
        // but short enough that a misconfigured server surfaces as a toast
        // rather than spinning forever.
        kotlinx.coroutines.withTimeoutOrNull(30_000L) {
            // first { } suspends until the predicate matches and then RETURNS,
            // terminating collection. The previous `collect { if (connected)
            // return@collect }` only returned from the lambda — the StateFlow
            // never completes, so collection ran for the full 30 s timeout even
            // after the WS connected, delaying every enable-time pull/push by 30 s.
            haRepository.connection.first { it is ConnectionState.Connected }
        }
    }

    private suspend fun pull() {
        _stats.value = _stats.value.copy(inProgress = true)
        val result = haRepository.getUserData(USER_DATA_KEY)
        val now = clock()
        val payload = result.getOrNull() ?: run {
            val err = result.exceptionOrNull()
            if (err != null) {
                R1Log.w("HaSync.pull", "fetch failed: ${err.message}")
                _stats.value = _stats.value.copy(
                    inProgress = false,
                    lastErrorMessage = err.message ?: "pull failed",
                    lastErrorAtMillis = now,
                )
            } else {
                _stats.value = _stats.value.copy(
                    inProgress = false,
                    lastPullAtMillis = now,
                    pullCount = _stats.value.pullCount + 1,
                )
            }
            return
        }
        if (payload !is JsonObject) {
            R1Log.w("HaSync.pull", "unexpected payload shape: ${payload::class.simpleName}")
            _stats.value = _stats.value.copy(
                inProgress = false,
                lastErrorMessage = "Unexpected payload shape from HA",
                lastErrorAtMillis = now,
            )
            return
        }
        val remoteTimestamp = payload["timestampMillis"]?.jsonPrimitive?.longOrNull ?: 0L
        if (remoteTimestamp <= lastRemoteTimestamp) {
            // Either no fresh data, or it's the value we just pushed.
            _stats.value = _stats.value.copy(
                inProgress = false,
                lastPullAtMillis = now,
                pullCount = _stats.value.pullCount + 1,
            )
            return
        }
        val backupJson = payload["backup"]?.jsonObject ?: run {
            R1Log.w("HaSync.pull", "remote payload missing 'backup'")
            _stats.value = _stats.value.copy(
                inProgress = false,
                lastErrorMessage = "Remote payload missing 'backup' field",
                lastErrorAtMillis = now,
            )
            return
        }
        val backup = runCatching {
            AppBackup.json.decodeFromJsonElement(AppBackup.serializer(), backupJson)
        }.getOrElse {
            R1Log.w("HaSync.pull", "decode failed: ${it.message}")
            _stats.value = _stats.value.copy(
                inProgress = false,
                lastErrorMessage = "Decode failed: ${it.message ?: "unknown"}",
                lastErrorAtMillis = now,
            )
            return
        }
        // Apply remote, then re-overlay device-local fields from the live
        // local snapshot so the sync never overwrites a per-device value
        // (server URL, iBeacon ids, webhook bind, MQTT auth). Also restore
        // any opted-out categories from the local snapshot so the user's
        // exclusions hold across pulls.
        settings.update { prev ->
            val applied = backup.applyOnto(prev)
            val deviceFiltered = preserveDeviceLocal(applied, prev)
            val merged = preserveExcludedCategories(deviceFiltered, prev)
            // Record the just-applied hash so the local-edit observer
            // doesn't immediately push the value back. Update both volatile
            // fields before returning so the next collector emission for
            // this same state is a no-op.
            lastAppliedHash = syncedSubsetHash(merged)
            lastRemoteTimestamp = remoteTimestamp
            merged
        }
        _stats.value = _stats.value.copy(
            inProgress = false,
            lastPullAtMillis = now,
            lastRemoteTimestampMillis = remoteTimestamp,
            pullCount = _stats.value.pullCount + 1,
        )
        R1Log.i("HaSync.pull", "applied remote payload @ $remoteTimestamp")
    }

    private suspend fun push(s: AppSettings, hash: Int) {
        _stats.value = _stats.value.copy(inProgress = true)
        val ts = clock()
        val backup = s.toSyncBackup()
        val payload = buildJsonObject {
            put("timestampMillis", JsonPrimitive(ts))
            put("schema", JsonPrimitive(AppBackup.SCHEMA_VERSION))
            put("backup", AppBackup.json.encodeToJsonElement(AppBackup.serializer(), backup))
        }
        val outcome = haRepository.setUserData(USER_DATA_KEY, payload)
        outcome.onSuccess {
            lastAppliedHash = hash
            lastRemoteTimestamp = ts
            _stats.value = _stats.value.copy(
                inProgress = false,
                lastPushAtMillis = ts,
                lastRemoteTimestampMillis = ts,
                pushCount = _stats.value.pushCount + 1,
            )
            R1Log.i("HaSync.push", "pushed payload @ $ts")
        }.onFailure { t ->
            _stats.value = _stats.value.copy(
                inProgress = false,
                lastErrorMessage = t.message ?: "push failed",
                lastErrorAtMillis = clock(),
            )
            R1Log.w("HaSync.push", "failed: ${t.message}")
        }
    }

    /**
     * Hash of just the fields that participate in sync. Identical local +
     * remote subsets ⇒ identical hashes, so the push observer can short-
     * circuit when there's nothing to mirror. The non-synced device-local
     * fields are deliberately excluded so an iBeacon-major change on one
     * device doesn't churn a push that would do nothing observable on
     * the receiving devices.
     */
    private fun syncedSubsetHash(s: AppSettings): Int {
        // Reuse the AppBackup serialization as the canonical shape — same
        // codec round-tripping that pull/push use, so the hash is stable
        // across version-compatible serializer changes.
        val backup = s.toSyncBackup()
        val json = AppBackup.json.encodeToString(AppBackup.serializer(), backup)
        return json.hashCode()
    }

    companion object {
        /** Key under which the per-user payload lives inside HA's
         *  `frontend.user_data` collection. Namespaced with the app id
         *  so we don't collide with anyone else's user_data writes. */
        const val USER_DATA_KEY = "r1ha.settings"

        /** Push debounce — coalesces a sweep of edits (e.g. user dialling
         *  the dashboard threshold sliders) into one upload. */
        const val PUSH_DEBOUNCE_MS = 5_000L
    }
}

/**
 * Strip device-local fields from a backup before uploading to HA.
 * Each physical device keeps its own server URL, iBeacon identity,
 * webhook bind, and MQTT credentials; syncing those would collapse
 * every device onto the same network identity. Additionally, any
 * categories the user has opted out of (via
 * [AppSettings.integrations.haSyncExcludedCategories]) get their
 * fields zeroed to defaults so we don't leak local state for them.
 */
private fun AppSettings.toSyncBackup(): AppBackup {
    val defaults = AdvancedSettings()
    val sanitizedAdvanced = advanced.copy(
        iBeaconUuid = defaults.iBeaconUuid,
        iBeaconMajor = defaults.iBeaconMajor,
        iBeaconMinor = defaults.iBeaconMinor,
        webhookPort = defaults.webhookPort,
        webhookId = defaults.webhookId,
        mqttHost = defaults.mqttHost,
        mqttPort = defaults.mqttPort,
        mqttUsername = defaults.mqttUsername,
        mqttPassword = defaults.mqttPassword,
        mqttUseTls = defaults.mqttUseTls,
        mqttClientId = defaults.mqttClientId,
    )
    val sanitized = copy(server = null, advanced = sanitizedAdvanced)
    // For each excluded category, overwrite the live fields with their
    // built-in defaults so the upload doesn't reveal local values the user
    // explicitly chose not to share.
    val defaultsAll = AppSettings()
    val excluded = excludedSyncCategories()
    val masked = excluded.fold(sanitized) { acc, cat -> cat.preserve(acc, defaultsAll) }
    return masked.toBackup(createdAt = "")
}

/**
 * Restore opted-out categories from [prev] after a remote payload has been
 * applied. Mirror of the masking that [toSyncBackup] does on push.
 */
private fun preserveExcludedCategories(applied: AppSettings, prev: AppSettings): AppSettings {
    val excluded = prev.excludedSyncCategories()
    return excluded.fold(applied) { acc, cat -> cat.preserve(acc, prev) }
}

/** Resolve string names from storage to enum values, dropping any
 *  unknown entries (forwards-compat with new categories added in later
 *  builds — the persisted set keeps the unrecognised name but it has no
 *  effect on this build's sync filter). */
private fun AppSettings.excludedSyncCategories(): Set<SyncCategory> =
    integrations.haSyncExcludedCategories.mapNotNull { name ->
        runCatching { SyncCategory.valueOf(name) }.getOrNull()
    }.toSet()

/**
 * Re-apply local device-local fields on top of a freshly-applied remote
 * backup. The remote's server URL / iBeacon / webhook / MQTT values are
 * the sanitized defaults from [toSyncBackup], so we restore the prior
 * local values verbatim instead of letting the defaults clobber them.
 *
 * [Behavior.wheelTutorialSeen] is restored here too: it is per-device
 * onboarding state (flipped true after the first wheel event on THIS device),
 * not a synced preference. It rides inside `behavior`, so without this a pull
 * that includes the Behaviour category would reset the flag to a remote value
 * and re-show the one-shot "wheel to adjust" hint after every sync. Preserved
 * unconditionally, independent of the Behaviour category opt-in.
 */
internal fun preserveDeviceLocal(applied: AppSettings, prev: AppSettings): AppSettings {
    return applied.copy(
        server = prev.server,
        behavior = applied.behavior.copy(
            wheelTutorialSeen = prev.behavior.wheelTutorialSeen,
        ),
        advanced = applied.advanced.copy(
            iBeaconUuid = prev.advanced.iBeaconUuid,
            iBeaconMajor = prev.advanced.iBeaconMajor,
            iBeaconMinor = prev.advanced.iBeaconMinor,
            webhookPort = prev.advanced.webhookPort,
            webhookId = prev.advanced.webhookId,
            mqttHost = prev.advanced.mqttHost,
            mqttPort = prev.advanced.mqttPort,
            mqttUsername = prev.advanced.mqttUsername,
            mqttPassword = prev.advanced.mqttPassword,
            mqttUseTls = prev.advanced.mqttUseTls,
            mqttClientId = prev.advanced.mqttClientId,
        ),
    )
}
