package com.github.itskenny0.r1ha.core.lovelace

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/**
 * Pure-logic coverage for Batch M decision functions: section ordering under
 * spans, header layout adaptation, background resolution, per-user view
 * visibility, sidebar visibility, and the tab indicator. None of these touch
 * Compose, so they exercise the documented single-column adaptations directly.
 */
class ViewChromeLogicTest {

    private val emptyRaw = JsonObject(emptyMap())

    private fun stubCard(entity: String): LovelaceCard =
        LovelaceCard.Button(
            raw = emptyRaw, entityId = entity, name = null, icon = null,
            showName = true, showIcon = true, showState = false, tapAction = null,
        )

    private fun section(
        entity: String,
        disabled: Boolean = false,
        columnSpan: Int? = null,
    ) = LovelaceSection(
        cards = listOf(stubCard(entity)),
        disabled = disabled,
        columnSpan = columnSpan,
    )

    // ── orderedSectionCards ──────────────────────────────────────────────────

    @Test fun `ordering concatenates enabled sections in declaration order`() {
        val cards = orderedSectionCards(
            listOf(section("light.a"), section("light.b"), section("light.c")),
        )
        assertThat(cards.map { (it as LovelaceCard.Button).entityId })
            .containsExactly("light.a", "light.b", "light.c").inOrder()
    }

    @Test fun `ordering drops disabled sections`() {
        val cards = orderedSectionCards(
            listOf(section("light.a", disabled = true), section("light.b")),
        )
        assertThat(cards.map { (it as LovelaceCard.Button).entityId })
            .containsExactly("light.b")
    }

    @Test fun `spans do not change single-column order`() {
        // column_span only widens cards on HA's multi-column grid; on one column
        // it must not reorder anything.
        val cards = orderedSectionCards(
            listOf(section("light.a", columnSpan = 3), section("light.b", columnSpan = 1)),
            maxColumns = 4,
            dense = true,
        )
        assertThat(cards.map { (it as LovelaceCard.Button).entityId })
            .containsExactly("light.a", "light.b").inOrder()
    }

    // ── resolveHeaderPlan ────────────────────────────────────────────────────

    @Test fun `null header yields no plan`() {
        assertThat(resolveHeaderPlan(null)).isNull()
    }

    @Test fun `header defaults to center alignment and bottom badges`() {
        val plan = resolveHeaderPlan(LovelaceViewHeader(card = stubCard("light.a")))!!
        assertThat(plan.hasCard).isTrue()
        assertThat(plan.alignment).isEqualTo(HeaderAlignment.CENTER)
        assertThat(plan.badgesSlot).isEqualTo(HeaderBadgesSlot.BOTTOM)
    }

    @Test fun `header top badges and start layout are honoured`() {
        val plan = resolveHeaderPlan(
            LovelaceViewHeader(card = null, layout = "start", badgesPosition = "top"),
        )!!
        assertThat(plan.hasCard).isFalse()
        assertThat(plan.alignment).isEqualTo(HeaderAlignment.START)
        assertThat(plan.badgesSlot).isEqualTo(HeaderBadgesSlot.TOP)
    }

    @Test fun `responsive layout collapses to start on the single column`() {
        val plan = resolveHeaderPlan(LovelaceViewHeader(card = null, layout = "responsive"))!!
        assertThat(plan.alignment).isEqualTo(HeaderAlignment.START)
    }

    // ── resolveViewBackground ────────────────────────────────────────────────

    @Test fun `view background wins over the dashboard fallback`() {
        val view = LovelaceViewBackground(image = "/v.png")
        val dash = LovelaceViewBackground(image = "/d.png")
        assertThat(resolveViewBackground(view, dash)).isEqualTo(view)
    }

    @Test fun `dashboard background is the fallback when the view sets none`() {
        val dash = LovelaceViewBackground(image = "/d.png")
        assertThat(resolveViewBackground(null, dash)).isEqualTo(dash)
    }

    @Test fun `no background resolves to null`() {
        assertThat(resolveViewBackground(null, null)).isNull()
    }

