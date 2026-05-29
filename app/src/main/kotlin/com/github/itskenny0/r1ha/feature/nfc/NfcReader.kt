package com.github.itskenny0.r1ha.feature.nfc

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import com.github.itskenny0.r1ha.App
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Foreground NFC tag-scan handler. Bound to MainActivity's onResume / onPause via
 * [bind] / [unbind] which set up the platform's reader mode while the app is in
 * the foreground — the user taps a tag against the phone and we fire HA's
 * `tag.scanned` event with the tag's UID as `tag_id`. HA picks up the event and
 * any matching tag-trigger automation runs.
 *
 * Foreground-only by design: we don't register an ACTION_NDEF_DISCOVERED filter
 * in the manifest because that would compete with the user's existing tag
 * handler app (the official HA Companion, NFC Tools, etc.). The toggle lives
 * in Advanced settings; off by default for the same reason.
 */
object NfcReader {

    /** Read the NFC adapter on the device, or null when there's no hardware. */
    fun adapterOrNull(activity: Activity): NfcAdapter? =
        NfcAdapter.getDefaultAdapter(activity.applicationContext)

    /** Engage reader mode on [activity]. Idempotent — calling twice replaces the
     *  callback. The callback fires on a binder thread; we dispatch onto the
     *  app's IO scope to keep HA call latency off the UI thread. */
    fun bind(activity: Activity) {
        val adapter = adapterOrNull(activity) ?: return
        if (!adapter.isEnabled) {
            // NFC chip present but turned off. The user can still configure the
            // toggle; nothing to bind right now.
            return
        }
        val app = activity.applicationContext as? App ?: return
        val readerScope = CoroutineScope(Dispatchers.IO)
        // enableReaderMode requires the activity to be resumed and can throw
        // IllegalStateException if it isn't (a race against a fast pause, or
        // certain OEM NFC stacks). bind() runs from onResume, so a throw here
        // would crash the app on a quirky device; guard it and leave reader
        // mode simply not engaged (onPause's unbind stays safe either way).
        runCatching {
            adapter.enableReaderMode(
                activity,
                { tag -> onTag(app, readerScope, tag) },
                // Cover the common HA tag types: A (most consumer tags), B, F, V,
                // plus the NDEF content type for tags with payload. We don't read
                // payload — the UID alone is the HA tag_id — so the flags are
                // essentially "wake me for any tag".
                NfcAdapter.FLAG_READER_NFC_A
                    or NfcAdapter.FLAG_READER_NFC_B
                    or NfcAdapter.FLAG_READER_NFC_F
                    or NfcAdapter.FLAG_READER_NFC_V
                    or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                null,
            )
        }.onFailure {
            R1Log.w("NfcReader", "enableReaderMode failed: ${it.message}")
            return
        }
        R1Log.i("NfcReader", "reader mode enabled")
    }

    /** Disable reader mode on [activity]. Safe to call when bind wasn't called. */
    fun unbind(activity: Activity) {
        val adapter = adapterOrNull(activity) ?: return
        runCatching { adapter.disableReaderMode(activity) }
    }

    private fun onTag(app: App, scope: CoroutineScope, tag: Tag) {
        val tagId = bytesToHex(tag.id)
        scope.launch {
            val settings = app.graph.settings.settings.first()
            if (!settings.advanced.nfcTagScannerEnabled) {
                // User flipped off the toggle while reader mode was active —
                // honour it without re-disabling reader mode (the activity
                // lifecycle handles bind/unbind).
                R1Log.d("NfcReader", "tag $tagId ignored (toggle off)")
                return@launch
            }
            R1Log.i("NfcReader", "tag scanned: $tagId")
            val data = buildJsonObject {
                put("tag_id", JsonPrimitive(tagId))
                put("device_id", JsonPrimitive("r1ha"))
            }
            app.graph.haRepository.fireEvent(eventType = "tag_scanned", data = data).fold(
                onSuccess = { Toaster.show("Fired tag_scanned: $tagId") },
                onFailure = { t ->
                    Toaster.errorExpandable(
                        shortText = "tag_scanned event failed",
                        fullText = t.message ?: t.toString(),
                    )
                },
            )
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b.toInt() and 0xFF))
        return sb.toString()
    }
}
