package com.github.itskenny0.r1ha.feature.quickactions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.EntityOverride
import com.github.itskenny0.r1ha.core.prefs.FavoritePage
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.LocalOnEntityCall
import com.github.itskenny0.r1ha.core.theme.LocalOnSetEntityPercent
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Button
import com.github.itskenny0.r1ha.ui.components.R1ButtonVariant
import com.github.itskenny0.r1ha.ui.components.WheelScrollForScrollState
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.rememberR1Haptic

/**
 * The "manage this card" actions surfaced at the bottom of the [QuickActionSheet]. These
 * mirror the retired CardContextMenu so the Quick Sheet is a complete replacement: a card
 * the user long-pressed expecting to move / remove / inspect still finds every affordance.
 *
 * Each callback is nullable where the action is conditional:
 *  - [moreInfo]  open the in-app ultra-detail sheet; null when the entity's effective
 *                `moreInfoEnabled` is false.
 *  - [customize] open the customize / rename sheet; null when not offered.
 *  - [history]   open the sensor-history overlay; null when not offered.
 *  - [openInHaUrl] the HA deep-link for "OPEN IN HA"; null / blank hides the button.
 *
 * [pages] / [sourcePageId] / [onMove] drive the MOVE TO chips (every page whose id differs
 * from the source); [onOpenInHa] / [onRemove] back their respective buttons.
 */
data class QuickSheetManageActions(
    val moreInfo: (() -> Unit)?,
    val customize: (() -> Unit)?,
    val history: (() -> Unit)?,
    val pages: List<FavoritePage>,
    val sourcePageId: String,
    val onMove: (String) -> Unit,
    val openInHaUrl: String?,
    val onOpenInHa: (String) -> Unit,
    val onRemove: () -> Unit,
)

/**
 * Full-screen Quick Sheet opened by long-pressing a card in the main card stack. Shows the
 * entity's domain quick-actions (built by [buildQuickActions]) above a "manage this card"
 * row that is feature-complete with the CardContextMenu it replaces.
 *
 * Dismisses on a scrim tap and on Back. On the R1 the hardware wheel scrolls the sheet when
 * [wheelInput] is supplied. A heavy long-press haptic fires on open so the gesture reads as
 * "the system noticed the hold".
 *
 * @param state    the long-pressed card's entity state.
 * @param override the per-card override (accent, favourites) handed to the action builders.
 * @param manage   the manage-row callbacks (see [QuickSheetManageActions]).
 * @param onDismiss close the sheet. Every action also calls this after firing so the sheet
 *                  closes itself regardless of whether the callback already did.
 * @param wheelInput the R1 wheel event stream; null on touch-only hosts (the sheet still
 *                  scrolls by finger).
 * @param settings the settings repository, forwarded to the wheel-scroll helper so the
 *                  user's wheel-acceleration preference applies; null is tolerated.
 */
