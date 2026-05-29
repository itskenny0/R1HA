package com.github.itskenny0.r1ha.feature.mediabrowse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.MediaBrowseEntry
import com.github.itskenny0.r1ha.core.ha.MediaBrowseResult
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import com.github.itskenny0.r1ha.ui.components.AsyncBitmap
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private class MediaBrowseViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = false,
        val entityId: String? = null,
        val children: List<MediaBrowseEntry> = emptyList(),
        val crumbs: List<MediaBrowseNav.Crumb> = emptyList(),
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    /** True when a hardware-back press should pop a browse level rather than
     *  leave the screen entirely. */
    fun canPopLevel(): Boolean = MediaBrowseNav.canPopLevel(_ui.value.crumbs)

    fun openRoot(entityId: String) {
        _ui.value = UiState(entityId = entityId, loading = true)
        browse(MediaBrowseNav.Crumb("ROOT", null, null), reset = true)
    }

    fun navigate(crumb: MediaBrowseNav.Crumb) {
        val current = _ui.value
        if (current.entityId == null) return
        _ui.value = current.copy(loading = true)
        browse(crumb, reset = false)
    }

    fun back() {
        val current = _ui.value
        val target = MediaBrowseNav.parentCrumb(current.crumbs) ?: return
        // Drop the current + parent crumbs; re-browsing the parent re-appends
        // one so the path lands exactly one level shorter.
        _ui.value = current.copy(
            loading = true,
            crumbs = MediaBrowseNav.crumbsForBack(current.crumbs),
        )
        browse(target, reset = false)
    }

    /** Re-browse the folder currently on screen (pull-to-refresh). Re-fetches
     *  the deepest crumb without changing the path depth. */
    fun refresh() {
        val current = _ui.value
        if (current.entityId == null) return
        val here = current.crumbs.lastOrNull() ?: return
        _ui.value = current.copy(
            loading = true,
            crumbs = current.crumbs.dropLast(1),
        )
        browse(here, reset = false)
    }

    private fun browse(crumb: MediaBrowseNav.Crumb, reset: Boolean) {
        val entity = _ui.value.entityId ?: return
        viewModelScope.launch {
            haRepository.browseMedia(
                entityId = entity,
                mediaContentId = crumb.mediaContentId,
                mediaContentType = crumb.mediaContentType,
            ).fold(
                onSuccess = { result ->
                    val newCrumbs = if (reset) MediaBrowseNav.rootCrumbs(result)
                    else MediaBrowseNav.pushCrumb(_ui.value.crumbs, result)
                    _ui.value = _ui.value.copy(
                        loading = false,
                        children = MediaBrowseNav.sortChildren(result.children),
                        crumbs = newCrumbs,
                        error = null,
                    )
                },
                onFailure = { t ->
                    R1Log.w("MediaBrowse", "browse failed: ${t.message}")
                    _ui.value = _ui.value.copy(loading = false, error = t.message)
                },
            )
        }
    }

    fun play(entry: MediaBrowseEntry) {
        val entity = _ui.value.entityId ?: return
        viewModelScope.launch {
            val target = runCatching { EntityId(entity) }.getOrNull() ?: run {
                Toaster.error("Invalid entity_id: $entity")
                return@launch
            }
            val data = buildJsonObject {
                put("media_content_id", JsonPrimitive(entry.mediaContentId))
                put("media_content_type", JsonPrimitive(entry.mediaContentType))
            }
            haRepository.call(ServiceCall(target = target, service = "play_media", data = data))
                .fold(
                    onSuccess = { Toaster.show("Playing: ${entry.title}") },
                    onFailure = { t ->
                        Toaster.errorExpandable(
                            shortText = "Play failed",
                            fullText = t.message ?: t.toString(),
                        )
                    },
                )
        }
    }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { MediaBrowseViewModel(haRepository) }
        }
    }
}

