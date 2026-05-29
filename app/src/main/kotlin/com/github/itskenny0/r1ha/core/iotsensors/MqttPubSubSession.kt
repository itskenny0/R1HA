package com.github.itskenny0.r1ha.core.iotsensors

import com.github.itskenny0.r1ha.core.util.R1Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * MQTT v3.1.1 session with publish AND subscribe support. IoT Sensors Mode
 * needs to push state to the broker (battery, light, etc.) and also receive
 * commands from HA (flashlight on/off, brightness set), so a publish-only
 * surface like [com.github.itskenny0.r1ha.core.mqtt.MqttPublisher] or the
 * stream-tuned [com.github.itskenny0.r1ha.core.iotcamera.MqttStreamSession]
 * doesn't cover the use case.
 *
 * Wire scope kept deliberately small:
 *   - QoS 0 in both directions (state retransmits next interval, commands
 *     are idempotent — losing one isn't a correctness problem here).
 *   - No packet-identifier bookkeeping outside SUBSCRIBE (where the broker
 *     requires one).
 *   - One background reader thread parses inbound frames and dispatches
 *     PUBLISH payloads to [onMessage]. SUBACK and PINGRESP are consumed and
 *     discarded.
 *   - PINGREQ runs on its own daemon thread at half the keep-alive interval.
 *
 * Reconnect strategy mirrors the camera session: on any IO failure we mark
 * the session not-ready and the next publish() or the start() retry-loop
 * reopens. The owning service polls periodically and resubscribes after a
 * successful reconnect (handled by [resubscribeAll]).
 */
class MqttPubSubSession(
    private val host: String,
    private val port: Int,
    private val clientId: String,
    private val username: String?,
    private val password: String?,
    private val useTls: Boolean,
    private val keepAliveSeconds: Int = 60,
    /** Called for every inbound PUBLISH the broker forwards. Runs on the
     *  background reader thread — handlers should hop to their own context
     *  before doing heavy work or touching UI state. */
    private val onMessage: (topic: String, payload: ByteArray) -> Unit = { _, _ -> },
) {
    private val started = AtomicBoolean(false)
    @Volatile private var socket: Socket? = null
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var inp: DataInputStream? = null
    @Volatile private var readerThread: Thread? = null
    @Volatile private var pingThread: Thread? = null
    private val ioLock = Any()
    @Volatile private var ready: Boolean = false
    /** nanoTime of the last reconnect attempt. Rate-limits the implicit
     *  reconnect that publish() / subscribe() trigger when the session is
     *  down: against a dead broker each call would otherwise pay the full
     *  10s connect timeout, and with the periodic publisher plus the
     *  battery / screen broadcast receivers all funnelling through here that
     *  piles up 10s-blocked coroutines on the IO dispatcher. With the
     *  cooldown a dead broker costs at most one connect attempt per window;
     *  in between, calls return false fast. Self-heal is preserved: the
     *  first call after the cooldown elapses retries, so recovery latency is
     *  bounded by one window. nanoTime is wall-clock-jump immune. */
    @Volatile private var lastConnectAttemptNanos: Long = 0L
    private val packetId = AtomicInteger(1)
    /** Topics we've successfully subscribed to. Replayed after a reconnect so
     *  the owning service doesn't have to track and re-issue them. */
    private val subscriptions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun isReady(): Boolean = ready

    fun start(): Boolean {
        if (!started.compareAndSet(false, true)) return ready
        // Seed the cooldown clock so a failed initial connect doesn't let the
        // first publish / subscribe retry instantly.
        lastConnectAttemptNanos = System.nanoTime()
        return runCatching { connect() }.getOrElse { t ->
            R1Log.w("IotSensors.mqtt", "initial connect failed: ${t.message}")
            false
        }
    }

    /** Attempt a reconnect, but no more than once per
     *  [RECONNECT_COOLDOWN_NANOS]. Returns true when the session is ready
     *  after the call. Cheap-fails (no connect) while inside the cooldown so
     *  a dead broker can't pin callers on the 10s connect timeout. */
    private fun reconnectIfDue(): Boolean {
        if (ready) return true
        val now = System.nanoTime()
        if (lastConnectAttemptNanos != 0L &&
            now - lastConnectAttemptNanos < RECONNECT_COOLDOWN_NANOS
        ) {
            return false
        }
        lastConnectAttemptNanos = now
        return runCatching { connect() }.getOrDefault(false)
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        ready = false
        synchronized(ioLock) {
            runCatching {
                val o = out ?: return@runCatching
                o.writeByte(0xE0); o.writeByte(0x00); o.flush()
            }
            runCatching { socket?.close() }
            socket = null
            out = null
            inp = null
        }
        runCatching { readerThread?.interrupt() }
        runCatching { pingThread?.interrupt() }
        readerThread = null
        pingThread = null
        subscriptions.clear()
    }

    fun publish(topic: String, payload: ByteArray, retain: Boolean): Boolean {
        if (!started.get()) return false
        if (!reconnectIfDue()) return false
        return synchronized(ioLock) {
            val o = out ?: return@synchronized false
            runCatching {
                writePublish(o, topic, payload, retain)
                o.flush()
                true
            }.getOrElse { t ->
                R1Log.d("IotSensors.mqtt", "publish '$topic' failed: ${t.message}")
                markDead()
                false
            }
        }
    }

    /** SUBSCRIBE to [topic] at QoS 0. Idempotent — repeat calls are no-ops.
     *  Survives reconnects via [resubscribeAll]. */
    fun subscribe(topic: String): Boolean {
        // Remember the topic regardless of connection state — resubscribeAll
        // replays it once the session recovers, so a subscribe issued while
        // the broker is down is not lost.
        subscriptions.add(topic)
        if (!reconnectIfDue()) return false
        return synchronized(ioLock) {
            val o = out ?: return@synchronized false
            runCatching {
                writeSubscribe(o, topic)
                o.flush()
                true
            }.getOrElse { t ->
                R1Log.d("IotSensors.mqtt", "subscribe '$topic' failed: ${t.message}")
                markDead()
                false
            }
        }
    }

    private fun resubscribeAll() {
        // Snapshot to a list so a concurrent subscribe() can't mutate the
        // backing set mid-iteration.
        val snapshot = subscriptions.toList()
        synchronized(ioLock) {
            val o = out ?: return
            for (topic in snapshot) {
                runCatching {
                    writeSubscribe(o, topic)
                    o.flush()
                }.onFailure { t ->
                    R1Log.d("IotSensors.mqtt", "resubscribe '$topic' failed: ${t.message}")
                }
            }
        }
    }

    private fun connect(): Boolean {
        synchronized(ioLock) {
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
            i.readUnsignedByte()
            val rc = i.readUnsignedByte()
            if (rc != 0) {
                runCatching { sock.close() }
                error("CONNECT refused, return code=$rc")
            }
            // No timeout once handshaking is done — the reader thread blocks
            // on readUnsignedByte until a frame arrives or the socket dies.
            sock.soTimeout = 0
            socket = sock
            out = o
            inp = i
            ready = true
            R1Log.i("IotSensors.mqtt", "connected to $host:$port (tls=$useTls)")
        }
        startReaderThread()
        startPingThread()
        resubscribeAll()
        return true
    }

    private fun startReaderThread() {
        runCatching { readerThread?.interrupt() }
        readerThread = thread(name = "r1ha-iot-sensors-mqtt-reader", isDaemon = true) {
            try {
                loop@ while (started.get() && ready) {
                    val i = inp ?: break@loop
                    val headerResult = runCatching { i.readUnsignedByte() }
                    val header = headerResult.getOrNull()
                    if (header == null) { markDead(); break@loop }
                    val lengthResult = runCatching { readRemainingLength(i) }
                    val length = lengthResult.getOrNull()
                    if (length == null) { markDead(); break@loop }
                    val type = header and 0xF0
                    when (type) {
                        0x30 -> readPublishAndDispatch(i, length, header)
                        0x90, 0xD0 -> skipBytes(i, length) // SUBACK / PINGRESP
                        else -> skipBytes(i, length)
                    }
                }
            } catch (_: InterruptedException) {
                // normal shutdown
            } catch (t: Throwable) {
                R1Log.d("IotSensors.mqtt", "reader loop ended: ${t.message}")
                markDead()
            }
        }
    }

    private fun startPingThread() {
        runCatching { pingThread?.interrupt() }
        pingThread = thread(name = "r1ha-iot-sensors-mqtt-ping", isDaemon = true) {
            try {
                while (started.get() && ready) {
                    Thread.sleep((keepAliveSeconds * 1000L) / 2)
                    if (!ready) break
                    synchronized(ioLock) {
                        val o = out ?: return@synchronized
                        runCatching {
                            o.writeByte(0xC0); o.writeByte(0x00); o.flush()
                        }.onFailure { markDead() }
                    }
                }
            } catch (_: InterruptedException) {
                // normal shutdown
            }
        }
    }

    private fun readPublishAndDispatch(i: DataInputStream, remaining: Int, header: Int) {
        // QoS-0 PUBLISH only (no packet identifier). Variable header layout:
        //   topic UTF-8 string (2-byte length + bytes)
        //   payload (rest of the remaining-length)
        // For QoS-1/2 publishes there'd be a 2-byte packet id after the
        // topic; we don't subscribe at higher QoS so the broker never sends
        // them, but if it did we'd misalign — keep an explicit check.
        val qos = (header and 0x06) shr 1
        var consumed = 0
        val topicLen = i.readUnsignedShort(); consumed += 2
        val topicBytes = ByteArray(topicLen)
        i.readFully(topicBytes); consumed += topicLen
        if (qos > 0) {
            i.readUnsignedShort(); consumed += 2 // discard packet id
        }
        val payloadLen = (remaining - consumed).coerceAtLeast(0)
        val payload = ByteArray(payloadLen)
        if (payloadLen > 0) i.readFully(payload)
        val topic = String(topicBytes, Charsets.UTF_8)
        runCatching { onMessage(topic, payload) }.onFailure { t ->
            R1Log.w("IotSensors.mqtt", "onMessage handler threw: ${t.message}")
        }
    }

    private fun skipBytes(i: DataInputStream, n: Int) {
        var remaining = n
        val buf = ByteArray(256)
        while (remaining > 0) {
            val read = i.read(buf, 0, minOf(remaining, buf.size))
            if (read <= 0) break
            remaining -= read
        }
    }

    private fun markDead() {
        ready = false
        synchronized(ioLock) {
            runCatching { socket?.close() }
            socket = null
            out = null
            inp = null
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
        var flags = 0x02
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

    private fun writeSubscribe(o: DataOutputStream, topic: String) {
        val body = ByteArrayOutputStream()
        val v = DataOutputStream(body)
        // Packet identifier — non-zero per spec. We don't track SUBACKs by
        // id (we just discard them) so monotonic-rolling is enough.
        val pid = (packetId.getAndIncrement() and 0xFFFF).let { if (it == 0) 1 else it }
        v.writeShort(pid)
        writeUtf(v, topic)
        v.writeByte(0x00) // QoS 0
        val frame = body.toByteArray()
        // 0x82 = SUBSCRIBE (type 8 + reserved flags 0010 per spec).
        o.writeByte(0x82)
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
        /** Minimum spacing between reconnect attempts while the broker is
         *  down. Bounds the cost of a dead broker to ~one 10s connect every
         *  5s instead of one per publish / event, while keeping recovery
         *  prompt once the broker returns. */
        private val RECONNECT_COOLDOWN_NANOS = 5_000_000_000L
    }
}