    @Test fun `an inert background (no image, no raw string) resolves to null`() {
        val inert = LovelaceViewBackground(size = "cover")
        assertThat(resolveViewBackground(inert, null)).isNull()
    }

    @Test fun `a raw-string-only background is kept (not treated as inert)`() {
        val gradient = LovelaceViewBackground(rawString = "linear-gradient(red, blue)")
        assertThat(resolveViewBackground(gradient, null)).isEqualTo(gradient)
    }

    // ── resolveSectionBackgroundOpacity ──────────────────────────────────────

    @Test fun `section opacity defaults to HA's 50 percent`() {
        assertThat(resolveSectionBackgroundOpacity(LovelaceSectionBackground()))
            .isEqualTo(DEFAULT_SECTION_BACKGROUND_OPACITY)
    }

    @Test fun `section opacity honours an explicit value, clamped`() {
        assertThat(resolveSectionBackgroundOpacity(LovelaceSectionBackground(opacity = 80)))
            .isEqualTo(80)
        assertThat(resolveSectionBackgroundOpacity(LovelaceSectionBackground(opacity = 250)))
            .isEqualTo(100)
    }

    @Test fun `null section background has no opacity`() {
        assertThat(resolveSectionBackgroundOpacity(null)).isNull()
    }

    // ── sectionBackgroundRuns ────────────────────────────────────────────────

    private fun bgSection(
        entity: String,
        background: LovelaceSectionBackground?,
        disabled: Boolean = false,
    ) = LovelaceSection(
        cards = listOf(stubCard(entity)),
        disabled = disabled,
        background = background,
    )

    @Test fun `runs tag each section with its background`() {
        val red = LovelaceSectionBackground(color = "red")
        val runs = sectionBackgroundRuns(
            listOf(
                bgSection("light.a", red),
                bgSection("light.b", null),
            ),
        )
        assertThat(runs).hasSize(2)
        assertThat(runs[0].background).isEqualTo(red)
        assertThat((runs[0].cards.single() as LovelaceCard.Button).entityId).isEqualTo("light.a")
        assertThat(runs[1].background).isNull()
    }

    @Test fun `runs drop disabled and empty sections`() {
        val runs = sectionBackgroundRuns(
            listOf(
                bgSection("light.a", null, disabled = true),
                LovelaceSection(cards = emptyList(), background = LovelaceSectionBackground()),
                bgSection("light.b", LovelaceSectionBackground()),
            ),
        )
        assertThat(runs).hasSize(1)
        assertThat((runs.single().cards.single() as LovelaceCard.Button).entityId).isEqualTo("light.b")
    }

    @Test fun `concatenating runs reproduces orderedSectionCards`() {
        // The painted path and the flat path must render the SAME cards in the
        // same order; only the grouping/painting differs.
        val sections = listOf(
            bgSection("light.a", LovelaceSectionBackground(color = "blue")),
            bgSection("light.b", null),
            bgSection("light.c", LovelaceSectionBackground()),
        )
        val flat = orderedSectionCards(sections).map { (it as LovelaceCard.Button).entityId }
        val viaRuns = sectionBackgroundRuns(sections)
            .flatMap { it.cards }.map { (it as LovelaceCard.Button).entityId }
        assertThat(viaRuns).isEqualTo(flat)
    }

    // ── isViewTabVisible / isViewListed ──────────────────────────────────────

    @Test fun `null visibility is always visible`() {
        assertThat(isViewTabVisible(null, currentUserId = null)).isTrue()
        assertThat(isViewTabVisible(null, currentUserId = "u1")).isTrue()
    }

    @Test fun `AlwaysHidden is never tab-visible`() {
        assertThat(isViewTabVisible(ViewVisibility.AlwaysHidden, "u1")).isFalse()
    }

    @Test fun `user-scoped view is visible only to listed users`() {
        val vis = ViewVisibility.Users(setOf("u1", "u2"))
        assertThat(isViewTabVisible(vis, "u1")).isTrue()
        assertThat(isViewTabVisible(vis, "u3")).isFalse()
        // Unknown current user (null id) cannot match, so the view hides.
        assertThat(isViewTabVisible(vis, null)).isFalse()
    }

