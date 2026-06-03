package com.github.itskenny0.r1ha.core.ha

import android.content.Context
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/**
 * Disk-backed snapshot of the HA entity cache. Lets the card stack paint with
 * the user's last-known state at cold start, before the WebSocket has even
 * connected — instead of showing a generic 'Connecting…' EmptyState the user
 * sees their actual deck within a couple of frames of app launch.
 *
 * Stored as a single JSON file in the app's files dir. Schema-versioned so
 * a future incompatible change can refuse-to-load older snapshots cleanly.
 * The persister is intentionally **lossy** — only the fields the card-stack
 * + sensor surfaces actually render are written. Everything else (HA's full
 * attributes blob, advanced color-mode metadata, climate setpoint ranges) is
 * re-fetched from HA on connect and merged in. This keeps the snapshot file
 * tiny (~5 KB for 50 entities) and the read-path fast.
 *
 * Writes are coalesced via a flow-driven debouncer — repeated `markDirty`
 * calls inside the debounce window collapse to one disk write. Without this
 * a busy session (wheel input → cache.update → markDirty per event) would
 * thrash the storage.
 */
class EntityStateCachePersister(
    private val appFilesDir: File,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 1_500L,
) {

    private val file: File get() = File(appFilesDir, FILE_NAME)
    private val dirtyTicks = MutableSharedFlow<Unit>(
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    /** Snapshot the latest writeable cache here so the debouncer thread can read
     *  it without holding a reference to the upstream MutableStateFlow. */
    @Volatile private var latest: Map<EntityId, EntityState> = emptyMap()

    /**
     * Wire the persister to a live cache flow. Call once from [DefaultHaRepository.start].
     * Subsequent `cache.update` emissions will be debounced and persisted.
     *
     * Returns the [Job] of the debounce-write loop so the caller can cancel it on a
     * stop()/start() cycle — otherwise each rebind would leak another collector onto the
     * long-lived [scope], and every [markDirty] would fan out into one redundant disk write
     * per leaked collector.
     */
    fun bind(): Job =
        scope.launch {
            dirtyTicks.collectLatest {
                // collectLatest cancels the previous in-flight delay every time
                // a new tick arrives — i.e. real debounce: only the LAST tick
                // inside the window survives to actually write.
                delay(debounceMs)
                withContext(Dispatchers.IO) { writeBlocking(latest) }
            }
        }

    /** Tell the persister the in-memory cache changed. Cheap — only emits a token
     *  on the bounded flow, the actual write happens off the hot path. */
    fun markDirty(snapshot: Map<EntityId, EntityState>) {
        latest = snapshot
        dirtyTicks.tryEmit(Unit)
    }

    /** Synchronous load — small file, called once from start() before connect.
     *  Returns an empty map on any failure (missing file, schema mismatch,
     *  corrupt JSON) so a broken snapshot can never block app startup. */
    fun load(): Map<EntityId, EntityState> = runCatching {
        val f = file
        if (!f.exists() || f.length() == 0L) return@runCatching emptyMap()
        val raw = f.readText(Charsets.UTF_8)
        val snapshot = json.decodeFromString(Snapshot.serializer(), raw)
        if (snapshot.version != SCHEMA_VERSION) {
            R1Log.i(
                "EntityCache",
                "snapshot schema v${snapshot.version} != current v$SCHEMA_VERSION — discarding",
            )
            return@runCatching emptyMap()
        }
        snapshot.entries.mapNotNull { it.toEntityState() }
            .associateBy { it.id }
    }.onFailure {
        R1Log.w("EntityCache", "load failed: ${it.message}")
    }.getOrDefault(emptyMap())

    /** Drop the on-disk snapshot. Surfaced by the dev menu's clear-caches
     *  affordance if we ever wire one for this layer. */
    fun clear() {
        runCatching { if (file.exists()) file.delete() }
    }

    private fun writeBlocking(map: Map<EntityId, EntityState>) {
        runCatching {
            val snapshot = Snapshot(
                version = SCHEMA_VERSION,
                writtenAt = Instant.now().toString(),
                entries = map.values.map { PersistedEntity.fromEntityState(it) },
            )
            val raw = json.encodeToString(Snapshot.serializer(), snapshot)
            // Write to a temp file and rename so a crash mid-write can't
            // leave behind a half-written snapshot that fails to parse on
            // next launch.
            val tmp = File(appFilesDir, "$FILE_NAME.tmp")
            tmp.writeText(raw, Charsets.UTF_8)
            tmp.renameTo(file)
        }.onFailure {
            R1Log.w("EntityCache", "write failed: ${it.message}")
        }
    }

    @Serializable
    private data class Snapshot(
        val version: Int,
        val writtenAt: String,
        val entries: List<PersistedEntity>,
    )

    /**
     * Subset of [EntityState] that's actually persisted. Skipped fields
     * default to safe values on rehydrate — HA's first state push after
     * connect re-fills them. The deliberate omissions: attributesJson
     * (heavy, regenerated), supportedColorModes (re-fetched), climate
     * setpoint ranges (re-fetched), step / minRaw / maxRaw (re-fetched),
     * raw Number (Number is abstract, can't serialize cleanly; the
     * derived percent + rawState is enough for paint).
     */
    @Serializable
    private data class PersistedEntity(
        val id: String,
        val friendlyName: String,
        val area: String? = null,
        val isOn: Boolean = false,
        val percent: Int? = null,
        val lastChangedEpochMs: Long = 0L,
        val isAvailable: Boolean = true,
        val supportsScalar: Boolean = true,
        val rawState: String? = null,
        val unit: String? = null,
        val deviceClass: String? = null,
        val effectList: List<String> = emptyList(),
        val effect: String? = null,
        val selectOptions: List<String> = emptyList(),
        val currentOption: String? = null,
        val mediaTitle: String? = null,
        val mediaArtist: String? = null,
        val mediaAlbumName: String? = null,
        val mediaDuration: Int? = null,
        val mediaPosition: Int? = null,
        val mediaPositionUpdatedAtEpochMs: Long? = null,
        val mediaPicture: String? = null,
        val isVolumeMuted: Boolean = false,
        val mediaSupportedFeatures: Int = 0,
    ) {
        fun toEntityState(): EntityState? {
            val eid = runCatching { EntityId(id) }.getOrNull() ?: return null
            return EntityState(
                id = eid,
                friendlyName = friendlyName,
                area = area,
                isOn = isOn,
                percent = percent,
                raw = null,
                lastChanged = Instant.ofEpochMilli(lastChangedEpochMs),
                isAvailable = isAvailable,
                supportsScalar = supportsScalar,
                rawState = rawState,
                unit = unit,
                deviceClass = deviceClass,
                effectList = effectList,
                effect = effect,
                selectOptions = selectOptions,
                currentOption = currentOption,
                mediaTitle = mediaTitle,
                mediaArtist = mediaArtist,
                mediaAlbumName = mediaAlbumName,
                mediaDuration = mediaDuration,
                mediaPosition = mediaPosition,
                mediaPositionUpdatedAt = mediaPositionUpdatedAtEpochMs?.let { Instant.ofEpochMilli(it) },
                mediaPicture = mediaPicture,
                isVolumeMuted = isVolumeMuted,
                mediaSupportedFeatures = mediaSupportedFeatures,
            )
        }

        companion object {
            fun fromEntityState(e: EntityState): PersistedEntity = PersistedEntity(
                id = e.id.value,
                friendlyName = e.friendlyName,
                area = e.area,
                isOn = e.isOn,
                percent = e.percent,
                lastChangedEpochMs = e.lastChanged.toEpochMilli(),
                isAvailable = e.isAvailable,
                supportsScalar = e.supportsScalar,
                rawState = e.rawState,
                unit = e.unit,
                deviceClass = e.deviceClass,
                effectList = e.effectList,
                effect = e.effect,
                selectOptions = e.selectOptions,
                currentOption = e.currentOption,
                mediaTitle = e.mediaTitle,
                mediaArtist = e.mediaArtist,
                mediaAlbumName = e.mediaAlbumName,
                mediaDuration = e.mediaDuration,
                mediaPosition = e.mediaPosition,
                mediaPositionUpdatedAtEpochMs = e.mediaPositionUpdatedAt?.toEpochMilli(),
                mediaPicture = e.mediaPicture,
                isVolumeMuted = e.isVolumeMuted,
                mediaSupportedFeatures = e.mediaSupportedFeatures,
            )
        }
    }

    companion object {
        private const val FILE_NAME = "entity_cache.json"
        /** Bump when changing the [PersistedEntity] shape in a non-additive
         *  way (renaming a field, changing semantics). Older snapshots are
         *  silently discarded on read. */
        const val SCHEMA_VERSION = 1
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false  // smaller files when most fields are defaults
        }

        /** Convenience: build a persister rooted in the app's files dir. */
        fun forContext(context: Context, scope: CoroutineScope): EntityStateCachePersister =
            EntityStateCachePersister(
                appFilesDir = context.filesDir,
                scope = scope,
            )

    }
}
