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

    @Test fun short_circuit_response_is_not_fed_back_into_breaker() {
        val c = Clock()
        val t = AuthThrottle(failureThreshold = 1, baseBackoffMillis = 1_000L, clock = c::millis)
        t.recordAuthFailure() // open at now=0, backoff 1000 -> openUntil 1000
        val itc = AuthThrottleInterceptor(t)
        val hits = intArrayOf(0)
        // Repeated short-circuited calls within the backoff window must neither close the
        // breaker (would return 200 early) nor push its backoff out.
        repeat(5) {
            assertEquals(503, itc.intercept(FakeChain(req("/api/states"), 200, hits)).code)
        }
        assertEquals(0, hits[0]) // none reached the network
        // Exactly at the original openUntil the half-open probe is admitted, proving the
        // short-circuited 503s were not recorded as failures (which would extend backoff)
        // nor as successes (which would have closed it sooner).
        c.now = 1_000L
        assertEquals(200, itc.intercept(FakeChain(req("/api/states"), 200, hits)).code)
        assertEquals(1, hits[0])
    }

    @Test fun caps_concurrent_gated_requests() {
        // The throttle stays closed (every call returns 200), so this isolates the
        // concurrency cap: at most maxConcurrentGated requests may be in flight at once,
        // which is what keeps a bad poll tick from landing 5 simultaneous 401s.
        val t = AuthThrottle(clock = Clock()::millis)
        val itc = AuthThrottleInterceptor(t, maxConcurrentGated = 2)
        val release = java.util.concurrent.CountDownLatch(1)
        val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
        val maxSeen = java.util.concurrent.atomic.AtomicInteger(0)
        val completed = java.util.concurrent.atomic.AtomicInteger(0)

        val itClass = itc

        class BlockingChain(private val r: Request) : Interceptor.Chain {
            override fun request() = r
            override fun proceed(request: Request): Response {
                val now = inFlight.incrementAndGet()
                maxSeen.updateAndGet { m -> if (now > m) now else m }
                release.await()
                inFlight.decrementAndGet()
                return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("x").body("".toResponseBody(null)).build()
            }
            override fun connection() = null
            override fun call() = throw UnsupportedOperationException()
            override fun connectTimeoutMillis() = 0
            override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun readTimeoutMillis() = 0
            override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun writeTimeoutMillis() = 0
            override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        }

        val threads = (1..6).map {
            Thread {
                itClass.intercept(BlockingChain(req("/api/states")))
                completed.incrementAndGet()
            }.also { it.start() }
        }
        Thread.sleep(300) // let all six pile up against the semaphore
        assertEquals(2, inFlight.get()) // only the cap is admitted; the other four block
        assertEquals(2, maxSeen.get())
        release.countDown() // drain everyone
        threads.forEach { it.join(2000) }
        assertEquals(6, completed.get()) // all eventually proceed once slots free
        assertEquals(2, maxSeen.get())   // never more than the cap in flight at once
    }

    @Test fun dynamic_max_concurrent_resizes_the_gate_down() {
        // The gate starts at 4 but a dynamic supplier pins it to 1, so only one request may be
        // in flight at once — the strict-mode "max simultaneous requests = 1" path.
        val t = AuthThrottle(clock = Clock()::millis)
        val cap = java.util.concurrent.atomic.AtomicInteger(1)
        val itc = AuthThrottleInterceptor(t, maxConcurrentGated = 4, dynamicMaxConcurrent = { cap.get() })
        val release = java.util.concurrent.CountDownLatch(1)
        val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
        val maxSeen = java.util.concurrent.atomic.AtomicInteger(0)

        class BlockingChain(private val r: Request) : Interceptor.Chain {
            override fun request() = r
            override fun proceed(request: Request): Response {
                val now = inFlight.incrementAndGet()
                maxSeen.updateAndGet { m -> if (now > m) now else m }
                release.await()
                inFlight.decrementAndGet()
                return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("x").body("".toResponseBody(null)).build()
            }
            override fun connection() = null
            override fun call() = throw UnsupportedOperationException()
            override fun connectTimeoutMillis() = 0
            override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun readTimeoutMillis() = 0
            override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun writeTimeoutMillis() = 0
            override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        }

        val threads = (1..5).map {
            Thread { itc.intercept(BlockingChain(req("/api/states"))) }.also { it.start() }
        }
        Thread.sleep(300)
        assertEquals(1, inFlight.get()) // dynamic cap of 1 admits exactly one
        assertEquals(1, maxSeen.get())
        release.countDown()
        threads.forEach { it.join(2000) }
        assertEquals(1, maxSeen.get()) // never exceeded the cap
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
