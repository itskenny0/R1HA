package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.StatisticsBucket
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.LocalHaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Renderer for HA's `statistic` card. Fetches long-term-statistics buckets
 * for the configured entity over the card's period and shows the requested
 * aggregate (mean / min / max / sum / change / state) as a single big
 * readout. An entity the recorder doesn't track, or a transport failure,
 * falls back to a quiet placeholder rather than an error.
 */
@Composable
fun StatisticCard(
    card: LovelaceCard.Statistic,
    stateMap: EntityStates,
    modifier: Modifier = Modifier,
) {
    val repo = LocalHaRepository.current
    val eid = remember(card.entityId) { safeEntityId(card.entityId) }
    val name = resolveName(card.name, eid?.let { stateMap[it] }, card.entityId)
    val unit = eid?.let { stateMap[it]?.unit }
    var value by remember(card.entityId, card.statType, card.period) {
        mutableStateOf<Double?>(null)
    }
    var loaded by remember(card.entityId, card.statType, card.period) { mutableStateOf(false) }

    if (repo != null) {
        LaunchedEffect(card.entityId, card.statType, card.period) {
            val end = Instant.now()
            val start = end.minus(lookbackFor(card.period))
            repo.getStatisticsDuringPeriod(
                statisticIds = listOf(card.entityId),
                start = start,
                end = end,
                period = bucketPeriodFor(card.period),
            ).onSuccess { byId ->
                value = reduceStatistic(byId[card.entityId].orEmpty(), card.statType)
            }
            loaded = true
        }
    }

    CardSurface(modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = R1.bodyEmph,
                    color = R1.Ink,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                StateChip(text = card.statType, accent = R1.InkSoft)
            }
            Spacer(Modifier.height(6.dp))
            val v = value
            val display = when {
                v != null -> formatStatistic(v) + (unit?.let { " $it" } ?: "")
                repo == null -> "STATISTICS UNAVAILABLE"
                !loaded -> "LOADING…"
                else -> "NO STATISTICS"
            }
            Text(
                text = display,
                style = if (v != null) R1.numeralXl else R1.labelMicro,
                color = if (v != null) R1.Ink else R1.InkMuted,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Reduce a bucket list to one number for the requested [statType]. mean /
 * min / max aggregate across buckets; sum / state / change take the final
 * bucket's running value (HA stores those cumulatively). Returns null when
 * no bucket carries the requested aggregate, so the renderer can fall back
 * to a placeholder. Pure; unit-tested without Compose.
 */
internal fun reduceStatistic(buckets: List<StatisticsBucket>, statType: String): Double? {
    if (buckets.isEmpty()) return null
    return when (statType.lowercase(Locale.US)) {
        "mean" -> buckets.mapNotNull { it.mean }.takeIf { it.isNotEmpty() }?.average()
        "min" -> buckets.mapNotNull { it.min }.minOrNull()
        "max" -> buckets.mapNotNull { it.max }.maxOrNull()
        "sum" -> buckets.lastOrNull { it.sum != null }?.sum
        "state" -> buckets.lastOrNull { it.state != null }?.state
        "change" -> buckets.mapNotNull { it.change }.takeIf { it.isNotEmpty() }?.sum()
        else -> buckets.mapNotNull { it.mean ?: it.state }.lastOrNull()
    }
}

/** Trim trailing zeros so 21.0 reads "21" but 21.34 stays "21.34". */
internal fun formatStatistic(value: Double): String {
    val rounded = (value * 100.0).let { Math.round(it) } / 100.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        String.format(Locale.US, "%.2f", rounded).trimEnd('0').trimEnd('.')
    }
}

/** Lookback window for the card's coarse period label. */
internal fun lookbackFor(period: String): Duration = when (period.lowercase(Locale.US)) {
    "hour", "5minute" -> Duration.ofDays(1)
    "day" -> Duration.ofDays(7)
    "week" -> Duration.ofDays(30)
    "month" -> Duration.ofDays(365)
    "year" -> Duration.ofDays(365 * 3L)
    else -> Duration.ofDays(7)
}

/** Recorder bucket resolution to request for the card's period label. */
internal fun bucketPeriodFor(period: String): String = when (period.lowercase(Locale.US)) {
    "hour", "5minute" -> "hour"
    "week" -> "day"
    "month" -> "week"
    "year" -> "month"
    else -> "day"
}
