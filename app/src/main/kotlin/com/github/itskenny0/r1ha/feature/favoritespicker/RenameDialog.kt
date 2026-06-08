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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import com.github.itskenny0.r1ha.core.prefs.PositionDotLocation
import com.github.itskenny0.r1ha.core.prefs.TapAction
import com.github.itskenny0.r1ha.core.prefs.ValueBarLocation
import com.github.itskenny0.r1ha.core.prefs.positionDotLocationLabel
import com.github.itskenny0.r1ha.core.prefs.valueBarLocationLabel
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Button
import com.github.itskenny0.r1ha.ui.components.R1ButtonVariant
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Per-entity customization dialog — the "CUSTOMIZE" surface that long-pressing
 * a card opens. Modelled after Home Assistant's hui-tile-card editor plus the
 * settings-screen NavRow drill-in pattern: the root view shows a live preview
 * card plus a list of nested submenu rows (IDENTITY / LAYOUT / ACTIONS /
 * BEHAVIOUR / ...), each of which drills into a focused subscreen. Save commits
 * every section at once; cancel discards every change.
 *
 * Submenus surface every override on [EntityOverride] plus the name override
 * (which lives in [com.github.itskenny0.r1ha.core.prefs.AppSettings.nameOverrides]
 * for back-compat with the rename-only era). Each submenu's NavRow shows a
 * "modified" indicator and a value summary so the user can spot which sections
 * already carry overrides without drilling into each one.
 *
 * Built from R1 primitives — sharp 2dp slots, hairline borders, monospace mono
 * details — so the customize surface stays inside the dashboard language
 * instead of becoming a generic Material settings page.
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
    // Which subscreen is open; null = root (the hub). Back gesture pops one
    // level: subscreen → root, root → cancel. The single source of truth is
    // this var, so the BackHandler stack stays simple.
    var subscreen by remember(entity.id.value) { mutableStateOf<CustomizeSubscreen?>(null) }
    BackHandler(enabled = subscreen != null) { subscreen = null }
    BackHandler(enabled = subscreen == null, onBack = onCancel)

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Dim the picker behind so the customize surface reads as a modal. r1Pressable
            // on the backdrop with `hapticOnClick = false` — tapping outside the inner
            // card dismisses without a haptic that might suggest a confirm. Cancel
            // from the root; pop a subscreen back to the hub from inside one.
            .background(R1.Bg.copy(alpha = 0.92f))
            .r1Pressable(
                onClick = { if (subscreen != null) subscreen = null else onCancel() },
                hapticOnClick = false,
            )
            .systemBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        // Inner panel — block the outer dismiss-on-tap by absorbing the click via its own
        // pressable that does nothing on click. Otherwise tapping inside the panel's
        // padding would dismiss the dialog mid-edit.
        val scrollState = rememberScrollState(0)
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = R1.space.l, vertical = R1.space.l)
                .clip(R1.ShapeS)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onClick = {}, hapticOnClick = false)
                .padding(16.dp)
                .verticalScroll(scrollState),
        ) {
            if (subscreen == null) {
                CustomizeHub(
                    entity = entity,
                    name = name,
                    override = override,
                    onDrill = { subscreen = it },
                    onNameChange = { name = it },
                    onOverrideChange = { override = it },
                    onResetAll = {
                        name = ""
                        override = EntityOverride.NONE
                    },
                    onCancel = onCancel,
                    onSave = { onSave(name, override) },
                )
            } else {
                CustomizeSubscreenHost(
                    entity = entity,
                    subscreen = subscreen!!,
                    override = override,
                    onChange = { override = it },
                    onBack = { subscreen = null },
                )
            }
        }
    }
}

/**
 * Submenu identifier. Each entry maps to one drill-in subscreen rendered by
 * [CustomizeSubscreenHost]. Adding a new submenu means appending here, adding
 * a NavRow on the hub, and a branch in the host.
 */
private enum class CustomizeSubscreen {
    IDENTITY, LAYOUT, ACTIONS, BEHAVIOUR, LOCK, LIGHTING, POSITION, ADVANCED,
}

/**
 * Root view of the customize dialog — header, live preview, a NavRow per
 * submenu showing whether it's been modified and a one-line value summary,
 * and the SAVE / CANCEL / RESET footer.
 */
@Composable
private fun CustomizeHub(
    entity: EntityState,
    name: String,
    override: EntityOverride,
    onDrill: (CustomizeSubscreen) -> Unit,
    onNameChange: (String) -> Unit,
    onOverrideChange: (EntityOverride) -> Unit,
    onResetAll: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    // Header — title + entity_id reminder so the user is sure they're editing
    // the right entity (critical when several have similar friendly names).
    Text(text = "CUSTOMIZE", style = R1.sectionHeader, color = R1.AccentWarm)
    Spacer(Modifier.height(R1.space.xxs))
    Text(
        text = entity.id.value,
        style = R1.body.copy(fontFamily = FontFamily.Monospace),
        color = R1.InkMuted,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )

    // ── Live preview — the actual EntityCard with the in-progress edits
    // applied so the user sees changes before committing. The name + override
    // map are local CompositionLocals here so the preview reflects the
    // dialog's state, not whatever's in settings.
    Spacer(Modifier.height(R1.space.m))
    val previewState = remember(entity, name) {
        val effectiveName = name.trim().ifBlank { entity.friendlyName }
        entity.copy(friendlyName = effectiveName)
    }
    // Keyed on entity id + override only — NOT on `name`. The hub recomposes on
    // every keystroke into the inline name field, and rebuilding this map there
    // would hand CompositionLocalProvider a fresh instance each time, needlessly
    // re-invalidating every LocalEntityOverrides reader in the EntityCard preview
    // subtree even though the override map is unchanged. The preview's name still
    // updates live via [previewState].
    val previewOverrides = remember(entity.id.value, override) {
        mapOf(entity.id.value to override)
    }
    androidx.compose.runtime.CompositionLocalProvider(
        com.github.itskenny0.r1ha.core.theme.LocalEntityOverrides provides previewOverrides,
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

    Spacer(Modifier.height(14.dp))
    SectionHeader("SECTIONS")

    // Per-submenu NavRow. value text is a one-line summary of the section's
    // current state ("ON · 28sp" etc.); the orange dot to the left signals
    // "this section carries an override". Tap drills in.
    CustomizeNavRow(
        label = "Identity",
        modified = isIdentityModified(name, override),
        value = identitySummary(name, override),
        onClick = { onDrill(CustomizeSubscreen.IDENTITY) },
    )
    CustomizeNavRow(
        label = "Layout",
        modified = isLayoutModified(override),
        value = layoutSummary(entity, override),
        onClick = { onDrill(CustomizeSubscreen.LAYOUT) },
    )
    CustomizeNavRow(
        label = "Actions",
        modified = isActionsModified(override),
        value = actionsSummary(override),
        onClick = { onDrill(CustomizeSubscreen.ACTIONS) },
    )
    CustomizeNavRow(
        label = "Behaviour",
        modified = isBehaviourModified(override),
        value = behaviourSummary(override),
        onClick = { onDrill(CustomizeSubscreen.BEHAVIOUR) },
    )
    // LOCK section only surfaces on lock entities — the PIN gate is
    // meaningless anywhere else; the user shouldn't have to scroll past it
    // on every customize visit to a light.
    if (entity.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.LOCK) {
        CustomizeNavRow(
            label = "Lock",
            modified = override.requirePinToUnlock == true || !override.requirePinHash.isNullOrBlank(),
            value = when {
                override.requirePinToUnlock != true -> "PIN gate off"
                override.requirePinHash.isNullOrBlank() -> "ANY DIGITS"
                else -> "PIN set"
            },
            onClick = { onDrill(CustomizeSubscreen.LOCK) },
        )
    }
    // LIGHTING section only on light entities — colour temperature + per-card
    // hidden-button toggles only make sense for bulbs.
    if (entity.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.LIGHT) {
        CustomizeNavRow(
            label = "Lighting",
            modified = override.lightColorTempK != null || override.lightButtonsHidden.isNotEmpty()
                || override.favoriteColors.isNotEmpty(),
            value = lightingSummary(override),
            onClick = { onDrill(CustomizeSubscreen.LIGHTING) },
        )
    }
    // POSITION section only on cover / valve entities — favourite positions.
    if (entity.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.COVER ||
        entity.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.VALVE) {
        CustomizeNavRow(
            label = "Position",
            modified = override.favoritePositions.isNotEmpty(),
            value = if (override.favoritePositions.isEmpty()) "No favourites"
                    else override.favoritePositions.joinToString { "$it%" },
            onClick = { onDrill(CustomizeSubscreen.POSITION) },
        )
    }
    CustomizeNavRow(
        label = "Advanced",
        modified = false,
        value = "Details, debug",
        onClick = { onDrill(CustomizeSubscreen.ADVANCED) },
    )

    Spacer(Modifier.height(18.dp))
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // RESET clears every override AND the name override back to default
        // — the global undo for users experimenting with the customize
        // surface. Stays on the LEFT (the destructive position) so it's
        // not the natural gravity target for a fat-finger tap reaching for
        // SAVE.
        R1Button(
            text = "RESET",
            onClick = onResetAll,
            variant = R1ButtonVariant.Outlined,
            accent = R1.StatusRed,
        )
        Spacer(Modifier.weight(1f))
        R1Button(text = "CANCEL", onClick = onCancel, variant = R1ButtonVariant.Outlined)
        Spacer(Modifier.width(R1.space.s))
        R1Button(text = "SAVE", onClick = onSave)
    }

    // The hub also lets the user edit the friendly name inline — it's the
    // single most-frequent customize action and burying it behind an extra
    // drill-in would be slower than the pre-refactor flow. The IDENTITY
    // subscreen still owns the field so the user can also reach it from
    // there; the inline copy here just shares the same `name` state.
    Spacer(Modifier.height(18.dp))
    SectionHeader("QUICK · NAME")
    R1TextField(
        value = name,
        onValueChange = onNameChange,
        placeholder = entity.friendlyName,
        monospace = false,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions.Default,
    )
    Spacer(Modifier.height(R1.space.xs))
    Text(
        text = "Local-only. Clear to revert to HA's friendly_name.",
        style = R1.body,
        color = R1.InkMuted,
    )
    @Suppress("UNUSED_EXPRESSION") onOverrideChange
}

