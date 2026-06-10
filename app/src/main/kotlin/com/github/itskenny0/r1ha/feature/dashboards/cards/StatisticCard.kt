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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.StatisticsBucket
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.lovelace.StatisticPeriodConfig
import com.github.itskenny0.r1ha.core.theme.LocalHaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.StatisticPeriodSpec
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.resolveStatisticWindow
import kotlinx.coroutines.delay
import java.time.Instant
import java.util.Locale

/**
 * Renderer for HA's `statistic` card. Resolves the configured period
 * (calendar / fixed_period / rolling_window) into a concrete window via the
 * shared period engine, fetches long-term-statistics buckets for that window,
 * and shows the requested aggregate (mean / min / max / sum / change / state)
 * as a single big readout. Tapping the card opens more-info (the dispatcher
 * default). Auto-refreshes on a sensible cadence.
 *
 * An entity the recorder doesn't track, or a transport failure, falls back to a
 * quiet placeholder rather than an error.
 */
@Composable
fun StatisticCard(
    card: LovelaceCard.Statistic,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = LocalHaRepository.current
    val eid = remember(card.entityId) { safeEntityId(card.entityId) }
    val name = resolveName(card.name, eid?.let { stateMap[it] }, card.entityId)
    val unit = card.unit ?: eid?.let { stateMap[it]?.unit }
    var value by remember(card.entityId, card.statType, card.periodSpec) {
        mutableStateOf<Double?>(null)
    }
    var loaded by remember(card.entityId, card.statType, card.periodSpec) { mutableStateOf(false) }

    if (repo != null) {
        LaunchedEffect(card.entityId, card.statType, card.periodSpec) {
            while (true) {
                val window = resolveStatisticWindow(card.periodSpec.toSpec(), Instant.now())
                repo.getStatisticsDuringPeriod(
                    statisticIds = listOf(card.entityId),
                    start = window.start,
                    end = window.end,
                    period = window.bucket,
                ).onSuccess { byId ->
                    value = reduceStatistic(byId[card.entityId].orEmpty(), card.statType)
                }
                loaded = true
                // HA refetches statistics on a coarse cadence; 5 minutes keeps
                // a dashboard fresh without hammering the recorder.
                delay(300_000L)
            }
        }
    }

    CardSurface(
        modifier = modifier.then(
            if (eid != null) {
                Modifier.r1Pressable(onClick = {
                    onAction(LovelaceAction.Builtin("more-info", card.entityId))
                })
            } else Modifier,
        ),
    ) {
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
                !loaded -> "LOADING..."
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

/** Bridge the config-layer period to the engine's spec type. */
internal fun StatisticPeriodConfig.toSpec(): StatisticPeriodSpec = when (this) {
    is StatisticPeriodConfig.Calendar -> StatisticPeriodSpec.Calendar(period, offset)
    is StatisticPeriodConfig.Fixed -> StatisticPeriodSpec.Fixed(startMillis, endMillis)
    is StatisticPeriodConfig.Rolling -> StatisticPeriodSpec.Rolling(durationMillis, offsetMillis)
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
