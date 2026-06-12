package com.github.itskenny0.r1ha.feature.cardstack

import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.lovelace.LovelaceParser
import com.github.itskenny0.r1ha.core.lovelace.parseCardJsonBlob
import com.github.itskenny0.r1ha.core.prefs.DeckRef
import com.github.itskenny0.r1ha.core.prefs.FavoritePage
import com.github.itskenny0.r1ha.core.prefs.effectiveDeckRefs
import com.github.itskenny0.r1ha.core.prefs.resolvedPinnedCardIds
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.serialization.json.JsonPrimitive

/**
 * One slot in a page's rendered deck: either a favourite entity (the classic
 * card) or a pinned Lovelace card painted by the dashboards engine. The two
 * share index space, wheel routing, the jump sheet, reorder and persistence;
 * pattern-match where behaviour diverges (the wheel and the service-call paths
 * treat a [Card] as "no entity").
 */
sealed interface DeckItem {
    /** Stable pager / list key. Unique within a page even for two identical
     *  card configs (each pinned card carries its own stored id). */
    val key: String

    data class Entity(val state: EntityState) : DeckItem {
        override val key: String get() = "e:" + state.id.value
    }

    data class Card(
        val card: LovelaceCard,
        /** The stored raw config blob, kept verbatim for the editor. */
        val raw: String,
        /** The page-stored stable id (see FavoritePage.pinnedCardIds). */
        val id: String,
    ) : DeckItem {
        override val key: String get() = "c:$id"
    }
}

/** The entity behind a deck item, or null for Lovelace cards. */
fun DeckItem.entityStateOrNull(): EntityState? = (this as? DeckItem.Entity)?.state

/** The storage ref this rendered item came from. */
fun DeckItem.toDeckRef(): DeckRef = when (this) {
    is DeckItem.Entity -> DeckRef.Entity(state.id.value)
    is DeckItem.Card -> DeckRef.Card(id)
}

/** Human-readable row label: entity friendly name, or the card's best title. */
fun DeckItem.displayName(): String = when (this) {
    is DeckItem.Entity -> state.friendlyName.ifBlank { state.id.value }
    is DeckItem.Card -> deckCardTitle(card)
}

/** Micro sub-label: entity domain, or the card's type. */
fun DeckItem.displayKind(): String = when (this) {
    is DeckItem.Entity -> state.id.domain.prefix.uppercase()
    is DeckItem.Card -> card.type.uppercase().replace('-', ' ')
}

/**
 * Best-effort one-line title for a Lovelace card, mirroring how HA's picker
 * summarises cards: explicit title/name/heading first, then the bound entity,
 * then the iframe url, finally the bare type. Reads the raw JSON so the rule
 * covers every card type uniformly, including ones parsed to Unsupported.
 */
fun deckCardTitle(card: LovelaceCard): String {
    for (key in listOf("title", "name", "heading")) {
        val v = (card.raw[key] as? JsonPrimitive)?.content
        if (!v.isNullOrBlank()) return v
    }
    (card.raw["content"] as? JsonPrimitive)?.content
        ?.lineSequence()?.firstOrNull { it.isNotBlank() }
        ?.let { return it.trim().removePrefix("#").trim().take(48) }
    (card.raw["entity"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { return it }
    (card.raw["url"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { return it }
    return card.type.uppercase().replace('-', ' ')
}

/**
 * Parse stored pinned-card JSON blobs into renderable cards. Unparseable
 * entries (hand-edited JSON gone wrong) are dropped with a log rather than
 * sinking the page; the management sheet still lists them for repair since it
 * works on the raw strings.
 */
internal fun parsePinnedCards(raw: List<String>): List<LovelaceCard> =
    raw.mapNotNull { parsePinnedCard(it) }

/** Single-blob variant of [parsePinnedCards]; null = unparseable (logged). */
internal fun parsePinnedCard(blob: String): LovelaceCard? {
    val obj = parseCardJsonBlob(blob) ?: run {
        R1Log.w("LovelaceDeck", "unparseable pinned card dropped: ${blob.take(80)}")
        return null
    }
    return runCatching { LovelaceParser.parseCard(obj) }
        .onFailure { R1Log.w("LovelaceDeck", "pinned card parse failed: ${it.message}") }
        .getOrNull()
}

/**
 * Materialize a page's deck: walk [FavoritePage.effectiveDeckRefs] and resolve
 * each ref into a [DeckItem]. Entity refs resolve through [materializeEntity]
 * (which applies renames / hide-when-unavailable and returns null while HA
 * hasn't sent state); card refs parse through [parseCard] (null for broken
 * blobs) and then gate on [cardIsVisible] (a conditional card whose conditions
 * resolve hidden must not occupy a full-page deck slot; see
 * [deckConditionContext] for the policy). Unresolvable / hidden refs drop out,
 * so the rendered deck is always a coherent subset of storage; the reorder
 * machinery already translates rendered indices into storage refs.
 */
fun buildDeckItems(
    page: FavoritePage,
    materializeEntity: (String) -> EntityState?,
    parseCard: (String) -> LovelaceCard?,
    cardIsVisible: (LovelaceCard) -> Boolean = { true },
): List<DeckItem> {
    if (page.pinnedCards.isEmpty()) {
        // Fast path for the overwhelmingly common favourites-only page: no ref
        // decode, no id resolution, exactly the legacy materialisation.
        return page.favorites.mapNotNull { id ->
            materializeEntity(id)?.let { DeckItem.Entity(it) }
        }
    }
    val cardIds = page.resolvedPinnedCardIds()
    val rawById = HashMap<String, String>(page.pinnedCards.size)
    for (i in page.pinnedCards.indices) rawById[cardIds[i]] = page.pinnedCards[i]
    return page.effectiveDeckRefs().mapNotNull { ref ->
        when (ref) {
            is DeckRef.Entity -> materializeEntity(ref.entityId)?.let { DeckItem.Entity(it) }
            is DeckRef.Card -> {
                val raw = rawById[ref.cardId] ?: return@mapNotNull null
                parseCard(raw)
                    ?.takeIf(cardIsVisible)
                    ?.let { DeckItem.Card(card = it, raw = raw, id = ref.cardId) }
            }
        }
    }
}
