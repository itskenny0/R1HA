package com.github.itskenny0.r1ha.feature.cardstack

import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCondition
import com.github.itskenny0.r1ha.core.lovelace.LovelaceConditionContext
import com.github.itskenny0.r1ha.feature.dashboards.cards.EntityStates
import com.github.itskenny0.r1ha.feature.dashboards.cards.cardWillRender
import com.github.itskenny0.r1ha.feature.dashboards.cards.collectConditionEntities
import com.github.itskenny0.r1ha.feature.dashboards.cards.collectEntityIds
import com.github.itskenny0.r1ha.feature.dashboards.cards.evaluateConditions
import com.github.itskenny0.r1ha.feature.dashboards.cards.resolveUserPersonState
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Visibility policy for pinned Lovelace cards in the card-stack deck.
 *
 * A deck slot is a full-page panel, so a conditional card that resolves hidden
 * must not occupy a slot at all (an empty full-height page is exactly the
 * failure being fixed). The ViewModel evaluates each pinned card's top-level
 * conditional layers at deck-build time with the context built here and drops
 * hidden cards from the rendered deck; they stay in storage and reappear the
 * moment their conditions pass again (the rebuild keys on the state cache, so
 * a gating entity's change re-runs this evaluation).
 *
 * Per-condition semantics in the deck, where they differ from a dashboard:
 *  - state / numeric / and / or / not: evaluated against the live state cache,
 *    fail closed on missing data, exactly like the dashboards renderer.
 *  - user: evaluated against the repository's cached current-user id; an
 *    unknown user fails closed (HA parity).
 *  - location: resolved through the current user's person entity when that
 *    person is in the observed state union; otherwise fails closed.
 *  - screen (media query) and view_columns: ALWAYS VISIBLE. These breakpoints
 *    describe responsive dashboard layout space (window width, column count)
 *    that has no analogue in the single-column full-page deck; hiding a whole
 *    deck page because the R1's 640x480 panel is "narrow" would hide content
 *    the user explicitly pinned. The context below carries no window metrics
 *    and no column count, which both evaluators treat as pass.
 *  - time: evaluated with the local clock at deck-build time; it re-evaluates
 *    on the next state-cache or settings emission rather than on a dedicated
 *    boundary timer (deck rebuilds are frequent enough in practice, and a
 *    per-card timer in the VM is not worth the plumbing for this edge).
 */
internal fun deckConditionContext(
    currentUserId: String?,
    statesByRawId: Map<String, EntityState>,
    now: LocalTime = LocalTime.now(),
    weekday: String = haWeekdayToken(LocalDate.now().dayOfWeek),
): LovelaceConditionContext = LovelaceConditionContext(
    currentUserId = currentUserId,
    // No window metrics: `screen` media queries become non-evaluable and the
    // evaluator fails them OPEN, implementing the always-visible policy above.
    windowWidthPx = null,
    windowHeightPx = null,
    nowSecondsOfDay = now.toSecondOfDay(),
    weekday = weekday,
    // No column count: `view_columns` passes unconditionally (HA behaviour for
    // an unknown count), implementing the always-visible policy above.
    maxColumns = null,
    contextEntityId = null,
    personStateForUser = { resolveUserPersonState(currentUserId, statesByRawId) },
)

/** HA's lowercase three-letter weekday token (sun..sat) for [dayOfWeek]. */
internal fun haWeekdayToken(dayOfWeek: DayOfWeek): String = when (dayOfWeek) {
    DayOfWeek.MONDAY -> "mon"
    DayOfWeek.TUESDAY -> "tue"
    DayOfWeek.WEDNESDAY -> "wed"
    DayOfWeek.THURSDAY -> "thu"
    DayOfWeek.FRIDAY -> "fri"
    DayOfWeek.SATURDAY -> "sat"
    DayOfWeek.SUNDAY -> "sun"
}

/**
 * Whether a pinned [card] should occupy a deck slot right now. Delegates to the
 * renderer-layer [cardWillRender], which recurses through stacked conditional
 * wrappers (a `visibility:` gate on a `type: conditional` parses as nested
 * [LovelaceCard.Conditional] layers) with the attributes-aware evaluator.
 */
internal fun deckCardIsVisible(
    card: LovelaceCard,
    states: EntityStates,
    context: LovelaceConditionContext,
): Boolean = cardWillRender(card, states, context)

/**
 * Strip the top-level conditional layers off a deck card for rendering. The
 * ViewModel already gated those layers at deck-build time (a hidden card never
 * occupies a slot), so the slot renders the wrapped card directly. Re-running
 * the wrapper inside the slot would re-evaluate `screen` / `view_columns`
 * against the real window, contradicting the deck's always-visible policy for
 * those kinds and collapsing the slot to the empty page this fix removes.
 * Conditionals nested INSIDE the content (e.g. in a vertical-stack child) are
 * untouched; they evaluate live in the renderer like on a dashboard.
 */
internal fun unwrapDeckConditional(card: LovelaceCard): LovelaceCard {
    var c = card
    while (c is LovelaceCard.Conditional) c = c.card
    return c
}

/**
 * Collect the entity ids that gate a pinned card's DECK-SLOT visibility: the
 * condition entities of its top-level conditional layers. Used to extend the
 * ViewModel's observed-id union so a gating entity's state change triggers a
 * deck rebuild (and so the build-time evaluation sees its state). Conditions
 * nested inside the content don't affect slot visibility and are excluded here;
 * they are still observed for rendering via the screen-level union, which walks
 * the whole card with [collectEntityIds].
 */
internal fun collectDeckVisibilityEntityIds(card: LovelaceCard, sink: MutableSet<String>) {
    var c = card
    while (c is LovelaceCard.Conditional) {
        c.conditions.forEach { collectConditionEntities(it, sink) }
        c = c.card
    }
}

/**
 * One pinned card's render diagnostics: what the deck decided and why. Pure so
 * the summary path is unit-testable; the ViewModel logs the formatted lines at
 * INFO under the "Deck.render" tag, once per changed page build.
 */
internal data class DeckCardRenderInfo(
    /** The page-stored stable card id. */
    val cardId: String,
    /** The card's (outermost) type token. */
    val type: String,
    val visible: Boolean,
    /** Human token for the first failing condition when hidden, else null. */
    val hiddenBy: String?,
    /** Referenced entities with live state vs. total referenced. */
    val statesPresent: Int,
    val statesTotal: Int,
)

/** Build the diagnostics record for one pinned card. */
internal fun deckCardRenderInfo(
    cardId: String,
    card: LovelaceCard,
    states: EntityStates,
    context: LovelaceConditionContext,
): DeckCardRenderInfo {
    val ids = LinkedHashSet<String>()
    collectEntityIds(card, ids)
    val present = ids.count { states.byRaw(it) != null }
    val visible = deckCardIsVisible(card, states, context)
    return DeckCardRenderInfo(
        cardId = cardId,
        type = card.type,
        visible = visible,
        hiddenBy = if (visible) null else describeFirstFailingCondition(card, states, context),
        statesPresent = present,
        statesTotal = ids.size,
    )
}

/**
 * The first failing condition across the card's top-level conditional layers,
 * as a short human token ("state(binary_sensor.door)", "user", ...). Null when
 * every layer passes (the card can still be "hidden" only if it isn't actually
 * hidden, so callers only consult this when visibility already failed).
 */
internal fun describeFirstFailingCondition(
    card: LovelaceCard,
    states: EntityStates,
    context: LovelaceConditionContext,
): String? {
    var c = card
    while (c is LovelaceCard.Conditional) {
        c.conditions.firstOrNull { !evaluateConditions(listOf(it), states, context) }
            ?.let { return describeCondition(it) }
        c = c.card
    }
    return null
}

/** Short log token for a condition kind (plus its gating entity when it has one). */
internal fun describeCondition(condition: LovelaceCondition): String = when (condition) {
    is LovelaceCondition.StateEquals ->
        (if (condition.negate) "state_not(" else "state(") + (condition.entityId ?: "?") + ")"
    is LovelaceCondition.NumericState -> "numeric_state(" + (condition.entityId ?: "?") + ")"
    is LovelaceCondition.And -> "and"
    is LovelaceCondition.Or -> "or"
    is LovelaceCondition.Not -> "not"
    is LovelaceCondition.User -> "user"
    is LovelaceCondition.Screen -> "screen"
    is LovelaceCondition.Time -> "time"
    is LovelaceCondition.Location -> "location"
    is LovelaceCondition.ViewColumns -> "view_columns"
    LovelaceCondition.Never -> "never"
    LovelaceCondition.AlwaysTrue -> "always"
}

/**
 * Format a page's per-slot diagnostics into the single line the ViewModel logs.
 * Deterministic for identical inputs, so its hashCode doubles as the
 * "did this page's rendered composition change" guard that keeps unchanged
 * pages from re-logging on every state tick.
 */
internal fun deckRenderSummary(pageId: String, cards: List<DeckCardRenderInfo>): String =
    cards.joinToString(
        prefix = "page=$pageId cards=${cards.size} ",
        separator = " ",
    ) { info ->
        val visibility = if (info.visible) "visible" else "hidden(${info.hiddenBy ?: "?"})"
        "[${info.cardId} type=${info.type} $visibility states=${info.statesPresent}/${info.statesTotal}]"
    }
