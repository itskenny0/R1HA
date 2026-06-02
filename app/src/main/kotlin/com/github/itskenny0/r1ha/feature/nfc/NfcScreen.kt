package com.github.itskenny0.r1ha.feature.nfc

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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.HaTag
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Button
import com.github.itskenny0.r1ha.ui.components.R1ButtonVariant
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1Section
import com.github.itskenny0.r1ha.ui.components.R1Switch
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent
import kotlinx.coroutines.launch

/**
 * NFC screen: a tester's-eye view of HA's tag registry. Lists every NFC / QR
 * tag HA knows about with its friendly name, raw id, and when it last fired,
 * newest-scan-first. Each row carries a SCAN button that fires the same
 * `tag_scanned` event a real tap would, so a tag-trigger automation can be
 * exercised without the physical tag. A manual field at the top fires an
 * arbitrary id for tags that aren't registered yet.
 *
 * Read-mostly and additive: renaming / deleting tags lives on the dedicated
 * Tags registry editor; this surface focuses on auditing and testing scans.
 */
@Composable
fun NfcScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: NfcViewModel = viewModel(factory = NfcViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    LaunchedEffect(Unit) { vm.refresh() }

    // Per-feature opt-in toggle, mirrored from settings so the screen can both
    // reflect and flip it (same field the Advanced dev-menu switch drives).
    val appSettings by settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scannerEnabled = appSettings.advanced.nfcTagScannerEnabled
    val settingsScope = rememberCoroutineScope()

    // Hardware/system NFC status. Re-read on every resume because the user can
    // toggle NFC in system settings and return without the activity restarting,
    // which would otherwise leave a stale "NFC OFF" / "READY" label here.
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var readerState by remember {
        mutableStateOf(
            activity?.let { NfcReader.readerState(it) } ?: NfcReader.ReaderState.NO_HARDWARE,
        )
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && activity != null) {
                readerState = NfcReader.readerState(activity)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        R1TopBar(
            title = "NFC TAGS",
            onBack = onBack,
            action = {
                R1Chip(
                    text = if (ui.loading) "…" else "REFRESH",
                    variant = R1ChipVariant.Action,
                    onClick = { vm.refresh() },
                    contentDescription = "Refresh tags",
                )
            },
        )
        AdaptiveContent(modifier = Modifier.weight(1f)) {
            when {
                ui.loading && ui.tags.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = R1.AccentWarm,
                    )
                }
                ui.error != null && ui.tags.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(R1.space.xl),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Tag list failed: ${ui.error}",
                        style = R1.body,
                        color = R1.StatusRed,
                    )
                }
                else -> PullToRefreshBox(
                    isRefreshing = ui.loading,
                    onRefresh = { vm.refresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal = R1.space.m,
                            vertical = R1.space.s,
                        ),
                        verticalArrangement = Arrangement.spacedBy(R1.space.xs),
                    ) {
                        item(key = "reader-status") {
                            ReaderStatusCard(
                                readerState = readerState,
                                scannerEnabled = scannerEnabled,
                                onToggle = { enabled ->
                                    settingsScope.launch {
                                        settings.update {
                                            it.copy(
                                                advanced = it.advanced.copy(
                                                    nfcTagScannerEnabled = enabled,
                                                ),
                                            )
                                        }
                                    }
                                },
                            )
                        }
                        item(key = "simulate-scan") {
                            // "…" only when THIS manual id is the one in flight; a
                            // row scan still locks the button (one fire at a time)
                            // but shouldn't make the manual field read as busy.
                            val manualNorm = NfcViewModel.normalizeTagId(ui.manualId)
                            SimulateScanCard(
                                manualId = ui.manualId,
                                busy = ui.firingId != null,
                                firingThis = ui.firingId != null && ui.firingId == manualNorm,
                                onIdChange = { vm.setManualId(it) },
                                onFire = { vm.simulateScan(ui.manualId) },
                            )
                        }
                        if (ui.tags.isEmpty()) {
                            item(key = "empty-tags") {
                                R1Section(title = "REGISTERED TAGS", count = 0) {
                                    Text(
                                        text = "No tags registered yet. Scan an NFC / QR tag at HA " +
                                            "to register it, or fire an id above to test a " +
                                            "tag-trigger automation.",
                                        style = R1.body,
                                        color = R1.InkMuted,
                                        modifier = Modifier.padding(
                                            horizontal = R1.space.l,
                                            vertical = R1.space.m,
                                        ),
                                    )
                                }
                            }
                        } else {
                            item(key = "tags-header") {
                                R1Section(
                                    title = "REGISTERED TAGS",
                                    count = ui.tags.size,
                                    description = "Newest scan first. SCAN fires a tag_scanned " +
                                        "event so a tag-trigger automation runs as if tapped.",
                                ) {}
                            }
                            items(items = ui.tags, key = { it.id }) { tag ->
                                TagRow(
                                    tag = tag,
                                    firing = ui.firingId == NfcViewModel.normalizeTagId(tag.id),
                                    onSimulate = { vm.simulateScan(tag.id) },
                                )
                            }
                        }
                        item(key = "bottom-spacer") { Spacer(Modifier.size(R1.space.xl)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SimulateScanCard(
    manualId: String,
    busy: Boolean,
    firingThis: Boolean,
    onIdChange: (String) -> Unit,
    onFire: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(R1.space.m),
    ) {
        Text(text = "SIMULATE SCAN", style = R1.sectionHeader, color = R1.AccentWarm)
        Spacer(Modifier.size(R1.space.xxs))
        Text(
            text = "Fire a tag_scanned event for any id without the physical tag.",
            style = R1.labelMicro,
            color = R1.InkMuted,
        )
        Spacer(Modifier.size(R1.space.s))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                R1TextField(
                    value = manualId,
                    onValueChange = onIdChange,
                    placeholder = "tag id, e.g. 04a1b2c3",
                    monospace = true,
                )
            }
            Spacer(Modifier.width(R1.space.s))
            R1Button(
                text = if (firingThis) "…" else "FIRE",
                onClick = onFire,
                enabled = !busy && manualId.isNotBlank(),
            )
        }
    }
}

