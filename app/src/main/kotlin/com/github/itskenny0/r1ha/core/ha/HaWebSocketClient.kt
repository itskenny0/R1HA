package com.github.itskenny0.r1ha.core.ha

import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.atomic.AtomicInteger

/**
 * Low-level Home Assistant WebSocket client. Owns the WS lifecycle, performs auth handshake,
 * exposes inbound messages as a SharedFlow, and accepts outbound messages via [send].
 *
 * Reconnect/backoff/keepalive are layered on by HaRepository (Milestone 6); this class focuses on
 * "one connection, cleanly handled."
 */
class HaWebSocketClient internal constructor(
    private val http: OkHttpClient,
    internal val scope: CoroutineScope,
    /**
     * How long the handshake (Connecting -> Authenticating -> Connected) may take before the
     * watchdog force-fails it to [ConnectionState.Disconnected]. See [ReconnectDecisions] for the
     * full rationale: OkHttp bounds only TCP/TLS establishment, so a half-open socket whose HA
     * backend never sends `auth_required` would otherwise leave the state stuck on Connecting
     * forever (none of onOpen/onClosed/onFailure ever runs), which is the "connecting forever
     * until restart" bug. Injectable so the unit test can use a tiny budget; a value <= 0 disables
     * the watchdog entirely (tests that drive a virtual clock and don't exercise the watchdog pass
     * 0 so advanceUntilIdle can't fast-forward into it).
     */
    private val handshakeWatchdogMillis: Long = ReconnectDecisions.HANDSHAKE_WATCHDOG_MS,
) {
    constructor() : this(http = OkHttpClient(), scope = CoroutineScope(SupervisorJob()))

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _inbound = MutableSharedFlow<HaInbound>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val inbound: SharedFlow<HaInbound> = _inbound.asSharedFlow()

    /**
     * Raw inbound text frames. Emitted alongside [inbound] so consumers that need the
     * full JSON payload (template subscriptions whose `event` frames don't match the
     * state-trigger shape HaInbound.Event expects, future commands we haven't typed
     * yet) can read the original text and parse on their own terms. Same backpressure
     * shape as [_inbound]: bounded buffer with DROP_OLDEST so a stalled consumer can't
     * grow memory unbounded.
     */
    private val _inboundRawText = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val inboundRawText: SharedFlow<String> = _inboundRawText.asSharedFlow()

    private val nextId = AtomicInteger(1)
    fun nextRequestId(): Int = nextId.getAndIncrement()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var receiverJob: Job? = null
    /**
     * Bounds the handshake. Started when [connect] enters [ConnectionState.Connecting]; cancelled
     * the instant the connection settles (Connected, AuthLost, Disconnected, or a fresh connect /
     * disconnect). If it fires, the handshake never completed — see [forceFailStaleHandshake].
     */
    @Volatile private var handshakeWatchdogJob: Job? = null
    /**
     * Outgoing-message buffer. Bounded with DROP_OLDEST so a stalled network can't grow the
     * queue without bound — the wheel can fire 20+ events/sec, and if the WS sender stalls
     * (network latency spike, OS suspending sockets during a wake-up) those would otherwise
     * accumulate in memory and replay as a stale flurry once the link resumes. Dropping
     * oldest is correct: every wheel event already supersedes the previous via the per-key
     * trailing debouncer, so an older queued message is by construction outdated.
     *
     * Capacity 256 is plenty for any realistic burst (a user can't physically generate that
     * many discrete intents); it just caps the worst case during a network stall.
     */
    private val outgoing = Channel<HaOutbound>(
        capacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    fun connect(url: String, accessToken: String) {
        connect(url) { accessToken }
    }

    /**
     * Connect variant where the access token is fetched at AuthRequired time via [tokenProvider].
     * Prefer this in production wiring: the repository can rotate the token between the call to
     * connect() and the WS handshake (token refresh fires during the connecting window), and a
     * captured-by-closure constructor argument would otherwise hand HA an already-revoked token.
     */
    fun connect(url: String, tokenProvider: () -> String) {
        // Allow connect from Idle, Disconnected, AND AuthLost: when the repository succeeds at
        // refreshing the access token after an auth-rejected handshake, it has to be able to
        // reconnect even though the state is still pinned at AuthLost.
        val canReconnect = _state.value is ConnectionState.Idle ||
            _state.value is ConnectionState.Disconnected ||
            _state.value is ConnectionState.AuthLost
        if (!canReconnect) return
        // A fresh attempt supersedes any previous watchdog (a rapid Disconnected -> connect bounce
        // could leave the old one armed against a socket we're about to replace).
        handshakeWatchdogJob?.cancel()
        _state.value = ConnectionState.Connecting
        R1Log.w("HaWS.connect", "connecting; arming handshake watchdog (${handshakeWatchdogMillis}ms)")
        val req = Request.Builder().url(url).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, resp: Response) {
                // webSocket was already set from the http.newWebSocket() return value below so
                // the onFailure/onClosed guards work even if the connection fails before onOpen
                // is delivered. Guard against a stale onOpen too: if disconnect() ran or a new
                // connection has replaced this one, don't bump state to Authenticating.
                if (this@HaWebSocketClient.webSocket !== ws) return
                _state.value = ConnectionState.Authenticating
                // Drain outgoing on a regular dispatched coroutine so the for-loop doesn't
                // park OkHttp's listener thread; UNDISPATCHED would force the first iteration
                // to run on the WS callback thread, blocking it until the channel suspends.
                receiverJob = scope.launch {
                    for (msg in outgoing) ws.send(HaJson.encodeToString(msg))
                }
            }
            override fun onMessage(ws: WebSocket, text: String) {
                // Ignore messages from a WebSocket that has been replaced or torn down: without
                // this guard, a late AuthOk from a previous connection could bump the state back
                // to Connected after the user has signed out (or while a new connection is mid-
                // authenticating).
                if (this@HaWebSocketClient.webSocket !== ws) return
                val msg = runCatching { HaJson.decodeFromString<HaInbound>(text) }
                    .getOrElse { HaInbound.Unknown }
                when (msg) {
                    is HaInbound.AuthRequired -> {
                        // Read the access token at handshake time, not at connect() invocation
                        // time. Between the two, the repository may have rotated the token (a
                        // refresh fired during the connecting window); the captured-by-closure
                        // value would otherwise hand HA an already-revoked token.
                        val token = runCatching { tokenProvider() }.getOrNull().orEmpty()
                        ws.send(HaJson.encodeToString<HaOutbound>(HaOutbound.Auth(token)))
                    }
                    is HaInbound.AuthOk -> {
                        // Handshake completed — disarm the watchdog before it can fire.
                        handshakeWatchdogJob?.cancel()
                        _state.value = ConnectionState.Connected(msg.haVersion)
                    }
                    is HaInbound.AuthInvalid -> {
                        // A definitive auth rejection IS a settled handshake; disarm.
                        handshakeWatchdogJob?.cancel()
                        _state.value = ConnectionState.AuthLost(msg.message)
                        ws.close(1000, "auth_invalid")
                    }
                    else -> Unit
                }
                _inbound.tryEmit(msg)
                // Also publish the raw text frame so consumers that need full payload
                // access (template subscriptions, future commands) don't pay the round-
                // trip of re-serialising the parsed model.
                _inboundRawText.tryEmit(text)
            }
            override fun onMessage(ws: WebSocket, bytes: ByteString) { /* HA only sends text */ }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                // Ignore stale callbacks: if disconnect() already ran (webSocket=null) or a new
                // connection has replaced this one (webSocket != ws), don't downgrade the state
                // to Disconnected — that would briefly flash "Disconnected (server closed)" on
                // the About page right after sign-out.
                if (this@HaWebSocketClient.webSocket !== ws) return
                // Preserve a sticky AuthLost — we just called ws.close() ourselves in response
                // to AuthInvalid; the resulting onClosed would otherwise overwrite a meaningful
                // "auth invalid" message with the generic "server closed".
                // The connection settled (server closed it); the handshake watchdog is moot.
                handshakeWatchdogJob?.cancel()
                if (_state.value !is ConnectionState.AuthLost) {
                    _state.value = ConnectionState.Disconnected(ConnectionState.Cause.ServerClosed, attempt = 0)
                }
                receiverJob?.cancel(); webSocket = null
            }
            override fun onFailure(ws: WebSocket, t: Throwable, resp: Response?) {
                if (this@HaWebSocketClient.webSocket !== ws) return
                // The connection settled (transport failure); the handshake watchdog is moot.
                handshakeWatchdogJob?.cancel()
                if (_state.value !is ConnectionState.AuthLost) {
                    _state.value = ConnectionState.Disconnected(ConnectionState.Cause.Error(t), attempt = 0)
                }
                receiverJob?.cancel(); webSocket = null
            }
        }
        // Store the WebSocket immediately so the listener's stale-callback guard works for
        // failures that fire before onOpen (e.g. DNS failure, TLS handshake error).
        val newSocket = http.newWebSocket(req, listener)
        webSocket = newSocket
        // Arm the handshake watchdog AFTER the socket exists so it can guard exactly this
        // attempt. If neither AuthOk/AuthInvalid nor onClosed/onFailure has settled the state by
        // the deadline, the socket is half-open (the root cause of "connecting forever"): tear it
        // down and synthesise a Disconnected so HaRepository's reconnect loop takes over. A budget
        // of <= 0 disables the watchdog (test-only path).
        if (handshakeWatchdogMillis > 0) {
            handshakeWatchdogJob = scope.launch {
                delay(handshakeWatchdogMillis)
                forceFailStaleHandshake(newSocket)
            }
        }
    }

    /**
     * Watchdog body — runs only when the handshake has been silent for [handshakeWatchdogMillis].
     * Guards against three races before acting: a newer connection replaced [guarded] (so this
     * watchdog belongs to a dead attempt), the connection already reached Connected, or it already
     * settled to AuthLost. In all of those the legitimate callback already disarmed us; the extra
     * checks are belt-and-braces for the window between a callback firing and its `cancel()` taking
     * effect. Otherwise it closes the wedged socket and transitions to Disconnected so the
     * repository schedules a backoff reconnect.
     */
    private fun forceFailStaleHandshake(guarded: WebSocket) {
        if (webSocket !== guarded) return
        val current = _state.value
        if (current is ConnectionState.Connected || current is ConnectionState.AuthLost) return
        R1Log.w(
            "HaWS.watchdog",
            "handshake stuck in ${current::class.simpleName} for ${handshakeWatchdogMillis}ms; " +
                "forcing Disconnected so reconnect can take over",
        )
        // close() may be a no-op on an already-dead socket; that's fine. Null the ref first so the
        // resulting onClosed/onFailure (if any) is treated as stale and doesn't double-fire state.
        webSocket = null
        receiverJob?.cancel()
        runCatching { guarded.close(4000, "handshake_timeout") }
        guarded.cancel()
        _state.value = ConnectionState.Disconnected(
            ConnectionState.Cause.Error(IllegalStateException("Handshake timed out")),
            attempt = 0,
        )
    }

    /**
     * Synchronously send a raw JSON text frame on the live WebSocket. Returns true if the
     * frame was accepted by OkHttp (which usually means "queued for socket write"), false if
     * the socket is not open or send is rejected.
     *
     * Used by repository methods that need to invoke WS-only HA commands (`repairs/list_issues`,
     * `backup/info`, `media_player/browse_media`, etc.) without each one having to declare a
     * type-safe [HaOutbound] variant. Caller is responsible for assigning a unique [HaInbound.Result.id]
     * via [nextRequestId] and registering the awaiter before sending so the inbound reply isn't
     * dropped by the deserialiser's default Unknown sink.
     *
     * Bypasses the outgoing channel + DROP_OLDEST guard because the typical caller is a
     * user-initiated query (Repairs screen open, Backups screen refresh) rather than a
     * high-rate gesture-driven event; the wheel-debounce backpressure doesn't apply.
     */
    fun sendRawText(text: String): Boolean {
        val ws = webSocket ?: return false
        return ws.send(text)
    }

    /**
     * Send a raw binary frame on the live WebSocket. Used by the assist-pipeline
     * voice satellite to stream PCM audio frames at the `stt_binary_handler_id`
     * byte prefix that HA's pipeline expects. Bypasses the JSON outgoing queue;
     * audio is high-rate and the frames are already minimal, so the channel
     * abstraction would just add latency.
     *
     * Returns false if no socket is connected (callers should check connection
     * state before opening the mic so they don't drop audio into the void).
     */
    fun sendRawBytes(bytes: ByteArray): Boolean {
        val ws = webSocket ?: return false
        return ws.send(ByteString.of(*bytes))
    }

    /** Enqueue an outbound message. Safe to call before [connect] has completed; the queue drains once connected. */
    fun send(msg: HaOutbound) {
        val result = outgoing.trySend(msg)
        if (result.isFailure) {
            // DROP_OLDEST means a backed-up channel silently discards the oldest queued frame
            // when a new one is offered. For wheel-driven debounced calls this is correct
            // (the trailing-edge value supersedes), but for one-shot CallService frames a
            // drop means the caller's deferred in pendingCalls will hang until the 15s
            // timeout. Surface it so users can see "WS overloaded" in the dev log rather
            // than wondering why a tap silently did nothing.
            R1Log.w("HaWS.send", "outbound queue dropped a $msg (channel full or closed)")
        }
    }

    fun disconnect(code: Int = 1000, reason: String = "client_disconnect") {
        handshakeWatchdogJob?.cancel()
        webSocket?.close(code, reason)
        webSocket = null
        receiverJob?.cancel()
        // Drain any queued outbound messages so they don't get replayed against a different
        // server when the user signs in again. This was an actual leak: a wheel debounce that
        // fired right before sign-out would sit in the channel and execute on the next
        // server's entities (mostly errors, occasionally surprising state changes if entity
        // IDs collided).
        while (outgoing.tryReceive().isSuccess) { /* discard */ }
        // Reset the request-id counter so a fresh connection starts at 1. HA tolerates any
        // monotonic sequence, but starting from a clean state makes logs/trace easier to
        // read across sign-out + sign-in cycles, and any straggler pendingCalls referencing
        // the old id range can no longer collide with a new request.
        nextId.set(1)
        _state.value = ConnectionState.Idle
    }
}
