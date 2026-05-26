package com.github.itskenny0.r1ha.core.iotcamera

import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Hand-rolled HTTP/1.1 server that exposes the device camera as MJPEG. Two
 * endpoints:
 *
 *   - `GET /stream` → `multipart/x-mixed-replace` with a JPEG part per frame.
 *     This is what HA's `generic` camera platform consumes when you set
 *     `still_image_url` and `stream_source` (or just one of them) to point
 *     at the device. Each client gets its own coroutine subscribed to
 *     [frames]; backpressure is handled by the SharedFlow's DROP_OLDEST so
 *     a slow client falls behind but never blocks the publisher.
 *
 *   - `GET /snapshot` → a single JPEG. Useful as `still_image_url` and as
 *     a sanity check that the stream is up without consuming a multipart
 *     parser. Returns 503 when no frame has been produced yet.
 *
 * Authentication: HTTP Basic on every request. Both [username] and
 * [password] travel base64-encoded; the LAN may not be hostile but Basic
 * still defeats casual curiosity and gives HA a normal `http://user:pw@host`
 * URL to talk to. The header check is constant-time-ish via the equality
 * comparison on the expected token; we don't go further (timing attacks on
 * an LAN HTTP server are not a meaningful threat model).
 *
 * No third-party dependency — same posture as [com.github.itskenny0.r1ha.core.webhook.WebhookServer],
 * which is the prior art this server is modelled on.
 */
