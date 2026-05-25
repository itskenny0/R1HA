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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private class MediaBrowseViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    data class Crumb(val title: String, val mediaContentId: String?, val mediaContentType: String?)
    data class UiState(
        val loading: Boolean = false,
        val entityId: String? = null,
        val children: List<MediaBrowseEntry> = emptyList(),
        val crumbs: List<Crumb> = emptyList(),
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun openRoot(entityId: String) {
        _ui.value = UiState(entityId = entityId, loading = true)
        browse(Crumb("ROOT", null, null), reset = true)
    }

    fun navigate(crumb: Crumb) {
        val current = _ui.value
        if (current.entityId == null) return
        _ui.value = current.copy(loading = true)
        browse(crumb, reset = false)
    }

    fun back() {
        val current = _ui.value
        if (current.crumbs.size <= 1) return
        val target = current.crumbs[current.crumbs.size - 2]
        // Drop everything from the target back so we don't lose history on a forward
        // navigation by accident.
        val truncated = current.crumbs.dropLast(2)
        _ui.value = current.copy(
            loading = true,
            crumbs = truncated,
        )
        browse(target, reset = false)
    }

    private fun browse(crumb: Crumb, reset: Boolean) {
        val entity = _ui.value.entityId ?: return
        viewModelScope.launch {
            haRepository.browseMedia(
                entityId = entity,
                mediaContentId = crumb.mediaContentId,
                mediaContentType = crumb.mediaContentType,
            ).fold(
                onSuccess = { result ->
                    val newCrumbs = if (reset) listOf(Crumb(result.current.title, null, null))
                    else _ui.value.crumbs + Crumb(
                        title = result.current.title,
                        mediaContentId = result.current.mediaContentId,
                        mediaContentType = result.current.mediaContentType,
                    )
                    _ui.value = _ui.value.copy(
                        loading = false,
                        children = result.children,
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
                            placeholder = "media_player.living_room",
                            monospace = true,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(R1.ShapeS)
                            .background(R1.SurfaceMuted)
                            .border(1.dp, R1.Hairline, R1.ShapeS)
                            .r1Pressable(onClick = {
                                val target = entityInput.trim()
                                if (target.isBlank()) {
                                    Toaster.error("Type a media_player.* entity_id first")
                                } else {
                                    vm.openRoot(target)
                                }
                            })
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(text = "BROWSE", style = R1.labelMicro, color = R1.AccentWarm)
                    }
                }
                Spacer(Modifier.size(8.dp))
                // Breadcrumb strip — horizontal scroll so deep paths don't
                // truncate. Most recent on the right.
                if (ui.crumbs.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
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
                                .clip(R1.ShapeS)
                                .background(R1.SurfaceMuted)
                                .border(1.dp, R1.Hairline, R1.ShapeS)
                                .r1Pressable(onClick = { vm.back() })
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(text = "← UP", style = R1.labelMicro, color = R1.InkSoft)
                        }
                        Spacer(Modifier.size(8.dp))
                    }
                }
                // Body
                when {
                    ui.loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = R1.AccentWarm,
                        )
                    }
                    ui.error != null -> Text(
                        text = ui.error ?: "",
                        style = R1.body,
                        color = R1.StatusAmber,
                    )
                    ui.entityId == null -> Text(
                        text = "Pick a media_player entity above to browse its library.",
                        style = R1.body,
                        color = R1.InkMuted,
                    )
                    ui.children.isEmpty() -> Text(
                        text = "(Empty)",
                        style = R1.body,
                        color = R1.InkMuted,
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(ui.children, key = { it.mediaContentId + "|" + it.mediaContentType }) { entry ->
                            EntryRow(
                                entry = entry,
                                onTap = {
                                    when {
                                        entry.canExpand -> vm.navigate(
                                            MediaBrowseViewModel.Crumb(
                                                title = entry.title,
                                                mediaContentId = entry.mediaContentId,
                                                mediaContentType = entry.mediaContentType,
                                            ),
                                        )
                                        entry.canPlay -> vm.play(entry)
                                        else -> Toaster.show("Item isn't playable or expandable")
                                    }
                                },
                                onPlay = { vm.play(entry) },
                            )
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
    onTap: () -> Unit,
    onPlay: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onTap)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val glyph = when {
            entry.canExpand && entry.canPlay -> "▸"
            entry.canExpand -> "›"
            entry.canPlay -> "▷"
            else -> "·"
        }
        Text(text = glyph, style = R1.bodyEmph, color = R1.AccentWarm)
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
                    .clip(R1.ShapeS)
                    .background(R1.Bg)
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(onClick = onPlay)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(text = "PLAY", style = R1.labelMicro, color = R1.AccentCool)
            }
        }
    }
}
