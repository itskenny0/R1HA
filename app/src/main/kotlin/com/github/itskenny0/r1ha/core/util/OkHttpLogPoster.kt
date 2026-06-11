package com.github.itskenny0.r1ha.core.util

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Real [LogPoster] backed by the app's shared [OkHttpClient] (handed in from the
 * dependency graph so log shipping inherits the same timeouts, TLS pinning, and
 * mTLS setup as the rest of the app and adds no new HTTP stack).
 *
 * The receiver's contract:
 *  - POST the NDJSON batch as `application/x-ndjson`; any 2xx counts as accepted.
 *  - GET the same URL for the TEST button; a 200 means the endpoint is alive.
 *
 * Every call runs synchronously on the caller's thread: the shipper's drain
 * loop is already on Dispatchers.IO and the crash-time best-effort ship runs on
 * its own short-lived thread with a join timeout, so blocking here is correct.
 *
 * Re-entrancy: this class deliberately performs NO R1Log calls. A failed POST
 * must not produce another shippable log line, which would feed the shipper its
 * own traffic and risk a loop.
 */
class OkHttpLogPoster(
    private val client: OkHttpClient,
) : LogPoster {

    override fun postBatch(endpoint: String, ndjsonBody: String): Boolean {
        return try {
            val body = ndjsonBody.toRequestBody(NDJSON)
            val req = Request.Builder()
                .url(endpoint)
                .post(body)
                .build()
            client.newCall(req).execute().use { resp -> resp.isSuccessful }
        } catch (_: Throwable) {
            // Network down, bad URL, timeout: all map to "not delivered" so the
            // shipper backs off. Never rethrow (the drain loop is best-effort)
            // and never log (re-entrancy).
            false
        }
    }

    override fun probe(endpoint: String): LogPoster.ProbeResult {
        return try {
            val req = Request.Builder().url(endpoint).get().build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty().trim().take(120)
                    LogPoster.ProbeResult(
                        ok = true,
                        detail = if (body.isNotBlank()) "HTTP ${resp.code}: $body" else "HTTP ${resp.code}",
                    )
                } else {
                    LogPoster.ProbeResult(ok = false, detail = "HTTP ${resp.code}")
                }
            }
        } catch (t: Throwable) {
            LogPoster.ProbeResult(ok = false, detail = t.message ?: t::class.java.simpleName)
        }
    }

    companion object {
        private val NDJSON = "application/x-ndjson".toMediaTypeOrNull()
    }
}
