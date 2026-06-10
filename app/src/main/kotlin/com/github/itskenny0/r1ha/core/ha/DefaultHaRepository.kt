package com.github.itskenny0.r1ha.core.ha

import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.feature.energy.parseEnergyPrefsJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** Read a JSON attribute as a plain String, regardless of whether HA encoded it as a JSON string or number. */
private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.content
/** Read a JSON attribute as Int. Works for both JsonPrimitive(123) and JsonPrimitive("123"). */
private fun JsonElement?.asInt(): Int? = (this as? JsonPrimitive)?.content?.toIntOrNull()
/** Read a JSON attribute as Double. Works for both JsonPrimitive(0.42) and JsonPrimitive("0.42"). */
private fun JsonElement?.asDouble(): Double? = (this as? JsonPrimitive)?.content?.toDoubleOrNull()
/**
 * Read a JSON attribute as Boolean. HA can encode the same logical field as a JSON boolean
 * (`true` / `false`), the same word as a string (`"true"`), or as 0/1; accept all three so
 * a single integration switching its emitter never silently flips the value.
 */
private fun JsonElement?.asBoolean(): Boolean? {
    val raw = (this as? JsonPrimitive)?.content ?: return null
    return when (raw.lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> null
    }
}

/**
 * Percent computation that needs the entity *state* in addition to attributes —
 * NUMBER and INPUT_NUMBER carry their value in `state`, not in an attribute. Calls
 * out to [computePercent] for everything else.
 */
private fun computePercentWithState(
    domain: Domain,
    attrs: kotlinx.serialization.json.JsonObject,
    stateStr: String,
): Int? = when (domain) {
    Domain.NUMBER, Domain.INPUT_NUMBER -> {
        val v = stateStr.toDoubleOrNull()
        val mn = attrs["min"].asDouble() ?: 0.0
        val mx = attrs["max"].asDouble() ?: 100.0
        if (v != null && mx > mn) {
            (((v - mn) / (mx - mn)) * 100.0).roundToInt().coerceIn(0, 100)
        } else null
    }
    else -> computePercent(domain, attrs)
}

private fun computePercent(domain: Domain, attrs: kotlinx.serialization.json.JsonObject): Int? = when (domain) {
    Domain.LIGHT -> attrs["brightness"].asInt()?.let(EntityState::normaliseLightBrightness)
    Domain.FAN -> attrs["percentage"].asInt()?.let(EntityState::normaliseFanPercentage)
    Domain.COVER -> attrs["current_position"].asInt()?.let(EntityState::normaliseCoverPosition)
    Domain.MEDIA_PLAYER -> attrs["volume_level"].asDouble()?.let(EntityState::normaliseMediaVolume)
    Domain.HUMIDIFIER -> attrs["humidity"].asInt()?.coerceIn(0, 100)
    // Climate: scale target_temperature into 0..100 via min_temp/max_temp so the wheel's
    // percent abstraction maps naturally to "low end is cold, high end is hot". Falls
    // back to null (and the card stays on the switch-only path) when the range attrs
    // are missing on a particular HA install.
    Domain.CLIMATE, Domain.WATER_HEATER -> {
        val target = climateTargetTemp(attrs)
        val min = attrs["min_temp"].asDouble()
        val max = attrs["max_temp"].asDouble()
        if (target != null && min != null && max != null && max > min) {
            (((target - min) / (max - min)) * 100.0).roundToInt().coerceIn(0, 100)
        } else null
    }
    // Valve: same shape as cover — `current_position` 0..100 (closed..open).
    Domain.VALVE -> attrs["current_position"].asInt()?.coerceIn(0, 100)
    // Vacuums: percent abstraction doesn't apply (states are categorical).
    Domain.VACUUM, Domain.LAWN_MOWER -> null
    // Number / input_number: state is the value. We don't have access to row.state
    // here (computePercent takes only attrs), but we can read the entity's range
    // from attributes; the actual conversion uses minRaw/maxRaw at the VM layer
    // when sending the service call. For DISPLAY of percent, the caller threads
    // the current value through differently — see [EntityState.percent].
    Domain.NUMBER, Domain.INPUT_NUMBER -> null
    // No scalar — pure on/off / read-only / action.
    Domain.SWITCH, Domain.INPUT_BOOLEAN, Domain.AUTOMATION, Domain.LOCK,
    Domain.SCENE, Domain.SCRIPT, Domain.BUTTON, Domain.INPUT_BUTTON,
    Domain.SENSOR, Domain.BINARY_SENSOR,
    Domain.SELECT, Domain.INPUT_SELECT,
    // Helper-only domains rendered exclusively on the Helpers
    // screen; the card stack doesn't try to compute a percent
    // for these. Counter / timer have integer / time values that
    // don't map to a 0..100 percent; input_text / input_datetime
    // are text-shaped.
    Domain.COUNTER, Domain.TIMER, Domain.INPUT_TEXT, Domain.INPUT_DATETIME,
    // New text / date / datetime / time / image / event domains: all read-only,
    // no 0..100 wheel scalar.
    Domain.TEXT, Domain.DATE, Domain.DATETIME, Domain.TIME, Domain.IMAGE, Domain.EVENT,
    // Siren: on/off; no positional scalar.
    Domain.SIREN,
    // Update entities expose `update_percentage` for install progress but
    // that's surfaced on the dedicated Updates screen — not a scalar
    // brightness/volume-style percent, so we leave the card-stack
    // percent null.
    Domain.UPDATE,
    // Remote — IR / RF send-only; no scalar.
    Domain.REMOTE,
    // Alarm — categorical armed-state, not a 0..100 scalar.
    Domain.ALARM_CONTROL_PANEL,
    // Person — presence zone is categorical; weather — a condition word plus
    // forecast attributes. Neither maps to a 0..100 wheel scalar.
    Domain.PERSON, Domain.WEATHER,
    // Catch-all domains with no card archetype: no wheel scalar.
    Domain.OTHER -> null
}

/**
 * Read the supported_color_modes attribute as a list of mode-name strings. HA emits
 * this as a JSON array; an absent attribute (non-coloured bulb) returns empty so
 * downstream code can default the wheel-mode chips to brightness-only.
 */