/**
 * Hosts each drill-in subscreen, picking the right Column to render based on
 * [subscreen]. Each subscreen renders its own back header + reset chip; the
 * outer scroll container is shared with the hub so a tall subscreen scrolls
 * cleanly without nesting scrollers.
 */
@Composable
private fun CustomizeSubscreenHost(
    entity: EntityState,
    subscreen: CustomizeSubscreen,
    override: EntityOverride,
    onChange: (EntityOverride) -> Unit,
    onBack: () -> Unit,
) {
    SubscreenHeader(
        title = when (subscreen) {
            CustomizeSubscreen.IDENTITY -> "IDENTITY"
            CustomizeSubscreen.LAYOUT -> "LAYOUT"
            CustomizeSubscreen.ACTIONS -> "ACTIONS"
            CustomizeSubscreen.BEHAVIOUR -> "BEHAVIOUR"
            CustomizeSubscreen.LOCK -> "LOCK"
            CustomizeSubscreen.LIGHTING -> "LIGHTING"
            CustomizeSubscreen.POSITION -> "POSITION"
            CustomizeSubscreen.ADVANCED -> "ADVANCED"
        },
        entityId = entity.id.value,
        onBack = onBack,
    )

    when (subscreen) {
        CustomizeSubscreen.IDENTITY -> IdentitySubscreen(entity, override, onChange)
        CustomizeSubscreen.LAYOUT -> LayoutSubscreen(entity, override, onChange)
        CustomizeSubscreen.ACTIONS -> ActionsSubscreen(entity, override, onChange)
        CustomizeSubscreen.BEHAVIOUR -> BehaviourSubscreen(entity, override, onChange)
        CustomizeSubscreen.LOCK -> LockSubscreen(entity, override, onChange)
        CustomizeSubscreen.LIGHTING -> LightingSubscreen(entity, override, onChange)
        CustomizeSubscreen.POSITION -> PositionSubscreen(entity, override, onChange)
        CustomizeSubscreen.ADVANCED -> AdvancedSubscreen(entity, override, onChange)
    }
}

// ── Subscreen: IDENTITY ──────────────────────────────────────────────────────
/**
 * NAME · GLYPH · COLOUR overrides. Identity is what makes one card
 * recognisable at a glance from the rest of the deck — friendly name, the
 * emoji / symbol that replaces the domain glyph, and the accent colour
 * painted across the chip / suffix / switch thumb.
 */
@Composable
private fun IdentitySubscreen(
    entity: EntityState,
    override: EntityOverride,
    onChange: (EntityOverride) -> Unit,
) {
    SectionHeader("NAME")
    // The user lands here without the inline NAME field that the hub shows
    // so they can edit just the name in isolation — but the name override
    // lives outside [EntityOverride] (in AppSettings.nameOverrides) so we
    // can't surface it from here without threading another callback. The
    // hub already exposes the inline picker; reflect that here so the user
    // doesn't think the field is missing.
    Text(
        text = "Name lives on the QUICK NAME field on the root customize view (back chevron above).",
        style = R1.body,
        color = R1.InkMuted,
    )

    SectionHeader("GLYPH")
    Text(
        text = "Replace the domain glyph (LIGHT / SWITCH / FAN / etc.) with a single emoji or short symbol. Empty = inherit the default.",
        style = R1.body,
        color = R1.InkMuted,
    )
    Spacer(Modifier.height(R1.space.s))
    GlyphPickerRow(
        selected = override.glyphOverride,
        onSelect = { onChange(override.copy(glyphOverride = it)) },
    )
    Spacer(Modifier.height(R1.space.xs))
    R1TextField(
        value = override.glyphOverride.orEmpty(),
        onValueChange = { v -> onChange(override.copy(glyphOverride = v.takeIf { it.isNotBlank() })) },
        placeholder = "Custom emoji or symbol",
        monospace = false,
    )

    SectionHeader("COLOUR")
    Text(
        text = "Override the card's accent tone. DEFAULT = domain colour.",
        style = R1.body,
        color = R1.InkMuted,
    )
    Spacer(Modifier.height(R1.space.s))
    ColourSwatchRow(
        selected = override.accentColor,
        onSelect = { onChange(override.copy(accentColor = it)) },
    )
    RowResetChip(
        onReset = {
            onChange(
                override.copy(
                    glyphOverride = null,
                    accentColor = null,
                )
            )
        },
    )
    @Suppress("UNUSED_EXPRESSION") entity
}

// ── Subscreen: LAYOUT ────────────────────────────────────────────────────────
/**
 * POSITION PIP · PILLS · TEXT SIZE · DECIMALS. Visual layout of the card body
 * rather than the values it shows or how it behaves under interaction.
 */
