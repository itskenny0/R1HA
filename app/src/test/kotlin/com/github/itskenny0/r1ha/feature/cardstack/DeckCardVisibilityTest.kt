package com.github.itskenny0.r1ha.feature.cardstack

import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCondition
import com.github.itskenny0.r1ha.core.prefs.FavoritePage
import com.github.itskenny0.r1ha.feature.dashboards.cards.EntityStates
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The deck-slot visibility policy for pinned conditional cards: what each
 * condition kind does when a Lovelace card lives in the card stack rather
 * than on a dashboard, plus the deck-build dropping and the render
 * diagnostics that report the decision.
 */
class DeckCardVisibilityTest {

    private fun state(
        id: String,
        rawState: String,
        attributes: Map<String, String> = emptyMap(),
    ) = EntityState(
        id = EntityId(id), friendlyName = id, area = null, isOn = rawState == "on",
        percent = null, raw = rawState.toDoubleOrNull(), lastChanged = Instant.EPOCH,
        isAvailable = rawState != "unavailable",
        rawState = rawState,
        attributesJson = if (attributes.isEmpty()) null else JsonObject(
            attributes.mapValues { JsonPrimitive(it.value) },
        ),
    )

    private fun markdown() = LovelaceCard.Markdown(
        raw = JsonObject(emptyMap()), title = null, content = "hi",
    )

    private fun conditional(vararg conditions: LovelaceCondition, inner: LovelaceCard = markdown()) =
        LovelaceCard.Conditional(
            raw = JsonObject(emptyMap()),
            conditions = conditions.toList(),
            card = inner,
        )

    private fun statesOf(vararg states: EntityState) =
        EntityStates.ofRaw(states.associateBy { it.id.value })

    // ── condition semantics in the deck ─────────────────────────────────────

    @Test fun `state condition shows when matched and hides when not`() {
        val card = conditional(LovelaceCondition.StateEquals("binary_sensor.door", "on"))
        val ctx = deckConditionContext(currentUserId = null, statesByRawId = emptyMap())
        assertThat(deckCardIsVisible(card, statesOf(state("binary_sensor.door", "on")), ctx)).isTrue()
        assertThat(deckCardIsVisible(card, statesOf(state("binary_sensor.door", "off")), ctx)).isFalse()
    }

    @Test fun `state condition with no live state fails closed`() {
        val card = conditional(LovelaceCondition.StateEquals("binary_sensor.door", "on"))
        val ctx = deckConditionContext(currentUserId = null, statesByRawId = emptyMap())
        assertThat(deckCardIsVisible(card, EntityStates.EMPTY, ctx)).isFalse()
    }

    @Test fun `user condition gates on the repository's current user`() {
        val card = conditional(LovelaceCondition.User(listOf("abc123")))
        val matching = deckConditionContext(currentUserId = "abc123", statesByRawId = emptyMap())
        val other = deckConditionContext(currentUserId = "zzz", statesByRawId = emptyMap())
        val unknown = deckConditionContext(currentUserId = null, statesByRawId = emptyMap())
        assertThat(deckCardIsVisible(card, EntityStates.EMPTY, matching)).isTrue()
        assertThat(deckCardIsVisible(card, EntityStates.EMPTY, other)).isFalse()
        // Unknown user fails closed, HA parity.
        assertThat(deckCardIsVisible(card, EntityStates.EMPTY, unknown)).isFalse()
    }

    @Test fun `screen media queries are always visible in the deck`() {
        // A full-page deck slot has no responsive layout space for the
        // breakpoint to describe; the deck context carries no window metrics so
        // even a query that would FAIL on the real window stays visible.
        val card = conditional(LovelaceCondition.Screen("(min-width: 100000px)"))
        val ctx = deckConditionContext(currentUserId = null, statesByRawId = emptyMap())
        assertThat(deckCardIsVisible(card, EntityStates.EMPTY, ctx)).isTrue()
    }

    @Test fun `view_columns conditions are always visible in the deck`() {
        val card = conditional(LovelaceCondition.ViewColumns(min = 3, max = null))
        val ctx = deckConditionContext(currentUserId = null, statesByRawId = emptyMap())
        assertThat(deckCardIsVisible(card, EntityStates.EMPTY, ctx)).isTrue()
    }

    @Test fun `location condition resolves the current user's person entity`() {
        val person = state("person.kenny", "home", attributes = mapOf("user_id" to "abc123"))
        val states = mapOf(person.id.value to person)
        val card = conditional(LovelaceCondition.Location(listOf("home")))
        val home = deckConditionContext(currentUserId = "abc123", statesByRawId = states)
        assertThat(deckCardIsVisible(card, statesOf(person), home)).isTrue()
        // Person unobserved -> fail closed.
        val noPerson = deckConditionContext(currentUserId = "abc123", statesByRawId = emptyMap())
        assertThat(deckCardIsVisible(card, EntityStates.EMPTY, noPerson)).isFalse()
        // Wrong zone -> hidden.
        val away = state("person.kenny", "work", attributes = mapOf("user_id" to "abc123"))
        val awayCtx = deckConditionContext(currentUserId = "abc123", statesByRawId = mapOf(away.id.value to away))
        assertThat(deckCardIsVisible(card, statesOf(away), awayCtx)).isFalse()
    }

