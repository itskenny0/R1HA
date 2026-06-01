package com.github.itskenny0.r1ha.core.ha

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthThrottleInterceptorTest {
    private class Clock(var now: Long = 0L) { fun millis() = now }

    /** Fake chain that returns a canned code and counts how many times the network was hit. */
    private class FakeChain(
        private val req: Request,
        private val responseCode: Int,
        val hits: IntArray,
    ) : Interceptor.Chain {
        override fun request() = req
        override fun proceed(request: Request): Response {
            hits[0]++
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(responseCode).message("x").body("".toResponseBody(null)).build()
        }
        // Unused chain members — return harmless defaults / throw.
        override fun connection() = null
        override fun call() = throw UnsupportedOperationException()
        override fun connectTimeoutMillis() = 0
        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun readTimeoutMillis() = 0
        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun writeTimeoutMillis() = 0
        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
    }

    private fun req(path: String) = Request.Builder().url("http://h$path").build()

    @Test fun exempts_auth_path() {
        val c = Clock()
        val t = AuthThrottle(failureThreshold = 1, clock = c::millis)
        t.recordAuthFailure() // open
        val hits = intArrayOf(0)
        val resp = AuthThrottleInterceptor(t)
            .intercept(FakeChain(req("/auth/token"), 200, hits))
        assertEquals(200, resp.code)
        assertEquals(1, hits[0]) // network was hit despite open breaker
    }

    @Test fun short_circuits_api_when_open() {
        val c = Clock()
        val t = AuthThrottle(failureThreshold = 1, clock = c::millis)
        t.recordAuthFailure() // open
        val hits = intArrayOf(0)
        val resp = AuthThrottleInterceptor(t)
            .intercept(FakeChain(req("/api/states"), 200, hits))
        assertEquals(503, resp.code)
        assertEquals(0, hits[0]) // network NOT hit
    }

    @Test fun records_401_and_eventually_opens() {
        val c = Clock()
        val t = AuthThrottle(failureThreshold = 2, clock = c::millis)
        val itc = AuthThrottleInterceptor(t)
        val hits = intArrayOf(0)
        itc.intercept(FakeChain(req("/api/states"), 401, hits))
        // one 401 recorded; still closed -> next call reaches network
        itc.intercept(FakeChain(req("/api/states"), 401, hits))
        // second 401 -> open; third call short-circuits
        val resp = itc.intercept(FakeChain(req("/api/states"), 200, hits))
        assertEquals(503, resp.code)
    }

    @Test fun success_keeps_closed() {
        val c = Clock()
        val t = AuthThrottle(failureThreshold = 2, clock = c::millis)
        val itc = AuthThrottleInterceptor(t)
        val hits = intArrayOf(0)
        repeat(5) { itc.intercept(FakeChain(req("/api/states"), 200, hits)) }
        assertEquals(5, hits[0]) // never short-circuited
    }

    @Test fun websocket_path_exempt() {
        val c = Clock()
        val t = AuthThrottle(failureThreshold = 1, clock = c::millis)
        t.recordAuthFailure()
        val hits = intArrayOf(0)
        val resp = AuthThrottleInterceptor(t)
            .intercept(FakeChain(req("/api/websocket"), 101, hits))
        assertEquals(101, resp.code)
        assertEquals(1, hits[0])
    }
}