@Composable
private fun LayoutSubscreen(
    entity: EntityState,
    override: EntityOverride,
    onChange: (EntityOverride) -> Unit,
) {
    SectionHeader("POSITION PIP")
    Text(
        text = "Override where this card's 'you are here' pip sits when the card is active. INHERIT follows the global setting.",
        style = R1.body,
        color = R1.InkMuted,
    )
    Spacer(Modifier.height(R1.space.s))
    PositionDotOverridePicker(
        selected = override.positionDotLocation,
        onSelect = { onChange(override.copy(positionDotLocation = it)) },
    )

    SectionHeader("VALUE BAR")
    Text(
        text = "Override which edge this card's brightness / volume / setpoint slider sits on, or hide it. INHERIT follows the global setting.",
        style = R1.body,
        color = R1.InkMuted,
    )
    Spacer(Modifier.height(R1.space.s))
    ValueBarOverridePicker(
        selected = override.valueBarLocation,
        onSelect = { onChange(override.copy(valueBarLocation = it)) },
    )

    SectionHeader("VISIBILITY")
    TristateRow(
        label = "Show on/off pill",
        value = override.showOnOffPill,
        onChange = { onChange(override.copy(showOnOffPill = it)) },
    )
    Spacer(Modifier.height(R1.space.s))
    TristateRow(
        label = "Show area label",
        value = override.showAreaLabel,
        onChange = { onChange(override.copy(showAreaLabel = it)) },
    )
    Spacer(Modifier.height(R1.space.s))
    TristateRow(
        label = "Ultra-detail view",
        value = override.moreInfoEnabled,
        onChange = { onChange(override.copy(moreInfoEnabled = it)) },
    )

    SectionHeader("TEXT SIZE")
    Text(
        text = "Absolute size for the big readout on this card. Smaller sizes help sensors with long text values (RSS headlines, verbose enum states) fit without truncation.",
        style = R1.body,
        color = R1.InkMuted,
    )
    Spacer(Modifier.height(R1.space.s))
    TextSizeRow(
        selected = override.textSizeSp,
        onSelect = { onChange(override.copy(textSizeSp = it)) },
    )

    if (entity.id.domain.isSensor) {
        SectionHeader("DECIMALS")
        Text(
            text = "Trim noisy precise readings. DEFAULT inherits the global setting.",
            style = R1.body,
            color = R1.InkMuted,
        )
        Spacer(Modifier.height(R1.space.s))
        DecimalSegmentedRow(
            selected = override.maxDecimalPlaces,
            onSelect = { onChange(override.copy(maxDecimalPlaces = it)) },
        )
    }
    RowResetChip(
        onReset = {
            onChange(
                override.copy(
                    positionDotLocation = null,
                    valueBarLocation = null,
                    showOnOffPill = null,
                    showAreaLabel = null,
                    moreInfoEnabled = null,
                    textSizeSp = null,
                    maxDecimalPlaces = null,
                )
            )
        },
    )
}

// ── Subscreen: ACTIONS ───────────────────────────────────────────────────────
/**
 * ACTION-ON-TAP · ACTION-ON-LONG-PRESS · ACTION-ON-WHEEL-PRESS · CUSTOM
 * BUTTONS. Everything the user can do *to* the card surface itself.
 */
@Composable
private fun ActionsSubscreen(
    entity: EntityState,
    override: EntityOverride,
    onChange: (EntityOverride) -> Unit,
) {
    SectionHeader("ACTION ON TAP")
    Text(
        text = "What happens on a single tap of the card body. INHERIT = the card's default for its domain.",
        style = R1.body,
        color = R1.InkMuted,
    )
    Spacer(Modifier.height(R1.space.s))
    TapActionPicker(
        selected = override.actionOnTap,
        onSelect = { onChange(override.copy(actionOnTap = it)) },
    )

    SectionHeader("ACTION ON LONG-PRESS")
    Text(
        text = "Fire another entity on long-press. E.g. `scene.movie_night`, `script.bedtime`, `switch.kettle`. Empty = no long-press action.",
        style = R1.body,
        color = R1.InkMuted,
    )
    Spacer(Modifier.height(R1.space.s))
    R1TextField(
        value = override.longPressTarget.orEmpty(),
        onValueChange = { v ->
            onChange(override.copy(longPressTarget = v.takeIf { it.isNotBlank() }))
        },
        placeholder = "scene.movie_night",
        monospace = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions.Default,
    )

    SectionHeader("ACTION ON WHEEL-PRESS")
    Text(
        text = "What happens when the user presses the centre of the scroll wheel (or the configured hardware button). INHERIT = no-op.",
        style = R1.body,
        color = R1.InkMuted,
    )
    Spacer(Modifier.height(R1.space.s))
    TapActionPicker(
        selected = override.actionOnWheelPress,
        onSelect = { onChange(override.copy(actionOnWheelPress = it)) },
    )

    SectionHeader("CUSTOM BUTTONS")
    Text(
        text = "Tap-to-fire chips for vendor or script services (e.g. `xiaomi_miio_fan.fan_set_natural_mode_on`). entity_id of this card is added to the data payload automatically.",
        style = R1.body,
        color = R1.InkMuted,
    )
    Spacer(Modifier.height(R1.space.s))
    CustomActionsEditor(
        actions = override.customActions,
        onChange = { next -> onChange(override.copy(customActions = next)) },
    )
    RowResetChip(
        onReset = {
            onChange(
                override.copy(
                    actionOnTap = null,
                    longPressTarget = null,
                    actionOnWheelPress = null,
                    customActions = emptyList(),
                )
            )
        },
    )
    @Suppress("UNUSED_EXPRESSION") entity
}

// ── Subscreen: BEHAVIOUR ─────────────────────────────────────────────────────
/**
 * TAP TO TOGGLE · WHEEL · HIDE WHEN UNAVAILABLE. The interactive defaults
 * that turn the card on and off without an explicit tap target.
 */
@Composable
private fun BehaviourSubscreen(
    entity: EntityState,
    override: EntityOverride,
    onChange: (EntityOverride) -> Unit,
) {
    SectionHeader("TAP TO TOGGLE")
    Text(
        text = "Override the global Behaviour setting for this card. INHERIT follows the Settings switch; ON forces tap-to-toggle on regardless of the global; OFF forces it off (so a casual tap doesn't fire the entity).",
        style = R1.body,
        color = R1.InkMuted,
    )
    Spacer(Modifier.height(R1.space.s))
    TapToToggleRow(
        selected = override.tapToToggle,
        onSelect = { onChange(override.copy(tapToToggle = it)) },
    )

    SectionHeader("WHEEL")
    Text(
        text = "Whether the wheel acts on this card. INHERIT follows the per-domain default (selects default OFF, everything else ON). ON forces the wheel active; OFF disables it so a casual spin can't change the entity.",
        style = R1.body,
        color = R1.InkMuted,
    )
    Spacer(Modifier.height(R1.space.s))
    TapToToggleRow(
        selected = override.wheelEnabled,
        onSelect = { onChange(override.copy(wheelEnabled = it)) },
    )

    SectionHeader("HIDE WHEN UNAVAILABLE")
    Text(
        text = "When ON, this card is removed from the deck whenever HA reports the entity as unavailable / unknown. Useful for entities that come and go (a vacuum that disappears when docked, a guest device). Other unavailable cards still appear dimmed.",
        style = R1.body,
        color = R1.InkMuted,
    )
    Spacer(Modifier.height(R1.space.s))
    TapToToggleRow(
        selected = override.hideWhenUnavailable,
        onSelect = { onChange(override.copy(hideWhenUnavailable = it)) },
    )
    RowResetChip(
        onReset = {
            onChange(
                override.copy(
                    tapToToggle = null,
                    wheelEnabled = null,
                    hideWhenUnavailable = null,
                )
            )
        },
    )
    @Suppress("UNUSED_EXPRESSION") entity
}

