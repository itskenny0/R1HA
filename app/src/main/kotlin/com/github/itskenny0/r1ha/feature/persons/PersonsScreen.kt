package com.github.itskenny0.r1ha.feature.persons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Section
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * "Who's home" surface — combines `person.*` (high-level humans the
 * user has configured in HA) with `device_tracker.*` (per-phone /
 * per-router pings that power the person entities) into one screen.
 *
 * Two sub-headings: PEOPLE and DEVICES. People come first because
 * they're the higher-fidelity view; devices are the raw plumbing
 * underneath, useful for "why does HA think X is away".
 */
@Composable
fun PersonsScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
    /** Drill into the entity's location-state history. Wired from
     *  AppNavGraph; defaults to a no-op so previews / tests don't
     *  have to thread the callback through. */
    onOpenHistory: (entityId: String) -> Unit = {},
) {
    val vm: PersonsViewModel = viewModel(factory = PersonsViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    val appSettings by settings.settings.collectAsState(
        initial = com.github.itskenny0.r1ha.core.prefs.AppSettings(),
    )
    val refreshSec = appSettings.integrations.personsRefreshSec
    if (refreshSec > 0) {
        com.github.itskenny0.r1ha.ui.components.AutoRefresh(refreshSec * 1000L) { vm.refresh() }
    } else {
        androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "WHO'S HOME", onBack = onBack)
        com.github.itskenny0.r1ha.ui.layout.AdaptiveContent(modifier = Modifier.weight(1f)) {
        when {
            ui.loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(22.dp)
                        .semantics { contentDescription = "Loading who's home" },
                    strokeWidth = 2.dp,
                    color = R1.AccentWarm,
                )
            }
            ui.error != null && ui.people.isEmpty() && ui.devices.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(R1.space.xl),
                contentAlignment = Alignment.Center,
            ) {
                // Distinct from "no person integrations" — the request
                // itself failed (auth, network, server down).
                Text(
                    text = "Persons load failed: ${ui.error}",
                    style = R1.body,
                    color = R1.StatusRed,
                )
            }
            ui.people.isEmpty() && ui.devices.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(R1.space.xl),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No people or device trackers in HA. Add a person integration to see them here.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
            }
            else -> androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = ui.loading,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = R1.space.m, vertical = R1.space.s,
                    ),
                    verticalArrangement = Arrangement.spacedBy(R1.space.xs),
                ) {
                    // Each row is its own `items` entry keyed by entity_id so the
                    // list lazily composes/recycles rows and a single person's
                    // state change recomposes only that one row, not the whole
                    // section. The section header is a plain header item above
                    // its rows; ordering and visible content are unchanged.
                    if (ui.people.isNotEmpty()) {
                        item(key = "__sec_people", contentType = "header") {
                            // heading() promotes the section title to a TalkBack
                            // navigation landmark so users can jump between the
                            // People and Device trackers groups.
                            Box(modifier = Modifier.semantics { heading() }) {
                                R1Section(
                                    title = "People",
                                    count = ui.people.size,
                                    topSpace = R1.space.s,
                                    content = {},
                                )
                            }
                        }
                        items(
                            items = ui.people,
                            key = { "person:${it.entityId}" },
                            contentType = { "personRow" },
                        ) { e ->
                            PersonRow(e, onTap = { onOpenHistory(e.entityId) })
                        }
                    }
                    if (ui.devices.isNotEmpty()) {
                        item(key = "__sec_devices", contentType = "header") {
                            Box(modifier = Modifier.semantics { heading() }) {
                                R1Section(
                                    title = "Device trackers",
                                    count = ui.devices.size,
                                    content = {},
                                )
                            }
                        }
                        items(
                            items = ui.devices,
                            key = { "device:${it.entityId}" },
                            contentType = { "personRow" },
                        ) { e ->
                            PersonRow(e, onTap = { onOpenHistory(e.entityId) })
                        }
                    }
                }
            }
        }
        } // AdaptiveContent
    }
}

/**
 * Round avatar for a person/device row. Renders the HA entity_picture via the
 * shared AsyncBitmap loader when one is set, otherwise a tinted initials chip
 * derived from the display name. The initials also stand in while a picture
 * has yet to decode or fails to load, so the slot is never an empty circle.
 */
