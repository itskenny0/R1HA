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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
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
            // The R1's portrait screen is tiny; with the area chips, the inline
            // create field, and an error line all visible at once the body can
            // exceed the dialog's height, so make it scroll rather than clip.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text = entityId, style = R1.labelMicro, color = R1.InkMuted, maxLines = 1)
                Spacer(Modifier.height(R1.space.s))
                Text(text = "NAME", style = R1.labelMicro, color = R1.InkSoft)
                Spacer(Modifier.height(R1.space.xs))
                R1TextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "Living room ceiling light",
                )
                // Empty name resets the entity to its integration-provided default
                // name (HA's entity_registry behaviour), so it's allowed; flag it
                // softly rather than blocking save.
                if (name.isBlank()) {
                    Spacer(Modifier.height(R1.space.xxs))
                    Text(
                        text = "Leave blank to use the default name",
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                    )
                }
                Spacer(Modifier.height(R1.space.s))
                Text(text = "AREA", style = R1.labelMicro, color = R1.InkSoft)
                Spacer(Modifier.height(R1.space.xs))
                if (areas == null && error == null) {
                    Text(text = "Loading…", style = R1.labelMicro, color = R1.InkMuted)
                } else if (areas != null) {
                    val list = areas!!
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
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
                            contentDescription = "Create a new area",
                            onClick = { createMode = !createMode },
                        )
                    }
                    // Loaded but no areas defined yet: nudge the user toward + NEW
                    // so the empty chip row isn't a dead end.
                    if (list.isEmpty() && !createMode) {
                        Spacer(Modifier.height(R1.space.xs))
                        Text(
                            text = "No areas yet. Tap + NEW to create one.",
                            style = R1.labelMicro,
                            color = R1.InkMuted,
                        )
                    }
                    if (createMode) {
                        Spacer(Modifier.height(R1.space.xs))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.width(160.dp)) {
                                R1TextField(
                                    value = newAreaName,
                                    onValueChange = { newAreaName = it },
                                    placeholder = "Workshop",
                                )
                            }
                            Spacer(Modifier.width(R1.space.s))
                            val trimmedNew = newAreaName.trim()
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .heightIn(min = R1.MinTarget)
                                    .clip(R1.ShapeS)
                                    .background(R1.SurfaceMuted)
                                    .border(1.dp, R1.Hairline, R1.ShapeS)
                                    .r1Pressable(
                                        contentDescription = "Create area",
                                        onClick = {
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
                                        },
                                    )
                                    .padding(horizontal = R1.space.m, vertical = R1.space.s),
                            ) {
                                Text(
                                    text = "CREATE",
                                    style = R1.labelMicro,
                                    // Dim the action until there's a name to submit so
                                    // a blank tap reads as a no-op, not a broken button.
                                    color = if (trimmedNew.isBlank()) R1.InkMuted else R1.AccentWarm,
                                )
                            }
                        }
                    }
                }
                if (error != null) {
                    Spacer(Modifier.height(R1.space.s))
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
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            // Meet the 48dp minimum tappable height even though the chip text is
            // short; the visual chip stays compact via wrapContentHeight, so the
            // extra hit area is invisible padding around it.
            .heightIn(min = R1.MinTarget)
            .clip(R1.ShapeS)
            .background(if (active) accent.copy(alpha = 0.18f) else R1.SurfaceMuted)
            .border(
                1.dp,
                if (active) accent.copy(alpha = 0.6f) else R1.Hairline,
                R1.ShapeS,
            )
            .r1Pressable(onClick = onClick, contentDescription = contentDescription)
            .padding(horizontal = R1.space.m, vertical = R1.space.xs),
    ) {
        Text(
            text = label,
            style = R1.labelMicro,
            color = if (active) accent else R1.Ink,
            modifier = Modifier.wrapContentHeight(),
        )
    }
}
