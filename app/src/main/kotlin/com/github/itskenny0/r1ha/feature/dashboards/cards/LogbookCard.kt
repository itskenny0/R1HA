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
import java.time.Duration

/** Cap rendered rows so a busy logbook doesn't blow up the card height. */
private const val MAX_ROWS = 20

/**
 * Renderer for HA's `logbook` card. Fetches HA's logbook and shows the
 * recent entries scoped to the card's configured entities (or every entry
 * when none are listed), newest first. A transport failure or an empty
 * window falls back to a quiet placeholder.
 */
@Composable
fun LogbookCard(
    card: LovelaceCard.Logbook,
    modifier: Modifier = Modifier,
) {
    val repo = LocalHaRepository.current
    var entries by remember(card.entities, card.hoursToShow) {
        mutableStateOf<List<LogbookEntry>?>(null)
    }
    if (repo != null) {
        LaunchedEffect(card.entities, card.hoursToShow) {
            repo.fetchLogbook(hours = card.hoursToShow)
                .onSuccess { all -> entries = filterLogbook(all, card.entities) }
                .onFailure { entries = emptyList() }
        }
    }

    CardSurface(modifier = modifier, title = card.title?.takeUnless { it.isBlank() }) {
        val rows = entries
        when {
            repo == null -> EmptyRow(text = "Logbook unavailable")
            rows == null -> EmptyRow(text = "Loading…")
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
 * Keep only entries whose entity falls in [entityIds] (or every entry when
 * the list is empty), newest first. Pure; unit-tested without Compose.
 */
internal fun filterLogbook(
    entries: List<LogbookEntry>,
    entityIds: List<String>,
): List<LogbookEntry> {
    val filtered = if (entityIds.isEmpty()) {
        entries
    } else {
        val wanted = entityIds.toHashSet()
        entries.filter { it.entityId?.value in wanted }
    }
    return filtered.sortedByDescending { it.timestamp }
}

private fun relativeAgo(t: java.time.Instant): String {
    val secs = Duration.between(t, java.time.Instant.now()).seconds.coerceAtLeast(0)
    return when {
        secs < 60 -> "${secs}s"
        secs < 3600 -> "${secs / 60}m"
        secs < 86_400 -> "${secs / 3600}h"
        else -> "${secs / 86_400}d"
    }
}
