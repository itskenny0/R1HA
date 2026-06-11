package com.github.itskenny0.r1ha.core.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Wire-shape + queue + backoff tests for the log shipper. Pure JVM (no
 * Android / no network): the queue and backoff are plain in-memory logic and
 * NDJSON encoding is a hand-rolled string builder.
 */
class LogShipperTest {

    // ── NDJSON entry serialization ──────────────────────────────────────────

    @Test fun `info entry encodes the contract fields on one line`() {
        val line = encodeLine(
            LogEntry(
                session = "abc123",
                seq = 7,
                ts = "2026-06-11T09:00:00Z",
                level = LogEntry.LEVEL_INFO,
                tag = "App.onCreate",
                msg = "application starting",
            ),
        )
        assertThat(line).doesNotContain("\n")
        assertThat(line).contains("\"session\":\"abc123\"")
        assertThat(line).contains("\"seq\":7")
        assertThat(line).contains("\"ts\":\"2026-06-11T09:00:00Z\"")
        assertThat(line).contains("\"level\":\"info\"")
        assertThat(line).contains("\"tag\":\"App.onCreate\"")
        assertThat(line).contains("\"msg\":\"application starting\"")
        // A non-crash entry omits the crash-only fields entirely.
        assertThat(line).doesNotContain("\"type\"")
        assertThat(line).doesNotContain("\"resend\"")
    }

    @Test fun `crash entry adds type stack and resend`() {
        val line = encodeLine(
            LogEntry(
                session = "s",
                seq = 0,
                ts = "2026-06-11T09:00:00Z",
                level = LogEntry.LEVEL_ERROR,
                tag = "App.crash",
                msg = "boom",
                crash = true,
                stack = "java.lang.IllegalStateException: boom\n\tat Foo.bar(Foo.kt:1)",
                resend = true,
            ),
        )
        assertThat(line).contains("\"type\":\"crash\"")
        assertThat(line).contains("\"resend\":true")
        // The newline inside the stack trace must be escaped, never raw, so the
        // line stays a single NDJSON record.
        assertThat(line).doesNotContain("\n")
        assertThat(line).contains("\\n\\tat Foo.bar")
    }

    @Test fun `string escaping keeps the line valid for quotes and backslashes`() {
        val line = encodeLine(
            LogEntry(
                session = "s", seq = 1, ts = "t", level = "warn", tag = "tag",
                msg = "path C:\\x and a \"quote\"",
            ),
        )
        assertThat(line).contains("C:\\\\x")
        assertThat(line).contains("\\\"quote\\\"")
        assertThat(line).doesNotContain("\n")
    }

    @Test fun `non-resend crash defaults resend to false`() {
        val line = encodeLine(
            LogEntry(
                session = "s", seq = 0, ts = "t", level = "error", tag = "App.crash",
                msg = "x", crash = true, stack = "trace",
            ),
        )
        assertThat(line).contains("\"resend\":false")
    }

    // ── Queue bounding + drop accounting ────────────────────────────────────

    private val noopPoster = object : LogPoster {
        override fun postBatch(endpoint: String, ndjsonBody: String) = true
        override fun probe(endpoint: String) = LogPoster.ProbeResult(true, "200")
    }

    @Test fun `disabled shipper drops everything`() {
        val s = LogShipper(
            poster = noopPoster,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )
        // never configured → disabled
        repeat(10) { s.submit(LogEntry.LEVEL_INFO, "t", "m$it") }
        assertThat(s.queueDepth()).isEqualTo(0)
    }

    @Test fun `blank endpoint disables shipping even when enabled true`() {
        val s = LogShipper(
            poster = noopPoster,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )
        s.configure(enabled = true, endpoint = "   ")
        s.submit(LogEntry.LEVEL_INFO, "t", "m")
        assertThat(s.queueDepth()).isEqualTo(0)
    }

