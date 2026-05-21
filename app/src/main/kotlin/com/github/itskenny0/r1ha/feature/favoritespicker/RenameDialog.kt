package com.github.itskenny0.r1ha.feature.favoritespicker

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.prefs.EntityOverride
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Button
import com.github.itskenny0.r1ha.ui.components.R1ButtonVariant
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Per-entity customization dialog. Combines the name override + display visibility
 * toggles + long-press action into a single scrollable panel — the user expects a
 * "customize" entry point and the rename + display + gesture options together are it.
 *
 * Each section is independent: NAME drives [EntityOverride] nothing (lives in the
 * separate [com.github.itskenny0.r1ha.core.prefs.AppSettings.nameOverrides] map),
 * DISPLAY toggles the per-card visibility overrides, GESTURE configures the long-press
 * action. Save persists all sections atomically; cancel discards every change.
 *
 * Built from R1 primitives — sharp 2dp slots, hairline borders, monospace mono details
 * — so the customize surface stays inside the dashboard language instead of becoming a
 * generic Material settings page.
 */
@Composable
fun RenameDialog(
    entity: EntityState,
    initialName: String,
    initialOverride: EntityOverride,
    onSave: (name: String, override: EntityOverride) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember(entity.id.value) { mutableStateOf(initialName) }
    var override by remember(entity.id.value) { mutableStateOf(initialOverride) }
    BackHandler(onBack = onCancel)
    val scrollState = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Dim the picker behind so the customize surface reads as a modal. r1Pressable
            // on the backdrop with `hapticOnClick = false` — tapping outside the inner
            // card dismisses without a haptic that might suggest a confirm.
            .background(R1.Bg.copy(alpha = 0.92f))
            .r1Pressable(onClick = onCancel, hapticOnClick = false)
            .systemBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        // Inner panel — block the outer dismiss-on-tap by absorbing the click via its own
        // pressable that does nothing on click. Otherwise tapping the text field's empty
        // padding would dismiss the dialog mid-edit.
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp)
                .clip(R1.ShapeS)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onClick = {}, hapticOnClick = false)
                .padding(16.dp)
                .verticalScroll(scrollState),
        ) {
            // Header — title + entity_id reminder so the user is sure they're editing
            // the right entity (critical when several have similar friendly names).
            Text(text = "CUSTOMIZE", style = R1.sectionHeader, color = R1.AccentWarm)
            Spacer(Modifier.height(2.dp))
            Text(
                text = entity.id.value,
                style = R1.body.copy(fontFamily = FontFamily.Monospace),
                color = R1.InkMuted,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )

            // ── Live preview — the actual EntityCard with the in-progress edits applied
            // so the user sees changes before committing. Both the name and the override
            // map are local CompositionLocals here so the preview reflects the dialog's
            // state, not whatever's in settings.
            Spacer(Modifier.height(12.dp))
            val previewState = remember(entity, name) {
                val effectiveName = name.trim().ifBlank { entity.friendlyName }
                entity.copy(friendlyName = effectiveName)
            }
            androidx.compose.runtime.CompositionLocalProvider(
                com.github.itskenny0.r1ha.core.theme.LocalEntityOverrides provides mapOf(
                    entity.id.value to override,
                ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(R1.ShapeS)
                        .border(1.dp, R1.Hairline, R1.ShapeS),
                ) {
                    com.github.itskenny0.r1ha.ui.components.EntityCard(
                        state = previewState,
                        onTapToggle = { /* preview is non-interactive */ },
                        tapToToggleEnabled = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // ── NAME ─────────────────────────────────────────────────────────────────
            SectionHeader("NAME")
            R1TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = entity.friendlyName,
                monospace = false,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions.Default,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Local-only. Clear to revert to HA's friendly_name.",
                style = R1.body,
                color = R1.InkMuted,
            )

            // ── DISPLAY ──────────────────────────────────────────────────────────────
            SectionHeader("DISPLAY")
            TristateRow(
                label = "Show on/off pill",
                value = override.showOnOffPill,
                onChange = { override = override.copy(showOnOffPill = it) },
            )
            Spacer(Modifier.height(8.dp))
            TristateRow(
                label = "Show area label",
                value = override.showAreaLabel,
                onChange = { override = override.copy(showAreaLabel = it) },
            )

            // ── TEXT SIZE ──────────────────────────────────────────────────────────
            SectionHeader("TEXT SIZE")
            Text(
                text = "Absolute size for the big readout on this card. Smaller sizes help " +
                    "sensors with long text values (RSS headlines, verbose enum states) fit " +
                    "without truncation.",
                style = R1.body,
                color = R1.InkMuted,
            )
            Spacer(Modifier.height(6.dp))
            TextSizeRow(
                selected = override.textSizeSp,
                onSelect = { override = override.copy(textSizeSp = it) },
            )

            // ── COLOUR TEMPERATURE (lights only) ─────────────────────────────────────
            if (entity.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.LIGHT) {
                SectionHeader("LIGHT COLOUR TEMP")
                Text(
                    text = "Apply a fixed colour temperature when this light turns on. Only works on lights that support color_temp_kelvin.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
                Spacer(Modifier.height(6.dp))
                CTRow(
                    selected = override.lightColorTempK,
                    onSelect = { override = override.copy(lightColorTempK = it) },
                )
                // ── LIGHT BUTTONS — show/hide BRIGHT / WHITE / HUE / FX ─────────────
                // Lets the user declutter cards they rarely tweak beyond brightness.
                // Each chip is a toggle; tap to flip visibility. Hiding a button the
                // bulb doesn't support is harmless — it just stays hidden either way.
                SectionHeader("LIGHT BUTTONS")
                Text(
                    text = "Hide controls you don't use on this card. Hiding a button HA already wouldn't render (e.g. HUE on a tunable-white bulb) is a no-op.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
                Spacer(Modifier.height(6.dp))
                LightButtonsRow(
                    hidden = override.lightButtonsHidden,
                    onToggle = { button ->
                        val next = if (button in override.lightButtonsHidden) {
                            override.lightButtonsHidden - button
                        } else {
                            override.lightButtonsHidden + button
                        }
                        override = override.copy(lightButtonsHidden = next)
                    },
                )
            }

            // ── COLOUR ──────────────────────────────────────────────────────────────
            SectionHeader("COLOUR")
            Text(
                text = "Override the card's accent tone. DEFAULT = domain colour.",
                style = R1.body,
                color = R1.InkMuted,
            )
            Spacer(Modifier.height(6.dp))
            ColourSwatchRow(
                selected = override.accentColor,
                onSelect = { override = override.copy(accentColor = it) },
            )

            // ── DECIMALS (sensors only — the option is meaningless on other entities)
            if (entity.id.domain.isSensor) {
                SectionHeader("DECIMALS")
                Text(
                    text = "Trim noisy precise readings. DEFAULT inherits the global setting.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
                Spacer(Modifier.height(6.dp))
                DecimalSegmentedRow(
                    selected = override.maxDecimalPlaces,
                    onSelect = { override = override.copy(maxDecimalPlaces = it) },
                )
            }

            // ── DETAILS — full HA attribute payload, collapsible. Useful for diagnosing
            // MQTT payloads and verifying that specific-field parsers pick up the right
            // values. Defaults to collapsed so it doesn't dominate the dialog; tap the
            // header to expand. The entity state + last_changed sit at the top so the
            // most-useful info is one tap away even without scrolling the attribute list.
            var detailsOpen by remember { mutableStateOf(false) }
            SectionHeader("DETAILS")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (detailsOpen) "▼ TAP TO COLLAPSE" else "▶ TAP TO EXPAND",
                    style = R1.body,
                    color = R1.InkSoft,
                    modifier = Modifier
                        .r1Pressable({ detailsOpen = !detailsOpen })
                        .padding(vertical = 4.dp),
                )
            }
            if (detailsOpen) {
                Spacer(Modifier.height(6.dp))
                DetailRow(label = "state", value = entity.rawState ?: "—")
                DetailRow(label = "last_changed", value = entity.lastChanged.toString())
                entity.attributesJson?.let { attrs ->
                    attrs.entries
                        .sortedBy { it.key }
                        .forEach { (k, v) ->
                            DetailRow(label = k, value = jsonElementToShortString(v))
                        }
                }
            }

            // ── TAP-TO-TOGGLE (per-card override) ────────────────────────────────────
            SectionHeader("TAP TO TOGGLE")
            Text(
                text = "Override the global Behaviour setting for this card. INHERIT follows " +
                    "the Settings switch; ON forces tap-to-toggle on regardless of the global; " +
                    "OFF forces it off (so a casual tap doesn't fire the entity).",
                style = R1.body,
                color = R1.InkMuted,
            )
            Spacer(Modifier.height(6.dp))
            TapToToggleRow(
                selected = override.tapToToggle,
                onSelect = { override = override.copy(tapToToggle = it) },
            )

            // ── WHEEL (per-card override) ────────────────────────────────────────────
            // Whether the scroll wheel drives this specific card. INHERIT picks the
            // per-domain default — select / input_select default OFF (cycling
            // options on every detent was too easy to trigger by accident, and the
            // CHOOSE button is the deliberate path); everything else defaults ON
            // (brightness / volume / setpoint dialled with the wheel is the
            // whole point of having one). Per-card ON / OFF overrides that.
            SectionHeader("WHEEL")
            Text(
                text = "Whether the wheel acts on this card. INHERIT follows the per-domain " +
                    "default (selects default OFF, everything else ON). ON forces the wheel " +
                    "active; OFF disables it so a casual spin can't change the entity.",
                style = R1.body,
                color = R1.InkMuted,
            )
            Spacer(Modifier.height(6.dp))
            TapToToggleRow(
                selected = override.wheelEnabled,
                onSelect = { override = override.copy(wheelEnabled = it) },
            )

            // ── GESTURE ──────────────────────────────────────────────────────────────
            SectionHeader("GESTURE")
            Text(
                text = "Long-press this card to fire another entity. E.g. `scene.movie_night`, `script.bedtime`, `switch.kettle`.",
                style = R1.body,
                color = R1.InkMuted,
            )
            Spacer(Modifier.height(6.dp))
            R1TextField(
                value = override.longPressTarget.orEmpty(),
                onValueChange = { v -> override = override.copy(longPressTarget = v.takeIf { it.isNotBlank() }) },
                placeholder = "scene.movie_night",
                monospace = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { onSave(name, override) },
                ),
            )

            Spacer(Modifier.height(18.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Reset clears every override back to its default — the user gets a fast
                // way to undo experimentation without manually un-picking each section.
                // Stays on the LEFT (the destructive position) so it's not the natural
                // gravity target for a fat-finger tap reaching for SAVE.
                R1Button(
                    text = "RESET",
                    onClick = {
                        name = ""
                        override = EntityOverride.NONE
                    },
                    variant = R1ButtonVariant.Outlined,
                    accent = R1.StatusRed,
                )
                Spacer(Modifier.weight(1f))
                R1Button(
                    text = "CANCEL",
                    onClick = onCancel,
                    variant = R1ButtonVariant.Outlined,
                )
                Spacer(Modifier.width(8.dp))
                R1Button(
                    text = "SAVE",
                    onClick = { onSave(name, override) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Spacer(Modifier.height(16.dp))
    Text(text = title, style = R1.sectionHeader, color = R1.InkSoft)
    Spacer(Modifier.height(2.dp))
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(R1.Hairline))
    Spacer(Modifier.height(8.dp))
}

/**
 * Three-state segmented picker for nullable booleans: DEFAULT (null, inherit global) /
 * SHOW (true, force visible) / HIDE (false, force hidden). The asymmetric labels make
 * the "inherit global setting" semantics easier to read than a plain on/off switch.
 */
@Composable
private fun TristateRow(
    label: String,
    value: Boolean?,
    onChange: (Boolean?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = R1.bodyEmph, color = R1.Ink)
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth().clip(R1.ShapeS).background(R1.SurfaceMuted)) {
            TristateCell(text = "DEFAULT", selected = value == null, onClick = { onChange(null) })
            CellDivider()
            TristateCell(text = "SHOW", selected = value == true, onClick = { onChange(true) })
            CellDivider()
            TristateCell(text = "HIDE", selected = value == false, onClick = { onChange(false) })
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TristateCell(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .background(if (selected) R1.AccentWarm else R1.SurfaceMuted)
            .r1Pressable(onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = R1.labelMicro,
            color = if (selected) R1.Bg else R1.InkSoft,
        )
    }
}

@Composable
private fun CellDivider() {
    Box(modifier = Modifier.width(1.dp).height(34.dp).background(R1.Bg))
}

/**
 * Single attribute key/value row in the customize-dialog DETAILS section. Monospace
 * for both columns (we're showing JSON-shaped data), with the value soft-wrapped so
 * long arrays/dicts don't push the dialog wider than the screen.
 */
@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = R1.labelMicro,
            color = R1.InkMuted,
        )
        Text(
            text = value,
            style = R1.body.copy(fontFamily = FontFamily.Monospace),
            color = R1.Ink,
            maxLines = 4,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/**
 * Compact one-line representation of a JsonElement. Primitives unwrap to their content
 * (strings without surrounding quotes for readability), arrays render as comma-joined
 * elements with brackets, objects collapse to their key count to keep the dialog
 * tractable on big payloads. The full structured form is overkill for at-a-glance
 * diagnostics; users who need the full thing can `adb logcat` the listAll output.
 */
private fun jsonElementToShortString(el: kotlinx.serialization.json.JsonElement): String = when (el) {
    is kotlinx.serialization.json.JsonNull -> "null"
    is kotlinx.serialization.json.JsonPrimitive -> el.content
    is kotlinx.serialization.json.JsonArray -> el.joinToString(prefix = "[", postfix = "]") {
        jsonElementToShortString(it)
    }
    is kotlinx.serialization.json.JsonObject -> "{${el.size} keys}"
}

/** Horizontal-scrolling swatch row for the per-card accent colour. First chip resets to
 *  default (domain colour); the rest are pulled from [EntityOverride.ACCENT_PALETTE]. */
@Composable
private fun ColourSwatchRow(
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    val scroll = androidx.compose.foundation.rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // DEFAULT chip — wider than the swatches so it reads as a text label rather than
        // an unidentified colour-less square.
        Box(
            modifier = Modifier
                .padding(end = 6.dp)
                .clip(R1.ShapeS)
                .background(if (selected == null) R1.AccentWarm else R1.Bg)
                .let { m -> if (selected == null) m else m.border(1.dp, R1.Hairline, R1.ShapeS) }
                .r1Pressable({ onSelect(null) })
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = "DEFAULT",
                style = R1.labelMicro,
                color = if (selected == null) R1.Bg else R1.InkSoft,
            )
        }
        EntityOverride.ACCENT_PALETTE.forEach { (label, argb) ->
            val isSelected = selected == argb
            // Each swatch is a coloured square; selected one gets a hairline accent ring
            // so the choice reads at a glance.
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(28.dp)
                    .clip(R1.ShapeS)
                    .background(androidx.compose.ui.graphics.Color(argb))
                    .let { m ->
                        if (isSelected) m.border(2.dp, R1.Ink, R1.ShapeS)
                        else m
                    }
                    .r1Pressable({ onSelect(argb) }),
            )
            // `label` is unused visually but kept for future "hover label" UX —
            // currently swatch is just colour. Suppress unused with a no-op reference.
            @Suppress("UNUSED_EXPRESSION") label
        }
    }
}

@Composable
private fun CTRow(
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    val scroll = androidx.compose.foundation.rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // DEFAULT chip — HA keeps the last user-set CT.
        Box(
            modifier = Modifier
                .padding(end = 6.dp)
                .clip(R1.ShapeS)
                .background(if (selected == null) R1.AccentWarm else R1.Bg)
                .let { m -> if (selected == null) m else m.border(1.dp, R1.Hairline, R1.ShapeS) }
                .r1Pressable({ onSelect(null) })
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = "DEFAULT",
                style = R1.labelMicro,
                color = if (selected == null) R1.Bg else R1.InkSoft,
            )
        }
        EntityOverride.LIGHT_CT_PRESETS.forEach { (label, kelvin) ->
            val isSelected = selected == kelvin
            // Tinted box approximating the CT colour — warm reads orange-ish, cool reads
            // bluish. Lets the user choose at a glance.
            val tint = ctApproxColor(kelvin)
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .clip(R1.ShapeS)
                    .background(if (isSelected) tint else R1.Bg)
                    .border(1.dp, if (isSelected) tint else R1.Hairline, R1.ShapeS)
                    .r1Pressable({ onSelect(kelvin) })
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "$label · ${kelvin}K",
                    style = R1.labelMicro,
                    color = if (isSelected) R1.Bg else R1.InkSoft,
                )
            }
        }
    }
}

