package com.github.itskenny0.r1ha.feature.cardstack

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.github.itskenny0.r1ha.core.theme.LocalCardPanelColor
import com.github.itskenny0.r1ha.core.theme.LocalColorfulCardsConfig
import com.github.itskenny0.r1ha.core.theme.LocalR1Theme
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens

/**
 * One card in the placeholder stack: enough to recover the card's real BACKDROP
 * colour ([entityId] keys the theme's per-entity palette; [accent] is the per-card
 * accent override). A null [entityId] (a pinned Lovelace card / empty slot) falls
 * back to the theme's card panel colour.
 */
internal data class PlaceholderCardSpec(val entityId: String?, val accent: Color?)

/**
 * The lightweight stand-in a deck page renders WHILE the horizontal (tab) pager is
 * mid-swipe and this page is not the settled one, shown ONLY on devices where direct
 * rendering of the real deck can't hold the swipe smoothly (the caller gates this on
 * measured swipe frame timing). The real page costs tens of ms of UI-thread
 * measure/layout when the pager prefetches it into view; this is a handful of
 * draw-only nodes, so the incoming page is nearly free to bring on screen, and the
 * real deck composes once at rest after the swipe settles.
 *
 * It is a faithful skeleton of the actual deck, not a generic card:
 *  - it renders one placeholder card PER real card ([cards], already capped to what
 *    fits), so a five-card page reads as a five-card stack and a one-card page as a
 *    single card, the count is known before the real deck composes;
 *  - each card is painted in its OWN real backdrop (the theme's per-entity colour via
 *    [com.github.itskenny0.r1ha.core.theme.R1Theme.auxCardStyle]), so the colours
 *    match what's about to appear, no grey slabs, no colour pop at settle;
 *  - [singleLarge] (fullscreen, non-peek layout) shows one full-height card; otherwise
 *    the cards stack at the deck's spacing so neighbours peek, mirroring the peek/
 *    dynamic decks.
 * Only the inner elements are introduced (a single cheap fade-and-rise, no perpetual
 * shimmer, which matters on the GPU-bound low-end devices this path targets).
 */
@Composable
internal fun DeckPagePlaceholder(
    cards: List<PlaceholderCardSpec>,
    title: String,
    topInset: Dp,
    singleLarge: Boolean,
    modifier: Modifier = Modifier,
) {
    val theme = LocalR1Theme.current
    val config = LocalColorfulCardsConfig.current
    val panelColor = LocalCardPanelColor.current
    // Real per-card backdrop brushes, resolved once (auxCardStyle is non-composable
    // and cheap, but keyed so we don't rebuild on every recomposition). Null styling
    // (dark themes, or a Lovelace card with no entity) uses the flat card panel.
    val brushes: List<Brush> = remember(cards, theme.id, config, panelColor) {
        cards.map { spec ->
            spec.entityId?.let { theme.auxCardStyle(it, spec.accent, config)?.backdrop }
                ?: SolidColor(panelColor)
        }
    }
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        reveal.animateTo(1f, animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing))
    }
    val maxCardWidth = rememberResponsiveDimens().maxContentWidth
    val cardShape = remember { RoundedCornerShape(14.dp) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(top = topInset, bottom = 24.dp)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        val band = maxHeight
        // One big card when the layout shows one at a time; otherwise size each so
        // ~two fill the band and the next peeks, the look of the stacked decks.
        val eachHeight = if (singleLarge || brushes.size <= 1) band * 0.92f else band * 0.46f
        Column(
            modifier = Modifier.widthIn(max = maxCardWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(R1.space.m),
        ) {
            brushes.forEachIndexed { index, brush ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(eachHeight)
                        .clip(cardShape)
                        .drawBehind { drawRect(brush) },
                ) {
                    // The card's signature right-edge value-bar rail, dim.
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 14.dp, top = 24.dp, bottom = 24.dp)
                            .width(3.dp)
                            .fillMaxHeight()
                            .clip(R1.ShapeM)
                            .background(Color.White.copy(alpha = 0.14f))
                            .graphicsLayer { alpha = reveal.value },
                    )
                    // Only the leading (focused) card carries the title + skeleton so
                    // the stand-in stays cheap; the rest are just the right colour.
                    if (index == 0) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 22.dp, end = 40.dp, top = 24.dp, bottom = 24.dp)
                                .graphicsLayer {
                                    alpha = reveal.value
                                    translationY = (1f - reveal.value) * 12.dp.toPx()
                                },
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(9.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.30f)))
                                Spacer(Modifier.width(8.dp))
                                SkeletonBar(76.dp, 9.dp)
                            }
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = title.uppercase(),
                                style = R1.labelMicro,
                                color = Color.White.copy(alpha = 0.75f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(18.dp))
                            SkeletonBar(120.dp, 46.dp)
                        }
                    }
                }
            }
        }
    }
}

/** A dim rounded slab on the already-correct card backdrop. White-tinted so it reads
 *  on both the vivid gradient themes and the dark themes. */
@Composable
private fun SkeletonBar(width: Dp, height: Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(R1.ShapeM)
            .background(Color.White.copy(alpha = 0.13f)),
    )
}
