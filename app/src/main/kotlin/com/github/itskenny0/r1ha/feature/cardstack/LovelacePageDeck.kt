package com.github.itskenny0.r1ha.feature.cardstack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.lovelace.LovelaceParser
import com.github.itskenny0.r1ha.core.lovelace.parseCardJsonBlob
import com.github.itskenny0.r1ha.core.theme.LocalHaRepository
import com.github.itskenny0.r1ha.core.theme.LocalHaServerUrl
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.feature.dashboards.cards.EntityStates
import com.github.itskenny0.r1ha.feature.dashboards.cards.LovelaceCardRenderer
import com.github.itskenny0.r1ha.feature.dashboards.cards.dispatchLovelaceAction
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Deck for a "cards page": a [com.github.itskenny0.r1ha.core.prefs.FavoritePage]
 * with no favourites and one or more [pinned Lovelace cards]. Each pinned card
 * gets a full deck slot, painted by the native dashboards engine — iframes,
 * markdown, gauges, tiles, anything LovelaceCardRenderer speaks.
 *
 * Visual language: each slot is a mounted instrument. The card content sits in
 * a bezel — hairline outer frame, accent corner ticks, a provenance chip
 * naming the card type, and a position counter in the lower corner — so a
 * pinned web page or gauge reads as a deliberately installed module of the
 * deck rather than a floating web fragment.
 *
 * Wheel: detents accumulate two-per-step and page the deck (these slots have
 * nothing for the wheel to actuate; navigation is the useful verb here, and a
 * two-detent threshold keeps a brushing touch from skipping a card).
 */
@Composable
internal fun LovelacePageDeck(
    cards: List<LovelaceCard>,
    states: EntityStates,
    accent: Color,
    isActive: Boolean,
    haRepository: HaRepository,
    serverUrl: String?,
    wheelSteps: SharedFlow<Int>,
    onMoreInfo: (String) -> Unit,
    onNavigatePath: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onManageCards: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { cards.size })
    val scope = rememberCoroutineScope()

    // Wheel-to-page: two detents step one card, matching the select-cycle
    // accumulator threshold so the wheel vocabulary stays consistent. Only the
    // active page's deck consumes; neighbours ignore the shared flow.
    if (isActive) {
        LaunchedEffect(pagerState, wheelSteps) {
            var accum = 0
            wheelSteps.collect { sign ->
                accum += sign
                val step = when {
                    accum >= 2 -> -1   // wheel up = previous card
                    accum <= -2 -> +1  // wheel down = next card
                    else -> 0
                }
                if (step != 0) {
                    accum = 0
                    val target = (pagerState.currentPage + step)
                        .coerceIn(0, (cards.size - 1).coerceAtLeast(0))
                    if (target != pagerState.currentPage) {
                        launch { pagerState.animateScrollToPage(target) }
                    }
                }
            }
        }
    }

    VerticalPager(
        state = pagerState,
        contentPadding = PaddingValues(top = 64.dp, bottom = 14.dp),
        modifier = modifier.fillMaxSize(),
        key = { idx -> "pinned-$idx" },
    ) { idx ->
        val card = cards.getOrNull(idx) ?: return@VerticalPager
        PinnedCardSlot(
            card = card,
            states = states,
            accent = accent,
            index = idx,
            count = cards.size,
            haRepository = haRepository,
            serverUrl = serverUrl,
            onAction = { action ->
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
                        stateLookup = { rawId -> states.byRaw(rawId) },
                    )
                }
            },
            onManageCards = onManageCards,
        )
    }
}

@Composable
private fun PinnedCardSlot(
    card: LovelaceCard,
    states: EntityStates,
    accent: Color,
    index: Int,
    count: Int,
    haRepository: HaRepository,
    serverUrl: String?,
    onAction: (LovelaceAction) -> Unit,
    onManageCards: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(R1.ShapeM)
                .background(Color(0xFF111111))
                .border(1.dp, R1.Hairline, R1.ShapeM)
                // Bezel ticks: four accent corner brackets, drawn (not
                // composed) so they cost nothing per frame. The 10px arms sit
                // 5px inside the hairline, giving the slot its instrument-
                // mount signature without competing with the card content.
                .drawBehind {
                    val arm = 10.dp.toPx()
                    val inset = 5.dp.toPx()
                    val w = size.width
                    val h = size.height
                    val stroke = 1.5.dp.toPx()
                    val c = accent.copy(alpha = 0.55f)
                    fun tick(x: Float, y: Float, dx: Float, dy: Float) {
                        drawLine(c, Offset(x, y), Offset(x + arm * dx, y), strokeWidth = stroke)
                        drawLine(c, Offset(x, y), Offset(x, y + arm * dy), strokeWidth = stroke)
                    }
                    tick(inset, inset, 1f, 1f)
                    tick(w - inset, inset, -1f, 1f)
                    tick(inset, h - inset, 1f, -1f)
                    tick(w - inset, h - inset, -1f, -1f)
                }
                .padding(10.dp),
        ) {
            // Provenance strip: accent pip + card type, long-press target for
            // management. Counter on the trailing edge mirrors the deck's
            // position vocabulary.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .background(accent),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = card.type.uppercase().replace('-', ' '),
                    style = R1.labelMicro,
                    fontWeight = FontWeight.SemiBold,
                    color = R1.InkSoft,
                    modifier = Modifier.r1Pressable(onClick = onManageCards),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "%02d / %02d".format(java.util.Locale.US, index + 1, count),
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
            Spacer(Modifier.size(8.dp))
            // Card body: the dashboards engine paints it. Scrollable when the
            // card is taller than the slot (entities lists); iframes size to
            // their aspect ratio and sit centred.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(
                    LocalHaRepository provides haRepository,
                    LocalHaServerUrl provides serverUrl,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        LovelaceCardRenderer(
                            card = card,
                            stateMap = states.sliceFor(card),
                            onAction = onAction,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Parse stored pinned-card JSON blobs into renderable cards. Unparseable
 * entries (hand-edited JSON gone wrong) are dropped with a log rather than
 * sinking the page; the management sheet still lists them for repair since it
 * works on the raw strings.
 */
internal fun parsePinnedCards(raw: List<String>): List<LovelaceCard> =
    raw.mapNotNull { blob ->
        val obj = parseCardJsonBlob(blob) ?: run {
            R1Log.w("LovelaceDeck", "unparseable pinned card dropped: ${blob.take(80)}")
            return@mapNotNull null
        }
        runCatching { LovelaceParser.parseCard(obj) }
            .onFailure { R1Log.w("LovelaceDeck", "pinned card parse failed: ${it.message}") }
            .getOrNull()
    }
