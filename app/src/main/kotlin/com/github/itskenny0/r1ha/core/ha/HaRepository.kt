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
     * GET `/api/lovelace/config` — parses the user's Lovelace dashboard
     * into a flat list of [LovelaceViewInfo] (one per tab/view). Each entry
     * contains the view title and the ordered list of entity IDs extracted
     * from standard card types (entities, entity, glance, button, grid,
     * vertical-stack, horizontal-stack). Custom cards with no parseable
     * entity are skipped; views that contain at least one custom card but
     * no entity IDs set [LovelaceViewInfo.hasRemoteCard] = true so the
     * Wear OS screen can show a dedicated "Remote Control" shortcut there.
     */
    suspend fun fetchLovelaceViews(): Result<List<LovelaceViewInfo>>

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
}