// ── Subscreen: LOCK ──────────────────────────────────────────────────────────
@Composable
private fun LockSubscreen(
    entity: EntityState,
    override: EntityOverride,
    onChange: (EntityOverride) -> Unit,
) {
    SectionHeader("REQUIRE PIN TO UNLOCK")
    Text(
        text = "When ON, this lock card hides its direct UNLOCK / LOCK switch and routes the action through a PIN keypad. Useful for locks HA doesn't enforce a code on but you want a deliberate-gesture confirm for. Set a PIN below to require an exact match; leave blank to accept any non-empty digit sequence as the confirm.",
        style = R1.body,
        color = R1.InkMuted,
    )
    Spacer(Modifier.height(R1.space.s))
    TapToToggleRow(
        selected = override.requirePinToUnlock,
        onSelect = { onChange(override.copy(requirePinToUnlock = it)) },
    )
    if (override.requirePinToUnlock == true) {
        Spacer(Modifier.height(R1.space.s))
        Text(
            text = if (override.requirePinHash.isNullOrBlank()) "PIN NOT SET (any digits accepted)"
                   else "PIN SET",
            style = R1.labelMicro,
            color = if (override.requirePinHash.isNullOrBlank()) R1.StatusAmber else R1.AccentGreen,
        )
        Spacer(Modifier.height(R1.space.s))
        Row(horizontalArrangement = Arrangement.spacedBy(R1.space.s)) {
            var newPin by remember(entity.id.value) { mutableStateOf("") }
            Box(modifier = Modifier.width(140.dp)) {
                R1TextField(
                    value = newPin,
                    onValueChange = { v -> newPin = v.filter { it.isDigit() }.take(12) },
                    placeholder = "4-12 digits",
                    monospace = true,
                )
            }
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .border(1.dp, R1.AccentWarm.copy(alpha = 0.5f), R1.ShapeS)
                    .r1Pressable(onClick = {
                        if (newPin.length >= 4) {
                            val hash = com.github.itskenny0.r1ha.ui.components.sha256Hex(newPin)
                            onChange(override.copy(requirePinHash = hash))
                            newPin = ""
                        }
                    })
                    .padding(horizontal = R1.space.m, vertical = R1.space.s),
            ) {
                Text(text = "SET PIN", style = R1.labelMicro, color = R1.AccentWarm)
            }
            if (!override.requirePinHash.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.StatusRed.copy(alpha = 0.5f), R1.ShapeS)
                        .r1Pressable(onClick = {
                            onChange(override.copy(requirePinHash = null))
                        })
                        .padding(horizontal = R1.space.m, vertical = R1.space.s),
                ) {
                    Text(text = "CLEAR PIN", style = R1.labelMicro, color = R1.StatusRed)
                }
            }
        }
    }
    RowResetChip(
        onReset = {
            onChange(
                override.copy(
                    requirePinToUnlock = null,
                    requirePinHash = null,
                )
            )
        },
    )
}

// ── Subscreen: POSITION ──────────────────────────────────────────────────────
/**
 * Favourite positions for cover and valve entities. Each saved position appears
 * as a chip on the cover/valve panel; tapping it sets that position immediately.
 * The "ADD CURRENT" button captures the entity's live position into the list;
 * "CLEAR ALL" wipes the list.
 */
@Composable
private fun PositionSubscreen(
    entity: EntityState,
    override: EntityOverride,
    onChange: (EntityOverride) -> Unit,
) {
    SectionHeader("FAVOURITE POSITIONS")
    Text(
        text = "Save positions as one-tap chips on the cover or valve control panel. Tap ADD CURRENT to save the entity's current position (0 = closed, 100 = open).",
        style = R1.body,
        color = R1.InkMuted,
    )
    Spacer(Modifier.height(R1.space.s))
    // Show currently saved favourites as a readable list.
    if (override.favoritePositions.isNotEmpty()) {
        Text(
            text = override.favoritePositions.joinToString { "$it%" },
            style = R1.bodyEmph,
            color = R1.Ink,
        )
        Spacer(Modifier.height(R1.space.s))
    }
    val currentPct = entity.percent
    Row(horizontalArrangement = Arrangement.spacedBy(R1.space.s)) {
        Box(
            modifier = Modifier
                .clip(R1.ShapeS)
                .background(if (currentPct != null) R1.SurfaceMuted else R1.Bg)
                .let { m ->
                    if (currentPct != null) m.border(1.dp, R1.AccentWarm.copy(alpha = 0.5f), R1.ShapeS)
                    else m.border(1.dp, R1.Hairline, R1.ShapeS)
                }
                .r1Pressable(
                    onClick = {
                        if (currentPct != null) {
                            val next = (override.favoritePositions + currentPct).distinct().sorted()
                            onChange(override.copy(favoritePositions = next))
                        }
                    },
                )
                .padding(horizontal = R1.space.m, vertical = R1.space.s),
        ) {
            Text(
                text = if (currentPct != null) "ADD CURRENT ($currentPct%)" else "ADD CURRENT",
                style = R1.labelMicro,
                color = if (currentPct != null) R1.AccentWarm else R1.InkMuted,
            )
        }
    }
    RowResetChip(
        onReset = { onChange(override.copy(favoritePositions = emptyList())) },
    )
}

// ── Subscreen: LIGHTING ──────────────────────────────────────────────────────
@Composable
private fun LightingSubscreen(
    entity: EntityState,
    override: EntityOverride,
    onChange: (EntityOverride) -> Unit,
) {
    SectionHeader("LIGHT COLOUR TEMP")
    Text(
        text = "Apply a fixed colour temperature when this light turns on. Only works on lights that support color_temp_kelvin.",
        style = R1.body,
        color = R1.InkMuted,
    )
    Spacer(Modifier.height(R1.space.s))
    CTRow(
        selected = override.lightColorTempK,
        onSelect = { onChange(override.copy(lightColorTempK = it)) },
    )

    SectionHeader("LIGHT BUTTONS")
    Text(
        text = "Hide controls you don't use on this card. Hiding a button HA already wouldn't render (e.g. HUE on a tunable-white bulb) is a no-op.",
        style = R1.body,
        color = R1.InkMuted,
    )
    Spacer(Modifier.height(R1.space.s))
    LightButtonsRow(
        hidden = override.lightButtonsHidden,
        onToggle = { button ->
            val next = if (button in override.lightButtonsHidden) {
                override.lightButtonsHidden - button
            } else {
                override.lightButtonsHidden + button
            }
            onChange(override.copy(lightButtonsHidden = next))
        },
    )
    SectionHeader("FAVOURITE COLOURS")
    Text(
        text = "Save the light's current colour as a one-tap swatch on the light control surface. Tapping a swatch fires light.turn_on with that rgb_color.",
        style = R1.body,
        color = R1.InkMuted,
    )
    Spacer(Modifier.height(R1.space.s))
    // Show currently saved favourite colours as small swatches.
    if (override.favoriteColors.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
        ) {
            override.favoriteColors.forEach { argb ->
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(R1.ShapeS)
                        .background(androidx.compose.ui.graphics.Color(argb))
                        .border(1.dp, R1.Hairline, R1.ShapeS),
                )
            }
        }
        Spacer(Modifier.height(R1.space.s))
    }
    // Read the entity's current rgb_color from attributesJson to allow "ADD CURRENT".
    val currentRgb = run {
        val arr = entity.attributesJson?.get("rgb_color")
            as? kotlinx.serialization.json.JsonArray
        arr?.mapNotNull {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
        }?.takeIf { it.size == 3 }
    }
    val currentRgbArgb = currentRgb?.let { (r, g, b) ->
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    Row(horizontalArrangement = Arrangement.spacedBy(R1.space.s)) {
        Box(
            modifier = Modifier
                .clip(R1.ShapeS)
                .background(if (currentRgbArgb != null) R1.SurfaceMuted else R1.Bg)
                .let { m ->
                    if (currentRgbArgb != null) m.border(1.dp, R1.AccentWarm.copy(alpha = 0.5f), R1.ShapeS)
                    else m.border(1.dp, R1.Hairline, R1.ShapeS)
                }
                .r1Pressable(
                    onClick = {
                        if (currentRgbArgb != null) {
                            val next = (override.favoriteColors + currentRgbArgb).distinct()
                            onChange(override.copy(favoriteColors = next))
                        }
                    },
                )
                .padding(horizontal = R1.space.m, vertical = R1.space.s),
        ) {
            Text(
                text = "ADD CURRENT COLOUR",
                style = R1.labelMicro,
                color = if (currentRgbArgb != null) R1.AccentWarm else R1.InkMuted,
            )
        }
        if (override.favoriteColors.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .border(1.dp, R1.StatusRed.copy(alpha = 0.5f), R1.ShapeS)
                    .r1Pressable(onClick = { onChange(override.copy(favoriteColors = emptyList())) })
                    .padding(horizontal = R1.space.m, vertical = R1.space.s),
            ) {
                Text(text = "CLEAR ALL", style = R1.labelMicro, color = R1.StatusRed)
            }
        }
    }
    RowResetChip(
        onReset = {
            onChange(
                override.copy(
                    lightColorTempK = null,
                    lightButtonsHidden = emptySet(),
                    favoriteColors = emptyList(),
                )
            )
        },
    )
    @Suppress("UNUSED_EXPRESSION") entity
}

