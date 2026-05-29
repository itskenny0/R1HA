package com.github.itskenny0.r1ha.feature.nfc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.HaTag
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.Locale

/**
 * Drives the NFC surface. Reads HA's tag registry (`tag/list`) so the user
 * can audit which NFC / QR tags exist and when each last fired, and offers a
 * "simulate scan" affordance that fires HA's `tag_scanned` event for a tag id
 * without needing the physical tag in hand.
 *
 * The simulate-scan path mirrors [NfcReader.onTag] exactly: same event type,
 * same `tag_id` + `device_id` payload, so a simulated scan from this screen is
 * indistinguishable to HA from a real tap against the device. That makes it a
 * faithful way to test a tag-trigger automation from the couch.
 *
 * Resolving which automation(s) a given tag triggers would require reading each
 * automation's trigger config, which the repository doesn't expose today; the
 * screen surfaces a short hint instead of a half-built mapping.
 */
class NfcViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val tags: List<HaTag> = emptyList(),
        /** Free-text id the user typed into the manual simulate field. */
        val manualId: String = "",
        /** Tag id currently being fired, so the row's button can show a
         *  busy state and ignore a double-tap. Null when idle. */
        val firingId: String? = null,
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun setManualId(value: String) {
        if (_ui.value.manualId == value) return
        _ui.value = _ui.value.copy(manualId = value)
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            haRepository.listTags().fold(
                onSuccess = { tags ->
                    R1Log.i("Nfc", "fetched ${tags.size} tag(s)")
                    _ui.value = _ui.value.copy(loading = false, tags = sortTags(tags), error = null)
                },
                onFailure = { t ->
                    R1Log.w("Nfc", "list failed: ${t.message}")
                    _ui.value = _ui.value.copy(loading = false, error = t.message)
                },
            )
        }
    }

    /**
     * Fire a `tag_scanned` event for [rawId]. Normalises the id first so a
     * pasted "AA:BB:CC" or "  ab12  " resolves to the canonical lowercase hex
     * HA stores. A blank id after normalisation is rejected without a network
     * round trip. On success we re-fetch so the just-scanned tag's last-scanned
     * timestamp bubbles up.
     */
    fun simulateScan(rawId: String) {
        val id = normalizeTagId(rawId)
        if (id.isEmpty()) {
            Toaster.error("Enter a tag id to simulate")
            return
        }
        if (_ui.value.firingId != null) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(firingId = id)
            val data = buildJsonObject {
                put("tag_id", JsonPrimitive(id))
                put("device_id", JsonPrimitive(DEVICE_ID))
            }
            haRepository.fireEvent(eventType = EVENT_TYPE, data = data).fold(
                onSuccess = {
                    R1Log.i("Nfc", "simulated scan: $id")
                    Toaster.show("Fired $EVENT_TYPE: $id")
                },
                onFailure = { t ->
                    R1Log.w("Nfc", "simulate $id failed: ${t.message}")
                    Toaster.errorExpandable(
                        shortText = "$EVENT_TYPE failed",
                        fullText = t.message ?: t.toString(),
                    )
                },
            )
            _ui.value = _ui.value.copy(firingId = null)
            // Give HA a beat to record the event, then refresh so the row's
            // last-scanned label reflects the simulated scan.
            kotlinx.coroutines.delay(600L)
            refresh()
        }
    }

    companion object {
        /** HA event a tag tap fires; tag-trigger automations listen for it. */
        internal const val EVENT_TYPE = "tag_scanned"

        /** Matches [NfcReader] so a simulated scan looks identical to a real
         *  foreground tap from this device. */
        internal const val DEVICE_ID = "r1ha"

        /**
         * Friendly label for a tag row: the user-assigned name when present,
         * otherwise the raw id so every row reads as something. Pure so the
         * screen and tests share one definition of "what do we show".
         */
        internal fun displayName(tag: HaTag): String =
            tag.name?.takeIf { it.isNotBlank() } ?: tag.id

        /**
         * Canonicalise a tag id the way HA stores NFC ids: strip whitespace and
         * the colon / dash separators people paste from a hex dump, then
         * lowercase. A registry id that already carries non-hex characters
         * (some QR payloads do) is left intact apart from trimming + casing, so
         * we only drop separators, never arbitrary content.
         */
        internal fun normalizeTagId(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            return trimmed
                .replace(":", "")
                .replace("-", "")
                .replace(" ", "")
                .lowercase(Locale.US)
        }

        /**
         * Sort newest-scan-first so a tag the user just touched (or just
         * simulated) bubbles to the top; never-scanned tags sink to the
         * bottom, then tie-break by display name so order is stable across
         * refreshes rather than jittering on equal timestamps.
         */
        internal fun sortTags(tags: List<HaTag>): List<HaTag> =
            tags.sortedWith(
                compareByDescending<HaTag> { it.lastScanned?.toEpochMilli() ?: Long.MIN_VALUE }
                    .thenBy { displayName(it).lowercase(Locale.US) },
            )

        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { NfcViewModel(haRepository) }
        }
    }
}