@Composable
private fun PersonAvatar(
    entry: PersonsViewModel.Entry,
    presenceColor: androidx.compose.ui.graphics.Color,
) {
    val size = 36.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(presenceColor.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        // Initials sit underneath as the always-present fallback.
        Text(
            text = initialsFor(entry.name),
            style = R1.labelMicro,
            color = presenceColor,
        )
        if (entry.entityPicture != null) {
            com.github.itskenny0.r1ha.ui.components.AsyncBitmap(
                url = entry.entityPicture,
                serverUrl = com.github.itskenny0.r1ha.core.theme.LocalHaServerUrl.current,
                bearerToken = com.github.itskenny0.r1ha.core.theme.LocalHaBearerToken.current,
                modifier = Modifier
                    .size(size)
                    .clip(androidx.compose.foundation.shape.CircleShape),
                // The parent row merges name + presence into a single spoken
                // label via clearAndSetSemantics, so this per-image description
                // is cleared and never announced on its own.
                contentDescription = "${entry.name} avatar",
            )
        }
    }
}

/** Presence chip text paired with its R1 palette colour for a row. */
private data class RowPresence(
    val label: String,
    val color: androidx.compose.ui.graphics.Color,
)

/** Map the pure [presenceLabel] derivation onto the R1 colour palette. Kept
 *  here (not in the pure helper) so PersonPresence.kt stays Android-free. */
private fun rowPresence(state: String): RowPresence {
    val derived = presenceLabel(state)
    val color = when (derived.kind) {
        PresenceKind.HOME -> R1.AccentGreen
        PresenceKind.AWAY -> R1.StatusAmber
        PresenceKind.UNKNOWN -> R1.StatusRed
        PresenceKind.ZONE -> R1.AccentCool
    }
    return RowPresence(derived.label, color)
}

@Composable
private fun PersonRow(entry: PersonsViewModel.Entry, onTap: () -> Unit = {}) {
    val presence = rowPresence(entry.state)
    // Merge the avatar, presence chip, name, entity id, and metadata chips into
    // one spoken phrase so TalkBack announces the row as a unit ("Jane Doe,
    // Home, 5m ago, battery 82 percent") instead of reading each fragment
    // separately. mergeDescendants keeps the row's tap action while the
    // explicit contentDescription replaces the child text for announcement.
    val rel = com.github.itskenny0.r1ha.ui.components.rememberRelativeTime(entry.since)
    val rowDescription = rowContentDescription(
        name = entry.name,
        state = entry.state,
        relativeTime = rel,
        source = entry.source,
        batteryLevel = entry.batteryLevel,
        gpsAccuracy = entry.gpsAccuracy,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onTap)
            .heightIn(min = R1.MinTarget)
            .semantics(mergeDescendants = true) { contentDescription = rowDescription }
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar — the person's entity_picture when HA provides one, with a
        // tinted initials chip as the fallback (and for device_trackers, which
        // rarely carry a picture). The presence colour tints the fallback so a
        // pictureless row still reads home/away at a glance.
        PersonAvatar(entry = entry, presenceColor = presence.color)
        Spacer(Modifier.width(R1.space.m))
        // Zone-presence chip — HOME (green), AWAY (amber), unknown (red), or
        // the named HA zone (cool accent). Derivation lives in the pure
        // presenceLabel() helper so it's unit-tested and Compose-free.
        Text(text = presence.label, style = R1.labelMicro, color = presence.color)
        Spacer(Modifier.width(R1.space.m))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.name,
                    style = R1.bodyEmph,
                    color = R1.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Relative timestamp on the right of the name — 'since
                // 2h' so the user can see how long the person/device has
                // been in their current state. Same ticker as the rest
                // of the app, so live-updates without us touching it.
                Spacer(Modifier.width(R1.space.s))
                com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel(
                    at = entry.since,
                    color = R1.InkMuted,
                    style = R1.labelMicro,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.entityId,
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (entry.source != null) {
                    Spacer(Modifier.width(R1.space.s))
                    Text(
                        text = entry.source.uppercase(),
                        style = R1.labelMicro,
                        color = R1.AccentNeutral,
                    )
                }
                if (entry.batteryLevel != null) {
                    Spacer(Modifier.width(R1.space.s))
                    // Colour the battery digit by threshold so a low
                    // phone battery on a person tracker stands out at
                    // a glance — same red/amber/muted ramp the other
                    // battery surfaces use.
                    val batteryColor = when {
                        entry.batteryLevel < 10 -> R1.StatusRed
                        entry.batteryLevel < 25 -> R1.StatusAmber
                        else -> R1.AccentNeutral
                    }
                    Text(
                        text = "${entry.batteryLevel}%",
                        style = R1.labelMicro,
                        color = batteryColor,
                    )
                }
                if (entry.gpsAccuracy != null) {
                    Spacer(Modifier.width(R1.space.s))
                    Text(
                        text = "±${entry.gpsAccuracy}m",
                        style = R1.labelMicro,
                        color = R1.AccentNeutral,
                    )
                }
            }
        }
    }
}