// ── Subscreen: ADVANCED ──────────────────────────────────────────────────────
@Composable
private fun AdvancedSubscreen(
    entity: EntityState,
    override: EntityOverride,
    onChange: (EntityOverride) -> Unit,
) {
    SectionHeader("ENTITY ID")
    Text(
        text = entity.id.value,
        style = R1.body.copy(fontFamily = FontFamily.Monospace),
        color = R1.Ink,
    )
    Spacer(Modifier.height(R1.space.xxs))
    Text(
        text = "Read-only. Renames live in HA; use the entity-config sheet to change the entity_id itself.",
        style = R1.body,
        color = R1.InkMuted,
    )

    SectionHeader("DETAILS")
    var detailsOpen by remember { mutableStateOf(false) }
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
        Spacer(Modifier.height(R1.space.s))
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

    SectionHeader("ACTIVE OVERRIDES")
    Text(
        text = "Every field currently overridden, with a per-row reset chip. Useful for spotting non-default settings on a card without drilling each section.",
        style = R1.body,
        color = R1.InkMuted,
    )
    Spacer(Modifier.height(R1.space.s))
    ActiveOverridesList(override = override, onChange = onChange)

    SectionHeader("RESET")
    Text(
        text = "Wipe every override on this card. The name override (on the root view) is preserved; clear that field from QUICK NAME if you want the HA friendly_name back too.",
        style = R1.body,
        color = R1.InkMuted,
    )
    Spacer(Modifier.height(R1.space.s))
    R1Button(
        text = "RESET ALL OVERRIDES",
        onClick = { onChange(EntityOverride.NONE) },
        variant = R1ButtonVariant.Outlined,
        accent = R1.StatusRed,
    )
}

// ── Subscreen primitives ─────────────────────────────────────────────────────
/**
 * Drill-in header for every subscreen. Back-chevron + title + entity_id
 * monospace caption underneath, hairline divider below — same visual shape
 * the SettingsScreen subpages use, so the navigation pattern is consistent
 * with what the user has seen elsewhere in the app.
 */
@Composable
private fun SubscreenHeader(title: String, entityId: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(R1.ShapeS)
                .r1Pressable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            com.github.itskenny0.r1ha.ui.components.Chevron(
                direction = com.github.itskenny0.r1ha.ui.components.ChevronDirection.Left,
                size = 10.dp,
                tint = R1.InkSoft,
            )
        }
        Spacer(Modifier.width(R1.space.s))
        Text(text = title, style = R1.sectionHeader, color = R1.AccentWarm)
    }
    Spacer(Modifier.height(R1.space.xxs))
    Text(
        text = entityId,
        style = R1.body.copy(fontFamily = FontFamily.Monospace),
        color = R1.InkMuted,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(R1.space.s))
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(R1.Hairline))
}

/**
 * Hub NavRow. [modified] adds a small orange dot on the left rail so the
 * user can see at a glance which sections carry overrides; [value] is a
 * one-line summary on the right. Mirrors the SettingsScreen's NavRow shape
 * so the customize surface reads as a settings-style drill-in rather than
 * a bespoke list.
 */
@Composable
private fun CustomizeNavRow(
    label: String,
    modified: Boolean,
    value: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .r1Pressable(onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Orange dot — only present when the section carries an override.
        // Reserves the same horizontal space either way so the labels stay
        // aligned across rows.
        Box(
            modifier = Modifier
                .size(R1.space.s)
                .background(if (modified) R1.AccentWarm else androidx.compose.ui.graphics.Color.Transparent),
        )
        Spacer(Modifier.width(R1.space.s))
        Text(
            text = label.uppercase(),
            style = R1.bodyEmph,
            color = if (modified) R1.AccentWarm else R1.Ink,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        if (value != null) {
            Text(
                text = value,
                style = R1.body,
                color = R1.InkSoft,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.padding(end = R1.space.s).widthIn(max = 220.dp),
            )
        }
        com.github.itskenny0.r1ha.ui.components.Chevron(
            direction = com.github.itskenny0.r1ha.ui.components.ChevronDirection.Right,
            size = 10.dp,
            tint = R1.InkMuted,
        )
    }
}

/** Per-subscreen RESET chip — wipes only the fields owned by that subscreen.
 *  Sits at the bottom of every subscreen with the same red-outline styling
 *  the hub-level RESET uses so the destructive intent reads consistently. */
@Composable
private fun RowResetChip(onReset: () -> Unit) {
    Spacer(Modifier.height(R1.space.l))
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        R1Chip(
            text = "RESET THIS SECTION",
            variant = R1ChipVariant.Action,
            tone = R1.StatusRed,
            selected = true,
            onClick = onReset,
            contentDescription = "Reset this section",
        )
    }
}

// ── Modified / summary helpers ───────────────────────────────────────────────

private fun isIdentityModified(name: String, o: EntityOverride): Boolean =
    name.isNotBlank() || o.glyphOverride != null || o.accentColor != null

private fun identitySummary(name: String, o: EntityOverride): String = buildList {
    if (name.isNotBlank()) add("name")
    if (o.glyphOverride != null) add("glyph")
    if (o.accentColor != null) add("colour")
}.let { mods ->
    if (mods.isEmpty()) "Default" else mods.joinToString(" · ").uppercase()
}

private fun isLayoutModified(o: EntityOverride): Boolean =
    o.positionDotLocation != null || o.valueBarLocation != null ||
        o.showOnOffPill != null || o.showAreaLabel != null ||
        o.textSizeSp != null || o.maxDecimalPlaces != null

private fun layoutSummary(entity: EntityState, o: EntityOverride): String {
    val mods = buildList {
        if (o.positionDotLocation != null) add("pip:${positionDotLocationLabel(o.positionDotLocation).split(' ').first()}")
        if (o.valueBarLocation != null) add("bar:${valueBarLocationLabel(o.valueBarLocation).lowercase()}")
        if (o.showOnOffPill != null) add(if (o.showOnOffPill == true) "pill on" else "pill off")
        if (o.showAreaLabel != null) add(if (o.showAreaLabel == true) "area on" else "area off")
        if (o.textSizeSp != null) add("${o.textSizeSp}sp")
        if (o.maxDecimalPlaces != null && entity.id.domain.isSensor) add("dec ${o.maxDecimalPlaces}")
    }
    return if (mods.isEmpty()) "Default" else mods.joinToString(" · ").uppercase()
}

private fun isActionsModified(o: EntityOverride): Boolean =
    o.actionOnTap != null || !o.longPressTarget.isNullOrBlank() ||
        o.actionOnWheelPress != null || o.customActions.isNotEmpty()

