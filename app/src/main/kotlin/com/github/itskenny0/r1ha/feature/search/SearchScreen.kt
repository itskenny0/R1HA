package com.github.itskenny0.r1ha.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.r1RowPressable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Universal Search surface — search every HA entity by name / id /
 * area; tap to fire (scenes / scripts / buttons) or toggle (lights /
 * switches / etc.). Read-only sensors and other non-toggle entities
 * surface a detail toast on tap rather than dispatching anything.
 *
 * Empty query renders an instructional placeholder rather than
 * dumping the entire entity registry — on a big install that's
 * thousands of rows which would be slow to scroll and not what the
 * user wants anyway.
 */
@Composable
fun SearchScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
    /** Opens the full-screen History drill-in for [entityId]. Routed
     *  from the small chart-glyph button on each result row. */
    onOpenHistory: (entityId: String) -> Unit = {},
    /** Opens the HA Assist surface. Surfaced from the empty-state
     *  fallback CTA: when the user's search returns nothing, offer to
     *  ask Assist instead (the query is staged via AssistDraftBus so
     *  the Assist screen lands with the prompt pre-filled). */
    onOpenAssist: () -> Unit = {},
) {
    val vm: SearchViewModel = viewModel(factory = SearchViewModel.factory(haRepository, settings))
    val ui by vm.ui.collectAsState()
    val results by vm.results.collectAsState()
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Active-page favourites set — used to swap the star glyph for
    // entities that are already favourited so the user doesn't try to
    // add them a second time (no-op anyway, but the visual feedback
    // closes the loop). Recomputed live as pages/favourites change.
    val appSettings by settings.settings.collectAsState(
        initial = com.github.itskenny0.r1ha.core.prefs.AppSettings(),
    )
    val activeFavourites = remember(appSettings.activePageId, appSettings.pages) {
        appSettings.pages.firstOrNull { it.id == appSettings.activePageId }
            ?.favorites?.toSet() ?: emptySet()
    }
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    // History-peek state — entity currently being previewed via long-press.
    // Holds an EntityState so the dialog can show the right name/unit even
    // when results re-shuffle underneath. Null = no peek active.
    var historyPeek by remember {
        androidx.compose.runtime.mutableStateOf<EntityState?>(null)
    }
    LaunchedEffect(Unit) {
        vm.refresh()
        kotlinx.coroutines.delay(80)
        runCatching { focus.requestFocus() }
    }
    // Long-press handler — open the entity in HA's web UI (the
    // /lovelace?edit=1&entity_id=… form HA uses internally to focus a
    // specific entity). Falls back to plain /lovelace when server isn't
    // configured.
    fun openInHa(entity: EntityState) {
        scope.launch {
            val server = runCatching { settings.settings.first().server?.url }.getOrNull()
            if (server.isNullOrBlank()) {
                Toaster.error("No HA server configured")
                return@launch
            }
            val url = "${server.trimEnd('/')}/history?entity_id=${entity.id.value}"
            runCatching {
                context.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { t ->
                R1Log.w("Search", "open-in-HA failed: ${t.message}")
                Toaster.error("No browser to open $url")
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        R1TopBar(title = "QUICK SEARCH", onBack = onBack)
        com.github.itskenny0.r1ha.ui.layout.AdaptiveContent(modifier = Modifier.weight(1f)) {
        // Domain-bucket filter chips and search field are inside AdaptiveContent
        // so they align with the results list at 800 dp on tablets. bucketCounts is
        // computed off Main in the ViewModel and exposed as a StateFlow so this
        // composable doesn't iterate the full entity registry on every recomp.
        val bucketCounts by vm.bucketCounts.collectAsState()
        BucketChips(
            current = ui.bucket,
            counts = bucketCounts,
            totalCount = ui.all.size,
            onSelect = { vm.setBucket(it) },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = R1.space.m, vertical = R1.space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "FIND", style = R1.labelMicro, color = R1.InkMuted, modifier = Modifier.padding(end = R1.space.s))
            Box(modifier = Modifier.weight(1f)) {
                R1TextField(
                    value = ui.query,
                    onValueChange = { vm.setQuery(it) },
                    placeholder = "kitchen light, scene, .door, ...",
                    monospace = false,
                    focusRequester = focus,
                )
            }
            if (ui.query.isNotEmpty()) {
                // 48 dp tap surface meets Android's interactive-target guidance; the
                // visible ✕ stays glyph-sized via the inner Text. Same pattern
                // applies on every clear-button across the app.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .r1Pressable(
                            onClick = { vm.setQuery("") },
                            contentDescription = "Clear search",
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "✕", style = R1.labelMicro, color = R1.InkSoft)
                }
            }
        }
        when {
            ui.loading && ui.all.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = "Loading entities"
                    },
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = R1.AccentWarm,
                )
            }
            // Failure path — listAllEntities errored. Show the error +
            // hint at recovery (pull-to-refresh or reconnect via
            // Settings).
            ui.error != null && ui.all.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(R1.space.xl),
                contentAlignment = Alignment.Center,
            ) {
                // Merge the three lines into one polite live-region node so TalkBack
                // announces the failure (and its recovery hint) as a single utterance
                // when the error first lands, instead of three separate stops.
                Column(
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Polite
                    },
                ) {
                    Text(
                        text = "Couldn't load entities.",
                        style = R1.body,
                        color = R1.StatusRed,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.size(R1.space.xs))
                    Text(
                        text = ui.error ?: "Unknown error",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                    Spacer(Modifier.size(R1.space.s))
                    Text(
                        text = "Open Settings and check your Server, or wait for the connection to reconnect.",
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                    )
                }
            }
            results.isEmpty() && ui.query.isBlank() &&
                ui.bucket == SearchViewModel.Bucket.ALL -> Box(
                modifier = Modifier.fillMaxSize().padding(R1.space.xl),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.semantics(mergeDescendants = true) {},
                ) {
                    Text(
                        text = "${ui.all.size} entities indexed.",
                        style = R1.body,
                        color = R1.InkMuted,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.size(R1.space.s))
                    Text(
                        text = "Type a name, entity_id, or area to find. Or tap a chip above to narrow by kind. Tap a result to fire (scenes / scripts / buttons) or toggle (lights / switches / fans).",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                }
            }
            results.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(R1.space.xl),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    // Announce the no-results outcome politely the moment a query
                    // settles with no match, so TalkBack users aren't left waiting
                    // on a silent empty list.
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                ) {
                    Text(
                        text = if (ui.query.isNotBlank()) "No matches for '${ui.query}'."
                        else "No ${ui.bucket.name.lowercase(java.util.Locale.US)} entities.",
                        style = R1.body,
                        color = R1.InkMuted,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (ui.bucket != SearchViewModel.Bucket.ALL) {
                        Spacer(Modifier.size(R1.space.s))
                        Text(
                            text = "Filter set to ${ui.bucket.name}. Tap ALL above to widen the search.",
                            style = R1.labelMicro,
                            color = R1.InkMuted,
                        )
                    }
                    // Fallback to Assist when the user's query didn't match any
                    // entity. Conversational intent ('turn off all the kitchen
                    // lights', 'is it raining tomorrow') routes naturally to
                    // HA's conversation engine even when no single entity_id
                    // matches the substring search; stage the query on
                    // AssistDraftBus and navigate.
                    if (ui.query.isNotBlank()) {
                        Spacer(Modifier.height(R1.space.l))
                        Box(
                            modifier = Modifier
                                .clip(R1.ShapeS)
                                .background(R1.AccentWarm.copy(alpha = 0.18f))
                                .r1Pressable(
                                    onClick = {
                                        com.github.itskenny0.r1ha.core.util.AssistDraftBus.push(ui.query)
                                        onOpenAssist()
                                    },
                                    contentDescription = "Ask Assist about ${ui.query}",
                                )
                                .heightIn(min = R1.MinTarget)
                                .padding(horizontal = R1.space.l, vertical = R1.space.m),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Ask Assist about \"${ui.query}\"",
                                style = R1.body,
                                color = R1.AccentWarm,
                            )
                        }
                    }
                }
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
                    item("__count_header") {
                        // When the list is at the cap, more entities matched than we show. Say so
                        // (with a "+") so the user reads it as "narrow your query" rather than
                        // "that entity doesn't exist".
                        val countLabel = if (results.size >= vm.currentResultCap) {
                            "${results.size}+ results, narrow your search"
                        } else {
                            "${results.size} result${if (results.size == 1) "" else "s"}"
                        }
                        Text(
                            text = countLabel,
                            style = R1.labelMicro,
                            color = R1.InkMuted,
                            modifier = Modifier
                                .padding(start = R1.space.xs, bottom = R1.space.xs)
                                // Announce the live result count as the query narrows,
                                // and expose it as a heading so TalkBack users can jump
                                // straight to the start of the results list.
                                .semantics {
                                    heading()
                                    liveRegion = LiveRegionMode.Polite
                                    contentDescription = countLabel
                                },
                        )
                    }
                    // contentType keyed on domain lets Compose recycle a row's layout
                    // tree across items of the same kind instead of rebuilding it on
                    // every scroll step. Domain is the right granularity because the
                    // row's shape (action-label, accent) is a pure function of domain.
                    items(
                        items = results,
                        key = { it.id.value },
                        contentType = { it.id.domain },
                    ) { entity ->
                        SearchResultRow(
                            entity,
                            isFavorite = entity.id.value in activeFavourites,
                            onTap = { vm.activate(entity) },
                            // Long-press now opens a compact history-peek dialog (with
                            // an OPEN IN HA button inside for users who still want the
                            // browser fallback). Replaces the previous "long-press =
                            // open in HA" gesture because the inline peek is a more
                            // useful default — most queries are "what is this sensor
                            // doing?" rather than "let me jump to the web UI."
                            onLongPress = { historyPeek = entity },
                            onFavorite = { vm.addToFavorites(entity.id) },
                            onHistory = { onOpenHistory(entity.id.value) },
                        )
                    }
                }
            }
        }
        } // AdaptiveContent
    }
    historyPeek?.let { peeked ->
        HistoryPeekDialog(
            entity = peeked,
            haRepository = haRepository,
            onDismiss = { historyPeek = null },
            onOpenFull = {
                val id = peeked.id.value
                historyPeek = null
                onOpenHistory(id)
            },
            onOpenInHa = {
                openInHa(peeked)
                historyPeek = null
            },
        )
    }
}

