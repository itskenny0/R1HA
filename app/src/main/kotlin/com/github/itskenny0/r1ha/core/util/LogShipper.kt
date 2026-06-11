package com.github.itskenny0.r1ha.core.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One log line on the wire. Serialised to a single JSON object per NDJSON line
 * by [encodeLine]. The field names + value vocabulary are the wire contract a
 * receiver implements, so do not rename them:
 *
 *   session  stable hex id per app process (one per [LogShipper] instance)
 *   seq      monotonic int per session, assigned at enqueue time
 *   ts       ISO-8601 UTC timestamp
 *   level    one of verbose / debug / info / warn / error
 *   tag      R1Log "where" string
 *   msg      the log message
 *
 * Crash entries carry three extra fields:
 *   type     literal "crash"
 *   stack    the full stack trace string
 *   resend   true ONLY for a crash re-shipped from disk on the next startup
 *
 * Kept as a plain data class (not @Serializable) because the NDJSON encoder is
 * hand-rolled: it has to guarantee exactly one line per entry with no pretty
 * printing, and the crash-only fields are conditionally present. The hand
 * encoder is trivial to unit-test for shape.
 */
data class LogEntry(
    val session: String,
    val seq: Int,
    val ts: String,
    val level: String,
    val tag: String,
    val msg: String,
    val crash: Boolean = false,
    val stack: String? = null,
    val resend: Boolean = false,
) {
    companion object {
        val LEVEL_VERBOSE = "verbose"
        val LEVEL_DEBUG = "debug"
        val LEVEL_INFO = "info"
        val LEVEL_WARN = "warn"
        val LEVEL_ERROR = "error"

        /** ISO-8601 UTC timestamp for [millis]. Uses [DateTimeFormatter.ISO_INSTANT]
         *  so the output is the canonical `2026-06-11T09:00:00Z` shape a receiver
         *  expects. Generating (toString) is desugar-safe; only PARSING HA's
         *  +00:00 offset is the path that needs parseHaInstant, and we never parse
         *  these back. */
        fun isoUtc(millis: Long): String =
            DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(millis))
    }
}

/**
 * Serialise a [LogEntry] to a single NDJSON line (no trailing newline; the
 * batch writer joins with '\n'). Hand-rolled JSON with strict escaping so the
 * line is always valid and never spans more than one physical line: control
 * characters and the newline in a stack trace are escaped, never emitted raw.
 */
fun encodeLine(e: LogEntry): String {
    val sb = StringBuilder(128)
    sb.append('{')
    appendField(sb, "session", e.session); sb.append(',')
    sb.append("\"seq\":").append(e.seq).append(',')
    appendField(sb, "ts", e.ts); sb.append(',')
    appendField(sb, "level", e.level); sb.append(',')
    appendField(sb, "tag", e.tag); sb.append(',')
    appendField(sb, "msg", e.msg)
    if (e.crash) {
        sb.append(',')
        appendField(sb, "type", "crash"); sb.append(',')
        appendField(sb, "stack", e.stack.orEmpty()); sb.append(',')
        sb.append("\"resend\":").append(e.resend)
    }
    sb.append('}')
    return sb.toString()
}

private fun appendField(sb: StringBuilder, key: String, value: String) {
    sb.append('"').append(key).append("\":")
    appendJsonString(sb, value)
}

/** Minimal but correct JSON string escaping. */
private fun appendJsonString(sb: StringBuilder, s: String) {
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            else ->
                if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else sb.append(c)
        }
    }
    sb.append('"')
}

/**
 * Abstraction over the actual HTTP transport so tests can inject a fake and the
 * shipper stays free of OkHttp. [postBatch] POSTs the already-joined NDJSON body
 * to the configured endpoint; [probe] does the GET the settings TEST button
 * uses. Both return success/failure rather than throwing so the shipper's drain
 * loop can drive backoff without try/catch noise.
 */
interface LogPoster {
    /** POST [ndjsonBody] to [endpoint]. Return true on a 2xx response. */
    fun postBatch(endpoint: String, ndjsonBody: String): Boolean

    /** GET [endpoint]. Return a human-readable result for the TEST button. */
    fun probe(endpoint: String): ProbeResult

    data class ProbeResult(val ok: Boolean, val detail: String)
}