private fun extractColorModes(attrs: kotlinx.serialization.json.JsonObject): List<String> {
    val arr = attrs["supported_color_modes"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return arr.mapNotNull { (it as? JsonPrimitive)?.content }
}

/**
 * Read the light's effect_list attribute as a list of effect names. HA exposes it as
 * a JSON array of strings; an absent attribute (most plain bulbs) returns empty so
 * the card hides the effect chip entirely.
 */
private fun extractEffectList(attrs: kotlinx.serialization.json.JsonObject): List<String> {
    val arr = attrs["effect_list"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return arr.mapNotNull { (it as? JsonPrimitive)?.content }
}

/**
 * Generic JSON-array-of-strings extractor — used for the `options` attribute on
 * select / input_select entities and any future attribute that ships as a flat
 * string array. Non-string elements are silently dropped rather than throwing so
 * a malformed HA payload doesn't lose the whole entity.
 */
private fun extractStringList(el: kotlinx.serialization.json.JsonElement?): List<String> {
    val arr = el as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return arr.mapNotNull { (it as? JsonPrimitive)?.content }
}

/**
 * Extract the current hue from `hs_color` if the bulb is reporting in colour mode.
 * HA exposes hs_color as [hue 0..360, saturation 0..100]. We only care about hue here
 * — saturation pinning is handled when we WRITE back at full saturation. Null when
 * the bulb isn't in a colour-aware mode.
 */
private fun extractHue(attrs: kotlinx.serialization.json.JsonObject): Double? {
    val arr = attrs["hs_color"] as? kotlinx.serialization.json.JsonArray ?: return null
    val h = arr.firstOrNull() as? JsonPrimitive ?: return null
    return h.content.toDoubleOrNull()
}

/**
 * Best-effort climate target-temperature read. HA exposes `temperature` for single-
 * setpoint HVAC modes (heat or cool); in `heat_cool` mode the entity has separate
 * `target_temp_high` (cooling target) and `target_temp_low` (heating target). We
 * pick the high one as the user-driven setpoint — that's what the slider usually
 * represents on dashboards. Falls back to `current_temperature` only as a last
 * resort (it's not a target value but at least gives a sensible scaled position
 * when the entity has no settable target at all).
 */
private fun climateTargetTemp(attrs: kotlinx.serialization.json.JsonObject): Double? =
    attrs["temperature"].asDouble()
        ?: attrs["target_temp_high"].asDouble()
        ?: attrs["target_temp_low"].asDouble()
        ?: attrs["current_temperature"].asDouble()

private fun computeRaw(domain: Domain, attrs: kotlinx.serialization.json.JsonObject): Number? = when (domain) {
    Domain.LIGHT -> attrs["brightness"].asInt()
    Domain.FAN -> attrs["percentage"].asInt()
    Domain.COVER -> attrs["current_position"].asInt()
    Domain.VALVE -> attrs["current_position"].asInt()
    Domain.MEDIA_PLAYER -> attrs["volume_level"].asDouble()
    Domain.HUMIDIFIER -> attrs["humidity"].asInt()
    // Climate's raw is the actual target_temperature for the card's display, with
    // the same fallback chain as computePercent so a `heat_cool` mode entity that
    // only exposes target_temp_high/low still renders sensibly. Water-heater
    // mirrors the climate path.
    Domain.CLIMATE, Domain.WATER_HEATER -> climateTargetTemp(attrs)
    Domain.SWITCH, Domain.INPUT_BOOLEAN, Domain.AUTOMATION, Domain.LOCK,
    Domain.SCENE, Domain.SCRIPT, Domain.BUTTON, Domain.INPUT_BUTTON,
    Domain.BINARY_SENSOR, Domain.VACUUM, Domain.LAWN_MOWER,
    Domain.SELECT, Domain.INPUT_SELECT -> null
    // For plain sensors the *state* IS the reading — there's no attribute to read from.
    // The SensorCard renders the rawState string directly; we don't try to coerce it
    // into a Number here because that loses precision (e.g. "21.7" → 21) and locale
    // formatting (HA already sends a presentation-ready string).
    Domain.SENSOR -> null
    // Number / input_number: same as plain sensor — the entity state is the value.
    // Repurposing rawState string for display + threading it through the VM at
    // service-call time keeps the precision intact.
    Domain.NUMBER, Domain.INPUT_NUMBER -> null
    // Helper-only domains: no numeric raw the card stack needs.
    Domain.COUNTER, Domain.TIMER, Domain.INPUT_TEXT, Domain.INPUT_DATETIME -> null
    // New read-only / toggle domains: no card-stack raw numeric.
    Domain.TEXT, Domain.DATE, Domain.DATETIME, Domain.TIME,
    Domain.SIREN, Domain.IMAGE, Domain.EVENT -> null
    // Update entities — version diff lives in attributes that the Updates
    // screen reads directly; no card-stack raw value to expose.
    Domain.UPDATE,
    // Remote — IR / RF send-only; no numeric raw.
    Domain.REMOTE,
    // Alarm — armed state is categorical, no numeric raw to surface.
    Domain.ALARM_CONTROL_PANEL,
    // Person — presence zone is a string; weather — the temperature lives in
    // an attribute the Weather screen reads directly, not a card-stack raw.
    Domain.PERSON, Domain.WEATHER,
    // Catch-all domains with no card archetype: no numeric raw.
    Domain.OTHER -> null
}

/**
 * Whether the entity exposes a settable scalar (brightness/percentage/position/volume) that
 * the wheel can drive. Used to filter on/off-only entities out of the Favourites picker —
 * otherwise users see brightness % controls for switches dressed as lights, which the wheel
 * can change visually but HA silently ignores.
 */
private fun supportsScalar(domain: Domain, attrs: kotlinx.serialization.json.JsonObject): Boolean = when (domain) {
    Domain.LIGHT -> {
        // `supported_color_modes` is the AUTHORITATIVE capability for a light — it lists
        // the modes the integration can drive. Non-dimmable lights have `["onoff"]` only;
        // anything else means at least brightness control. We trust it absolutely when
        // present (don't fall through to brightness-attribute checks, which lit up false
        // positives on non-dim lights when they were on with brightness=255).
        val supportedModes = (attrs["supported_color_modes"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        if (supportedModes.isNotEmpty()) {
            supportedModes.any { it != "onoff" }
        } else {
            // Older integrations don't expose supported_color_modes. Fall back to
            // color_mode then brightness as best-effort hints.
            val mode = attrs["color_mode"].asString()
            when {
                mode == "onoff" -> false
                mode != null -> true
                attrs["brightness"] != null -> true
                else -> false
            }
        }
    }
    // FanEntityFeature.SET_SPEED = bit 0 of supported_features.
    Domain.FAN -> ((attrs["supported_features"].asInt() ?: 0) and 1) != 0 ||
        attrs["percentage"] != null
    // CoverEntityFeature.SET_POSITION = bit 2.
    Domain.COVER -> ((attrs["supported_features"].asInt() ?: 0) and 4) != 0 ||
        attrs["current_position"] != null
    // MediaPlayerEntityFeature.VOLUME_SET = bit 2.
    Domain.MEDIA_PLAYER -> ((attrs["supported_features"].asInt() ?: 0) and 4) != 0 ||
        attrs["volume_level"] != null
    // Humidifiers always expose `set_humidity` as a service; the wheel drives that.
    // Treat presence of `humidity` attribute as authoritative — if it's missing
    // (a misbehaving integration) we still want a switch-card representation.
    Domain.HUMIDIFIER -> attrs["humidity"] != null
    // Climate: scalar when we have a temperature target AND the temperature range
    // (min/max). Without the range we can't map percent → °C, so the card falls back
    // to the switch-only path (turn_on / turn_off). ClimateEntityFeature.TARGET_TEMPERATURE
    // is bit 1 — we trust the supported_features bitmask AND the presence of min_temp.
    // Climate / water_heater: scalar when we have a temperature target AND a range.
    // Earlier this gated only on supported_features bit 1 (TARGET_TEMPERATURE) plus
    // min/max, but some integrations (notably MQTT-thermostats) forget the bit
    // while still exposing the attribute — fall back to climateTargetTemp() probing
    // the attributes themselves so those entities don't degrade to switch-only.
    Domain.CLIMATE, Domain.WATER_HEATER -> climateTargetTemp(attrs) != null &&
        attrs["min_temp"] != null && attrs["max_temp"] != null
    // Valve: same shape as cover — has the SET_POSITION bit (1<<1) or an explicit
    // current_position attribute. Falls back to switch (open_valve/close_valve)
    // when neither is present.
    Domain.VALVE -> ((attrs["supported_features"].asInt() ?: 0) and 2) != 0 ||
        attrs["current_position"] != null
    // number / input_number: always scalar — that's the entity's whole reason for
    // existing. Range comes from min/max attrs (defaulted to 0..100 if absent).
    Domain.NUMBER, Domain.INPUT_NUMBER -> true
    // Pure on/off domains — no scalar; rendered as switch cards.
    Domain.SWITCH, Domain.INPUT_BOOLEAN, Domain.AUTOMATION, Domain.LOCK -> false
    // Vacuums + lawn mowers map naturally to switch cards (start/dock on tap).
    Domain.VACUUM, Domain.LAWN_MOWER -> false
    // Action-only domains — no scalar; rendered as ActionCard tiles.
    Domain.SCENE, Domain.SCRIPT, Domain.BUTTON, Domain.INPUT_BUTTON -> false
    // Sensors are read-only — rendered as SensorCard, no wheel.
    Domain.SENSOR, Domain.BINARY_SENSOR -> false
    // Select entities — settable but the value is a discrete option, not a 0..100
    // scalar. Returning false routes them away from the percent / switch paths into
    // the dedicated SelectCard.
    Domain.SELECT, Domain.INPUT_SELECT -> false
    // Helper-only domains rendered exclusively on the Helpers screen; not
    // scalar from the card stack's perspective.
    Domain.COUNTER, Domain.TIMER, Domain.INPUT_TEXT, Domain.INPUT_DATETIME -> false
    // New read-only domains: no wheel scalar.
    Domain.TEXT, Domain.DATE, Domain.DATETIME, Domain.TIME, Domain.IMAGE, Domain.EVENT -> false
    // Siren: on/off only; no wheel scalar.
    Domain.SIREN -> false
    // Update entities are managed from the dedicated Updates screen, not
    // the card stack — return false so the Favourites picker filters them
    // out of "controllable" buckets, just like sensors.
    Domain.UPDATE -> false
    // Remote — IR / RF blasters; send-only, no scalar. Renders as a
    // switch card with the RemotePanel for activities + custom buttons.
    Domain.REMOTE -> false
    // Alarm — discrete armed states; rendered as a switch card with the
    // AlarmPanel surfacing the per-mode chips. No wheel-driven scalar.
    Domain.ALARM_CONTROL_PANEL -> false
    // Person / weather are read-only — rendered as SensorCard, no wheel.
    Domain.PERSON, Domain.WEATHER,
    // Catch-all domains with no card archetype: never wheel-scalar.
    Domain.OTHER -> false
}

/**
 * Best-available human-readable text for a failed [HaInbound.Result]. HA's error object can
 * carry a `message`, only a `code` (e.g. "not_found" / an integer code), or in rare frames
 * neither. Prefer the message, fall back to the code string, and only then the opaque
 * "ha_error" sentinel. Surfacing the code instead of swallowing it makes failure toasts and
 * logs diagnosable when an integration omits the message field.
 */
private fun HaInbound.Result.Error?.bestMessage(): String =
    this?.message ?: this?.codeString ?: "ha_error"

/** Decode the wire's bucket boundary, accepting either an ISO-8601 string
 *  or an epoch-millis number. */
private fun parseBucketInstant(element: kotlinx.serialization.json.JsonElement?): java.time.Instant? {
    val raw = (element as? JsonPrimitive)?.content ?: return null
    raw.toLongOrNull()?.let { return runCatching { java.time.Instant.ofEpochMilli(it) }.getOrNull() }
    return parseHaInstant(raw)
}

/** Fallback bucket span when HA omits `end` (rare but defensive). */
private fun bucketSpanSeconds(period: String): Long = when (period) {
    "5minute" -> 300L
    "hour" -> 3600L
    "day" -> 86_400L
    "week" -> 7L * 86_400L
    "month" -> 30L * 86_400L
    else -> 3600L
}

class DefaultHaRepository(
    private val ws: HaWebSocketClient,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
    /**
     * Optional refresher; production wires in [TokenRefresher], tests can pass null to skip the
     * network calls entirely and reuse whatever access token the test stubbed into [tokens].
     */
    private val refresher: TokenRefresher? = null,
    private val backoff: BackoffPolicy = BackoffPolicy(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * Optional disk persister for the entity cache — seeds [cache] on start
     * with the last-seen snapshot so the card stack paints immediately at
     * cold-start, before the WS even connects. Null in tests so they don't
     * accidentally read a developer's snapshot from /tmp.
     */
    private val persister: EntityStateCachePersister? = null,
    /**
     * Optional REST auth-failure circuit breaker, shared with the OkHttp interceptor.
     * Reset here on manual retry and server change so the user gets immediate relief
     * instead of waiting for the half-open backoff to elapse. Null in tests.
     */
    private val authThrottle: AuthThrottle? = null,
    /**
     * Current connection-hardening tuning, read on each use so a strict-mode toggle takes
     * effect without restarting the repository. Defaults to the conservative non-strict
     * values so tests (and the brief window before [App]'s collector runs) behave sensibly.
     * In production [AppGraph] supplies a lambda reading its live `connectionTuning`.
     */
    private val connectionTuning: () -> ConnectionTuning = {
        ConnectionTuning.from(com.github.itskenny0.r1ha.core.prefs.ConnectionSettings())
    },
    /**
     * Base backoff between [fetchHistory] retries (doubles each attempt). The production
     * default rides out a transiently-open auth breaker; tests inject a tiny value so the
     * retry path runs without real-time waits.
     */
    private val historyRetryBackoffMillis: Long = 1_000L,
) : HaRepository {

    override val connection: StateFlow<ConnectionState> = ws.state

    /** Failures broadcast to the ViewModel so it can roll back optimistic overrides. */
    private val _callFailures = MutableSharedFlow<EntityId>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val callFailures: SharedFlow<EntityId> = _callFailures.asSharedFlow()

    private val cache = MutableStateFlow<Map<EntityId, EntityState>>(emptyMap())

    /**
     * Domain-agnostic last-known-state cache keyed by the raw `domain.object_id`
     * string. Populated from the SAME WS `subscribe_trigger` / `state_changed`
     * stream (see [applyEvent]) and the REST `/api/states` seed (see
     * [seedCacheFromHa]) as [cache], but WITHOUT the supported-domain filter, so
     * [observeRawRows] can surface a value for an entity whose domain isn't in the
     * [Domain] enum (`sun.sun`, a custom integration sensor, `device_tracker.*`)
     * instead of a blank box. Bounded by the dashboard's subscribed ids plus the
     * user's favourites, itself bounded by the HA install's entity count.
     */
    private val rawCache = MutableStateFlow<Map<String, RawEntityRow>>(emptyMap())

    /**
     * Raw entity ids the dashboards renderer is currently displaying. These are
     * subscribed + REST-seeded ALONGSIDE the user's favourites so a card shows
     * live state for an entity the user never pinned. Kept as raw strings (not
     * [EntityId]) so an entity whose domain isn't in our [Domain] enum still
     * gets subscribed; the WS trigger + REST seed are domain-agnostic, the
     * typed-cache write downstream is what filters by supported domain.
     */
    private val _dashboardEntityIds = MutableStateFlow<Set<String>>(emptySet())

    /** Cached logged-in user id (auth/current_user), populated on connect and
     *  cleared on disconnect / server change. See [currentUserId]. */
    private val _currentUserId = MutableStateFlow<String?>(null)
    override val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

    /** Cached logged-in user display name, populated alongside [_currentUserId]. */
    private val _currentUserName = MutableStateFlow<String?>(null)
    override val currentUserName: StateFlow<String?> = _currentUserName.asStateFlow()

    private val pendingCalls = ConcurrentHashMap<Int, CompletableDeferred<Result<Unit>>>()
    /**
     * Parallel awaiter map keyed by the same request id space as [pendingCalls], but
     * the deferred completes with the inbound Result frame's `result` payload (a
     * JsonElement) rather than dropping it. Used by [callWsExpectingPayload] for
     * WS-only commands (`repairs/list_issues`, `backup/info`, etc.) where the
     * caller needs the response body.
     *
     * Kept distinct from [pendingCalls] so the call_service path (which doesn't
     * care about the payload) doesn't pay for an extra alloc on every gesture.
     */
    private val pendingPayloads = ConcurrentHashMap<Int, CompletableDeferred<Result<kotlinx.serialization.json.JsonElement?>>>()

    /**
     * Active live subscriptions (render_template, subscribe_events). HA's WS protocol
     * resets the request-id space on each new connection, so a subscription that
     * survived a reconnect would never receive events keyed to its old id. We track
     * each live subscription here so the WS Connected observer can re-issue the
     * subscribe frame with a fresh id and update the collector's filter atomically.
     *
     * Keyed by a stable local subscription handle id (not the WS request id) so the
     * caller's cancel() can find its entry even after the request id mutated.
     */
    private val liveSubs = ConcurrentHashMap<Int, ActiveLiveSub>()

    /** Next-available local handle id; independent of [ws.nextRequestId]. */
    private val nextLiveSubHandle = java.util.concurrent.atomic.AtomicInteger(1)

    /**
     * One live subscription's state. [requestId] is the WS-protocol id we're currently
     * filtered on — gets rotated on reconnect via [HaWebSocketClient.nextRequestId].
     * [frameType] + [frameExtras] are what we re-send to resubscribe.
     */
    private class ActiveLiveSub(
        val frameType: String,
        val frameExtras: kotlinx.serialization.json.JsonObject,
        val requestId: java.util.concurrent.atomic.AtomicInteger,
        val onEvent: (kotlinx.serialization.json.JsonObject) -> Unit,
        /** Mutable so registerLiveSubscription can fill it after launch. */
        var collectorJob: Job? = null,
    )
    private var supervisorJob: Job? = null
    // Volatile because the WS listener thread (OkHttp dispatch) reads it from the
    // AuthRequired handler while the repo coroutine writes it from connectFromSettings
    // and the post-refresh resubscribe path. @Volatile is enough since we only ever
    // assign or read a single reference, never read-modify-write.
    @Volatile private var subscriptionId: Int? = null
    /**
     * Most recently loaded HA access token. Read by the WS [HaWebSocketClient.connect]
     * tokenProvider closure on the OkHttp listener thread, so it must be volatile and
     * synchronously readable. Set in [connectFromSettings] and on every successful
     * token refresh; tokens.load() is suspend and not safe to call from the listener.
     */
    @Volatile private var latestAccessToken: String? = null
    /**
     * Tracks the in-flight seedCacheFromHa coroutine so URL change / sign-out can
     * cancel it before its retry loop finishes. Without this, a slow seed for
     * server A can land in the cache after the user has already signed into server
     * B, briefly painting server-A entities on server-B cards.
     */
    @Volatile private var seedJob: Job? = null
    /**
     * Tracks the cache.onEach collector that mirrors entity updates into the
     * persister. Lives on [scope] (not [supervisorJob]) so it survives WS
     * reconnects, but stop() needs to cancel it explicitly to avoid double-
     * subscribing on a subsequent start().
     */
    @Volatile private var persisterCollectorJob: Job? = null
    /**
     * Tracks the persister's debounce-write loop ([EntityStateCachePersister.bind]). Like
     * [persisterCollectorJob] it lives on [scope] and must be cancelled on stop()/rebind —
     * otherwise each start() after the first leaks another write loop onto [scope] and every
     * cache change fans out into one redundant disk write per leaked loop.
     */
    @Volatile private var persisterBindJob: Job? = null
    /** Tracks the currently-scheduled reconnect-backoff job so [reconnectNow] can cancel it. */
    @Volatile private var pendingReconnect: Job? = null

    /** Wall-clock target for the next scheduled reconnect. UI reads this for the
     *  countdown text. Cleared when we connect or when reconnectNow() short-circuits
     *  the backoff. */
    private val _reconnectAt = MutableStateFlow<Long?>(null)
    override val reconnectNextAttemptAtMillis: StateFlow<Long?> = _reconnectAt.asStateFlow()

    /** Tracks consecutive reconnect attempts so BackoffPolicy actually backs off. */
    @Volatile private var reconnectAttempt: Int = 0

    /**
     * Tracks AuthLost-driven refresh attempts so we don't tight-loop if a misconfigured HA
     * keeps issuing access tokens that fail auth. Reset on Connected.
     */
    @Volatile private var authLostRefreshAttempt: Int = 0

    /**
     * Wall-clock millisecond timestamp of the last useful signal from HA — either a
     * state_changed event applied through [applyEvent] or a successful REST seed/poll.
     * The heartbeat poller (see [start]) uses this to decide whether the WebSocket has
     * gone silent and a REST fallback is warranted. Initialised to 0 so the FIRST
     * heartbeat tick after start fires a REST poll if the WS hasn't connected yet — that
     * way a broken-WS-but-working-REST reverse-proxy setup paints cards within ~30 s of
     * launch instead of sitting blank indefinitely.
     *
     * Exposed through the [HaRepository.lastEventAtMillis] StateFlow so the About screen
     * can render a 'last WS event N seconds ago' diagnostic — useful for users who can
     * see the connection dot green but cards updating slowly (the reverse-proxy
     * partial-WS case the heartbeat is designed to mitigate).
     */
    private val _lastEventAt = MutableStateFlow(0L)
    override val lastEventAtMillis: StateFlow<Long> = _lastEventAt.asStateFlow()

    // Key the per-call debouncer by (target, service) rather than just (target).
    // Without the service segment, rapid taps of distinct media-transport buttons
    // (PLAY → NEXT → VOL+) all collapsed onto the same EntityId-only key and
    // cancelled each other — only the last submission inside the 120 ms window
    // would actually fire. Different services on the same entity now go through
    // separate pending slots so each one ships; identical-service calls still
    // coalesce, which is the wanted behaviour for scalar wheel/touch streams (the
    // last brightness value during a sustained spin is the only one HA needs).
    private val debouncer = DebouncedCaller<Pair<EntityId, String>, ServiceCall>(scope, debounceMillis = 120) { _, call ->
        val id = ws.nextRequestId()
        val deferred = CompletableDeferred<Result<Unit>>()
        pendingCalls[id] = deferred
        ws.send(HaOutbound.CallService(id, call.haDomain, call.service, call.target.value, call.data))
        // Wait for HA's Result with a hard ceiling. Without the timeout a slow/dead HA leaves
        // the deferred in `pendingCalls` forever; without the await we lose visibility into
        // whether the command actually shipped. CALL_TIMEOUT_MS is generous enough that even
        // a busy HA on a flaky link finishes inside it; if it doesn't, the user wants to know.
        val outcome = try {
            withTimeout(CALL_TIMEOUT_MS) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            // Drain the pending entry so we don't leak the deferred if a late Result eventually
            // arrives — the .remove() races with the inbound listener but ConcurrentHashMap
            // guarantees one of them wins cleanly.
            pendingCalls.remove(id)
            Result.failure(IllegalStateException("Timed out after ${CALL_TIMEOUT_MS / 1000}s"))
        }
        outcome.onFailure { t ->
            // R1Log gets the full picture (entity_id + service + message); the toast
            // is short enough to render legibly on the R1's 240×320 display. HA's
            // error strings can be paragraph-length ("Failed to call service light/turn_on:
            // Unable to find referenced entities…") and Android's Toast widget hard-
            // truncates anything past ~2 short lines, so a multi-line message gets cut
            // mid-sentence. We trim to the first ~28 chars of the underlying message,
            // surface only the entity's objectId, and use LENGTH_LONG so the user has
            // enough time to read it.
            R1Log.w("HaRepo.call", "${call.target.value}/${call.service} failed: ${t.message}")
            val rawMsg = t.message ?: "unknown error"
            val firstLine = rawMsg.lineSequence().firstOrNull().orEmpty()
            val shortMsg = if (firstLine.length > 28) firstLine.take(25) + "…" else firstLine
            // Surface full context in the expandable body so the user can tap the
            // toast to read the entire error (HA's "Validation error: Entity X
            // doesn't support service Y" runs well past the inline preview).
            // entity_id + service line gives a copy-paste handle for diagnosing
            // missing features (the most common cause of validation errors on
            // media_player integrations).
            com.github.itskenny0.r1ha.core.util.Toaster.errorExpandable(
                shortText = "${call.target.objectId}: $shortMsg",
                fullText = buildString {
                    append(call.target.value).append('\n')
                    append(call.service).append(" failed\n\n")
                    append(rawMsg)
                },
            )
            // Tell the ViewModel so it can roll back the optimistic override — the slider
            // bounces back to HA's last-known value instead of sitting stuck on the user's
            // intent. tryEmit is fine: the buffer is bounded with DROP_OLDEST.
            _callFailures.tryEmit(call.target)
        }
    }

    override suspend fun start() {
        if (supervisorJob != null) return
        // Seed the in-memory cache from disk BEFORE we start the WS — IF the
        // user opted into disk persistence via Settings → Dev menu →
        // 'persistCacheToDisk'. Off by default while the rehydrate path is
        // being hardened (the rehydrated entities have null `raw` and null
        // `attributesJson` which one user's session caught in an
        // unguarded read path). Opt-in users get the cold-start speedup;
        // everyone else gets the safe behaviour.
        persister?.let { p ->
            val current = settings.settings.first()
            if (!current.advanced.persistCacheToDisk) {
                R1Log.i("HaRepo", "persistCacheToDisk=false; skipping disk-cache wiring")
                return@let
            }
            val restored = withContext(Dispatchers.IO) { p.load() }
            if (restored.isNotEmpty()) {
                cache.value = restored
                R1Log.i("HaRepo", "seeded cache from disk: ${restored.size} entities")
            }
            // Bind the persister to start collecting markDirty ticks. The
            // bind() call kicks off the debounce loop on [scope]. Cancel any
            // previously-bound collector so a stop()/start() cycle within the
            // same process doesn't end up with two collectors fighting over
            // the same persister.
            persisterCollectorJob?.cancel()
            persisterBindJob?.cancel()
            persisterBindJob = p.bind()
            // Mirror every cache change into the persister so the snapshot
            // stays current. Debouncing happens inside markDirty's flow.
            persisterCollectorJob = cache.onEach { p.markDirty(it) }.launchIn(scope)
        }
        supervisorJob = scope.launch {
            ws.inbound.onEach { msg ->
                when (msg) {
                    is HaInbound.Result -> {
                        val deferred = pendingCalls.remove(msg.id)
                        if (deferred != null) {
                            deferred.complete(
                                if (msg.success) Result.success(Unit)
                                else Result.failure(
                                    IllegalStateException(msg.error.bestMessage())
                                )
                            )
                        }
                        // Same id space serves payload-awaiters too; complete in parallel
                        // with the response body (or null when HA didn't include one).
                        val payloadDeferred = pendingPayloads.remove(msg.id)
                        if (payloadDeferred != null) {
                            payloadDeferred.complete(
                                if (msg.success) Result.success(msg.result)
                                else Result.failure(
                                    IllegalStateException(msg.error.bestMessage())
                                )
                            )
                        }
                        if (deferred == null && payloadDeferred == null) {
                            // A Result arriving for an id we no longer track means either
                            // the deferred already timed out (and we replaced its failure
                            // text 15s ago) or sign-out drained the map while HA's reply
                            // was in flight. Surface at debug only so noisy traces stay
                            // out of the default log level, but visible during triage.
                            R1Log.d("HaRepo.late", "result for unknown id=${msg.id}; success=${msg.success}")
                        }
                    }
                    is HaInbound.Event -> applyEvent(msg)
                    else -> Unit
                }
            }.launchIn(this)

            // Track the previous state alongside each onEach emission so the Disconnected
            // branch can suppress its own reconnect when we transitioned out of AuthLost:
            // the AuthLost handler already schedules a refresh + connectFromSettings, and
            // double-scheduling here would race a second reconnect against the first.
            var prevState: ConnectionState = ConnectionState.Idle
            ws.state.onEach { st ->
                val previous = prevState
                prevState = st
                when (st) {
                    is ConnectionState.Connected -> {
                        reconnectAttempt = 0
                        authLostRefreshAttempt = 0
                        // Stamp the heartbeat now so the REST fallback poller in [start]
                        // doesn't fire a redundant /api/states right after a fresh
                        // Connected (the seedCacheFromHa() call below already handles
                        // the initial paint).
                        _lastEventAt.value = System.currentTimeMillis()
                        // Connected — there's nothing scheduled, so the UI countdown should
                        // stop. The pendingReconnect job, if any, has already fired and
                        // self-cleared this; this assignment is the belt-and-braces case
                        // where we landed in Connected via reconnectNow() or a manual
                        // start() while a backoff was pending.
                        _reconnectAt.value = null
                        resubscribe()
                        resubscribeLive()
                        // Don't block the state observer on the REST seed (can take a few
                        // seconds with retries) — if a Disconnect happens mid-seed, the
                        // observer needs to be free to react to it, otherwise the conflated
                        // StateFlow would collapse a brief Connected → Disconnected → Connected
                        // bounce into a single observed Connected.
                        seedJob?.cancel()
                        seedJob = scope.launch {
                            // Seed the entity cache first (REST), THEN fetch the
                            // logged-in user id. Doing the user fetch after the seed
                            // keeps the post-connect WS frame order subscribe-first
                            // (the subscribe_trigger goes out from resubscribe before
                            // this REST-bound seed yields). Best-effort: the id stays
                            // null when the command is unsupported / the call fails.
                            seedCacheFromHa()
                            refreshCurrentUser()
                        }
                    }
                    is ConnectionState.Disconnected -> {
                        // The WS client always reports st.attempt=0 (it has no notion of
                        // consecutive failures); we track the run here.
                        val attempt = reconnectAttempt
                        reconnectAttempt = attempt + 1
                        // Fail any in-flight service-call deferreds whose Result will never
                        // arrive: without this they leak into pendingCalls until the process
                        // dies and any awaiter would hang indefinitely.
                        if (pendingCalls.isNotEmpty()) {
                            pendingCalls.values.forEach {
                                it.complete(Result.failure(IllegalStateException("WS disconnected mid-call")))
                            }
                            pendingCalls.clear()
                        }
                        if (pendingPayloads.isNotEmpty()) {
                            pendingPayloads.values.forEach {
                                it.complete(Result.failure(IllegalStateException("WS disconnected mid-call")))
                            }
                            pendingPayloads.clear()
                        }
                        // Drop the cached user id: the next connect re-fetches it
                        // (and a server change must not bleed user A's id into B).
                        _currentUserId.value = null
                        _currentUserName.value = null
                        // If we just transitioned out of AuthLost (which fired its own
                        // refresh + connectFromSettings) the Disconnected handler must NOT
                        // also schedule a reconnect; both timers would otherwise race and
                        // double-connect. The AuthLost path owns the reconnect dispatch.
                        if (previous is ConnectionState.AuthLost) {
                            R1Log.i("HaRepo.disconnect", "suppressing reconnect; AuthLost handler owns it")
                            return@onEach
                        }
                        // Give-up gate: after [RECONNECT_GIVE_UP_THRESHOLD] consecutive
                        // failures (~8 min of backed-off attempts with the default policy),
                        // stop scheduling auto-retries. Without this the repository keeps
                        // hammering a permanently-broken endpoint at the 30 s ceiling
                        // forever, and reauth attempts on a stale token can trip HA's
                        // `login_attempts_threshold` and get the device IP-banned.
                        // The user retains a one-tap recovery path via the existing
                        // "STILL LOADING · TAP TO RETRY" affordance, which calls
                        // [reconnectNow] and resets the counter.
                        if (attempt >= RECONNECT_GIVE_UP_THRESHOLD) {
                            R1Log.w(
                                "HaRepo.disconnect",
                                "$attempt consecutive reconnect failures; pausing auto-retry to avoid IP-ban (tap retry to resume)",
                            )
                            com.github.itskenny0.r1ha.core.util.Toaster.error(
                                "Home Assistant unreachable after $attempt attempts. Auto-reconnect paused; tap retry when ready.",
                            )
                            _reconnectAt.value = null
                            return@onEach
                        }
                        reconnectLater(attempt)
                    }
                    is ConnectionState.AuthLost -> {
                        // Access token was rejected — most often because the 30-minute lifetime
                        // expired. Try one refresh; if it succeeds, reconnect. If the refresh
                        // itself fails (revoked refresh token, server unreachable, etc.) we stay
                        // in AuthLost and the user has to manually sign out & reconnect.
                        // Bounded to the configured max (ConnectionTuning.maxAuthRetries; 3 by
                        // default, lower in strict mode) to avoid tight-looping if HA keeps issuing
                        // access tokens that fail auth, and to bound the /auth/token POSTs a strict
                        // HA also counts as failed logins.
                        // Also drain pendingCalls — the WS was just closed by AuthInvalid so
                        // any outstanding Result deferreds won't ever complete naturally.
                        if (pendingCalls.isNotEmpty()) {
                            pendingCalls.values.forEach {
                                it.complete(Result.failure(IllegalStateException("WS auth lost")))
                            }
                            pendingCalls.clear()
                        }
                        if (pendingPayloads.isNotEmpty()) {
                            pendingPayloads.values.forEach {
                                it.complete(Result.failure(IllegalStateException("WS auth lost")))
                            }
                            pendingPayloads.clear()
                        }
                        val attempt = authLostRefreshAttempt
                        // Cap the recovery loop. In strict mode the user can lower this so the
                        // app POSTs /auth/token fewer times before pausing — each failed refresh
                        // is also a failed login a strict HA counts toward its ban.
                        val maxAuthRetries = connectionTuning().maxAuthRetries
                        if (attempt >= maxAuthRetries) {
                            R1Log.w("HaRepo.authLost", "max refresh attempts ($attempt) reached; staying AuthLost")
                            return@onEach
                        }
                        authLostRefreshAttempt = attempt + 1
                        R1Log.w("HaRepo.authLost", "reason=${st.reason}; attempting token refresh (try ${attempt + 1})")
                        scope.launch {
                            // Small backoff so a misbehaving HA doesn't get hammered.
                            delay(backoff.delayForAttempt(attempt))
                            if (refresher?.forceRefresh() == true) {
                                R1Log.i("HaRepo.authLost", "refresh succeeded; reconnecting")
                                connectFromSettings()
                            } else {
                                R1Log.w("HaRepo.authLost", "refresh failed; staying AuthLost")
                            }
                        }
                    }
                    else -> Unit
                }
            }.launchIn(this)

            // Re-subscribe + reseed the cache whenever the user's favourites change. Without
            // this the WS only receives subscribe_trigger for the initial favourites list
            // (taken at WS Connected) and never sees state_changed events for anything the
            // user adds later — so newly-added cards would sit at 0% until manually toggled
            // from elsewhere.
            settings.settings
                .map { it.favorites }
                .distinctUntilChanged()
                .onEach {
                    R1Log.i("HaRepo.favsChange", "favorites changed to ${it.size} entries")
                    if (ws.state.value is ConnectionState.Connected) {
                        resubscribe()
                        seedJob?.cancel()
                        seedJob = scope.launch { seedCacheFromHa() }
                    }
                }
                .launchIn(this)

            // Same treatment for the dashboards renderer's entity set: when the user
            // opens a dashboard view (or switches views), the renderer publishes the
            // entities it shows via [observeRaw]. Re-subscribe + reseed so those cards
            // get live state even when the entities aren't pinned favourites.
            _dashboardEntityIds
                .onEach {
                    R1Log.i("HaRepo.dashEntities", "dashboard entity set now ${it.size} entries")
                    if (ws.state.value is ConnectionState.Connected) {
                        resubscribe()
                        seedJob?.cancel()
                        seedJob = scope.launch { seedCacheFromHa() }
                    }
                }
                .launchIn(this)

            // Observe the server URL; connect when it appears and disconnect when it goes
            // away. We deliberately do NOT force-reconnect on URL changes while a connection
            // is in flight, because the only legal way to change URLs in this app is via the
            // sign-out flow (which sets URL to null first, triggering the disconnect branch).
            // That also lets tests that pre-wire a WS connection coexist with start() without
            // having their connection torn down.
            settings.settings
                .map { it.server?.url }
                .distinctUntilChanged()
                .onEach { url ->
                    R1Log.i("HaRepo.serverChange", "server URL now $url; ws.state=${ws.state.value::class.simpleName}")
                    // Reset the consecutive-failure counter on any URL transition so a sign-out
                    // followed by a sign-in starts the backoff schedule fresh instead of
                    // inheriting accumulated failures from the previous server.
                    reconnectAttempt = 0
                    authLostRefreshAttempt = 0
                    // A different server (or a re-onboard to the same one) invalidates the
                    // breaker's accumulated 401s: the new credentials deserve a clean start.
                    authThrottle?.reset()
                    if (url == null) {
                        // Drop any cached entity states from the previous server so the next
                        // sign-in starts fresh: otherwise stale data from server A could be
                        // briefly visible on cards when the user signs into server B with the
                        // same entity IDs. Cancel any seedJob whose 3-retry loop is still
                        // grinding so its results don't land in the new server's cache.
                        seedJob?.cancel()
                        seedJob = null
                        cache.update { emptyMap() }
                        subscriptionId = null
                        // Fail any outstanding service-call awaiters; their WS is going away.
                        pendingCalls.values.forEach {
                            it.complete(Result.failure(IllegalStateException("Signed out")))
                        }
                        pendingCalls.clear()
                        pendingPayloads.values.forEach {
                            it.complete(Result.failure(IllegalStateException("Signed out")))
                        }
                        pendingPayloads.clear()
                        // Drop live subscriptions on sign-out too: the next sign-in
                        // is to a different server and those subscriptions belong
                        // to entities/templates on the old one.
                        liveSubs.values.forEach { it.collectorJob?.cancel() }
                        liveSubs.clear()
                        ws.disconnect()
                        return@onEach
                    }
                    val st = ws.state.value
                    if (st is ConnectionState.Idle || st is ConnectionState.Disconnected) {
                        connectFromSettings()
                    }
                }
                .launchIn(this)

            // Heartbeat / REST fallback poller. The WS path is still the primary delivery
            // channel for state_changed — instant updates, low overhead — but a class of
            // reverse-proxy misconfigurations break it in subtle ways that leave the app
            // looking healthy on the surface:
            //   1. nginx without `proxy_set_header Upgrade $http_upgrade;` rejects the
            //      Upgrade handshake — ws.state stays Disconnected; cards never refresh.
            //   2. nginx with `proxy_buffering on` (the default) for the WS location can
            //      coalesce or drop frames — ws.state shows Connected but state_changed
            //      events arrive late, out of order, or not at all.
            //   3. Cloudflare's free tier closes idle WebSockets after ~100s — silent
            //      drops until the next reconnect cycle catches up.
            // The user has no leverage to fix any of these from the app, but a periodic
            // REST poll on /api/states works through every one of them (no Upgrade, no
            // streaming, no idle timeout). Cadence is conservative — 30 s — so a healthy
            // WS that produces any event resets the timer and the poller never fires.
            // A truly silent WS gives the user cards lagging ~30 s instead of forever.
            launch {
                while (true) {
                    // Strict mode stretches the heartbeat cadence so a quiet WS triggers fewer
                    // background REST polls against a strict HA. Re-read each tick so a toggle
                    // applies on the next loop.
                    delay(connectionTuning().scaleBackground(HEARTBEAT_INTERVAL_MS))
                    val s = settings.settings.first()
                    if (s.server == null) continue
                    if (s.favorites.isEmpty()) continue
                    val st = ws.state.value
                    // AuthLost / Idle won't be helped by polling — REST uses the same
                    // access token that just got rejected, and Idle means no URL yet.
                    if (st is ConnectionState.AuthLost || st is ConnectionState.Idle) continue
                    val silentFor = System.currentTimeMillis() - _lastEventAt.value
                    if (silentFor < HEARTBEAT_SILENCE_THRESHOLD_MS) continue
                    R1Log.i(
                        "HaRepo.heartbeat",
                        "no WS event for ${silentFor / 1000}s (state=${st::class.simpleName}); polling REST",
                    )
                    silentRefreshFromHa()
                }
            }
        }
    }

    override suspend fun stop() {
        // Fail any in-flight service-call deferreds first: their Result will never arrive
        // because the supervisor cancel below tears down the inbound observer, and the
        // ws.disconnect drains the outgoing queue. Without an explicit fail any awaiter
        // hangs until the 15s timeout, which means a caller's UI sits "FIRING…" while
        // the user has already navigated away.
        if (pendingCalls.isNotEmpty()) {
            pendingCalls.values.forEach {
                it.complete(Result.failure(IllegalStateException("Repository stopped")))
            }
            pendingCalls.clear()
        }
        if (pendingPayloads.isNotEmpty()) {
            pendingPayloads.values.forEach {
                it.complete(Result.failure(IllegalStateException("Repository stopped")))
            }
            pendingPayloads.clear()
        }
        // Tear down every active live subscription's collector job. Without this
        // a subscription's collectorJob would survive repository teardown via the
        // scope hierarchy until the SupervisorJob root cancels — relying on that
        // is fragile and forces extra work on every stale event during shutdown.
        if (liveSubs.isNotEmpty()) {
            liveSubs.values.forEach { it.collectorJob?.cancel() }
            liveSubs.clear()
        }
        latestAccessToken = null
        subscriptionId = null
        seedJob?.cancel(); seedJob = null
        persisterCollectorJob?.cancel(); persisterCollectorJob = null
        persisterBindJob?.cancel(); persisterBindJob = null
        supervisorJob?.cancel(); supervisorJob = null
        ws.disconnect()
    }

    private suspend fun connectFromSettings() {
        // Proactively refresh the access token if it's within ~60s of expiry. Cheap when the
        // token has time left (just an in-memory check), and avoids the AuthLost → refresh →
        // reconnect round-trip on the common "user opens app after >30min" case.
        refresher?.ensureFresh()
        val s = settings.settings.first()
        val server = s.server ?: return
        val t = tokens.load()
        if (t == null) {
            // Server is configured but we have no usable tokens — most often the Keystore key
            // got wiped (factory reset of secure storage), leaving encrypted tokens that can no
            // longer be decrypted. Without this signal the UI would sit on "Idle" forever; tell
            // the user explicitly to re-auth from Settings.
            R1Log.w("HaRepo.connect", "tokens.load() returned null even though server is set; user needs to re-auth")
            com.github.itskenny0.r1ha.core.util.Toaster.error(
                "Authentication tokens missing. Open Settings → Sign out & reconnect.",
            )
            return
        }
        val base = server.url.trimEnd('/')
        val wsUrl = when {
            base.startsWith("https://") -> base.replaceFirst("https://", "wss://")
            base.startsWith("http://")  -> base.replaceFirst("http://", "ws://")
            else -> base
        } + "/api/websocket"
        // Pass a tokenProvider rather than the captured-at-call-time token so the WS handshake
        // reads the latest value at AuthRequired time. If a concurrent refresh rotates the
        // token between this line and the handshake, the WS picks up the rotated value
        // rather than handing HA an already-revoked one. The provider reads the @Volatile
        // [latestAccessToken] cache rather than re-running suspend tokens.load() from the
        // OkHttp listener thread; the cache is updated on every successful refresh and on
        // every connectFromSettings entry.
        latestAccessToken = t.accessToken
        ws.connect(wsUrl) { latestAccessToken ?: t.accessToken }
    }

    private fun reconnectLater(attempt: Int) {
        // Track this job so reconnectNow() can cancel it and fire immediately. Cancel any
        // previously-pending reconnect first so two overlapping backoffs don't both fire
        // (would cause an immediate-after-delay double-connect from rapid bouncing).
        pendingReconnect?.cancel()
        val delayMs = backoff.delayForAttempt(attempt)
        _reconnectAt.value = System.currentTimeMillis() + delayMs
        pendingReconnect = scope.launch {
            delay(delayMs)
            _reconnectAt.value = null
            connectFromSettings()
        }
    }

    override fun reconnectNow() {
        // The user explicitly asked to retry: drop the auth breaker so REST traffic flows
        // again immediately rather than waiting out the open backoff.
        authThrottle?.reset()
        val current = ws.state.value
        // Only honour the request when there's nothing useful in flight already — re-entering
        // a Connecting state would just thrash the WS client.
        if (current is ConnectionState.Connecting ||
            current is ConnectionState.Authenticating ||
            current is ConnectionState.Connected
        ) {
            R1Log.i("HaRepo.reconnectNow", "ignored (state=${current::class.simpleName})")
            return
        }
        pendingReconnect?.cancel()
        pendingReconnect = null
        // Clear the countdown target — we're firing now, not waiting. Without this the UI
        // would keep showing "RECONNECTING IN Xs…" briefly until Connected updates the
        // surrounding state, which looks broken when the user just tapped retry.
        _reconnectAt.value = null
        // Reset the consecutive-failure counter so the *next* backoff (if this attempt also
        // fails) starts from scratch — the user has signalled they want a fresh start.
        reconnectAttempt = 0
        R1Log.i("HaRepo.reconnectNow", "forcing immediate reconnect (was $current)")
        scope.launch { connectFromSettings() }
    }

    private fun applyEvent(ev: HaInbound.Event) {
        // HA occasionally emits state-change events with no to_state (entity removed) or a
        // missing state field; treat both as no-ops rather than letting the unwrap NPE.
        val raw = ev.event.variables.trigger.toState ?: return
        val stateStr = raw.state ?: return
        val idStr = raw.entityId ?: ev.event.variables.trigger.entityId
        val prefix = idStr.substringBefore('.', missingDelimiterValue = "")
        // Mirror EVERY live trigger into the domain-agnostic raw cache BEFORE the
        // supported-domain guard below drops unmodelled domains from the typed
        // cache. This is what keeps a sun.sun / custom-domain dashboard card live:
        // the WS already delivers these events because subscribe_trigger registers
        // the raw dashboard ids verbatim; only the receive side filtered them out.
        if (idStr.contains('.')) {
            val rawRow = RawEntityRow(
                entityId = idStr,
                friendlyName = raw.attributes["friendly_name"].asString() ?: idStr,
                state = stateStr,
                attributes = raw.attributes,
                lastChanged = parseHaInstant(raw.lastChanged),
            )
            rawCache.update { it + (idStr to rawRow) }
            _lastEventAt.value = System.currentTimeMillis()
        }
        if (!Domain.isSupportedPrefix(prefix)) return
        // The prefix check above accepts a supported domain, but EntityId's init also
        // rejects malformed ids (empty object_id, e.g. "sensor."). A throw here would
        // escape the inbound onEach and cancel the whole message-processing flow for the
        // session, silently freezing all live updates. Guard it the same way the REST
        // seed path does (see seedCacheFromHa / listAllControllable) and drop the event.
        val id = runCatching { EntityId(idStr) }.getOrNull() ?: return
        // Resolve the domain once. EntityId.domain re-parses the entity_id string
        // (substringBefore('.') allocation + a map lookup) on every access, and the
        // EntityState construction below reads it ~60 times; hoisting to a local turns
        // ~60 substring allocations + map lookups per event into one.
        val domain = id.domain
        val objectId = id.objectId
        // State-string → isOn mapping, branched by domain. Each domain has its own state
        // vocabulary in HA: lights/switches/input_boolean/automation/humidifier use
        // "on"/"off", media_players use "playing"/"paused"/"idle", covers use "open"/
        // "closed"/"opening"/"closing", locks use "locked"/"unlocked", thermostats use
        // the HVAC mode itself ("off"/"heat"/"cool"/"auto"/"dry"/"fan_only"). `isOn=true`
        // reads as "the affordance is engaged" — light on, switch on, cover open, lock
        // UNLOCKED (so the toggle reads intuitively: tap to lock when unlocked), thermostat
        // running.
        val isOn = when (domain) {
            Domain.LIGHT, Domain.FAN, Domain.SWITCH, Domain.INPUT_BOOLEAN,
            Domain.AUTOMATION, Domain.HUMIDIFIER -> stateStr.equals("on", ignoreCase = true)
            Domain.COVER, Domain.VALVE -> stateStr.equals("open", ignoreCase = true)
            Domain.MEDIA_PLAYER -> stateStr.equals("playing", ignoreCase = true)
            Domain.LOCK -> stateStr.equals("unlocked", ignoreCase = true)
            Domain.CLIMATE, Domain.WATER_HEATER -> !stateStr.equals("off", ignoreCase = true) &&
                stateStr != "unavailable" && stateStr != "unknown"
            // Scripts have an "on" state while they're executing. Scene/button never get
            // a meaningful on state: their state attribute is a last-fired timestamp.
            Domain.SCRIPT -> stateStr.equals("on", ignoreCase = true)
            Domain.SCENE, Domain.BUTTON, Domain.INPUT_BUTTON -> false
            // binary_sensor uses "on"/"off" by HA convention: "on" means the triggered
            // state (door open, motion detected, leak found). Plain sensor entities have
            // numeric/string readings and don't have a meaningful on/off mapping.
            Domain.BINARY_SENSOR -> stateStr.equals("on", ignoreCase = true)
            Domain.SENSOR -> false
            // number / input_number entities: state is the numeric value as a string.
            // "Non-zero" is the closest thing to "on" but it isn't very meaningful here;
            // the wheel just drives the value. Treat as false so tap-toggle doesn't try
            // to flip a slider to its zero/non-zero positions.
            Domain.NUMBER, Domain.INPUT_NUMBER -> false
            // Vacuum: any active state (cleaning, returning) reads as "on".
            Domain.VACUUM -> stateStr.equals("cleaning", ignoreCase = true) ||
                stateStr.equals("returning", ignoreCase = true) ||
                stateStr.equals("on", ignoreCase = true)
            // Lawn mower: parallel state taxonomy to vacuum. Treat any active
            // state (mowing, returning) as "on" so card visuals reflect motion.
            Domain.LAWN_MOWER -> stateStr.equals("mowing", ignoreCase = true) ||
                stateStr.equals("returning", ignoreCase = true) ||
                stateStr.equals("on", ignoreCase = true)
            // Select / input_select have no on/off: they're settable enums. Pin
            // isOn to false so tap-toggle doesn't try to flip them; the dedicated
            // picker overlay is the only way to change the option.
            Domain.SELECT, Domain.INPUT_SELECT -> false
            // Counter / timer / input_text / input_datetime: Helpers-screen
            // rendered only. No meaningful on/off mapping; the bespoke
            // per-kind controls on the Helpers screen handle interaction.
            Domain.COUNTER, Domain.INPUT_TEXT, Domain.INPUT_DATETIME -> false
            // Timer: 'active' is the running state, 'paused' is suspended,
            // 'idle' is stopped. Treat 'active' as on so a hypothetical
            // pin-to-favorites + tap could be wired later without further
            // changes to isOn semantics.
            Domain.TIMER -> stateStr.equals("active", ignoreCase = true)
            // Siren: standard on/off state vocabulary.
            Domain.SIREN -> stateStr.equals("on", ignoreCase = true)
            // text / date / datetime / time / image / event: no on/off concept;
            // read-only or fire-and-forget.
            Domain.TEXT, Domain.DATE, Domain.DATETIME, Domain.TIME,
            Domain.IMAGE, Domain.EVENT -> false
            // Update entities have state "on" when an update is available and
            // "off" when up to date. Surface that mapping so the Updates
            // screen can read isOn as "update available" without touching
            // attributesJson — useful for any future status surfacing.
            Domain.UPDATE -> stateStr.equals("on", ignoreCase = true)
            // Remote/IR blasters: "on" when the integration's listener is
            // active. Broadlink and most learned-command blasters report "on"
            // by default; activity hubs (Harmony) report the activity name
            // when running, "off" when idle. Treat anything non-off as on so
            // the chrome pill reads sensibly.
            Domain.REMOTE -> !stateStr.equals("off", ignoreCase = true) &&
                stateStr != "unavailable" && stateStr != "unknown"
            // Alarm: "on" when the panel is armed in any flavour, triggered,
            // or in the arming / pending / disarming transitions. Disarmed
            // reads as off so the chrome pill reads false-as-safe.
            Domain.ALARM_CONTROL_PANEL -> !stateStr.equals("disarmed", ignoreCase = true) &&
                stateStr != "unavailable" && stateStr != "unknown" && stateStr.isNotBlank()
            // Person: state is a zone name. "home" reads as on so a presence pill
            // reads true-as-home; any other zone (including "not_home") reads off.
            Domain.PERSON -> stateStr.equals("home", ignoreCase = true)
            // Weather: state is the current condition word, no on/off concept.
            // Read-only forecast surface; pin to false so the chrome pill stays quiet.
            Domain.WEATHER -> false
            // Catch-all domains with no archetype: no on/off mapping.
            Domain.OTHER -> false
        }
        val available = stateStr != "unavailable" && stateStr != "unknown"
        val pct = computePercentWithState(domain, raw.attributes, stateStr)
        val rawNum = computeRaw(domain, raw.attributes)
            ?: if (domain == Domain.NUMBER || domain == Domain.INPUT_NUMBER) stateStr.toDoubleOrNull() else null
        val newState = EntityState(
            id = id,
            friendlyName = raw.attributes["friendly_name"].asString() ?: objectId,
            area = raw.attributes["area_id"].asString(),
            isOn = isOn,
            percent = if (available) pct else null,
            raw = rawNum,
            lastChanged = (parseHaInstant(raw.lastChanged) ?: Instant.now()),
            lastTriggered = if (domain == Domain.AUTOMATION || domain == Domain.SCRIPT)
                raw.attributes["last_triggered"].asString()?.let { parseHaInstant(it) } else null,
            isAvailable = available,
            supportsScalar = supportsScalar(domain, raw.attributes),
            rawState = stateStr,
            // For climate, HA puts the temperature unit on `temperature_unit` rather than
            // `unit_of_measurement` (which it doesn't expose at all). Surface it through
            // the same `unit` field so the card display layer doesn't need to know.
            unit = raw.attributes["unit_of_measurement"].asString()
                ?: raw.attributes["temperature_unit"].asString(),
            deviceClass = raw.attributes["device_class"].asString(),
            // Range for any scalar with a custom span — climate (min_temp), humidifier
            // (min_humidity), number/input_number (min). Picked by domain so HA's
            // overloaded attribute names don't bleed across.
            minRaw = when (domain) {
                Domain.CLIMATE, Domain.WATER_HEATER -> raw.attributes["min_temp"].asDouble()
                Domain.HUMIDIFIER -> raw.attributes["min_humidity"].asDouble()
                Domain.NUMBER, Domain.INPUT_NUMBER -> raw.attributes["min"].asDouble() ?: 0.0
                else -> null
            },
            maxRaw = when (domain) {
                Domain.CLIMATE, Domain.WATER_HEATER -> raw.attributes["max_temp"].asDouble()
                Domain.HUMIDIFIER -> raw.attributes["max_humidity"].asDouble()
                Domain.NUMBER, Domain.INPUT_NUMBER -> raw.attributes["max"].asDouble() ?: 100.0
                else -> null
            },
            supportedColorModes = if (domain == Domain.LIGHT) extractColorModes(raw.attributes) else emptyList(),
            colorTempK = if (domain == Domain.LIGHT) raw.attributes["color_temp_kelvin"].asInt() else null,
            minColorTempK = if (domain == Domain.LIGHT) raw.attributes["min_color_temp_kelvin"].asInt() else null,
            maxColorTempK = if (domain == Domain.LIGHT) raw.attributes["max_color_temp_kelvin"].asInt() else null,
            hue = if (domain == Domain.LIGHT) extractHue(raw.attributes) else null,
            step = if (domain == Domain.NUMBER || domain == Domain.INPUT_NUMBER)
                raw.attributes["step"].asDouble() else null,
            effectList = if (domain == Domain.LIGHT) extractEffectList(raw.attributes) else emptyList(),
            effect = if (domain == Domain.LIGHT) raw.attributes["effect"].asString()?.takeIf { it != "None" } else null,
            attributesJson = raw.attributes,
            // Select-domain bits — options list + current option track via state.
            selectOptions = if (domain.isSelect) extractStringList(raw.attributes["options"]) else emptyList(),
            currentOption = if (domain.isSelect) stateStr.takeIf { it.isNotBlank() && it != "unknown" && it != "unavailable" } else null,
            mediaTitle = if (domain == Domain.MEDIA_PLAYER) raw.attributes["media_title"].asString() else null,
            mediaArtist = if (domain == Domain.MEDIA_PLAYER) raw.attributes["media_artist"].asString() else null,
            mediaAlbumName = if (domain == Domain.MEDIA_PLAYER) raw.attributes["media_album_name"].asString() else null,
            mediaDuration = if (domain == Domain.MEDIA_PLAYER) raw.attributes["media_duration"].asInt() else null,
            mediaPosition = if (domain == Domain.MEDIA_PLAYER) raw.attributes["media_position"].asInt() else null,
            mediaPositionUpdatedAt = if (domain == Domain.MEDIA_PLAYER) {
                raw.attributes["media_position_updated_at"].asString()?.let { parseHaInstant(it) }
            } else null,
            mediaPicture = if (domain == Domain.MEDIA_PLAYER) raw.attributes["entity_picture"].asString() else null,
            isVolumeMuted = domain == Domain.MEDIA_PLAYER &&
                (raw.attributes["is_volume_muted"].asBoolean() ?: false),
            mediaSupportedFeatures = if (domain == Domain.MEDIA_PLAYER)
                raw.attributes["supported_features"].asInt() ?: 0
            else 0,
            mediaShuffle = domain == Domain.MEDIA_PLAYER &&
                (raw.attributes["shuffle"].asBoolean() ?: false),
            mediaRepeat = if (domain == Domain.MEDIA_PLAYER)
                raw.attributes["repeat"].asString() else null,
            mediaSource = if (domain == Domain.MEDIA_PLAYER)
                raw.attributes["source"].asString() else null,
            mediaSourceList = if (domain == Domain.MEDIA_PLAYER)
                extractStringList(raw.attributes["source_list"]) else emptyList(),
            vacuumSupportedFeatures = if (domain == Domain.VACUUM)
                raw.attributes["supported_features"].asInt() ?: 0 else 0,
            // Generic supported_features for the domains that get a dedicated
            // panel but don't share fields with the vacuum/media branches.
            // Lawn-mower / climate / valve / water_heater each read this field
            // via [EntityState.hasFeature] to gate their respective chips.
            supportedFeatures = when (domain) {
                Domain.LAWN_MOWER, Domain.CLIMATE, Domain.VALVE, Domain.WATER_HEATER,
                Domain.ALARM_CONTROL_PANEL ->
                    raw.attributes["supported_features"].asInt() ?: 0
                else -> 0
            },
            vacuumBatteryLevel = if (domain == Domain.VACUUM)
                raw.attributes["battery_level"].asInt() else null,
            vacuumStatus = if (domain == Domain.VACUUM)
                raw.attributes["status"].asString() ?: stateStr else null,
            vacuumFanSpeed = if (domain == Domain.VACUUM)
                raw.attributes["fan_speed"].asString() else null,
            vacuumFanSpeedList = if (domain == Domain.VACUUM)
                extractStringList(raw.attributes["fan_speed_list"]) else emptyList(),
            climateHvacMode = if (domain == Domain.CLIMATE || domain == Domain.WATER_HEATER)
                (if (domain == Domain.CLIMATE) stateStr
                else raw.attributes["operation_mode"].asString()) else null,
            climateHvacModes = if (domain == Domain.CLIMATE)
                extractStringList(raw.attributes["hvac_modes"])
            else if (domain == Domain.WATER_HEATER)
                extractStringList(raw.attributes["operation_list"])
            else emptyList(),
            climateFanMode = if (domain == Domain.CLIMATE)
                raw.attributes["fan_mode"].asString() else null,
            climateFanModes = if (domain == Domain.CLIMATE)
                extractStringList(raw.attributes["fan_modes"]) else emptyList(),
            climatePresetMode = if (domain == Domain.CLIMATE)
                raw.attributes["preset_mode"].asString() else null,
            climatePresetModes = if (domain == Domain.CLIMATE)
                extractStringList(raw.attributes["preset_modes"]) else emptyList(),
            climateHvacAction = if (domain == Domain.CLIMATE)
                raw.attributes["hvac_action"].asString() else null,
            climateCurrentTemperature = if (domain == Domain.CLIMATE || domain == Domain.WATER_HEATER)
                raw.attributes["current_temperature"].asDouble() else null,
            climateTargetTemperature = if (domain == Domain.CLIMATE || domain == Domain.WATER_HEATER)
                raw.attributes["temperature"].asDouble() else null,
            climateTargetTempLow = if (domain == Domain.CLIMATE)
                raw.attributes["target_temp_low"].asDouble() else null,
            climateTargetTempHigh = if (domain == Domain.CLIMATE)
                raw.attributes["target_temp_high"].asDouble() else null,
            climateTempStep = if (domain == Domain.CLIMATE || domain == Domain.WATER_HEATER)
                raw.attributes["target_temp_step"].asDouble() else null,
            climateMinTemp = if (domain == Domain.CLIMATE || domain == Domain.WATER_HEATER)
                raw.attributes["min_temp"].asDouble() else null,
            climateMaxTemp = if (domain == Domain.CLIMATE || domain == Domain.WATER_HEATER)
                raw.attributes["max_temp"].asDouble() else null,
            temperatureUnit = if (domain == Domain.CLIMATE || domain == Domain.WATER_HEATER)
                raw.attributes["temperature_unit"].asString()
                    ?: raw.attributes["unit_of_measurement"].asString() else null,
            lockCodeFormat = if (domain == Domain.LOCK)
                raw.attributes["code_format"].asString() else null,
            lockChangedBy = if (domain == Domain.LOCK)
                raw.attributes["changed_by"].asString() else null,
            fanPercentageStep = if (domain == Domain.FAN)
                raw.attributes["percentage_step"].asDouble() else null,
            fanPresetMode = if (domain == Domain.FAN)
                raw.attributes["preset_mode"].asString() else null,
            fanPresetModes = if (domain == Domain.FAN)
                extractStringList(raw.attributes["preset_modes"]) else emptyList(),
            fanOscillating = if (domain == Domain.FAN)
                raw.attributes["oscillating"].asBoolean() else null,
            fanDirection = if (domain == Domain.FAN)
                raw.attributes["direction"].asString() else null,
            remoteCurrentActivity = if (domain == Domain.REMOTE)
                raw.attributes["current_activity"].asString() else null,
            remoteActivityList = if (domain == Domain.REMOTE)
                extractStringList(raw.attributes["activity_list"]) else emptyList(),
            alarmCodeFormat = if (domain == Domain.ALARM_CONTROL_PANEL)
                raw.attributes["code_format"].asString() else null,
            alarmCodeArmRequired = if (domain == Domain.ALARM_CONTROL_PANEL)
                (raw.attributes["code_arm_required"].asBoolean() ?: true) else true,
            alarmChangedBy = if (domain == Domain.ALARM_CONTROL_PANEL)
                raw.attributes["changed_by"].asString() else null,
            displayPrecision = if (domain == Domain.SENSOR)
                raw.attributes["display_precision"].asInt()
                    ?: raw.attributes["suggested_display_precision"].asInt()
            else null,
            sirenAvailableTones = if (domain == Domain.SIREN)
                extractStringList(raw.attributes["available_tones"]) else emptyList(),
            sirenVolumeLevel = if (domain == Domain.SIREN &&
                (raw.attributes["is_volume_controllable"].asBoolean() ?: false))
                raw.attributes["volume_level"].asDouble() else null,
        )
        cache.update { it + (id to newState) }
        // Heartbeat: any successfully-applied event means the WS path is alive. The
        // poller in [start] reads this to decide whether REST fallback is needed; the
        // About screen reads it to surface 'last event N seconds ago'.
        _lastEventAt.value = System.currentTimeMillis()
    }


    override fun observe(entities: Set<EntityId>): Flow<Map<EntityId, EntityState>> =
        cache.map { it.filterKeys { id -> id in entities } }
            // HA re-emits state_changed events whose to_state is byte-identical to the
            // last one for an entity (sensors that report the same reading on a fixed
            // poll interval are the common case). EntityState is a value-equals data
            // class, so the filtered subset compares equal and we can skip the whole
            // downstream materialize + recomposition for the no-op churn.
            .distinctUntilChanged()

    override fun observeRaw(entityIds: Set<String>): Flow<Map<String, EntityState>> {
        // Register this set for subscription + seeding. The collector wired in
        // [start] picks up the change and re-issues the WS trigger + REST seed so
        // these entities receive live state even when they aren't favourites.
        _dashboardEntityIds.value = entityIds
        return cache
            .map { current ->
                if (entityIds.isEmpty()) emptyMap()
                else current.entries
                    .filter { it.key.value in entityIds }
                    .associate { it.key.value to it.value }
            }
            .distinctUntilChanged()
    }

    override fun observeRawRows(entityIds: Set<String>): Flow<Map<String, RawEntityRow>> {
        // Same side effect as observeRaw: register the set for WS subscription +
        // REST seeding. Reads the domain-agnostic [rawCache] so unmodelled-domain
        // entities aren't dropped.
        _dashboardEntityIds.value = entityIds
        return rawCache
            .map { current ->
                if (entityIds.isEmpty()) emptyMap()
                else current.filterKeys { it in entityIds }
            }
            .distinctUntilChanged()
    }

    override suspend fun call(call: ServiceCall): Result<Unit> {
        // Read-only "guest mode": if the user has flipped the Settings toggle,
        // refuse every outbound service call and surface a toast/log so the
        // UX explains the silence. Observation paths (state subscriptions,
        // /api/states, history fetches) are unaffected — only this dispatch
        // entry is gated.
        val current = settings.settings.first()
        if (current.guestModeEnabled) {
            R1Log.i("HaRepo.guest", "blocked ${call.target.value}/${call.service} in guest mode")
            _callFailures.tryEmit(call.target)
            return Result.failure(IllegalStateException("Guest mode is on. Toggle it off in Settings to control your home."))
        }
        // Optimistic update was already applied by the ViewModel — the repo just forwards.
        // Key includes the service name so rapid taps of distinct buttons on the same
        // entity (PLAY then NEXT then VOL+ on a media_player) don't cancel each other.
        debouncer.submit(call.target to call.service, call)
        return Result.success(Unit)
    }

    override suspend fun listAllEntities(): Result<List<EntityState>> =
        fetchAndDecodeAllEntities(includeUnsupported = false)

    /** Like [listAllEntities] but keeps entities from domains the app has no card archetype
     *  for (device_tracker, zone, calendar, ...). Used by Universal Search so the user can find
     *  every entity they own, not just the ones the card stack can render. */
    override suspend fun listAllEntitiesForSearch(): Result<List<EntityState>> =
        fetchAndDecodeAllEntities(includeUnsupported = true)

    private suspend fun fetchAndDecodeAllEntities(
        includeUnsupported: Boolean,
    ): Result<List<EntityState>> = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.settings.first()
            val server = s.server ?: error("Server URL not configured. Sign out & reconnect from Settings.")
            // Pre-emptive refresh — if the cached access token is within 60 s of
            // expiry, swap it before issuing the call. Skips the round-trip-then-401
            // dance in the common case where the app's been idle ~30 minutes and the
            // user just opened the picker.
            refresher?.ensureFresh()
            // Try the request with the cached access token. On HTTP 401 (token expired
            // mid-app or in the background) ask the TokenRefresher for a fresh one and
            // retry once. Without this retry path the picker often greeted users with a
            // "401 for /api/states" error after the app sat idle past the 30-minute
            // access-token lifetime, even though the refresh-token was perfectly valid
            // and the WS pipeline would have self-healed via AuthLost. Restarting the
            // app worked because cold-start triggered a fresh auth flow; the retry here
            // gives that same recovery in-place without the user noticing.
            val body = fetchStatesBody(server.url) ?: run {
                if (refresher?.forceRefresh() == true) {
                    R1Log.i("HaRepo.listAll", "401 → refreshed access token; retrying once")
                    fetchStatesBody(server.url)
                        ?: error("Home Assistant returned HTTP 401 for /api/states even after refresh. Sign out & reconnect.")
                } else {
                    error("Home Assistant returned HTTP 401 for /api/states. Sign out & reconnect.")
                }
            }
            // Parse the response as a List<JsonElement> first, then decode each row
            // independently. The earlier `decodeFromString<List<RawStateRow>>` was an
            // all-or-nothing parse: a single weird row (state field missing, attributes
            // shape unexpected, etc.) would throw and the entire entity list would be lost.
            // That was almost certainly why scenes occasionally vanished from the picker —
            // some scene entries in HA's response had shapes the strict decoder didn't
            // accept. Per-row decoding with a try/catch keeps the rest of the list
            // available and lets us log the offenders rather than silently empty the UI.
            decodeStatesBody(
                body,
                includeUnsupported = includeUnsupported,
                strictDecode = s.advanced.strictEntityDecode,
            )
        }
    }

    override suspend fun listAllEntitiesRawPrefixCounts(): Result<Map<String, Int>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val s = settings.settings.first()
                val server = s.server ?: error("Server URL not configured.")
                refresher?.ensureFresh()
                val body = fetchStatesBody(server.url) ?: run {
                    if (refresher?.forceRefresh() == true) {
                        fetchStatesBody(server.url)
                            ?: error("Home Assistant returned HTTP 401 for /api/states even after refresh.")
                    } else {
                        error("Home Assistant returned HTTP 401 for /api/states. Sign out & reconnect.")
                    }
                }
                // Pull just the entity_id from each row by inspecting the raw JSON
                // object — no per-row decoder, no supported-domain filter. The diagnostic
                // needs to show what HA SENT, not what we kept; if media_player.* is in
                // here but missing from listAllEntities's result, that proves the filter
                // is the issue. If it's missing from BOTH, the problem is upstream
                // (HA-side permissions / entity-level visibility).
                val rowsJson = listStatesJson.decodeFromString<List<kotlinx.serialization.json.JsonElement>>(body)
                rowsJson
                    .mapNotNull {
                        val obj = it as? kotlinx.serialization.json.JsonObject
                        val eid = (obj?.get("entity_id") as? JsonPrimitive)?.content
                        eid?.substringBefore('.', missingDelimiterValue = "")
                    }
                    .filter { it.isNotBlank() }
                    .groupingBy { it }
                    .eachCount()
                    .toSortedMap()
            }
        }

    /**
     * Issue a GET to [url] with the current access token. Returns null on HTTP 401 so
     * the caller can refresh + retry; throws on any other non-success. Shared with the
     * [listAllEntities] / [fetchHistory] paths so 401 self-heal works the same way for
     * both.
     */
    private suspend fun fetchHistoryBody(url: String): String? = withContext(Dispatchers.IO) {
        val t = tokens.load() ?: error("Authentication tokens missing. Sign out & reconnect from Settings.")
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${t.accessToken}")
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401) return@withContext null
            require(resp.isSuccessful) { "Home Assistant returned HTTP ${resp.code} for /api/history" }
            resp.body!!.string()
        }
    }

    /**
     * One-shot REST `GET /api/states` returning the response body, or null on HTTP 401
     * so the caller can attempt a token refresh + retry. Any other HTTP failure throws
     * (the runCatching at the call site surfaces it). Always reads the access token
     * fresh from the [tokens] store so a retry after [TokenRefresher.forceRefresh]
     * picks up the newly-rotated value.
     */
    private suspend fun fetchStatesBody(serverUrl: String): String? = withContext(Dispatchers.IO) {
        val t = tokens.load() ?: error("Authentication tokens missing. Sign out & reconnect from Settings.")
        val req = Request.Builder()
            .url("${serverUrl.trimEnd('/')}/api/states")
            .header("Authorization", "Bearer ${t.accessToken}")
            .build()
        http.newCall(req).execute().use { resp ->
            // 401 is special — the caller decides whether to refresh + retry. Any
            // other non-success is a hard error (404 on a missing /api endpoint,
            // 500 from HA, network error, etc.) and gets reported as-is.
            if (resp.code == 401) return@withContext null
            require(resp.isSuccessful) { "Home Assistant returned HTTP ${resp.code} for /api/states" }
            resp.body!!.string()
        }
    }

    /**
     * Seeds the in-memory cache from a one-shot REST `GET /api/states` so the user sees current
     * values immediately after adding a favourite (subscribe_trigger only fires on the *next*
     * transition, so without this seed the card would sit at 0% until the user actually changes
     * the entity from elsewhere). Retries 3× with a short delay because the call right after
     * WS Connected sometimes races HA's REST stack on slow servers.
     */
    private suspend fun seedCacheFromHa() {
        // Seed favourites AND the dashboards renderer's current entity set, so a
        // dashboard card shows its current value immediately rather than sitting
        // blank until the entity next transitions. Unsupported-domain ids drop out
        // here (EntityId rejects them); that's the typed-cache limitation noted on
        // [_dashboardEntityIds].
        val favIds = (settings.settings.first().favorites + _dashboardEntityIds.value)
            .mapNotNull { runCatching { EntityId(it) }.getOrNull() }
            .toSet()
        if (favIds.isEmpty()) return
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            val result = listAllEntities()
            result.fold(
                onSuccess = { all ->
                    // If the user signed out while this REST call was in flight, drop the
                    // results on the floor — otherwise we'd repopulate the cache that the
                    // URL-change observer just cleared, bleeding server-A state into server-B.
                    if (settings.settings.first().server == null) {
                        R1Log.w("HaRepo.seed", "server gone mid-seed; discarding ${all.size} entities")
                        return
                    }
                    val byId = all.filter { it.id in favIds }.associateBy { it.id }
                    if (byId.isNotEmpty()) {
                        // Only toast on the FIRST successful seed (i.e. when the cache was
                        // previously empty). Doing the emptiness check INSIDE update {} closes
                        // the race window where two concurrent seeds would both see "empty"
                        // and both fire the toast.
                        var wasEmpty = false
                        cache.update { current ->
                            wasEmpty = current.isEmpty()
                            current + byId
                        }
                        R1Log.i("HaRepo.seed", "seeded ${byId.size}/${favIds.size} favourites (attempt ${attempt + 1})")
                        if (wasEmpty) {
                            com.github.itskenny0.r1ha.core.util.Toaster.show("Loaded ${byId.size} entities")
                        }
                    } else {
                        R1Log.w("HaRepo.seed", "REST returned ${all.size} entities but none matched favourites")
                    }
                    return
                },
                onFailure = { t ->
                    lastError = t
                    R1Log.w("HaRepo.seed", "attempt ${attempt + 1} failed: ${t.message}")
                    delay(500L * (attempt + 1)) // 500ms, 1s, 1.5s
                },
            )
        }
        val msg = lastError?.message ?: "unknown error"
        R1Log.e("HaRepo.seed", "all retries failed: $msg", lastError)
        com.github.itskenny0.r1ha.core.util.Toaster.error("Couldn't load entities: $msg")
    }

    /**
     * REST fallback poll used by the heartbeat in [start]. Differs from [seedCacheFromHa]
     * in being:
     *   - **Single-attempt**: no retries-with-delay loop. If REST is broken we'd rather
     *     fail silently and try again on the next heartbeat tick than stack three back-
     *     to-back retries inside one tick (which would extend the heartbeat to ~3 s in
     *     practice and bury the next legitimate tick).
     *   - **Silent**: no Toaster.error on failure, no "Loaded N entities" toast on the
     *     first successful poll. This runs in the background every 30 s while the WS is
     *     silent; users would be drowning in toasts on a perma-broken reverse proxy.
     *     Failures still log through R1Log so they're recoverable from the in-app log
     *     viewer.
     *
     * Note we still update _lastEventAt on success so a stretch of working REST polls
     * keeps the heartbeat from re-firing every tick — the WS being broken doesn't mean
     * the REST cache needs continual refresh; one good poll per 30 s is plenty.
     */
    private suspend fun silentRefreshFromHa() {
        val favIds = settings.settings.first().favorites
            .mapNotNull { runCatching { EntityId(it) }.getOrNull() }
            .toSet()
        if (favIds.isEmpty()) return
        val result = listAllEntities()
        result.fold(
            onSuccess = { all ->
                if (settings.settings.first().server == null) {
                    R1Log.w("HaRepo.heartbeat", "server gone mid-poll; discarding ${all.size} entities")
                    return
                }
                val byId = all.filter { it.id in favIds }.associateBy { it.id }
                if (byId.isNotEmpty()) {
                    cache.update { current -> current + byId }
                    R1Log.i("HaRepo.heartbeat", "REST refresh updated ${byId.size}/${favIds.size} favourites")
                    // The successful poll counts as a useful signal — back off until the
                    // next genuine silence window.
                    _lastEventAt.value = System.currentTimeMillis()
                } else {
                    R1Log.w("HaRepo.heartbeat", "REST returned ${all.size} entities; none matched favourites")
                }
            },
            onFailure = { t ->
                R1Log.w("HaRepo.heartbeat", "REST poll failed: ${t.message}")
            },
        )
    }

    /** Single Json instance for /api/states deserialisation to avoid the per-call allocation lint. */
    private val listStatesJson = Json { ignoreUnknownKeys = true }

    override suspend fun fetchHistory(entityId: EntityId, hours: Int): Result<List<HistoryPoint>> =
        withContext(Dispatchers.IO) {
            // Retry on failure with exponential backoff. The auth breaker returns a synthetic
            // 503 for /api/history the whole time it is tripped, and the SensorCard /
            // HistoryGraphCard fetch is a one-shot — so without this a single transient or
            // unrelated 401 (a token rotation, or a stale-token camera poll feeding the now-
            // shared breaker) left the chart permanently blank until the card was re-opened.
            // While the breaker is open the retry is short-circuited locally (no HA traffic,
            // so no failed-login risk); the first attempt after the cooldown elapses becomes
            // the breaker's half-open probe, whose success both loads the chart and closes the
            // breaker for every client. A genuine empty history (HA simply has no samples) is
            // a *success* and returns immediately without burning the retry budget.
            var lastFailure: Throwable? = null
            for (attempt in 0..HISTORY_FETCH_RETRIES) {
                if (attempt > 0) delay(historyRetryBackoffMillis shl (attempt - 1))
                val result = runCatching { fetchHistoryOnce(entityId, hours) }
                result.fold(
                    onSuccess = { return@withContext Result.success(it) },
                    onFailure = { t ->
                        // A cancelled fetch (the card was scrolled out of view) must propagate
                        // to cancel the coroutine, never be retried.
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        lastFailure = t
                    },
                )
            }
            val t = lastFailure ?: IllegalStateException("history fetch failed without a cause")
            // 'left the composition' is Compose's lifecycle-scope cancellation wording; log it
            // at DEBUG so it doesn't spam the toast feed and bury genuine failures.
            if (t.message?.contains("left the composition", ignoreCase = true) == true) {
                R1Log.d("HaRepo.fetchHistory", "${entityId.value}: cancelled (card no longer composed)")
            } else {
                R1Log.w("HaRepo.fetchHistory", "${entityId.value}: ${t.message}")
            }
            Result.failure(t)
        }

    override suspend fun fetchLocationHistory(entityId: EntityId, hours: Int): Result<List<LocationFix>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val s = settings.settings.first()
                val server = s.server ?: error("Server URL not configured.")
                refresher?.ensureFresh()
                val since = Instant.now().minusSeconds(hours.toLong() * 3600L)
                // Keep the attribute payload (no `no_attributes`) so latitude /
                // longitude survive; `minimal_response` would strip them after the
                // first sample, so it is intentionally omitted here.
                val url = "${server.url.trimEnd('/')}/api/history/period/$since" +
                    "?filter_entity_id=${java.net.URLEncoder.encode(entityId.value, "UTF-8")}"
                val body = fetchHistoryBody(url) ?: run {
                    if (refresher?.forceRefresh() == true) fetchHistoryBody(url) else null
                } ?: error("Home Assistant returned no history for ${entityId.value}")
                val outer = listStatesJson.decodeFromString<List<List<LocationHistoryRow>>>(body)
                outer.firstOrNull().orEmpty().mapNotNull { row ->
                    val lat = row.attributes?.get("latitude")?.let { (it as? JsonPrimitive)?.doubleOrNull }
                    val lon = row.attributes?.get("longitude")?.let { (it as? JsonPrimitive)?.doubleOrNull }
                    val ts = row.last_changed ?: row.last_updated
                    if (lat != null && lon != null && ts != null) {
                        parseHaInstant(ts)?.let { LocationFix(it, lat, lon) }
                    } else {
                        null
                    }
                }
            }.onFailure { t ->
                if (t is kotlinx.coroutines.CancellationException) throw t
                R1Log.d("HaRepo.fetchLocationHistory", "${entityId.value}: ${t.message}")
            }
        }

    /** Row shape for the attribute-bearing location history (keeps `attributes`). */
    @kotlinx.serialization.Serializable
    private data class LocationHistoryRow(
        val attributes: kotlinx.serialization.json.JsonObject? = null,
        val last_changed: String? = null,
        val last_updated: String? = null,
    )

    /** One attempt of the [fetchHistory] REST GET + parse. Throws on any failure (HTTP error,
     *  breaker short-circuit 503, parse error); the caller's retry loop decides whether to
     *  re-attempt. */
    private suspend fun fetchHistoryOnce(entityId: EntityId, hours: Int): List<HistoryPoint> {
        val s = settings.settings.first()
        val server = s.server ?: error("Server URL not configured.")
        // Pre-emptive refresh on near-expiry — same reasoning as listAllEntities.
        refresher?.ensureFresh()
        val since = Instant.now().minusSeconds(hours.toLong() * 3600L)
        // HA's history endpoint takes the ISO timestamp in the URL path. URL-encode
        // the entity_id even though current HA versions don't require it — defensive
        // against entity_ids that contain unusual characters in future versions.
        val sinceIso = since.toString()
        val url = "${server.url.trimEnd('/')}/api/history/period/$sinceIso" +
            "?filter_entity_id=${java.net.URLEncoder.encode(entityId.value, "UTF-8")}" +
            "&minimal_response&no_attributes"
        // Same 401 → refresh → retry path as listAllEntities — sensor charts
        // fired silently in the background while the user was on the card stack
        // were a common trigger for "history failed; chart blank" until the user
        // restarted the app.
        val body = fetchHistoryBody(url) ?: run {
            if (refresher?.forceRefresh() == true) {
                R1Log.i("HaRepo.fetchHistory", "401 → refreshed access token; retrying once")
                fetchHistoryBody(url)
                    ?: error("Home Assistant returned HTTP 401 for /api/history even after refresh. Sign out & reconnect.")
            } else {
                error("Home Assistant returned HTTP 401 for /api/history. Sign out & reconnect.")
            }
        }
        // HA returns a JSON array of arrays — outermost level is one entry per
        // requested entity (we only ask for one). Each inner entry is a state
        // snapshot. `minimal_response` strips the attribute payload after the
        // first sample which keeps the response small and parse fast.
        val outer = listStatesJson.decodeFromString<List<List<HistoryRow>>>(body)
        val first = outer.firstOrNull().orEmpty()
        return first.mapNotNull { row ->
            val state = row.state ?: return@mapNotNull null
            val ts = row.last_changed ?: row.last_updated ?: return@mapNotNull null
            val instant = parseHaInstant(ts) ?: return@mapNotNull null
            HistoryPoint.fromRaw(state, instant)
        }
    }

    /** Minimal row shape for /api/history; uses `minimal_response` so attributes are absent
     *  after the first sample. Both timestamp fields are nullable because HA omits one or
     *  the other depending on whether the sample is the first in the window. */
    @kotlinx.serialization.Serializable
    private data class HistoryRow(
        val state: String? = null,
        val last_changed: String? = null,
        val last_updated: String? = null,
    )

    override suspend fun conversationProcess(
        text: String,
        language: String?,
        conversationId: String?,
        agentId: String?,
    ): Result<ConversationResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.settings.first()
            val server = s.server ?: error("Server URL not configured.")
            refresher?.ensureFresh()
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("text", JsonPrimitive(text))
                if (!language.isNullOrBlank()) put("language", JsonPrimitive(language))
                if (!conversationId.isNullOrBlank()) put("conversation_id", JsonPrimitive(conversationId))
                // agent_id routes to a specific conversation agent. HA picks
                // its default when omitted; passing it lets the user steer
                // between multiple configured back-ends (OpenAI, local Llama,
                // Google, etc.) from the same Assist surface.
                if (!agentId.isNullOrBlank()) put("agent_id", JsonPrimitive(agentId))
            }
            val url = "${server.url.trimEnd('/')}/api/conversation/process"
            val body = conversationCallBody(url, payload) ?: run {
                if (refresher?.forceRefresh() == true) {
                    R1Log.i("HaRepo.conversation", "401 → refreshed; retrying once")
                    conversationCallBody(url, payload)
                        ?: error("Home Assistant returned HTTP 401 for /api/conversation/process after refresh.")
                } else {
                    error("Home Assistant returned HTTP 401 for /api/conversation/process.")
                }
            }
            // Response shape: { response: { speech: { plain: { speech: "…" } },
            // response_type: "action_done" | "query_answer" | "error", … },
            // conversation_id: "…" }
            val root = kotlinx.serialization.json.Json.parseToJsonElement(body)
                as? kotlinx.serialization.json.JsonObject
                ?: error("Unexpected conversation response shape")
            val convId = (root["conversation_id"] as? JsonPrimitive)?.content
            val response = root["response"] as? kotlinx.serialization.json.JsonObject
            val responseType = (response?.get("response_type") as? JsonPrimitive)?.content
            val speech = response
                ?.get("speech")?.let { it as? kotlinx.serialization.json.JsonObject }
                ?.get("plain")?.let { it as? kotlinx.serialization.json.JsonObject }
                ?.get("speech")?.let { (it as? JsonPrimitive)?.content }
                ?: "(no response)"
            ConversationResponse(
                speech = speech,
                conversationId = convId,
                responseType = responseType,
            )
        }.onFailure { t ->
            R1Log.w("HaRepo.conversation", "process failed: ${t.message}")
        }
    }

    override suspend fun fetchLogbook(hours: Int): Result<List<LogbookEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val s = settings.settings.first()
                val server = s.server ?: error("Server URL not configured.")
                refresher?.ensureFresh()
                val since = Instant.now().minusSeconds(hours.toLong() * 3600L)
                val sinceIso = since.toString()
                // HA accepts the start time in the path; `end_time` is omitted so the
                // endpoint defaults to "now". The user wants the most recent activity,
                // so we let HA's default end window catch any events that landed in
                // the milliseconds since the request was constructed.
                val url = "${server.url.trimEnd('/')}/api/logbook/$sinceIso"
                val body = fetchHistoryBody(url) ?: run {
                    if (refresher?.forceRefresh() == true) {
                        R1Log.i("HaRepo.logbook", "401 → refreshed; retrying once")
                        fetchHistoryBody(url)
                            ?: error("Home Assistant returned HTTP 401 for /api/logbook after refresh.")
                    } else {
                        error("Home Assistant returned HTTP 401 for /api/logbook.")
                    }
                }
                // The endpoint returns a flat JSON array. We allow unknown keys
                // because HA includes context_id / context_user_id / message
                // depending on integration version — we only consume a fixed subset.
                val rows = logbookJson.decodeFromString<List<LogbookRow>>(body)
                rows.mapNotNull { row ->
                    val ts = row.`when`
                        ?: return@mapNotNull null
                    val instant = parseHaInstant(ts)
                        ?: return@mapNotNull null
                    val entityId = row.entity_id?.let { raw ->
                        // Defensive — HA can include entity_ids from domains we
                        // don't model (weather.*, person.*). Skip the EntityId
                        // constructor's domain-validation by trying it inside a
                        // runCatching; on miss, surface the event without a
                        // structured entity reference.
                        runCatching { EntityId(raw) }.getOrNull()
                    }
                    LogbookEntry(
                        timestamp = instant,
                        name = row.name ?: row.entity_id ?: "(unknown)",
                        message = row.message ?: "changed",
                        entityId = entityId,
                        domain = row.domain ?: row.entity_id?.substringBefore('.'),
                        state = row.state,
                        contextUserId = row.context_user_id,
                        contextEntityId = row.context_entity_id,
                        // HA's frontend resolves a friendly label under
                        // `context_entity_id_name`; some payloads use the
                        // user-facing `context_name` instead. Prefer the
                        // entity-scoped label, fall back to the generic one.
                        contextName = row.context_entity_id_name ?: row.context_name,
                    )
                }.sortedByDescending { it.timestamp } // newest first
            }.onFailure { t ->
                R1Log.w("HaRepo.logbook", "fetch failed: ${t.message}")
            }
        }

    override suspend fun fetchLogbookForEntity(
        entityId: String,
        hours: Int,
    ): Result<List<LogbookEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.settings.first()
            val server = s.server ?: error("Server URL not configured.")
            refresher?.ensureFresh()
            val since = Instant.now().minusSeconds(hours.toLong() * 3600L)
            val sinceIso = since.toString()
            // HA's logbook endpoint accepts an `entity` query param to scope the
            // result server-side, so the sheet doesn't pull the whole install's
            // log just to filter to one entity.
            val url = "${server.url.trimEnd('/')}/api/logbook/$sinceIso" +
                "?entity=${java.net.URLEncoder.encode(entityId, "UTF-8")}"
            val body = fetchHistoryBody(url) ?: run {
                if (refresher?.forceRefresh() == true) {
                    fetchHistoryBody(url)
                        ?: error("Home Assistant returned HTTP 401 for /api/logbook after refresh.")
                } else {
                    error("Home Assistant returned HTTP 401 for /api/logbook.")
                }
            }
            decodeLogbookRows(body)
        }.onFailure { t ->
            R1Log.w("HaRepo.logbook", "entity fetch failed: ${t.message}")
        }
    }

    /** Shared decode for the /api/logbook JSON body — used by both the
     *  whole-install [fetchLogbook] and the per-entity [fetchLogbookForEntity]. */
    private fun decodeLogbookRows(body: String): List<LogbookEntry> {
        val rows = logbookJson.decodeFromString<List<LogbookRow>>(body)
        return rows.mapNotNull { row ->
            val ts = row.`when` ?: return@mapNotNull null
            val instant = parseHaInstant(ts) ?: return@mapNotNull null
            val entityId = row.entity_id?.let { raw ->
                runCatching { EntityId(raw) }.getOrNull()
            }
            LogbookEntry(
                timestamp = instant,
                name = row.name ?: row.entity_id ?: "(unknown)",
                message = row.message ?: "changed",
                entityId = entityId,
                domain = row.domain ?: row.entity_id?.substringBefore('.'),
                state = row.state,
                contextUserId = row.context_user_id,
                contextEntityId = row.context_entity_id,
                contextName = row.context_entity_id_name ?: row.context_name,
            )
        }.sortedByDescending { it.timestamp }
    }

    /** Minimal row shape for /api/logbook. Every field is nullable
     *  because HA's logbook payloads vary by event type — an automation
     *  trigger row often lacks `state` but has `message`, a state-change
     *  row has both. `when` is the JSON key HA uses and is the only
     *  field we treat as required (skipping rows without it). */
    @kotlinx.serialization.Serializable
    private data class LogbookRow(
        val `when`: String? = null,
        val name: String? = null,
        val message: String? = null,
        val entity_id: String? = null,
        val domain: String? = null,
        val state: String? = null,
        // "Triggered by" context HA attaches to each row: the originating user
        // account, the originating entity, and (when HA can resolve them) their
        // human-readable labels. All optional — older HA versions and some event
        // types omit the context block entirely.
        val context_user_id: String? = null,
        val context_entity_id: String? = null,
        val context_entity_id_name: String? = null,
        val context_name: String? = null,
    )

    /** Lenient JSON for /api/logbook — same shape as [listStatesJson]: ignore
     *  fields HA adds in newer versions (context_id, context_user_id, icon).
     *  Defined as a property to keep the decoder hot rather than rebuilding
     *  it per call. */
    private val logbookJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    override suspend fun listPersistentNotifications(): Result<List<PersistentNotification>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val rows = fetchRawRowsForDomain("persistent_notification")
                rows.mapNotNull { row ->
                    val notificationId = row.entityId.substringAfter('.', "")
                        .takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val title = (row.attributes["title"] as? JsonPrimitive)?.content
                    // HA omits `message` for some auto-generated notifications;
                    // fall back to the raw state which holds the message body.
                    val message = (row.attributes["message"] as? JsonPrimitive)?.content
                        ?: row.state
                    val createdRaw = (row.attributes["created_at"] as? JsonPrimitive)?.content
                    val createdAt = createdRaw?.let { parseHaInstant(it) }
                    PersistentNotification(
                        notificationId = notificationId,
                        title = title,
                        message = message,
                        createdAt = createdAt,
                    )
                }.sortedByDescending { it.createdAt ?: Instant.EPOCH }
            }.onFailure { t ->
                R1Log.w("HaRepo.notifs", "list failed: ${t.message}")
            }
        }

    override suspend fun dismissPersistentNotification(id: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = kotlinx.serialization.json.buildJsonObject {
                    put("notification_id", JsonPrimitive(id))
                }
                callRawService("persistent_notification", "dismiss", payload).getOrThrow()
                Unit
            }.onFailure { t ->
                R1Log.w("HaRepo.notifs", "dismiss $id failed: ${t.message}")
            }
        }

    override suspend fun listRawEntitiesByDomain(domainPrefix: String): Result<List<RawEntityRow>> =
        withContext(Dispatchers.IO) {
            runCatching {
                fetchRawRowsForDomain(domainPrefix)
            }.onFailure { t ->
                R1Log.w("HaRepo.raw", "$domainPrefix fetch failed: ${t.message}")
            }
        }

    override suspend fun fetchHaConfig(): Result<HaConfig> = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.settings.first()
            val server = s.server ?: error("Server URL not configured.")
            refresher?.ensureFresh()
            val url = "${server.url.trimEnd('/')}/api/config"
            val body = simpleAuthedGet(url) ?: run {
                if (refresher?.forceRefresh() == true) {
                    R1Log.i("HaRepo.config", "401 → refreshed; retrying once")
                    simpleAuthedGet(url)
                        ?: error("Home Assistant returned HTTP 401 for /api/config after refresh.")
                } else {
                    error("Home Assistant returned HTTP 401 for /api/config.")
                }
            }
            val root = listStatesJson.decodeFromString<kotlinx.serialization.json.JsonObject>(body)
            HaConfig(
                version = (root["version"] as? JsonPrimitive)?.content,
                locationName = (root["location_name"] as? JsonPrimitive)?.content,
                timeZone = (root["time_zone"] as? JsonPrimitive)?.content,
                elevation = (root["elevation"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                unitSystem = (root["unit_system"] as? kotlinx.serialization.json.JsonObject)
                    ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }
                    ?.toMap()
                    .orEmpty(),
                internalUrl = (root["internal_url"] as? JsonPrimitive)?.content,
                externalUrl = (root["external_url"] as? JsonPrimitive)?.content,
                components = (root["components"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    ?.sorted()
                    .orEmpty(),
            )
        }.onFailure { t ->
            R1Log.w("HaRepo.config", "fetch failed: ${t.message}")
        }
    }

    override suspend fun fetchCurrentUser(): Result<HaCurrentUser?> = withContext(Dispatchers.IO) {
        // `auth/current_user` is available to any authenticated token (unlike the
        // admin-only `config/auth/list`). An older server that doesn't recognise
        // it returns an error frame; we map that to a null result so callers
        // degrade silently rather than logging a scary failure.
        callWsExpectingPayload("auth/current_user").map { payload ->
            val obj = payload as? kotlinx.serialization.json.JsonObject ?: return@map null
            val id = (obj["id"] as? JsonPrimitive)?.content ?: return@map null
            HaCurrentUser(
                id = id,
                name = (obj["name"] as? JsonPrimitive)?.content.orEmpty(),
                isAdmin = (obj["is_admin"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }.recover { t ->
            // A command the server doesn't support, or a transient WS error: treat
            // as "unknown user" without surfacing a failure to the caller.
            R1Log.w("HaRepo.currentUser", "fetch failed: ${t.message}")
            null
        }
    }

    /** Refresh [_currentUserId] from the server. Best-effort: any failure leaves
     *  the cache as-is null so user/location conditions fail closed. */
    private suspend fun refreshCurrentUser() {
        val user = fetchCurrentUser().getOrNull()
        _currentUserId.value = user?.id
        _currentUserName.value = user?.name?.takeIf { it.isNotBlank() }
    }

    override suspend fun listServices(): Result<List<HaServiceDomain>> = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.settings.first()
            val server = s.server ?: error("Server URL not configured.")
            refresher?.ensureFresh()
            val url = "${server.url.trimEnd('/')}/api/services"
            val body = simpleAuthedGet(url) ?: run {
                if (refresher?.forceRefresh() == true) {
                    simpleAuthedGet(url) ?: error("HTTP 401 for /api/services after refresh.")
                } else error("HTTP 401 for /api/services.")
            }
            // HA's response: a JSON array of {domain: String, services: {name: {description, fields}}}.
            val arr = listStatesJson.decodeFromString<kotlinx.serialization.json.JsonArray>(body)
            arr.mapNotNull { el ->
                val obj = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val domain = (obj["domain"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val servicesObj = obj["services"] as? kotlinx.serialization.json.JsonObject
                    ?: return@mapNotNull null
                val services = servicesObj.entries.map { (name, value) ->
                    val svcObj = value as? kotlinx.serialization.json.JsonObject
                    val description = (svcObj?.get("description") as? JsonPrimitive)?.content
                    val fieldsObj = svcObj?.get("fields") as? kotlinx.serialization.json.JsonObject
                    val fieldNames = fieldsObj?.keys?.toList().orEmpty()
                    HaService(name = name, description = description, fieldNames = fieldNames)
                }.sortedBy { it.name }
                HaServiceDomain(domain = domain, services = services)
            }.sortedBy { it.domain }
        }.onFailure { t ->
            R1Log.w("HaRepo.services", "list failed: ${t.message}")
        }
    }

    override suspend fun fetchCalendarEvents(
        entityId: String,
        fromDaysBack: Int,
        toDaysAhead: Int,
    ): Result<List<CalendarEvent>> = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.settings.first()
            val server = s.server ?: error("Server URL not configured.")
            refresher?.ensureFresh()
            val now = Instant.now()
            val start = now.minusSeconds(fromDaysBack.toLong() * 86_400L)
            val end = now.plusSeconds(toDaysAhead.toLong() * 86_400L)
            val url = "${server.url.trimEnd('/')}/api/calendars/$entityId" +
                "?start=${start.toString()}&end=${end.toString()}"
            val body = simpleAuthedGet(url) ?: run {
                if (refresher?.forceRefresh() == true) {
                    simpleAuthedGet(url)
                        ?: error("Home Assistant returned HTTP 401 for /api/calendars after refresh.")
                } else {
                    error("Home Assistant returned HTTP 401 for /api/calendars.")
                }
            }
            val arr = listStatesJson.decodeFromString<kotlinx.serialization.json.JsonArray>(body)
            arr.mapNotNull { el ->
                val obj = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val summary = (obj["summary"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val startEl = obj["start"]
                val endEl = obj["end"]
                val (startInstant, isAllDay) = parseCalDate(startEl)
                val (endInstant, _) = parseCalDate(endEl)
                CalendarEvent(
                    summary = summary,
                    start = startInstant,
                    end = endInstant,
                    allDay = isAllDay,
                    location = (obj["location"] as? JsonPrimitive)?.content,
                    description = (obj["description"] as? JsonPrimitive)?.content,
                )
            }.sortedBy { it.start ?: Instant.MAX }
        }.onFailure { t ->
            R1Log.w("HaRepo.calendar", "$entityId fetch failed: ${t.message}")
        }
    }

    /** HA's event boundary shapes:
     *   { dateTime: "2026-05-14T18:00:00+02:00" } — timed event
     *   { date: "2026-05-14" }                   — all-day event
     *  Returns the parsed [Instant] (UTC midnight for all-day) and the
     *  all-day flag for the UI to render appropriately. */
    private fun parseCalDate(el: kotlinx.serialization.json.JsonElement?): Pair<Instant?, Boolean> {
        val obj = el as? kotlinx.serialization.json.JsonObject ?: return null to false
        (obj["dateTime"] as? JsonPrimitive)?.content?.let { dt ->
            return parseHaInstant(dt) to false
        }
        (obj["date"] as? JsonPrimitive)?.content?.let { date ->
            // All-day events in HA are local-date strings (no timezone). Resolve them against
            // the device's system zone so events show on the correct calendar day; forcing
            // UTC midnight made e.g. a 2026-05-19 event show on 2026-05-18 in UTC-5 zones.
            val parsed = runCatching {
                java.time.LocalDate.parse(date)
                    .atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant()
            }.getOrNull()
            return parsed to true
        }
        return null to false
    }

    override suspend fun fetchErrorLog(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.settings.first()
            val server = s.server ?: error("Server URL not configured.")
            refresher?.ensureFresh()
            val url = "${server.url.trimEnd('/')}/api/error_log"
            // Stream the body and keep only the last 32 KB instead of materialising
            // the whole response. HA's error log can be tens of MB on a misbehaving
            // install, and the previous `resp.body.string() then takeLast()` shape
            // allocated the entire body before truncating, which crashed the app
            // with OOM on the 512MB-heap R1 when the log got pathological.
            val maxBytes = 32 * 1024
            val body = simpleAuthedGetTail(url, maxBytes) ?: run {
                if (refresher?.forceRefresh() == true) {
                    simpleAuthedGetTail(url, maxBytes)
                        ?: error("Home Assistant returned HTTP 401 for /api/error_log after refresh.")
                } else {
                    error("Home Assistant returned HTTP 401 for /api/error_log.")
                }
            }
            body
        }.onFailure { t ->
            R1Log.w("HaRepo.errorLog", "fetch failed: ${t.message}")
        }
    }

    /**
     * Stream a body and keep the last [maxBytes] only. Uses an
     * `okio.Buffer` as a sliding window — every 4 KB read appends to
     * the buffer; once the buffer exceeds maxBytes we `skip()` the
     * excess off the front. Memory is bounded by maxBytes + 4 KB
     * regardless of upstream size.
     */
    private suspend fun simpleAuthedGetTail(url: String, maxBytes: Int): String? =
        withContext(Dispatchers.IO) {
            val t = tokens.load()
                ?: error("Authentication tokens missing. Sign out & reconnect from Settings.")
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${t.accessToken}")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 401) return@withContext null
                require(resp.isSuccessful) { "HTTP ${resp.code} for $url" }
                val source = resp.body?.source() ?: return@withContext ""
                val window = okio.Buffer()
                val tmp = okio.Buffer()
                val chunk = 4 * 1024L
                var totalRead = 0L
                while (true) {
                    val n = source.read(tmp, chunk)
                    if (n == -1L) break
                    totalRead += n
                    tmp.readAll(window)
                    val over = window.size - maxBytes
                    if (over > 0) window.skip(over)
                }
                val truncated = totalRead > window.size
                val tail = window.readUtf8()
                if (truncated) "… (truncated to last $maxBytes chars)\n$tail" else tail
            }
        }

    /** Bearer-authed GET — returns the body as a String, or null on HTTP
     *  401 (so the caller can refresh + retry). Used by surfaces that
     *  don't fit the existing fetchStatesBody / fetchHistoryBody helpers
     *  (config, error_log). */
    private suspend fun simpleAuthedGet(url: String): String? = withContext(Dispatchers.IO) {
        val t = tokens.load()
            ?: error("Authentication tokens missing. Sign out & reconnect from Settings.")
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${t.accessToken}")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401) return@withContext null
            require(resp.isSuccessful) { "HTTP ${resp.code} for $url" }
            resp.body?.string().orEmpty()
        }
    }

    /** Shared raw-row fetcher used by the not-in-Domain-enum surfaces
     *  ([listPersistentNotifications], [listRawEntitiesByDomain] for
     *  cameras / persons / weather / calendars). Calls `/api/states`
     *  with the 401-refresh-retry pattern and filters rows whose
     *  entity_id starts with `<domainPrefix>.`. Returns a stable
     *  [RawEntityRow] shape regardless of the HA-side domain — callers
     *  pick the attributes they care about. */
    private suspend fun fetchRawRowsForDomain(domainPrefix: String): List<RawEntityRow> {
        val s = settings.settings.first()
        val server = s.server ?: error("Server URL not configured.")
        refresher?.ensureFresh()
        val body = fetchStatesBody(server.url) ?: run {
            if (refresher?.forceRefresh() == true) {
                R1Log.i("HaRepo.raw", "401 → refreshed; retrying once")
                fetchStatesBody(server.url)
                    ?: error("Home Assistant returned HTTP 401 for /api/states even after refresh.")
            } else {
                error("Home Assistant returned HTTP 401 for /api/states. Sign out & reconnect.")
            }
        }
        val rowsJson = listStatesJson.decodeFromString<List<kotlinx.serialization.json.JsonElement>>(body)
        val prefixDot = "$domainPrefix."
        return rowsJson.mapNotNull { el ->
            val obj = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val eid = (obj["entity_id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            if (!eid.startsWith(prefixDot)) return@mapNotNull null
            val state = (obj["state"] as? JsonPrimitive)?.content ?: ""
            val attrs = (obj["attributes"] as? kotlinx.serialization.json.JsonObject)
                ?: kotlinx.serialization.json.JsonObject(emptyMap())
            val friendly = (attrs["friendly_name"] as? JsonPrimitive)?.content ?: eid
            // last_changed is ISO-8601 from HA; runCatching guards
            // against malformed strings (some integrations emit
            // 'unavailable' or omit the field).
            val lastChanged = (obj["last_changed"] as? JsonPrimitive)?.content
                ?.let { parseHaInstant(it) }
            RawEntityRow(
                entityId = eid,
                friendlyName = friendly,
                state = state,
                attributes = attrs,
                lastChanged = lastChanged,
            )
        }
    }

    override suspend fun fireEvent(
        eventType: String,
        data: kotlinx.serialization.json.JsonObject,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(eventType.matches(Regex("[a-z0-9_]+"))) { "Invalid event_type: '$eventType'" }
            val s = settings.settings.first()
            if (s.guestModeEnabled) {
                error("Guest mode is on. Toggle it off in Settings to fire events.")
            }
            val server = s.server ?: error("Server URL not configured.")
            refresher?.ensureFresh()
            val url = "${server.url.trimEnd('/')}/api/events/$eventType"
            val body = serviceCallRawBody(url, data) ?: run {
                if (refresher?.forceRefresh() == true) {
                    R1Log.i("HaRepo.evt", "401 → refreshed; retrying once")
                    serviceCallRawBody(url, data)
                        ?: error("Home Assistant returned HTTP 401 for /api/events after refresh.")
                } else {
                    error("Home Assistant returned HTTP 401 for /api/events.")
                }
            }
            body
        }.onFailure { t ->
            R1Log.w("HaRepo.evt", "$eventType failed: ${t.message}")
        }
    }

    override suspend fun callRawService(
        domain: String,
        service: String,
        data: kotlinx.serialization.json.JsonObject,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(domain.matches(Regex("[a-z0-9_]+"))) { "Invalid service domain: '$domain'" }
            require(service.matches(Regex("[a-z0-9_]+"))) { "Invalid service name: '$service'" }
            val s = settings.settings.first()
            if (s.guestModeEnabled) {
                error("Guest mode is on. Toggle it off in Settings to call services.")
            }
            val server = s.server ?: error("Server URL not configured.")
            refresher?.ensureFresh()
            val url = "${server.url.trimEnd('/')}/api/services/$domain/$service"
            val body = serviceCallRawBody(url, data) ?: run {
                if (refresher?.forceRefresh() == true) {
                    R1Log.i("HaRepo.svc", "401 → refreshed; retrying once")
                    serviceCallRawBody(url, data)
                        ?: error("Home Assistant returned HTTP 401 for /api/services after refresh.")
                } else {
                    error("Home Assistant returned HTTP 401 for /api/services.")
                }
            }
            // /api/services/<d>/<s> returns a JSON array of the state changes
            // it produced. We forward it verbatim so the user can see what
            // HA actually did — empty array = no state mutated (still a
            // success on HA's side, often what you want for fire-and-forget
            // services like `automation.reload`).
            body
        }.onFailure { t ->
            R1Log.w("HaRepo.svc", "$domain.$service failed: ${t.message}")
        }
    }

    /** POST to /api/services/<domain>/<service>. Returns null on HTTP 401
     *  for the refresh + retry pattern; HTTP 400 surfaces HA's error body
     *  as an exception so the Service Caller screen can show it. */
    private suspend fun serviceCallRawBody(
        url: String,
        payload: kotlinx.serialization.json.JsonObject,
    ): String? = withContext(Dispatchers.IO) {
        val t = tokens.load()
            ?: error("Authentication tokens missing. Sign out & reconnect from Settings.")
        val mediaType = "application/json".toMediaTypeOrNull()
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${t.accessToken}")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401) return@withContext null
            val responseBody = resp.body?.string().orEmpty()
            require(resp.isSuccessful) {
                if (responseBody.isNotBlank()) responseBody.trim()
                else "Home Assistant returned HTTP ${resp.code} for the service call"
            }
            responseBody
        }
    }

    override suspend fun getWeatherForecasts(
        entityId: String,
        type: String,
    ): Result<kotlinx.serialization.json.JsonElement> = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.settings.first()
            // get_forecasts is a read — guest mode gates writes, not reads, so a
            // guest holding the device still sees the weather screen's forecast.
            val server = s.server ?: error("Server URL not configured.")
            refresher?.ensureFresh()
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("entity_id", JsonPrimitive(entityId))
                put("type", JsonPrimitive(type))
            }
            // HA rejects weather.get_forecasts with HTTP 400 unless the REST call
            // carries return_response — the param flips the body from the produced
            // state changes to the service's response data.
            val url = "${server.url.trimEnd('/')}/api/services/weather/get_forecasts?return_response=true"
            val body = serviceCallRawBody(url, payload) ?: run {
                if (refresher?.forceRefresh() == true) {
                    serviceCallRawBody(url, payload)
                        ?: error("HTTP 401 for weather.get_forecasts after refresh.")
                } else {
                    error("HTTP 401 for weather.get_forecasts.")
                }
            }
            // The response body looks like:
            // {"changed_states":[...],"service_response":{"weather.home":{"forecast":[...]}}}
            // Return the per-entity object ({"forecast":[...]}) verbatim so the
            // caller can run it through the existing forecast-entry parser.
            val root = listStatesJson.decodeFromString<kotlinx.serialization.json.JsonObject>(body)
            val serviceResponse = root["service_response"] as? kotlinx.serialization.json.JsonObject
                ?: kotlinx.serialization.json.JsonObject(emptyMap())
            serviceResponse[entityId]
                ?: kotlinx.serialization.json.JsonObject(emptyMap())
        }.onFailure { t ->
            R1Log.w("HaRepo.weather", "get_forecasts($type) for $entityId failed: ${t.message}")
        }
    }

    override suspend fun renderTemplate(template: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val s = settings.settings.first()
                val server = s.server ?: error("Server URL not configured.")
                refresher?.ensureFresh()
                val payload = kotlinx.serialization.json.buildJsonObject {
                    put("template", JsonPrimitive(template))
                }
                val url = "${server.url.trimEnd('/')}/api/template"
                val body = templateCallBody(url, payload) ?: run {
                    if (refresher?.forceRefresh() == true) {
                        R1Log.i("HaRepo.template", "401 → refreshed; retrying once")
                        templateCallBody(url, payload)
                            ?: error("Home Assistant returned HTTP 401 for /api/template after refresh.")
                    } else {
                        error("Home Assistant returned HTTP 401 for /api/template.")
                    }
                }
                // /api/template returns the rendered template as a plain
                // string in the response body — not wrapped in JSON. Some
                // HA versions emit quoted strings; trim outer quotes if so.
                body.trim().let { raw ->
                    if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') {
                        raw.substring(1, raw.length - 1)
                    } else raw
                }
            }.onFailure { t ->
                R1Log.w("HaRepo.template", "render failed: ${t.message}")
            }
        }

    /** POST to /api/template with a JSON payload. HA's response is plain
     *  text (not JSON-wrapped), so the caller just receives the raw
     *  string body. Returns null on HTTP 401 for the refresh + retry
     *  pattern used elsewhere. HTTP 400 is surfaced as an exception
     *  carrying HA's error body — that's the "your template has a
     *  Jinja syntax error" path and the user wants to see it. */
    private suspend fun templateCallBody(
        url: String,
        payload: kotlinx.serialization.json.JsonObject,
    ): String? = withContext(Dispatchers.IO) {
        val t = tokens.load()
            ?: error("Authentication tokens missing. Sign out & reconnect from Settings.")
        val mediaType = "application/json".toMediaTypeOrNull()
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${t.accessToken}")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401) return@withContext null
            val responseBody = resp.body?.string().orEmpty()
            require(resp.isSuccessful) {
                // Forward HA's body verbatim — it contains the Jinja syntax
                // error / template traceback the user needs to iterate.
                if (responseBody.isNotBlank()) responseBody.trim()
                else "Home Assistant returned HTTP ${resp.code} for /api/template"
            }
            responseBody
        }
    }

    /** POST to /api/conversation/process. Returns null on HTTP 401 so the caller
     *  can refresh + retry. Same pattern as [fetchHistoryBody]. */
    private suspend fun conversationCallBody(
        url: String,
        payload: kotlinx.serialization.json.JsonObject,
    ): String? = withContext(Dispatchers.IO) {
        val t = tokens.load() ?: error("Authentication tokens missing. Sign out & reconnect from Settings.")
        val mediaType = "application/json".toMediaTypeOrNull()
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${t.accessToken}")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401) return@withContext null
            require(resp.isSuccessful) {
                "Home Assistant returned HTTP ${resp.code} for /api/conversation/process"
            }
            resp.body!!.string()
        }
    }

    companion object {
        /**
         * Maximum number of consecutive WS reconnect failures before the repository
         * gives up and stops scheduling auto-retries. With the default BackoffPolicy
         * (1 s base, 30 s cap, 0.25 jitter) this comes out to roughly 8 minutes of
         * trying before pausing. Past that point a permanently-broken state (wrong
         * URL, revoked token, HA service down) keeps the auto-retry loop slamming
         * the server every 30 s indefinitely, which can trip HA's
         * `login_attempts_threshold` and IP-ban the device. Recovery: the user
         * taps the existing "STILL LOADING · TAP TO RETRY" affordance, which
         * routes to [reconnectNow] and resets the counter.
         */
        const val RECONNECT_GIVE_UP_THRESHOLD = 20
        /**
         * Times [fetchHistory] re-attempts after a failure (on top of the first try), with
         * the backoff doubling each time. At the 1 s default base the attempts land at
         * +1/+3/+7/+15/+31 s, so the window comfortably outlasts the breaker's 15 s default
         * cooldown: a transiently-tripped breaker recovers and the chart fills rather than
         * staying blank until the card is re-opened.
         */
        const val HISTORY_FETCH_RETRIES = 5
        /**
         * Hard ceiling on how long the repository will wait for a `result` message after sending
         * a `call_service`. Set high enough to absorb a busy HA on a slow phone-to-broker link
         * (cover-set-position, media-volume-set on a Sonos group can take a couple of seconds),
         * low enough that the user knows within a sensible window if their command was lost.
         */
        const val CALL_TIMEOUT_MS = 15_000L

        /**
         * Heartbeat tick interval — the REST fallback poller wakes this often to *check*
         * whether the WS has been silent, but actual REST calls only fire when the
         * silence threshold below is also exceeded. Same value for both means a worst-
         * case 60 s lag (one tick to notice silence, one full poll cycle to refresh)
         * but in practice the second tick fires almost immediately after the first.
         */
        const val HEARTBEAT_INTERVAL_MS = 30_000L

        /**
         * How long the WS must be silent (no state_changed event applied) before the
         * REST fallback kicks in. A healthy WS produces events on any entity change,
         * which keeps this from ever tripping; the threshold only fires on the broken-
         * proxy / Cloudflare-idle-close / WS-coalesced-frames cases described in
         * [start]'s heartbeat block. 30 s matches the tick interval — there's no value
         * in a longer silence window because the tick is what gates the poll anyway.
         */
        const val HEARTBEAT_SILENCE_THRESHOLD_MS = 30_000L

        /**
         * Lenient Json used by the pure decoders below. Mirrors the instance
         * [listStatesJson] config (ignoreUnknownKeys) so the extracted
         * decode path is byte-for-byte equivalent to the inline version it
         * replaced. Kept on the companion so the decoders stay pure and
         * unit-testable without constructing a repository.
         */
        private val statesJson = Json { ignoreUnknownKeys = true }

        /**
         * Decode an `/api/states` REST body into typed [EntityState]s. Pure with
         * respect to network, settings, and instance state. Mirrors the resilient
         * per-row decode the live [listAllEntities] path uses, so one malformed row
         * never blanks the whole list. Exposed `internal` for real-payload unit
         * coverage.
         *
         * [logInfo] / [logWarn] receive the same field-diagnostic lines the inline
         * version emitted (raw / decoded counts, per-row drops, unsupported domains).
         * They default to [R1Log] for production; unit tests pass no-ops so the decode
         * runs without an Android logging shadow.
         */
        internal fun decodeStatesBody(
            body: String,
            logInfo: (String, String) -> Unit = { where, msg -> R1Log.i(where, msg) },
            logWarn: (String, String) -> Unit = { where, msg -> R1Log.w(where, msg) },
            // When true, entities from domains the app has no archetype for (device_tracker,
            // zone, calendar, ...) are kept as read-only [Domain.OTHER] records instead of being
            // dropped. The card stack / favourites picker pass false (they only render
            // archetypes); Universal Search passes true so the user can find every entity.
            includeUnsupported: Boolean = false,
            // Mirrors AdvancedSettings.strictEntityDecode. When false (the lenient default), a row
            // whose rich decode throws is kept as a minimal read-only record so it stays
            // searchable; when true, such a row is dropped (the historical behaviour).
            strictDecode: Boolean = false,
        ): List<EntityState> {
            val rowsJson = statesJson.decodeFromString<List<kotlinx.serialization.json.JsonElement>>(body)
            logInfo("HaRepo.listAll", "raw rows from /api/states: ${rowsJson.size}")
            val rowSerializer = RawStateRow.serializer()
            val rows = rowsJson.mapNotNull { el ->
                runCatching { statesJson.decodeFromJsonElement(rowSerializer, el) }.getOrElse { t ->
                    val eid = (el as? kotlinx.serialization.json.JsonObject)?.get("entity_id")?.let {
                        (it as? JsonPrimitive)?.content
                    } ?: "<unparseable>"
                    logWarn("HaRepo.listAll", "skipping malformed row $eid: ${t.message}")
                    null
                }
            }
            // Quick visibility on what came back so the user can see scenes/sensors in
            // logcat if the UI ever drops them; keeps debugging cheap in the field.
            val countsByDomain = rows.groupingBy {
                it.entity_id.substringBefore('.', missingDelimiterValue = "")
            }.eachCount()
            logInfo("HaRepo.listAll", "decoded ${rows.size} rows; by domain=$countsByDomain")
            // Diagnostic — when a user reports missing entities of a particular kind,
            // log raw vs decoded counts per domain so the offender pops out of logcat
            // immediately. The raw count is from the JSON array elements that name
            // a supported domain; the decoded count is what survived per-row decode.
            val rawByDomain = rowsJson.groupingBy {
                val obj = it as? kotlinx.serialization.json.JsonObject
                val eid = (obj?.get("entity_id") as? JsonPrimitive)?.content.orEmpty()
                eid.substringBefore('.', missingDelimiterValue = "")
            }.eachCount()
            val rawSupported = rawByDomain.filterKeys { Domain.isSupportedPrefix(it) }
            val deltas = rawSupported.mapValues { (d, raw) -> raw - (countsByDomain[d] ?: 0) }
                .filterValues { it > 0 }
            if (deltas.isNotEmpty()) {
                logWarn("HaRepo.listAll", "decoder dropped per-row: $deltas (raw=$rawSupported)")
            }
            // Log unsupported domains separately so users investigating "where's my
            // entity?" can run `adb logcat -s HaRepo.listAll` and see exactly which
            // domain prefixes we're dropping (with counts). The supported set is
            // implicit via Domain.isSupportedPrefix.
            val unsupported = countsByDomain.filterKeys { !Domain.isSupportedPrefix(it) }
            if (unsupported.isNotEmpty()) {
                logInfo("HaRepo.listAll", "unsupported (dropped): $unsupported")
            }
            return rows.mapNotNull { row ->
                val prefix = row.entity_id.substringBefore('.', missingDelimiterValue = "")
                if (!includeUnsupported && !Domain.isSupportedPrefix(prefix)) return@mapNotNull null
                // Wrap the whole EntityState construction in a try/catch so one weird
                // row (a media_player whose `volume_level` is a JsonArray instead of a
                // primitive, a climate with malformed `min_temp`, etc.) doesn't drop
                // the entire entity. Log the offender so users can find it via
                // `adb logcat -s HaRepo.listAll`.
                runCatching {
                val id = EntityId(row.entity_id)
                // Resolve the domain once: EntityId.domain re-parses the entity_id on
                // every access and the construction below reads it ~60 times. At a
                // 5000-entity /api/states response this turns ~300k substring allocations
                // + map lookups into 5000.
                val domain = id.domain
                val stateStr = row.stateStr
                val attrs = row.attrsObj
                val available = stateStr != "unavailable" && stateStr != "unknown"
                val pct = if (available) computePercentWithState(domain, attrs, stateStr) else null
                val rawNum = computeRaw(domain, attrs)
                    ?: if (domain == Domain.NUMBER || domain == Domain.INPUT_NUMBER) stateStr.toDoubleOrNull() else null
                EntityState(
                    id = id,
                    friendlyName = attrs["friendly_name"].asString() ?: row.entity_id.substringAfter('.'),
                    area = attrs["area_id"].asString(),
                    // Use the same domain-aware logic as `applyEvent` so REST seed matches
                    // event-driven cache updates. Inline rather than calling out so this
                    // function stays self-contained for testing.
                    isOn = when (domain) {
                        Domain.LIGHT, Domain.FAN, Domain.SWITCH, Domain.INPUT_BOOLEAN,
                        Domain.AUTOMATION, Domain.HUMIDIFIER -> stateStr.equals("on", ignoreCase = true)
                        Domain.COVER, Domain.VALVE -> stateStr.equals("open", ignoreCase = true)
                        Domain.MEDIA_PLAYER -> stateStr.equals("playing", ignoreCase = true)
                        Domain.LOCK -> stateStr.equals("unlocked", ignoreCase = true)
                        Domain.CLIMATE, Domain.WATER_HEATER ->
                            !stateStr.equals("off", ignoreCase = true) && available
                        Domain.SCRIPT -> stateStr.equals("on", ignoreCase = true)
                        Domain.SCENE, Domain.BUTTON, Domain.INPUT_BUTTON -> false
                        Domain.BINARY_SENSOR -> stateStr.equals("on", ignoreCase = true)
                        Domain.SENSOR -> false
                        Domain.NUMBER, Domain.INPUT_NUMBER -> false
                        Domain.VACUUM -> stateStr.equals("cleaning", ignoreCase = true) ||
                            stateStr.equals("returning", ignoreCase = true) ||
                            stateStr.equals("on", ignoreCase = true)
                        Domain.LAWN_MOWER -> stateStr.equals("mowing", ignoreCase = true) ||
                            stateStr.equals("returning", ignoreCase = true) ||
                            stateStr.equals("on", ignoreCase = true)
                        // Settable enums — no on/off concept.
                        Domain.SELECT, Domain.INPUT_SELECT -> false
                        // Helper-only — Helpers screen renders these bespoke.
                        Domain.COUNTER, Domain.INPUT_TEXT, Domain.INPUT_DATETIME -> false
                        Domain.TIMER -> stateStr.equals("active", ignoreCase = true)
                        // Siren: standard on/off.
                        Domain.SIREN -> stateStr.equals("on", ignoreCase = true)
                        // text / date / datetime / time / image / event: no on/off concept.
                        Domain.TEXT, Domain.DATE, Domain.DATETIME, Domain.TIME,
                        Domain.IMAGE, Domain.EVENT -> false
                        // Update entity: "on" = update available.
                        Domain.UPDATE -> stateStr.equals("on", ignoreCase = true)
                        // Remote: anything non-off / available counts as on.
                        Domain.REMOTE -> !stateStr.equals("off", ignoreCase = true) &&
                            stateStr != "unavailable" && stateStr != "unknown"
                        // Alarm: any non-disarmed armed/triggered state reads as on.
                        Domain.ALARM_CONTROL_PANEL -> !stateStr.equals("disarmed", ignoreCase = true) &&
                            available && stateStr.isNotBlank()
                        // Person: "home" reads as on; weather has no on/off concept.
                        Domain.PERSON -> stateStr.equals("home", ignoreCase = true)
                        Domain.WEATHER -> false
                        // Catch-all domains with no archetype: no on/off mapping.
                        Domain.OTHER -> false
                    },
                    percent = pct,
                    raw = rawNum,
                    lastChanged = (parseHaInstant(row.last_changed) ?: Instant.now()),
                    lastTriggered = if (domain == Domain.AUTOMATION || domain == Domain.SCRIPT)
                        attrs["last_triggered"].asString()?.let { parseHaInstant(it) } else null,
                    isAvailable = available,
                    supportsScalar = supportsScalar(domain, attrs),
                    rawState = stateStr,
                    unit = attrs["unit_of_measurement"].asString()
                        ?: attrs["temperature_unit"].asString(),
                    deviceClass = attrs["device_class"].asString(),
                    minRaw = when (domain) {
                        Domain.CLIMATE, Domain.WATER_HEATER -> attrs["min_temp"].asDouble()
                        Domain.HUMIDIFIER -> attrs["min_humidity"].asDouble()
                        Domain.NUMBER, Domain.INPUT_NUMBER -> attrs["min"].asDouble() ?: 0.0
                        else -> null
                    },
                    maxRaw = when (domain) {
                        Domain.CLIMATE, Domain.WATER_HEATER -> attrs["max_temp"].asDouble()
                        Domain.HUMIDIFIER -> attrs["max_humidity"].asDouble()
                        Domain.NUMBER, Domain.INPUT_NUMBER -> attrs["max"].asDouble() ?: 100.0
                        else -> null
                    },
                    supportedColorModes = if (domain == Domain.LIGHT) extractColorModes(attrs) else emptyList(),
                    colorTempK = if (domain == Domain.LIGHT) attrs["color_temp_kelvin"].asInt() else null,
                    minColorTempK = if (domain == Domain.LIGHT) attrs["min_color_temp_kelvin"].asInt() else null,
                    maxColorTempK = if (domain == Domain.LIGHT) attrs["max_color_temp_kelvin"].asInt() else null,
                    hue = if (domain == Domain.LIGHT) extractHue(attrs) else null,
                    step = if (domain == Domain.NUMBER || domain == Domain.INPUT_NUMBER)
                        attrs["step"].asDouble() else null,
                    effectList = if (domain == Domain.LIGHT) extractEffectList(attrs) else emptyList(),
                    effect = if (domain == Domain.LIGHT) attrs["effect"].asString()?.takeIf { it != "None" } else null,
                    attributesJson = attrs,
                    // Select / input_select — options list from `options` attribute,
                    // current option is just the state string. Empty / null for
                    // other domains.
                    selectOptions = if (domain.isSelect) extractStringList(attrs["options"]) else emptyList(),
                    currentOption = if (domain.isSelect) stateStr.takeIf { it.isNotBlank() && it != "unknown" && it != "unavailable" } else null,
                    mediaTitle = if (domain == Domain.MEDIA_PLAYER) attrs["media_title"].asString() else null,
                    mediaArtist = if (domain == Domain.MEDIA_PLAYER) attrs["media_artist"].asString() else null,
                    mediaAlbumName = if (domain == Domain.MEDIA_PLAYER) attrs["media_album_name"].asString() else null,
                    mediaDuration = if (domain == Domain.MEDIA_PLAYER) attrs["media_duration"].asInt() else null,
                    mediaPosition = if (domain == Domain.MEDIA_PLAYER) attrs["media_position"].asInt() else null,
                    mediaPositionUpdatedAt = if (domain == Domain.MEDIA_PLAYER) {
                        attrs["media_position_updated_at"].asString()?.let { parseHaInstant(it) }
                    } else null,
                    mediaPicture = if (domain == Domain.MEDIA_PLAYER) attrs["entity_picture"].asString() else null,
                    isVolumeMuted = domain == Domain.MEDIA_PLAYER &&
                        (attrs["is_volume_muted"] as? JsonPrimitive)?.content == "true",
                    mediaSupportedFeatures = if (domain == Domain.MEDIA_PLAYER)
                        attrs["supported_features"].asInt() ?: 0
                    else 0,
                    mediaShuffle = domain == Domain.MEDIA_PLAYER &&
                        (attrs["shuffle"].asBoolean() ?: false),
                    mediaRepeat = if (domain == Domain.MEDIA_PLAYER)
                        attrs["repeat"].asString() else null,
                    mediaSource = if (domain == Domain.MEDIA_PLAYER)
                        attrs["source"].asString() else null,
                    mediaSourceList = if (domain == Domain.MEDIA_PLAYER)
                        extractStringList(attrs["source_list"]) else emptyList(),
                    vacuumSupportedFeatures = if (domain == Domain.VACUUM)
                        attrs["supported_features"].asInt() ?: 0 else 0,
                    supportedFeatures = when (domain) {
                        Domain.LAWN_MOWER, Domain.CLIMATE, Domain.VALVE, Domain.WATER_HEATER,
                        Domain.ALARM_CONTROL_PANEL ->
                            attrs["supported_features"].asInt() ?: 0
                        else -> 0
                    },
                    vacuumBatteryLevel = if (domain == Domain.VACUUM)
                        attrs["battery_level"].asInt() else null,
                    vacuumStatus = if (domain == Domain.VACUUM)
                        attrs["status"].asString() ?: stateStr else null,
                    vacuumFanSpeed = if (domain == Domain.VACUUM)
                        attrs["fan_speed"].asString() else null,
                    vacuumFanSpeedList = if (domain == Domain.VACUUM)
                        extractStringList(attrs["fan_speed_list"]) else emptyList(),
                    climateHvacMode = if (domain == Domain.CLIMATE || domain == Domain.WATER_HEATER)
                        (if (domain == Domain.CLIMATE) stateStr
                        else attrs["operation_mode"].asString()) else null,
                    climateHvacModes = if (domain == Domain.CLIMATE)
                        extractStringList(attrs["hvac_modes"])
                    else if (domain == Domain.WATER_HEATER)
                        extractStringList(attrs["operation_list"])
                    else emptyList(),
                    climateFanMode = if (domain == Domain.CLIMATE)
                        attrs["fan_mode"].asString() else null,
                    climateFanModes = if (domain == Domain.CLIMATE)
                        extractStringList(attrs["fan_modes"]) else emptyList(),
                    climatePresetMode = if (domain == Domain.CLIMATE)
                        attrs["preset_mode"].asString() else null,
                    climatePresetModes = if (domain == Domain.CLIMATE)
                        extractStringList(attrs["preset_modes"]) else emptyList(),
                    climateHvacAction = if (domain == Domain.CLIMATE)
                        attrs["hvac_action"].asString() else null,
                    climateCurrentTemperature = if (domain == Domain.CLIMATE || domain == Domain.WATER_HEATER)
                        attrs["current_temperature"].asDouble() else null,
                    climateTargetTemperature = if (domain == Domain.CLIMATE || domain == Domain.WATER_HEATER)
                        attrs["temperature"].asDouble() else null,
                    climateTargetTempLow = if (domain == Domain.CLIMATE)
                        attrs["target_temp_low"].asDouble() else null,
                    climateTargetTempHigh = if (domain == Domain.CLIMATE)
                        attrs["target_temp_high"].asDouble() else null,
                    climateTempStep = if (domain == Domain.CLIMATE || domain == Domain.WATER_HEATER)
                        attrs["target_temp_step"].asDouble() else null,
                    climateMinTemp = if (domain == Domain.CLIMATE || domain == Domain.WATER_HEATER)
                        attrs["min_temp"].asDouble() else null,
                    climateMaxTemp = if (domain == Domain.CLIMATE || domain == Domain.WATER_HEATER)
                        attrs["max_temp"].asDouble() else null,
                    temperatureUnit = if (domain == Domain.CLIMATE || domain == Domain.WATER_HEATER)
                        attrs["temperature_unit"].asString()
                            ?: attrs["unit_of_measurement"].asString() else null,
                    lockCodeFormat = if (domain == Domain.LOCK)
                        attrs["code_format"].asString() else null,
                    lockChangedBy = if (domain == Domain.LOCK)
                        attrs["changed_by"].asString() else null,
                    fanPercentageStep = if (domain == Domain.FAN)
                        attrs["percentage_step"].asDouble() else null,
                    fanPresetMode = if (domain == Domain.FAN)
                        attrs["preset_mode"].asString() else null,
                    fanPresetModes = if (domain == Domain.FAN)
                        extractStringList(attrs["preset_modes"]) else emptyList(),
                    fanOscillating = if (domain == Domain.FAN)
                        attrs["oscillating"].asBoolean() else null,
                    fanDirection = if (domain == Domain.FAN)
                        attrs["direction"].asString() else null,
                    remoteCurrentActivity = if (domain == Domain.REMOTE)
                        attrs["current_activity"].asString() else null,
                    remoteActivityList = if (domain == Domain.REMOTE)
                        extractStringList(attrs["activity_list"]) else emptyList(),
                    alarmCodeFormat = if (domain == Domain.ALARM_CONTROL_PANEL)
                        attrs["code_format"].asString() else null,
                    alarmCodeArmRequired = if (domain == Domain.ALARM_CONTROL_PANEL)
                        (attrs["code_arm_required"].asBoolean() ?: true) else true,
                    alarmChangedBy = if (domain == Domain.ALARM_CONTROL_PANEL)
                        attrs["changed_by"].asString() else null,
                    displayPrecision = if (domain == Domain.SENSOR)
                        attrs["display_precision"].asInt()
                            ?: attrs["suggested_display_precision"].asInt()
                    else null,
                    sirenAvailableTones = if (domain == Domain.SIREN)
                        extractStringList(attrs["available_tones"]) else emptyList(),
                    sirenVolumeLevel = if (domain == Domain.SIREN &&
                        (attrs["is_volume_controllable"].asBoolean() ?: false))
                        attrs["volume_level"].asDouble() else null,
                )
                }.getOrElse { t ->
                    logWarn("HaRepo.listAll", "construction failed for ${row.entity_id}: ${t.message}")
                    if (strictDecode) return@mapNotNull null
                    // Lenient default: keep the entity as a minimal read-only record so a row whose
                    // rich attributes failed to decode is still findable in Universal Search rather
                    // than silently vanishing. A second failure (e.g. a malformed entity_id with no
                    // dot) drops it for real.
                    runCatching {
                        EntityState(
                            id = EntityId(row.entity_id),
                            friendlyName = row.attrsObj["friendly_name"].asString()
                                ?: row.entity_id.substringAfter('.'),
                            area = row.attrsObj["area_id"].asString(),
                            isOn = false,
                            percent = null,
                            raw = null,
                            lastChanged = Instant.now(),
                            isAvailable = row.stateStr != "unavailable" && row.stateStr != "unknown",
                            rawState = row.stateStr,
                        )
                    }.getOrNull()
                }
            }
        }

        /**
         * Decode a `recorder/statistics_during_period` result object into per-id
         * bucket lists. [payload] is the result payload (a map of statistic_id to
         * an array of bucket objects); [period] supplies the fallback bucket span
         * when HA omits a bucket's `end`. Pure and `internal` for unit coverage.
         */
        internal fun decodeStatisticsBuckets(
            payload: kotlinx.serialization.json.JsonObject,
            period: String,
        ): Map<String, List<StatisticsBucket>> {
            val out = LinkedHashMap<String, List<StatisticsBucket>>(payload.size)
            for ((sid, value) in payload) {
                val arr = value as? kotlinx.serialization.json.JsonArray ?: continue
                val buckets = arr.mapNotNull { el ->
                    val b = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    // HA encodes bucket boundaries as either ISO strings (default)
                    // or epoch milliseconds (some older cores / custom integrations);
                    // accept both so a single odd source doesn't blank the chart.
                    val startAt = parseBucketInstant(b["start"]) ?: return@mapNotNull null
                    val endAt = parseBucketInstant(b["end"])
                        ?: startAt.plusSeconds(bucketSpanSeconds(period))
                    StatisticsBucket(
                        start = startAt,
                        end = endAt,
                        mean = b["mean"].asDouble(),
                        min = b["min"].asDouble(),
                        max = b["max"].asDouble(),
                        sum = b["sum"].asDouble(),
                        state = b["state"].asDouble(),
                        change = b["change"].asDouble(),
                    )
                }
                out[sid] = buckets
            }
            return out
        }
    }

    /**
     * Lenient shape for HA's /api/states rows. Originally `state: String` rejected any
     * row where HA reported state as a JSON number (some MQTT integrations leak the
     * native MQTT payload through without coercing it to a string), and the per-row
     * decoder would drop the entity entirely. JsonElement absorbs both forms; we
     * normalise to a plain String in [stateStr]. `attributes` is also JsonElement
     * rather than JsonObject so a misbehaving integration that emits an array (yes,
     * really) doesn't kill the row either.
     */
    @kotlinx.serialization.Serializable
    private data class RawStateRow(
        val entity_id: String,
        val state: kotlinx.serialization.json.JsonElement? = null,
        val attributes: kotlinx.serialization.json.JsonElement? = null,
        val last_changed: String? = null,
    ) {
        /** Normalised state string. Empty / null state in the wire payload reads as "unknown"
         *  so downstream availability/isOn computations treat the row consistently. */
        val stateStr: String
            get() = when (val s = state) {
                null -> "unknown"
                is JsonPrimitive -> s.content
                else -> s.toString()
            }

        /** Normalised attributes object. Anything that isn't a JSON object (null, array,
         *  primitive) reads as empty so attribute lookups return null. */
        val attrsObj: kotlinx.serialization.json.JsonObject
            get() = (attributes as? kotlinx.serialization.json.JsonObject)
                ?: kotlinx.serialization.json.JsonObject(emptyMap())
    }

    /**
     * Re-issue every live subscription with a fresh WS request id after a reconnect.
     * Updates each subscription's atomic id in place so the live collectors (running
     * in subscribeTemplate / subscribeEvents below) start filtering on the new id
     * the moment HA confirms the subscribe. Called from the WS Connected observer.
     */
    private fun resubscribeLive() {
        if (liveSubs.isEmpty()) return
        scope.launch {
            // Snapshot entries to avoid ConcurrentModificationException — callers
            // might cancel mid-iteration.
            val snapshot = liveSubs.values.toList()
            for (sub in snapshot) {
                val newId = ws.nextRequestId()
                sub.requestId.set(newId)
                val frame = kotlinx.serialization.json.buildJsonObject {
                    put("id", JsonPrimitive(newId))
                    put("type", JsonPrimitive(sub.frameType))
                    sub.frameExtras.forEach { (k, v) -> put(k, v) }
                }
                ws.sendRawText(frame.toString())
                R1Log.i("HaRepo.liveSubs", "resubscribed ${sub.frameType} id=$newId")
            }
        }
    }

    private fun resubscribe() {
        scope.launch {
            val favs = settings.settings.first().favorites
            // Subscribe to favourites PLUS whatever the dashboards renderer is
            // currently showing, so a dashboard entity the user never pinned still
            // receives live state_changed events. Deduped; order doesn't matter.
            val ids = (favs + _dashboardEntityIds.value).toList()
            if (ids.isEmpty()) {
                // Nothing to watch — tear down the existing subscription so HA
                // stops pushing events we no longer care about, instead of leaving a stale
                // trigger subscribed forever.
                subscriptionId?.let { old ->
                    val unsubId = ws.nextRequestId()
                    ws.send(HaOutbound.UnsubscribeEvents(id = unsubId, subscription = old))
                    subscriptionId = null
                }
                return@launch
            }
            val newId = ws.nextRequestId()
            ws.send(HaOutbound.SubscribeStateTrigger(id = newId, entityIds = ids))
            subscriptionId?.let { old ->
                val unsubId = ws.nextRequestId()
                ws.send(HaOutbound.UnsubscribeEvents(id = unsubId, subscription = old))
            }
            subscriptionId = newId
        }
    }

    override suspend fun listTodoEntities(): Result<List<ToDoList>> = withContext(Dispatchers.IO) {
        runCatching {
            val rows = fetchRawRowsForDomain("todo")
            rows.map { row ->
                // HA stores the item count in the entity's state as a numeric
                // string. Fall back to 0 if it's missing or unparseable.
                val count = row.state.toIntOrNull() ?: 0
                ToDoList(
                    entityId = row.entityId,
                    friendlyName = row.friendlyName,
                    itemCount = count,
                )
            }.sortedBy { it.friendlyName.lowercase() }
        }.onFailure { t ->
            R1Log.w("HaRepo.todo", "list entities failed: ${t.message}")
        }
    }

    override suspend fun fetchTodoItems(entityId: String): Result<List<ToDoItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val s = settings.settings.first()
                // Guest mode gates writes (call / callRawService / fireEvent),
                // not reads. fetchTodoItems is a read — a guest holding the
                // device still needs to see what's on the shopping list.
                val server = s.server ?: error("Server URL not configured.")
                refresher?.ensureFresh()
                val payload = kotlinx.serialization.json.buildJsonObject {
                    put("entity_id", JsonPrimitive(entityId))
                }
                // HA 2024.1+ supports return_response on the REST service-call
                // endpoint. The query param is what flips the response from
                // "list of state changes" to "service's response data".
                val url = "${server.url.trimEnd('/')}/api/services/todo/get_items?return_response=true"
                val body = serviceCallRawBody(url, payload) ?: run {
                    if (refresher?.forceRefresh() == true) {
                        serviceCallRawBody(url, payload)
                            ?: error("HTTP 401 for todo.get_items after refresh.")
                    } else {
                        error("HTTP 401 for todo.get_items.")
                    }
                }
                // The response body looks like:
                // {"changed_states":[...],"service_response":{"todo.shopping":{"items":[...]}}}
                val root = listStatesJson.decodeFromString<kotlinx.serialization.json.JsonObject>(body)
                val serviceResponse = root["service_response"] as? kotlinx.serialization.json.JsonObject
                    ?: kotlinx.serialization.json.JsonObject(emptyMap())
                val entityResponse = serviceResponse[entityId] as? kotlinx.serialization.json.JsonObject
                    ?: kotlinx.serialization.json.JsonObject(emptyMap())
                val items = entityResponse["items"] as? kotlinx.serialization.json.JsonArray
                    ?: kotlinx.serialization.json.JsonArray(emptyList())
                items.mapIndexedNotNull { idx, el ->
                    val obj = el as? kotlinx.serialization.json.JsonObject ?: return@mapIndexedNotNull null
                    // HA's stable `uid` is what update_item / remove_item target.
                    // Keep it NULL when the provider doesn't expose one (rare on
                    // Local To-do / Google Tasks / Shopping List, but happens on
                    // some CalDAV providers) rather than substituting the summary:
                    // that substitution made two rows reading the same text look
                    // identical, and the summary-keyed dedupe below then collapsed
                    // them into one. The caller derives a position-based identity
                    // for uidless rows and falls back to the summary for service
                    // calls that need to address such an item.
                    val rawUid = (obj["uid"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                    val summary = (obj["summary"] as? JsonPrimitive)?.content ?: return@mapIndexedNotNull null
                    val status = (obj["status"] as? JsonPrimitive)?.content ?: "needs_action"
                    // HA exposes the due value under `due` (a date or datetime) on
                    // most providers and `due_datetime` on a few; accept either.
                    val due = (obj["due"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                        ?: (obj["due_datetime"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                    val description = (obj["description"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                    ToDoItem(
                        uid = rawUid,
                        summary = summary,
                        completed = status == "completed",
                        position = idx,
                        due = due,
                        description = description,
                    )
                }
                    // Dedupe only on a real, non-null uid: a misbehaving integration
                    // that returns two items with the same provider uid would crash
                    // LazyColumn on its duplicate-key check, so keep the first. Items
                    // WITHOUT a uid are never deduped here, so two rows that happen to
                    // share a summary survive as distinct entries (the UI keys them by
                    // position). distinctBy keeps the first occurrence per uid; null
                    // uids all map to the same bucket, so partition them out first.
                    .let { parsed ->
                        val withUid = parsed.filter { it.uid != null }.distinctBy { it.uid }
                        val withoutUid = parsed.filter { it.uid == null }
                        // Preserve original wire order across both groups.
                        (withUid + withoutUid).sortedBy { it.position }
                    }
            }.onFailure { t ->
                R1Log.w("HaRepo.todo", "fetch items for $entityId failed: ${t.message}")
            }
        }

    override suspend fun addTodoItem(entityId: String, summary: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = kotlinx.serialization.json.buildJsonObject {
                    put("entity_id", JsonPrimitive(entityId))
                    put("item", JsonPrimitive(summary))
                }
                callRawService("todo", "add_item", payload).getOrThrow()
                Unit
            }.onFailure { t ->
                R1Log.w("HaRepo.todo", "add to $entityId failed: ${t.message}")
            }
        }

    override suspend fun updateTodoItem(
        entityId: String,
        uid: String,
        completed: Boolean,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("entity_id", JsonPrimitive(entityId))
                // HA's update_item / remove_item services accept the `item`
                // field as either the summary string OR the stable uid; we
                // pass the uid so duplicate-summary lists ("Apples" twice
                // on a shopping list) target the right row.
                put("item", JsonPrimitive(uid))
                put("status", JsonPrimitive(if (completed) "completed" else "needs_action"))
            }
            callRawService("todo", "update_item", payload).getOrThrow()
            Unit
        }.onFailure { t ->
            R1Log.w("HaRepo.todo", "update on $entityId failed: ${t.message}")
        }
    }

    override suspend fun editTodoItem(
        entityId: String,
        uid: String,
        summary: String?,
        description: String?,
        due: String?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("entity_id", JsonPrimitive(entityId))
                put("item", JsonPrimitive(uid))
                if (summary != null) put("rename", JsonPrimitive(summary))
                if (description != null) put("description", JsonPrimitive(description))
                // A datetime due ("...T...") routes to due_datetime; a bare date
                // to due_date. An empty string clears whichever field is implied
                // (HA clears the value when the service receives an empty string).
                if (due != null) {
                    if (due.contains("T")) {
                        put("due_datetime", JsonPrimitive(due))
                    } else {
                        put("due_date", JsonPrimitive(due))
                    }
                }
            }
            callRawService("todo", "update_item", payload).getOrThrow()
            Unit
        }.onFailure { t ->
            R1Log.w("HaRepo.todo", "edit on $entityId failed: ${t.message}")
        }
    }

    override suspend fun moveTodoItem(
        entityId: String,
        uid: String,
        previousUid: String?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val extras = kotlinx.serialization.json.buildJsonObject {
            put("entity_id", JsonPrimitive(entityId))
            put("uid", JsonPrimitive(uid))
            // Omitting previous_uid (or sending null) moves the item to the top,
            // matching HA's moveItem(prev = undefined) semantics.
            if (previousUid != null) put("previous_uid", JsonPrimitive(previousUid))
        }
        callWsExpectingPayload("todo/item/move", extras).map { }.onFailure { t ->
            R1Log.w("HaRepo.todo", "move on $entityId failed: ${t.message}")
        }
    }

    override suspend fun removeTodoItem(entityId: String, uid: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = kotlinx.serialization.json.buildJsonObject {
                    put("entity_id", JsonPrimitive(entityId))
                    put("item", JsonPrimitive(uid))
                }
                callRawService("todo", "remove_item", payload).getOrThrow()
                Unit
            }.onFailure { t ->
                R1Log.w("HaRepo.todo", "remove from $entityId failed: ${t.message}")
            }
        }

    override suspend fun clearCompletedTodoItems(entityId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = kotlinx.serialization.json.buildJsonObject {
                    put("entity_id", JsonPrimitive(entityId))
                }
                callRawService("todo", "remove_completed_items", payload).getOrThrow()
                Unit
            }.onFailure { t ->
                R1Log.w("HaRepo.todo", "clear completed on $entityId failed: ${t.message}")
            }
        }

    /**
     * Generic WS request-response helper. Builds a JSON frame `{"id": N, "type": ...}`
     * with [extras] merged at the top level, sends it via [HaWebSocketClient.sendRawText],
     * and awaits the matching [HaInbound.Result] frame's `result` payload (or its error
     * field on failure).
     *
     * Bails immediately when the WS isn't Connected — there's no point queueing a
     * request that depends on a paired response while the link is down. Caller surfaces
     * "(disconnected)" rather than hanging.
     *
     * 15 s timeout matches the call_service path; same rationale: HA's WS commands are
     * snappy in practice, and a longer timeout just delays the user's "retry" decision.
     */
    /** Map a [ConnectionState] to a user-readable error message for surfaces that
     *  refuse to run while the WS is down. The repository's exception message is
     *  read verbatim by failure toasts in several screens. */
    private fun friendlyDisconnectedMessage(state: ConnectionState): String = when (state) {
        is ConnectionState.Disconnected -> "Home Assistant is offline; reconnecting…"
        is ConnectionState.AuthLost -> "Sign-in expired. Sign out and back in from Settings."
        is ConnectionState.Idle -> "Home Assistant connection hasn't started yet."
        is ConnectionState.Connecting -> "Home Assistant is connecting; try again in a moment."
        is ConnectionState.Authenticating -> "Home Assistant is authenticating; try again in a moment."
        is ConnectionState.Connected -> "Home Assistant is connected (unexpected state mismatch)."
    }

    private suspend fun callWsExpectingPayload(
        type: String,
        extras: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
    ): Result<kotlinx.serialization.json.JsonElement?> = withContext(Dispatchers.IO) {
        if (ws.state.value !is ConnectionState.Connected) {
            return@withContext Result.failure(
                IllegalStateException(friendlyDisconnectedMessage(ws.state.value)),
            )
        }
        val id = ws.nextRequestId()
        val deferred = CompletableDeferred<Result<kotlinx.serialization.json.JsonElement?>>()
        pendingPayloads[id] = deferred
        val frame = kotlinx.serialization.json.buildJsonObject {
            put("id", JsonPrimitive(id))
            put("type", JsonPrimitive(type))
            extras.forEach { (k, v) -> put(k, v) }
        }
        val sent = ws.sendRawText(frame.toString())
        if (!sent) {
            pendingPayloads.remove(id)
            return@withContext Result.failure(IllegalStateException("WS send refused"))
        }
        try {
            kotlinx.coroutines.withTimeout(15_000) { deferred.await() }
        } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
            pendingPayloads.remove(id)
            Result.failure(IllegalStateException("WS request '$type' timed out after 15s"))
        }
    }

    override suspend fun getUserData(key: String): Result<kotlinx.serialization.json.JsonElement?> =
        withContext(Dispatchers.IO) {
            val extras = kotlinx.serialization.json.buildJsonObject {
                put("key", JsonPrimitive(key))
            }
            // HA wraps the value under a "value" property of the response payload;
            // unwrap so callers can decode directly. Returns null when nothing
            // has been written under [key] for the current user.
            callWsExpectingPayload("frontend/get_user_data", extras).map { payload ->
                val obj = payload as? kotlinx.serialization.json.JsonObject
                obj?.get("value")?.takeUnless { it is kotlinx.serialization.json.JsonNull }
            }
        }

    override suspend fun setUserData(
        key: String,
        value: kotlinx.serialization.json.JsonElement,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val extras = kotlinx.serialization.json.buildJsonObject {
            put("key", JsonPrimitive(key))
            put("value", value)
        }
        callWsExpectingPayload("frontend/set_user_data", extras).map { }
    }

    override suspend fun fetchUpdateReleaseNotes(entityId: String): Result<String?> =
        withContext(Dispatchers.IO) {
            val extras = kotlinx.serialization.json.buildJsonObject {
                put("entity_id", JsonPrimitive(entityId))
            }
            // HA returns the notes as a bare markdown string under `result`; a null
            // result (integration provides no notes) maps to a null payload.
            callWsExpectingPayload("update/release_notes", extras).map { payload ->
                (payload as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            }
        }

    override suspend fun listRepairs(): Result<List<RepairIssue>> = withContext(Dispatchers.IO) {
        callWsExpectingPayload("repairs/list_issues").mapCatching { payload ->
            val obj = payload as? kotlinx.serialization.json.JsonObject ?: return@mapCatching emptyList()
            val arr = obj["issues"] as? kotlinx.serialization.json.JsonArray ?: return@mapCatching emptyList()
            arr.mapNotNull { el ->
                val r = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                fun str(key: String): String? = (r[key] as? JsonPrimitive)?.content
                // booleanOrNull is the spec-safe accessor on JsonPrimitive; `.boolean`
                // would throw when HA stuffs a string into the field (which it has
                // historically done for some integration-defined repair payloads).
                fun bool(key: String): Boolean =
                    (r[key] as? JsonPrimitive)?.booleanOrNull == true
                val domain = str("domain") ?: return@mapNotNull null
                val issueId = str("issue_id") ?: return@mapNotNull null
                RepairIssue(
                    domain = domain,
                    issueId = issueId,
                    severity = str("severity") ?: "warning",
                    translationKey = str("translation_key"),
                    description = str("description"),
                    learnMoreUrl = str("learn_more_url"),
                    breaksInHaVersion = str("breaks_in_ha_version"),
                    isFixable = bool("is_fixable"),
                    ignored = bool("ignored"),
                    createdAt = str("created"),
                )
            }
        }.onFailure { t ->
            R1Log.w("HaRepo.repairs", "list failed: ${t.message}")
        }
    }

    override suspend fun ignoreRepair(domain: String, issueId: String, ignore: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            val extras = kotlinx.serialization.json.buildJsonObject {
                put("domain", JsonPrimitive(domain))
                put("issue_id", JsonPrimitive(issueId))
                put("ignore", JsonPrimitive(ignore))
            }
            callWsExpectingPayload("repairs/ignore", extras).map { }.onFailure { t ->
                R1Log.w("HaRepo.repairs", "ignore $domain/$issueId failed: ${t.message}")
            }
        }

    override suspend fun browseMedia(
        entityId: String,
        mediaContentId: String?,
        mediaContentType: String?,
    ): Result<MediaBrowseResult> = withContext(Dispatchers.IO) {
        val extras = kotlinx.serialization.json.buildJsonObject {
            put("entity_id", JsonPrimitive(entityId))
            if (mediaContentId != null) put("media_content_id", JsonPrimitive(mediaContentId))
            if (mediaContentType != null) put("media_content_type", JsonPrimitive(mediaContentType))
        }
        callWsExpectingPayload("media_player/browse_media", extras).mapCatching { payload ->
            val root = payload as? kotlinx.serialization.json.JsonObject
                ?: error("media browse returned a non-object payload")
            val current = parseMediaEntry(root)
                ?: error("media browse returned malformed root entry")
            val childrenArr = root["children"] as? kotlinx.serialization.json.JsonArray
            val children = childrenArr?.mapNotNull { (it as? kotlinx.serialization.json.JsonObject)?.let(::parseMediaEntry) }
                ?: emptyList()
            MediaBrowseResult(current = current, children = children)
        }.onFailure { t ->
            R1Log.w("HaRepo.media", "browse failed: ${t.message}")
        }
    }

    override suspend fun subscribeEvents(
        eventType: String?,
        onEvent: (kotlinx.serialization.json.JsonObject) -> Unit,
    ): Result<HaRepository.EventSubscription> = withContext(Dispatchers.IO) {
        runCatching {
            val extras = kotlinx.serialization.json.buildJsonObject {
                if (eventType != null) put("event_type", JsonPrimitive(eventType))
            }
            registerLiveSubscription(
                frameType = "subscribe_events",
                frameExtras = extras,
                onEvent = onEvent,
                logTag = "HaRepo.events",
            )
        }.onFailure { t ->
            R1Log.w("HaRepo.events", "subscribe failed: ${t.message}")
        }.map { handle ->
            object : HaRepository.EventSubscription {
                override suspend fun cancel() = cancelLiveSubscription(handle)
            }
        }
    }

    override suspend fun startAssistPipeline(
        pipelineId: String?,
        conversationId: String?,
        onEvent: (kotlinx.serialization.json.JsonObject) -> Unit,
    ): Result<HaRepository.PipelineRun> = withContext(Dispatchers.IO) {
        runCatching {
            // HA's assist_pipeline/run starts a pipeline that emits a stream of events
            // (run-start, stt-start, stt-end, intent-start, intent-end, tts-start,
            // tts-end, run-end) on its subscription id. The client speaks audio at
            // HA via binary frames prefixed with the byte from run-start's
            // `runner_data.stt_binary_handler_id`; finish_audio sends a single-byte
            // frame to signal end-of-utterance.
            val extras = kotlinx.serialization.json.buildJsonObject {
                put("start_stage", JsonPrimitive("stt"))
                put("end_stage", JsonPrimitive("tts"))
                put(
                    "input",
                    kotlinx.serialization.json.buildJsonObject {
                        put("sample_rate", JsonPrimitive(16000))
                    },
                )
                if (pipelineId != null) put("pipeline", JsonPrimitive(pipelineId))
                if (conversationId != null) put("conversation_id", JsonPrimitive(conversationId))
            }
            val handle = registerLiveSubscription(
                frameType = "assist_pipeline/run",
                frameExtras = extras,
                onEvent = onEvent,
                logTag = "HaRepo.pipeline",
            )
            object : HaRepository.PipelineRun {
                override fun sendAudio(handlerByte: Byte, pcm: ByteArray): Boolean {
                    val frame = ByteArray(pcm.size + 1)
                    frame[0] = handlerByte
                    System.arraycopy(pcm, 0, frame, 1, pcm.size)
                    return ws.sendRawBytes(frame)
                }
                override fun finishAudio(handlerByte: Byte): Boolean {
                    return ws.sendRawBytes(byteArrayOf(handlerByte))
                }
                override suspend fun cancel() = cancelLiveSubscription(handle)
            }
        }.onFailure { t ->
            R1Log.w("HaRepo.pipeline", "start failed: ${t.message}")
        }
    }

    override suspend fun listAssistPipelines(): Result<HaRepository.AssistPipelines> =
        withContext(Dispatchers.IO) {
            callWsExpectingPayload("assist_pipeline/pipeline/list").mapCatching { payload ->
                val obj = payload as? kotlinx.serialization.json.JsonObject
                    ?: return@mapCatching HaRepository.AssistPipelines(emptyList(), null)
                val arr = obj["pipelines"] as? kotlinx.serialization.json.JsonArray
                val preferred = (obj["preferred_pipeline"] as? JsonPrimitive)?.content
                val pipelines = arr.orEmpty().mapNotNull { el ->
                    val p = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    val id = (p["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                    val name = (p["name"] as? JsonPrimitive)?.content ?: id
                    // stt_engine is null/absent when the pipeline has no speech-to-text
                    // configured; surface that so the picker can flag it as unusable
                    // for the satellite's audio run.
                    val stt = (p["stt_engine"] as? JsonPrimitive)?.content
                        ?.takeUnless { it.isBlank() }
                    HaRepository.AssistPipelineInfo(id = id, name = name, sttEngine = stt)
                }
                HaRepository.AssistPipelines(pipelines = pipelines, preferredId = preferred)
            }.onFailure { t ->
                R1Log.w("HaRepo.pipeline", "list failed: ${t.message}")
            }
        }

    override suspend fun subscribeTemplate(
        template: String,
        onResult: (String) -> Unit,
    ): Result<HaRepository.TemplateSubscription> = withContext(Dispatchers.IO) {
        runCatching {
            val extras = kotlinx.serialization.json.buildJsonObject {
                put("template", JsonPrimitive(template))
                // Report errors via the event channel so a single Jinja
                // syntax error doesn't tank the whole subscription.
                put("report_errors", JsonPrimitive(true))
            }
            registerLiveSubscription(
                frameType = "render_template",
                frameExtras = extras,
                onEvent = { event ->
                    val rendered = (event["result"] as? JsonPrimitive)?.content
                        ?: (event["error"] as? JsonPrimitive)?.content
                    if (rendered != null) onResult(rendered)
                },
                logTag = "HaRepo.template.live",
            )
        }.onFailure { t ->
            R1Log.w("HaRepo.template.live", "subscribe failed: ${t.message}")
        }.map { handle ->
            object : HaRepository.TemplateSubscription {
                override suspend fun cancel() = cancelLiveSubscription(handle)
            }
        }
    }

    override suspend fun subscribeTemplateDetailed(
        template: String,
        variables: kotlinx.serialization.json.JsonObject?,
        entityIds: List<String>,
        strict: Boolean,
        reportErrors: Boolean,
        onRender: (HaRepository.TemplateRender) -> Unit,
    ): Result<HaRepository.TemplateSubscription> = withContext(Dispatchers.IO) {
        runCatching {
            val extras = kotlinx.serialization.json.buildJsonObject {
                put("template", JsonPrimitive(template))
                if (strict) put("strict", JsonPrimitive(true))
                if (reportErrors) put("report_errors", JsonPrimitive(true))
                if (variables != null) put("variables", variables)
                if (entityIds.isNotEmpty()) {
                    put(
                        "entity_ids",
                        kotlinx.serialization.json.buildJsonArray {
                            entityIds.forEach { add(JsonPrimitive(it)) }
                        },
                    )
                }
            }
            registerLiveSubscription(
                frameType = "render_template",
                frameExtras = extras,
                onEvent = { event ->
                    val error = (event["error"] as? JsonPrimitive)?.content
                    if (error != null) {
                        val level = (event["level"] as? JsonPrimitive)?.content ?: "ERROR"
                        onRender(HaRepository.TemplateRender.Error(error, level))
                    } else {
                        val result = (event["result"] as? JsonPrimitive)?.content
                        if (result != null) onRender(HaRepository.TemplateRender.Ok(result))
                    }
                },
                logTag = "HaRepo.template.card",
            )
        }.onFailure { t ->
            R1Log.w("HaRepo.template.card", "subscribe failed: ${t.message}")
        }.map { handle ->
            object : HaRepository.TemplateSubscription {
                override suspend fun cancel() = cancelLiveSubscription(handle)
            }
        }
    }

    /**
     * Shared subscribe logic for [subscribeTemplate] / [subscribeEvents]. Sends the
     * subscribe frame, awaits the initial Result confirmation, registers the
     * subscription so [resubscribeLive] can replay it on reconnect, and starts a
     * collector that filters inboundRawText by the subscription's current id
     * (mutated atomically on reconnect).
     *
     * Returns a stable local handle id that the caller hands back via
     * [cancelLiveSubscription] when the screen tears down or the user toggles off.
     */
    private suspend fun registerLiveSubscription(
        frameType: String,
        frameExtras: kotlinx.serialization.json.JsonObject,
        onEvent: (kotlinx.serialization.json.JsonObject) -> Unit,
        logTag: String,
    ): Int {
        if (ws.state.value !is ConnectionState.Connected) {
            error(friendlyDisconnectedMessage(ws.state.value))
        }
        val handle = nextLiveSubHandle.getAndIncrement()
        val wsId = ws.nextRequestId()
        val currentIdRef = java.util.concurrent.atomic.AtomicInteger(wsId)
        val resultDeferred = CompletableDeferred<Result<kotlinx.serialization.json.JsonElement?>>()
        pendingPayloads[wsId] = resultDeferred
        val frame = kotlinx.serialization.json.buildJsonObject {
            put("id", JsonPrimitive(wsId))
            put("type", JsonPrimitive(frameType))
            frameExtras.forEach { (k, v) -> put(k, v) }
        }
        if (!ws.sendRawText(frame.toString())) {
            pendingPayloads.remove(wsId)
            error("WS refused $frameType subscribe")
        }
        val initial = kotlinx.coroutines.withTimeout(15_000) { resultDeferred.await() }
        initial.getOrThrow()

        val active = ActiveLiveSub(
            frameType = frameType,
            frameExtras = frameExtras,
            requestId = currentIdRef,
            onEvent = onEvent,
        )
        liveSubs[handle] = active

        // Collector — keyed off the ATOMIC reference so resubscribe can mutate
        // the id without restarting this job. The job lives on the repo scope
        // (not the caller's), so a transient screen teardown doesn't kill it.
        // Stored on the ActiveLiveSub so cancelLiveSubscription can cancel it.
        active.collectorJob = scope.launch {
            ws.inboundRawText.collect { raw ->
                val obj = runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(raw)
                        as? kotlinx.serialization.json.JsonObject
                }.getOrNull() ?: return@collect
                val frameId = (obj["id"] as? JsonPrimitive)?.content?.toIntOrNull()
                if (frameId != currentIdRef.get()) return@collect
                if ((obj["type"] as? JsonPrimitive)?.content != "event") return@collect
                val event = obj["event"] as? kotlinx.serialization.json.JsonObject
                    ?: return@collect
                onEvent(event)
            }
        }
        R1Log.i(logTag, "live subscription registered handle=$handle ws=$wsId")
        return handle
    }

    /**
     * Cancel a previously-registered live subscription. Removes it from [liveSubs]
     * (so [resubscribeLive] won't replay it after a future reconnect) and sends a
     * best-effort unsubscribe_events frame so HA stops pushing.
     */
    private suspend fun cancelLiveSubscription(handle: Int) {
        val active = liveSubs.remove(handle) ?: return
        runCatching {
            val unsubId = ws.nextRequestId()
            val unsub = kotlinx.serialization.json.buildJsonObject {
                put("id", JsonPrimitive(unsubId))
                put("type", JsonPrimitive("unsubscribe_events"))
                put("subscription", JsonPrimitive(active.requestId.get()))
            }
            ws.sendRawText(unsub.toString())
        }
        active.collectorJob?.cancel()
    }

    override suspend fun listBackups(): Result<List<BackupInfo>> = withContext(Dispatchers.IO) {
        callWsExpectingPayload("backup/info").mapCatching { payload ->
            val obj = payload as? kotlinx.serialization.json.JsonObject ?: return@mapCatching emptyList()
            // HA wraps the backup list under either "backups" (2024.4+) or
            // "data.backups" (Supervisor-routed); accept both shapes.
            val arr = (obj["backups"] as? kotlinx.serialization.json.JsonArray)
                ?: ((obj["data"] as? kotlinx.serialization.json.JsonObject)?.get("backups") as? kotlinx.serialization.json.JsonArray)
                ?: return@mapCatching emptyList()
            arr.mapNotNull { el ->
                val r = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                fun str(key: String): String? = (r[key] as? JsonPrimitive)?.content
                fun long(key: String): Long? = (r[key] as? JsonPrimitive)?.content?.toLongOrNull()
                fun bool(key: String): Boolean = (r[key] as? JsonPrimitive)?.booleanOrNull == true
                val id = str("backup_id") ?: str("slug") ?: return@mapNotNull null
                BackupInfo(
                    backupId = id,
                    name = str("name") ?: id,
                    createdAt = str("date") ?: str("created"),
                    sizeBytes = long("size") ?: long("size_bytes"),
                    protected = bool("protected"),
                    type = str("type"),
                )
            }.sortedByDescending { it.createdAt ?: "" }
        }.onFailure { t ->
            R1Log.w("HaRepo.backup", "list failed: ${t.message}")
        }
    }

    override suspend fun listAreas(): Result<List<AreaInfo>> = withContext(Dispatchers.IO) {
        callWsExpectingPayload("config/area_registry/list").mapCatching { payload ->
            val arr = payload as? kotlinx.serialization.json.JsonArray ?: return@mapCatching emptyList()
            arr.mapNotNull { el ->
                val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val id = (o["area_id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val name = (o["name"] as? JsonPrimitive)?.content ?: id
                val floorId = (o["floor_id"] as? JsonPrimitive)?.content
                AreaInfo(areaId = id, name = name, floorId = floorId)
            }.sortedBy { it.name.lowercase() }
        }.onFailure { t ->
            R1Log.w("HaRepo.area", "list failed: ${t.message}")
        }
    }

    override suspend fun createArea(name: String): Result<AreaInfo> = withContext(Dispatchers.IO) {
        val extras = kotlinx.serialization.json.buildJsonObject {
            put("name", JsonPrimitive(name))
        }
        callWsExpectingPayload("config/area_registry/create", extras).mapCatching { payload ->
            val o = payload as? kotlinx.serialization.json.JsonObject
                ?: error("create_area returned non-object payload")
            val id = (o["area_id"] as? JsonPrimitive)?.content
                ?: error("create_area returned no area_id")
            val resolvedName = (o["name"] as? JsonPrimitive)?.content ?: name
            AreaInfo(areaId = id, name = resolvedName)
        }.onFailure { t ->
            R1Log.w("HaRepo.area", "create '$name' failed: ${t.message}")
        }
    }

    override suspend fun renameArea(areaId: String, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        val extras = kotlinx.serialization.json.buildJsonObject {
            put("area_id", JsonPrimitive(areaId))
            put("name", JsonPrimitive(name))
        }
        callWsExpectingPayload("config/area_registry/update", extras).map { }.onFailure { t ->
            R1Log.w("HaRepo.area", "rename '$areaId' failed: ${t.message}")
        }
    }

    override suspend fun updateEntityRegistry(
        entityId: String,
        name: String?,
        areaId: String?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val extras = kotlinx.serialization.json.buildJsonObject {
            put("entity_id", JsonPrimitive(entityId))
            // HA distinguishes "set the name to empty string" (revert to integration-
            // supplied original_name) from "leave the name untouched" (omit the
            // field). We follow that contract: null caller means omit; an empty
            // string caller means revert.
            if (name != null) put("name", JsonPrimitive(name.ifBlank { "" }))
            // Same shape for area_id: pass null to omit, empty string to clear
            // the area assignment.
            if (areaId != null) {
                if (areaId.isBlank()) {
                    // HA's update endpoint expects an explicit `null` JSON value
                    // to clear an area assignment; an empty string is rejected.
                    put("area_id", kotlinx.serialization.json.JsonNull)
                } else {
                    put("area_id", JsonPrimitive(areaId))
                }
            }
        }
        callWsExpectingPayload("config/entity_registry/update", extras).map { }.onFailure { t ->
            R1Log.w("HaRepo.entityRegistry", "update $entityId failed: ${t.message}")
        }
    }

    override suspend fun listDevices(): Result<List<DeviceInfo>> = withContext(Dispatchers.IO) {
        callWsExpectingPayload("config/device_registry/list").mapCatching { payload ->
            val arr = payload as? kotlinx.serialization.json.JsonArray
                ?: return@mapCatching emptyList()
            arr.mapNotNull { el ->
                val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                fun str(k: String): String? = (o[k] as? JsonPrimitive)?.content
                // identifiers/connections are JSON arrays of [domain, id] 2-tuples.
                // Skip anything that isn't exactly a 2-element array of primitives.
                fun tuples(k: String): List<Pair<String, String>> {
                    val outer = o[k] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
                    return outer.mapNotNull { entry ->
                        val pair = entry as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
                        if (pair.size != 2) return@mapNotNull null
                        val a = (pair[0] as? JsonPrimitive)?.content ?: return@mapNotNull null
                        val b = (pair[1] as? JsonPrimitive)?.content ?: return@mapNotNull null
                        a to b
                    }
                }
                val id = str("id") ?: return@mapNotNull null
                DeviceInfo(
                    id = id,
                    name = str("name"),
                    nameByUser = str("name_by_user"),
                    manufacturer = str("manufacturer"),
                    model = str("model"),
                    areaId = str("area_id"),
                    disabledBy = str("disabled_by"),
                    viaDeviceId = str("via_device_id"),
                    swVersion = str("sw_version"),
                    hwVersion = str("hw_version"),
                    configurationUrl = str("configuration_url"),
                    identifiers = tuples("identifiers"),
                    connections = tuples("connections"),
                )
            }
        }.onFailure { t ->
            R1Log.w("HaRepo.devices", "list failed: ${t.message}")
        }
    }

    override suspend fun listEntityRegistry(): Result<List<EntityRegistryEntry>> = withContext(Dispatchers.IO) {
        callWsExpectingPayload("config/entity_registry/list").mapCatching { payload ->
            val arr = payload as? kotlinx.serialization.json.JsonArray
                ?: return@mapCatching emptyList()
            arr.mapNotNull { el ->
                val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                fun str(k: String): String? = (o[k] as? JsonPrimitive)?.content
                val entityId = str("entity_id") ?: return@mapNotNull null
                EntityRegistryEntry(
                    entityId = entityId,
                    name = str("name"),
                    originalName = str("original_name"),
                    deviceId = str("device_id"),
                    areaId = str("area_id"),
                    platform = str("platform"),
                    disabledBy = str("disabled_by"),
                    hiddenBy = str("hidden_by"),
                )
            }
        }.onFailure { t ->
            R1Log.w("HaRepo.entityRegistry", "list failed: ${t.message}")
        }
    }

    override suspend fun getExtendedEntityRegistryOptions(
        entityId: String,
    ): Result<ExtEntityRegistryOptions> = withContext(Dispatchers.IO) {
        val domain = entityId.substringBefore('.', missingDelimiterValue = "")
        val extras = kotlinx.serialization.json.buildJsonObject {
            put("entity_id", JsonPrimitive(entityId))
        }
        callWsExpectingPayload("config/entity_registry/get", extras).map { payload ->
            ExtEntityRegistryOptions.fromPayload(
                domain,
                payload as? kotlinx.serialization.json.JsonObject,
            )
        }.recover {
            // Older HA servers reject the unknown command; degrade silently so the
            // favorite / code features fall back to their built-in defaults.
            ExtEntityRegistryOptions.EMPTY
        }
    }

    override suspend fun getEntityRegistryOptions(
        entityId: String,
    ): Result<kotlinx.serialization.json.JsonObject?> = withContext(Dispatchers.IO) {
        val extras = kotlinx.serialization.json.buildJsonObject {
            put("entity_id", JsonPrimitive(entityId))
        }
        // `config/entity_registry/get` returns the full extended registry entry,
        // including the per-domain `options` blob (favourite positions / colours)
        // that the slim `config/entity_registry/list` reply omits.
        callWsExpectingPayload("config/entity_registry/get", extras).map { payload ->
            val obj = payload as? kotlinx.serialization.json.JsonObject
            obj?.get("options") as? kotlinx.serialization.json.JsonObject
        }.onFailure { t ->
            R1Log.w("HaRepo.entityRegistry", "get options $entityId failed: ${t.message}")
        }
    }

    override suspend fun updateEntityRegistryOptions(
        entityId: String,
        optionsDomain: String,
        options: kotlinx.serialization.json.JsonObject,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val extras = kotlinx.serialization.json.buildJsonObject {
            put("entity_id", JsonPrimitive(entityId))
            // HA's update endpoint persists per-domain options under the
            // `options_domain` + `options` pair (the same form the frontend's
            // updateEntityRegistryEntry uses to write favourites).
            put("options_domain", JsonPrimitive(optionsDomain))
            put("options", options)
        }
        callWsExpectingPayload("config/entity_registry/update", extras).map { }.onFailure { t ->
            R1Log.w("HaRepo.entityRegistry", "update options $entityId failed: ${t.message}")

        }
    }

    override suspend fun listConfigEntries(): Result<List<ConfigEntry>> = withContext(Dispatchers.IO) {
        callWsExpectingPayload("config_entries/get").mapCatching { payload ->
            val arr = payload as? kotlinx.serialization.json.JsonArray
                ?: return@mapCatching emptyList()
            arr.mapNotNull { el ->
                val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                fun str(k: String): String? = (o[k] as? JsonPrimitive)?.content
                fun bool(k: String): Boolean =
                    (o[k] as? JsonPrimitive)?.booleanOrNull == true
                val entryId = str("entry_id") ?: return@mapNotNull null
                val domain = str("domain") ?: return@mapNotNull null
                ConfigEntry(
                    entryId = entryId,
                    domain = domain,
                    title = str("title") ?: domain,
                    source = str("source") ?: "user",
                    state = str("state") ?: "unknown",
                    supportsOptions = bool("supports_options"),
                    supportsRemoveDevice = bool("supports_remove_device"),
                    supportsUnload = bool("supports_unload"),
                    prefDisableNewEntities = bool("pref_disable_new_entities"),
                    prefDisablePolling = bool("pref_disable_polling"),
                    reason = str("reason"),
                    disabledBy = str("disabled_by"),
                )
            }
        }.onFailure { t ->
            R1Log.w("HaRepo.configEntries", "list failed: ${t.message}")
        }
    }

    override suspend fun reloadConfigEntry(entryId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val extras = kotlinx.serialization.json.buildJsonObject {
            put("entry_id", JsonPrimitive(entryId))
        }
        callWsExpectingPayload("config_entries/reload", extras).map { }.onFailure { t ->
            R1Log.w("HaRepo.configEntries", "reload $entryId failed: ${t.message}")
        }
    }

    /** Decode one [MediaBrowseEntry] from a HA browse_media payload object. */
    private fun parseMediaEntry(obj: kotlinx.serialization.json.JsonObject): MediaBrowseEntry? {
        fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.content
        fun bool(key: String): Boolean =
            (obj[key] as? JsonPrimitive)?.booleanOrNull == true
        val mediaContentId = str("media_content_id") ?: return null
        val mediaContentType = str("media_content_type") ?: return null
        return MediaBrowseEntry(
            title = str("title") ?: mediaContentId,
            mediaClass = str("media_class"),
            mediaContentId = mediaContentId,
            mediaContentType = mediaContentType,
            canPlay = bool("can_play"),
            canExpand = bool("can_expand"),
            thumbnail = str("thumbnail"),
        )
    }

    override suspend fun fetchErrorLogFull(maxBytes: Int): Result<ErrorLogTail> =
        withContext(Dispatchers.IO) {
            runCatching {
                val s = settings.settings.first()
                val server = s.server ?: error("Server URL not configured.")
                refresher?.ensureFresh()
                val url = "${server.url.trimEnd('/')}/api/error_log"
                val tail = simpleAuthedGetTailWithSize(url, maxBytes) ?: run {
                    if (refresher?.forceRefresh() == true) {
                        simpleAuthedGetTailWithSize(url, maxBytes)
                            ?: error("Home Assistant returned HTTP 401 for /api/error_log after refresh.")
                    } else {
                        error("Home Assistant returned HTTP 401 for /api/error_log.")
                    }
                }
                tail
            }.onFailure { t ->
                R1Log.w("HaRepo.errorLogFull", "fetch failed: ${t.message}")
            }
        }

    override suspend fun listTags(): Result<List<HaTag>> = withContext(Dispatchers.IO) {
        // tag/list returns the array under the top-level result (not wrapped in a
        // result object the way some other commands are). callWsExpectingPayload
        // returns whatever's in the result field; for this command that's the
        // array directly. Older HA servers (pre 2023.5) returned an object with
        // a "tags" key — accept both shapes defensively.
        callWsExpectingPayload("tag/list").mapCatching { payload ->
            val arr = when (payload) {
                is kotlinx.serialization.json.JsonArray -> payload
                is kotlinx.serialization.json.JsonObject ->
                    payload["tags"] as? kotlinx.serialization.json.JsonArray
                        ?: error("tag/list reply has no tags array")
                else -> error("tag/list returned an unexpected payload shape")
            }
            arr.mapNotNull { el ->
                val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                fun str(key: String): String? = (o[key] as? JsonPrimitive)?.content
                val id = str("id") ?: str("tag_id") ?: return@mapNotNull null
                val lastScanned = str("last_scanned")?.let {
                    parseHaInstant(it)
                }
                HaTag(
                    id = id,
                    name = str("name"),
                    description = str("description"),
                    lastScanned = lastScanned,
                )
            }
        }.onFailure { t ->
            R1Log.w("HaRepo.tags", "list failed: ${t.message}")
        }
    }

    override suspend fun updateTag(
        tagId: String,
        name: String?,
        description: String?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val extras = kotlinx.serialization.json.buildJsonObject {
            put("tag_id", JsonPrimitive(tagId))
            if (name != null) put("name", JsonPrimitive(name))
            if (description != null) put("description", JsonPrimitive(description))
        }
        callWsExpectingPayload("tag/update", extras).map { }.onFailure { t ->
            R1Log.w("HaRepo.tags", "update $tagId failed: ${t.message}")
        }
    }

    override suspend fun deleteTag(tagId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val extras = kotlinx.serialization.json.buildJsonObject {
            put("tag_id", JsonPrimitive(tagId))
        }
        callWsExpectingPayload("tag/delete", extras).map { }.onFailure { t ->
            R1Log.w("HaRepo.tags", "delete $tagId failed: ${t.message}")
        }
    }

    override suspend fun fetchLovelaceConfig(
        urlPath: String?,
        forceRefresh: Boolean,
    ): Result<kotlinx.serialization.json.JsonObject> = withContext(Dispatchers.IO) {
        // HA's `lovelace/config` command accepts `url_path` (null = default
        // dashboard) and a `force` flag. `force: true` makes a YAML-mode
        // dashboard re-read its file from disk (the manual RELOAD path); the
        // default false serves HA's cached config. Live state still flows
        // through the state subscriptions, not repeated config fetches.
        val extras = kotlinx.serialization.json.buildJsonObject {
            put("url_path", if (urlPath == null) kotlinx.serialization.json.JsonNull else JsonPrimitive(urlPath))
            put("force", JsonPrimitive(forceRefresh))
        }
        callWsExpectingPayload("lovelace/config", extras).mapCatching { payload ->
            // Storage-mode dashboards that are still auto-generated return
            // `config_not_found`. Surface that as a typed error so the UI
            // can offer the user a useful next step ("HA hasn't published
            // a Lovelace config for this dashboard yet").
            payload as? kotlinx.serialization.json.JsonObject
                ?: error("lovelace/config returned a non-object payload")
        }.onFailure { t ->
            R1Log.w("HaRepo.lovelace", "fetchConfig urlPath=${urlPath ?: "(default)"} failed: ${t.message}")
        }
    }

    override suspend fun listLovelaceDashboards():
        Result<kotlinx.serialization.json.JsonArray> = withContext(Dispatchers.IO) {
            callWsExpectingPayload("lovelace/dashboards/list").mapCatching { payload ->
                payload as? kotlinx.serialization.json.JsonArray
                    ?: error("lovelace/dashboards/list returned a non-array payload")
            }.onFailure { t ->
                R1Log.w("HaRepo.lovelace", "listDashboards failed: ${t.message}")
            }
        }

    override suspend fun listAuthUsers(): Result<List<HaUser>> = withContext(Dispatchers.IO) {
        callWsExpectingPayload("config/auth/list").mapCatching { payload ->
            val arr = payload as? kotlinx.serialization.json.JsonArray
                ?: error("config/auth/list returned a non-array payload")
            arr.mapNotNull { el ->
                val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                fun str(key: String): String? = (o[key] as? JsonPrimitive)?.content
                fun bool(key: String): Boolean =
                    (o[key] as? JsonPrimitive)?.booleanOrNull == true
                val id = str("id") ?: return@mapNotNull null
                val groups = (o["group_ids"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    .orEmpty()
                HaUser(
                    id = id,
                    name = str("name").orEmpty(),
                    systemGenerated = bool("system_generated"),
                    isActive = bool("is_active"),
                    localOnly = bool("local_only"),
                    groupIds = groups,
                )
            }.sortedBy { it.name.lowercase().ifBlank { "~~" + it.id } }
        }.onFailure { t ->
            R1Log.w("HaRepo.authUsers", "list failed: ${t.message}")
        }
    }

    override suspend fun listBlueprints(domain: String): Result<List<BlueprintInfo>> =
        withContext(Dispatchers.IO) {
            // HA's WS command is `blueprint/list` with the blueprint kind passed
            // as a `domain` field (NOT a `blueprint/list/<domain>` command type,
            // which HA rejects with "unknown_command"). We only support the two
            // HA Core ships today (automation, script); validate up front so a
            // typo here is a clear failure rather than a vague server reply.
            require(domain == "automation" || domain == "script") {
                "Unsupported blueprint domain '$domain'"
            }
            val extras = kotlinx.serialization.json.buildJsonObject {
                put("domain", JsonPrimitive(domain))
            }
            callWsExpectingPayload("blueprint/list", extras).mapCatching { payload ->
                val root = payload as? kotlinx.serialization.json.JsonObject
                    ?: return@mapCatching emptyList()
                // HA's reply shape: { "<path>": { metadata: {...}, ... }, ... }.
                // Older HA's wrapped it under "blueprints"; accept both
                // defensively because some integration tests still mock the
                // older shape.
                val map = (root["blueprints"] as? kotlinx.serialization.json.JsonObject) ?: root
                map.entries.mapNotNull { (path, value) ->
                    val obj = value as? kotlinx.serialization.json.JsonObject
                        ?: return@mapNotNull null
                    decodeBlueprint(domain = domain, path = path, obj = obj, rawYaml = null)
                }.sortedBy { it.name.lowercase() }
            }.onFailure { t ->
                R1Log.w("HaRepo.blueprints", "list $domain failed: ${t.message}")
            }
        }

    override suspend fun importBlueprint(url: String): Result<BlueprintInfo> =
        withContext(Dispatchers.IO) {
            val extras = kotlinx.serialization.json.buildJsonObject {
                put("url", JsonPrimitive(url))
            }
            callWsExpectingPayload("blueprint/import", extras).mapCatching { payload ->
                val root = payload as? kotlinx.serialization.json.JsonObject
                    ?: error("import returned a non-object payload")
                val blueprintObj = root["blueprint"] as? kotlinx.serialization.json.JsonObject
                    ?: error("import reply missing 'blueprint' object")
                val suggested = (root["suggested_filename"] as? JsonPrimitive)?.content.orEmpty()
                // HA returns the verbatim YAML in `raw_data` so the user's
                // `blueprint/save` writes exactly what the validator just
                // approved. Missing on very old HA installs; we fall back to
                // an empty string and the save path errors out with a clear
                // message rather than silently writing nothing.
                val rawYaml = (root["raw_data"] as? JsonPrimitive)?.content
                // HA infers the domain from the blueprint body itself; copy
                // it out of the metadata so the row carries the right tag.
                val metadata = blueprintObj["metadata"] as? kotlinx.serialization.json.JsonObject
                val resolvedDomain = (metadata?.get("domain") as? JsonPrimitive)?.content
                    ?: "automation"
                // validation_errors is an array (or null). Join into a
                // single human line for the preview sheet's red banner;
                // null means HA was happy with the blueprint.
                val errs = root["validation_errors"] as? kotlinx.serialization.json.JsonArray
                val errText = errs?.takeIf { it.isNotEmpty() }?.joinToString("; ") {
                    (it as? JsonPrimitive)?.content ?: it.toString()
                }
                val base = decodeBlueprint(
                    domain = resolvedDomain,
                    path = suggested,
                    obj = blueprintObj,
                    rawYaml = rawYaml,
                ) ?: error("import reply blueprint object had no metadata")
                base.copy(
                    sourceUrl = base.sourceUrl ?: url,
                    validationErrors = errText,
                )
            }.onFailure { t ->
                R1Log.w("HaRepo.blueprints", "import '$url' failed: ${t.message}")
            }
        }

    override suspend fun saveBlueprint(
        domain: String,
        path: String,
        yaml: String,
        sourceUrl: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        require(domain == "automation" || domain == "script") {
            "Unsupported blueprint domain '$domain'"
        }
        require(path.isNotBlank()) { "blueprint path cannot be blank" }
        require(yaml.isNotBlank()) { "blueprint YAML cannot be blank" }
        val extras = kotlinx.serialization.json.buildJsonObject {
            put("domain", JsonPrimitive(domain))
            put("path", JsonPrimitive(path))
            put("yaml", JsonPrimitive(yaml))
            // HA stamps `source_url` into the on-disk header so a later
            // `blueprint/list` reply carries the URL back to us — that's how
            // the row's source link survives a server restart.
            put("source_url", JsonPrimitive(sourceUrl))
        }
        callWsExpectingPayload("blueprint/save", extras).map { }.onFailure { t ->
            R1Log.w("HaRepo.blueprints", "save $domain/$path failed: ${t.message}")
        }
    }

    /**
     * Decode one blueprint object (either the value-side of `blueprint/list`
     * or the `blueprint` field of `blueprint/import`) into a [BlueprintInfo].
     * Returns null when the metadata block is absent; HA shouldn't ship
     * blueprints without it but old YAML from third-party repos sometimes
     * does and we'd rather skip a single row than fail the whole list.
     */
    private fun decodeBlueprint(
        domain: String,
        path: String,
        obj: kotlinx.serialization.json.JsonObject,
        rawYaml: String?,
    ): BlueprintInfo? {
        val metadata = obj["metadata"] as? kotlinx.serialization.json.JsonObject
            ?: return null
        fun str(key: String): String? = (metadata[key] as? JsonPrimitive)?.content
        val name = str("name") ?: path.substringAfterLast('/').removeSuffix(".yaml")
        val description = str("description").orEmpty()
        val sourceUrl = str("source_url")
        // `input` is a map of slot-name → schema; count its keys to surface
        // 'this blueprint wants 3 things' to the user. Absent / non-object
        // payload reads as zero.
        val inputCount = (obj["input"] as? kotlinx.serialization.json.JsonObject)?.size
            ?: (metadata["input"] as? kotlinx.serialization.json.JsonObject)?.size
            ?: 0
        return BlueprintInfo(
            domain = domain,
            path = path,
            name = name,
            description = description,
            sourceUrl = sourceUrl,
            inputCount = inputCount,
            rawYaml = rawYaml,
        )
    }

    override suspend fun listStatisticIds(): Result<List<StatisticId>> = withContext(Dispatchers.IO) {
        // recorder/list_statistic_ids returns an array of catalogue rows. We
        // call without a `statistic_type` filter so the user can pick from
        // every recorded series; the per-series has_mean / has_sum flags
        // tell the UI which aggregation chips to enable.
        callWsExpectingPayload("recorder/list_statistic_ids").mapCatching { payload ->
            val arr = payload as? kotlinx.serialization.json.JsonArray
                ?: error("recorder/list_statistic_ids returned a non-array payload")
            arr.mapNotNull { el ->
                val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                // contentOrNull (not content): HA sends `"name": null` for
                // entity-backed series, and JsonNull IS a JsonPrimitive whose
                // .content is the literal string "null" — which is exactly what
                // was leaking into the picker rows. contentOrNull maps JsonNull
                // back to a real null so the name falls through to the friendly
                // name / id instead.
                fun str(key: String): String? = (o[key] as? JsonPrimitive)?.contentOrNull
                fun bool(key: String): Boolean =
                    (o[key] as? JsonPrimitive)?.booleanOrNull == true
                val sid = str("statistic_id") ?: return@mapNotNull null
                // HA returns unit under "statistics_unit_of_measurement" on
                // newer cores and "unit_of_measurement" on older ones; accept
                // either so the picker still labels rows correctly.
                val unit = str("statistics_unit_of_measurement")
                    ?: str("unit_of_measurement")
                StatisticId(
                    statisticId = sid,
                    name = str("name"),
                    source = str("source"),
                    unitOfMeasurement = unit,
                    hasMean = bool("has_mean"),
                    hasSum = bool("has_sum"),
                )
            }.sortedBy { (it.name?.lowercase()?.ifBlank { null } ?: it.statisticId.lowercase()) }
        }.onFailure { t ->
            R1Log.w("HaRepo.statistics", "list ids failed: ${t.message}")
        }
    }

    override suspend fun getStatisticsDuringPeriod(
        statisticIds: List<String>,
        start: java.time.Instant,
        end: java.time.Instant,
        period: String,
    ): Result<Map<String, List<StatisticsBucket>>> = withContext(Dispatchers.IO) {
        if (statisticIds.isEmpty()) {
            return@withContext Result.success(emptyMap())
        }
        val extras = kotlinx.serialization.json.buildJsonObject {
            put("start_time", JsonPrimitive(start.toString()))
            put("end_time", JsonPrimitive(end.toString()))
            put(
                "statistic_ids",
                kotlinx.serialization.json.buildJsonArray {
                    statisticIds.forEach { add(JsonPrimitive(it)) }
                },
            )
            put("period", JsonPrimitive(period))
            // Ask for every aggregation type the recorder might have stored;
            // the parser silently drops the ones HA didn't fill in. Smaller
            // type-filter would mean re-fetching when the user flips the
            // aggregation chip, which would feel laggy.
            put(
                "types",
                kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive("mean"))
                    add(JsonPrimitive("min"))
                    add(JsonPrimitive("max"))
                    add(JsonPrimitive("sum"))
                    add(JsonPrimitive("state"))
                    add(JsonPrimitive("change"))
                },
            )
        }
        callWsExpectingPayload("recorder/statistics_during_period", extras).mapCatching { payload ->
            val obj = payload as? kotlinx.serialization.json.JsonObject
                ?: error("recorder/statistics_during_period returned a non-object payload")
            decodeStatisticsBuckets(obj, period)
        }.onFailure { t ->
            R1Log.w("HaRepo.statistics", "period fetch failed: ${t.message}")
        }
    }


    /**
     * Fetch Energy dashboard user prefs via the `energy/get_prefs` WS command.
     * Parses `device_consumption[].stat_consumption` (entity/stat id) and
     * `device_consumption[].name` (optional custom display name) into a map.
     * Entries with a blank or absent `name` are omitted so callers can do
     * `map[id] ?: fallback` without extra null-checks.
     *
     * Best-effort: any parse error or transport failure returns an empty map;
     * no error is surfaced to the user because a missing custom-name overlay
     * is a graceful degradation, not a hard failure.
     *
     * UNVERIFIED OFFLINE: the `energy/get_prefs` WS command and its
     * `device_consumption[].name` field have not been tested against a live
     * Home Assistant instance. The shape follows HA's energy websocket API docs.
     */
    override suspend fun getEnergyPrefs(): Result<Map<String, String>> =
        withContext(Dispatchers.IO) {
            callWsExpectingPayload("energy/get_prefs").mapCatching { payload ->
                parseEnergyPrefsJson(payload)
            }.recoverCatching { t ->
                R1Log.w("HaRepo.energyPrefs", "get_prefs failed (best-effort): ${t.message}")
                emptyMap()
            }
        }

    override suspend fun fetchPanels(): Result<List<HaPanel>> = withContext(Dispatchers.IO) {
        callWsExpectingPayload("get_panels").mapCatching { payload ->
            // HA returns an object keyed by url_path: { "lovelace": { ... }, "hacs": { ... } }
            val obj = payload as? kotlinx.serialization.json.JsonObject
                ?: return@mapCatching emptyList()
            obj.entries.mapNotNull { (urlPath, value) ->
                val panel = value as? kotlinx.serialization.json.JsonObject
                    ?: return@mapNotNull null
                fun str(key: String): String? =
                    (panel[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                HaPanel(
                    urlPath = urlPath,
                    title = str("title"),
                    icon = str("icon"),
                    componentName = str("component_name") ?: return@mapNotNull null,
                )
            }
        }.onFailure { t ->
            R1Log.w("HaRepo.panels", "get_panels failed: ${t.message}")
        }
    }

    /**
     * Fetch the full Energy dashboard preferences (sources + per-device meters)
     * via `energy/get_prefs`, decoded into [EnergyPreferences]. Unlike
     * [getEnergyPrefs] this surfaces a failure to the caller so the energy
     * cards can render a "no energy config" state instead of silently empty.
     *
     * UNVERIFIED OFFLINE: not exercised against a live HA energy setup.
     */
    override suspend fun getEnergyPreferencesFull(): Result<EnergyPreferences> =
        withContext(Dispatchers.IO) {
            callWsExpectingPayload("energy/get_prefs").mapCatching { payload ->
                parseEnergyPreferences(payload)
            }.onFailure { t ->
                R1Log.w("HaRepo.energyPrefs", "get_prefs (full) failed: ${t.message}")
            }
        }

    /**
     * Fetch `energy/info`: the auto-generated cost-sensor map. Best-effort; a
     * failure recovers to empty info so the sources table just omits the cost
     * column rather than failing the whole card.
     */
    override suspend fun getEnergyInfo(): Result<EnergyInfo> =
        withContext(Dispatchers.IO) {
            callWsExpectingPayload("energy/info").mapCatching { payload ->
                parseEnergyInfo(payload)
            }.recoverCatching { t ->
                R1Log.w("HaRepo.energyPrefs", "energy/info failed (best-effort): ${t.message}")
                EnergyInfo()
            }
        }

    /**
     * Variant of [simpleAuthedGetTail] that also reports the total body
     * size pre-truncation so callers can render an accurate "showing last
     * N of M bytes" hint. Memory profile is identical: bounded by
     * [maxBytes] + one 4 KB read buffer regardless of upstream size.
     */
    private suspend fun simpleAuthedGetTailWithSize(url: String, maxBytes: Int): ErrorLogTail? =
        withContext(Dispatchers.IO) {
            val t = tokens.load()
                ?: error("Authentication tokens missing. Sign out & reconnect from Settings.")
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${t.accessToken}")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 401) return@withContext null
                require(resp.isSuccessful) { "HTTP ${resp.code} for $url" }
                val source = resp.body?.source()
                    ?: return@withContext ErrorLogTail(body = "", truncated = false, totalBytes = 0L)
                val window = okio.Buffer()
                val tmp = okio.Buffer()
                val chunk = 4 * 1024L
                var totalRead = 0L
                while (true) {
                    val n = source.read(tmp, chunk)
                    if (n == -1L) break
                    totalRead += n
                    tmp.readAll(window)
                    val over = window.size - maxBytes
                    if (over > 0) window.skip(over)
                }
                val truncated = totalRead > window.size
                val tail = window.readUtf8()
                ErrorLogTail(body = tail, truncated = truncated, totalBytes = totalRead)
            }
        }

}

