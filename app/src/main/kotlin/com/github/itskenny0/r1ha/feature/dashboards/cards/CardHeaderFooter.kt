package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.HistoryPoint
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceHeaderFooter
import com.github.itskenny0.r1ha.core.theme.LocalHaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.ChartSample
import com.github.itskenny0.r1ha.ui.components.HuiImage
import com.github.itskenny0.r1ha.ui.components.Sparkline
import com.github.itskenny0.r1ha.ui.components.SparklinePlaceholder
import com.github.itskenny0.r1ha.ui.components.SparklineSeries
import com.github.itskenny0.r1ha.ui.components.downSampleLineData
import com.github.itskenny0.r1ha.ui.components.maxDetailsFor
import com.github.itskenny0.r1ha.ui.components.purgeToWindow
import com.github.itskenny0.r1ha.ui.components.redrawIntervalMillis
import kotlinx.coroutines.delay

/**
 * Renderer for HA's card-level header / footer slots (one small subsystem reused
 * by the entities + entity cards). Dispatches the parsed
 * [com.github.itskenny0.r1ha.core.lovelace.LovelaceHeaderFooter] to the matching
 * composable, reusing the existing graph layer (Sparkline/ChartEngine), the image
 * engine (HuiImage), and the action dispatcher.
 *
 * Mirrors HA's three header-footer types:
 *  - buttons: a row of icon buttons, each firing its entity's tap action;
 *  - graph: a one-entity history sparkline;
 *  - picture: a tappable image.
 *
 * An [LovelaceHeaderFooter.Unsupported] slot renders nothing.
 */
@Composable
fun CardHeaderFooterSlot(
    slot: LovelaceHeaderFooter,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (slot) {
        is LovelaceHeaderFooter.Buttons -> ButtonsHeaderFooter(slot, stateMap, onAction, modifier)
        is LovelaceHeaderFooter.Graph -> GraphHeaderFooter(slot, stateMap, onAction, modifier)
        is LovelaceHeaderFooter.Picture -> PictureHeaderFooter(slot, onAction, modifier)
        is LovelaceHeaderFooter.Unsupported -> Unit
    }
}

/**
 * A row of icon buttons. Each entry's tap defaults to `toggle` (scene entries to
 * `scene.turn_on`), hold to `more-info`, matching HA's hui-buttons-header-footer
 * setConfig. Resolution rides the shared action layer so the dispatcher handles
 * the toggle/more-info fallbacks and confirmation gates.
 */
@Composable
private fun ButtonsHeaderFooter(
    slot: LovelaceHeaderFooter.Buttons,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (slot.entries.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        slot.entries.forEach { entry ->
            val state = stateMap.byRaw(entry.entityId)
            val accent = stateAccentFor(entry.entityId, state)
            val icon = cardEntityIcon(entry.entityId, state, entry.icon)
            // HA default: tap toggles the entity (scene -> scene.turn_on), hold
            // opens more-info. Defer to the shared dispatcher for both.
            val tap = entry.tapAction ?: headerFooterDefaultTap(entry.entityId)
            val hold = entry.holdAction
            val actions = resolveCardActions(
                tapAction = tap,
                holdAction = hold,
                doubleTapAction = null,
                cardEntityId = entry.entityId,
            )
            Column(
                modifier = Modifier
                    .clip(R1.ShapeM)
                    .r1CardActions(actions = actions, onAction = onAction, contentDescription = entry.name)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CardIconDisc(icon = icon, accent = accent, discSize = 34.dp, iconSize = 18.dp, showBorder = false)
                if (entry.name != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = entry.name,
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** HA's scene entries tap to `scene.turn_on`; everything else toggles. Matches
 *  hui-buttons-header-footer's per-domain default. */
private fun headerFooterDefaultTap(entityId: String): LovelaceAction =
    if (entityId.substringBefore('.', "") == "scene") {
        LovelaceAction.CallService("scene.turn_on", entityId, null)
    } else {
        LovelaceAction.Builtin("toggle", entityId)
    }

/**
 * A compact history sparkline of one entity, drawn through the shared
 * Sparkline/ChartEngine (the same path the sensor card uses). Tapping opens the
 * entity's more-info. History is fetched off [LocalHaRepository]; absent it, the
 * slot shows the "history unavailable" placeholder.
 */
@Composable
private fun GraphHeaderFooter(
    slot: LovelaceHeaderFooter.Graph,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val eid = safeEntityId(slot.entityId)
    val state = eid?.let { stateMap[it] }
    val accent = stateAccentFor(slot.entityId, state)
    val repo = LocalHaRepository.current
    var raw by remember(slot.entityId, slot.hoursToShow) { mutableStateOf<List<HistoryPoint>>(emptyList()) }
    var loaded by remember(slot.entityId, slot.hoursToShow) { mutableStateOf(false) }
    var nowMillis by remember(slot.entityId, slot.hoursToShow) { mutableStateOf(System.currentTimeMillis()) }
    if (repo != null && eid != null) {
        LaunchedEffect(slot.entityId, slot.hoursToShow) {
            while (true) {
                repo.fetchHistory(eid, hours = slot.hoursToShow).onSuccess { raw = it }
                loaded = true
                nowMillis = System.currentTimeMillis()
                delay(redrawIntervalMillis(slot.hoursToShow.toDouble()))
            }
        }
    }
    val windowEnd = nowMillis
    val windowStart = windowEnd - slot.hoursToShow.toLong() * 3_600_000L
    val samples = remember(raw, windowStart, slot.detail) {
        val all = raw.mapNotNull { p -> p.numeric?.let { ChartSample(p.timestamp.toEpochMilli(), it) } }
        val purged = purgeToWindow(all, windowStart)
        downSampleLineData(
            purged,
            maxDetails = maxDetailsFor(slot.hoursToShow.toDouble(), slot.detail),
            minX = windowStart,
            maxX = windowEnd,
            useMean = slot.detail != 2,
        )
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .r1CardActions(
                actions = resolveCardActions(null, null, null, slot.entityId),
                onAction = onAction,
            ),
    ) {
        when {
            samples.size >= 2 -> Sparkline(
                series = listOf(SparklineSeries(samples = samples, color = accent)),
                height = 56.dp,
                windowStartMillis = windowStart,
                windowEndMillis = windowEnd,
                limitMin = slot.limitMin,
                limitMax = slot.limitMax,
            )
            else -> SparklinePlaceholder(
                height = 56.dp,
                errorText = when {
                    repo == null -> "HISTORY UNAVAILABLE"
                    loaded -> "NO STATE HISTORY FOUND"
                    else -> null
                },
            )
        }
    }
}

/**
 * A tappable image header/footer. Renders through HuiImage and fires the
 * configured tap / hold / double-tap actions through the dispatcher; an absent
 * tap leaves the image inert (HA's stub default is `action: none`).
 */
@Composable
private fun PictureHeaderFooter(
    slot: LovelaceHeaderFooter.Picture,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A picture header/footer carries no entity, so tap fallback is null: the
    // image is inert unless an action is configured.
    val actions = resolveCardActions(
        tapAction = slot.tapAction,
        holdAction = slot.holdAction,
        doubleTapAction = slot.doubleTapAction,
        cardEntityId = null,
    )
    HuiImage(
        imageUrl = slot.image,
        contentDescription = slot.altText,
        modifier = modifier
            .fillMaxWidth()
            .r1CardActions(actions = actions, onAction = onAction, contentDescription = slot.altText),
    )
}
