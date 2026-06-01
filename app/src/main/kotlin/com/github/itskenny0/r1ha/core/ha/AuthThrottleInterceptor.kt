package com.github.itskenny0.r1ha.core.ha

import okhttp3.Interceptor
import okhttp3.Protocol
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
 */
class AuthThrottleInterceptor(private val throttle: AuthThrottle) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val path = req.url.encodedPath
        val gated = path.startsWith("/api/") && path != "/api/websocket"
        if (!gated) return chain.proceed(req)

        if (throttle.shouldShortCircuit()) {
            return Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(503)
                .message("auth-throttled")
                .body("".toResponseBody(null))
                .build()
        }

        val resp = chain.proceed(req)
        when {
            resp.code == 401 -> throttle.recordAuthFailure()
            resp.isSuccessful -> throttle.recordSuccess()
        }
        return resp
    }
}
