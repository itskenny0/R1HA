package com.github.itskenny0.r1ha.core.prefs

/**
 * Pure helpers for [FavoritePage.deckOrder]: the interleaved ordering of a
 * page's favourite entities and pinned Lovelace cards. Storage of record stays
 * [FavoritePage.favorites] + [FavoritePage.pinnedCards]; deckOrder only says
 * how the two interleave. Every function here is side-effect free so the
 * ordering contract is unit-testable without DataStore or Compose.
 */

/** One slot in a page's deck, resolved from a deckOrder ref. */
sealed interface DeckRef {
    data class Entity(val entityId: String) : DeckRef
    data class Card(val cardId: String) : DeckRef
}

private const val ENTITY_PREFIX = "e:"
private const val CARD_PREFIX = "c:"

/** Encode a ref into its stored "e:<id>" / "c:<id>" string form. */
fun DeckRef.encode(): String = when (this) {
    is DeckRef.Entity -> ENTITY_PREFIX + entityId
    is DeckRef.Card -> CARD_PREFIX + cardId
}

/** Decode a stored ref; null for malformed or unknown-prefix entries (a newer
 *  build's ref kind degrades to "not present" rather than crashing). */
fun decodeDeckRef(raw: String): DeckRef? = when {
    raw.startsWith(ENTITY_PREFIX) && raw.length > ENTITY_PREFIX.length ->
        DeckRef.Entity(raw.substring(ENTITY_PREFIX.length))
    raw.startsWith(CARD_PREFIX) && raw.length > CARD_PREFIX.length ->
        DeckRef.Card(raw.substring(CARD_PREFIX.length))
    else -> null
}

/** Fresh unique id for a newly pinned card. */
fun newPinnedCardId(): String =
    "lc" + java.util.UUID.randomUUID().toString().replace("-", "").take(10)

/**
 * Positional card ids with DETERMINISTIC fallbacks: index `i` of
 * [FavoritePage.pinnedCards] resolves to `pinnedCardIds[i]` when present and
 * unique, otherwise to a "legacy-i" placeholder (suffixed on the rare
 * collision). Determinism matters: the rendering path resolves ids without
 * writing, and a later mutation persists exactly these ids via
 * [healPinnedCardIds], so an id the user long-pressed always matches the id
 * a write path looks up. Placeholders never appear in a stored deckOrder
 * (old blobs that lack ids also lack deckOrder), so their positional
 * fragility is confined to blobs no new build has written yet.
 */
fun FavoritePage.resolvedPinnedCardIds(): List<String> {
    val seen = HashSet<String>()
    return pinnedCards.indices.map { i ->
        val stored = pinnedCardIds.getOrNull(i)?.takeIf { it.isNotBlank() && seen.add(it) }
        stored ?: generateSequence(0) { it + 1 }
            .map { n -> if (n == 0) "legacy-$i" else "legacy-$i-$n" }
            .first { seen.add(it) }
    }
}

/**
 * Ensure [FavoritePage.pinnedCardIds] is a same-length, unique-id companion
 * of [FavoritePage.pinnedCards] by persisting [resolvedPinnedCardIds].
 * Idempotent and deterministic; call on every write path that touches pinned
 * cards.
 */
fun healPinnedCardIds(page: FavoritePage): FavoritePage {
    val resolved = page.resolvedPinnedCardIds()
    return if (resolved == page.pinnedCardIds) page else page.copy(pinnedCardIds = resolved)
}

/**
 * The page's effective deck order as resolved refs. This is THE ordering
 * contract: stored deckOrder entries that still resolve come first (first
 * occurrence wins), then favourites missing from it in favourites order, then
 * cards missing from it in card order. An empty deckOrder therefore degrades
 * to favourites-then-cards, and a stale ref (deleted item) simply drops out.
 */
