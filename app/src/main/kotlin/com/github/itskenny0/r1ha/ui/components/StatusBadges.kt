package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.prefs.SecondaryInfo
import com.github.itskenny0.r1ha.core.theme.LocalUiOptions
import com.github.itskenny0.r1ha.core.theme.R1

// ---- internal data model -------------------------------------------------------

/**
 * Internal description of one status badge slot. [showBolt] requests a
 * [ChargingBoltGlyph] prefix instead of a plain text-only rendering.
 */
private data class BadgeSpec(
    val label: String,
    val color: Color,
    val contentDesc: String,
    val showBolt: Boolean = false,
)

// ---- public composables -------------------------------------------------------

/**
 * A compact horizontal row of tiny status badges derived from [state].
 *
 * Renders nothing (emits no layout) when no badge signal is present for this
 * entity. Each badge is a small pill: [R1.ShapeS] clip, [R1.SurfaceMuted]
 * background, 1dp [R1.Hairline] border, and [R1.labelMicro] text. Non-interactive;
 * accessibility is provided via [clearAndSetSemantics].
 *
 * Badges produced (in this order, only when their signal is present):
 *  - OFFLINE: entity is unavailable ([EntityState.isAvailable] == false).
 *    Text "OFFLINE" in [R1.StatusAmber].
 *  - BATTERY: `battery_level` attribute is present and numeric.
 *    Text is the level as "<n>%". A [ChargingBoltGlyph] prefix is prepended when
 *    any of `battery_charging == "on"`, `is_charging == "true"`, or
 *    `charging == "on"` is detected. Color is [R1.StatusRed] when the level is
 *    at or below 20 and not charging; [R1.InkSoft] otherwise.
 *  - UPDATE: entity belongs to the `update` domain and [EntityState.isOn] is true
 *    (HA sets the state to `on` when an update is available).
 *    Text "UPDATE" in [R1.AccentWarm].
 */
@Composable
fun StatusBadges(state: EntityState, modifier: Modifier = Modifier) {
    val badges = buildList<BadgeSpec> {
        // OFFLINE
        if (!state.isAvailable) {
            add(
                BadgeSpec(
                    label = "OFFLINE",
                    color = R1.StatusAmber,
                    contentDesc = "Offline",
                ),
            )
        }

        // BATTERY
        val batteryLevel = state.attrString("battery_level")?.toDoubleOrNull()?.toInt()
        if (batteryLevel != null) {
            val isCharging =
                state.attrString("battery_charging") == "on" ||
                    state.attrString("is_charging") == "true" ||
                    state.attrString("charging") == "on"
            val batteryColor = if (batteryLevel <= 20 && !isCharging) R1.StatusRed else R1.InkSoft
            add(
                BadgeSpec(
                    label = "${batteryLevel}%",
                    color = batteryColor,
                    contentDesc = if (isCharging) {
                        "Battery $batteryLevel percent, charging"
                    } else {
                        "Battery $batteryLevel percent"
                    },
                    showBolt = isCharging,
                ),
            )
        }

        // UPDATE AVAILABLE
        if (state.id.domain == Domain.UPDATE && state.isOn) {
            add(
                BadgeSpec(
                    label = "UPDATE",
                    color = R1.AccentWarm,
                    contentDesc = "Update available",
                ),
            )
        }
    }

    if (badges.isEmpty()) return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(R1.space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        badges.forEach { spec ->
            Box(
                modifier = Modifier
                    .clearAndSetSemantics { contentDescription = spec.contentDesc }
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .padding(horizontal = R1.space.xs, vertical = R1.space.xxs),
            ) {
                if (spec.showBolt) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChargingBoltGlyph(size = 8.dp, tint = spec.color)
                        Spacer(Modifier.width(2.dp))
                        Text(text = spec.label, style = R1.labelMicro, color = spec.color)
                    }
                } else {
                    Text(text = spec.label, style = R1.labelMicro, color = spec.color)
                }
            }
        }
    }
}

/**
 * A single-line secondary-info label rendered under a card's main readout.
 *
 * Emits no layout when [kind] is [SecondaryInfo.NONE] or when the backing data is
 * absent for this entity at this moment (null / blank result from [secondaryInfoText]).
 *
 * Text derivation is delegated to [secondaryInfoText], the same pure function used by
 * GlanceData and test harnesses. The [rememberNowTick] ticker drives recomposition so
 * RELATIVE timestamps advance in real time without the caller having to manage a clock.
 *
 * Style: [R1.labelMicro], [R1.InkMuted], single line with ellipsis truncation.
 */
@Composable
fun SecondaryInfoLine(
    state: EntityState,
    kind: SecondaryInfo,
    modifier: Modifier = Modifier,
) {
    if (kind == SecondaryInfo.NONE) return

    val ui = LocalUiOptions.current
    val now by rememberNowTick()
    val use24h = rememberUse24HourClock()
    val text = secondaryInfoText(
        state = state,
        kind = kind,
        now = now,
        timestampStyle = ui.timestampStyle,
        use24h = use24h,
    )

    if (text.isNullOrBlank()) return

    Text(
        text = text,
        style = R1.labelMicro,
        color = R1.InkMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
