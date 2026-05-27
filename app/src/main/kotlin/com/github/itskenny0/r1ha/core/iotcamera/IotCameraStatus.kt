package com.github.itskenny0.r1ha.core.iotcamera

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Live state of the IoT Camera Mode pipeline, surfaced by the settings
 * screen so users can tell at a glance whether each sink is running and
 * where to point HA. The service writes; the UI reads. One [StateFlow]
 * per status block so the settings screen doesn't recompose for unrelated
 * changes (e.g. a frame-count tick during the user editing the port).
 */
class IotCameraStatus {

    enum class SinkState {
        /** Sink toggle is off; nothing running. */
        OFF,

        /** Sink is starting up; first request / publish hasn't completed. */
        STARTING,

        /** Sink is up and serving / publishing. */
        ACTIVE,

        /** Sink failed (broker unreachable, port in use, etc.). [errorMessage]
         *  carries the why-text shown next to the indicator. */
        FAILED,
    }

    data class Snapshot(
        val mjpeg: SinkState = SinkState.OFF,
        val mjpegError: String? = null,
        val mqtt: SinkState = SinkState.OFF,
        val mqttError: String? = null,
        /** True once at least one PUBLISH for the discovery config payload
         *  has been written to the broker socket. False = HA won't have
         *  auto-registered an entity yet. */
        val mqttDiscoveryPublished: Boolean = false,
        /** Cumulative bytes pushed across both sinks since the service
         *  started. Tallied at publish-write time so it reflects egress
         *  rather than encoded-frame size — for MJPEG specifically this
         *  multiplies by the number of connected clients, so two viewers
         *  at the same fps doubles the count. */
        val bytesUploadedTotal: Long = 0L,
        /** Rolling bits-per-second over the last ~1 s, computed by a
         *  ticker in the service. Zero between ticks; smoothed enough
         *  to be glanceable but responsive to throttle changes. */
        val bitrateBps: Long = 0L,
    )

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    fun setMjpeg(state: SinkState, error: String? = null) {
        _snapshot.value = _snapshot.value.copy(mjpeg = state, mjpegError = error)
    }

    fun setMqtt(state: SinkState, error: String? = null) {
        _snapshot.value = _snapshot.value.copy(mqtt = state, mqttError = error)
    }

    fun setMqttDiscoveryPublished(published: Boolean) {
        _snapshot.value = _snapshot.value.copy(mqttDiscoveryPublished = published)
    }

    fun reset() {
        _snapshot.value = Snapshot()
    }

    /** Bump the byte counter from a sink. Called from both MJPEG client
     *  writes and MQTT publish-completed paths so the total reflects
     *  what actually went over the wire. */
    fun addBytesUploaded(delta: Long) {
        if (delta <= 0L) return
        val cur = _snapshot.value
        _snapshot.value = cur.copy(bytesUploadedTotal = cur.bytesUploadedTotal + delta)
    }

    /** Set the current rolling bitrate. The service ticker computes this
     *  from a delta of [Snapshot.bytesUploadedTotal] over a fixed window. */
    fun setBitrate(bps: Long) {
        _snapshot.value = _snapshot.value.copy(bitrateBps = bps.coerceAtLeast(0L))
    }
}

/**
 * Best-effort discovery of the device's primary LAN IPv4 address — the
 * one HA would point at if you typed `http://<this>:8181/stream` from
 * another machine on the same network. Skips loopback + virtual + IPv6
 * because pasting an IPv6 into HA's generic camera URL field is friction
 * we don't want to ask the user to navigate.
 *
 * Returns null when no usable interface is up (airplane mode, no Wi-Fi,
 * Ethernet-only device with cable unplugged). Caller surfaces that as
 * "Connect to a network to get a URL" rather than guessing.
 *
 * Cached on call: NetworkInterface enumeration is a syscall but ~ms-fast,
 * so we don't bother caching at the call-site. If the user switches Wi-Fi
 * networks mid-config the new IP is one settings-screen refresh away.
 */
fun discoverLanIpv4(): String? = runCatching {
    val nics = NetworkInterface.getNetworkInterfaces() ?: return null
    nics.toList()
        .asSequence()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { nic ->
            nic.inetAddresses.toList().asSequence().filterIsInstance<Inet4Address>()
        }
        .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress || it.isMulticastAddress }
        .firstOrNull()
        ?.hostAddress
}.getOrNull()