fun FavoritePage.effectiveDeckRefs(): List<DeckRef> {
    val cardIds = resolvedPinnedCardIds()
    val cardIdSet = cardIds.toSet()
    val favoriteSet = favorites.toSet()
    val out = ArrayList<DeckRef>(favorites.size + cardIds.size)
    val seen = HashSet<String>()
    for (raw in deckOrder) {
        val ref = decodeDeckRef(raw) ?: continue
        val resolves = when (ref) {
            is DeckRef.Entity -> ref.entityId in favoriteSet
            is DeckRef.Card -> ref.cardId in cardIdSet
        }
        if (resolves && seen.add(raw)) out.add(ref)
    }
    for (id in favorites) {
        val key = ENTITY_PREFIX + id
        if (seen.add(key)) out.add(DeckRef.Entity(id))
    }
    for (id in cardIds) {
        val key = CARD_PREFIX + id
        if (seen.add(key)) out.add(DeckRef.Card(id))
    }
    return out
}

/**
 * Encode [refs] for storage, collapsing to the empty list when they already
 * equal the page's default favourites-then-cards order. Writing empty in the
 * canonical case keeps favourites-only pages byte-identical with pre-deckOrder
 * storage (users who never pin a card see zero settings churn) and lets old
 * builds reproduce the order from favourites alone.
 */
fun canonicalDeckOrder(page: FavoritePage, refs: List<DeckRef>): List<String> {
    val default = page.favorites.map { DeckRef.Entity(it) as DeckRef } +
        page.resolvedPinnedCardIds().map { DeckRef.Card(it) }
    return if (refs == default) emptyList() else refs.map { it.encode() }
}

/**
 * Move [fromRef] so it lands at [toRef]'s slot in the effective deck order
 * (matching a drag of one rendered row onto another). Ref-addressed rather
 * than index-addressed because the rendered deck can be a strict subset of
 * the stored items (entities hidden while unavailable, blobs that failed to
 * parse), so rendered indices don't map 1:1 onto storage indices.
 *
 * Also rewrites [FavoritePage.favorites] and the pinned-card lists into their
 * new relative orders, so an old build reading the page (no deckOrder
 * support) still sees the entities in the dragged order.
 */
fun FavoritePage.withDeckMove(fromRef: DeckRef, toRef: DeckRef): FavoritePage {
    if (fromRef == toRef) return this
    val healed = healPinnedCardIds(this)
    val refs = healed.effectiveDeckRefs().toMutableList()
    val fromIdx = refs.indexOf(fromRef)
    val toIdx = refs.indexOf(toRef)
    if (fromIdx < 0 || toIdx < 0) return this
    val item = refs.removeAt(fromIdx)
    refs.add(refs.indexOf(toRef).let { if (fromIdx < toIdx) it + 1 else it }.coerceIn(0, refs.size), item)
    return healed.reorderedToRefs(refs)
}

/**
 * Move by RENDERED indices, resolving refs against the freshly-read page
 * INSIDE the settings transform. The rendered deck is the page's effective
 * refs filtered to [visibleRefs] (encoded; captured once at gesture time; a
 * SET, so it stays valid while the order changes underneath). Translating here
 * rather than at the call site is what makes fast drags compose: the
 * drag-reorder gesture fires several adjacent swaps per frame, each assuming
 * the previous one landed, but the ViewModel's state snapshot only updates
 * after the async settings round-trip, so call-site translation read stale
 * positions and scrambled the deck. Inside the mutex-serialized transform each
 * swap sees the order its predecessor actually produced.
 */
fun FavoritePage.withDeckMoveByRenderedIndex(
    fromIndex: Int,
    toIndex: Int,
    visibleRefs: Set<String>,
): FavoritePage {
    if (fromIndex == toIndex) return this
    val healed = healPinnedCardIds(this)
    val rendered = healed.effectiveDeckRefs().filter { it.encode() in visibleRefs }
    val fromRef = rendered.getOrNull(fromIndex) ?: return this
    val toRef = rendered.getOrNull(toIndex.coerceIn(0, rendered.size - 1)) ?: return this
    return healed.withDeckMove(fromRef, toRef)
}