@Composable
fun MediaBrowseScreen(
    haRepository: HaRepository,
    onBack: () -> Unit,
) {
    val vm: MediaBrowseViewModel = viewModel(factory = MediaBrowseViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    var entityInput by remember { mutableStateOf("") }

    // Transient per-row "PLAY tapped" set, kept screen-local (the ViewModel has no
    // per-row in-flight flag and editing it is out of slice). A tapped playable row
    // flips its spoken label to "Playing <title>" under a polite live region for a
    // beat so a screen-reader user hears that the play registered; it clears itself
    // shortly after.
    val playingNow = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val playScope = androidx.compose.runtime.rememberCoroutineScope()
    val markPlaying: (String) -> Unit = { key ->
        playingNow[key] = true
        playScope.launch {
            kotlinx.coroutines.delay(1_500L)
            playingNow.remove(key)
        }
    }

    // Server URL + bearer token drive the AsyncBitmap thumbnails. browse_media
    // returns proxied / relative thumbnail paths that need the configured HA
    // host prepended and the token attached. Sourced from the process graph so
    // the screen's nav-graph call site doesn't need new parameters.
    val app = LocalContext.current.applicationContext as com.github.itskenny0.r1ha.App
    val serverUrl by produceState<String?>(null, app) {
        value = app.graph.settings.settings.first().server?.url
    }
    val token by produceState<String?>(null, app) { value = app.graph.tokens.load()?.accessToken }

    // Hardware Back pops one browse level if we're below the root; otherwise it
    // leaves the screen. Enabled only while there is a level to pop so the OS
    // default (leave screen) handles the root case.
    androidx.activity.compose.BackHandler(enabled = vm.canPopLevel()) { vm.back() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "MEDIA BROWSE", onBack = onBack)
        AdaptiveContent(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                // Entity binding row — sits at top so the user can swap the
                // media_player target without losing browse state for the
                // current one.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        R1TextField(
                            value = if (ui.entityId.isNullOrBlank()) entityInput else (ui.entityId ?: ""),
                            onValueChange = { entityInput = it },
                            modifier = Modifier.semantics {
                                contentDescription = "Media player entity id to browse"
                            },
                            placeholder = "media_player.living_room",
                            monospace = true,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .heightIn(min = R1.MinTarget)
                            .clip(R1.ShapeS)
                            .background(R1.SurfaceMuted)
                            .border(1.dp, R1.Hairline, R1.ShapeS)
                            .r1Pressable(
                                onClick = {
                                    val target = entityInput.trim()
                                    if (target.isBlank()) {
                                        Toaster.error("Type a media_player.* entity_id first")
                                    } else {
                                        vm.openRoot(target)
                                    }
                                },
                                contentDescription = "Browse this media player",
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "BROWSE", style = R1.labelMicro, color = R1.AccentWarm)
                    }
                }
                Spacer(Modifier.size(8.dp))
                // Breadcrumb strip — horizontal scroll so deep paths don't
                // truncate. Most recent on the right.
                if (ui.crumbs.isNotEmpty()) {
                    // Spoken as one path phrase ("Library, then Artists. Currently
                    // in Artists.") and marked a heading so a reader can jump to it,
                    // rather than announcing each crumb and the " / " separators as
                    // separate fragments. Position + colour mark the current folder
                    // visually; the merged label states it in words.
                    val crumbLabel = mediaBreadcrumbLabel(ui.crumbs.map { it.title })
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .semantics {
                                heading()
                                contentDescription = crumbLabel
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ui.crumbs.forEachIndexed { i, c ->
                            if (i > 0) Text(text = " / ", style = R1.labelMicro, color = R1.InkMuted)
                            Text(
                                text = c.title,
                                style = R1.labelMicro,
                                color = if (i == ui.crumbs.lastIndex) R1.Ink else R1.InkMuted,
                            )
                        }
                    }
                    Spacer(Modifier.size(6.dp))
                    if (ui.crumbs.size > 1) {
                        Box(
                            modifier = Modifier
                                .heightIn(min = R1.MinTarget)
                                .clip(R1.ShapeS)
                                .background(R1.SurfaceMuted)
                                .border(1.dp, R1.Hairline, R1.ShapeS)
                                .r1Pressable(
                                    onClick = { vm.back() },
                                    contentDescription = "Go up one folder",
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = "← UP", style = R1.labelMicro, color = R1.InkSoft)
                        }
                        Spacer(Modifier.size(8.dp))
                    }
                }
                // Body. The full-screen spinner only covers the first load of a
                // level (no children yet); a pull-to-refresh over an already-
                // populated list keeps the list visible with the refresh
                // indicator instead of flashing the whole screen blank.
                when {
                    ui.loading && ui.children.isEmpty() ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .semantics {
                                    liveRegion = LiveRegionMode.Polite
                                    contentDescription = "Loading media library"
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = R1.AccentWarm,
                            )
                        }
                    ui.error != null -> Text(
                        text = "Browse failed: ${ui.error}",
                        style = R1.body,
                        color = R1.StatusAmber,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    ui.entityId == null -> Text(
                        text = "Pick a media_player entity above to browse its library.",
                        style = R1.body,
                        color = R1.InkMuted,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    ui.children.isEmpty() -> PullToRefreshBox(
                        isRefreshing = ui.loading,
                        onRefresh = { vm.refresh() },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // Keep an empty folder pull-to-refreshable so a since-
                        // populated playlist can be re-fetched without leaving.
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item {
                                Text(
                                    text = "This folder is empty.",
                                    style = R1.body,
                                    color = R1.InkMuted,
                                    modifier = Modifier.semantics {
                                        liveRegion = LiveRegionMode.Polite
                                    },
                                )
                            }
                        }
                    }
                    else -> PullToRefreshBox(
                        isRefreshing = ui.loading,
                        onRefresh = { vm.refresh() },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(ui.children, key = { it.mediaContentId + "|" + it.mediaContentType }) { entry ->
                                val playKey = entry.mediaContentId + "|" + entry.mediaContentType
                                EntryRow(
                                    entry = entry,
                                    serverUrl = serverUrl,
                                    bearerToken = token,
                                    playing = playingNow[playKey] == true,
                                    onTap = {
                                        when {
                                            entry.canExpand -> vm.navigate(
                                                MediaBrowseNav.Crumb(
                                                    title = entry.title,
                                                    mediaContentId = entry.mediaContentId,
                                                    mediaContentType = entry.mediaContentType,
                                                ),
                                            )
                                            entry.canPlay -> {
                                                markPlaying(playKey)
                                                vm.play(entry)
                                            }
                                            else -> Toaster.show("Item isn't playable or expandable")
                                        }
                                    },
                                    onPlay = {
                                        markPlaying(playKey)
                                        vm.play(entry)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: MediaBrowseEntry,
    serverUrl: String?,
    bearerToken: String?,
    playing: Boolean,
    onTap: () -> Unit,
    onPlay: () -> Unit,
) {
    // Merged spoken label so TalkBack reads the row as one unit (title, kind,
    // media class, and what a tap does) instead of announcing the glyph and the
    // title separately. While a play_media is in flight the label flips to
    // "Playing <title>" under a polite live region.
    val rowLabel = if (playing) mediaPlayInFlightLabel(entry.title) else mediaEntryRowLabel(entry)
    val rowSemantics = if (playing) {
        Modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = rowLabel
        }
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = R1.MinTarget)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .then(rowSemantics)
            .r1Pressable(onClick = onTap, contentDescription = rowLabel)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Thumbnail where the entry carries one; otherwise a type glyph in the
        // same footprint so titles stay aligned across rows with and without
        // art.
        if (!entry.thumbnail.isNullOrBlank()) {
            AsyncBitmap(
                url = entry.thumbnail,
                serverUrl = serverUrl,
                bearerToken = bearerToken,
                modifier = Modifier
                    .size(40.dp)
                    .clip(R1.ShapeS),
                contentDescription = null,
            )
        } else {
            val glyph = when {
                entry.canExpand && entry.canPlay -> "▸"
                entry.canExpand -> "›"
                entry.canPlay -> "▷"
                else -> "·"
            }
            Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Text(text = glyph, style = R1.bodyEmph, color = R1.AccentWarm)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = entry.title, style = R1.body, color = R1.Ink, maxLines = 1)
            if (!entry.mediaClass.isNullOrBlank()) {
                Text(
                    text = entry.mediaClass.uppercase(),
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
        }
        if (entry.canPlay) {
            Box(
                modifier = Modifier
                    .heightIn(min = R1.MinTarget)
                    .clip(R1.ShapeS)
                    .background(R1.Bg)
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(
                        onClick = onPlay,
                        contentDescription = mediaPlayActionLabel(entry.title),
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "PLAY", style = R1.labelMicro, color = R1.AccentCool)
            }
        }
    }
}