/**
 * Inline history peek triggered by a long-press on a Search row. Fetches the
 * last 24 hours of history via the repository, renders it through the same
 * [com.github.itskenny0.r1ha.ui.components.SensorHistoryChart] component the
 * card stack uses, and offers shortcuts to the full History screen or to
 * HA's web UI. Bypassed when the entity is non-numeric — the chart component
 * itself surfaces a "HISTORY ISN'T NUMERIC" hint in that case.
 */
@Composable
private fun HistoryPeekDialog(
    entity: EntityState,
    haRepository: HaRepository,
    onDismiss: () -> Unit,
    onOpenFull: () -> Unit,
    onOpenInHa: () -> Unit,
) {
    var points by remember(entity.id.value) {
        androidx.compose.runtime.mutableStateOf<List<com.github.itskenny0.r1ha.core.ha.HistoryPoint>?>(null)
    }
    var error by remember(entity.id.value) {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    LaunchedEffect(entity.id.value) {
        haRepository.fetchHistory(entity.id, hours = 24).fold(
            onSuccess = { points = it },
            onFailure = { error = it.message ?: "fetch failed" },
        )
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = R1.Bg,
        title = {
            androidx.compose.foundation.layout.Column {
                Text(text = entity.friendlyName, style = R1.sectionHeader, color = R1.Ink)
                Text(text = entity.id.value, style = R1.labelMicro, color = R1.InkSoft)
            }
        },
        text = {
            androidx.compose.foundation.layout.Column {
                when {
                    error != null -> Text(
                        text = "Couldn't load history: $error",
                        style = R1.body,
                        color = R1.StatusRed,
                    )
                    points == null -> Text(
                        text = "LOADING…",
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                    )
                    else -> com.github.itskenny0.r1ha.ui.components.SensorHistoryChart(
                        points = points ?: emptyList(),
                        accent = R1.AccentWarm,
                        unit = entity.unit,
                    )
                }
            }
        },
        confirmButton = {
            com.github.itskenny0.r1ha.ui.components.R1Button(
                text = "OPEN FULL",
                onClick = onOpenFull,
            )
        },
        dismissButton = {
            com.github.itskenny0.r1ha.ui.components.R1Button(
                text = "OPEN IN HA",
                onClick = onOpenInHa,
                variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
            )
        },
    )
}

@Composable
private fun BucketChips(
    current: SearchViewModel.Bucket,
    counts: Map<SearchViewModel.Bucket, Int>,
    totalCount: Int,
    onSelect: (SearchViewModel.Bucket) -> Unit,
) {
    val items = listOf(
        SearchViewModel.Bucket.ALL to "ALL",
        SearchViewModel.Bucket.CONTROLS to "CONTROLS",
        SearchViewModel.Bucket.SENSORS to "SENSORS",
        SearchViewModel.Bucket.ACTIONS to "ACTIONS",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
        horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
    ) {
        for ((bucket, label) in items) {
            // ALL chip shows the grand total; the rest show their own bucket
            // count. Suppressed while the registry is still loading (totalCount
            // == 0) so the chips don't briefly read 'CONTROLS 0' before the
            // first refresh lands.
            val count = if (bucket == SearchViewModel.Bucket.ALL) totalCount else counts[bucket] ?: 0
            val display = if (totalCount == 0) label else "$label  $count"
            val selected = bucket == current
            R1Chip(
                text = display,
                variant = R1ChipVariant.Filter,
                selected = selected,
                onClick = { onSelect(bucket) },
                // Spell the count and selection out so TalkBack reads
                // "CONTROLS filter, 12 entities, selected" rather than the
                // packed "CONTROLS  12" glyph string.
                contentDescription = bucketChipContentDescription(
                    label = label,
                    count = if (totalCount == 0) null else count,
                    selected = selected,
                ),
            )
        }
    }
}

@Composable
private fun SearchResultRow(
    entity: EntityState,
    isFavorite: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onFavorite: () -> Unit,
    onHistory: () -> Unit,
) {
    val domain = entity.id.domain
    // Domain-derived display values are pure functions of the domain, so remember
    // them keyed on it. Avoids re-running the uppercase()/take()/when-mapping work on
    // every recomposition (scroll, favourite-toggle) and across the whole visible
    // window when only one row's state changed.
    val domainLabel = remember(domain) {
        // Wider truncation (10 chars vs. previous 6) so "AUTOMATION" no longer reads
        // "AUTOMA"; longer HA domains like INPUT_NUMBER still need ellipsis but the
        // common ones now fit unbroken.
        domain.prefix.uppercase().let { p -> if (p.length <= 10) p else p.take(9) + "…" }
    }
    val domainAccent = remember(domain) { accentFor(domain) }
    val actionLabel = remember(domain, entity.isOn) { actionLabelFor(domain, entity.isOn) }
    // Single spoken description for the row's primary tap target. Without it
    // TalkBack would stitch together the domain tag, name, raw entity_id, state
    // and the action glyph into a noisy run; this collapses them into one tidy
    // utterance that leads with the friendly name and ends with what a tap does.
    val rowDescription = remember(
        entity.friendlyName, entity.rawState, entity.area, domain, actionLabel,
    ) {
        rowContentDescription(
            friendlyName = entity.friendlyName,
            domainPrefix = domain.prefix,
            rawState = entity.rawState,
            area = entity.area,
            actionLabel = actionLabel,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            // Tap = the domain-appropriate action (fire/press/toggle/info).
            // Long-press = open the inline history-peek dialog.
            .r1RowPressable(
                onTap = onTap,
                onLongPress = onLongPress,
                contentDescription = rowDescription,
            )
            .heightIn(min = R1.MinTarget)
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = domainLabel,
            style = R1.labelMicro,
            color = domainAccent,
        )
        Spacer(Modifier.width(R1.space.m))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = entity.friendlyName, style = R1.bodyEmph, color = R1.Ink, maxLines = 1)
            val stateLine = remember(entity.id.value, entity.rawState, entity.area) {
                buildString {
                    append(entity.id.value)
                    entity.rawState?.let { append("  ·  ").append(it) }
                    entity.area?.let { append("  ·  ").append(it) }
                }
            }
            Text(text = stateLine, style = R1.labelMicro, color = R1.InkSoft, maxLines = 1)
        }
        Spacer(Modifier.width(R1.space.s))
        // History drill-in glyph — opens the full-screen history view
        // for this entity. Separate tap target from the action/toggle
        // path so the user can investigate a sensor's recent state
        // without tripping the toggle action on adjacent rows. Hand-
        // drawn HistoryChartGlyph (was 📈 emoji) so the icon stays
        // monochrome and reads at the same hairline weight as the
        // surrounding chrome — the colour-emoji font was painting a
        // green/red chart icon that visibly broke the row's tone.
        Box(
            modifier = Modifier
                .size(48.dp)
                .r1Pressable(
                    onClick = onHistory,
                    contentDescription = "History for ${entity.friendlyName}",
                ),
            contentAlignment = Alignment.Center,
        ) {
            com.github.itskenny0.r1ha.ui.components.HistoryChartGlyph(
                size = 14.dp,
                tint = R1.InkSoft,
            )
        }
        // Star tap target — adds the entity to the active page's
        // favourites. Separate from the row's main r1RowPressable so a
        // tap on the star doesn't fire the entity's action. Filled glyph
        // + accent tint when the entity is already on the active page,
        // so the user doesn't fruitlessly re-tap.
        Box(
            modifier = Modifier
                .size(48.dp)
                .r1Pressable(
                    onClick = onFavorite,
                    contentDescription = if (isFavorite) {
                        "${entity.friendlyName} is a favourite"
                    } else {
                        "Add ${entity.friendlyName} to favourites"
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (isFavorite) "★" else "☆",
                style = R1.body,
                color = if (isFavorite) R1.AccentWarm else R1.InkSoft,
            )
        }
        Spacer(Modifier.width(R1.space.xs))
        // Action affordance hint, what tap will do. Already folded into the row's
        // merged contentDescription above, so hide this glyph from TalkBack to
        // avoid a duplicate "ON" / "FIRE" announcement after the row description.
        Text(
            text = actionLabel,
            style = R1.labelMicro,
            color = R1.AccentWarm,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * Spoken description for a result row's primary tap target. Leads with the
 * friendly name (what the user is looking for), then the kind, current state and
 * area when present, and closes with the action a tap performs. Extracted as a
 * pure function so the exact wording can be unit-tested without a composable.
 *
 * The trailing [actionLabel] is mapped to a spoken verb: the row glyph shows a
 * terse "ON" / "OFF" / "FIRE" / "PRESS" / "INFO", but TalkBack reads better with
 * a phrase describing the gesture's effect.
 */
internal fun rowContentDescription(
    friendlyName: String,
    domainPrefix: String,
    rawState: String?,
    area: String?,
    actionLabel: String,
): String = buildString {
    append(friendlyName)
    append(", ").append(domainPrefix.replace('_', ' '))
    if (!rawState.isNullOrBlank()) append(", ").append(rawState)
    if (!area.isNullOrBlank()) append(", ").append(area)
    append(". ").append(actionVerbFor(actionLabel))
}

/** Maps a terse on-screen action glyph to a spoken phrase for TalkBack. */
private fun actionVerbFor(actionLabel: String): String = when (actionLabel) {
    "ON" -> "Tap to turn on"
    "OFF" -> "Tap to turn off"
    "FIRE" -> "Tap to fire"
    "PRESS" -> "Tap to press"
    else -> "Tap for details"
}

/**
 * Spoken description for a domain-filter chip. Reads the kind, the matching
 * entity count when known, and whether the chip is the active filter. Pure so
 * the phrasing is unit-testable.
 */
internal fun bucketChipContentDescription(
    label: String,
    count: Int?,
    selected: Boolean,
): String = buildString {
    append(label).append(" filter")
    if (count != null) {
        append(", ").append(count).append(if (count == 1) " entity" else " entities")
    }
    if (selected) append(", selected")
}

/**
 * Tap-affordance label for a result row: what tapping the row will do. Pure
 * function of the entity's domain and current on/off state, extracted so it can be
 * unit-tested independently of the composable. "FIRE" for scenes/scripts, "PRESS"
 * for buttons, "ON"/"OFF" for toggleable entities (reflecting the post-tap target),
 * "INFO" for everything read-only.
 */
internal fun actionLabelFor(domain: Domain, isOn: Boolean): String = when (domain) {
    Domain.SCENE, Domain.SCRIPT -> "FIRE"
    Domain.BUTTON, Domain.INPUT_BUTTON -> "PRESS"
    Domain.LIGHT, Domain.SWITCH, Domain.FAN, Domain.COVER, Domain.LOCK,
    Domain.MEDIA_PLAYER, Domain.INPUT_BOOLEAN, Domain.AUTOMATION,
    Domain.HUMIDIFIER, Domain.CLIMATE, Domain.WATER_HEATER, Domain.VACUUM,
    Domain.LAWN_MOWER, Domain.VALVE -> if (isOn) "OFF" else "ON"
    else -> "INFO"
}

private fun accentFor(domain: Domain): androidx.compose.ui.graphics.Color = when (domain) {
    Domain.LIGHT, Domain.FAN, Domain.MEDIA_PLAYER, Domain.SWITCH, Domain.INPUT_BOOLEAN -> R1.AccentWarm
    Domain.SENSOR, Domain.BINARY_SENSOR, Domain.COVER, Domain.VALVE, Domain.NUMBER,
    Domain.INPUT_NUMBER -> R1.AccentCool
    Domain.SCENE, Domain.SCRIPT, Domain.AUTOMATION, Domain.BUTTON,
    Domain.INPUT_BUTTON -> R1.AccentGreen
    else -> R1.AccentNeutral
}
