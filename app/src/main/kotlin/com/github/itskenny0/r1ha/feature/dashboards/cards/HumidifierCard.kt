package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Renderer for HA's `humidifier` card. Bound to one `humidifier.*` entity, it
 * surfaces an on/off toggle, the target-humidity stepper, and the
 * available-mode chip row (when the entity advertises `available_modes`).
 *
 * Target humidity arrives via [EntityState.percent] (0..100) and the bounds
 * via [EntityState.minRaw] / [EntityState.maxRaw]; mode + available_modes are
 * read from the raw attributes since they're humidifier-specific. Service
 * calls dispatch through the standard [LovelaceAction.CallService] path so the
 * card stays Compose-pure.
 */
@Composable
fun HumidifierCard(
    card: LovelaceCard.Humidifier,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val eid = safeEntityId(card.entityId)
    val state = eid?.let { stateMap[it] } ?: stateMap.byRaw(card.entityId)
    if (state == null) {
        EntityNotFoundCard(card.entityId, modifier)
        return
    }
    val name = resolveName(card.name, state, card.entityId)
    val isOn = state.isOn
    val accent = if (isOn) R1.AccentWarm else R1.InkSoft
    val target = state?.percent
    val minH = state?.minRaw?.toInt() ?: 0
    val maxH = state?.maxRaw?.toInt() ?: 100
    val step = 5
    val currentHumidity = (state?.attributesJson?.get("current_humidity") as? JsonPrimitive)
        ?.content?.toDoubleOrNull()
    val mode = (state?.attributesJson?.get("mode") as? JsonPrimitive)?.content
    val availableModes = (state?.attributesJson?.get("available_modes") as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.content }
        .orEmpty()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, accent.copy(alpha = 0.4f), R1.ShapeM)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = R1.bodyEmph,
                color = R1.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            // More-info affordance, matching the light card: a tap opens the
            // detail sheet for the humidifier entity.
            MoreInfoDot(
                accent = accent,
                onClick = { onAction(LovelaceAction.Builtin("more-info", card.entityId)) },
            )
            Spacer(Modifier.width(8.dp))
            ModeChip(label = if (isOn) "ON" else "OFF", accent = accent, selected = isOn) {
                onAction(toggleAction(card.entityId, !isOn))
            }
        }
        Spacer(Modifier.height(12.dp))
        // show_current_as_primary swaps the emphasis: the current reading takes
        // the accent (hero) colour and the target reads as the muted secondary.
        // The default keeps the target as the accented value.
        val currentColor = if (card.showCurrentAsPrimary) accent else R1.Ink
        val targetColor = if (card.showCurrentAsPrimary) R1.InkMuted else accent
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (card.showCurrentTemperature) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "CURRENT", style = R1.labelMicro, color = R1.InkMuted)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = currentHumidity?.let { "${fmtTemp(it)}%" } ?: "-",
                        style = R1.numeralM,
                        color = currentColor,
                    )
                }
            }
            if (target != null) {
                StepperButton(label = "−", accent = accent, enabled = isOn) {
                    onAction(setHumidityAction(card.entityId, (target - step).coerceAtLeast(minH)))
                }
                Spacer(Modifier.width(10.dp))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = if (!card.showCurrentTemperature) Modifier.weight(1f) else Modifier,
                ) {
                    Text(text = "TARGET", style = R1.labelMicro, color = R1.InkMuted)
                    Spacer(Modifier.height(2.dp))
                    Text(text = "$target%", style = R1.numeralM, color = targetColor)
                }
                Spacer(Modifier.width(10.dp))
                StepperButton(label = "+", accent = accent, enabled = isOn) {
                    onAction(setHumidityAction(card.entityId, (target + step).coerceAtMost(maxH)))
                }
            } else if (!card.showCurrentTemperature) {
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "TARGET", style = R1.labelMicro, color = R1.InkMuted)
                    Spacer(Modifier.height(2.dp))
                    Text(text = "-", style = R1.numeralM, color = accent)
                }
            }
        }
        if (availableModes.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                availableModes.forEach { m ->
                    ModeChip(
                        label = m.replace('_', ' '),
                        accent = accent,
                        selected = m.equals(mode, ignoreCase = true),
                    ) { onAction(setModeAction(card.entityId, m)) }
                }
            }
        }
        // Card features hosted under the humidifier body, reusing the shared
        // TileFeatureRows slot the thermostat card uses. Only render when at
        // least one feature resolves to a control the entity supports.
        val hasFeatures = card.features.any {
            it !is com.github.itskenny0.r1ha.core.lovelace.LovelaceTileFeature.Unsupported
        }
        if (hasFeatures) {
            Spacer(Modifier.height(10.dp))
            TileFeatureRows(
                features = card.features,
                entityId = card.entityId,
                state = state,
                accent = accent,
                onAction = onAction,
            )
        }
    }
}

private fun toggleAction(entityId: String, on: Boolean): LovelaceAction.CallService =
    LovelaceAction.CallService(
        service = if (on) "humidifier.turn_on" else "humidifier.turn_off",
        entityId = entityId,
        data = null,
    )

private fun setHumidityAction(entityId: String, humidity: Int): LovelaceAction.CallService =
    LovelaceAction.CallService(
        service = "humidifier.set_humidity",
        entityId = entityId,
        data = buildJsonObject { put("humidity", JsonPrimitive(humidity)) },
    )

private fun setModeAction(entityId: String, mode: String): LovelaceAction.CallService =
    LovelaceAction.CallService(
        service = "humidifier.set_mode",
        entityId = entityId,
        data = buildJsonObject { put("mode", JsonPrimitive(mode)) },
    )
