package com.github.itskenny0.r1ha.core.util

/**
 * Glue between the uncaught-exception handler and [CrashStore] / [LogPoster].
 *
 * Two responsibilities, deliberately split from [LogShipper] so the crash path
 * has no dependency on the running drain loop (which may itself be wedged):
 *
 *  1. [captureAndShip] runs INSIDE the uncaught-exception handler, before the
 *     process dies. It (a) synchronously writes a crash file, then (b) makes a
 *     best-effort immediate POST on a SEPARATE thread joined with a hard 2 s
 *     timeout so a dead network can never hang the dying process. The caller
 *     chains to the previous handler afterwards (the system crash dialog /
 *     restart behaviour is preserved).
 *
 *  2. [resendPending] runs at the NEXT startup. It enumerates stored crash
 *     files, ships each (with resend = true) directly via the poster, deletes
 *     on confirmed delivery, and prunes the rest to the newest few.
 */
object CrashShipping {

    /** Hard cap on how long the dying process waits for the best-effort POST. */
    const val SHIP_JOIN_TIMEOUT_MS = 2_000L

    /**
     * Crash-time capture. Always writes the file first (the reliable path); the
     * immediate ship is opportunistic. [enabled] + [endpoint] gate the ship but
     * NOT the file write — a crash is always persisted so the next startup can
     * resend even when shipping was off at crash time. Returns the written file
     * (or null on write failure).
     *
     * Everything here is wrapped so a failure inside crash handling can never
     * mask the original exception or block the chain to the previous handler.
     */
    fun captureAndShip(
        store: CrashStore,
        poster: LogPoster,
        session: String,
        threadName: String,
        throwable: Throwable,
        versionName: String,
        nowMillis: Long,
        enabled: Boolean,
        endpoint: String,
    ) {
        val record = CrashRecord(
            ts = LogEntry.isoUtc(nowMillis),
            session = session,
            thread = threadName,
            message = "${throwable::class.java.name}: ${throwable.message ?: ""}",
            stack = throwable.stackTraceToString(),
            versionName = versionName,
        )
        runCatching { store.writeCrash(record, nowMillis) }

        if (!enabled || endpoint.isBlank()) return

        // Best-effort immediate ship on a daemon thread with a hard join timeout.
        // A wedged network blocks only this thread, and only for up to the
        // timeout; the crashing thread proceeds to the previous handler.
        val line = encodeLine(crashEntry(record, seq = 0, resend = false))
        val poke = Thread({
            runCatching { poster.postBatch(endpoint, line) }
        }, "r1ha-crash-ship").apply { isDaemon = true }
        runCatching {
            poke.start()
            poke.join(SHIP_JOIN_TIMEOUT_MS)
        }
    }

    /**
     * Next-startup resend. No-op (and no file deletion) when shipping is
     * disabled — undelivered crashes stay on disk for a later enabled run, but
     * the store is still pruned so it can't grow without bound. When enabled,
     * each file is shipped with resend = true and deleted on confirmed delivery;
     * survivors are pruned to [CrashStore.PRUNE_KEEP].
     */
    fun resendPending(
        store: CrashStore,
        poster: LogPoster,
        session: String,
        enabled: Boolean,
        endpoint: String,
    ) {
        if (!enabled || endpoint.isBlank()) {
            store.prune()
            return
        }
        var seq = 0
        for (file in store.listFiles()) {
            val record = store.read(file)
            if (record == null) {
                // Unparseable file: drop it so it doesn't wedge the loop forever.
                store.delete(file)
                continue
            }
            val line = encodeLine(crashEntry(record, seq = seq++, resend = true))
            val ok = runCatching { poster.postBatch(endpoint, line) }.getOrDefault(false)
            if (ok) store.delete(file)
        }
        store.prune()
    }

    /** Build the wire entry for a stored crash. The original crash session is
     *  preserved in the entry so a receiver can correlate it with that run's
     *  logs; [session] (the current process) is NOT substituted. */
    private fun crashEntry(r: CrashRecord, seq: Int, resend: Boolean): LogEntry = LogEntry(
        session = r.session.ifBlank { "unknown" },
        seq = seq,
        ts = r.ts,
        level = LogEntry.LEVEL_ERROR,
        tag = "App.crash",
        msg = r.message,
        crash = true,
        stack = r.stack,
        resend = resend,
    )
}
