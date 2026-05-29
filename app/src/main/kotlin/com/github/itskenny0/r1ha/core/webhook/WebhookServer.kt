package com.github.itskenny0.r1ha.core.webhook

import com.github.itskenny0.r1ha.core.util.R1Log
import java.io.InputStream
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Tiny single-threaded HTTP/1.1 listener for the webhook-receiver feature. Plain
 * ServerSocket so we don't add a runtime dependency (NanoHTTPD, OkHttp's MockWebServer,
 * etc.) just to listen for the occasional automation ping from HA.
 *
 * Accepts `POST /webhook/<id>` where <id> matches [webhookId]. The request line and
 * headers are read as line-based ASCII; the body is then read as exactly
 * Content-Length BYTES off the raw stream and decoded as UTF-8 (so a multibyte
 * payload, where the UTF-8 char count is smaller than the byte count, isn't
 * truncated or left blocking on the socket read-timeout). Body length is capped at
 * [MAX_BODY_BYTES]. Headers are walked just enough to find `Content-Length`; we don't
 * honour Transfer-Encoding: chunked because HA's outbound webhook client never uses it.
 *
 * Errors and dropped sockets are logged at debug and don't kill the listen loop.
 * Server stops cleanly on [stop]: closes the socket, joins the accept thread.
 */
class WebhookServer(
    private val port: Int,
    private val webhookId: String,
    private val onWebhook: (body: String, remoteAddr: String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        acceptThread = thread(name = "r1ha-webhook-${port}", isDaemon = true) {
            runCatching {
                ServerSocket(port).also { socket = it }.use { server ->
                    R1Log.i("Webhook", "listening on :$port path=/webhook/$webhookId")
                    while (running.get() && !server.isClosed) {
                        val client = try {
                            server.accept()
                        } catch (e: SocketException) {
                            // Expected when stop() closes the socket; bail quietly.
                            if (!running.get()) return@use
                            R1Log.d("Webhook", "accept threw mid-listen: ${e.message}")
                            continue
                        }
                        handleClient(client)
                    }
                }
            }.onFailure { t ->
                R1Log.w("Webhook", "server thread crashed: ${t.message}", t)
            }
            R1Log.i("Webhook", "listener stopped")
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { socket?.close() }
        socket = null
        runCatching { acceptThread?.join(2_000) }
        acceptThread = null
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            val remote = sock.inetAddress?.hostAddress ?: "?"
            runCatching {
                // Bound the read so a peer that connects and then sends nothing
                // can't wedge this single-threaded accept loop forever (the
                // whole listener would stop accepting new webhooks). HA's own
                // webhook client always completes the request promptly, so a
                // few seconds is generous.
                sock.soTimeout = CLIENT_READ_TIMEOUT_MS
                // Read the request line + headers as raw ASCII bytes (CRLF-delimited)
                // so the InputStream cursor lands exactly at the start of the body.
                // A BufferedReader would read-ahead past the header boundary and
                // swallow body bytes, which is why we don't wrap the stream.
                val input = sock.getInputStream()
                val statusLine = readHeaderLine(input) ?: return@use
                val parts = statusLine.split(' ')
                if (parts.size < 2) {
                    sendStatus(sock, 400, "Bad Request")
                    return@use
                }
                val method = parts[0]
                val path = parts[1]
                // Drain headers, pull Content-Length so we know how much body to read.
                var contentLength = 0
                while (true) {
                    val line = readHeaderLine(input) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon <= 0) continue
                    val name = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    if (name == "content-length") {
                        contentLength = value.toIntOrNull()?.coerceIn(0, MAX_BODY_BYTES) ?: 0
                    }
                }
                if (method != "POST") {
                    sendStatus(sock, 405, "Method Not Allowed")
                    return@use
                }
                val expectedPath = "/webhook/$webhookId"
                if (path != expectedPath) {
                    sendStatus(sock, 404, "Not Found")
                    return@use
                }
                val body = readBody(input, contentLength)
                R1Log.i("Webhook", "POST $path from $remote (${body.length} chars)")
                onWebhook(body, remote)
                sendStatus(sock, 200, "OK")
            }.onFailure { t ->
                R1Log.d("Webhook", "client $remote: ${t.message}")
            }
        }
    }

    companion object {
        /** Per-connection read timeout. Long enough for any legitimate HA
         *  webhook POST, short enough that a stalled peer can't hold the
         *  accept loop hostage. */
        private const val CLIENT_READ_TIMEOUT_MS = 10_000

        /** Hard cap on the body we'll buffer. HA webhook payloads are tiny
         *  (typically < 1 KB JSON); 1 MB is a generous ceiling that still
         *  stops a hostile peer from making us allocate unbounded memory. */
        internal const val MAX_BODY_BYTES = 1_000_000

        /**
         * Read one CRLF- (or bare-LF-) terminated line of ASCII from [input],
         * returning it without the line terminator. Returns null at clean
         * end-of-stream before any byte is read. A lone CR is tolerated and
         * dropped. Used for the request line and headers only, which are
         * defined by HTTP to be single-byte (ISO-8859-1 / ASCII), so reading
         * byte-by-byte here can never split a multibyte sequence.
         */
        internal fun readHeaderLine(input: InputStream): String? {
            val sb = StringBuilder()
            var sawAny = false
            while (true) {
                val b = input.read()
                if (b == -1) return if (sawAny) sb.toString() else null
                sawAny = true
                when (b) {
                    '\n'.code -> return sb.toString()
                    '\r'.code -> { /* drop; the paired \n ends the line */ }
                    else -> sb.append(b.toChar())
                }
            }
        }

        /**
         * Read exactly [contentLength] BYTES from [input] and decode them as
         * UTF-8. Reading bytes (not chars) is the whole point: Content-Length
         * counts bytes, so a multibyte-UTF-8 body has fewer chars than bytes
         * and a char-counting read loop would never reach the target and would
         * block until the socket read-timeout. Stops early (returning whatever
         * decoded) if the stream ends before [contentLength] bytes arrive.
         */
        internal fun readBody(input: InputStream, contentLength: Int): String {
            if (contentLength <= 0) return ""
            val buf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(buf, read, contentLength - read)
                if (n <= 0) break
                read += n
            }
            return String(buf, 0, read, Charsets.UTF_8)
        }
    }

    private fun sendStatus(sock: Socket, code: Int, reason: String) {
        runCatching {
            val out = PrintWriter(sock.getOutputStream())
            out.print("HTTP/1.1 $code $reason\r\n")
            out.print("Content-Length: 0\r\n")
            out.print("Connection: close\r\n\r\n")
            out.flush()
        }
    }
}