    @Test fun `isViewListed combines subview and per-user gates`() {
        val plain = LovelaceView(title = "A", path = "a", icon = null, panel = false, cards = emptyList())
        val sub = plain.copy(subview = true)
        val hidden = plain.copy(visible = ViewVisibility.AlwaysHidden)
        val scoped = plain.copy(visible = ViewVisibility.Users(setOf("u1")))
        assertThat(isViewListed(plain, "u1")).isTrue()
        assertThat(isViewListed(sub, "u1")).isFalse()
        assertThat(isViewListed(hidden, "u1")).isFalse()
        assertThat(isViewListed(scoped, "u1")).isTrue()
        assertThat(isViewListed(scoped, "u2")).isFalse()
    }

    // ── resolveSidebarVisible ────────────────────────────────────────────────

    @Test fun `null sidebar is not visible`() {
        assertThat(resolveSidebarVisible(null) { true }).isFalse()
    }

    @Test fun `sidebar with no conditions is always visible`() {
        val sb = LovelaceViewSidebar(sections = listOf(section("light.a")))
        assertThat(resolveSidebarVisible(sb) { false }).isTrue()
    }

    @Test fun `sidebar visibility requires every condition to pass`() {
        val condA = LovelaceCondition.StateEquals(entityId = "light.a", states = listOf("on"), negate = false)
        val condB = LovelaceCondition.StateEquals(entityId = "light.b", states = listOf("on"), negate = false)
        val sb = LovelaceViewSidebar(visibility = listOf(condA, condB))
        // both pass
        assertThat(resolveSidebarVisible(sb) { true }).isTrue()
        // one fails
        assertThat(resolveSidebarVisible(sb) { it === condA }).isFalse()
    }

    // ── resolveViewByPath ────────────────────────────────────────────────────

    private fun namedView(path: String) =
        LovelaceView(title = path, path = path, icon = null, panel = false, cards = emptyList())

    @Test fun `resolveViewByPath matches an explicit path`() {
        val views = listOf(namedView("home"), namedView("lights"))
        assertThat(resolveViewByPath(views, "lights")).isEqualTo(views[1])
    }

    @Test fun `resolveViewByPath addresses by numeric index when no path matches`() {
        val views = listOf(namedView("home"), namedView("lights"))
        assertThat(resolveViewByPath(views, "1")).isEqualTo(views[1])
    }

    @Test fun `resolveViewByPath prefers a path named like a number over the index`() {
        // A view whose path is literally "1" wins over index addressing.
        val views = listOf(namedView("1"), namedView("home"))
        assertThat(resolveViewByPath(views, "1")).isEqualTo(views[0])
    }

    @Test fun `resolveViewByPath falls back to the first view`() {
        val views = listOf(namedView("home"), namedView("lights"))
        assertThat(resolveViewByPath(views, "nope")).isEqualTo(views[0])
        assertThat(resolveViewByPath(views, null)).isEqualTo(views[0])
    }

    @Test fun `resolveViewByPath returns null for an empty list`() {
        assertThat(resolveViewByPath(emptyList(), "home")).isNull()
    }

    // ── resolveTabIndicator ──────────────────────────────────────────────────

    @Test fun `tab indicator shows icon alone by default`() {
        assertThat(resolveTabIndicator(showIconAndTitle = false, hasIcon = true, hasTitle = true))
            .isEqualTo(TabIndicator.ICON)
    }

    @Test fun `tab indicator shows both when the flag is set and both exist`() {
        assertThat(resolveTabIndicator(showIconAndTitle = true, hasIcon = true, hasTitle = true))
            .isEqualTo(TabIndicator.ICON_AND_TITLE)
    }

    @Test fun `tab indicator falls back to the title when there is no icon`() {
        assertThat(resolveTabIndicator(showIconAndTitle = true, hasIcon = false, hasTitle = true))
            .isEqualTo(TabIndicator.TITLE)
    }
}