private fun actionsSummary(o: EntityOverride): String {
    val mods = buildList {
        if (o.actionOnTap != null) add("tap:${o.actionOnTap.name.lowercase()}")
        if (!o.longPressTarget.isNullOrBlank()) add("long")
        if (o.actionOnWheelPress != null) add("wheel:${o.actionOnWheelPress.name.lowercase()}")
        if (o.customActions.isNotEmpty()) add("${o.customActions.size} buttons")
    }
    return if (mods.isEmpty()) "Default" else mods.joinToString(" · ").uppercase()
}

private fun isBehaviourModified(o: EntityOverride): Boolean =
    o.tapToToggle != null || o.wheelEnabled != null || o.hideWhenUnavailable != null

private fun behaviourSummary(o: EntityOverride): String {
    val mods = buildList {
        if (o.tapToToggle != null) add(if (o.tapToToggle == true) "tap on" else "tap off")
        if (o.wheelEnabled != null) add(if (o.wheelEnabled == true) "wheel on" else "wheel off")
        if (o.hideWhenUnavailable == true) add("hide unavail")
    }
    return if (mods.isEmpty()) "Default" else mods.joinToString(" · ").uppercase()
}

private fun lightingSummary(o: EntityOverride): String {
    val mods = buildList {
        if (o.lightColorTempK != null) add("${o.lightColorTempK}K")
        if (o.lightButtonsHidden.isNotEmpty()) add("${o.lightButtonsHidden.size} hidden")
        if (o.favoriteColors.isNotEmpty()) add("${o.favoriteColors.size} colours")
    }
    return if (mods.isEmpty()) "Default" else mods.joinToString(" · ").uppercase()
}

// ── New pickers introduced by this refactor ──────────────────────────────────

/**
 * Curated glyph chips matching the kind of replacements users actually
 * reach for: room emojis (kitchen / bed), weather, transport, etc. The
 * curated set keeps the picker tractable; the inline text field next to
 * it accepts any other Unicode codepoint.
 */
@Composable
private fun GlyphPickerRow(
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val curated = listOf(
        "★", "●", "◆", "▲", "✱", "♥", "♪",
        "🏠", "🛏", "🛋", "🍳", "🚪", "🚿",
        "💡", "🌙", "☀", "🔌", "🔥", "❄",
        "🎵", "📺", "🎮", "🌧", "⏰",
    )
    val scroll = androidx.compose.foundation.rememberScrollState()
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // DEFAULT chip — selected when no override is set.
        Box(
            modifier = Modifier
                .padding(end = 6.dp)
                .clip(R1.ShapeS)
                .background(if (selected == null) R1.AccentWarm else R1.Bg)
                .let { m -> if (selected == null) m else m.border(1.dp, R1.Hairline, R1.ShapeS) }
                .r1Pressable({ onSelect(null) })
                .padding(horizontal = R1.space.m, vertical = R1.space.s),
        ) {
            Text(
                text = "DEFAULT",
                style = R1.labelMicro,
                color = if (selected == null) R1.Bg else R1.InkSoft,
            )
        }
        curated.forEach { glyph ->
            val isSelected = selected == glyph
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(36.dp)
                    .clip(R1.ShapeS)
                    .background(if (isSelected) R1.AccentWarm else R1.Bg)
                    .let { m ->
                        if (isSelected) m.border(2.dp, R1.Ink, R1.ShapeS)
                        else m.border(1.dp, R1.Hairline, R1.ShapeS)
                    }
                    .r1Pressable({ onSelect(glyph) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = glyph,
                    style = R1.bodyEmph,
                    color = if (isSelected) R1.Bg else R1.Ink,
                )
            }
        }
    }
}

/**
 * 3x3 grid picker for the per-card position pip slot, mirroring the global
 * picker in Settings → Appearance. INHERIT is the central cell because the
 * three-state model here is "inherit / hide / pick a corner" and central is
 * naturally where "no anchor" reads. HIDDEN is offered explicitly via a
 * chip below the grid so the user can override the global "visible
 * somewhere" choice and silence the pip on this single card.
 */
@Composable
private fun PositionDotOverridePicker(
    selected: PositionDotLocation?,
    onSelect: (PositionDotLocation?) -> Unit,
) {
    // INHERIT chip — wide so it reads as a labelled selection rather than
    // an empty cell. Selected by default when the override is null.
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(R1.ShapeS)
                .background(if (selected == null) R1.AccentWarm else R1.SurfaceMuted)
                .border(
                    1.dp,
                    if (selected == null) R1.AccentWarm else R1.Hairline,
                    R1.ShapeS,
                )
                .r1Pressable({ onSelect(null) })
                .padding(vertical = R1.space.s),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "INHERIT", style = R1.labelMicro, color = if (selected == null) R1.Bg else R1.InkSoft)
        }
        Spacer(Modifier.width(R1.space.xs))
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(R1.ShapeS)
                .background(if (selected == PositionDotLocation.HIDDEN) R1.AccentWarm else R1.SurfaceMuted)
                .border(
                    1.dp,
                    if (selected == PositionDotLocation.HIDDEN) R1.AccentWarm else R1.Hairline,
                    R1.ShapeS,
                )
                .r1Pressable({ onSelect(PositionDotLocation.HIDDEN) })
                .padding(vertical = R1.space.s),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "HIDDEN",
                style = R1.labelMicro,
                color = if (selected == PositionDotLocation.HIDDEN) R1.Bg else R1.InkSoft,
            )
        }
    }
    Spacer(Modifier.height(R1.space.s))
    val rows: List<List<Pair<String, PositionDotLocation>>> = listOf(
        listOf(
            "↖" to PositionDotLocation.TOP_LEFT,
            "↑" to PositionDotLocation.TOP_CENTER,
            "↗" to PositionDotLocation.TOP_RIGHT,
        ),
        listOf(
            "←" to PositionDotLocation.LEFT_CENTER,
            "·" to PositionDotLocation.HIDDEN,
            "→" to PositionDotLocation.RIGHT_CENTER,
        ),
        listOf(
            "↙" to PositionDotLocation.BOTTOM_LEFT,
            "↓" to PositionDotLocation.BOTTOM_CENTER,
            "↘" to PositionDotLocation.BOTTOM_RIGHT,
        ),
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { (glyph, loc) ->
                    val isSelected = selected == loc && loc != PositionDotLocation.HIDDEN
                    val isCenterHidden = loc == PositionDotLocation.HIDDEN
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .padding(2.dp)
                            .clip(R1.ShapeS)
                            .background(if (isSelected) R1.AccentWarm else R1.SurfaceMuted)
                            .border(
                                1.dp,
                                if (isSelected) R1.AccentWarm else R1.Hairline,
                                R1.ShapeS,
                            )
                            .r1Pressable(onClick = {
                                // Centre cell is special: we already surface HIDDEN
                                // via its own chip above, so the centre tile here
                                // re-selects INHERIT for parity with how the user
                                // mental-models the 3x3.
                                if (isCenterHidden) onSelect(null) else onSelect(loc)
                            }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = glyph,
                            style = R1.bodyEmph,
                            color = if (isSelected) R1.Bg else R1.InkSoft,
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(R1.space.xs))
    Text(
        text = "Current: ${
            when (selected) {
                null -> "INHERIT GLOBAL"
                else -> positionDotLocationLabel(selected)
            }
        }",
        style = R1.labelMicro,
        color = R1.InkMuted,
    )
}

/**
 * Per-card value-bar override picker: an INHERIT / HIDDEN chip row over a
 * cross of LEFT / TOP / RIGHT / BOTTOM tiles. INHERIT (null) follows the
 * global setting and is the default selection; HIDDEN drops the bar on this
 * card. Same chrome as [PositionDotOverridePicker] so the two override rows
 * sitting next to each other read consistently.
 */
