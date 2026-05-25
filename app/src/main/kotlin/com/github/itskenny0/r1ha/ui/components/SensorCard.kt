package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * Read-only card for sensor / binary_sensor entities — temperature probes, humidity,
 * energy meters, motion detectors, leak alarms, door contacts. No wheel input, no tap
 * action (a tap is silently ignored at the EntityCard wrapper level for sensor domains).
 *
 * For plain sensors the body is a big monospace numeric readout taken straight from the
 * raw HA state (HA already sends a presentation-ready string like "21.7" — no need to
 * coerce it through Number-formatting and lose precision/locale handling). The unit
 * suffix sits inline next to the value. For binary_sensors there's no number; the readout
 * is the state word itself (CLOSED, OPEN, MOTION, CLEAR, etc.) coloured by accent vs
 * muted depending on isOn.
 */
@Composable
fun SensorCard(
    state: EntityState,
    accent: Color,
    domainLabel: String,
    showArea: Boolean,
    /**
     * Absolute readout size in sp from [EntityOverride.textSizeSp]. Null = the smart
     * default — long-text sensors auto-shrink so a news headline / tweet / verbose
     * enum state can fit on a single card without truncation. The user's customize
     * dialog can override this to lock the size at any sp value in
     * [EntityOverride.TEXT_SIZES_SP] regardless of string length.
     */
    textSizeSp: Int? = null,
    modifier: Modifier = Modifier,
) {
    val isBinary = state.id.domain == Domain.BINARY_SENSOR
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(R1.Bg)
            .padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
        // ── Header ─────────────────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(width = 14.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
            Spacer(Modifier.width(8.dp))
            Text(domainLabel, style = R1.labelMicro, color = R1.Ink)
            if (!state.deviceClass.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                Text("·", style = R1.labelMicro, color = R1.InkMuted)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = state.deviceClass.uppercase(),
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                )
            }
            if (showArea && !state.area.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                Text("·", style = R1.labelMicro, color = R1.InkMuted)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = state.area.replace('_', ' ').uppercase(),
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = state.friendlyName,
            style = R1.titleCard,
            color = R1.Ink,
            maxLines = 2,
        )

        Spacer(Modifier.height(20.dp))

        // ── Body: big readout ──────────────────────────────────────────────────────
        if (isBinary) {
            // Binary sensors — render the state word itself (sized like our numeric
            // readouts so the visual weight matches a temperature display). We map a few
            // common device_class values to friendlier words; everything else falls back
            // to the raw state text uppercased.
            val word = friendlyBinaryWord(state)
            val (bodyStyle, _) = sensorReadoutStyle(word, textSizeSp)
            Text(
                text = word,
                style = bodyStyle,
                color = if (state.isOn) accent else R1.InkSoft,
                softWrap = true,
            )
        } else {
            // Plain sensors — render the rawState as the body. A sensor's value can be
            // anything from "21" (temperature) to a 240-char weather summary or an
            // entire tweet, so the body needs to fluidly scale. When the user hasn't
            // pinned a size via the customize dialog, [sensorReadoutStyle] picks a
            // smart default based on string length so long values wrap to multiple
            // lines at a legible size rather than overflowing the card. Soft-wrap is
            // enabled so long text just keeps wrapping; the surrounding Column gives
            // it the full card width to consume.
            val maxDecimals = com.github.itskenny0.r1ha.core.theme.LocalUiOptions.current.maxDecimalPlaces
            val value = formatSensorValue(state.rawState, maxDecimals = maxDecimals)
            val (bodyStyle, suffixStyle) = sensorReadoutStyle(value, textSizeSp)
            // Row when there's a unit suffix (it sits inline with the bottom of the
            // value, R1's "21 °C" idiom); for unitless long-text sensors we drop the
            // Row entirely so the wrapping Text gets the full container width without
            // any horizontal-row layout overhead.
            if (!state.unit.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = value,
                        style = bodyStyle,
                        color = R1.Ink,
                        softWrap = true,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = state.unit,
                        style = suffixStyle,
                        color = accent,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            } else {
                Text(
                    text = value,
                    style = bodyStyle,
                    color = R1.Ink,
                    softWrap = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // ── History block — chart for numeric sensors, recent-changes list for everything
        // else (binary sensors, enum sensors, weather strings, etc.).
        //
        // PERF: lazy-loaded with a 1.5 s dwell delay. SensorHistoryChart is one
        // of the heaviest composables in the app (Canvas drawing of N points
        // + grid + labels), and on quick scrolls past a sensor card we
        // don't want to fetch + render a graph the user never actually saw.
        // The DisposableEffect's coroutine launches on enter, sleeps 1.5 s,
        // then triggers the fetch. If the user swipes away within the dwell,
        // the SensorCard disposes, the coroutine cancels, no network + no
        // Canvas. Returning to the card after a long gap re-fetches fresh.
        val repo = com.github.itskenny0.r1ha.core.theme.LocalHaRepository.current
        val historyState = androidx.compose.runtime.remember(state.id.value) {
            androidx.compose.runtime.mutableStateOf<List<com.github.itskenny0.r1ha.core.ha.HistoryPoint>>(emptyList())
        }
        val dwellElapsed = androidx.compose.runtime.remember(state.id.value) {
            androidx.compose.runtime.mutableStateOf(false)
        }
        val textHistoryLength = com.github.itskenny0.r1ha.core.theme.LocalUiOptions.current.textHistoryLength
        if (repo != null) {
            androidx.compose.runtime.LaunchedEffect(state.id.value) {
                // Sleep 1.5s — if the user swipes off the card in that
                // window, LaunchedEffect cancels and we skip the fetch
                // entirely. dwellElapsed gates the heavy chart render too.
                kotlinx.coroutines.delay(1500L)
                dwellElapsed.value = true
                repo.fetchHistory(state.id, hours = 24)
                    .onSuccess { historyState.value = it }
            }
        }
        // Latest state numeric? Then it's a line chart; otherwise list of changes.
        val latestIsNumeric = state.rawState?.toDoubleOrNull()?.isFinite() == true
        if (!dwellElapsed.value) {
            // Placeholder strip while the dwell timer is running. Same
            // approximate height as the real chart so the card layout
            // doesn't shift when the chart populates.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "—",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
        } else if (latestIsNumeric) {
            SensorHistoryChart(
                points = historyState.value,
                accent = accent,
                unit = state.unit,
            )
        } else {
            SensorHistoryList(
                points = historyState.value,
                accent = accent,
                maxEntries = textHistoryLength,
            )
        }

        Spacer(Modifier.weight(1f))

        // ── Footer hint — clarifies the read-only nature so users don't expect the
        // wheel to do anything here. Stays subtle (labelMicro on InkMuted).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(R1.Hairline),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "READ-ONLY",
            style = R1.labelMicro,
            color = R1.InkMuted,
        )
    }
}

