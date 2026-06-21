package com.github.itskenny0.r1ha.core.iotcamera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Size
import java.util.Locale

/**
 * Walks every Camera2 id the device exposes and produces user-pickable
 * descriptors. Devices with multi-lens setups (phones with wide + tele +
 * ultrawide on the back, tablets with stereo fronts) surface each physical
 * lens as its own logical id; the labels we hand back tell them apart by
 * focal length so the user can pick the exact lens rather than just FRONT
 * or BACK.
 *
 * "Logical vs physical" wrinkle: on API 28+ a vendor can ship a synthetic
 * "back camera" that fuses several physicals behind one id. We list both
 * the logical and the per-physical ids so the user can choose to bind to
 * the fused camera (auto-switching FOV) or pin to one specific sensor
 * (predictable framing). Devices without multi-camera support just see one
 * row per facing direction.
 */
object CameraEnumerator {

    data class CameraDescriptor(
        /** Camera2 id string. Stable across boots on a given device; pass
         *  to [CameraManager.openCamera]. */
        val id: String,
        /** Short label for the picker — "BACK · 26mm", "FRONT · WIDE", etc. */
        val label: String,
        /** Long-form description. Surfaces sensor active area + supported
         *  output sizes in the row's subtitle so the user can sanity-check
         *  before picking. */
        val description: String,
        /** Sorted descending by area so the picker can default to the
         *  highest-resolution mode for the chosen camera. */
        val supportedJpegSizes: List<Size>,
        /** LENS_FACING_* enum constant. Used to group rows by facing. */
        val facing: Int,
    )

    fun list(context: Context): List<CameraDescriptor> {
        // Always resolve the CameraManager through applicationContext. The camera
        // subsystem's process singleton (CameraManagerGlobal) retains the context it
        // was obtained from; handed a Service context (IotCameraService calls
        // pickDefault(this) at startup) it pins the whole service past onDestroy
        // (LeakCanary caught exactly this). Pinning the Application instead is harmless
        // since it lives for the process lifetime. Same mitigation as CameraCapture.
        val manager = context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()
        val logicalIds = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        val seenIds = mutableSetOf<String>()
        val results = mutableListOf<CameraDescriptor>()

        fun add(id: String, parent: String?) {
            val key = parent?.let { "phys:$it:$id" } ?: id
            if (!seenIds.add(key)) return
            runCatching { describe(manager, id, physicalParent = parent) }.getOrNull()
                ?.let(results::add)
        }

        // Standard enumeration: every id the HAL is willing to advertise.
        // On most devices this gives the user the expected back + front
        // pair, which is enough.
        for (id in logicalIds) {
            add(id, parent = null)
            // Logical multi-camera devices (modern Pixels, some Samsungs)
            // expose 1-2 logical IDs but bundle 3-4 physical sensors
            // behind each via INFO_PHYSICAL_CAMERA_IDS. API 28+ only —
            // older devices either don't have multi-physical or expose
            // every physical as its own logical.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching {
                    val chars = manager.getCameraCharacteristics(id)
                    chars.physicalCameraIds.forEach { physId -> add(physId, parent = id) }
                }
            }
        }

        return results.sortedWith(
            // Back cameras first (most common stream target), then front,
            // then external — and within a facing group, larger sensors
            // first so the "main" wide-angle sits at the top of its group.
            compareBy<CameraDescriptor>(
                { facingOrder(it.facing) },
                { -(it.supportedJpegSizes.firstOrNull()?.let { s -> s.width * s.height } ?: 0) },
            ),
        )
    }

    /** Find a default camera id when the user hasn't picked one explicitly.
     *  Prefers the first back-facing camera (most common surveillance use
     *  case), falls back to the first available id on devices that only
     *  ship a front camera (a few Chromebooks, dev boards). */
    fun pickDefault(context: Context): String? {
        val list = list(context)
        return list.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_BACK }?.id
            ?: list.firstOrNull()?.id
    }

    private fun describe(
        manager: CameraManager,
        id: String,
        physicalParent: String?,
    ): CameraDescriptor {
        val chars = manager.getCameraCharacteristics(id)
        val facing = chars.get(CameraCharacteristics.LENS_FACING)
            ?: CameraCharacteristics.LENS_FACING_EXTERNAL
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?: floatArrayOf()
        val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = streamMap?.getOutputSizes(ImageFormat.JPEG)?.toList()
            ?.sortedByDescending { it.width * it.height }
            ?: emptyList()
        val facingTag = when (facing) {
            CameraCharacteristics.LENS_FACING_BACK -> "BACK"
            CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
            else -> "EXTERNAL"
        }
        // Focal lengths come back in mm in the sensor's coordinate space, not
        // 35mm equivalent, so the numbers won't match what users see in
        // marketing copy ("26mm wide" on a phone is usually ~5mm sensor-real).
        // We still display the sensor-real number because it's stable and
        // unambiguous; tagging "WIDE/TELE/ULTRAWIDE" on multi-lens devices
        // gives a friendlier hint than millimetres alone.
        val focalLabel = when {
            focalLengths.isEmpty() -> "id $id"
            focalLengths.size == 1 -> "${"%.1f".format(Locale.US, focalLengths[0])}mm"
            else -> focalLengths.joinToString(separator = " / ") { "%.1f".format(Locale.US, it) } + "mm"
        }
        // Synthesize a sortable "physical sensor" hint from the focal
        // length — under 4mm tends to be ultrawide, 4-7mm wide, 7+ tele.
        // It's a heuristic but matches the OEM's marketing labels on every
        // multi-camera device we've checked.
        val sensorHint = focalLengths.firstOrNull()?.let { f ->
            when {
                f < 3f -> " (ULTRAWIDE)"
                f < 5f -> " (WIDE)"
                f >= 7f -> " (TELE)"
                else -> ""
            }
        } ?: ""
        val physicalTag = if (physicalParent != null) " · PHYS" else ""
        val label = "$facingTag · $focalLabel$sensorHint$physicalTag"
        val topSize = sizes.firstOrNull()
        val description = buildString {
            if (physicalParent != null) {
                append("phys ").append(id).append(" (parent ").append(physicalParent).append(")")
            } else {
                append("id ").append(id)
            }
            if (topSize != null) {
                append(" · max ").append(topSize.width).append("×").append(topSize.height)
            }
            if (sizes.size > 1) {
                append(" · ").append(sizes.size).append(" sizes")
            }
        }
        // Encode physical-camera selection in the id we hand back to the
        // capture pipeline. Format: "phys:<parentLogical>:<physicalId>".
        // CameraCapture splits this on use and passes the physical id via
        // OutputConfiguration.setPhysicalCameraId so we capture from the
        // exact sensor the user picked.
        val captureId = if (physicalParent != null) "phys:$physicalParent:$id" else id
        return CameraDescriptor(
            id = captureId,
            label = label,
            description = description,
            supportedJpegSizes = sizes,
            facing = facing,
        )
    }

    private fun facingOrder(facing: Int): Int = when (facing) {
        CameraCharacteristics.LENS_FACING_BACK -> 0
        CameraCharacteristics.LENS_FACING_FRONT -> 1
        else -> 2
    }
}
