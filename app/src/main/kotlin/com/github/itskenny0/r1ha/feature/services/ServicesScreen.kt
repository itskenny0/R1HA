package com.github.itskenny0.r1ha.feature.services

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.HaService
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.util.Toaster
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Services Browser — every service HA exposes via /api/services, with
 * substring search and per-domain expansion. Tap a service to copy
 * "<domain>.<service>" to the clipboard so the user can paste it
 * straight into the Service Caller.
 *
 * Read-only — no dispatch from this surface. The Service Caller is
 * the right place to actually fire; this is the discovery + reference
 * companion.
 */
@Composable
fun ServicesScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: ServicesViewModel = viewModel(factory = ServicesViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    LaunchedEffect(Unit) { vm.refresh() }
    var expandedDomain by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        R1TopBar(title = "SERVICES", onBack = onBack)
        com.github.itskenny0.r1ha.ui.layout.AdaptiveContent(modifier = Modifier.weight(1f)) {
        SearchBar(query = ui.query, onQueryChange = { vm.setQuery(it) })
        when {
            ui.loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = R1.AccentWarm,
                )
            }
            ui.error != null && ui.domains.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Service registry fetch failed — surface the real error
                // rather than the "no services" fallback so the user
                // doesn't think their HA install is empty.
                Text(
                    text = "Services load failed: ${ui.error}",
                    style = R1.body,
                    color = R1.StatusRed,
                )
            }
            ui.domains.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (ui.query.isNotBlank()) "No matches for '${ui.query}'."
                    else "No services reported by HA.",
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
                        horizontal = 12.dp, vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (domain in ui.domains) {
                        item(key = domain.domain) {
                            DomainRow(
                                domain = domain.domain,
                                count = domain.services.size,
                                expanded = (expandedDomain == domain.domain) || ui.query.isNotBlank(),
                                onToggle = {
                                    expandedDomain = if (expandedDomain == domain.domain) null else domain.domain
                                },
                            )
                        }
                        if ((expandedDomain == domain.domain) || ui.query.isNotBlank()) {
                            for (svc in domain.services) {
                                item(key = "${domain.domain}.${svc.name}") {
                                    ServiceRow(
                                        domain = domain.domain,
                                        service = svc,
                                        onCopy = {
                                            val fqn = "${domain.domain}.${svc.name}"
                                            clipboard.setText(AnnotatedString(fqn))
                                            Toaster.show("Copied $fqn")
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        } // AdaptiveContent
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "FIND", style = R1.labelMicro, color = R1.InkMuted, modifier = Modifier.padding(end = 8.dp))
        Box(modifier = Modifier.weight(1f)) {
            R1TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "turn_on, reload, …",
                monospace = true,
            )
        }
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier.size(48.dp).r1Pressable({ onQueryChange("") }),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "✕", style = R1.labelMicro, color = R1.InkSoft)
            }
        }
    }
}

@Composable
private fun DomainRow(domain: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = domain.uppercase(), style = R1.body, color = R1.AccentWarm, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(text = "$count", style = R1.labelMicro, color = R1.InkSoft)
        Spacer(Modifier.width(6.dp))
        Text(text = if (expanded) "▾" else "▸", style = R1.labelMicro, color = R1.InkSoft)
    }
}

@Composable
private fun ServiceRow(domain: String, service: HaService, onCopy: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .clip(R1.ShapeS)
            .background(R1.Bg)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onCopy)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$domain.${service.name}",
                style = R1.body,
                color = R1.Ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Text(text = "COPY", style = R1.labelMicro, color = R1.InkSoft)
        }
        if (!service.description.isNullOrBlank()) {
            Text(
                text = service.description,
                style = R1.labelMicro,
                color = R1.InkSoft,
                maxLines = 3,
            )
        }
        if (service.fieldNames.isNotEmpty()) {
            Text(
                text = "fields: ${service.fieldNames.joinToString(", ")}",
                style = R1.labelMicro,
                color = R1.InkMuted,
                maxLines = 2,
            )
        }
    }
}