/**
 * Compute the body + suffix [androidx.compose.ui.text.TextStyle] for a sensor readout.
 *
 * When the user has pinned a size via the per-card customize dialog ([override] is
 * non-null), that size is used verbatim and the suffix is scaled proportionally.
 *
 * Otherwise we smart-default: very short values (a temperature like "21" or "21.5")
 * keep the full 72 sp display, but as the string grows we step down through a curated
 * size ramp so longer content — enum states, RSS-style summaries, full tweets — fits
 * on the card without truncation. The breakpoints assume a monospace font on a 240 px
 * wide card (~20 chars/line at 12 sp).
 */
private fun sensorReadoutStyle(
    value: String,
    override: Int?,
): Pair<androidx.compose.ui.text.TextStyle, androidx.compose.ui.text.TextStyle> {
    val bodySp: Float = when {
        override != null -> override.toFloat()
        value.length <= 4 -> 72f
        value.length <= 8 -> 56f
        value.length <= 14 -> 40f
        value.length <= 24 -> 28f
        value.length <= 48 -> 20f
        value.length <= 96 -> 14f
        value.length <= 200 -> 11f
        else -> 9f
    }
    // Lighten letter-spacing on the smaller sizes — the default -2sp tracking on
    // numeralXl reads cramped when shrunk; reset to 0 below 24 sp.
    val tracking = if (bodySp < 24f) 0.sp else (-2).sp
    val body = R1.numeralXl.copy(
        fontSize = bodySp.sp,
        lineHeight = (bodySp * 1.15f).sp,
        letterSpacing = tracking,
    )
    // Suffix tracks the body proportionally but never grows above the default 20 sp,
    // and floors at 8 sp so it stays legible alongside a 9 sp body.
    val suffixSp = (bodySp * 20f / 72f).coerceIn(8f, 20f)
    val suffix = R1.numeralM.copy(fontSize = suffixSp.sp)
    return body to suffix
}

/**
 * Map a binary sensor's [state.rawState] to a presentation word. The HA convention is
 * "on" / "off", but the natural word depends on `device_class` — a door is OPEN/CLOSED,
 * a motion sensor is MOTION/CLEAR, a moisture sensor is WET/DRY, etc. Falls back to a
 * straight TRUE/FALSE for anything we don't recognise, which still reads better than the
 * raw "on" / "off" everywhere.
 */
private fun friendlyBinaryWord(state: EntityState): String {
    val on = state.isOn
    return when (state.deviceClass) {
        "door", "garage_door", "window", "opening" -> if (on) "OPEN" else "CLOSED"
        "motion", "occupancy", "presence" -> if (on) "MOTION" else "CLEAR"
        // Moisture sensors trip on any wetness, not just leaks — a damp basement
        // floor or condensation on a window can also trigger them. WET reads as
        // descriptive rather than alarming, which matches the actual signal.
        "moisture" -> if (on) "WET" else "DRY"
        "smoke" -> if (on) "SMOKE" else "CLEAR"
        "gas", "carbon_monoxide" -> if (on) "DETECTED" else "CLEAR"
        "lock" -> if (on) "UNLOCKED" else "LOCKED"
        "battery" -> if (on) "LOW" else "OK"
        "battery_charging" -> if (on) "CHARGING" else "IDLE"
        "power", "plug" -> if (on) "POWER" else "OFF"
        "connectivity" -> if (on) "ONLINE" else "OFFLINE"
        // Temperature-class binary sensors trip when their threshold is crossed.
        // HA's contract: `cold` = too cold, `heat` = too hot. CRITICAL is the
        // shorter all-caps word that scans on the R1's narrow card.
        "cold" -> if (on) "COLD" else "OK"
        "heat" -> if (on) "HOT" else "OK"
        // Photoresistor / light-detected sensors.
        "light" -> if (on) "LIGHT" else "DARK"
        // Generic safety / problem / tamper alarms. PROBLEM is shorter than
        // 'DETECTED' and scans more clearly on alarm-class cards.
        "safety", "problem", "tamper" -> if (on) "PROBLEM" else "OK"
        // Vibration / sound trip-detectors.
        "vibration" -> if (on) "VIBRATION" else "STILL"
        "sound" -> if (on) "SOUND" else "QUIET"
        // Motor / appliance running indicators (washing machine state, etc.).
        "running" -> if (on) "RUNNING" else "IDLE"
        // 'Update available' binary sensors (HA's `update` integration).
        "update" -> if (on) "AVAILABLE" else "UP TO DATE"
        else -> if (on) "ON" else "OFF"
    }
}

