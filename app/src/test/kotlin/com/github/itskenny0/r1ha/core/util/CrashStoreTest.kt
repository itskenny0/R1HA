package com.github.itskenny0.r1ha.core.util

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Crash-file write / parse / delete round-trip + retention, plus the
 * next-startup resend flow. Pure file I/O against a temp dir (no Android).
 */
class CrashStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store() = CrashStore(tmp.newFolder("crash-${System.nanoTime()}"))

    private fun sample(session: String = "abc", message: String = "boom") = CrashRecord(
        ts = "2026-06-11T09:00:00Z",
        session = session,
        thread = "main",
        message = message,
        stack = "java.lang.IllegalStateException: boom\n\tat Foo.bar(Foo.kt:1)\n\tat Baz.qux(Baz.kt:2)",
        versionName = "r1ha-20260611-0900",
    )

    @Test fun `write then read round-trips every field including multiline stack`() {
        val s = store()
        val rec = sample()
        val file = s.writeCrash(rec, atMillis = 1_000L)
        assertThat(file).isNotNull()

        val read = s.read(file!!)
        assertThat(read).isNotNull()
        assertThat(read!!.ts).isEqualTo(rec.ts)
        assertThat(read.session).isEqualTo(rec.session)
        assertThat(read.thread).isEqualTo(rec.thread)
        assertThat(read.message).isEqualTo(rec.message)
        assertThat(read.versionName).isEqualTo(rec.versionName)
        assertThat(read.stack).isEqualTo(rec.stack)
    }

    @Test fun `message newlines are flattened so the header stays single-line`() {
        val s = store()
        val file = s.writeCrash(sample(message = "line one\nline two"), atMillis = 1L)!!
        val read = s.read(file)!!
        assertThat(read.message).isEqualTo("line one line two")
    }

    @Test fun `delete removes the file`() {
        val s = store()
        val file = s.writeCrash(sample(), atMillis = 1L)!!
        assertThat(file.exists()).isTrue()
        assertThat(s.delete(file)).isTrue()
        assertThat(file.exists()).isFalse()
    }

    @Test fun `listFiles returns newest first`() {
        val s = store()
        s.writeCrash(sample(), atMillis = 100L)
        s.writeCrash(sample(), atMillis = 300L)
        s.writeCrash(sample(), atMillis = 200L)
        val names = s.listFiles().map { it.name }
        assertThat(names).containsExactly("crash-300.txt", "crash-200.txt", "crash-100.txt").inOrder()
    }

    @Test fun `prune keeps only the newest few`() {
        val s = store()
        for (i in 1..8) s.writeCrash(sample(), atMillis = i.toLong())
        s.prune(keep = 5)
        val remaining = s.listFiles().map { it.name }
        assertThat(remaining).hasSize(5)
        // Newest five (8..4) survive; 3,2,1 pruned.
        assertThat(remaining).containsExactly(
            "crash-8.txt", "crash-7.txt", "crash-6.txt", "crash-5.txt", "crash-4.txt",
        ).inOrder()
    }

    @Test fun `read returns null for a malformed file`() {
        val s = store()
        val bad = tmp.newFile("crash-9.txt")
        bad.writeText("not a crash file, no marker")
        assertThat(s.read(bad)).isNull()
    }

    // ── Resend flow ─────────────────────────────────────────────────────────

    @Test fun `resendPending ships with resend true and deletes on confirmed delivery`() {
        val s = store()
        s.writeCrash(sample(session = "old1"), atMillis = 1L)
        s.writeCrash(sample(session = "old2"), atMillis = 2L)
        val sent = mutableListOf<String>()
        val poster = object : LogPoster {
            override fun postBatch(endpoint: String, ndjsonBody: String): Boolean {
                sent.add(ndjsonBody); return true
            }
            override fun probe(endpoint: String) = LogPoster.ProbeResult(true, "200")
        }
        CrashShipping.resendPending(s, poster, session = "now", enabled = true, endpoint = "http://x")
        assertThat(sent).hasSize(2)
        assertThat(sent.all { it.contains("\"resend\":true") && it.contains("\"type\":\"crash\"") }).isTrue()
        // The crash's ORIGINAL session is preserved on the wire, not the current one.
        assertThat(sent.any { it.contains("\"session\":\"old1\"") }).isTrue()
        // All delivered → files gone.
        assertThat(s.listFiles()).isEmpty()
    }

    @Test fun `resendPending keeps files when delivery fails`() {
        val s = store()
        s.writeCrash(sample(), atMillis = 1L)
        val poster = object : LogPoster {
            override fun postBatch(endpoint: String, ndjsonBody: String) = false
            override fun probe(endpoint: String) = LogPoster.ProbeResult(false, "")
        }
        CrashShipping.resendPending(s, poster, session = "now", enabled = true, endpoint = "http://x")
        assertThat(s.listFiles()).hasSize(1)
    }

    @Test fun `resendPending disabled prunes but never ships or deletes survivors`() {
        val s = store()
        for (i in 1..8) s.writeCrash(sample(), atMillis = i.toLong())
        var shipped = false
        val poster = object : LogPoster {
            override fun postBatch(endpoint: String, ndjsonBody: String): Boolean { shipped = true; return true }
            override fun probe(endpoint: String) = LogPoster.ProbeResult(true, "")
        }
        CrashShipping.resendPending(s, poster, session = "now", enabled = false, endpoint = "")
        assertThat(shipped).isFalse()
        // Pruned to the newest 5 even while disabled, so disk can't grow unbounded.
        assertThat(s.listFiles()).hasSize(5)
    }

    @Test fun `captureAndShip always writes a crash file even when shipping disabled`() {
        val s = store()
        var shipped = false
        val poster = object : LogPoster {
            override fun postBatch(endpoint: String, ndjsonBody: String): Boolean { shipped = true; return true }
            override fun probe(endpoint: String) = LogPoster.ProbeResult(true, "")
        }
        CrashShipping.captureAndShip(
            store = s,
            poster = poster,
            session = "sess",
            threadName = "main",
            throwable = IllegalStateException("kaboom"),
            versionName = "v1",
            nowMillis = 42L,
            enabled = false,
            endpoint = "",
        )
        // File persisted for a later resend; nothing shipped (disabled).
        assertThat(s.listFiles()).hasSize(1)
        assertThat(shipped).isFalse()
        val rec = s.read(s.listFiles().first())!!
        assertThat(rec.message).contains("kaboom")
        assertThat(rec.session).isEqualTo("sess")
    }
}