/** Rebuild the page's stored lists to match [refs]' relative orders and stamp
 *  the (possibly canonical-empty) deckOrder. */
private fun FavoritePage.reorderedToRefs(refs: List<DeckRef>): FavoritePage {
    val cardIds = resolvedPinnedCardIds()
    val cardByById = pinnedCards.indices.associateBy({ cardIds[it] }, { pinnedCards[it] })
    val newFavorites = refs.filterIsInstance<DeckRef.Entity>().map { it.entityId }
    val newCardIds = refs.filterIsInstance<DeckRef.Card>().map { it.cardId }
    val newCards = newCardIds.mapNotNull { cardByById[it] }
    val reordered = copy(
        favorites = newFavorites,
        pinnedCards = newCards,
        pinnedCardIds = if (newCards.isEmpty()) emptyList() else newCardIds,
    )
    return reordered.copy(deckOrder = canonicalDeckOrder(reordered, refs))
}

/**
 * Append freshly pinned cards (raw config JSON strings). New cards land at the
 * end of the deck, which is what the default-order healing produces anyway, so
 * deckOrder only changes when it was already non-canonical.
 */
fun FavoritePage.withPinnedCardsAppended(
    raws: List<String>,
    idGen: () -> String = ::newPinnedCardId,
): FavoritePage {
    if (raws.isEmpty()) return this
    val healed = healPinnedCardIds(this)
    val used = healed.pinnedCardIds.toHashSet()
    val newIds = raws.map { generateSequence(idGen).first { id -> used.add(id) } }
    val grown = healed.copy(
        pinnedCards = healed.pinnedCards + raws,
        pinnedCardIds = healed.pinnedCardIds + newIds,
    )
    // A non-canonical interleave must list the new refs explicitly or the
    // healing pass would still append them at the end; canonical (empty)
    // deckOrder already means "cards at the end" so it stays empty.
    return if (grown.deckOrder.isEmpty()) grown
    else grown.copy(deckOrder = grown.deckOrder + newIds.map { DeckRef.Card(it).encode() })
}

/** Remove the pinned card with [cardId]; deckOrder heals via the stale-ref drop
 *  but is rewritten eagerly so storage doesn't accumulate dead refs. */
fun FavoritePage.withPinnedCardRemoved(cardId: String): FavoritePage {
    val healed = healPinnedCardIds(this)
    val idx = healed.pinnedCardIds.indexOf(cardId)
    if (idx < 0) return this
    val shrunk = healed.copy(
        pinnedCards = healed.pinnedCards.filterIndexed { i, _ -> i != idx },
        pinnedCardIds = healed.pinnedCardIds.filterIndexed { i, _ -> i != idx },
    )
    val refs = shrunk.effectiveDeckRefs()
    return shrunk.copy(deckOrder = canonicalDeckOrder(shrunk, refs))
}

/** Replace the raw config of the pinned card with [cardId]; id and deck slot
 *  are preserved so the edited card stays where it was. */
fun FavoritePage.withPinnedCardReplaced(cardId: String, newRaw: String): FavoritePage {
    val healed = healPinnedCardIds(this)
    val idx = healed.pinnedCardIds.indexOf(cardId)
    if (idx < 0) return this
    return healed.copy(
        pinnedCards = healed.pinnedCards.mapIndexed { i, raw -> if (i == idx) newRaw else raw },
    )
}

/** Remove a favourite and its deckOrder ref in one pass (stale refs would heal
 *  anyway; eager cleanup keeps the stored list honest). */
fun FavoritePage.withFavoriteRemoved(entityId: String): FavoritePage {
    if (entityId !in favorites) return this
    val ref = DeckRef.Entity(entityId).encode()
    return copy(
        favorites = favorites.filter { it != entityId },
        deckOrder = deckOrder.filter { it != ref },
    )
}
