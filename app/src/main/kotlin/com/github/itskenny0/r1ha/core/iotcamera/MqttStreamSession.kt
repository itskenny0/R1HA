package com.github.itskenny0.r1ha.core.iotcamera

import com.github.itskenny0.r1ha.core.util.R1Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * Long-lived MQTT v3.1.1 publish-only session. Built specifically for the
 * camera streaming use case: open the socket once, send CONNECT, then
 * fire PUBLISH for every encoded frame without paying the TCP + TLS +
 * CONNACK roundtrip cost per packet.
 *
 * Why not extend [com.github.itskenny0.r1ha.core.mqtt.MqttPublisher]:
 * that surface is deliberately one-shot (open / publish / close) because
 * it serves the dev-menu "publish a value" feature where session lifetime
 * is milliseconds. Streaming at 10 fps would mean re-handshaking the
 * broker 600 times per minute, which most brokers rate-limit and which
 * eats 90% of the wall-clock budget on TCP setup. So we keep this
 * dedicated to streaming and leave [MqttPublisher] for fire-and-forget.
 *
 * Reconnect: when the socket dies (broker restart, NAT translation
 * timeout, transient network blip) [publish] returns false and the
 * background ping thread will lazily re-CONNECT on the next call. We
 * don't proactively reconnect from a heartbeat because the surrounding
 * stream cadence (10+ Hz) is enough to discover dead sockets quickly,
 * and a dedicated reconnect timer adds complexity we don't need.
 *
 * QoS-0 throughout: we don't track packet IDs or wait for PUBACK. A
 * dropped frame is acceptable for a live video stream; the consumer
 * (HA's MQTT camera platform) renders the latest received frame anyway.
 */
class MqttStreamSession(
    private val host: String,
    private val port: Int,
    private val clientId: String,
    private val username: String?,
    private val password: String?,
    private val useTls: Boolean,
    private val keepAliveSeconds: Int = 60,
) {
    private val started = AtomicBoolean(false)
    @Volatile private var socket: Socket? = null
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var inp: DataInputStream? = null
    @Volatile private var pingThread: Thread? = null
    private val ioLock = Any()
    /** True when CONNECT has completed and the socket is usable. False on
     *  fresh start, after a disconnect, or after a publish failed. */
    @Volatile private var ready: Boolean = false
    /** Monotonic timestamp (SystemClock.elapsedRealtime-equivalent via
     *  System.nanoTime) of the last reconnect attempt. The frame collector
     *  publishes at 10+ Hz; against a dead broker each publish would call
     *  connect() with a 10s socket timeout under [ioLock], so without a
     *  cooldown the collector is pinned 10s at a time and the frame
     *  SharedFlow's DROP_OLDEST silently swallows every frame in between.
     *  We rate-limit reconnect attempts: at most one connect() per
     *  [reconnectCooldownMs]. Between attempts publish() returns false fast
     *  so the collector keeps draining frames (and the MJPEG sink stays
     *  live). Self-heal is preserved: the very next publish after the
     *  cooldown elapses retries, so a broker that comes back is picked up
     *  within one cooldown window. nanoTime is used because it is immune to
     *  wall-clock jumps (NTP / user changing the clock). */
    @Volatile private var lastConnectAttemptNanos: Long = 0L

    fun start(): Boolean {
        if (!started.compareAndSet(false, true)) return ready
        // Seed the cooldown clock so a failed initial connect doesn't let
        // the first frame publish retry instantly; reconnect attempts are
        // rate-limited from the very first failure onward.
        lastConnectAttemptNanos = System.nanoTime()
        return runCatching { connect() }.getOrElse { t ->
            R1Log.w("IotCamera.mqtt", "initial connect failed: ${t.message}")
            false
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        ready = false
        synchronized(ioLock) {
            runCatching { out?.writeByte(0xE0); out?.writeByte(0x00); out?.flush() }
            runCatching { socket?.close() }
            socket = null
            out = null
            inp = null
        }
        runCatching { pingThread?.interrupt() }
        pingThread = null
    }

    /** Publish [payload] to [topic] at QoS 0. Returns true when the bytes
     *  cleared the socket buffer, false when the session was dead. On
     *  failure the caller should keep streaming — the next call will
     *  attempt to reconnect. */
    fun publish(topic: String, payload: ByteArray, retain: Boolean): Boolean {
        if (!started.get()) return false
        if (!ready) {
            // Reconnect cooldown: a dead broker would otherwise make every
            // frame publish pay the full 10s connect timeout, pinning the
            // frame collector. Bail fast until the cooldown elapses; the
            // caller treats false as "frame dropped, keep streaming".
            val now = System.nanoTime()
            val sinceLast = now - lastConnectAttemptNanos
            if (lastConnectAttemptNanos != 0L && sinceLast < RECONNECT_COOLDOWN_NANOS) {
                return false
            }
            lastConnectAttemptNanos = now
            if (!runCatching { connect() }.getOrDefault(false)) return false
        }
        return synchronized(ioLock) {
            val o = out ?: return@synchronized false
            runCatching {
                writePublish(o, topic, payload, retain)
                o.flush()
                true
            }.getOrElse { t ->
                R1Log.d("IotCamera.mqtt", "publish to '$topic' failed: ${t.message}")
                ready = false
                runCatching { socket?.close() }
                socket = null
                out = null
                inp = null
                false
            }
        }
    }

    private fun connect(): Boolean {
        synchronized(ioLock) {
            // Tear down any half-open state from a previous attempt before
            // we open the new socket; otherwise the GC takes its time and
            // FDs leak.
            runCatching { socket?.close() }
            val sock = openSocket(host, port, useTls)
            sock.soTimeout = 10_000
            val o = DataOutputStream(sock.getOutputStream())
            val i = DataInputStream(sock.getInputStream())
            writeConnect(o, clientId, username, password, keepAliveSeconds)
            o.flush()
            val ackType = i.readUnsignedByte()
            if (ackType != 0x20) {
                runCatching { sock.close() }
                error("expected CONNACK (0x20), got 0x${ackType.toString(16)}")
            }
            val ackLen = readRemainingLength(i)
            if (ackLen != 2) {
                runCatching { sock.close() }
                error("CONNACK remaining-length must be 2, got $ackLen")
            }
            i.readUnsignedByte() // session present
            val rc = i.readUnsignedByte()
            if (rc != 0) {
                runCatching { sock.close() }
                error("CONNECT refused, return code=$rc")
            }
            // Clear the read timeout now that the handshake is done; we
            // don't read from the broker again for streaming (no
            // subscribe), so blocking on input would just keep a thread
            // parked. Setting it to 0 = no timeout.
            sock.soTimeout = 0
            socket = sock
            out = o
            inp = i
            ready = true
            R1Log.i("IotCamera.mqtt", "connected to $host:$port (tls=$useTls)")
        }
        startPingThread()
        return true
    }

    /** Periodic PINGREQ so brokers with strict keep-alive enforcement
     *  don't drop us during a silent stretch (e.g. all frames identical
     *  and the encoder happens to bail). Half the keep-alive interval
     *  gives the broker plenty of headroom. */
    private fun startPingThread() {
        runCatching { pingThread?.interrupt() }
        pingThread = thread(name = "r1ha-mqtt-ping", isDaemon = true) {
            try {
                while (started.get() && ready) {
                    Thread.sleep((keepAliveSeconds * 1000L) / 2)
                    if (!ready) break
                    synchronized(ioLock) {
                        val o = out ?: return@synchronized
                        runCatching {
                            o.writeByte(0xC0)
                            o.writeByte(0x00)
                            o.flush()
                        }.onFailure {
                            ready = false
                        }
                    }
                }
            } catch (_: InterruptedException) {
                // normal shutdown
            }
        }
    }

    private fun openSocket(host: String, port: Int, useTls: Boolean): Socket =
        if (useTls) {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            factory.createSocket().apply { connect(InetSocketAddress(host, port), 10_000) }
        } else {
            Socket().apply { connect(InetSocketAddress(host, port), 10_000) }
        }

    private fun writeConnect(
        o: DataOutputStream,
        clientId: String,
        username: String?,
        password: String?,
        keepAliveSeconds: Int,
    ) {
        val body = ByteArrayOutputStream()
        val v = DataOutputStream(body)
        v.writeShort(4); v.writeBytes("MQTT")
        v.writeByte(0x04)
        var flags = 0x02 // clean session
        if (!username.isNullOrEmpty()) flags = flags or 0x80
        if (!password.isNullOrEmpty()) flags = flags or 0x40
        v.writeByte(flags)
        v.writeShort(keepAliveSeconds.coerceIn(10, 65535))
        writeUtf(v, clientId)
        if (!username.isNullOrEmpty()) writeUtf(v, username)
        if (!password.isNullOrEmpty()) {
            val pbytes = password.toByteArray(Charsets.UTF_8)
            v.writeShort(pbytes.size); v.write(pbytes)
        }
        val payload = body.toByteArray()
        o.writeByte(0x10)
        writeRemainingLength(o, payload.size)
        o.write(payload)
    }

    private fun writePublish(o: DataOutputStream, topic: String, payload: ByteArray, retain: Boolean) {
        val body = ByteArrayOutputStream()
        val v = DataOutputStream(body)
        writeUtf(v, topic)
        v.write(payload)
        val frame = body.toByteArray()
        val header = 0x30 or (if (retain) 0x01 else 0x00)
        o.writeByte(header)
        writeRemainingLength(o, frame.size)
        o.write(frame)
    }

    private fun writeUtf(o: DataOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        o.writeShort(bytes.size)
        o.write(bytes)
    }

    private fun writeRemainingLength(o: DataOutputStream, length: Int) {
        var remaining = length
        do {
            var b = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining > 0) b = b or 0x80
            o.writeByte(b)
        } while (remaining > 0)
    }

    private fun readRemainingLength(i: DataInputStream): Int {
        var multiplier = 1
        var value = 0
        repeat(4) {
            val b = i.readUnsignedByte()
            value += (b and 0x7F) * multiplier
            if ((b and 0x80) == 0) return value
            multiplier *= 128
        }
        error("remaining-length overflow")
    }

    companion object {
        /** Minimum spacing between reconnect attempts when the broker is
         *  down. Long enough that a dead broker can't pin the 10+ Hz frame
         *  collector (each blocked attempt costs up to one 10s connect
         *  timeout), short enough that recovery after the broker returns is
         *  prompt. 5s means worst-case recovery latency is one cooldown plus
         *  one connect, and a permanently dead broker costs ~1 connect
         *  attempt every 5s instead of one per frame. */
        private val RECONNECT_COOLDOWN_NANOS = 5_000_000_000L
    }
}
