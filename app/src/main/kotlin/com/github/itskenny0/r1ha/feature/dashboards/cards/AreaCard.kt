package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.LocalHaBearerToken
import com.github.itskenny0.r1ha.core.theme.LocalHaRepository
import com.github.itskenny0.r1ha.core.theme.LocalHaServerUrl
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.ui.components.AsyncBitmap
import com.github.itskenny0.r1ha.ui.components.HuiImage
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Renderer for HA's `area` card. Resolves the area's member entities from the
 * area registry through the shared [rememberAreaMembers] resolver (one template
 * call shared across every area card on the dashboard), then renders HA's area
 * surface: an optional picture / camera header, a sensor summary secondary line,
 * orange alert chips for active alert binary_sensors, and the area's controls.
 *
 * The config's own `entities:` list still renders when present; otherwise the
 * resolved members fill the body (compact rows).
 */
@Composable
fun AreaCard(
    card: LovelaceCard.Area,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = LocalHaRepository.current
    val members = rememberAreaMembers(repo, card.area, card.excludeEntities)

    val title = card.name?.takeUnless { it.isBlank() }
        ?: card.area.replace('_', ' ').replaceFirstChar { it.uppercase() }
    val accent = haColorAccent(card.color)

    // The card's tap_action (a navigation_path folds into it at parse time).
    val actions = resolveCardActions(
        tapAction = card.tapAction,
        holdAction = card.holdAction,
        doubleTapAction = card.doubleTapAction,
        // No card entity: a null tap stays null (inert) rather than more-info.
        cardEntityId = null,
    )

    // Summary + alerts come off the resolved member states (HA's pipeline).
    val summary = areaSensorSummary(card.sensorClasses, members.states, members.deviceKeyOf)
    val alertClasses = card.alertClasses.map { it.lowercase() }.toSet()
    val activeAlerts = areaActiveAlertClasses(members.states, alertClasses)

    // display_type: camera / picture (legacy show_camera == camera).
    val display = (card.displayType ?: if (card.showCamera) "camera" else null)?.lowercase()
    val firstCamera = members.states.firstOrNull { it.id.value.startsWith("camera.") }?.id?.value

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, accent?.copy(alpha = 0.4f) ?: R1.Hairline, R1.ShapeM)
            .r1CardActions(actions = actions, onAction = onAction, contentDescription = title),
    ) {
        AreaHeader(card, display, firstCamera)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = R1.bodyEmph,
                color = accent ?: R1.Ink,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (summary != null) {
                Spacer(Modifier.width(10.dp))
                Text(text = summary, style = R1.labelMicro, color = R1.InkSoft, maxLines = 1)
            }
        }

        // Alert chips: one orange pill per active alert class.
        if (activeAlerts.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                activeAlerts.forEach { cls ->
                    StateChip(text = cls.replace('_', ' '), accent = R1.AccentWarm)
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // The config's explicit entities, else the resolved compact member rows.
        val rows = card.entities
        if (rows.isNotEmpty()) {
            rows.forEach { row ->
                val eid = safeEntityId(row.entityId)
                val state = eid?.let { stateMap[it] }
                AreaEntityRow(
                    name = resolveName(row.name, state, row.entityId),
                    state = state,
                    entityId = row.entityId,
                    onAction = { onAction(defaultTapAction(row.entityId)) },
                )
            }
        } else if (members.states.isNotEmpty() && display != "camera" && display != "picture") {
            // A compact list of the area's controllable members (cap the count so
            // a busy area doesn't run the card off-screen).
            members.states
                .filter { areaMemberIsControl(it) }
                .take(6)
                .forEach { state ->
                    AreaEntityRow(
                        name = state.friendlyName,
                        state = state,
                        entityId = state.id.value,
                        onAction = { onAction(defaultTapAction(state.id.value)) },
                    )
                }
        }

        // Card features target the area's first matching entity (HA renders the
        // feature row against an area-derived entity).
        if (card.features.isNotEmpty()) {
            val featureState = members.states.firstOrNull { areaMemberIsControl(it) }
            if (featureState != null) {
                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Column {
                        TileFeatureRows(
                            features = card.features,
                            entityId = featureState.id.value,
                            state = featureState,
                            accent = accent ?: R1.AccentWarm,
                            onAction = onAction,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}

/** Optional picture / camera header for the area card. Picture uses the
 *  configured [LovelaceCard.Area.image]; camera shows the area's first camera. */
@Composable
private fun AreaHeader(
    card: LovelaceCard.Area,
    display: String?,
    firstCamera: String?,
) {
    when {
        display == "camera" && firstCamera != null -> {
            HuiImage(
                imageUrl = null,
                cameraEntityId = firstCamera,
                cameraView = card.cameraView,
                aspectRatioStr = card.aspectRatio ?: "16:9",
                modifier = Modifier.fillMaxWidth(),
                contentDescription = null,
            )
        }
        !card.image.isNullOrBlank() -> {
            AsyncBitmap(
                url = card.image,
                serverUrl = LocalHaServerUrl.current,
                bearerToken = LocalHaBearerToken.current,
                modifier = Modifier.fillMaxWidth().height(96.dp),
                contentDescription = null,
            )
        }
    }
}

/** One compact entity row inside the area card body. */
@Composable
private fun AreaEntityRow(
    name: String,
    state: EntityState?,
    entityId: String,
    onAction: () -> Unit,
) {
    val accent = stateAccentFor(entityId, state)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .r1Pressable(onClick = onAction)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = R1.body,
            color = R1.Ink,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val stateText = state?.let(::compactStateText)?.takeUnless { it.isBlank() }
        if (stateText != null) {
            Spacer(Modifier.width(10.dp))
            StateChip(text = stateText, accent = accent)
        }
    }
}

/**
 * Whether an area member is a control worth surfacing in the compact body / as
 * the features target. Lights, switches, covers, fans, locks, climate, media
 * players and the input-control domains are actionable; bare sensors are
 * summarised on the secondary line rather than listed individually.
 */
internal fun areaMemberIsControl(state: EntityState): Boolean = when (state.id.domain) {
    Domain.LIGHT, Domain.SWITCH, Domain.FAN, Domain.COVER, Domain.LOCK,
    Domain.MEDIA_PLAYER, Domain.CLIMATE, Domain.HUMIDIFIER, Domain.VALVE,
    Domain.INPUT_BOOLEAN -> true
    else -> false
}
