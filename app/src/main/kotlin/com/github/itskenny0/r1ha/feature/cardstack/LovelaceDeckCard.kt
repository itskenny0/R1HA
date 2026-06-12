package com.github.itskenny0.r1ha.feature.cardstack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.feature.dashboards.cards.EntityStates
import com.github.itskenny0.r1ha.feature.dashboards.cards.LovelaceCardRenderer
import com.github.itskenny0.r1ha.feature.dashboards.cards.dispatchLovelaceAction
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Screen-level wiring a Lovelace deck slot needs to dispatch its tap actions
 * and surface its context menu. Built once per PageDeck so the per-card
 * composable stays a thin renderer.
 */
internal class LovelaceDeckHooks(
    val haRepository: HaRepository,
    /** Live entity-state lookup for action dispatch. A provider (not a value)
     *  so the hooks object stays reference-stable across state ticks; the
     *  rendered slice is passed to [LovelaceDeckCard] separately. */
    val states: () -> EntityStates,
    val onMoreInfo: (String) -> Unit,
    val onNavigatePath: (String) -> Unit,
    val onOpenUrl: (String) -> Unit,
    /** Long-press / "…" affordance: open the edit-remove menu for this card. */
    val onOpenCardMenu: (DeckItem.Card) -> Unit,
    /** Pending tap-action confirmation (HA `confirmation:` config); rendered
     *  by [LovelaceConfirmOverlay] above the deck. */
    val pendingConfirm: MutableState<Pair<String, CompletableDeferred<Boolean>>?>,
)

/** Dispatch a Lovelace tap action through the shared action pipeline, gating
 *  on the deck-level confirmation overlay when the config asks for one. */
internal fun LovelaceDeckHooks.dispatch(scope: CoroutineScope, action: LovelaceAction) {
    scope.launch {
        dispatchLovelaceAction(
            action = action,
            fallbackEntityId = when (action) {
                is LovelaceAction.CallService -> action.entityId
                is LovelaceAction.Builtin -> action.entityId
                else -> null
            },
            haRepository = haRepository,
            onNavigate = onNavigatePath,
            onOpenUrl = onOpenUrl,
            onMoreInfo = onMoreInfo,
            stateLookup = { rawId -> states().byRaw(rawId) },
            confirmGate = { confirmation, _ ->
                val deferred = CompletableDeferred<Boolean>()
                pendingConfirm.value = (confirmation.text ?: "Are you sure?") to deferred
                try {
                    deferred.await()
                } finally {
                    pendingConfirm.value = null
                }
            },
        )
    }
}

/**
 * A pinned Lovelace card as a first-class deck slot. Wears the SAME outer
 * treatment as the entity cards: the caller's [modifier] carries the shared
 * rounded-clip + shadow graphicsLayer from PageDeck, and the surface here is
 * the plain near-black [R1.Bg] every non-themed card variant paints. No
 * border, no corner ticks, no type chip; the card content itself is the face.
 *
 * Interaction map:
 *  - card content owns its taps (the dashboards engine dispatches actions);
 *  - long-press on the card body opens the edit / remove menu, mirroring the
 *    entity cards' long-press convention (skipped over iframes, where the
 *    WebView consumes the gesture);
 *  - a small "…" dot cluster bottom-right mirrors the entity cards' on-card
 *    detail affordance and opens the same menu, guaranteeing a menu entry
 *    point even on iframe cards.
 */
