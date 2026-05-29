package com.github.itskenny0.r1ha.feature.devices

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityRegistryEntry
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1Section
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import java.util.Locale

/**
 * Device drill-in: device metadata up top (area, maker, model, firmware,
 * parent hub when set) followed by the device's entities grouped by domain
 * with controls floated above read-only sensors. Each entity shows its live
 * state when HA is reporting one; entities the live state set doesn't carry
 * are labelled honestly rather than shown as blank.
 *
 * Read-only, like the rest of the Devices surface: this answers "what does
 * this device expose and what's it doing right now?" without leaving the
 * native app.
 */
@Composable
fun DeviceDetailScreen(
    detail: DevicesViewModel.DetailState,
    listState: LazyListState,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(
            title = detail.device.displayName.uppercase(),
            onBack = onBack,
        )
        val totalEntities = detail.groups.sumOf { it.entities.size }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "meta") { DeviceMetadata(detail) }
            if (detail.groups.isEmpty()) {
                item(key = "empty") {
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                        Text(
                            text = "No entities registered for this device.",
                            style = R1.body,
                            color = R1.InkMuted,
                        )
                    }
                }
            } else {
                item(key = "entities-header") {
                    R1Section(
                        title = "Entities",
                        count = totalEntities,
                        topSpace = R1.space.l,
                        content = {},
                    )
                }
                for (group in detail.groups) {
                    item(key = "group/${group.domain}") {
                        DomainHeader(group = group)
                    }
                    for (entity in group.entities) {
                        item(key = "entity/${entity.entityId}") {
                            EntityDetailRow(
                                entity = entity,
                                live = detail.liveStates[entity.entityId],
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceMetadata(detail: DevicesViewModel.DetailState) {
    val device = detail.device
    Column(modifier = Modifier.fillMaxWidth().padding(top = R1.space.s)) {
        if (device.disabledBy != null) {
            R1Chip(text = "DISABLED", variant = R1ChipVariant.Pill, tone = R1.StatusAmber)
            Spacer(Modifier.height(R1.space.s))
        }
        MetaRow(label = "AREA", value = detail.areaName)
        MetaRow(label = "MAKER", value = device.manufacturer)
        MetaRow(label = "MODEL", value = device.model)
        MetaRow(label = "SW", value = device.swVersion)
        MetaRow(label = "HW", value = device.hwVersion)
        MetaRow(
            label = "PARENT",
            value = detail.parent?.displayName ?: device.viaDeviceId,
        )
        MetaRow(label = "CONFIG", value = device.configurationUrl)
        device.identifiers.forEach { (domain, value) ->
            MetaRow(label = "IDENT", value = "$domain:$value")
        }
        device.connections.forEach { (type, value) ->
            MetaRow(label = "CONN", value = "$type:$value")
        }
        MetaRow(label = "ID", value = device.id)
    }
}

@Composable
private fun MetaRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = R1.labelMicro,
            color = R1.InkMuted,
            modifier = Modifier.width(56.dp),
        )
        Spacer(Modifier.width(R1.space.s))
        Text(
            text = value,
            style = R1.body,
            color = R1.InkSoft,
            modifier = Modifier.weight(1f),
            maxLines = 3,
        )
    }
}

@Composable
private fun DomainHeader(group: DeviceEntityGroup) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = R1.space.s, bottom = R1.space.xs, start = R1.space.xs, end = R1.space.xs)
            // Domain group title is a TalkBack heading so users can jump between
            // the entity groups; the count pill is folded into the spoken label.
            .semantics(mergeDescendants = true) {
                heading()
                val noun = if (group.entities.size == 1) "entity" else "entities"
                contentDescription =
                    "${group.domain.replace('_', ' ')}, ${group.entities.size} $noun"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.domain.replace('_', ' ').uppercase(Locale.US),
            style = R1.sectionHeader,
            color = if (group.isControl) R1.AccentWarm else R1.AccentCool,
        )
        Spacer(Modifier.width(R1.space.m))
        Box(
            modifier = Modifier
                .height(1.dp)
                .weight(1f)
                .background(R1.Hairline),
        )
        Spacer(Modifier.width(R1.space.s))
        R1Chip(text = "${group.entities.size}", variant = R1ChipVariant.Pill, tone = R1.InkSoft)
    }
}

@Composable
private fun EntityDetailRow(entity: EntityRegistryEntry, live: EntityState?) {
    val registryDisabled = entity.disabledBy != null || entity.hiddenBy != null
    val muted = registryDisabled || live?.isAvailable == false
    // Spoken state mirrors the visible pill: the live readout when HA reports
    // one, or "no live state" when it doesn't. Tags (platform, disabled, hidden)
    // are folded into the merged label so they are announced, not just shown.
    val stateSpoken = if (live == null) "no live state" else liveStateLabel(live)
    val rowTags = buildList {
        entity.platform?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (entity.disabledBy != null) add("disabled")
        if (entity.hiddenBy != null) add("hidden")
    }
    val rowDescription = DevicesA11y.entityRowDescription(
        name = entity.displayName,
        entityId = entity.entityId,
        stateSpoken = stateSpoken,
        tags = rowTags,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .clearAndSetSemantics { contentDescription = rowDescription }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entity.displayName,
                style = R1.bodyEmph,
                color = if (muted) R1.InkMuted else R1.Ink,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(R1.space.s))
            EntityStatePill(entity = entity, live = live)
        }
        Text(
            text = entity.entityId,
            style = R1.labelMicro,
            color = R1.InkSoft,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
        val tags = buildList {
            entity.platform?.takeIf { it.isNotBlank() }?.let { add(it.uppercase(Locale.US)) }
            if (entity.disabledBy != null) add("DISABLED")
            if (entity.hiddenBy != null) add("HIDDEN")
        }
        if (tags.isNotEmpty()) {
            Text(
                text = tags.joinToString(" : "),
                style = R1.labelMicro,
                color = R1.InkMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun EntityStatePill(entity: EntityRegistryEntry, live: EntityState?) {
    if (live == null) {
        // The registry knows this entity but it isn't in the live state
        // set: disabled, or its integration isn't currently reporting.
        Text(text = "no live state", style = R1.labelMicro, color = R1.InkMuted)
        return
    }
    val text = liveStateLabel(live)
    val tone = when {
        !live.isAvailable -> R1.StatusAmber
        live.isOn -> R1.AccentGreen
        else -> R1.InkSoft
    }
    Text(text = text, style = R1.labelMicro, color = tone, maxLines = 1)
}

/**
 * Compact one-line state readout for the detail row: the raw HA state with
 * its unit when one exists ("21.4 °C", "playing"), falling back to a plain
 * on/off word. Locale.US for the numeric path so the decimal separator is
 * stable regardless of device locale.
 */
internal fun liveStateLabel(live: EntityState): String {
    if (!live.isAvailable) return "unavailable"
    val raw = live.rawState?.takeIf { it.isNotBlank() }
    val unit = live.unit?.takeIf { it.isNotBlank() }
    return when {
        raw != null && unit != null -> "$raw $unit"
        raw != null -> raw
        live.percent != null -> String.format(Locale.US, "%d%%", live.percent)
        live.isOn -> "on"
        else -> "off"
    }
}