@Composable
private fun TagRow(
    tag: HaTag,
    firing: Boolean,
    onSimulate: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .heightIn(min = R1.MinTarget)
            .padding(horizontal = R1.space.m, vertical = R1.space.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = NfcViewModel.displayName(tag),
                style = R1.bodyEmph,
                color = R1.Ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (tag.lastScanned != null) {
                RelativeTimeLabel(
                    at = tag.lastScanned,
                    color = R1.AccentCool,
                    style = R1.labelMicro,
                )
            } else {
                Text(text = "NEVER SCANNED", style = R1.labelMicro, color = R1.InkMuted)
            }
        }
        if (!tag.name.isNullOrBlank()) {
            Spacer(Modifier.size(R1.space.xxs))
            Text(
                text = tag.id,
                style = R1.labelMicro.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = TextUnit(10f, TextUnitType.Sp),
                ),
                color = R1.InkMuted,
                maxLines = 1,
            )
        }
        if (!tag.description.isNullOrBlank()) {
            Spacer(Modifier.size(R1.space.xs))
            Text(
                text = tag.description,
                style = R1.labelMicro,
                color = R1.InkSoft,
                maxLines = 3,
            )
        }
        Spacer(Modifier.size(R1.space.s))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            // The bare "SCAN" label reads ambiguously to a screen reader once
            // several rows are on screen; name the tag it fires.
            R1Button(
                text = if (firing) "…" else "SCAN",
                onClick = onSimulate,
                enabled = !firing,
                variant = R1ButtonVariant.Outlined,
                modifier = Modifier.semantics {
                    contentDescription = "Simulate scan of ${NfcViewModel.displayName(tag)}"
                },
            )
        }
    }
}

/**
 * Reader status: the device's NFC capability paired with the app's opt-in
 * toggle, collapsed into the four states the user can be in. The toggle is
 * the same `nfcTagScannerEnabled` flag the Advanced settings switch drives,
 * surfaced here so the NFC screen is self-contained: a user who lands here can
 * see why a real tap isn't firing and flip it on without hunting for the
 * setting. Simulate-scan works regardless of these states (it fires the event
 * over the HA connection, not the radio), which the copy makes explicit.
 */
@Composable
private fun ReaderStatusCard(
    readerState: NfcReader.ReaderState,
    scannerEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val noHardware = readerState == NfcReader.ReaderState.NO_HARDWARE
    val statusLabel: String
    val statusColor: Color
    val detail: String
    when {
        noHardware -> {
            statusLabel = "NO NFC HARDWARE"
            statusColor = R1.InkMuted
            detail = "This device has no NFC radio, so it can't read a physical tap. " +
                "Simulate scan below still fires tag_scanned over your HA connection."
        }
        !scannerEnabled -> {
            statusLabel = "READER OFF"
            statusColor = R1.InkSoft
            detail = "The foreground tag reader is off. Turn it on to fire tag_scanned " +
                "when you tap an NFC tag against this device while the app is open."
        }
        readerState == NfcReader.ReaderState.DISABLED -> {
            statusLabel = "NFC TURNED OFF"
            statusColor = R1.StatusAmber
            detail = "The reader is on, but NFC is switched off in system settings. " +
                "Enable NFC there to read physical taps."
        }
        else -> {
            statusLabel = "READER ON"
            statusColor = R1.AccentGreen
            detail = "Tap an NFC tag against this device while the app is open to fire " +
                "its tag_scanned event."
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(R1.space.m),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = R1.MinTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "FOREGROUND READER", style = R1.label, color = R1.InkSoft)
                Spacer(Modifier.size(R1.space.xxs))
                Text(text = statusLabel, style = R1.bodyEmph, color = statusColor)
            }
            if (!noHardware) {
                Spacer(Modifier.width(R1.space.s))
                R1Switch(
                    checked = scannerEnabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.semantics {
                        contentDescription = "Foreground NFC tag reader"
                    },
                )
            }
        }
        Spacer(Modifier.size(R1.space.xs))
        Text(text = detail, style = R1.labelMicro, color = R1.InkMuted)
    }
}

/** Walk the [android.content.ContextWrapper] chain to the hosting Activity, or
 *  null when this composable isn't hosted by one (previews, tests). */
private fun android.content.Context.findActivity(): android.app.Activity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