    @Test fun `stacked conditional layers must all pass`() {
        val card = conditional(
            LovelaceCondition.StateEquals("binary_sensor.door", "on"),
            inner = conditional(LovelaceCondition.User(listOf("abc123"))),
        )
        val door = statesOf(state("binary_sensor.door", "on"))
        val rightUser = deckConditionContext(currentUserId = "abc123", statesByRawId = emptyMap())
        val wrongUser = deckConditionContext(currentUserId = "zzz", statesByRawId = emptyMap())
        assertThat(deckCardIsVisible(card, door, rightUser)).isTrue()
        assertThat(deckCardIsVisible(card, door, wrongUser)).isFalse()
    }

    @Test fun `non-conditional cards are always visible`() {
        val ctx = deckConditionContext(currentUserId = null, statesByRawId = emptyMap())
        assertThat(deckCardIsVisible(markdown(), EntityStates.EMPTY, ctx)).isTrue()
    }

    // ── unwrapping for the slot renderer ────────────────────────────────────

    @Test fun `unwrapDeckConditional strips every top-level layer`() {
        val inner = markdown()
        val wrapped = conditional(
            LovelaceCondition.AlwaysTrue,
            inner = conditional(LovelaceCondition.AlwaysTrue, inner = inner),
        )
        assertThat(unwrapDeckConditional(wrapped)).isSameInstanceAs(inner)
        assertThat(unwrapDeckConditional(inner)).isSameInstanceAs(inner)
    }

    // ── visibility-entity collection (the VM's observe-union extension) ─────

    @Test fun `collectDeckVisibilityEntityIds walks stacked layers but not content`() {
        val card = conditional(
            LovelaceCondition.StateEquals("binary_sensor.door", "on"),
            inner = conditional(
                LovelaceCondition.NumericState("sensor.temp", above = 20.0, below = null),
                inner = parsePinnedCard("""{"type":"tile","entity":"light.desk"}""")!!,
            ),
        )
        val sink = LinkedHashSet<String>()
        collectDeckVisibilityEntityIds(card, sink)
        // Condition gates from BOTH layers, but not the content's own entity:
        // content entities don't affect slot visibility and are observed via
        // the screen-level render union instead.
        assertThat(sink).containsExactly("binary_sensor.door", "sensor.temp")
    }

    // ── deck-build dropping ──────────────────────────────────────────────────

    @Test fun `buildDeckItems drops cards the visibility gate hides`() {
        val page = FavoritePage(
            id = "p1", name = "HOME",
            pinnedCards = listOf("blob-hidden", "blob-visible"),
            pinnedCardIds = listOf("c1", "c2"),
        )
        val hidden = conditional(LovelaceCondition.Never)
        val visible = markdown()
        val deck = buildDeckItems(
            page = page,
            materializeEntity = { null },
            parseCard = { raw -> if (raw == "blob-hidden") hidden else visible },
            cardIsVisible = { card -> card !is LovelaceCard.Conditional },
        )
        assertThat(deck).hasSize(1)
        assertThat((deck.single() as DeckItem.Card).id).isEqualTo("c2")
    }

    @Test fun `buildDeckItems default gate keeps every parseable card`() {
        val page = FavoritePage(
            id = "p1", name = "HOME",
            pinnedCards = listOf("blob"),
            pinnedCardIds = listOf("c1"),
        )
        val deck = buildDeckItems(
            page = page,
            materializeEntity = { null },
            parseCard = { markdown() },
        )
        assertThat(deck).hasSize(1)
    }

    // ── diagnostics ──────────────────────────────────────────────────────────

    @Test fun `render info reports type, visibility and state coverage`() {
        val card = conditional(
            LovelaceCondition.StateEquals("binary_sensor.door", "on"),
            inner = parsePinnedCard("""{"type":"tile","entity":"light.desk"}""")!!,
        )
        val ctx = deckConditionContext(currentUserId = null, statesByRawId = emptyMap())
        val info = deckCardRenderInfo(
            cardId = "c1",
            card = card,
            states = statesOf(state("binary_sensor.door", "off")),
            context = ctx,
        )
        assertThat(info.type).isEqualTo("conditional")
        assertThat(info.visible).isFalse()
        assertThat(info.hiddenBy).isEqualTo("state(binary_sensor.door)")
        // door has live state, light.desk does not.
        assertThat(info.statesPresent).isEqualTo(1)
        assertThat(info.statesTotal).isEqualTo(2)
    }

    @Test fun `render info names the user condition when it hides the card`() {
        val card = conditional(LovelaceCondition.User(listOf("abc123")))
        val ctx = deckConditionContext(currentUserId = null, statesByRawId = emptyMap())
        val info = deckCardRenderInfo("c1", card, EntityStates.EMPTY, ctx)
        assertThat(info.visible).isFalse()
        assertThat(info.hiddenBy).isEqualTo("user")
    }

    @Test fun `summary line is deterministic so its hash gates re-logging`() {
        val infos = listOf(
            DeckCardRenderInfo("c1", "conditional", false, "user", 0, 0),
            DeckCardRenderInfo("c2", "markdown", true, null, 2, 2),
        )
        val a = deckRenderSummary("p1", infos)
        val b = deckRenderSummary("p1", infos.map { it.copy() })
        assertThat(a).isEqualTo(b)
        assertThat(a).contains("page=p1 cards=2")
        assertThat(a).contains("[c1 type=conditional hidden(user) states=0/0]")
        assertThat(a).contains("[c2 type=markdown visible states=2/2]")
        val flipped = deckRenderSummary(
            "p1",
            listOf(infos[0].copy(visible = true, hiddenBy = null), infos[1]),
        )
        assertThat(flipped.hashCode()).isNotEqualTo(a.hashCode())
    }
}
