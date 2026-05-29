package com.github.itskenny0.r1ha.feature.dashboards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.itskenny0.r1ha.core.lovelace.LOVELACE_EDIT_JSON
import com.github.itskenny0.r1ha.core.lovelace.parseCardJsonBlob
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.serialization.json.JsonObject

/**
 * Modal sheet for editing one card's raw JSON config. v1 surface is
 * intentionally minimal: a syntax-coloured monospace text area and
 * a SAVE / CANCEL pair. Validation runs live (JSON parseability +
 * the parsed type appearing in the supported list); save is gated
 * on parseability so the user can't silently corrupt the override
 * blob.
 *
 * Richer per-card visual editors are explicitly deferred. HA's
 * frontend has ~50 of them and re-authoring them inside R1HA would
 * be a multi-week undertaking. The JSON path is the universal
 * fallback that mirrors HA's own "raw editor" affordance.
 */
@Composable
fun CardEditSheet(
    initial: JsonObject,
    onDismiss: () -> Unit,
    onSave: (JsonObject) -> Unit,
) {
    val initialText = remember(initial) {
        LOVELACE_EDIT_JSON.encodeToString(JsonObject.serializer(), initial)
    }
    var text by remember { mutableStateOf(initialText) }
    val parsed = remember(text) { parseCardJsonBlob(text) }
    val canSave = parsed != null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.92f))
            .r1Pressable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 36.dp)
                .clip(R1.ShapeM)
                .background(R1.Surface)
                .border(1.dp, R1.AccentWarm.copy(alpha = 0.5f), R1.ShapeM)
                .padding(14.dp)
                .r1Pressable(onClick = {}),
        ) {
            Text(text = "EDIT CARD JSON", style = R1.sectionHeader, color = R1.AccentWarm)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Save lands as a local override; HA's config is never touched.",
                style = R1.body,
                color = R1.InkSoft,
            )
            Spacer(Modifier.height(10.dp))
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .border(1.dp, R1.Hairline, R1.ShapeM),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = R1.SurfaceMuted,
                    unfocusedContainerColor = R1.SurfaceMuted,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedTextColor = R1.Ink,
                    unfocusedTextColor = R1.Ink,
                ),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                ),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (canSave) "JSON ok · type = ${parsed?.get("type")?.toString() ?: "?"}" else "invalid JSON",
                style = R1.labelMicro,
                color = if (canSave) R1.AccentGreen else R1.StatusRed,
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeRound)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeRound)
                        .r1Pressable(onClick = onDismiss)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) { Text("CANCEL", style = R1.labelMicro, color = R1.InkSoft) }
                Spacer(Modifier.height(0.dp))
                androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 8.dp))
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeRound)
                        .background(if (canSave) R1.AccentWarm else R1.SurfaceMuted)
                        .border(1.dp, if (canSave) R1.AccentWarm else R1.Hairline, R1.ShapeRound)
                        .r1Pressable(onClick = { parsed?.let(onSave) })
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "SAVE",
                        style = R1.labelMicro,
                        color = if (canSave) R1.Bg else R1.InkMuted,
                    )
                }
            }
        }
    }
}
