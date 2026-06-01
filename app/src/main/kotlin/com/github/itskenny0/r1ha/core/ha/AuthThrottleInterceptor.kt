package com.github.itskenny0.r1ha.core.ha

import java.util.concurrent.Semaphore
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Feeds REST auth outcomes into [throttle] and short-circuits gated requests while the
 * breaker is open. Gates only paths under `/api/`. Exempt:
 *  - paths under `/auth/` — token refresh must keep trying so the app can recover.
 *  - `/api/websocket` — the WebSocket has its own reconnect guard; its auth failures
 *                       arrive as WS messages, not HTTP 401s, so they never reach here.
 *
 * A short-circuited request returns a synthetic 503 without touching the network, so HA
 * never sees the request and logs no failed login.
 *
 * The gate matches HA served at the URL root (paths begin with `/api/`). Behind a
 * reverse proxy that mounts HA under a subpath (e.g. `/ha/api/...`) the gate simply
 * never engages — the breaker is a no-op rather than a hazard, since the same subpath
 * shifts the websocket URL too, so nothing is wrongly short-circuited.
 *
 * [maxConcurrentGated] caps how many gated requests may hit the network at once. OkHttp's
 * dispatcher allows 5 concurrent requests per host by default, and a single bad poll tick
 * (the dashboard fans out ~11 calls) would otherwise land up to 5 simultaneous 401s before
 * the breaker could open — enough to trip a strict HA login-attempt ban on its own. Holding
 * the fan-out to a couple at a time, and re-checking the breaker after acquiring a slot,
 * bounds the number of 401s that escape before the breaker opens to roughly this cap.
 *
 * [dynamicMaxConcurrent], when supplied, lets the cap follow a user setting at runtime: it is
 * read on every request and the gate is resized to match (see [ResizableGate]). The default
 * (null) keeps the gate fixed at [maxConcurrentGated], which is what the unit tests rely on.
 * Each client that shares a single [throttle] gets its OWN interceptor instance (hence its own
 * gate), so a slow camera poll never starves REST calls of slots — only the breaker STATE is
 * shared, which is the point: the first stale-token 401 from any client opens it for all.
 */
class AuthThrottleInterceptor(
    private val throttle: AuthThrottle,
    maxConcurrentGated: Int = 2,
    private val dynamicMaxConcurrent: (() -> Int)? = null,
) : Interceptor {
    private val gate = ResizableGate(maxConcurrentGated)

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val path = req.url.encodedPath
        val gated = path.startsWith("/api/") && path != "/api/websocket"
        if (!gated) return chain.proceed(req)

        // Already open: fail fast without queuing for a slot or touching the network.
        if (throttle.shouldShortCircuit()) return throttled(req)

        // Track the user-configured cap before we queue for a slot. Resizing here (rather than
        // on a settings collector) keeps the gate lock-free of any coroutine plumbing.
        dynamicMaxConcurrent?.let { gate.resize(it()) }
        gate.acquire()
        try {
            // Re-check after waiting for a slot: an earlier request in this same burst may
            // have got a 401 and opened the breaker while we queued. Bailing here is what
            // keeps the escaped-401 count down to the concurrency cap. Use the non-mutating
            // isOpenNow() rather than shouldShortCircuit() so we don't consume the single
            // half-open probe that shouldShortCircuit() just admitted above.
            if (throttle.isOpenNow()) return throttled(req)
            val resp = chain.proceed(req)
            when {
                resp.code == 401 -> throttle.recordAuthFailure()
                resp.isSuccessful -> throttle.recordSuccess()
            }
            return resp
        } finally {
            gate.release()
        }
    }

    private fun throttled(req: Request): Response =
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(503)
            .message("auth-throttled")
            .body("".toResponseBody(null))
            .build()
}

/**
 * A counting semaphore whose permit ceiling can change at runtime. [Semaphore] only grows via
 * [Semaphore.release] and shrinks via [Semaphore.reducePermits]; this wraps both behind a single
 * [resize] so callers can follow a user setting. The invariant `available = max - inFlight` is
 * preserved: growing releases the delta, shrinking reduces it (which may make permits go briefly
 * negative while requests in excess of the new cap drain — exactly the intended throttling).
 *
 * Permits never drop below 1 so the gate can't deadlock itself shut on a stray 0 from settings.
 */
private class ResizableGate(initialMax: Int) {
    // Subclass to reach Semaphore.reducePermits, which is protected on the base class.
    private class ShrinkableSemaphore(permits: Int) : Semaphore(permits, /* fair = */ true) {
        public override fun reducePermits(reduction: Int) = super.reducePermits(reduction)
    }

    private val sem = ShrinkableSemaphore(initialMax.coerceAtLeast(1))
    private var max = initialMax.coerceAtLeast(1)

    @Synchronized fun resize(newMax: Int) {
        val n = newMax.coerceAtLeast(1)
        when {
            n > max -> sem.release(n - max)
            n < max -> sem.reducePermits(max - n)
        }
        max = n
    }

    fun acquire() = sem.acquire()
    fun release() = sem.release()
}