/**
 * Approximate display colour for a kelvin value — quick visual cue in the CT chip row.
 * Not a real blackbody interpolation; just five buckets covering the common 2700–6500
 * range, biased so warm reads orange-amber and cool reads pale blue.
 */
private fun ctApproxColor(kelvin: Int): androidx.compose.ui.graphics.Color = when {
    kelvin <= 2800 -> androidx.compose.ui.graphics.Color(0xFFFF9D5C)
    kelvin <= 3700 -> androidx.compose.ui.graphics.Color(0xFFFFC58A)
    kelvin <= 4500 -> androidx.compose.ui.graphics.Color(0xFFFFE3B6)
    kelvin <= 5800 -> androidx.compose.ui.graphics.Color(0xFFE8EEF7)
    else -> androidx.compose.ui.graphics.Color(0xFFB6CCF0)
}

/**
 * Tri-state row for the per-card [EntityOverride.tapToToggle] override. Three chips:
 * INHERIT (null — follow the global Behaviour setting), ON (true — force the gesture
 * on regardless), OFF (false — force it off so a casual tap doesn't fire the card).
 */
@Composable
private fun TapToToggleRow(
    selected: Boolean?,
    onSelect: (Boolean?) -> Unit,
) {
    val options: List<Pair<String, Boolean?>> = listOf(
        "INHERIT" to null,
        "ON" to true,
        "OFF" to false,
    )
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        options.forEachIndexed { idx, (label, value) ->
            val active = selected == value
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(R1.ShapeS)
                    .background(if (active) R1.AccentWarm else R1.Bg)
                    .let { m ->
                        if (active) m else m.border(1.dp, R1.Hairline, R1.ShapeS)
                    }
                    .r1Pressable({ onSelect(value) })
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = R1.labelMicro,
                    color = if (active) R1.Bg else R1.InkSoft,
                )
            }
            if (idx < options.lastIndex) Spacer(Modifier.width(4.dp))
        }
    }
}