    @Test fun `queue bounds at capacity and tallies drops of the oldest`() {
        // capacity 5, batchSize 50, flush interval 60s: the drain loop's first
        // action is to wait for a full batch (50) OR the flush deadline (60s),
        // so it never drains within this synchronous test. The 8 submits below
        // therefore see only the bounded enqueue path.
        val s = LogShipper(
            poster = object : LogPoster {
                override fun postBatch(endpoint: String, ndjsonBody: String) = false
                override fun probe(endpoint: String) = LogPoster.ProbeResult(false, "")
            },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job()),
            session = "t",
            capacity = 5,
            batchSize = 50,
            flushIntervalMs = 60_000L,
            nowMillis = { 0L },
        )
        s.configure(enabled = true, endpoint = "http://x")
        repeat(8) { s.submit(LogEntry.LEVEL_INFO, "t", "m$it") }
        // Capacity caps the depth; the 3 oldest were dropped.
        assertThat(s.queueDepth()).isEqualTo(5)
        assertThat(s.pendingDropCount()).isEqualTo(3)
    }

    // ── Backoff progression ─────────────────────────────────────────────────

    @Test fun `backoff doubles to the cap and resets on success`() {
        val b = Backoff(baseMs = 5_000L, capMs = 60_000L)
        assertThat(b.currentMs).isEqualTo(5_000L)
        assertThat(b.onFailure()).isEqualTo(5_000L)   // waited 5s, next is 10s
        assertThat(b.currentMs).isEqualTo(10_000L)
        assertThat(b.onFailure()).isEqualTo(10_000L)  // next 20s
        assertThat(b.onFailure()).isEqualTo(20_000L)  // next 40s
        assertThat(b.onFailure()).isEqualTo(40_000L)  // next capped at 60s
        assertThat(b.currentMs).isEqualTo(60_000L)
        assertThat(b.onFailure()).isEqualTo(60_000L)  // stays at cap
        assertThat(b.currentMs).isEqualTo(60_000L)
        b.onSuccess()
        assertThat(b.currentMs).isEqualTo(5_000L)     // reset to base
    }

    // ── End-to-end drain (real loop, fake transport) ────────────────────────

    @Test fun `drain flushes a batch and emits a gap marker after drops`() {
        val received = java.util.concurrent.CopyOnWriteArrayList<String>()
        val gate = java.util.concurrent.CountDownLatch(1)
        val poster = object : LogPoster {
            override fun postBatch(endpoint: String, ndjsonBody: String): Boolean {
                received.add(ndjsonBody)
                gate.countDown()
                return true
            }
            override fun probe(endpoint: String) = LogPoster.ProbeResult(true, "200")
        }
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
        )
        val s = LogShipper(
            poster = poster,
            scope = scope,
            session = "t",
            capacity = 3,
            batchSize = 50,
            flushIntervalMs = 100L,
        )
        s.configure(enabled = true, endpoint = "http://x/log")
        // 5 submits into a capacity-3 queue → 2 oldest dropped, drop count 2.
        repeat(5) { s.submit(LogEntry.LEVEL_INFO, "t", "m$it") }
        // Wait for the drain loop to flush (flush interval is 100ms).
        assertThat(gate.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        val body = received.first()
        // The batch leads with the synthetic gap-marker warn entry.
        assertThat(body).contains("\"level\":\"warn\"")
        assertThat(body).contains("dropped 2 entries")
    }

    @Test fun `normalizeLogEndpoint expands a bare host with scheme port and path`() {
        assertThat(normalizeLogEndpoint("192.168.1.10")).isEqualTo("http://192.168.1.10:19192/log")
        assertThat(normalizeLogEndpoint(" myhost.lan ")).isEqualTo("http://myhost.lan:19192/log")
    }

    @Test fun `normalizeLogEndpoint keeps explicit pieces`() {
        assertThat(normalizeLogEndpoint("http://192.168.1.10:19192/log"))
            .isEqualTo("http://192.168.1.10:19192/log")
        assertThat(normalizeLogEndpoint("https://logs.example.com/r1ha"))
            .isEqualTo("https://logs.example.com/r1ha")
        assertThat(normalizeLogEndpoint("http://host:8080")).isEqualTo("http://host:8080/log")
    }

    @Test fun `normalizeLogEndpoint rejects blank and unparseable input`() {
        assertThat(normalizeLogEndpoint("")).isNull()
        assertThat(normalizeLogEndpoint("   ")).isNull()
        assertThat(normalizeLogEndpoint("http://")).isNull()
    }
}
