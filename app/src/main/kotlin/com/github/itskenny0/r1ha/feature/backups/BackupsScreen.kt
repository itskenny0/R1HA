package com.github.itskenny0.r1ha.feature.backups

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.github.itskenny0.r1ha.ui.components.R1Button
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1Section
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

private class BackupsViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {
    data class UiState(
        val loading: Boolean = true,
        val backups: List<BackupInfo> = emptyList(),
        val error: String? = null,
        /** True while a backup.create is in flight. Gates the CREATE button so a
         *  double-tap (or a tap on a busy HA instance) can't fire two overlapping
         *  backup jobs, which HA rejects on the second call anyway. */
        val creating: Boolean = false,
        val sort: BackupsLogic.Sort = BackupsLogic.Sort.NEWEST_FIRST,
    ) {
        /** Backups in the order the UI should render them, per [sort]. */
        val sorted: List<BackupInfo> get() = BackupsLogic.sortBackups(backups, sort)
    }

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

    fun cycleSort() {
        val order = BackupsLogic.Sort.entries
        val next = order[(order.indexOf(_ui.value.sort) + 1) % order.size]
        _ui.value = _ui.value.copy(sort = next)
    }

    fun createBackup() {
        if (_ui.value.creating) return
        _ui.value = _ui.value.copy(creating = true)
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
                    _ui.value = _ui.value.copy(creating = false)
                    refresh()
                },
                onFailure = { t ->
                    _ui.value = _ui.value.copy(creating = false)
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
                R1Chip(
                    text = if (ui.loading) "…" else "REFRESH",
                    variant = R1ChipVariant.Action,
                    onClick = { vm.refresh() },
                    contentDescription = "Refresh backup list",
                )
            },
        )
        AdaptiveContent(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.fillMaxSize().padding(R1.space.m)) {
                R1Button(
                    text = if (ui.creating) "CREATING BACKUP…" else "CREATE BACKUP NOW",
                    onClick = { vm.createBackup() },
                    enabled = !ui.creating,
                    accent = R1.AccentWarm,
                    modifier = Modifier.fillMaxWidth(),
                    leadingContent = if (ui.creating) {
                        {
                            CircularProgressIndicator(
                                modifier = Modifier.size(R1.space.l),
                                strokeWidth = 2.dp,
                                color = R1.InkMuted,
                            )
                            Spacer(Modifier.size(R1.space.s))
                        }
                    } else {
                        null
                    },
                )
                Spacer(Modifier.size(R1.space.s))
                Text(
                    text = "Fires backup.create on your HA server. The new backup appears in the list once HA has finished writing it (15-60 s on a typical install).",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
                Spacer(Modifier.size(R1.space.xs))
                when {
                    ui.loading && ui.backups.isEmpty() -> Box(
                        modifier = Modifier.fillMaxWidth().padding(R1.space.xl),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = R1.AccentWarm)
                    }
                    ui.error != null && ui.backups.isEmpty() -> Column {
                        Spacer(Modifier.size(R1.space.m))
                        Text(text = "COULDN'T LOAD BACKUPS", style = R1.labelMicro, color = R1.StatusRed)
                        Spacer(Modifier.size(R1.space.xs))
                        Text(text = ui.error ?: "", style = R1.body, color = R1.InkSoft)
                        Spacer(Modifier.size(R1.space.s))
                        Text(
                            text = "backup/info is HA Core 2024.4+ only. Older releases or installs without the backup integration return empty.",
                            style = R1.labelMicro,
                            color = R1.InkMuted,
                        )
                    }
                    ui.backups.isEmpty() -> Column {
                        Spacer(Modifier.size(R1.space.m))
                        Text(
                            text = "NO BACKUPS YET",
                            style = R1.labelMicro,
                            color = R1.InkSoft,
                        )
                        Spacer(Modifier.size(R1.space.xs))
                        Text(
                            text = "Tap CREATE BACKUP NOW above to take your first one, or schedule automatic backups from Home Assistant's Settings > System > Backups.",
                            style = R1.body,
                            color = R1.InkMuted,
                        )
                    }
                    else -> R1Section(
                        title = "Server backups",
                        count = ui.backups.size,
                        topSpace = R1.space.s,
                        modifier = Modifier.weight(1f),
                        trailing = {
                            R1Chip(
                                text = sortLabel(ui.sort),
                                variant = R1ChipVariant.Action,
                                onClick = { vm.cycleSort() },
                                contentDescription = "Change sort order, currently ${sortLabel(ui.sort).lowercase()}",
                            )
                        },
                    ) {
                        PullToRefreshBox(
                            isRefreshing = ui.loading,
                            onRefresh = { vm.refresh() },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(vertical = R1.space.xs),
                                verticalArrangement = Arrangement.spacedBy(R1.space.xs),
                            ) {
                                items(ui.sorted, key = { it.backupId }) { b ->
                                    BackupRow(b)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun sortLabel(sort: BackupsLogic.Sort): String = when (sort) {
    BackupsLogic.Sort.NEWEST_FIRST -> "NEWEST"
    BackupsLogic.Sort.OLDEST_FIRST -> "OLDEST"
    BackupsLogic.Sort.NAME -> "NAME"
    BackupsLogic.Sort.SIZE_DESC -> "SIZE"
}

@Composable
private fun BackupRow(b: BackupInfo) {
    val meta = remember(b.backupId) {
        val created = BackupsLogic.formatCreatedAt(b.createdAt)
        val relative = BackupsLogic.relativeCreatedAt(b.createdAt)
        val size = BackupsLogic.formatSize(b.sizeBytes)
        val type = BackupsLogic.typeLabel(b.type)
        buildString {
            append(created)
            if (relative != null) {
                append(" (")
                append(relative)
                append(")")
            }
            append(" · ")
            append(size)
            append(" · ")
            append(type)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
        verticalArrangement = Arrangement.spacedBy(R1.space.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = b.name, style = R1.bodyEmph, color = R1.Ink, modifier = Modifier.weight(1f))
            if (b.protected) {
                Text(text = "ENCRYPTED", style = R1.labelMicro, color = R1.AccentCool)
            }
        }
        Text(
            text = meta,
            style = R1.labelMicro,
            color = R1.InkSoft,
        )
        Text(text = b.backupId, style = R1.labelMicro, color = R1.InkMuted, maxLines = 1)
    }
}