@Composable
internal fun LovelaceDeckCard(
    item: DeckItem.Card,
    hooks: LovelaceDeckHooks,
    states: EntityStates,
    scope: CoroutineScope,
    /** Peek neighbours hide the "…" affordance, matching how EntityCard slots
     *  null out their on-card detail button when half-visible. */
    isFocused: Boolean,
    modifier: Modifier = Modifier,
) {
    // Iframe slots skip the scroll wrapper: the WebView already owns vertical
    // drags inside its bounds, and stacking a second scroll consumer around a
    // fixed-aspect box only fights the pager. Other card types can outgrow
    // the slot, so they scroll.
    val isIframe = item.card is LovelaceCard.Unsupported && item.card.url != null
    Box(
        modifier = modifier
            .background(R1.Bg)
            .then(
                if (isIframe) {
                    Modifier
                } else {
                    Modifier.pointerInput(item.id) {
                        detectTapGestures(onLongPress = { hooks.onOpenCardMenu(item) })
                    }
                },
            ),
    ) {
        val bodyModifier = if (isIframe) {
            Modifier.fillMaxWidth()
        } else {
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(modifier = bodyModifier) {
                LovelaceCardRenderer(
                    card = item.card,
                    stateMap = states.sliceFor(item.card),
                    onAction = { action -> hooks.dispatch(scope, action) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        // "…" menu affordance: same dot-cluster mark as the entity cards'
        // detail button, anchored bottom-right where that button lives.
        if (isFocused) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 6.dp, bottom = 6.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .r1Pressable(
                        onClick = { hooks.onOpenCardMenu(item) },
                        contentDescription = "Card actions",
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Row {
                    repeat(3) { i ->
                        if (i > 0) Spacer(Modifier.size(3.dp))
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(R1.Ink.copy(alpha = 0.55f)),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Context menu for a pinned Lovelace deck card: the per-item counterpart of
 * the entity cards' CardContextMenu, opened from the slot's long-press / "…"
 * affordance or its jump-sheet row. Edit opens the structured editor; remove
 * unpins. Reorder is pointed at the jump sheet (where the whole mixed order
 * is visible) rather than duplicated here.
 */
@Composable
internal fun LovelaceCardMenu(
    title: String,
    cardType: String,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.92f))
            .r1Pressable(onClick = onDismiss, hapticOnClick = false),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp)
                .clip(R1.ShapeS)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onClick = {}, hapticOnClick = false)
                .padding(16.dp),
        ) {
            Text(text = "CARD ACTIONS", style = R1.sectionHeader, color = R1.AccentWarm)
            Spacer(Modifier.size(4.dp))
            Text(
                text = title,
                style = R1.body,
                color = R1.Ink,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                text = cardType.uppercase().replace('-', ' '),
                style = R1.labelMicro,
                color = R1.InkMuted,
            )
            Spacer(Modifier.size(14.dp))
            com.github.itskenny0.r1ha.ui.components.R1Button(
                text = "EDIT CARD",
                onClick = onEdit,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(8.dp))
            com.github.itskenny0.r1ha.ui.components.R1Button(
                text = "REMOVE FROM PAGE",
                onClick = onRemove,
                modifier = Modifier.fillMaxWidth(),
                accent = R1.StatusRed,
            )
            Spacer(Modifier.size(8.dp))
            com.github.itskenny0.r1ha.ui.components.R1Button(
                text = "CANCEL",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = "Reorder from the position pip's jump list (long-press a row, drag).",
                style = R1.labelMicro,
                color = R1.InkMuted,
            )
        }
    }
}

/**
 * Confirmation overlay for `confirmation:`-gated tap actions. Rendered above
 * the active deck; CONFIRM / CANCEL resolve the deferred the dispatch path is
 * suspended on, so confirmations actually gate instead of silently passing.
 */
@Composable
internal fun LovelaceConfirmOverlay(
    pendingConfirm: MutableState<Pair<String, CompletableDeferred<Boolean>>?>,
    accent: androidx.compose.ui.graphics.Color,
) {
    val pending = pendingConfirm.value ?: return
    val (text, deferred) = pending
    androidx.activity.compose.BackHandler { deferred.complete(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.92f))
            .r1Pressable(onClick = { deferred.complete(false) }),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(R1.ShapeM)
                .background(R1.Surface)
                .border(1.dp, accent.copy(alpha = 0.6f), R1.ShapeM)
                .padding(16.dp)
                .r1Pressable(onClick = {}),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = text, style = R1.body, color = R1.Ink)
            Spacer(Modifier.size(12.dp))
            Row {
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeRound)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeRound)
                        .r1Pressable(onClick = { deferred.complete(false) })
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) { Text("CANCEL", style = R1.labelMicro, color = R1.InkSoft) }
                Spacer(Modifier.size(8.dp))
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeRound)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, accent.copy(alpha = 0.7f), R1.ShapeRound)
                        .r1Pressable(onClick = { deferred.complete(true) })
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) { Text("CONFIRM", style = R1.labelMicro, color = accent) }
            }
        }
    }
}
