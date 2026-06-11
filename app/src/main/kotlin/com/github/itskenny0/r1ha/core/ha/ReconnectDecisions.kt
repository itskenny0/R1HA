package com.github.itskenny0.r1ha.core.ha

/**
 * Pure decision logic for the reconnect state machine, extracted from
 * [DefaultHaRepository] / [HaWebSocketClient] / [com.github.itskenny0.r1ha.MainActivity]
 * so the wedge-detection, watchdog, and resume-nudge rules can be reasoned about and
 * unit-tested without spinning up a WebSocket or an Activity.
 *
 * Background — the "connecting forever" bug:
 *   The WS handshake has three legs after the socket opens at the TCP layer: HA must send
 *   `auth_required`, the client replies `Auth`, HA replies `AuthOk` / `AuthInvalid`. OkHttp's
 *   `connectTimeout` only covers TCP/TLS establishment; once `onOpen` fires nothing bounds how
 *   long HA can take to deliver `auth_required`, and a reverse proxy that ACKs frames at the
 *   transport layer while the HA backend never answers leaves the socket in a half-open state
 *   where none of OkHttp's `onClosed` / `onFailure` callbacks ever run. The connection state
 *   stays [ConnectionState.Connecting] / [ConnectionState.Authenticating] forever, the
 *   repository's reconnect loop (which only schedules backoff from [ConnectionState.Disconnected])
 *   never fires, and the UI shows "connecting…" indefinitely. Restarting the process rebuilds
 *   the WS client fresh at [ConnectionState.Idle], so a brand-new socket completes the handshake
 *   normally — which is exactly why "restart fixes it instantly".
 */
object ReconnectDecisions {

    /**
     * How long the handshake (Connecting → Authenticating → Connected) may take before the
     * watchdog force-fails it. Generous enough that a slow-but-healthy server on a high-latency
     * link finishes well inside it (HA's `auth_required` lands within a second of the socket
     * opening on any working install), tight enough that a wedged half-open socket is rescued
     * within ~20 s instead of never.
     */
    const val HANDSHAKE_WATCHDOG_MS = 20_000L

    /**
     * Minimum age a Connecting / Authenticating state must reach before an external nudge
     * (foreground resume) is allowed to treat it as wedged and force a fresh connect. Below
     * this we assume the in-flight handshake is healthy and leave it alone, so a resume that
     * lands milliseconds into a legitimate connect doesn't thrash the WS client. Sits below
     * [HANDSHAKE_WATCHDOG_MS] so the resume path can rescue a wedge the watchdog hasn't yet
     * reached (e.g. the app was backgrounded, its timers throttled, and the watchdog coroutine
     * was starved of wall-clock progress while doze held the process).
     */
    const val RESUME_STALE_CONNECTING_MS = 15_000L

    /** What [resumeNudgeAction] decided the foreground-resume handler should do. */
    enum class ResumeAction {
        /** Connection is healthy or genuinely in-flight; do nothing. */
        NONE,

        /** Not connected and nothing useful in flight; kick a fresh reconnect. */
        KICK,

        /**
         * State is Connecting / Authenticating but it has been stuck longer than
         * [RESUME_STALE_CONNECTING_MS]; the in-flight attempt is presumed wedged, so force a
         * fresh connect even though a naive guard would skip a Connecting state.
         */
        RESCUE_STALE,
    }

    /**
     * Whether a handshake that entered [ConnectionState.Connecting] /
     * [ConnectionState.Authenticating] at [connectingSinceMillis] should be considered wedged as
     * of [nowMillis]. Used by both the in-WS watchdog (with its full [HANDSHAKE_WATCHDOG_MS]
     * budget) and the resume nudge (with the shorter [RESUME_STALE_CONNECTING_MS]).
     *
     * Both clocks must be the SAME monotonic source ([android.os.SystemClock.elapsedRealtime]
     * in production) so the comparison survives a wall-clock jump (NTP correction, the user
     * changing the device time) and counts doze time. A negative or zero [thresholdMillis]
     * treats any handshake as immediately stale; a [connectingSinceMillis] in the future
     * (clock went backwards) is clamped to "not stale yet".
     */
    fun isHandshakeStale(
        connectingSinceMillis: Long,
        nowMillis: Long,
        thresholdMillis: Long,
    ): Boolean {
        val age = nowMillis - connectingSinceMillis
        if (age < 0) return false
        return age >= thresholdMillis
    }

    /**
     * The action a foreground-resume should take given the current connection [state] and, when
     * the state is Connecting / Authenticating, how long it has been in flight
     * ([connectingAgeMillis], measured on the same monotonic clock).
     *
     * Decision matrix:
     *   - Connected                      -> NONE  (healthy)
     *   - AuthLost                       -> NONE  (repository owns its own refresh+reconnect loop;
     *                                              piling a resume kick on top just re-POSTs the
     *                                              same rejected token)
     *   - Idle / Disconnected            -> KICK  (nothing in flight; reconnect immediately)
     *   - Connecting / Authenticating    -> NONE while younger than [staleThresholdMillis]
     *                                       (healthy in-flight handshake), RESCUE_STALE once it
     *                                       crosses the threshold (presumed wedged half-open socket)
     *
     * [connectingAgeMillis] is ignored for non-handshake states; callers pass 0 there.
     */
    fun resumeNudgeAction(
        state: ConnectionState,
        connectingAgeMillis: Long,
        staleThresholdMillis: Long = RESUME_STALE_CONNECTING_MS,
    ): ResumeAction = when (state) {
        is ConnectionState.Connected -> ResumeAction.NONE
        is ConnectionState.AuthLost -> ResumeAction.NONE
        is ConnectionState.Idle, is ConnectionState.Disconnected -> ResumeAction.KICK
        is ConnectionState.Connecting, is ConnectionState.Authenticating ->
            if (isHandshakeStale(0L, connectingAgeMillis, staleThresholdMillis)) {
                ResumeAction.RESCUE_STALE
            } else {
                ResumeAction.NONE
            }
    }
}