@Composable
private fun ValueBarOverridePicker(
    selected: ValueBarLocation?,
    onSelect: (ValueBarLocation?) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(R1.ShapeS)
                .background(if (selected == null) R1.AccentWarm else R1.SurfaceMuted)
                .border(
                    1.dp,
                    if (selected == null) R1.AccentWarm else R1.Hairline,
                    R1.ShapeS,
                )
                .r1Pressable({ onSelect(null) })
                .padding(vertical = R1.space.s),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "INHERIT", style = R1.labelMicro, color = if (selected == null) R1.Bg else R1.InkSoft)
        }
        Spacer(Modifier.width(R1.space.xs))
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(R1.ShapeS)
                .background(if (selected == ValueBarLocation.HIDDEN) R1.AccentWarm else R1.SurfaceMuted)
                .border(
                    1.dp,
                    if (selected == ValueBarLocation.HIDDEN) R1.AccentWarm else R1.Hairline,
                    R1.ShapeS,
                )
                .r1Pressable({ onSelect(ValueBarLocation.HIDDEN) })
                .padding(vertical = R1.space.s),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "HIDDEN",
                style = R1.labelMicro,
                color = if (selected == ValueBarLocation.HIDDEN) R1.Bg else R1.InkSoft,
            )
        }
    }
    Spacer(Modifier.height(R1.space.s))
    val rows: List<List<Pair<String, ValueBarLocation>?>> = listOf(
        listOf(null, "↑" to ValueBarLocation.TOP, null),
        listOf(
            "←" to ValueBarLocation.LEFT,
            "·" to ValueBarLocation.HIDDEN,
            "→" to ValueBarLocation.RIGHT,
        ),
        listOf(null, "↓" to ValueBarLocation.BOTTOM, null),
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { cell ->
                    if (cell == null) {
                        Spacer(Modifier.weight(1f).height(44.dp).padding(2.dp))
                    } else {
                        val (glyph, loc) = cell
                        // The centre cell maps to HIDDEN conceptually but the
                        // HIDDEN chip above already covers that, so re-selecting
                        // the centre tile clears to INHERIT for parity with the
                        // position-pip picker's 3x3 centre behaviour.
                        val isCenterHidden = loc == ValueBarLocation.HIDDEN
                        val isSelected = selected == loc && !isCenterHidden
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .padding(2.dp)
                                .clip(R1.ShapeS)
                                .background(if (isSelected) R1.AccentWarm else R1.SurfaceMuted)
                                .border(
                                    1.dp,
                                    if (isSelected) R1.AccentWarm else R1.Hairline,
                                    R1.ShapeS,
                                )
                                .r1Pressable({
                                    if (isCenterHidden) onSelect(null) else onSelect(loc)
                                }),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = glyph,
                                style = R1.bodyEmph,
                                color = if (isSelected) R1.Bg else R1.InkSoft,
                            )
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(R1.space.xs))
    Text(
        text = "Current: ${
            when (selected) {
                null -> "INHERIT GLOBAL"
                else -> valueBarLocationLabel(selected)
            }
        }",
        style = R1.labelMicro,
        color = R1.InkMuted,
    )
}

/**
 * INHERIT / TOGGLE / FIRE / NAV / NOOP picker for the per-card tap and
 * wheel-press action overrides. INHERIT is the leftmost chip so the
 * default-position is easy to reach back to after experimentation.
 */
@Composable
private fun TapActionPicker(
    selected: TapAction?,
    onSelect: (TapAction?) -> Unit,
) {
    val options: List<Pair<String, TapAction?>> = listOf(
        "INHERIT" to null,
        "TOGGLE" to TapAction.TOGGLE,
        "FIRE" to TapAction.FIRE,
        "NAV" to TapAction.NAVIGATE_HISTORY,
        "NOOP" to TapAction.NOOP,
    )
    Row(modifier = Modifier.fillMaxWidth().clip(R1.ShapeS).background(R1.SurfaceMuted)) {
        options.forEachIndexed { idx, (label, value) ->
            val active = selected == value
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(if (active) R1.AccentWarm else R1.SurfaceMuted)
                    .r1Pressable({ onSelect(value) })
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = R1.labelMicro,
                    color = if (active) R1.Bg else R1.InkSoft,
                )
            }
            if (idx < options.lastIndex) CellDivider()
        }
    }
}

/**
 * ADVANCED → ACTIVE OVERRIDES table. One row per non-default field with
 * its current value, a label, and a per-row REVERT chip that nulls just
 * that field. Always renders at least the empty-state placeholder so the
 * user knows the surface exists even on cards with no overrides yet.
 */
@Composable
private fun ActiveOverridesList(
    override: EntityOverride,
    onChange: (EntityOverride) -> Unit,
) {
    val rows = mutableListOf<Triple<String, String, () -> Unit>>()
    if (override.textSizeSp != null) {
        rows += Triple("Text size", "${override.textSizeSp}sp") {
            onChange(override.copy(textSizeSp = null))
        }
    }
    if (override.showOnOffPill != null) {
        rows += Triple(
            "On/off pill",
            if (override.showOnOffPill == true) "SHOWN" else "HIDDEN",
        ) { onChange(override.copy(showOnOffPill = null)) }
    }
    if (override.showAreaLabel != null) {
        rows += Triple(
            "Area label",
            if (override.showAreaLabel == true) "SHOWN" else "HIDDEN",
        ) { onChange(override.copy(showAreaLabel = null)) }
    }
    if (override.accentColor != null) {
        rows += Triple(
            "Accent",
            "#" + java.lang.String.format("%08X", override.accentColor),
        ) { onChange(override.copy(accentColor = null)) }
    }
    if (override.maxDecimalPlaces != null) {
        rows += Triple("Decimals", "${override.maxDecimalPlaces}") {
            onChange(override.copy(maxDecimalPlaces = null))
        }
    }
    if (override.glyphOverride != null) {
        rows += Triple("Glyph", override.glyphOverride) {
            onChange(override.copy(glyphOverride = null))
        }
    }
    if (override.positionDotLocation != null) {
        rows += Triple("Position pip", positionDotLocationLabel(override.positionDotLocation)) {
            onChange(override.copy(positionDotLocation = null))
        }
    }
    if (override.valueBarLocation != null) {
        rows += Triple("Value bar", valueBarLocationLabel(override.valueBarLocation)) {
            onChange(override.copy(valueBarLocation = null))
        }
    }
    if (override.actionOnTap != null) {
        rows += Triple("Action on tap", override.actionOnTap.name) {
            onChange(override.copy(actionOnTap = null))
        }
    }
    if (override.actionOnWheelPress != null) {
        rows += Triple("Action on wheel-press", override.actionOnWheelPress.name) {
            onChange(override.copy(actionOnWheelPress = null))
        }
    }
    if (override.tapToToggle != null) {
        rows += Triple(
            "Tap to toggle",
            if (override.tapToToggle == true) "ON" else "OFF",
        ) { onChange(override.copy(tapToToggle = null)) }
    }
    if (override.wheelEnabled != null) {
        rows += Triple(
            "Wheel",
            if (override.wheelEnabled == true) "ON" else "OFF",
        ) { onChange(override.copy(wheelEnabled = null)) }
    }
    if (override.hideWhenUnavailable != null) {
        rows += Triple(
            "Hide when unavailable",
            if (override.hideWhenUnavailable == true) "ON" else "OFF",
        ) { onChange(override.copy(hideWhenUnavailable = null)) }
    }
    if (!override.longPressTarget.isNullOrBlank()) {
        rows += Triple("Long-press target", override.longPressTarget) {
            onChange(override.copy(longPressTarget = null))
        }
    }
    if (override.lightColorTempK != null) {
        rows += Triple("Light CT", "${override.lightColorTempK}K") {
            onChange(override.copy(lightColorTempK = null))
        }
    }
    if (override.lightButtonsHidden.isNotEmpty()) {
        rows += Triple(
            "Hidden light buttons",
            override.lightButtonsHidden.joinToString { it.name.take(1) },
        ) { onChange(override.copy(lightButtonsHidden = emptySet())) }
    }
    if (override.customActions.isNotEmpty()) {
        rows += Triple("Custom buttons", "${override.customActions.size} buttons") {
            onChange(override.copy(customActions = emptyList()))
        }
    }
    if (override.requirePinToUnlock != null) {
        rows += Triple(
            "Require PIN",
            if (override.requirePinToUnlock == true) "ON" else "OFF",
        ) { onChange(override.copy(requirePinToUnlock = null)) }
    }
    if (!override.requirePinHash.isNullOrBlank()) {
        rows += Triple("PIN hash set", "SHA-256") {
            onChange(override.copy(requirePinHash = null))
        }
    }
    if (override.favoriteColors.isNotEmpty()) {
        rows += Triple("Favourite colours", "${override.favoriteColors.size} saved") {
            onChange(override.copy(favoriteColors = emptyList()))
        }
    }
    if (override.favoritePositions.isNotEmpty()) {
        rows += Triple(
            "Favourite positions",
            override.favoritePositions.joinToString { "$it%" },
        ) { onChange(override.copy(favoritePositions = emptyList())) }
    }
    if (rows.isEmpty()) {
        Text(text = "No overrides on this card.", style = R1.body, color = R1.InkMuted)
        return
    }
    rows.forEach { (label, value, revert) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = R1.bodyEmph, color = R1.Ink)
                Text(
                    text = value,
                    style = R1.body,
                    color = R1.InkMuted,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(R1.space.s))
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.StatusRed.copy(alpha = 0.14f))
                    .border(1.dp, R1.StatusRed.copy(alpha = 0.5f), R1.ShapeS)
                    .r1Pressable(onClick = revert)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(text = "REVERT", style = R1.labelMicro, color = R1.StatusRed)
            }
        }
    }
}

