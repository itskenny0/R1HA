package com.github.itskenny0.r1ha.core.ha

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface HaRepository {
    val connection: StateFlow<ConnectionState>

    /**
     * The logged-in user's id, fetched via `auth/current_user` on each connect
     * and cached for the session (cleared to null on disconnect / server change).
     * Null when not yet fetched, the server doesn't support the command, or the
     * fetch failed. Consumed by the Lovelace `user` / `location` conditions and
     * the action confirmation-exemption check, which all fail closed for a null
     * id exactly as HA does for an unknown user.
     */
    val currentUserId: StateFlow<String?>

    /**
     * The logged-in user's display name, cached from the same `auth/current_user`
     * fetch that populates [currentUserId]. Null when not yet fetched / failed.
     * The markdown card forwards it as the `user` template variable, matching HA's
     * frontend (`variables: { user: hass.user.name }`).
     */
    val currentUserName: StateFlow<String?>

    /**
     * One-shot fetch of the logged-in user via the `auth/current_user` WS
     * command. Returns the [HaCurrentUser], or null when the server doesn't
     * recognise the command (degrades silently). A transport failure is a
     * [Result.failure]; callers that only need best-effort identity can treat
     * both null and failure as "unknown user".
     */
    suspend fun fetchCurrentUser(): Result<HaCurrentUser?>
    /** Hot map of currently-known entity states for the subscribed set. */
    fun observe(entities: Set<EntityId>): Flow<Map<EntityId, EntityState>>

    /**
     * Domain-agnostic observe for the dashboards renderer. Takes RAW
     * `domain.object_id` strings (so an entity whose domain isn't in the
     * [Domain] enum is still requested) and, as a side effect, registers them
     * for WS subscription + REST seeding alongside the user's favourites so a
     * dashboard card shows live state for an entity the user never pinned.
     *
     * Emits a map keyed by the raw id string. An entity whose domain IS
     * supported appears with its full [EntityState]; an entity whose domain
     * isn't modelled is currently omitted (the typed cache can't hold it),
     * which the renderer treats the same as "not yet loaded".
     */
    fun observeRaw(entityIds: Set<String>): Flow<Map<String, EntityState>>

    /**
     * Domain-agnostic observe that, unlike [observeRaw], NEVER drops an entity for
     * having an unmodelled domain. Backed by a raw last-known-state cache keyed by
     * the raw `domain.object_id` string and populated from the SAME WS
     * `subscribe_trigger` / `state_changed` stream and REST `/api/states` seed, so
     * a dashboard card for `sun.sun`, a custom integration sensor, or a
     * `device_tracker.*` shows its current value instead of a blank box.
     *
     * Registers [entityIds] for WS subscription + REST seeding as a side effect,
     * exactly like [observeRaw] (both write the same dashboard-id set). Emits a map
     * keyed by the raw id string; ids HA hasn't reported yet are simply absent,
     * which the renderer treats as "not yet loaded".
     */
    fun observeRawRows(entityIds: Set<String>): Flow<Map<String, RawEntityRow>>
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
    /** One-shot REST GET /api/states equivalent, used by FavoritesPicker. Filtered to the
     *  domains the app has a card archetype for. */
    suspend fun listAllEntities(): Result<List<EntityState>>

    /** Like [listAllEntities] but also includes entities from domains with no card archetype
     *  (device_tracker, zone, calendar, ...) as read-only [Domain.OTHER] records. Used by
     *  Universal Search so every entity the user owns is findable, not just the renderable ones. */
    suspend fun listAllEntitiesForSearch(): Result<List<EntityState>>

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
     * Per-entity logbook fetch — `GET /api/logbook/<since-iso>?entity=<id>`.
     * Same shape as [fetchLogbook] but scoped server-side to a single entity, so
     * the more-info sheet can embed an entity's recent activity without slurping
     * (and then filtering) the whole-install logbook. [hours] defaults to 24 to
     * match the sheet's "last day" framing.
     */
    suspend fun fetchLogbookForEntity(
        entityId: String,
        hours: Int = 24,
    ): Result<List<LogbookEntry>>

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
     * Fetch a weather entity's forecast via the response-only
     * `weather.get_forecasts` service. Modern HA integrations (2024.x+)
     * dropped the legacy `forecast` state attribute and expose forecasts
     * only through this service, which HA rejects with HTTP 400 unless the
     * REST call carries `?return_response=true` and reads the data back from
     * the response body rather than the produced state changes.
     *
     * [type] is `"hourly"` or `"daily"` (HA also accepts `"twice_daily"`).
     * Returns the per-entity service-response object verbatim, shaped
     * `{ "forecast": [ ... ] }`, so callers can run it through their existing
     * forecast-entry parser. Errors (HTTP 400 on integrations that don't
     * support the requested forecast type, 401, transport failures) come back
     * as a failed [Result] so the caller can fall back to the legacy attribute.
     */
    suspend fun getWeatherForecasts(
        entityId: String,
        type: String,
    ): Result<kotlinx.serialization.json.JsonElement>

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
     * List every floor HA knows about via `config/floor_registry/list`. Floors
     * group areas into levels (HA 2024.x). The areas / home strategies use this
     * to section their overview by floor; an older server that doesn't expose the
     * command (or has no floors) yields an empty list and the strategy degrades
     * to a single ungrouped "Areas" section.
     */
    suspend fun listFloors(): Result<List<FloorInfo>>

    /**
     * Ask HA's `usage_prediction/common_control` WS command for the entities the
     * user most commonly controls at this time of day. Returns the ordered entity
     * id list, or a failure when the `usage_prediction` integration isn't loaded
     * (the common-controls strategy then falls back to recently-changed
     * toggleables).
     */
    suspend fun predictCommonControls(): Result<List<String>>

    /**
     * Create a fresh area via `config/area_registry/create`. Returns the
     * server-assigned area_id so the caller can immediately assign an entity
     * to the new area without a second round-trip to refresh the list.
     */
    suspend fun createArea(name: String): Result<AreaInfo>

    /**
     * Rename an existing area via `config/area_registry/update`. Sends the
     * stable [areaId] plus the new [name]; callers refresh the area list on
     * success so the updated name shows everywhere it is referenced.
     */
    suspend fun renameArea(areaId: String, name: String): Result<Unit>

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
     * One configured Assist pipeline as reported by `assist_pipeline/pipeline/list`.
     * [sttEngine] is null when the pipeline has no speech-to-text engine wired up;
     * such a pipeline can't service a Voice Satellite run (HA rejects it with
     * "the pipeline does not support speech-to-text"), so the picker uses this to
     * flag / filter STT-capable pipelines.
     */
    data class AssistPipelineInfo(
        val id: String,
        val name: String,
        val sttEngine: String?,
    )

    /**
     * The full pipeline list plus HA's preferred (default) pipeline id, so the UI
     * can mark which entry HA would otherwise pick. [preferredId] may be null on
     * installs that don't report one.
     */
    data class AssistPipelines(
        val pipelines: List<AssistPipelineInfo>,
        val preferredId: String?,
    )

    /**
     * Enumerate the configured Assist pipelines (`assist_pipeline/pipeline/list`).
     * Lets the Voice Satellite surface a picker so the user can choose a
     * STT-capable pipeline rather than always running HA's default.
     */
    suspend fun listAssistPipelines(): Result<AssistPipelines>

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

    /** A `render_template` event, either a rendered string or an error with
     *  HA's severity level. Used by the detailed subscription so a card can map
     *  an error to a styled callout instead of treating it as a render. */
    sealed interface TemplateRender {
        data class Ok(val result: String) : TemplateRender
        data class Error(val message: String, val level: String) : TemplateRender
    }

    /**
     * Subscribe to live re-renders of [template] with the full parameter set HA's
     * markdown card sends: [variables] (the `{config, user}` map a template can
     * read), [entityIds] to scope which entities re-trigger the render, [strict]
     * to fail on undefined variables, and [reportErrors] so HA pushes Jinja errors
     * through the event channel (with a severity level) rather than tanking the
     * whole subscription. Each event arrives as a [TemplateRender] so the caller
     * can distinguish a rendered result from an error and pick a fallback.
     *
     * A null / empty [entityIds] omits the scope (HA derives listeners from the
     * template itself), and a null [variables] sends no variables block.
     */
    suspend fun subscribeTemplateDetailed(
        template: String,
        variables: kotlinx.serialization.json.JsonObject? = null,
        entityIds: List<String> = emptyList(),
        strict: Boolean = true,
        reportErrors: Boolean = true,
        onRender: (TemplateRender) -> Unit,
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
     * Fetch one entity's extended registry entry via `config/entity_registry/get`
     * and narrow it to the options the Lovelace card-features need (favorite
     * positions / colours, default code). Returns [ExtEntityRegistryOptions.EMPTY]
     * on older servers or when the entity has no registry entry, so callers fall
     * back to their built-in defaults rather than failing.
     */
    suspend fun getExtendedEntityRegistryOptions(entityId: String): Result<ExtEntityRegistryOptions>

    /**
     * Fetch one entity's registry `options` blob via `config/entity_registry/get`.
     * Returns the raw `options` object (HA nests per-domain favourite positions /
     * colours under `options[<domain>]`), or null when the entity has no registry
     * options. Used by the more-info sheet's favourites controls to read the
     * user's stored favourite positions / colours.
     */
    suspend fun getEntityRegistryOptions(
        entityId: String,
    ): Result<kotlinx.serialization.json.JsonObject?>

    /**
     * Write one domain's `options` block on an entity's registry entry via
     * `config/entity_registry/update` (the `options_domain` + `options` form HA's
     * own frontend uses to persist favourites). [optionsDomain] is the domain key
     * (`light` / `cover` / `valve`); [options] is the per-domain object to merge.
     * Returns failure when the WS call is rejected so the caller never reports a
     * fake success.
     */
    suspend fun updateEntityRegistryOptions(
        entityId: String,
        optionsDomain: String,
        options: kotlinx.serialization.json.JsonObject,
    ): Result<Unit>

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

    /**
     * List installed blueprints for [domain] via HA's
     * `blueprint/list/<domain>` WS command. HA returns a map of path →
     * `{metadata: {...}}`; the decoder flattens that into a [BlueprintInfo]
     * per entry. [domain] is "automation" or "script"; any other value is
     * a programmer error (HA only ships those two blueprint kinds today).
     *
     * Powers the native Blueprints browser. Read side of the surface:
     * pair with [importBlueprint] / [saveBlueprint] for the import flow.
     */
    suspend fun listBlueprints(domain: String): Result<List<BlueprintInfo>>

    /**
     * Import a blueprint from [url] via HA's `blueprint/import` WS command.
     * HA fetches + parses the YAML (raw URLs, GitHub permalinks, gist
     * shortlinks) and returns the metadata, the suggested install path,
     * the raw YAML body, and any validation errors. Returns a
     * [BlueprintInfo] with [BlueprintInfo.rawYaml] populated so the
     * preview sheet can render and the subsequent [saveBlueprint] can
     * write the same YAML HA validated.
     */
    suspend fun importBlueprint(url: String): Result<BlueprintInfo>

    /**
     * Install a previously-imported blueprint by writing its YAML to disk
     * via HA's `blueprint/save` WS command. [domain] selects which
     * `blueprints/<domain>/` directory HA writes under; [path] is the
     * relative filename (typically HA's `suggested_filename` from the
     * import preview, but the caller is free to override). [yaml] is the
     * verbatim body HA returned during import; [sourceUrl] is the URL the
     * user pasted, persisted so a future "where did this come from?"
     * surface can show it without parsing the YAML.
     */
    suspend fun saveBlueprint(
        domain: String,
        path: String,
        yaml: String,
        sourceUrl: String,
    ): Result<Unit>

    /**
     * List every statistic_id HA's recorder is collecting via
     * `recorder/list_statistic_ids`. The reply tells us, per series, which
     * aggregation columns (mean / sum) the recorder fills in: that hint
     * drives the Statistics screen's aggregation chip availability so we
     * don't offer SUM on a temperature sensor or MEAN on a kWh meter.
     */
    suspend fun listStatisticIds(): Result<List<StatisticId>>

    /**
     * Fetch long-term statistics buckets for [statisticIds] between [start]
     * and [end] at the requested [period] resolution
     * (`5minute` / `hour` / `day` / `week` / `month`). HA returns one bucket
     * list per requested id; ids the recorder doesn't know about are
     * omitted from the result map. Used by the Statistics screen.
     */
    suspend fun getStatisticsDuringPeriod(
        statisticIds: List<String>,
        start: java.time.Instant,
        end: java.time.Instant,
        period: String,
    ): Result<Map<String, List<StatisticsBucket>>>

    /**
     * Fetch the raw `lovelace/config` blob for the dashboard at [urlPath].
     * Pass null to load HA's default dashboard. Returns the raw JsonObject
     * so the parser can stay in the lovelace module without R1HA's
     * repository layer having to know the card schema.
     *
     * [forceRefresh] maps to HA's `lovelace/config` `force` flag: for a
     * YAML-mode dashboard it makes HA re-read the file from disk instead of
     * serving the cached parse. The manual RELOAD affordance sets it; the
     * default false serves HA's cached config (the common storage-mode path).
     */
    suspend fun fetchLovelaceConfig(
        urlPath: String? = null,
        forceRefresh: Boolean = false,
    ): Result<kotlinx.serialization.json.JsonObject>

    /**
     * List the user-visible dashboards exposed by `lovelace/dashboards/list`.
     * The default dashboard is always available even when the list is
     * empty; callers compose it in locally. Each entry's [url_path] is the
     * value to pass to [fetchLovelaceConfig] for that dashboard. */
    suspend fun listLovelaceDashboards(): Result<kotlinx.serialization.json.JsonArray>

    /**
     * Fetch the Energy dashboard user preferences via `energy/get_prefs`.
     * Returns a map of stat/entity id to the user-configured custom display
     * name for that source or device. An absent or blank name entry is
     * omitted from the map so callers can use `map[id] ?: fallbackName`
     * safely.
     *
     * Best-effort: a transport error, a disconnected WS, or an HA install
     * that hasn't set any custom names all result in an empty map (no
     * visible error surfaced to the user). The caller should treat this
     * map as an optional overlay, never a required fetch.
     *
     * UNVERIFIED OFFLINE: the `energy/get_prefs` WS command and the
     * `device_consumption[].name` field shape have not been tested against
     * a live Home Assistant instance. The implementation follows HA's
     * documented energy websocket API.
     */
    suspend fun getEnergyPrefs(): Result<Map<String, String>>
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
