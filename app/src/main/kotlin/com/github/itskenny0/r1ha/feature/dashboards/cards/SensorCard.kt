package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.HistoryPoint
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.LocalHaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.ChartSample
import com.github.itskenny0.r1ha.ui.components.Sparkline
import com.github.itskenny0.r1ha.ui.components.SparklinePlaceholder
import com.github.itskenny0.r1ha.ui.components.SparklineSeries
import com.github.itskenny0.r1ha.ui.components.downSampleLineData
import com.github.itskenny0.r1ha.ui.components.formatWithPrecision
import com.github.itskenny0.r1ha.ui.components.maxDetailsFor
import com.github.itskenny0.r1ha.ui.components.purgeToWindow
import com.github.itskenny0.r1ha.ui.components.redrawIntervalMillis
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * Renderer for HA's `sensor` card. A single numeric (or text) sensor's
 * name + current value; when the config sets `graph: line` the renderer
 * fetches the entity's recent history and overlays a compact sparkline
 * through the shared [Sparkline] engine (downsampled, fixed-window anchored,
 * area-filled). The card paints instantly from a placeholder and slides the
 * window live while displayed.
 *
 * History is fetched off [LocalHaRepository]; when that's unset (the
 * dashboards host didn't provide one) the card degrades gracefully to the
 * name + value readout with no graph.
 */
@Composable
fun SensorCard(
    card: LovelaceCard.Sensor,
    stateMap: EntityStates,
    modifier: Modifier = Modifier,
) {
    val eid = safeEntityId(card.entityId)
    val state = eid?.let { stateMap[it] }
    val name = resolveName(card.name, state, card.entityId)
    val accent = stateAccentFor(card.entityId, state)
    val unit = card.unit ?: state?.unit
    // R1HA colours the sensor value with its device-class accent by default
    // (a deliberate readability choice). HA's `state_color` is the opt-in for
    // exactly that tint, so an explicit `state_color: false` drops back to the
    // neutral ink HA uses by default while the accent still drives the icon and
    // sparkline.
    val valueColor = if (card.raw.containsKey("state_color") && !card.stateColor) R1.Ink else accent
    val valueText = state?.let { s ->
        // An `attribute:` override displays that attribute in place of the state.
        val raw = card.attribute
            ?.let { attr -> s.attributesJson?.get(attr)?.let { jsonScalar(it) } }
            ?: s.rawState.orEmpty()
        val formatted = formatWithPrecision(raw, s.displayPrecision)
        if (unit != null && raw.toDoubleOrNull() != null) "$formatted $unit" else compactStateText(s)
    }?.takeUnless { it.isBlank() } ?: "-"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, R1.Hairline, R1.ShapeM)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = cardEntityIcon(card.entityId, state, card.icon),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = name,
                style = R1.bodyEmph,
                color = R1.Ink,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = valueText,
                style = R1.numeralM,
                color = valueColor,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }

        if (card.graph && eid != null) {
            Spacer(Modifier.height(10.dp))
            val repo = LocalHaRepository.current
            var raw by remember(card.entityId, card.hoursToShow) {
                mutableStateOf<List<HistoryPoint>>(emptyList())
            }
            var loaded by remember(card.entityId, card.hoursToShow) { mutableStateOf(false) }
            var nowMillis by remember(card.entityId, card.hoursToShow) {
                mutableStateOf(System.currentTimeMillis())
            }
            if (repo != null) {
                // Periodic refetch + redraw tick slides the window live; cadence
                // is per-minute for short windows, hourly past a day.
                LaunchedEffect(card.entityId, card.hoursToShow) {
                    while (true) {
                        repo.fetchHistory(eid, hours = card.hoursToShow)
                            .onSuccess { raw = it }
                        loaded = true
                        nowMillis = System.currentTimeMillis()
                        delay(redrawIntervalMillis(card.hoursToShow.toDouble()))
                    }
                }
            }
            val windowEnd = nowMillis
            val windowStart = windowEnd - card.hoursToShow.toLong() * 3_600_000L
            val samples = remember(raw, windowStart, card.detail) {
                val all = raw.mapNotNull { p -> p.numeric?.let { ChartSample(p.timestamp.toEpochMilli(), it) } }
                val purged = purgeToWindow(all, windowStart)
                val detail = card.detail ?: 1
                downSampleLineData(
                    purged,
                    maxDetails = maxDetailsFor(card.hoursToShow.toDouble(), detail),
                    minX = windowStart,
                    maxX = windowEnd,
                    useMean = detail != 2,
                )
            }
            when {
                samples.size >= 2 -> Sparkline(
                    series = listOf(SparklineSeries(samples = samples, color = accent)),
                    height = 64.dp,
                    windowStartMillis = windowStart,
                    windowEndMillis = windowEnd,
                    limitMin = card.limitMin,
                    limitMax = card.limitMax,
                )
                else -> SparklinePlaceholder(
                    height = 64.dp,
                    errorText = when {
                        repo == null -> "HISTORY UNAVAILABLE"
                        loaded -> "NOT ENOUGH HISTORY YET"
                        else -> null
                    },
                )
            }
        }
    }
}

/** Render a JSON scalar attribute value as a plain string (its content,
 *  unquoted for both string and numeric primitives). Null for object/array
 *  values, which an `attribute:` display can't render meaningfully. */
internal fun jsonScalar(el: kotlinx.serialization.json.JsonElement): String? =
    (el as? kotlinx.serialization.json.JsonPrimitive)?.content