// ── Shared composables and helpers carried over from the monolithic dialog.
// The signatures match the pre-refactor surface so other surfaces that referenced
// these names (none in production, but worth keeping consistent) keep working.

@Composable
private fun SectionHeader(title: String) {
    Spacer(Modifier.height(R1.space.l))
    Text(text = title, style = R1.sectionHeader, color = R1.InkSoft)
    Spacer(Modifier.height(R1.space.xxs))
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(R1.Hairline))
    Spacer(Modifier.height(R1.space.s))
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
        Spacer(Modifier.height(R1.space.xs))
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
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = R1.space.xxs)) {
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
                .padding(horizontal = R1.space.m, vertical = R1.space.s),
        ) {
            Text(
                text = "DEFAULT",
                style = R1.labelMicro,
                color = if (selected == null) R1.Bg else R1.InkSoft,
            )
        }
        EntityOverride.ACCENT_PALETTE.forEach { (label, argb) ->
            val isSelected = selected == argb
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
        Box(
            modifier = Modifier
                .padding(end = 6.dp)
                .clip(R1.ShapeS)
                .background(if (selected == null) R1.AccentWarm else R1.Bg)
                .let { m -> if (selected == null) m else m.border(1.dp, R1.Hairline, R1.ShapeS) }
                .r1Pressable({ onSelect(null) })
                .padding(horizontal = R1.space.m, vertical = R1.space.s),
        ) {
            Text(
                text = "DEFAULT",
                style = R1.labelMicro,
                color = if (selected == null) R1.Bg else R1.InkSoft,
            )
        }
        EntityOverride.LIGHT_CT_PRESETS.forEach { (label, kelvin) ->
            val isSelected = selected == kelvin
            val tint = ctApproxColor(kelvin)
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .clip(R1.ShapeS)
                    .background(if (isSelected) tint else R1.Bg)
                    .border(1.dp, if (isSelected) tint else R1.Hairline, R1.ShapeS)
                    .r1Pressable({ onSelect(kelvin) })
                    .padding(horizontal = R1.space.m, vertical = R1.space.s),
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
 * Tri-state row for boolean overrides on per-card behaviour fields.
 * INHERIT (null) / ON (true) / OFF (false).
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
                    .padding(vertical = R1.space.s),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = R1.labelMicro,
                    color = if (active) R1.Bg else R1.InkSoft,
                )
            }
            if (idx < options.lastIndex) Spacer(Modifier.width(R1.space.xs))
        }
    }
}

/**
 * Light-card button visibility toggle row. Each of BRIGHT / WHITE / HUE / FX is a
 * chip that highlights when the button is currently SHOWN on the card.
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
                    .padding(vertical = R1.space.s),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = R1.labelMicro,
                    color = if (visible) R1.Bg else R1.InkSoft,
                )
            }
            if (idx < all.lastIndex) Spacer(Modifier.width(R1.space.xs))
        }
    }
}

@Composable
private fun TextSizeRow(
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    val scroll = androidx.compose.foundation.rememberScrollState()
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
                .padding(horizontal = R1.space.m, vertical = R1.space.s),
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
                    .padding(horizontal = R1.space.m, vertical = R1.space.s),
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

/**
 * CUSTOM BUTTONS section editor. Lists existing actions as rows with a small
 * REMOVE chip, then offers an inline add form (label + service + optional
 * JSON data). ADD commits to the list and clears the form.
 */
@Composable
private fun CustomActionsEditor(
    actions: List<com.github.itskenny0.r1ha.core.prefs.CustomAction>,
    onChange: (List<com.github.itskenny0.r1ha.core.prefs.CustomAction>) -> Unit,
) {
    var newLabel by remember { mutableStateOf("") }
    var newService by remember { mutableStateOf("") }
    var newData by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth()) {
        actions.forEachIndexed { index, action ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = action.label, style = R1.bodyEmph, color = R1.Ink)
                    Text(
                        text = action.service,
                        style = R1.body.copy(fontFamily = FontFamily.Monospace),
                        color = R1.InkMuted,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(R1.space.s))
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.StatusRed.copy(alpha = 0.18f))
                        .r1Pressable(
                            onClick = { onChange(actions.toMutableList().also { it.removeAt(index) }) },
                            contentDescription = "Remove ${action.label}",
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(text = "REMOVE", style = R1.labelMicro, color = R1.StatusRed)
                }
            }
        }
        if (actions.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(R1.Hairline))
            Spacer(Modifier.height(R1.space.s))
        }
        R1TextField(
            value = newLabel,
            onValueChange = { newLabel = it },
            placeholder = "Label (e.g. Natural mode)",
            monospace = false,
        )
        Spacer(Modifier.height(R1.space.s))
        R1TextField(
            value = newService,
            onValueChange = { newService = it.trim() },
            placeholder = "domain.service (e.g. xiaomi_miio_fan.fan_set_natural_mode_on)",
            monospace = true,
        )
        Spacer(Modifier.height(R1.space.s))
        R1TextField(
            value = newData,
            onValueChange = { newData = it },
            placeholder = "Optional data JSON (e.g. {\"speed\":3})",
            monospace = true,
            singleLine = false,
        )
        Spacer(Modifier.height(R1.space.s))
        R1Button(
            text = "ADD",
            onClick = {
                val label = newLabel.trim()
                val service = newService.trim()
                if (label.isEmpty() || !service.contains('.')) return@R1Button
                val data = newData.trim().takeIf { it.isNotEmpty() }
                val next = actions + com.github.itskenny0.r1ha.core.prefs.CustomAction(
                    label = label,
                    service = service,
                    dataJson = data,
                )
                onChange(next)
                newLabel = ""
                newService = ""
                newData = ""
            },
            enabled = newLabel.isNotBlank() && newService.contains('.'),
            variant = R1ButtonVariant.Outlined,
        )
    }
}