@Composable
fun QuickActionSheet(
    state: EntityState,
    override: EntityOverride,
    manage: QuickSheetManageActions,
    onDismiss: () -> Unit,
    wheelInput: WheelInput? = null,
    settings: SettingsRepository? = null,
) {
    val view = LocalView.current
    val haptic = rememberR1Haptic()
    val scrollState = rememberScrollState()

    BackHandler(onBack = onDismiss)
    // Heavy "you held that down" haptic on open, matching the long-press idiom the rest of
    // the chrome uses for context menus.
    LaunchedEffect(Unit) { haptic.longPress(view) }
    // Route the R1 wheel to the sheet's scroll state when a wheel is present.
    if (wheelInput != null) {
        WheelScrollForScrollState(wheelInput = wheelInput, scrollState = scrollState, settings = settings)
    }

    // Quick-action dispatch context: every fired action ticks a confirmation haptic and
    // routes through the screen-level composition locals (null on previews / non-card hosts,
    // where the call is a harmless no-op).
    val onEntityCall = LocalOnEntityCall.current
    val onSetPercent = LocalOnSetEntityPercent.current
    val ctx = QuickActionContext(
        state = state,
        override = override,
        onEntityCall = { call ->
            onEntityCall?.invoke(call)
            haptic.tick(view)
        },
        onSetPercent = { id, pct ->
            onSetPercent?.invoke(id, pct)
            haptic.tick(view)
        },
        dismiss = onDismiss,
    )
    val groups = buildQuickActions(ctx)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.92f))
            .r1Pressable(onClick = onDismiss, hapticOnClick = false)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp)
                .clip(R1.ShapeS)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                // Swallow taps inside the surface so they don't fall through to the scrim's
                // dismiss; no haptic on this inert tap.
                .r1Pressable(onClick = {}, hapticOnClick = false)
                .padding(16.dp)
                .verticalScroll(scrollState),
        ) {
            // ── Header ──────────────────────────────────────────────────────────────────
            Text(text = "CARD ACTIONS", style = R1.sectionHeader, color = R1.AccentWarm)
            Spacer(Modifier.height(4.dp))
            Text(
                text = state.friendlyName,
                style = R1.body,
                color = R1.Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.id.value,
                style = R1.labelMicro.copy(fontFamily = FontFamily.Monospace),
                color = R1.InkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // ── Quick actions ──────────────────────────────────────────────────────────
            // Domain builders surface their groups here; an empty list (read-only entities)
            // collapses straight to the manage row below.
            for (group in groups) {
                Spacer(Modifier.height(14.dp))
                if (group.title != null) {
                    Text(text = group.title, style = R1.labelMicro, color = R1.InkSoft)
                    Spacer(Modifier.height(6.dp))
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (action in group.actions) {
                        QuickActionChip(action)
                    }
                }
            }

            // ── Manage this card ─────────────────────────────────────────────────────────
            Spacer(Modifier.height(14.dp))
            Text(text = "MANAGE", style = R1.labelMicro, color = R1.InkSoft)
            Spacer(Modifier.height(6.dp))

            // Ultra-detail more-info - opens the in-app attribute / history sheet. Hidden
            // when the effective per-entity moreInfoEnabled resolved to false.
            val moreInfo = manage.moreInfo
            if (moreInfo != null) {
                R1Button(
                    text = "MORE INFO",
                    onClick = { moreInfo(); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }
            // Customize / rename this card.
            val customize = manage.customize
            if (customize != null) {
                R1Button(
                    text = "CUSTOMIZE",
                    onClick = { customize(); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                    variant = R1ButtonVariant.Outlined,
                )
                Spacer(Modifier.height(8.dp))
            }
            // Open the sensor-history overlay for this entity.
            val history = manage.history
            if (history != null) {
                R1Button(
                    text = "HISTORY",
                    onClick = { history(); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                    variant = R1ButtonVariant.Outlined,
                )
                Spacer(Modifier.height(8.dp))
            }

            // Move-to-page entries. Filtered to pages OTHER than the source so we never
            // offer a self-move. When there's only one page total this collapses to a
            // 'no other pages' hint pointing at the '+' chip. Rendered as a wrapping
            // FlowRow of compact chips rather than one full-width button per page.
            val targetPages = manage.pages.filter { it.id != manage.sourcePageId }
            Spacer(Modifier.height(6.dp))
            Text(text = "MOVE TO", style = R1.labelMicro, color = R1.InkSoft)
            Spacer(Modifier.height(6.dp))
            if (targetPages.isEmpty()) {
                Text(
                    text = "No other pages yet. Add one with the '+' chip on the tab strip.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (p in targetPages) {
                        Box(
                            modifier = Modifier
                                .clip(R1.ShapeS)
                                .border(1.dp, R1.AccentWarm, R1.ShapeS)
                                .r1Pressable(
                                    onClick = { manage.onMove(p.id); onDismiss() },
                                    contentDescription = "Move to ${p.name}",
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = p.name.uppercase(),
                                style = R1.labelMicro,
                                color = R1.AccentWarm,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            // Open in HA - deep-link to the entity's history page in the HA web UI. Hidden
            // when the user isn't signed in (the url is then null / blank).
            val openUrl = manage.openInHaUrl
            if (!openUrl.isNullOrBlank()) {
                R1Button(
                    text = "OPEN IN HA",
                    onClick = { manage.onOpenInHa(openUrl); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                    variant = R1ButtonVariant.Outlined,
                )
                Spacer(Modifier.height(8.dp))
            }
            // Remove from this page - destructive, so it carries the status-red accent.
            R1Button(
                text = "REMOVE FROM PAGE",
                onClick = { manage.onRemove(); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
                accent = R1.StatusRed,
            )
            Spacer(Modifier.height(8.dp))
            R1Button(
                text = "CANCEL",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                variant = R1ButtonVariant.Outlined,
            )
        }
    }
}

/**
 * A single quick-action chip. Outlined when idle, filled with a 20%-alpha accent wash when
 * [QuickAction.selected] (the entity's current mode / preset). A [QuickAction.accentArgb]
 * tints the border + text; otherwise selected chips use the warm accent and idle chips the
 * hairline border with plain ink text.
 */
@Composable
private fun QuickActionChip(action: QuickAction) {
    val accentColor = action.accentArgb?.let { Color(it) }
    val borderColor = accentColor ?: if (action.selected) R1.AccentWarm else R1.Hairline
    val fillColor =
        if (action.selected) (accentColor ?: R1.AccentWarm).copy(alpha = 0.2f) else Color.Transparent
    val textColor = accentColor ?: if (action.selected) R1.AccentWarm else R1.Ink
    Box(
        modifier = Modifier
            .clip(R1.ShapeS)
            .background(fillColor)
            .border(1.dp, borderColor, R1.ShapeS)
            .r1Pressable(onClick = { action.onFire() }, contentDescription = action.label)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (action.glyph != null) {
                Text(text = action.glyph, style = R1.body, color = textColor)
            }
            Text(text = action.label, style = R1.labelMicro, color = textColor)
        }
    }
}