class MjpegServer(
    private val port: Int,
    private val username: String,
    private val password: String,
    private val frames: SharedFlow<ByteArray>,
    private val latestFrame: () -> ByteArray?,
) {
    private val running = AtomicBoolean(false)
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val expectedAuthHeader: String by lazy {
        // "Basic " + base64(user:pw). Pre-compute so every request is a
        // single equals() compare rather than re-encoding on each hit.
        val raw = "$username:$password".toByteArray(Charsets.UTF_8)
        "Basic " + Base64.getEncoder().encodeToString(raw)
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        acceptThread = thread(name = "r1ha-mjpeg-$port", isDaemon = true) {
            runCatching {
                ServerSocket(port).also { socket = it }.use { server ->
                    R1Log.i("IotCamera.mjpeg", "listening on :$port")
                    while (running.get() && !server.isClosed) {
                        val client = try {
                            server.accept()
                        } catch (e: SocketException) {
                            if (!running.get()) return@use
                            R1Log.d("IotCamera.mjpeg", "accept threw mid-listen: ${e.message}")
                            continue
                        }
                        // One coroutine per accepted client. Spawning a
                        // thread per connection would scale poorly on a
                        // small device — typical use is "HA polling the
                        // stream URL" so concurrency is low, but a careless
                        // user with three tabs open can hit five connections
                        // in seconds. Coroutines amortise that flat.
                        clientScope.launch { serve(client) }
                    }
                }
            }.onFailure { t ->
                R1Log.w("IotCamera.mjpeg", "server thread crashed: ${t.message}", t)
            }
            R1Log.i("IotCamera.mjpeg", "listener stopped")
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { socket?.close() }
        socket = null
        runCatching { acceptThread?.join(2_000) }
        acceptThread = null
        clientScope.cancel()
    }

    private suspend fun serve(client: Socket) {
        val remote = client.inetAddress?.hostAddress ?: "?"
        try {
            client.soTimeout = 0 // long-lived stream; let the kernel notice peer death
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val statusLine = reader.readLine() ?: return
            val parts = statusLine.split(' ')
            if (parts.size < 2) {
                client.respond(400, "Bad Request", "text/plain", "bad request line".toByteArray())
                return
            }
            val method = parts[0]
            val path = parts[1].substringBefore('?') // ignore query string
            var authHeader: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Authorization:", ignoreCase = true)) {
                    authHeader = line.substringAfter(':').trim()
                }
            }
            if (method != "GET") {
                client.respond(405, "Method Not Allowed", "text/plain", "GET only".toByteArray())
                return
            }
            if (authHeader != expectedAuthHeader) {
                // 401 with WWW-Authenticate so browsers prompt; HA generic
                // camera uses the http://user:pw@host shorthand so it never
                // sees the realm string.
                client.respondWith401()
                return
            }
            when (path) {
                "/", "/index", "/index.html" -> client.serveIndex()
                "/snapshot" -> client.serveSnapshot()
                "/stream" -> client.serveStream()
                else -> client.respond(404, "Not Found", "text/plain", "no such path".toByteArray())
            }
        } catch (e: Exception) {
            R1Log.d("IotCamera.mjpeg", "client $remote: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    private fun Socket.serveIndex() {
        // Tiny landing page so anyone who opens the URL in a browser sees
        // it's a working stream + which paths are available. No HTML form,
        // just a plain summary — the device isn't a config surface.
        val body = """
            R1HA IoT Camera

            GET /snapshot  → latest JPEG
            GET /stream    → multipart/x-mixed-replace MJPEG

            Configure in Home Assistant as a 'generic' camera with
            still_image_url and stream_source pointing at the URLs above.
        """.trimIndent().toByteArray(Charsets.UTF_8)
        respond(200, "OK", "text/plain", body)
    }

    private fun Socket.serveSnapshot() {
        val frame = latestFrame()
        if (frame == null) {
            respond(503, "Service Unavailable", "text/plain", "no frame yet".toByteArray())
            return
        }
        respond(200, "OK", "image/jpeg", frame)
    }

    private suspend fun Socket.serveStream() {
        val out = getOutputStream()
        // Headers — keep-alive isn't needed because the response itself never
        // terminates; the connection only closes when peer disconnects or we
        // tear down. `Cache-Control: no-store` keeps HA + browsers from
        // caching the multipart byte stream.
        val header = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
                "Cache-Control: no-store, no-cache, must-revalidate\r\n" +
                "Pragma: no-cache\r\n" +
                "Connection: close\r\n\r\n"
        ).toByteArray(Charsets.UTF_8)
        out.write(header)
        out.flush()
        // Subscribe to the frame bus; SharedFlow handles backpressure via
        // its overflow policy so we never block the publisher. Per-client
        // try/catch around the write so a single peer dying doesn't take
        // out the collector.
        try {
            frames.collect { jpeg ->
                writePart(out, jpeg)
            }
        } catch (_: SocketException) {
            // peer disconnected — normal for browser tab close / HA reload
        } catch (e: Exception) {
            R1Log.d("IotCamera.mjpeg", "stream collect ended: ${e.message}")
        }
    }

    private fun writePart(out: OutputStream, jpeg: ByteArray) {
        val partHeader = (
            "--$BOUNDARY\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: ${jpeg.size}\r\n\r\n"
        ).toByteArray(Charsets.UTF_8)
        out.write(partHeader)
        out.write(jpeg)
        out.write("\r\n".toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun Socket.respond(
        code: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
    ) {
        runCatching {
            val out = getOutputStream()
            val header = (
                "HTTP/1.1 $code $reason\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
            out.write(header)
            out.write(body)
            out.flush()
        }
    }

    private fun Socket.respondWith401() {
        runCatching {
            val out = getOutputStream()
            val body = "auth required".toByteArray(Charsets.UTF_8)
            val header = (
                "HTTP/1.1 401 Unauthorized\r\n" +
                    "WWW-Authenticate: Basic realm=\"R1HA Camera\"\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
            out.write(header)
            out.write(body)
            out.flush()
        }
    }

    companion object {
        /** MIME-multipart boundary. Plain ASCII without `-` runs that some
         *  HTTP parsers split on; matches the convention used by IP Webcam
         *  / motion / mjpg-streamer so HA's existing test matrices pass. */
        const val BOUNDARY = "r1ha_mjpeg_boundary"
    }
}