/**
 * Light-card button visibility toggle row. Each of BRIGHT / WHITE / HUE / FX is a
 * chip that highlights when the button is currently SHOWN on the card (the natural
 * mental model — green = visible, grey = hidden — rather than the storage model of
 * "is this in the hidden set"). Tap to flip.
 */
@Composable
private fun LightButtonsRow(
    hidden: Set<com.github.itskenny0.r1ha.core.prefs.LightCardButton>,
    onToggle: (com.github.itskenny0.r1ha.core.prefs.LightCardButton) -> Unit,
) {
    val all = com.github.itskenny0.r1ha.core.prefs.LightCardButton.entries
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        all.forEachIndexed { idx, btn ->
            val visible = btn !in hidden
            val label = when (btn) {
                com.github.itskenny0.r1ha.core.prefs.LightCardButton.BRIGHTNESS -> "BRIGHT"
                com.github.itskenny0.r1ha.core.prefs.LightCardButton.WHITE -> "WHITE"
                com.github.itskenny0.r1ha.core.prefs.LightCardButton.HUE -> "HUE"
                com.github.itskenny0.r1ha.core.prefs.LightCardButton.EFFECTS -> "FX"
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(R1.ShapeS)
                    .background(if (visible) R1.AccentWarm else R1.Bg)
                    .let { m ->
                        if (visible) m else m.border(1.dp, R1.Hairline, R1.ShapeS)
                    }
                    .r1Pressable({ onToggle(btn) })
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = R1.labelMicro,
                    color = if (visible) R1.Bg else R1.InkSoft,
                )
            }
            if (idx < all.lastIndex) Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun TextSizeRow(
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    // Fifteen sp chips + a DEFAULT chip don't fit across the R1's 240 px width as a
    // segmented row, so this is a horizontal-scroll variant. Each chip is its own
    // clickable card with the absolute sp label; the selected chip fills accent.
    // Matches the COLOUR row's swatch styling for visual consistency.
    val scroll = androidx.compose.foundation.rememberScrollState()
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // DEFAULT chip — selected when the override is unset (null). Tap re-selects null
        // to return to the theme default size; visually identical to the sp chips but
        // labelled "DEFAULT" so the user knows where the "no override" position is.
        val defaultSelected = selected == null
        Box(
            modifier = Modifier
                .padding(end = 6.dp)
                .clip(R1.ShapeS)
                .background(if (defaultSelected) R1.AccentWarm else R1.Bg)
                .let { m ->
                    if (defaultSelected) m else m.border(1.dp, R1.Hairline, R1.ShapeS)
                }
                .r1Pressable({ onSelect(null) })
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = "DEFAULT",
                style = R1.labelMicro,
                color = if (defaultSelected) R1.Bg else R1.InkSoft,
            )
        }
        EntityOverride.TEXT_SIZES_SP.forEach { sp ->
            val isSelected = selected == sp
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .clip(R1.ShapeS)
                    .background(if (isSelected) R1.AccentWarm else R1.Bg)
                    .let { m ->
                        if (isSelected) m else m.border(1.dp, R1.Hairline, R1.ShapeS)
                    }
                    .r1Pressable({ onSelect(sp) })
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "${sp}sp",
                    style = R1.labelMicro,
                    color = if (isSelected) R1.Bg else R1.InkSoft,
                )
            }
        }
    }
}

@Composable
private fun DecimalSegmentedRow(
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().clip(R1.ShapeS).background(R1.SurfaceMuted)) {
        TristateCell(text = "DEFAULT", selected = selected == null, onClick = { onSelect(null) })
        CellDivider()
        listOf(0, 1, 2, 3, 4).forEachIndexed { idx, n ->
            TristateCell(
                text = if (n == 0) "INT" else "$n",
                selected = selected == n,
                onClick = { onSelect(n) },
            )
            if (idx < 4) CellDivider()
        }
    }
}

