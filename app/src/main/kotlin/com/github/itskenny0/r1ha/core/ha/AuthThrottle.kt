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
    windowMillis: Long = 60_000L,
    // Open after the very first 401 by default. Healthy auth produces ~0 sustained 401s (the
    // token is refreshed proactively and any success clears the window), so a threshold of 1
    // rarely false-trips, while a strict HA that bans after a handful of failed logins needs
    // the breaker to engage before that handful accumulates — and once the camera/image
    // clients also feed this breaker, the first stale-token 401 from any of them is the right
    // moment to short-circuit the rest of the burst.
    failureThreshold: Int = 1,
    // Short first backoff so a transient blip recovers quickly via the half-open probe; the
    // exponential growth still pulls genuinely-broken auth out to long, sparse retries.
    baseBackoffMillis: Long = 15_000L,
    private val maxBackoffMillis: Long = 900_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private enum class State { CLOSED, OPEN, HALF_OPEN }

    private val lock = Any()
    private val failures = ArrayDeque<Long>()
    private var state = State.CLOSED
    private var openUntil = 0L
    private var consecutiveOpens = 0

    // Live-tunable breaker parameters. Held as volatile vars (seeded from the constructor) so
    // a settings change — e.g. the user toggling strict connection mode — re-tunes the breaker
    // in place without rebuilding the OkHttp client. Mutated only under [lock] via [applyConfig];
    // read under [lock] in the decision paths below.
    @Volatile private var windowMillis: Long = windowMillis
    @Volatile private var failureThreshold: Int = failureThreshold
    @Volatile private var baseBackoffMillis: Long = baseBackoffMillis

    /**
     * Re-tune the breaker thresholds at runtime. Values are clamped to sane floors so a stray 0
     * can't disable the breaker. Does not retroactively shorten an already-open backoff: the new
     * values take effect from the next [recordAuthFailure] / [reopen]. Idempotent and cheap, so
     * a settings collector can call it on every emission.
     */
    fun applyConfig(
        failureThreshold: Int,
        baseBackoffMillis: Long,
        windowMillis: Long = this.windowMillis,
    ) = synchronized(lock) {
        this.failureThreshold = failureThreshold.coerceAtLeast(1)
        this.baseBackoffMillis = baseBackoffMillis.coerceAtLeast(1_000L)
        this.windowMillis = windowMillis.coerceAtLeast(1_000L)
    }

    /** Returns true when the caller should fail fast without hitting the network.
     *  Has the side effect of admitting a single half-open probe when the backoff
     *  has elapsed. */
    fun shouldShortCircuit(): Boolean = synchronized(lock) {
        when (state) {
            State.CLOSED -> false
            State.HALF_OPEN -> true // a probe is already in flight; everyone else waits
            State.OPEN -> {
                if (clock() >= openUntil) {
                    // Admit exactly this one probe: once HALF_OPEN, every other caller
                    // takes the branch above and short-circuits until the probe resolves.
                    state = State.HALF_OPEN
                    false
                } else {
                    true
                }
            }
        }
    }

    /** Non-mutating check: is the breaker currently tripped (OPEN and still inside its
     *  backoff window)? Used for the interceptor's re-check after it has acquired a
     *  concurrency slot, so a request that queued while an earlier one in the same burst
     *  opened the breaker bails without hitting the network. Unlike [shouldShortCircuit]
     *  this has no side effect, so it never consumes the single half-open probe. */
    fun isOpenNow(): Boolean = synchronized(lock) {
        state == State.OPEN && clock() < openUntil
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
        failures.clear()
        consecutiveOpens++
    }
}