/**
 * Pure backoff schedule for the drain loop: starts at [BASE_MS] and doubles on
 * each consecutive failure up to [CAP_MS]; resets to [BASE_MS] on any success.
 * Extracted as its own tiny stateful object so the progression is unit-testable
 * without spinning up the whole shipper.
 */
class Backoff(
    private val baseMs: Long = BASE_MS,
    private val capMs: Long = CAP_MS,
) {
    var currentMs: Long = baseMs
        private set

    /** Advance after a failed flush; returns the delay to wait before retry. */
    fun onFailure(): Long {
        val now = currentMs
        currentMs = (currentMs * 2).coerceAtMost(capMs)
        return now
    }

    /** Reset after any successful flush. */
    fun onSuccess() {
        currentMs = baseMs
    }

    companion object {
        const val BASE_MS = 5_000L
        const val CAP_MS = 60_000L
    }
}

/**
 * In-memory, process-scoped log shipper. Hooks [R1Log] centrally (via
 * [R1Log.shipper]) so every log call enqueues an entry while shipping is
 * enabled, and a single background coroutine drains the bounded queue to the
 * configured HTTP endpoint in batches.
 *
 * Design:
 *  - Bounded queue ([CAPACITY] = 500). Beyond it, the OLDEST entries are
 *    dropped; a running drop count surfaces as a single synthetic warn entry
 *    ("log shipping dropped N entries") the next time the queue has room, so a
 *    long offline stretch leaves a visible gap marker rather than silence.
 *  - The drain coroutine flushes when either [BATCH_SIZE] (50) entries are
 *    queued or [FLUSH_INTERVAL_MS] (~3 s) elapses, whichever comes first.
 *  - Failures drive [Backoff] (5 s → 60 s cap) WITHOUT blocking the app: the
 *    queue keeps accepting (and bounding) entries while offline.
 *  - Re-entrancy guard: the shipper never logs its own HTTP activity through
 *    R1Log while the poster runs, so a failure can't recursively enqueue.
 *
 * The session id is a random hex string minted at construction (one per app
 * process) and is stable for the life of the instance.
 */
