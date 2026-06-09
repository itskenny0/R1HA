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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.ui.components.R1Section
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.SkeletonList
import com.github.itskenny0.r1ha.ui.components.WheelScrollForGrid
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * "Who's home" surface: combines `person.*` (high-level humans the
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
    val gridState = rememberLazyGridState()
    WheelScrollForGrid(wheelInput = wheelInput, gridState = gridState, settings = settings)
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
        // Size-aware dimensions: gutter, content-width cap, and the person-grid
        // column count all step up with the window tier. One column on R1 /
        // compact (the stacked list, unchanged); two on medium / expanded; three
        // on extra-large, so a wide tablet fills its width with a person grid
        // instead of one stretched column of rows.
        val dimens = rememberResponsiveDimens()
        com.github.itskenny0.r1ha.ui.layout.AdaptiveContent(modifier = Modifier.weight(1f)) {
        when {
            ui.loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "Loading who's home" },
            ) {
                SkeletonList()
            }
            ui.error != null && ui.people.isEmpty() && ui.devices.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(R1.space.xl),
                contentAlignment = Alignment.Center,
            ) {
                // Distinct from "no person integrations": the request
                // itself failed (auth, network, server down).
                Text(
                    text = "Persons load failed: ${ui.error}",
                    style = responsiveType(R1.body),
                    color = R1.StatusRed,
                )
            }
            ui.people.isEmpty() && ui.devices.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(R1.space.xl),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No people or device trackers in HA. Add a person integration to see them here.",
                    style = responsiveType(R1.body),
                    color = R1.InkMuted,
                )
            }
            else -> androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                // Pull-to-refresh spinner only; the populated list stays put on
                // auto-refresh ticks (initialLoading drives the full-screen
                // spinner branch above).
                isRefreshing = ui.refreshing,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                // Centre + width-cap the grid on big tiers so a person grid on a
                // 13in panel reads as a tidy centred block rather than rows
                // stretched edge-to-edge; mini / compact fill (maxContentWidth is
                // Unspecified there).
                val capped = if (dimens.capsContentWidth) {
                    Modifier
                        .fillMaxSize()
                        .widthIn(max = dimens.maxContentWidth)
                        .align(Alignment.TopCenter)
                } else {
                    Modifier.fillMaxSize()
                }
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(dimens.dashboardColumns),
                    modifier = capped,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = dimens.screenGutter, vertical = R1.space.s,
                    ),
                    verticalArrangement = Arrangement.spacedBy(R1.space.xs),
                    horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
                ) {
                    // Each row is its own `items` entry keyed by entity_id so the
                    // list lazily composes/recycles rows and a single person's
                    // state change recomposes only that one row, not the whole
                    // section. The section header is a plain header item above
                    // its rows; ordering and visible content are unchanged.
                    if (ui.people.isNotEmpty()) {
                        item(
                            key = "__sec_people",
                            contentType = "header",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            // heading() promotes the section title to a TalkBack
                            // navigation landmark so users can jump between the
                            // People and Device trackers groups.
                            Box(modifier = Modifier.semantics { heading() }) {
                                R1Section(
                                    title = "People",
                                    count = ui.people.size,
                                    topSpace = R1.space.s,
                                    trailing = { PresenceSummary(ui.people) },
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
                        item(
                            key = "__sec_devices",
                            contentType = "header",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            Box(modifier = Modifier.semantics { heading() }) {
                                R1Section(
                                    title = "Device trackers",
                                    count = ui.devices.size,
                                    trailing = { PresenceSummary(ui.devices) },
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
 * shared AsyncBitmap loader when one is set; otherwise an in-house line glyph
 * (Person, or the Zone map-pin when the entity is in a named HA zone) drawn
 * faintly behind the display-name initials. The glyph and initials are tinted
 * by presence so a pictureless row still reads home/away/elsewhere at a glance,
 * and the slot is never an empty circle while a picture decodes or fails.
 */
@Composable
private fun PersonAvatar(
    entry: PersonsViewModel.Entry,
    presenceColor: androidx.compose.ui.graphics.Color,
) {
    val size = 36.dp
    // A named-zone presence reads as a place, so back the initials with the
    // map-pin (Zone) glyph rather than the head-and-shoulders (Person).
    val inNamedZone = presenceLabel(entry.state).kind == PresenceKind.ZONE
    val glyph = if (inNamedZone) {
        com.github.itskenny0.r1ha.ui.icons.R1IconSet.Zone
    } else {
        com.github.itskenny0.r1ha.ui.icons.R1IconSet.Person
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(presenceColor.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        // In-house glyph sits behind the initials at a low alpha so it reads as
        // a tinted backdrop rather than competing with the letters.
        if (entry.entityPicture == null) {
            androidx.compose.material3.Icon(
                imageVector = glyph,
                contentDescription = null,
                tint = presenceColor.copy(alpha = 0.5f),
                modifier = Modifier.size(size * 0.7f),
            )
        }
        // Initials sit on top as the always-present fallback label.
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

/**
 * Home-vs-away count for a section header ("3 HOME / 2 AWAY"), so the user
 * sees who's in without scanning every row. Home is tinted with the same
 * green the row chips use and away with the amber, keeping the accent system
 * consistent. UNKNOWN rows are excluded from both counts (see presenceTally).
 * The split is rendered as plain text rather than colour alone, and the whole
 * header carries one merged spoken summary for TalkBack.
 */
@Composable
private fun PresenceSummary(entries: List<PersonsViewModel.Entry>) {
    val tally = presenceTally(entries.map { it.state })
    if (tally.home == 0 && tally.away == 0 && tally.elsewhere == 0) return
    // Reconciled with the row chips: HOME green, AWAY amber, and named-zone
    // "OUT" in the same cool accent the zone chips use, so the header counts
    // match how each row is coloured (a named zone is no longer mislabelled as
    // away). A thin divider separates whichever buckets are present.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clearAndSetSemantics { contentDescription = tally.summary() },
    ) {
        val shown = buildList {
            if (tally.home > 0) add("${tally.home} HOME" to R1.AccentGreen)
            if (tally.away > 0) add("${tally.away} AWAY" to R1.StatusAmber)
            if (tally.elsewhere > 0) add("${tally.elsewhere} OUT" to R1.AccentCool)
        }
        shown.forEachIndexed { i, (label, color) ->
            if (i > 0) {
                Text(text = " / ", style = R1.labelMicro, color = R1.InkMuted)
            }
            Text(text = label, style = R1.labelMicro, color = color)
        }
    }
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
    // The whole row is tappable and opens this entity's location history, so
    // suffix the merged label with the tap action (the trailing chevron conveys
    // the same affordance visually). Appended here, not inside the pure helper,
    // so the helper's tested output stays a content-only description.
    val rowDescription = rowContentDescription(
        name = entry.name,
        state = entry.state,
        relativeTime = rel,
        source = entry.source,
        batteryLevel = entry.batteryLevel,
        gpsAccuracy = entry.gpsAccuracy,
    ) + ", open history"
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
        // Avatar: the person's entity_picture when HA provides one, with a
        // tinted initials chip as the fallback (and for device_trackers, which
        // rarely carry a picture). The presence colour tints the fallback so a
        // pictureless row still reads home/away at a glance.
        PersonAvatar(entry = entry, presenceColor = presence.color)
        Spacer(Modifier.width(R1.space.m))
        // Zone-presence chip: HOME (green), AWAY (amber), unknown (red), or
        // the named HA zone (cool accent). Derivation lives in the pure
        // presenceLabel() helper so it's unit-tested and Compose-free.
        Text(text = presence.label, style = responsiveType(R1.labelMicro), color = presence.color)
        Spacer(Modifier.width(R1.space.m))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.name,
                    style = responsiveType(R1.bodyEmph),
                    color = R1.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Relative timestamp on the right of the name: 'since
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
                // The raw entity_id is plumbing detail. For person.* rows it
                // merely restates the display name (person.jane_doe), so demote
                // it: show the friendlier presence-spoken location instead, and
                // keep the raw id only for device_tracker rows where it's the
                // useful "which device" disambiguator.
                val secondary = if (entry.kind == PersonsViewModel.Kind.PERSON) {
                    presenceSpoken(entry.state)
                } else {
                    entry.entityId
                }
                Text(
                    text = secondary,
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
                    // a glance: same red/amber/muted ramp the other
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
        // Trailing chevron: the only visual cue that the row drills into the
        // entity's location history. The parent row owns the tap + merged
        // semantics, so this glyph is decorative (no contentDescription, no
        // separate tap target) and the action is announced via rowDescription.
        Spacer(Modifier.width(R1.space.s))
        com.github.itskenny0.r1ha.ui.components.Chevron(
            direction = com.github.itskenny0.r1ha.ui.components.ChevronDirection.Right,
            tint = R1.InkMuted,
        )
    }
}
