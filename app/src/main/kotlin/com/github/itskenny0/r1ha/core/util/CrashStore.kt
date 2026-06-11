package com.github.itskenny0.r1ha.core.util

import java.io.File

/**
 * A captured crash, persisted to a file under `filesDir/crash/` so it survives
 * the process death that follows an uncaught exception and can be re-shipped on
 * the next startup with `resend: true`.
 *
 * The on-disk format is a tiny line-oriented codec (header lines + a blank line
 * + the raw stack trace) chosen over JSON so the write at crash time stays
 * dead simple and allocation-light — the process is already dying and a JSON
 * encoder failing would lose the trace. [CrashStore] round-trips it.
 */
data class CrashRecord(
    val ts: String,
    val session: String,
    val thread: String,
    val message: String,
    val stack: String,
    val versionName: String,
)

/**
 * Reads + writes crash files under a directory. Pure file I/O + parsing so it
 * unit-tests against a temp dir without Android. The crashing-process side
 * ([writeCrash]) is synchronous and tiny; the next-startup side enumerates,
 * parses, and (on confirmed delivery) deletes.
 *
 * Retention: when not delivered, the [PRUNE_KEEP] newest files are kept and
 * older ones pruned so a crash loop can't fill the disk.
 */
class CrashStore(private val dir: File) {

    /** Ensure the crash directory exists; cheap to call repeatedly. */
    private fun ensureDir(): Boolean = dir.exists() || dir.mkdirs()

    /**
     * Synchronously write [record] to a new file. Returns the file on success or
     * null on failure (caller is mid-crash and must not throw). The filename
     * embeds the wall-clock millis so directory order ≈ chronological order for
     * the newest-first prune.
     */
    fun writeCrash(record: CrashRecord, atMillis: Long): File? {
        if (!ensureDir()) return null
        val file = File(dir, "crash-$atMillis.txt")
        return try {
            file.writeText(encode(record), Charsets.UTF_8)
            file
        } catch (_: Throwable) {
            null
        }
    }

    /** All crash files, newest first (by embedded timestamp, falling back to
     *  lastModified for hand-placed files). */
    fun listFiles(): List<File> {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") } ?: return emptyList()
        return files.sortedByDescending { sortKey(it) }
    }

    /** Parse a single crash file, or null if it is malformed / unreadable. */
    fun read(file: File): CrashRecord? = try {
        decode(file.readText(Charsets.UTF_8))
    } catch (_: Throwable) {
        null
    }

    /** Delete a delivered crash file. Returns true if it is gone afterwards. */
    fun delete(file: File): Boolean = try {
        !file.exists() || file.delete()
    } catch (_: Throwable) {
        false
    }

    /** Keep only the [PRUNE_KEEP] newest files; delete the rest. Called after a
     *  resend pass so undelivered crashes don't accumulate unboundedly. */
    fun prune(keep: Int = PRUNE_KEEP) {
        val files = listFiles()
        if (files.size <= keep) return
        files.drop(keep).forEach { runCatching { it.delete() } }
    }

    private fun sortKey(f: File): Long =
        f.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull() ?: f.lastModified()

    companion object {
        const val PRUNE_KEEP = 5
        private const val MARK_STACK = "--- STACK ---"

        /** Default crash directory under an app's filesDir. */
        fun forFilesDir(filesDir: File): CrashStore = CrashStore(File(filesDir, "crash"))

        /**
         * Encode a [CrashRecord]. Header is `key: value` lines (values single-line:
         * the message has its newlines flattened so the header can't be confused
         * with the stack), then a [MARK_STACK] marker line, then the raw stack
         * (which may span many lines).
         */
        fun encode(r: CrashRecord): String = buildString {
            append("ts: ").append(r.ts).append('\n')
            append("session: ").append(r.session).append('\n')
            append("thread: ").append(r.thread).append('\n')
            append("version: ").append(r.versionName).append('\n')
            append("message: ").append(r.message.replace('\n', ' ').replace('\r', ' ')).append('\n')
            append(MARK_STACK).append('\n')
            append(r.stack)
        }

        /** Decode; returns null if the marker is missing or required headers absent. */
        fun decode(text: String): CrashRecord? {
            val markIdx = text.indexOf(MARK_STACK)
            if (markIdx < 0) return null
            val header = text.substring(0, markIdx)
            // Stack is everything after the marker line.
            val afterMark = text.substring(markIdx + MARK_STACK.length)
            val stack = afterMark.removePrefix("\n").removePrefix("\r\n")
            val fields = HashMap<String, String>()
            for (line in header.split('\n')) {
                val sep = line.indexOf(':')
                if (sep <= 0) continue
                val key = line.substring(0, sep).trim()
                val value = line.substring(sep + 1).trim()
                fields[key] = value
            }
            val ts = fields["ts"] ?: return null
            return CrashRecord(
                ts = ts,
                session = fields["session"].orEmpty(),
                thread = fields["thread"].orEmpty(),
                message = fields["message"].orEmpty(),
                stack = stack,
                versionName = fields["version"].orEmpty(),
            )
        }
    }
}
