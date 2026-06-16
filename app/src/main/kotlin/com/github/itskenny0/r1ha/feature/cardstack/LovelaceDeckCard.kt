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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.LocalCardBackdropPainted
import com.github.itskenny0.r1ha.core.theme.LocalCardInk
import com.github.itskenny0.r1ha.core.theme.LocalColorfulCardsConfig
import com.github.itskenny0.r1ha.core.theme.LocalR1Theme
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
 * A pinned Lovelace card as a first-class deck slot. The SLOT (the caller's
 * [modifier], carrying the pager's scale animation) stays full-page so swipe
 * mechanics and the "…" anchor are unchanged, but the VISIBLE SURFACE wraps
 * its content height, vertically centred in the slot: a button card is
 * button-height, a markdown card is text-height. Tall content clamps at the
 * slot height and scrolls internally (see [DeckCardSurface] for the measure
 * contract). [surfaceModifier] carries the rounded clip + fading shadow from
 * PageDeck, applied at the wrapped size. The deck adds NO frame of its own:
 * card renderers already paint their own bordered panel (ButtonCard,
 * CardSurface, ...), and wrapping that in another background + padding read
 * as a redundant full-width frame around every small card. The card content
 * itself is the face.
 *
 * Interaction map:
 *  - card content owns its taps (the dashboards engine dispatches actions);
 *  - long-press anywhere in the slot opens the edit / remove menu, mirroring
 *    the entity cards' long-press convention (skipped over iframes, where the
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
    /** Clip + shadow treatment for the content-hugging surface, supplied by
     *  PageDeck so it matches the entity cards' corner / shadow language. */
    surfaceModifier: Modifier = Modifier,
) {
    // The ViewModel already gated the top-level conditional layers at deck
    // build time (a hidden card never reaches a slot), so render the wrapped
    // card directly. Re-running the wrapper here would re-evaluate `screen` /
    // `view_columns` against the real window, contradicting the deck's
    // always-visible policy for those kinds and blanking the slot.
    val content = remember(item.card) { unwrapDeckConditional(item.card) }
    // Iframe slots skip the scroll wrapper: the WebView already owns vertical
    // drags inside its bounds, and stacking a second scroll consumer around a
    // fixed-aspect box only fights the pager. Other card types can outgrow
    // the slot, so they scroll.
    val isIframe = content is LovelaceCard.Unsupported && content.url != null
    // A theme whose card identity IS a backdrop (Colourful Cards' gradients)
    // paints the per-card sky behind pinned Lovelace slots too, so a button / IR
    // remote card reads as the same colourful tile as the entity cards rather
    // than a flat near-black panel (the IR "TAP TO RUN" cards were the visible
    // mismatch). Keyed on the slot's STABLE id so each card keeps its hue across
    // recompositions and palette-set switches; null on the plain themes keeps the
    // R1.Bg surface byte-identical. The ink rides in on LocalCardInk so the deck
    // header line turns white over the gradient (the card's own face owns the
    // rest of its content).
    val theme = LocalR1Theme.current
    val colorfulConfig = LocalColorfulCardsConfig.current
    val auxStyle = remember(theme, item.id, colorfulConfig) {
        theme.auxCardStyle(item.id, null, colorfulConfig)
    }
    Box(
        modifier = modifier.then(
            if (isIframe) {
                Modifier
            } else {
                Modifier.pointerInput(item.id) {
                    detectTapGestures(onLongPress = { hooks.onOpenCardMenu(item) })
                }
            },
        ),
        contentAlignment = Alignment.Center,
    ) {
        DeckCardSurface(
            scrollable = !isIframe,
            modifier = surfaceModifier,
            backdrop = auxStyle?.backdrop,
            scrim = auxStyle?.scrim,
            anchor = auxStyle?.anchor,
        ) {
            CompositionLocalProvider(
                LocalCardInk provides (auxStyle?.ink ?: LocalCardInk.current),
                // Tell a card face (e.g. the button / IR card) that the slot already
                // painted a colourful backdrop, so it renders a transparent face and
                // lets the gradient show instead of stamping its own dark plate.
                LocalCardBackdropPainted provides (auxStyle != null),
            ) {
                // Identity header above EVERY face: the SAME derived title the
                // jump sheet shows for this slot (displayName -> deckCardTitle on
                // the stored card), so the stack and the pip's jump list agree on
                // what each card is called. Always on; suppressing it for
                // self-naming configs left button cards (IR remotes always carry
                // `name`) and titled Lovelace cards with no visible identity line
                // in the deck; see [deckCardHeaderTitle]. Deliberately a bare
                // micro text line, not a chip or framed band: the heavier
                // per-card type chip was tried and rejected. Reads LocalCardInk so
                // it turns white over a colourful backdrop, grey on the plain themes.
                Text(
                    text = deckCardHeaderTitle(item.card),
                    style = R1.labelMicro,
                    color = LocalCardInk.current.muted,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 2.dp),
                )
                LovelaceCardRenderer(
                    card = content,
                    stateMap = states.sliceFor(content),
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
 * The content-hugging surface of a Lovelace deck slot.
 *
 * Measure contract: the
 * [androidx.compose.foundation.verticalScroll] layout node measures its child
 * with an unbounded max height and reports min(content height, incoming max),
 * so under the centering Box's loose constraints the surface
 *  - WRAPS short content (a button card is button-height),
 *  - CAPS at the slot height when content is taller, with the overflow
 *    reachable through the scroll (whose range is exactly the overflow,
 *    i.e. zero when the content fits, so a short card consumes no drags
 *    beyond what nested scroll hands to the pager).
 *
 * The surface paints the plain near-black [R1.Bg] by default: invisible against
 * the page background, but it keeps the layer opaque so the shadow [modifier]
 * the caller supplies doesn't bleed through the gaps a stack card leaves
 * between its children. No padding: the card's own chrome is the visible
 * panel and any deck-side inset would re-introduce the full-width frame
 * this fixes. When the caller supplies a [backdrop] (a theme whose card identity
 * IS a gradient, e.g. Colourful Cards) it is painted instead, with the optional
 * [scrim] + [anchor] layered over it exactly as the entity cards layer them, so
 * a pinned Lovelace slot reads as the same colourful tile.
 *
 * Iframe slots pass [scrollable] = false: the WebView owns vertical drags
 * inside its bounds and a second scroll consumer around a fixed-aspect box
 * only fights the pager.
 *
 * Touch handling on the scroll is enabled ONLY while the content actually
 * overflows (maxValue > 0). A Compose scrollable claims drag gestures
 * regardless of its scroll range, and once a drag with enough vertical drift
 * crosses ITS slop the gesture-conflict arbitration consults only the NEAREST
 * ancestor scroll container (the vertical deck), never the horizontal tab
 * pager two levels up, so this inner scroll consumed the swipe outright and
 * left/right tab swipes died on every card face. With the gate, a fitting
 * card face is gesture-inert and cross-axis swipes hand off to the tab pager
 * exactly as they do over the FULLSCREEN deck; only genuinely overflowing
 * content keeps the (needed) inner scroll.
 */
@Composable
internal fun DeckCardSurface(
    scrollable: Boolean,
    modifier: Modifier = Modifier,
    /** Theme backdrop for the slot. Null = the plain near-black [R1.Bg] (every
     *  theme without a backdrop card identity); non-null = paint it, then the
     *  optional [scrim] and [anchor] over it, the same layering the entity cards
     *  use for the colourful gradient. */
    backdrop: Brush? = null,
    scrim: Brush? = null,
    anchor: Brush? = null,
    content: @Composable () -> Unit,
) {
    val scrollState = rememberScrollState()
    val bodyModifier = if (scrollable) {
        Modifier.verticalScroll(scrollState, enabled = scrollState.maxValue > 0)
    } else {
        Modifier
    }
    val surfaceBackground = if (backdrop == null) {
        Modifier.background(R1.Bg)
    } else {
        var sky = Modifier.background(backdrop)
        scrim?.let { sky = sky.background(it) }
        anchor?.let { sky = sky.background(it) }
        sky
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(surfaceBackground)
            .then(bodyModifier),
    ) {
        content()
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
