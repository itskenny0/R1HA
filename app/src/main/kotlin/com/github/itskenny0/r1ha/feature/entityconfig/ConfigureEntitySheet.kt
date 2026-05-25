package com.github.itskenny0.r1ha.feature.entityconfig

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.R
import com.github.itskenny0.r1ha.core.ha.AreaInfo
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.util.Toaster
import com.github.itskenny0.r1ha.ui.components.R1Button
import com.github.itskenny0.r1ha.ui.components.R1ButtonVariant
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.coroutines.launch

/**
 * Reusable dialog for renaming an entity and assigning it to an area, both
 * server-side via `config/entity_registry/update`. Distinct from the local
 * [com.github.itskenny0.r1ha.core.prefs.EntityOverride] flow — this one
 * touches HA's source-of-truth registry, so the rename + area assignment
 * propagate to every other client (HA frontend, Companion app, voice
 * assistant addressing).
 *
 * Opens with the entity's current friendly name pre-filled and the area
 * picker chips populated from HA's area registry. A NEW AREA affordance
 * lets the user create + assign in a single flow when their target area
 * doesn't exist yet.
 *
 * Pure-UI; the caller is responsible for closing the sheet on [onDismiss]
 * (or [onSaved], which is fired with the new state after a successful
 * persist).
 */
@Composable
fun ConfigureEntitySheet(
    haRepository: HaRepository,
    entityId: String,
    initialName: String,
    initialAreaId: String? = null,
    onDismiss: () -> Unit,
    onSaved: (name: String, areaId: String?) -> Unit = { _, _ -> },
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initialName) }
    var areaId by remember { mutableStateOf(initialAreaId) }
    var areas by remember { mutableStateOf<List<AreaInfo>?>(null) }
    var inFlight by remember { mutableStateOf(false) }
    var createMode by remember { mutableStateOf(false) }
    var newAreaName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(entityId) {
        haRepository.listAreas().fold(
            onSuccess = { areas = it },
            onFailure = { t -> error = "Couldn't load areas: ${t.message}" },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = R1.Bg,
        title = { Text(text = "CONFIGURE ENTITY", style = R1.sectionHeader, color = R1.Ink) },
        text = {
            Column {
                Text(text = entityId, style = R1.labelMicro, color = R1.InkMuted, maxLines = 1)
                Spacer(Modifier.height(10.dp))
                Text(text = "NAME", style = R1.labelMicro, color = R1.InkSoft)
                Spacer(Modifier.height(4.dp))
                R1TextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "Living room ceiling light",
                )
                Spacer(Modifier.height(10.dp))
                Text(text = "AREA", style = R1.labelMicro, color = R1.InkSoft)
                Spacer(Modifier.height(4.dp))
                if (areas == null && error == null) {
                    Text(text = "Loading…", style = R1.labelMicro, color = R1.InkMuted)
                } else if (areas != null) {
                    val list = areas!!
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // (none) chip — explicit affordance to clear area
                        AreaChip(
                            label = "(none)",
                            active = areaId.isNullOrBlank(),
                            onClick = { areaId = ""; createMode = false },
                        )
                        for (a in list) {
                            AreaChip(
                                label = a.name,
                                active = a.areaId == areaId,
                                onClick = { areaId = a.areaId; createMode = false },
                            )
                        }
                        // NEW AREA chip toggles a small inline input.
                        AreaChip(
                            label = "+ NEW",
                            active = createMode,
                            accent = R1.AccentWarm,
                            onClick = { createMode = !createMode },
                        )
                    }
                    if (createMode) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.width(160.dp)) {
                                R1TextField(
                                    value = newAreaName,
                                    onValueChange = { newAreaName = it },
                                    placeholder = "Workshop",
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(R1.ShapeS)
                                    .background(R1.SurfaceMuted)
                                    .border(1.dp, R1.Hairline, R1.ShapeS)
                                    .r1Pressable(onClick = {
                                        val n = newAreaName.trim()
                                        if (n.isBlank()) return@r1Pressable
                                        scope.launch {
                                            haRepository.createArea(n).fold(
                                                onSuccess = { created ->
                                                    areas = (areas.orEmpty() + created)
                                                        .sortedBy { it.name.lowercase() }
                                                    areaId = created.areaId
                                                    newAreaName = ""
                                                    createMode = false
                                                    Toaster.show("Area '${created.name}' created")
                                                },
                                                onFailure = { t ->
                                                    error = "Create failed: ${t.message ?: "unknown"}"
                                                },
                                            )
                                        }
                                    })
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(text = "CREATE", style = R1.labelMicro, color = R1.AccentWarm)
                            }
                        }
                    }
                }
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(text = error ?: "", style = R1.labelMicro, color = R1.StatusAmber)
                }
            }
        },
        confirmButton = {
            R1Button(
                text = if (inFlight) "SAVING…" else stringResource(R.string.dialog_save),
                enabled = !inFlight,
                onClick = {
                    inFlight = true
                    error = null
                    scope.launch {
                        haRepository.updateEntityRegistry(
                            entityId = entityId,
                            name = name.trim(),
                            // areaId == null means "user hasn't picked anything";
                            // areaId == "" means "explicitly clear". Pass through.
                            areaId = areaId,
                        ).fold(
                            onSuccess = {
                                Toaster.show("Saved")
                                onSaved(name.trim(), areaId)
                                onDismiss()
                            },
                            onFailure = { t ->
                                error = t.message ?: "Save failed"
                                inFlight = false
                            },
                        )
                    }
                },
            )
        },
        dismissButton = {
            R1Button(
                text = stringResource(R.string.dialog_cancel),
                variant = R1ButtonVariant.Outlined,
                onClick = onDismiss,
            )
        },
    )
}

@Composable
private fun AreaChip(
    label: String,
    active: Boolean,
    accent: androidx.compose.ui.graphics.Color = R1.AccentCool,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(R1.ShapeS)
            .background(if (active) accent.copy(alpha = 0.18f) else R1.SurfaceMuted)
            .border(
                1.dp,
                if (active) accent.copy(alpha = 0.6f) else R1.Hairline,
                R1.ShapeS,
            )
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = R1.labelMicro,
            color = if (active) accent else R1.Ink,
        )
    }
}
