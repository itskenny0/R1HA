package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.LogbookEntry
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.LocalHaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.AutoRefresh
import java.time.Duration

/** Cap rendered rows so a busy logbook doesn't blow up the card height. */
private const val MAX_ROWS = 20

/** Live-update cadence: refetch the logbook while the card is displayed. */
private const val LOGBOOK_REFRESH_MS = 30_000L

/**
 * Renderer for HA's `logbook` card. Fetches HA's logbook and shows the recent
 * entries scoped to the card's configured entities (and any entities resolved
 * from a `target:` group), newest first, refetching periodically so the feed
 * stays live while displayed.
 *
 * Target resolution (gap I2/8): `target.entity_id` already merges into
 * [LovelaceCard.Logbook.entities] at parse time. `area_id` / `floor_id` /
 * `device_id` are resolved client-side via the entity + area registries (see
 * [resolveLogbookTarget]). `label_id` is not resolvable (R1HA's entity-registry
 * projection carries no labels); an unresolved label target surfaces a small
 * note rather than being silently ignored.
 *
 * A `state_filter:` narrows entries to the listed states; a recorder/logbook
 * integration that isn't loaded surfaces a dedicated warning (see
 * [isLogbookNotLoaded]).
 */
@Composable
fun LogbookCard(
    card: LovelaceCard.Logbook,
    modifier: Modifier = Modifier,
) {
    val repo = LocalHaRepository.current
    var entries by remember(card.entities, card.hoursToShow, card.stateFilter) {
        mutableStateOf<List<LogbookEntry>?>(null)
    }
    var notLoaded by remember(card.entities, card.hoursToShow) { mutableStateOf(false) }
    // Entity ids resolved from the target groups, merged with the explicit list.
    var resolvedTargetIds by remember(card.target) { mutableStateOf<Set<String>>(emptySet()) }
    // A counter bumped by AutoRefresh to drive periodic refetches.
    var tick by remember { mutableIntStateOf(0) }

    if (repo != null && !card.target.isEmpty) {
        LaunchedEffect(card.target) {
            val registry = repo.listEntityRegistry().getOrNull().orEmpty()
            val areas = repo.listAreas().getOrNull().orEmpty()
            val devices = repo.listDevices().getOrNull().orEmpty()
            val deviceAreas = devices.mapNotNull { d -> d.areaId?.let { d.id to it } }.toMap()
            resolvedTargetIds = resolveLogbookTarget(card.target, registry, areas, deviceAreas)
        }
    }

    if (repo != null) {
        AutoRefresh(everyMillis = LOGBOOK_REFRESH_MS) { tick++ }
        LaunchedEffect(card.entities, card.hoursToShow, card.stateFilter, resolvedTargetIds, tick) {
            val scopeIds = LinkedHashSet(card.entities).apply { addAll(resolvedTargetIds) }
            repo.fetchLogbook(hours = card.hoursToShow)
                .onSuccess { all ->
                    notLoaded = false
                    entries = filterLogbookEntries(all, scopeIds, card.stateFilter)
                }
                .onFailure { err ->
                    notLoaded = isLogbookNotLoaded(err)
                    entries = emptyList()
                }
        }
    }

    CardSurface(modifier = modifier, title = card.title?.takeUnless { it.isBlank() }) {
        if (hasUnresolvableTarget(card.target)) {
            EmptyRow(text = "Label targets can't be resolved on this device")
        }
        val rows = entries
        when {
            repo == null -> EmptyRow(text = "Logbook unavailable")
            notLoaded -> EmptyRow(text = "Logbook integration is not loaded")
            rows == null -> EmptyRow(text = "Loading...")
            rows.isEmpty() -> EmptyRow(text = "No recent activity")
            else -> rows.take(MAX_ROWS).forEachIndexed { idx, entry ->
                if (idx > 0) LogbookDivider()
                LogbookRow(entry)
            }
        }
    }
}

@Composable
private fun LogbookRow(entry: LogbookEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = R1.bodyEmph,
                color = R1.Ink,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (entry.message.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = entry.message,
                    style = R1.body,
                    color = R1.InkMuted,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = relativeAgo(entry.timestamp),
            style = R1.labelMicro,
            color = R1.InkMuted,
        )
    }
}

@Composable
private fun LogbookDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(R1.Hairline),
    )
}

/**
 * Keep only entries whose entity falls in [entityIds] (or every entry when the
 * list is empty), newest first. Retained for callers/tests that filter without a
 * state filter; delegates to [filterLogbookEntries].
 */
internal fun filterLogbook(
    entries: List<LogbookEntry>,
    entityIds: List<String>,
): List<LogbookEntry> = filterLogbookEntries(entries, entityIds.toSet(), emptyList())

private fun relativeAgo(t: java.time.Instant): String {
    val secs = Duration.between(t, java.time.Instant.now()).seconds.coerceAtLeast(0)
    return when {
        secs < 60 -> "${secs}s"
        secs < 3600 -> "${secs / 60}m"
        secs < 86_400 -> "${secs / 3600}h"
        else -> "${secs / 86_400}d"
    }
}
