package com.github.itskenny0.r1ha.core.ha

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface HaRepository {
    val connection: StateFlow<ConnectionState>
    /** Hot map of currently-known entity states for the subscribed set. */
    fun observe(entities: Set<EntityId>): Flow<Map<EntityId, EntityState>>
    /**
     * Fires once per service call the repository couldn't deliver — timeout, WS dropped,
     * HA returned an error, etc. The ViewModel watches this so it can roll back its
     * optimistic UI override; the repository already surfaces a user-visible toast.
     */
    val callFailures: SharedFlow<EntityId>

    /**
     * Wall-clock millis when the next reconnect attempt is scheduled to fire, or null
     * if no backoff is pending (we're either connected or actively connecting). UI
     * reads this to show "Reconnecting in Xs…" countdown text on the stalled-loading
     * empty state, which is much friendlier than an indefinite spinner during a long
     * backoff window.
     */
    val reconnectNextAttemptAtMillis: StateFlow<Long?>

    /**
     * Wall-clock millis when the last useful HA signal arrived — either a state_changed
     * event applied from the WebSocket or a successful REST fallback poll. 0 means
     * "nothing yet this session". UI consumers (currently AboutScreen → CONNECTION
     * diagnostic) read this to surface "WS dropped 47 s ago" when a reverse-proxy
     * misconfiguration silently breaks the WS event stream — the connection-state dot
     * stays green so the user has no other signal that something is wrong.
     */
    val lastEventAtMillis: StateFlow<Long>
    /** Fire a service call. Coalesces back-to-back calls per entity via internal debounce. */
    suspend fun call(call: ServiceCall): Result<Unit>
    /** One-shot REST GET /api/states equivalent, used by FavoritesPicker. */
    suspend fun listAllEntities(): Result<List<EntityState>>

    /**
     * Diagnostic — issue the same GET /api/states call as [listAllEntities] but
     * group the **raw** response by entity_id prefix without applying our supported-
     * domain filter or per-row decoder. Lets the user verify whether HA even sent
     * media_player.* (or any other domain) for their token. Used by About →
     * Entities → 'PROBE RAW' so 'where are my entities?' becomes self-service.
     */
    suspend fun listAllEntitiesRawPrefixCounts(): Result<Map<String, Int>>

    /**
     * History fetch — `GET /api/history/period/<since-iso>?filter_entity_id=<id>`. Returns
     * the timestamped state changes for [entityId] going back [hours] hours from now,
     * in chronological order. Used by SensorCard to render a line chart for numeric
     * sensors and a recent-changes list for text/categorical sensors.
     */
    suspend fun fetchHistory(entityId: EntityId, hours: Int = 24): Result<List<HistoryPoint>>

    /**
     * HA's conversation/process endpoint — sends a natural-language [text]
     * prompt and returns the plain-text response. Powers the Assist text
     * surface. [conversationId] threads multi-turn context; null starts a
     * fresh conversation.
     */
    suspend fun conversationProcess(
        text: String,
        language: String? = null,
        conversationId: String? = null,
        /**
         * Conversation agent ID — e.g. `"homeassistant"`, `"conversation.openai_conversation"`,
         * or a pipeline UUID. Null = HA picks the default agent (Assist's normal behaviour);
         * non-null routes the request to a specific agent so users with multiple LLM
         * back-ends configured (OpenAI + Local + Google) can pick which one answers.
         */
        agentId: String? = null,
    ): Result<ConversationResponse>

    /**
     * Fetch the HA logbook — `GET /api/logbook/<since-iso>?end_time=<now>`.
     * Returns a chronological list of recent state changes, automation
     * triggers, scene activations, etc. Used by the Recent Activity
     * surface as a "what just happened?" feed. [hours] defaults to 12 —
     * a balance between catching the morning's automations from an
     * evening glance and not slurping an enormous payload on big HA
     * installs.
     */
    suspend fun fetchLogbook(hours: Int = 12): Result<List<LogbookEntry>>

    /**
     * Render a Jinja2 template against the live HA state — POSTs to
     * `/api/template` with `{template: "..."}` and returns the
     * resulting plain-text string. Powers the Templates power-user
     * surface; an HA install ships a template editor in its frontend
     * and this brings the same loop (type → render → iterate) onto
     * the R1 for users who don't have a laptop nearby.
     */
    suspend fun renderTemplate(template: String): Result<String>

    /**
     * Fire an arbitrary HA service by domain + service name — POSTs to
     * `/api/services/<domain>/<service>` with the given JSON [data]
     * body. Distinct from [call] because [call] dispatches via the
     * WebSocket call_service path and requires an [EntityId] target,
     * whereas many services don't need one (homeassistant.restart,
     * automation.reload, persistent_notification.create). Powers the
     * Service Caller power-user surface.
     */
    suspend fun callRawService(
        domain: String,
        service: String,
        data: kotlinx.serialization.json.JsonObject,
    ): Result<String>

    /**
     * Fire an arbitrary HA event by [eventType] — POSTs to
     * `/api/events/<event_type>` with the given JSON [data] as the event
     * payload. Used by the dev menu's fire-event tile for power users who
     * need to trigger automations that listen for custom events.
     * HA returns `{"message": "Event <type> fired."}` on success.
     */
    suspend fun fireEvent(
        eventType: String,
        data: kotlinx.serialization.json.JsonObject,
    ): Result<String>

    /**
     * List current HA persistent notifications. Goes through raw
     * `/api/states` rather than the [listAllEntities] decoder because
     * the `persistent_notification.*` domain isn't in our [Domain] enum
     * (and putting it there would cascade through exhaustive when-
     * branches). Filters server-side on the JSON.
     */
    suspend fun listPersistentNotifications(): Result<List<PersistentNotification>>

    /**
     * Dismiss a single persistent notification — fires
     * `persistent_notification.dismiss` with `{notification_id: ...}`.
     * The [id] is the bit after `persistent_notification.` (not the
     * full entity_id).
     */
    suspend fun dismissPersistentNotification(id: String): Result<Unit>

    /**
     * Lightweight raw entity row for surfaces that need entities outside
     * our supported [Domain] enum — cameras, persons, weather, calendars,
     * etc. Returns one entry per HA-reported entity, with the raw state
     * string and the full attributes JsonObject so the caller can dig into
     * domain-specific fields without bloating [EntityState]. Filters
     * client-side by [domainPrefix] (e.g. "camera"). */
    suspend fun listRawEntitiesByDomain(domainPrefix: String): Result<List<RawEntityRow>>

    /**
     * GET `/api/config` — HA's server metadata (version, location name,
     * timezone, components list, unit system, internal/external URLs).
     * Powers the System Health diagnostic screen.
     */
    suspend fun fetchHaConfig(): Result<HaConfig>

    /**
     * GET `/api/error_log` — HA's recent log output. Plain text body, up
     * to a few hundred KB depending on log level. We deliberately cap
     * the returned size client-side rather than streaming because the
     * R1's renderer wants the whole thing in memory anyway.
     */
    suspend fun fetchErrorLog(): Result<String>

    /**
     * GET `/api/calendars/<entity_id>?start=<iso>&end=<iso>` — events
     * for a single calendar in a given window. Used by the calendar
     * drill-down screen to show "what else is on the agenda this week".
     */
    suspend fun fetchCalendarEvents(
        entityId: String,
        fromDaysBack: Int = 0,
        toDaysAhead: Int = 14,
    ): Result<List<CalendarEvent>>

    /**
     * GET `/api/services` — every service HA exposes, grouped by
     * domain. Used by the Services Browser power-user surface.
     */
    suspend fun listServices(): Result<List<HaServiceDomain>>

    /**
     * List every `todo.*` entity the server exposes. Used by the To-do
     * screen to populate its list-picker. Backs onto [listRawEntitiesByDomain]
     * so we don't have to model todos in the [Domain] enum (the dashboard
     * card stack doesn't show them; they live on their own screen).
     */
    suspend fun listTodoEntities(): Result<List<ToDoList>>

    /**
     * Fetch the items inside a single todo entity via the
     * `/api/services/todo/get_items?return_response=true` REST endpoint.
     * HA returns the items as part of the service-call response body
     * since 2024.1.
     */
    suspend fun fetchTodoItems(entityId: String): Result<List<ToDoItem>>

    /** Append a new item to the named todo list. */
    suspend fun addTodoItem(entityId: String, summary: String): Result<Unit>

    /**
     * Flip an item's completed status. Targets by HA's stable `uid` so
     * lists with duplicate summaries (legitimate on shopping lists where
     * "Apples" can appear twice) still route the call to the right row.
     */
    suspend fun updateTodoItem(
        entityId: String,
        uid: String,
        completed: Boolean,
    ): Result<Unit>

    /** Remove an item by uid. Same duplicate-summary rationale as the
     *  update path. */
    suspend fun removeTodoItem(entityId: String, uid: String): Result<Unit>

    /** Bulk-delete every completed item from the named list. */
    suspend fun clearCompletedTodoItems(entityId: String): Result<Unit>

    /**
     * List the current repairs / issues HA's `repairs` integration knows about.
     * Unlike persistent_notifications, repairs are NOT exposed via REST — they
     * only flow over WS via the `repairs/list_issues` command. The repository
     * routes that command through the active WS connection and decodes the
     * `issues` array in the reply. Returns failure (without UI noise) when the
     * WS is disconnected: callers should fall back to a "(server offline)"
     * placeholder rather than treating that as a hard error.
     */
    suspend fun listRepairs(): Result<List<RepairIssue>>

    /**
     * Ignore (skip) a single repair issue. Same WS-only constraint as
     * [listRepairs] — fires `repairs/ignore { issue_id, domain, ignore: true }`.
     * The server hides ignored issues from future list responses until the
     * issue is re-raised. No-op (success with no effect) when the WS is
     * disconnected.
     */
    suspend fun ignoreRepair(domain: String, issueId: String, ignore: Boolean = true): Result<Unit>

    /**
     * Browse the media library exposed by [entityId] one level at a time.
     * Pass null for [mediaContentId] / [mediaContentType] to get the root
     * (player's top-level sources); pass values from a previous [MediaBrowseEntry]
     * (whose `canExpand == true`) to drill into a folder.
     *
     * Same WS-only constraint as the repairs surface: HA's media_player.browse_media
     * is exposed via the WS command, not REST. Failure to find the entity, network
     * issues, or unsupported integration responses all surface as Result.failure.
     */
    suspend fun browseMedia(
        entityId: String,
        mediaContentId: String? = null,
        mediaContentType: String? = null,
    ): Result<MediaBrowseResult>

    /**
     * List the backups HA's backup integration knows about. HA Core 2024.4+ exposes
     * this via the `backup/info` WS command; older HA installs return failure
     * (caller surfaces "(no backups visible)" rather than treating that as a hard
     * error since restore APIs differ between Supervisor and Core).
     */
    suspend fun listBackups(): Result<List<BackupInfo>>

    /**
     * List every area HA knows about via `config/area_registry/list`. Used to
     * populate the area picker on the entity-configuration sheet, since HA's
     * area registry is the source of truth for "kitchen / bedroom / garage".
     */
    suspend fun listAreas(): Result<List<AreaInfo>>

    /**
     * Create a fresh area via `config/area_registry/create`. Returns the
     * server-assigned area_id so the caller can immediately assign an entity
     * to the new area without a second round-trip to refresh the list.
     */
    suspend fun createArea(name: String): Result<AreaInfo>

    /**
     * Update one entity's registry entry — rename it (the `name` field
     * overrides the integration-supplied `original_name`) and/or assign it
     * to an area. Pass `null` for either field to leave it untouched;
     * pass an empty string for [name] to clear the override (HA reverts to
     * the original name) or for [areaId] to remove the area assignment.
     *
     * Fires `config/entity_registry/update` over the WebSocket.
     */
    suspend fun updateEntityRegistry(
        entityId: String,
        name: String? = null,
        areaId: String? = null,
    ): Result<Unit>

    /** Handle for a live template subscription. [cancel] tears down both the
     *  server-side subscription and the local collector. Safe to call twice. */
    interface TemplateSubscription {
        suspend fun cancel()
    }

    /** Handle for a live event subscription. Same contract as [TemplateSubscription]. */
    interface EventSubscription {
        suspend fun cancel()
    }

    /**
     * Handle for an in-flight assist pipeline run. The voice satellite uses
     * this to push PCM audio frames at HA over the same WebSocket the events
     * arrive on. [sendAudio] prepends [com.github.itskenny0.r1ha.core.voice.VoiceSatelliteEngine]'s
     * STT binary handler byte (provided by HA in the `run-start` event); the
     * caller is responsible for finding and supplying it before driving audio.
     *
     * [cancel] tears down the server-side subscription. The pipeline will
     * also end naturally once [finishAudio] is called and HA delivers the
     * `run-end` event.
     */
    interface PipelineRun {
        /** Stream one PCM 16-bit, 16 kHz, mono audio chunk to HA. Returns
         *  false if the WS isn't connected. [handlerByte] is the byte HA
         *  asked us to prefix audio frames with in the `run-start` event. */
        fun sendAudio(handlerByte: Byte, pcm: ByteArray): Boolean
        /** Signal end-of-utterance by sending a single-byte frame containing
         *  just the handler byte. HA will run STT to completion and continue
         *  the pipeline through intent → TTS. */
        fun finishAudio(handlerByte: Byte): Boolean
        suspend fun cancel()
    }

    /**
     * Open an assist pipeline run over the WebSocket. HA pipes events back
     * (run-start → stt-start → stt-end → intent-start → intent-end →
     * tts-start → tts-end → run-end) on the returned [PipelineRun]'s
     * subscription. Audio is pushed via [PipelineRun.sendAudio] using the
     * handler byte HA hands back in the `run-start` event's
     * `data.runner_data.stt_binary_handler_id`.
     *
     * [pipelineId] selects a specific configured pipeline; null = the
     * default. [conversationId] threads multi-turn context; null = start a
     * fresh conversation.
     */
    suspend fun startAssistPipeline(
        pipelineId: String?,
        conversationId: String?,
        onEvent: (kotlinx.serialization.json.JsonObject) -> Unit,
    ): Result<PipelineRun>

    /**
     * Subscribe to HA's event bus for events of [eventType]. Each event is delivered
     * to [onEvent] with the full event payload (entity_id, data, time_fired, etc.).
     *
     * Common useful types: "state_changed" (every entity state change), "logbook_entry"
     * (HA's logbook stream), "homeassistant_start" / "homeassistant_stop". Pass null to
     * subscribe to ALL events; volume is large so prefer a specific type when known.
     *
     * Uses the same inboundRawText + sendRawText machinery as [subscribeTemplate]; the
     * returned handle's [cancel] sends an unsubscribe_events frame server-side.
     */
    suspend fun subscribeEvents(
        eventType: String?,
        onEvent: (kotlinx.serialization.json.JsonObject) -> Unit,
    ): Result<EventSubscription>

    /**
     * Subscribe to live re-renders of [template] via HA's `render_template` WS
     * command. The repository handles the subscription lifecycle: returns a
     * [TemplateSubscription] handle whose `cancel()` unsubscribes server-side
     * and stops the local collector. [onResult] is invoked on the repository's
     * IO scope each time HA emits a fresh render; the first invocation lands
     * within ~50 ms of subscribing.
     *
     * Failures in HA's evaluation (Jinja syntax error, undefined entity) come
     * through the same callback with the error message as the result; the
     * caller decides whether to surface them.
     */
    suspend fun subscribeTemplate(
        template: String,
        onResult: (String) -> Unit,
    ): Result<TemplateSubscription>

    /**
     * Fetch the per-user JSON blob stored under [key] via HA's
     * `frontend/get_user_data` WS command. Returns the JSON value
     * (any shape, opaque to HA) or null when nothing has been
     * written under that key for the current user. This is the same
     * storage HA's own frontend uses for dashboard preferences, so
     * the format is stable across HA versions.
     */
    suspend fun getUserData(key: String): Result<kotlinx.serialization.json.JsonElement?>

    /**
     * Persist [value] under [key] via HA's `frontend/set_user_data` WS
     * command. Per-user storage; another HA user signed into the same
     * server can't read or overwrite it. The whole [value] is replaced
     * on each call (HA doesn't merge — this is a simple put).
     */
    suspend fun setUserData(key: String, value: kotlinx.serialization.json.JsonElement): Result<Unit>

    /**
     * List every device HA's device registry knows about via
     * `config/device_registry/list`. Powers the native Devices browser
     * which sections devices by area / manufacturer and drills into the
     * entity list for each device. Read-only: editing a device's name
     * / area / disabled state is left to HA's web UI (the WS protocol
     * supports it but each of those flows wants its own confirm UX).
     */
    suspend fun listDevices(): Result<List<DeviceInfo>>

    /**
     * List every entry in HA's entity registry via
     * `config/entity_registry/list`. The Devices browser pulls this
     * once and filters client-side by `device_id` for each drill-in
     * (one round trip vs one-per-device when drilling in repeatedly).
     */
    suspend fun listEntityRegistry(): Result<List<EntityRegistryEntry>>

    /**
     * List every configured integration instance via `config_entries/get`.
     * Powers the native Integrations browser which groups by domain and
     * surfaces a reload affordance per row. Setup / removal flows live
     * in HA's web UI; this surface is read + reload.
     */
    suspend fun listConfigEntries(): Result<List<ConfigEntry>>

    /**
     * Reload a single config entry via `config_entries/reload`. The
     * integration is unloaded and re-set up in place; the call returns
     * once the new setup completes (or fails). Surfaces the same toast
     * as any other action on failure.
     */
    suspend fun reloadConfigEntry(entryId: String): Result<Unit>

    suspend fun start()
    suspend fun stop()

    /**
     * Cancel any pending reconnect-backoff and attempt a connection immediately. No-op if the
     * connection is already Connecting / Authenticating / Connected — in those states the
     * existing attempt is the right one to ride out. Used by the stalled-loading affordance
     * so the user has a one-tap recovery path that doesn't require waiting out the backoff
     * (which can be 30+ seconds on the 20th consecutive failure).
     */
    fun reconnectNow()

    /**
     * Full /api/error_log fetch, capped client-side at [maxBytes]. Same streaming
     * tail mechanic as [fetchErrorLog] but with a larger ceiling so the native
     * Logs viewer can show meaningfully more than the 32 KB tail the System
     * Health screen renders. Returns the tail and a flag indicating whether the
     * server's full body exceeded [maxBytes] (so the UI can render a "truncated
     * to last N bytes" hint).
     */
    suspend fun fetchErrorLogFull(maxBytes: Int = 512 * 1024): Result<ErrorLogTail>

    /**
     * List every HA user via the `config/auth/list` WS command. Admin-only —
     * non-admin tokens receive a permission_denied / auth_error reply which we
     * surface as a Result.failure carrying a friendly message; the Users screen
     * shows a "needs admin" empty state rather than a stack trace.
     */
    suspend fun listAuthUsers(): Result<List<HaUser>>

    /**
     * List every NFC / QR tag the registry knows about via the `tag/list`
     * WS command. HA returns id, name, description, and last-scanned ISO
     * timestamp; we surface those fields verbatim.
     */
    suspend fun listTags(): Result<List<HaTag>>

    /**
     * Update a tag's friendly name + description via `tag/update`. Pass null
     * for either field to leave it untouched server-side. Tag id can't be
     * changed (it's the value the NFC tag actually broadcasts).
     */
    suspend fun updateTag(
        tagId: String,
        name: String? = null,
        description: String? = null,
    ): Result<Unit>

    /** Delete a tag via `tag/delete`. The id is gone from the registry but
     *  the physical tag still broadcasts its value; a future scan re-registers
     *  it with a blank name. */
    suspend fun deleteTag(tagId: String): Result<Unit>
}

/**
 * Plain-text tail of HA's /api/error_log along with a flag indicating
 * whether the server's full body was longer than the requested cap.
 * Memory-bounded: even on a multi-megabyte log only [body] bytes ever
 * land in memory.
 */
data class ErrorLogTail(
    val body: String,
    val truncated: Boolean,
    val totalBytes: Long,
)
