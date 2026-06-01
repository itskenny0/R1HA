package com.github.itskenny0.r1ha.core.ha

/**
 * Rolling-window circuit breaker for the REST auth path. Counts HTTP 401s within a
 * sliding [windowMillis]; once [failureThreshold] land in the window it OPENs for an
 * exponentially growing backoff ([baseBackoffMillis], doubling per consecutive reopen,
 * capped at [maxBackoffMillis]). While OPEN, [shouldShortCircuit] returns true so the
 * caller can fail fast WITHOUT a network request — which is the whole point: a revoked
 * refresh token otherwise has every polling tick fire a fan-out of 401s that HA logs as
 * failed logins and eventually IP-bans.
 *
 * When the backoff elapses the breaker goes HALF_OPEN and admits exactly one probe;
 * concurrent callers keep short-circuiting until the probe resolves. A probe success
 * ([recordSuccess]) closes the breaker and clears the backoff growth; a probe failure
 * ([recordAuthFailure]) reopens it with the next (longer) backoff.
 *
 * Thread-safe: the OkHttp interceptor calls this from arbitrary network threads.
 * [clock] is injectable so tests drive time deterministically.
 */
class AuthThrottle(
    private val windowMillis: Long = 60_000L,
    private val failureThreshold: Int = 4,
    private val baseBackoffMillis: Long = 30_000L,
    private val maxBackoffMillis: Long = 900_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private enum class State { CLOSED, OPEN, HALF_OPEN }

    private val lock = Any()
    private val failures = ArrayDeque<Long>()
    private var state = State.CLOSED
    private var openUntil = 0L
    private var consecutiveOpens = 0
    private var probeInFlight = false

    /** Returns true when the caller should fail fast without hitting the network.
     *  Has the side effect of admitting a single half-open probe when the backoff
     *  has elapsed. */
    fun shouldShortCircuit(): Boolean = synchronized(lock) {
        when (state) {
            State.CLOSED -> false
            State.HALF_OPEN -> true // a probe is already in flight; everyone else waits
            State.OPEN -> {
                if (clock() >= openUntil) {
                    state = State.HALF_OPEN
                    probeInFlight = true
                    false // admit exactly this one probe
                } else {
                    true
                }
            }
        }
    }

    /** Record a 401 (or a failed half-open probe). */
    fun recordAuthFailure() = synchronized(lock) {
        if (state == State.HALF_OPEN) {
            reopen()
            return
        }
        val now = clock()
        failures.addLast(now)
        while (failures.isNotEmpty() && now - failures.first() > windowMillis) failures.removeFirst()
        if (state == State.CLOSED && failures.size >= failureThreshold) reopen()
    }

    /** Record any successful (2xx) gated response, or a successful half-open probe. */
    fun recordSuccess() = synchronized(lock) {
        state = State.CLOSED
        failures.clear()
        consecutiveOpens = 0
        probeInFlight = false
        openUntil = 0L
    }

    /** Manual reset (user tapped retry / server changed). */
    fun reset() = recordSuccess()

    private fun reopen() {
        // backoff = base * 2^(consecutiveOpens), capped. Use shift but guard overflow.
        val shift = consecutiveOpens.coerceAtMost(20)
        val grown = baseBackoffMillis shl shift
        val backoff = if (grown <= 0L) maxBackoffMillis else grown.coerceAtMost(maxBackoffMillis)
        state = State.OPEN
        openUntil = clock() + backoff
        probeInFlight = false
        failures.clear()
        consecutiveOpens++
    }
}