class LogShipper(
    private val poster: LogPoster,
    private val scope: CoroutineScope,
    /** Stable per-process session id. Random hex by default; injectable for tests. */
    val session: String = randomSession(),
    private val capacity: Int = CAPACITY,
    private val batchSize: Int = BATCH_SIZE,
    private val flushIntervalMs: Long = FLUSH_INTERVAL_MS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val queue = ArrayDeque<LogEntry>(capacity)
    private val seq = AtomicInteger(0)
    private val droppedSinceMarker = AtomicInteger(0)

    /** Endpoint + enabled state, set by the settings collector. Volatile so the
     *  drain loop and the R1Log hook (different threads) see fresh values without
     *  a lock on the hot enqueue path. */
    @Volatile private var enabled: Boolean = false
    @Volatile private var endpoint: String = ""

    /** Live snapshot of the effective shipping state, read by the crash handler
     *  (which can't suspend to collect the settings flow). [shippingEnabled]
     *  already folds in the blank-endpoint guard. */
    val shippingEnabled: Boolean get() = enabled
    val shippingEndpoint: String get() = endpoint

    /** True while the poster is executing, so the enqueue path can refuse to
     *  re-enter (the poster must never cause another shippable log). */
    private val posting = AtomicBoolean(false)

    private var drainJob: Job? = null

    /** Apply the latest user settings. Starting the drain loop is idempotent;
     *  flipping enabled off leaves the loop running but inert (it has nothing to
     *  send), which keeps the code path simple and lets a re-enable resume
     *  without re-launching. */
    fun configure(enabled: Boolean, endpoint: String) {
        this.endpoint = endpoint.trim()
        this.enabled = enabled && this.endpoint.isNotBlank()
        if (this.enabled && drainJob == null) start()
    }

    private fun isActive(): Boolean = enabled && endpoint.isNotBlank()

    /** Enqueue a log line. No-op when disabled. Drops the oldest entries past
     *  [capacity] and tallies the drop so a gap marker is emitted later. Never
     *  enqueues while the poster is mid-flight (re-entrancy guard) — that path
     *  is the shipper's own HTTP code and must not feed itself. */
    fun submit(level: String, tag: String, msg: String) {
        if (!isActive()) return
        if (posting.get()) return
        enqueue(
            LogEntry(
                session = session,
                seq = seq.getAndIncrement(),
                ts = LogEntry.isoUtc(nowMillis()),
                level = level,
                tag = tag,
                msg = msg,
            ),
        )
    }

    /** Enqueue a crash entry (called from the next-startup resend path; the
     *  best-effort in-process ship at crash time goes straight to the poster on
     *  its own thread, bypassing the queue). */
    fun submitCrash(tag: String, msg: String, stack: String, resend: Boolean) {
        if (!isActive()) return
        enqueue(
            LogEntry(
                session = session,
                seq = seq.getAndIncrement(),
                ts = LogEntry.isoUtc(nowMillis()),
                level = LogEntry.LEVEL_ERROR,
                tag = tag,
                msg = msg,
                crash = true,
                stack = stack,
                resend = resend,
            ),
        )
    }

    private fun enqueue(entry: LogEntry) {
        synchronized(lock) {
            while (queue.size >= capacity) {
                queue.removeFirst()
                droppedSinceMarker.incrementAndGet()
            }
            queue.addLast(entry)
        }
    }

    /** Snapshot the current queue depth — for tests + diagnostics. */
    fun queueDepth(): Int = synchronized(lock) { queue.size }

    /** Drop count not yet folded into a gap marker — for tests. */
    fun pendingDropCount(): Int = droppedSinceMarker.get()

    private fun start() {
        drainJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                ensureActive()
                runDrainTick(this)
            }
        }
    }

    /** One iteration of the drain loop. Extracted so a test can step it
     *  deterministically. Returns true if a batch was flushed (success). */
    internal suspend fun runDrainTick(coroutineScope: CoroutineScope): Boolean {
        if (!isActive()) {
            delay(flushIntervalMs)
            return false
        }
        // Wait until we have a full batch OR the flush interval elapsed.
        val deadline = nowMillis() + flushIntervalMs
        while (coroutineScope.isActive && queueDepth() < batchSize && nowMillis() < deadline) {
            delay(POLL_MS)
        }
        val batch = drainBatch()
        if (batch.isEmpty()) return false

        val body = batch.joinToString("\n") { encodeLine(it) }
        val ok = runPost(body)
        if (ok) {
            backoff.onSuccess()
            return true
        }
        // Re-queue the failed batch at the FRONT (preserving order) so nothing
        // is lost, then back off. Re-queue respects the bound: if the queue
        // filled while we were posting, the oldest of the combined set is the
        // one dropped, which is the right drop-oldest policy.
        requeueFront(batch)
        val wait = backoff.onFailure()
        delay(wait)
        return false
    }

    private val backoff = Backoff()

    /** Take up to [batchSize] oldest entries, plus a leading gap-marker warn
     *  entry if drops have accumulated since the last marker. */
    private fun drainBatch(): List<LogEntry> = synchronized(lock) {
        if (queue.isEmpty()) return emptyList()
        val out = ArrayList<LogEntry>(batchSize + 1)
        val dropped = droppedSinceMarker.getAndSet(0)
        if (dropped > 0) {
            out.add(
                LogEntry(
                    session = session,
                    seq = seq.getAndIncrement(),
                    ts = LogEntry.isoUtc(nowMillis()),
                    level = LogEntry.LEVEL_WARN,
                    tag = "LogShipper",
                    msg = "log shipping dropped $dropped entries (queue overflow)",
                ),
            )
        }
        var room = batchSize - out.size
        while (room > 0 && queue.isNotEmpty()) {
            out.add(queue.removeFirst())
            room--
        }
        out
    }

    private fun requeueFront(batch: List<LogEntry>) = synchronized(lock) {
        // Push back in reverse so original order is preserved at the head.
        for (i in batch.indices.reversed()) {
            if (queue.size >= capacity) {
                queue.removeLast()
                droppedSinceMarker.incrementAndGet()
            }
            queue.addFirst(batch[i])
        }
    }

    private fun runPost(body: String): Boolean {
        posting.set(true)
        return try {
            poster.postBatch(endpoint, body)
        } catch (_: Throwable) {
            false
        } finally {
            posting.set(false)
        }
    }

    companion object {
        const val CAPACITY = 500
        const val BATCH_SIZE = 50
        const val FLUSH_INTERVAL_MS = 3_000L
        private const val POLL_MS = 100L

        /** 16 hex chars (64 bits) of randomness — collision-free enough for a
         *  per-process session id and short enough to read in a log viewer. */
        fun randomSession(): String {
            val bytes = ByteArray(8)
            java.security.SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
