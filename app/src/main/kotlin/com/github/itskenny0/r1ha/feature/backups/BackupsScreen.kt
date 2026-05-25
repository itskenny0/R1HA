package com.github.itskenny0.r1ha.feature.backups

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.BackupInfo
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private class BackupsViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {
    data class UiState(
        val loading: Boolean = true,
        val backups: List<BackupInfo> = emptyList(),
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            haRepository.listBackups().fold(
                onSuccess = { backups ->
                    _ui.value = _ui.value.copy(loading = false, backups = backups, error = null)
                    R1Log.i("Backups", "fetched ${backups.size}")
                },
                onFailure = { t ->
                    _ui.value = _ui.value.copy(loading = false, error = t.message)
                },
            )
        }
    }

    fun createBackup() {
        viewModelScope.launch {
            haRepository.callRawService(
                domain = "backup",
                service = "create",
                data = JsonObject(emptyMap()),
            ).fold(
                onSuccess = {
                    Toaster.show("Backup creation started")
                    // Settle delay so the new backup shows up in the next list.
                    kotlinx.coroutines.delay(2_000)
                    refresh()
                },
                onFailure = { t ->
                    Toaster.errorExpandable(
                        shortText = "Backup failed to start",
                        fullText = t.message ?: t.toString(),
                    )
                },
            )
        }
    }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { BackupsViewModel(haRepository) }
        }
    }
}

@Composable
fun BackupsScreen(
    haRepository: HaRepository,
    onBack: () -> Unit,
) {
    val vm: BackupsViewModel = viewModel(factory = BackupsViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(
            title = "BACKUPS",
            onBack = onBack,
            action = {
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .r1Pressable(onClick = { vm.refresh() })
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(text = if (ui.loading) "…" else "REFRESH", style = R1.labelMicro, color = R1.InkSoft)
                }
            },
        )
        AdaptiveContent(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .r1Pressable(onClick = { vm.createBackup() })
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(text = "CREATE BACKUP NOW", style = R1.labelMicro, color = R1.AccentWarm)
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "Fires backup.create on your HA server. The new backup appears in the list once HA has finished writing it (15-60 s on a typical install).",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
                Spacer(Modifier.size(12.dp))
                when {
                    ui.loading && ui.backups.isEmpty() -> Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = R1.AccentWarm)
                    }
                    ui.error != null && ui.backups.isEmpty() -> Column {
                        Text(text = "COULDN'T LOAD BACKUPS", style = R1.labelMicro, color = R1.StatusAmber)
                        Spacer(Modifier.size(4.dp))
                        Text(text = ui.error ?: "", style = R1.body, color = R1.InkSoft)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = "backup/info is HA Core 2024.4+ only. Older releases or installs without the backup integration return empty.",
                            style = R1.labelMicro,
                            color = R1.InkMuted,
                        )
                    }
                    ui.backups.isEmpty() -> Text(
                        text = "(No backups found)",
                        style = R1.body,
                        color = R1.InkMuted,
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(ui.backups, key = { it.backupId }) { b ->
                            BackupRow(b)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupRow(b: BackupInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = b.name, style = R1.bodyEmph, color = R1.Ink, modifier = Modifier.weight(1f))
            if (b.protected) {
                Text(text = "PROTECTED", style = R1.labelMicro, color = R1.AccentCool)
            }
        }
        Text(
            text = buildString {
                if (b.createdAt != null) append(b.createdAt).append(" · ")
                if (b.sizeBytes != null) append(formatSize(b.sizeBytes)).append(" · ")
                append(b.type ?: "manual")
            },
            style = R1.labelMicro,
            color = R1.InkSoft,
        )
        Text(text = b.backupId, style = R1.labelMicro, color = R1.InkMuted, maxLines = 1)
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> String.format("%.1f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
