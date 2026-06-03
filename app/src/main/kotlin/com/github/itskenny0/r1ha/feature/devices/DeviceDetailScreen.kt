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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityRegistryEntry
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1Section
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent
import com.github.itskenny0.r1ha.ui.icons.R1Icons
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
        val totalEntities = detail.totalEntities
        val reporting = detail.reportingEntities
        // Centre + width-cap the detail column on roomy tiers (medium+) so the
        // metadata and entity list read as a tidy centred block instead of one
        // wall-wide line on a tablet / desktop panel; mini / compact fill.
        AdaptiveContent(modifier = Modifier.weight(1f)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = R1.space.m, vertical = R1.space.s),
            verticalArrangement = Arrangement.spacedBy(R1.space.xs),
        ) {
            item(key = "meta") { DeviceMetadata(detail) }
            if (detail.groups.isEmpty()) {
                item(key = "empty") {
                    Column(modifier = Modifier.fillMaxWidth().padding(top = R1.space.xl)) {
                        Text(
                            text = "No entities registered for this device.",
                            style = responsiveType(R1.body),
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
                        content = {
                            // Live state only flows for entities HA is actively
                            // reporting; spell out the coverage so a partially-live
                            // device reads honestly rather than implying the count
                            // is the whole live picture.
                            val coverage = DevicesA11y.reportingCoverageDescription(
                                reporting = reporting,
                                total = totalEntities,
                            )
                            if (coverage != null) {
                                Text(
                                    text = coverage.uppercase(Locale.US),
                                    style = responsiveType(R1.labelMicro),
                                    color = R1.AccentCool,
                                    modifier = Modifier.padding(top = R1.space.xxs),
                                )
                            }
                        },
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
                                isControl = group.isControl,
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
private fun DeviceMetadata(detail: DevicesViewModel.DetailState) {
    val device = detail.device
    Column(modifier = Modifier.fillMaxWidth().padding(top = R1.space.s)) {
        // Status chips: battery / charging / connectivity, mirroring HA's own
        // device page which surfaces a battery icon and dims unavailable
        // entities. Derived from the live-state map for the device's entities.
        val health = remember(detail.groups, detail.liveStates) {
            deviceHealth(detail.groups.flatMap { it.entities }, detail.liveStates)
        }
        val statusChips = buildList {
            if (device.disabledBy != null) add("DISABLED" to R1.StatusAmber)
            health.batteryPercent?.let { pct ->
                val tone = when {
                    pct < 15 -> R1.StatusRed
                    pct < 40 -> R1.StatusAmber
                    else -> R1.AccentGreen
                }
                val bolt = if (health.charging) "+" else ""
                add("BATTERY $pct%$bolt" to tone)
            }
            // Surface availability only when something is actually offline, so a
            // healthy device stays uncluttered.
            if (health.unavailableCount > 0) {
                val tone = if (health.allUnavailable) R1.StatusRed else R1.StatusAmber
                add("${health.unavailableCount} UNAVAILABLE" to tone)
            }
        }
        if (statusChips.isNotEmpty()) {
            val healthSpoken = DevicesA11y.deviceHealthDescription(
                disabled = device.disabledBy != null,
                batteryPercent = health.batteryPercent,
                charging = health.charging,
                unavailableCount = health.unavailableCount,
                liveCount = health.liveCount,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = R1.space.s)
                    .then(
                        if (healthSpoken != null) {
                            Modifier.semantics(mergeDescendants = true) {
                                contentDescription = healthSpoken
                            }
                        } else {
                            Modifier
                        },
                    ),
                horizontalArrangement = Arrangement.spacedBy(R1.space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                statusChips.forEach { (text, tone) ->
                    R1Chip(text = text, variant = R1ChipVariant.Pill, tone = tone)
                }
            }
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
        modifier = Modifier.fillMaxWidth().padding(vertical = R1.space.xxs),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = responsiveType(R1.labelMicro),
            color = R1.InkMuted,
            modifier = Modifier.width(56.dp),
        )
        Spacer(Modifier.width(R1.space.s))
        Text(
            text = value,
            style = responsiveType(R1.body),
            color = R1.InkSoft,
            modifier = Modifier.weight(1f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
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
        val accent = if (group.isControl) R1.AccentWarm else R1.AccentCool
        Icon(
            imageVector = R1Icons.forDomain(group.domain),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(R1.space.s))
        Text(
            text = group.domain.replace('_', ' ').uppercase(Locale.US),
            style = responsiveType(R1.sectionHeader),
            color = accent,
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
private fun EntityDetailRow(
    entity: EntityRegistryEntry,
    live: EntityState?,
    isControl: Boolean,
) {
    val registryDisabled = entity.disabledBy != null || entity.hiddenBy != null
    val muted = registryDisabled || live?.isAvailable == false
    // Control-domain entities (light, switch, climate, ...) are the things the
    // user can act on, so their row is tappable and expands an attribute
    // readout in place. The drill-in itself stays read-only: this surfaces what
    // the entity is reporting without leaving the native app. Read-only sensor
    // rows aren't tappable.
    var expanded by rememberSaveable(entity.entityId) { mutableStateOf(false) }
    val expandable = isControl
    // Spoken state mirrors the visible pill: the live readout when HA reports
    // one, or "no live state" when it doesn't. Tags (platform, disabled, hidden)
    // are folded into the merged label so they are announced, not just shown.
    val stateSpoken = if (live == null) "no live state" else liveStateLabel(live)
    val rowTags = remember(entity.platform, entity.disabledBy, entity.hiddenBy, expandable) {
        buildList {
            entity.platform?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (entity.disabledBy != null) add("disabled")
            if (entity.hiddenBy != null) add("hidden")
            if (expandable) add("double tap to expand")
        }
    }
    val rowDescription = DevicesA11y.entityRowDescription(
        name = entity.displayName,
        entityId = entity.entityId,
        stateSpoken = stateSpoken,
        tags = rowTags,
    )
    val iconTint = when {
        muted -> R1.InkMuted
        live?.isOn == true -> R1.AccentGreen
        isControl -> R1.AccentWarm
        else -> R1.InkSoft
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .then(
                if (expandable) {
                    Modifier.r1Pressable(
                        onClick = { expanded = !expanded },
                        contentDescription = null,
                    )
                } else {
                    Modifier
                },
            )
            .clearAndSetSemantics { contentDescription = rowDescription }
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = R1Icons.forEntity(
                    entityId = entity.entityId,
                    deviceClass = live?.deviceClass,
                    state = live?.rawState,
                ),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(R1.space.s))
            Text(
                text = entity.displayName,
                style = responsiveType(R1.bodyEmph),
                color = if (muted) R1.InkMuted else R1.Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(R1.space.s))
            EntityStatePill(entity = entity, live = live)
            if (expandable) {
                Spacer(Modifier.width(R1.space.s))
                Text(
                    text = if (expanded) "v" else ">",
                    style = responsiveType(R1.labelMicro),
                    color = R1.InkSoft,
                )
            }
        }
        Text(
            text = entity.entityId,
            style = responsiveType(R1.labelMicro),
            color = R1.InkSoft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = R1.space.xxs),
        )
        val tags = remember(entity.platform, entity.disabledBy, entity.hiddenBy) {
            buildList {
                entity.platform?.takeIf { it.isNotBlank() }?.let { add(it.uppercase(Locale.US)) }
                if (entity.disabledBy != null) add("DISABLED")
                if (entity.hiddenBy != null) add("HIDDEN")
            }
        }
        if (tags.isNotEmpty()) {
            Text(
                text = tags.joinToString(" : "),
                style = responsiveType(R1.labelMicro),
                color = R1.InkMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = R1.space.xxs),
            )
        }
        if (expandable && expanded) {
            ExpandedEntityDetail(entity = entity, live = live)
        }
    }
}

/**
 * Inline attribute readout for an expanded control entity: device-class and
 * area from the registry/live state plus a short list of the live attributes
 * HA carries (unit, hvac mode, current option, ...). Read-only, like the rest
 * of the drill-in: it answers "what is this entity reporting right now?".
 */
@Composable
private fun ExpandedEntityDetail(entity: EntityRegistryEntry, live: EntityState?) {
    val lines = remember(live, entity.areaId) {
        buildList {
            live?.deviceClass?.takeIf { it.isNotBlank() }?.let { add("CLASS" to it) }
            live?.unit?.takeIf { it.isNotBlank() }?.let { add("UNIT" to it) }
            live?.currentOption?.takeIf { it.isNotBlank() }?.let { add("OPTION" to it) }
            live?.climateHvacMode?.takeIf { it.isNotBlank() }?.let { add("MODE" to it) }
            live?.mediaTitle?.takeIf { it.isNotBlank() }?.let { add("PLAYING" to it) }
            live?.area?.takeIf { it.isNotBlank() }?.let { add("AREA" to it) }
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(top = R1.space.xs)) {
        if (lines.isEmpty()) {
            Text(
                text = if (live == null) "No live attributes reported." else "No extra attributes.",
                style = responsiveType(R1.labelMicro),
                color = R1.InkMuted,
            )
        } else {
            lines.forEach { (label, value) ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = R1.space.xxs)) {
                    Text(
                        text = label,
                        style = responsiveType(R1.labelMicro),
                        color = R1.InkMuted,
                        modifier = Modifier.width(56.dp),
                    )
                    Spacer(Modifier.width(R1.space.s))
                    Text(
                        text = value,
                        style = responsiveType(R1.labelMicro),
                        color = R1.InkSoft,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun EntityStatePill(entity: EntityRegistryEntry, live: EntityState?) {
    if (live == null) {
        // The registry knows this entity but it isn't in the live state
        // set: disabled, or its integration isn't currently reporting.
        Text(text = "no live state", style = responsiveType(R1.labelMicro), color = R1.InkMuted)
        return
    }
    val text = liveStateLabel(live)
    val tone = when {
        !live.isAvailable -> R1.StatusAmber
        live.isOn -> R1.AccentGreen
        else -> R1.InkSoft
    }
    Text(text = text, style = responsiveType(R1.labelMicro), color = tone, maxLines = 1)
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
