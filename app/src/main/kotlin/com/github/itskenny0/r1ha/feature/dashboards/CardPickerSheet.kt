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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.PICKER_TEMPLATES
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.serialization.json.JsonObject

/**
 * Bottom-sheet card picker. Lists every card type R1HA can render
 * natively (see PICKER_TEMPLATES) and inserts a minimal-skeleton
 * config on tap; the caller then opens the regular edit sheet so the
 * user can fill in the entity / title / etc.
 *
 * The grid layout (rather than a list) gives every type roughly equal
 * visual weight, mirroring HA's own card-picker dialog. Selecting a
 * card emits its template JsonObject through [onPick]; the caller is
 * responsible for appending it to the view + opening the editor sheet
 * for the freshly-added card if it wants the user to keep customising
 * (v1 just inserts and dismisses).
 */
@Composable
fun CardPickerSheet(
    onDismiss: () -> Unit,
    onPick: (JsonObject) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.92f))
            .r1Pressable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
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
            Text(text = "ADD CARD", style = R1.sectionHeader, color = R1.AccentWarm)
            Spacer(Modifier.height(10.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(420.dp).fillMaxWidth(),
            ) {
                items(PICKER_TEMPLATES) { (type, template) ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(R1.ShapeM)
                            .background(R1.SurfaceMuted)
                            .border(1.dp, R1.Hairline, R1.ShapeM)
                            .r1Pressable(onClick = { onPick(template) })
                            .padding(horizontal = 12.dp, vertical = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = type.uppercase(), style = R1.bodyEmph, color = R1.Ink)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = describe(type),
                                style = R1.labelMicro,
                                color = R1.InkSoft,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeRound)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeRound)
                        .r1Pressable(onClick = onDismiss)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) { Text("CLOSE", style = R1.labelMicro, color = R1.InkSoft) }
            }
        }
    }
}

private fun describe(type: String): String = when (type) {
    "entities" -> "Vertical entity list"
    "glance" -> "Compact tile grid"
    "tile" -> "Modern one-entity tile"
    "button" -> "Single action button"
    "light" -> "Brightness orb"
    "gauge" -> "Numeric arc"
    "markdown" -> "Markdown body"
    "iframe" -> "Embedded web page"
    "heading" -> "Section heading"
    "weather-forecast" -> "Weather + forecast"
    "vertical-stack" -> "Column of cards"
    "horizontal-stack" -> "Row of cards"
    "grid" -> "Cards in N columns"
    "conditional" -> "Show only when…"
    else -> type
}
