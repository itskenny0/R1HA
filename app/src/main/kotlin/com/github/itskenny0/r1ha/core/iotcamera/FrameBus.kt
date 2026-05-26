package com.github.itskenny0.r1ha.core.iotcamera

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Single-slot frame fan-out used by [IotCameraService] to broker JPEG
 * frames from the capture pipeline to every connected sink (MJPEG
 * clients, MQTT publisher). Each emit replaces the previous frame so a
 * slow consumer never accumulates backlog — they always see the latest
 * available frame and drop intermediates. That's the right trade for a
 * live stream: showing yesterday's frame is worse than skipping forward.
 *
 * Implementation note: [MutableSharedFlow] with replay=1 and
 * [BufferOverflow.DROP_OLDEST] gives a free "latest-known" cache for any
 * new subscriber (e.g. a fresh MJPEG client joining mid-stream sees a
 * frame immediately rather than waiting for the next encode) without
 * keeping a history buffer that would balloon memory on a 30 fps stream.
 */
class FrameBus {
    private val _frames = MutableSharedFlow<ByteArray>(
        replay = 1,
        extraBufferCapacity = 0,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Read-only stream of raw JPEG frames, newest-only. */
    val frames: SharedFlow<ByteArray> = _frames.asSharedFlow()

    /** Number of active consumers, exposed so the capture pipeline can
     *  short-circuit JPEG encoding when nothing's listening. Frees the
     *  user from paying the encode cost during the brief windows where
     *  the master toggle is on but neither sink is connected. */
    val subscriberCount get() = _frames.subscriptionCount

    fun publish(jpeg: ByteArray) {
        // tryEmit always succeeds with DROP_OLDEST overflow strategy +
        // a non-zero replay buffer. The replay slot just gets overwritten.
        _frames.tryEmit(jpeg)
    }

    /** Latest frame the bus has seen, or null when nothing has been
     *  published yet. Cheap snapshot — peek into the replay slot rather
     *  than spinning a collector. Useful for the MJPEG `/snapshot`
     *  endpoint, which is one-shot and wants the current frame without
     *  the suspending dance. */
    fun latest(): ByteArray? = _frames.replayCache.firstOrNull()
}
