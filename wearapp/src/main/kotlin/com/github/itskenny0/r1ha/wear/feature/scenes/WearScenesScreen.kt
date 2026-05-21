package com.github.itskenny0.r1ha.wear.feature.scenes

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import kotlinx.coroutines.launch

/**
 * A flat scrollable list of all scene and script entities.
 *
 * Tapping a row activates the scene/script immediately via a fire-and-forget
 * service call. The [ScalingLazyColumn] is the idiomatic Wear OS list container
 * — it applies the watch's round-screen scaling effect so items near the edges
 * of the display shrink gracefully to indicate they're scrollable.
 *
 * Uses `haRepository.listAllEntities()` (one-shot REST call) rather than the
 * streaming `observe()` since scenes have no meaningful live state — they're
 * always "activatable".
 */
@Composable
fun WearScenesScreen(
    haRepository: HaRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var scenes by remember { mutableStateOf<List<EntityState>>(emptyList()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val listState = rememberScalingLazyListState()

    LaunchedEffect(Unit) {
        loading = true
        haRepository.listAllEntities()
            .onSuccess { all ->
                scenes = all.filter { it.id.domain == Domain.SCENE || it.id.domain == Domain.SCRIPT }
                    .sortedBy { it.friendlyName }
                loading = false
            }
            .onFailure { e ->
                errorMsg = e.message ?: "Failed to load scenes"
                loading = false
            }
    }

    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    text = "Scenes",
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                )
            }

            when {
                loading -> item { CircularProgressIndicator() }

                errorMsg != null -> item {
                    Text(
                        text = errorMsg!!,
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.error,
                        textAlign = TextAlign.Center,
                    )
                }

                scenes.isEmpty() -> item {
                    Text(
                        text = "No scenes found",
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                }

                else -> items(scenes) { entity ->
                    Chip(
                        label = {
                            Text(
                                text = entity.friendlyName,
                                maxLines = 1,
                            )
                        },
                        secondaryLabel = {
                            Text(
                                text = if (entity.id.domain == Domain.SCRIPT) "Script" else "Scene",
                                style = MaterialTheme.typography.caption2,
                            )
                        },
                        onClick = {
                            scope.launch {
                                haRepository.call(ServiceCall.tapAction(entity.id, entity.isOn))
                            }
                        },
                        colors = ChipDefaults.primaryChipColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
