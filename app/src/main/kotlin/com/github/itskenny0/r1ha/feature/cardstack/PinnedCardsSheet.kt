package com.github.itskenny0.r1ha.feature.cardstack

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.parseCardJsonBlob
import com.github.itskenny0.r1ha.core.prefs.FavoritePage
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.feature.dashboards.CardEditSheet
import com.github.itskenny0.r1ha.feature.dashboards.CardPickerSheet
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Management surface for a page's pinned Lovelace cards: list, reorder,
 * edit (raw JSON via the dashboards CardEditSheet), delete, and add — either
 * from the native card-type picker (which now includes iframe) or any pasted
 * config. Works on the RAW stored strings so an unparseable card still shows
 * up here for repair instead of vanishing with the deck's lenient parse.
 *
 * Saves are immediate (each mutation calls [onSave] with the new list): on a
 * 640x480 panel a separate save step is one tap too many, and every mutation
 * here is individually cheap to undo.
 */
@Composable
internal fun PinnedCardsSheet(
    page: FavoritePage,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val cards = remember(page.id) { page.pinnedCards.toMutableStateList() }
    var pickerOpen by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    fun persist() = onSave(cards.toList())

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
                .padding(horizontal = 14.dp, vertical = 24.dp)
                .clip(R1.ShapeM)
                .background(R1.Surface)
                .border(1.dp, R1.AccentWarm.copy(alpha = 0.5f), R1.ShapeM)
                .padding(14.dp)
                .r1Pressable(onClick = {}),
        ) {
            Text(
                text = "PINNED CARDS · ${page.name.uppercase()}",
                style = R1.sectionHeader,
                color = R1.AccentWarm,
            )
            Spacer(Modifier.height(4.dp))
            if (page.favorites.isNotEmpty()) {
                Text(
                    text = "This page shows its favourites; pinned cards render on pages without any.",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
                Spacer(Modifier.height(4.dp))
            }
            if (cards.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No cards pinned yet. ADD CARD installs the first one.",
                        style = R1.body,
                        color = R1.InkSoft,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 260.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(cards, key = { idx, _ -> "pin-$idx" }) { idx, blob ->
                        PinnedCardRow(
                            index = idx,
                            count = cards.size,
                            blob = blob,
                            onMoveUp = {
                                if (idx > 0) {
                                    cards.add(idx - 1, cards.removeAt(idx)); persist()
                                }
                            },
                            onMoveDown = {
                                if (idx < cards.lastIndex) {
                                    cards.add(idx + 1, cards.removeAt(idx)); persist()
                                }
                            },
                            onEdit = { editingIndex = idx },
                            onDelete = { cards.removeAt(idx); persist() },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SheetButton(label = "ADD CARD", accent = true) { pickerOpen = true }
                Spacer(Modifier.width(8.dp))
                SheetButton(label = "CLOSE", accent = false, onClick = onDismiss)
            }
        }
    }

    if (pickerOpen) {
        CardPickerSheet(
            onDismiss = { pickerOpen = false },
            onPick = { template ->
                pickerOpen = false
                cards.add(template.toString())
                persist()
                // Drop straight into the editor so the skeleton gets its url /
                // entity filled in while the user's intent is fresh.
                editingIndex = cards.lastIndex
            },
        )
    }
    editingIndex?.let { idx ->
        val initial = cards.getOrNull(idx)?.let { parseCardJsonBlob(it) }
        if (initial == null) {
            editingIndex = null
        } else {
            CardEditSheet(
                initial = initial,
                onDismiss = { editingIndex = null },
                onSave = { edited: JsonObject ->
                    cards[idx] = edited.toString()
                    persist()
                    editingIndex = null
                },
            )
        }
    }
}

@Composable
private fun PinnedCardRow(
    index: Int,
    count: Int,
    blob: String,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val parsed = remember(blob) { parseCardJsonBlob(blob) }
    val typeLabel = parsed?.get("type")?.jsonPrimitive?.content?.uppercase()?.replace('-', ' ')
        ?: "INVALID JSON"
    val detail = remember(parsed) { rowDetail(parsed) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.SurfaceMuted)
            .border(1.dp, if (parsed == null) R1.StatusRed.copy(alpha = 0.5f) else R1.Hairline, R1.ShapeM)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = typeLabel,
                style = R1.bodyEmph,
                color = if (parsed == null) R1.StatusRed else R1.Ink,
            )
            if (detail.isNotBlank()) {
                Text(text = detail, style = R1.labelMicro, color = R1.InkSoft, maxLines = 1)
            }
        }
        if (index > 0) RowGlyph("▲", onMoveUp)
        if (index < count - 1) RowGlyph("▼", onMoveDown)
        RowGlyph("EDIT", onEdit)
        RowGlyph("✕", onDelete, danger = true)
    }
}

/** One-line identifying detail under the type label: url, entity, or title. */
private fun rowDetail(parsed: JsonObject?): String {
    if (parsed == null) return "tap EDIT to repair"
    for (key in listOf("url", "entity", "title", "content", "heading")) {
        val v = runCatching { parsed[key]?.jsonPrimitive?.content }.getOrNull()
        if (!v.isNullOrBlank()) return v
    }
    return ""
}

@Composable
private fun RowGlyph(label: String, onClick: () -> Unit, danger: Boolean = false) {
    Box(
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(R1.ShapeS)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = R1.labelMicro,
            color = if (danger) R1.StatusRed else R1.InkSoft,
        )
    }
}

@Composable
private fun SheetButton(label: String, accent: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(R1.ShapeRound)
            .background(R1.SurfaceMuted)
            .border(
                1.dp,
                if (accent) R1.AccentWarm.copy(alpha = 0.6f) else R1.Hairline,
                R1.ShapeRound,
            )
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = R1.labelMicro,
            color = if (accent) R1.AccentWarm else R1.InkSoft,
        )
    }
}
