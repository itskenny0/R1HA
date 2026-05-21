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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
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
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "FIND", style = R1.labelMicro, color = R1.InkMuted, modifier = Modifier.padding(end = 8.dp))
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
                    modifier = Modifier.size(48.dp).r1Pressable({ vm.setQuery("") }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "✕", style = R1.labelMicro, color = R1.InkSoft)
                }
            }
        }
        when {
            ui.loading && ui.all.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
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
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column {
                    Text(
                        text = "Couldn't load entities.",
                        style = R1.body,
                        color = R1.StatusRed,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = ui.error ?: "Unknown error",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = "Check Settings → Server, or wait for the WS to reconnect.",
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                    )
                }
            }
            results.isEmpty() && ui.query.isBlank() &&
                ui.bucket == SearchViewModel.Bucket.ALL -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column {
                    Text(
                        text = "${ui.all.size} entities indexed.",
                        style = R1.body,
                        color = R1.InkMuted,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = "Type a name, entity_id, or area to find. Or tap a chip above to narrow by kind. Tap a result to fire (scenes / scripts / buttons) or toggle (lights / switches / fans).",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                }
            }
            results.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (ui.query.isNotBlank()) "No matches for '${ui.query}'."
                        else "No ${ui.bucket.name.lowercase()} entities.",
                        style = R1.body,
                        color = R1.InkMuted,
                    )
                    if (ui.bucket != SearchViewModel.Bucket.ALL) {
                        Spacer(Modifier.size(6.dp))
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
                        Spacer(Modifier.height(14.dp))
                        Box(
                            modifier = Modifier
                                .clip(R1.ShapeS)
                                .background(R1.AccentWarm.copy(alpha = 0.18f))
                                .r1Pressable(onClick = {
                                    com.github.itskenny0.r1ha.core.util.AssistDraftBus.push(ui.query)
                                    onOpenAssist()
                                })
                                .padding(horizontal = 14.dp, vertical = 10.dp),
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
                        horizontal = 12.dp, vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item("__count_header") {
                        Text(
                            text = "${results.size} result${if (results.size == 1) "" else "s"}",
                            style = R1.labelMicro,
                            color = R1.InkMuted,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                        )
                    }
                    items(items = results, key = { it.id.value }) { entity ->
                        SearchResultRow(
                            entity,
                            isFavorite = entity.id.value in activeFavourites,
                            onTap = { vm.activate(entity) },
                            onLongPress = { openInHa(entity) },
                            onFavorite = { vm.addToFavorites(entity.id) },
                            onHistory = { onOpenHistory(entity.id.value) },
                        )
                    }
                }
            }
        }
        } // AdaptiveContent
    }
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
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((bucket, label) in items) {
            val active = bucket == current
            // ALL chip shows the grand total; the rest show their own bucket
            // count. Suppressed while the registry is still loading (totalCount
            // == 0) so the chips don't briefly read 'CONTROLS 0' before the
            // first refresh lands.
            val count = if (bucket == SearchViewModel.Bucket.ALL) totalCount else counts[bucket] ?: 0
            val display = if (totalCount == 0) label else "$label  $count"
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(if (active) R1.AccentWarm else R1.SurfaceMuted)
                    .r1Pressable(onClick = { onSelect(bucket) })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = display,
                    style = R1.labelMicro,
                    color = if (active) R1.Bg else R1.InkSoft,
                )
            }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            // Tap = the domain-appropriate action (fire/press/toggle/info).
            // Long-press = open the entity's /history view in HA's web UI.
            .r1RowPressable(onTap = onTap, onLongPress = onLongPress)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            // Wider truncation (10 chars vs. previous 6) so "AUTOMATION" no
            // longer reads "AUTOMA"; longer HA domains like INPUT_NUMBER still
            // need ellipsis but the common ones now fit unbroken.
            text = entity.id.domain.prefix.uppercase().let { p ->
                if (p.length <= 10) p else p.take(9) + "…"
            },
            style = R1.labelMicro,
            color = accentFor(entity.id.domain),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = entity.friendlyName, style = R1.body, color = R1.Ink, maxLines = 1)
            val stateLine = buildString {
                append(entity.id.value)
                entity.rawState?.let { append("  ·  ").append(it) }
                entity.area?.let { append("  ·  ").append(it) }
            }
            Text(text = stateLine, style = R1.labelMicro, color = R1.InkSoft, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
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
                .r1Pressable(onClick = onHistory),
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
                .r1Pressable(onClick = onFavorite),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (isFavorite) "★" else "☆",
                style = R1.body,
                color = if (isFavorite) R1.AccentWarm else R1.InkSoft,
            )
        }
        Spacer(Modifier.width(4.dp))
        // Action affordance hint — what tap will do.
        val actionLabel = when (entity.id.domain) {
            Domain.SCENE, Domain.SCRIPT -> "FIRE"
            Domain.BUTTON, Domain.INPUT_BUTTON -> "PRESS"
            Domain.LIGHT, Domain.SWITCH, Domain.FAN, Domain.COVER, Domain.LOCK,
            Domain.MEDIA_PLAYER, Domain.INPUT_BOOLEAN, Domain.AUTOMATION,
            Domain.HUMIDIFIER, Domain.CLIMATE, Domain.WATER_HEATER, Domain.VACUUM,
            Domain.LAWN_MOWER, Domain.VALVE -> if (entity.isOn) "OFF" else "ON"
            else -> "INFO"
        }
        Text(text = actionLabel, style = R1.labelMicro, color = R1.AccentWarm)
    }
}

private fun accentFor(domain: Domain): androidx.compose.ui.graphics.Color = when (domain) {
    Domain.LIGHT, Domain.FAN, Domain.MEDIA_PLAYER, Domain.SWITCH, Domain.INPUT_BOOLEAN -> R1.AccentWarm
    Domain.SENSOR, Domain.BINARY_SENSOR, Domain.COVER, Domain.VALVE, Domain.NUMBER,
    Domain.INPUT_NUMBER -> R1.AccentCool
    Domain.SCENE, Domain.SCRIPT, Domain.AUTOMATION, Domain.BUTTON,
    Domain.INPUT_BUTTON -> R1.AccentGreen
    else -> R1.AccentNeutral
}
