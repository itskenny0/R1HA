package com.github.itskenny0.r1ha.core.lovelace

/**
 * Pure decision logic for Lovelace view + section chrome (Batch M).
 *
 * R1HA renders every dashboard view as a single 640px-wide scrolling column
 * (the R1 is a narrow portrait panel). HA's masonry grid, sections grid, panel
 * view, and sidebar all collapse onto that one column. The functions here are
 * the deliberate, documented adaptations of HA's multi-column chrome:
 *
 *  - [orderedSectionCards] flattens a sections-view's sections into one reading
 *    order, honouring HA's span placement only as ORDER (never widening a card).
 *  - [resolveHeaderPlan] adapts the view header's layout + badge placement to
 *    one column.
 *  - [resolveViewBackground] / [resolveSectionBackground] resolve the
 *    background config (with the dashboard-level fallback HA applies).
 *  - [isViewTabVisible] / [resolveSidebarVisible] / [resolveTabIndicator] cover
 *    per-user view visibility, sidebar visibility, and the tab indicator.
 *
 * All of it is free of Compose / Android so it can be unit-tested against the
 * parsed model directly.
 */

/**
 * Flatten the [sections] of a sections-view into a single ordered card list.
 *
 * HA lays sections out in a CSS grid of up to `max_columns` columns; each
 * section can `column_span` / `row_span` multiple cells, and
 * `dense_section_placement` packs them densely. None of that produces side-by-
 * side columns on the 640px R1 panel: there is room for exactly one column, so
 * the single-column flatten IS the equivalent of HA's grid. The spans therefore
 * affect only the ORDER cards appear in, never their width.
 *
 * The order R1HA uses is HA's reading order: sections in declaration order, top
 * to bottom, left to right. Because every section occupies the full single
 * column, declaration order already equals reading order, so this is a simple
 * concatenation of each enabled section's cards. [maxColumns] and [dense] are
 * accepted (and documented) so the ordering contract is explicit and a future
 * multi-column tier can refine it; on one column they do not change the result.
 *
 * Disabled sections (HA's `disabled: true`) are dropped entirely.
 */
fun orderedSectionCards(
    sections: List<LovelaceSection>,
    @Suppress("UNUSED_PARAMETER") maxColumns: Int? = null,
    @Suppress("UNUSED_PARAMETER") dense: Boolean = false,
): List<LovelaceCard> =
    sections.filterNot { it.disabled }.flatMap { it.cards }

/** Header badge placement relative to the header card on the single column. */
enum class HeaderBadgesSlot {
    /** Badges render above the header card (HA `badges_position: top`). */
    TOP,

    /** Badges render below the header card (HA default `badges_position: bottom`). */
    BOTTOM,
}

/** Horizontal alignment the header content uses on the single column. */
enum class HeaderAlignment { START, CENTER }

/**
 * The resolved single-column rendering plan for a view's `header:`.
 *
 *  - [hasCard]: whether a header card should be rendered.
 *  - [badgesSlot]: where the view badges go relative to that card.
 *  - [alignment]: START or CENTER. HA's "responsive" layout floats the badges
 *    beside the heading on a wide screen; there is no room for that on 640px, so
 *    "responsive" collapses to START (a left-aligned stack), while "start" stays
 *    START and "center" stays CENTER.
 */
data class HeaderPlan(
    val hasCard: Boolean,
    val badgesSlot: HeaderBadgesSlot,
    val alignment: HeaderAlignment,
)

/**
 * Resolve a view's `header:` config into a single-column [HeaderPlan]. Returns
 * null when there is no header to render (HA's defaults: layout "center",
 * badges "bottom").
 */
fun resolveHeaderPlan(header: LovelaceViewHeader?): HeaderPlan? {
    if (header == null) return null
    val slot = when (header.badgesPosition?.trim()?.lowercase()) {
        "top" -> HeaderBadgesSlot.TOP
        else -> HeaderBadgesSlot.BOTTOM
    }
    val alignment = when (header.layout?.trim()?.lowercase()) {
        "center" -> HeaderAlignment.CENTER
        // "start" stays start; "responsive" has no multi-column meaning on the
        // narrow panel, so it collapses to a left-aligned stack.
        "start", "responsive" -> HeaderAlignment.START
        // HA's default layout is "center".
        else -> HeaderAlignment.CENTER
    }
    return HeaderPlan(
        hasCard = header.card != null,
        badgesSlot = slot,
        alignment = alignment,
    )
}

/**
 * Resolve the background to render behind a view's cards. HA falls back to the
 * dashboard-level background when the view declares none
 * (`curViewConfig?.background || config.background`). Returns null when neither
 * is set or the resolved background has nothing renderable (no image and no raw
 * string the renderer can use).
 */
fun resolveViewBackground(
    viewBackground: LovelaceViewBackground?,
    dashboardBackground: LovelaceViewBackground?,
): LovelaceViewBackground? {
    val resolved = viewBackground ?: dashboardBackground ?: return null
    // A background with no image and no raw string is inert (e.g. an object that
    // only set `size`); treat it as nothing rather than reserving a layer.
    if (resolved.image == null && resolved.rawString.isNullOrBlank()) return null
    return resolved
}

/** HA's default section background opacity (percent). */
const val DEFAULT_SECTION_BACKGROUND_OPACITY: Int = 50

/**
 * The resolved opacity (0..100) for a section background, applying HA's default
 * when the config omits it. Returns null when [background] is null (no surface
 * to draw).
 */
fun resolveSectionBackgroundOpacity(background: LovelaceSectionBackground?): Int? {
    if (background == null) return null
    return (background.opacity ?: DEFAULT_SECTION_BACKGROUND_OPACITY).coerceIn(0, 100)
}

/**
 * One renderable run of section cards on the single column, tagging the run with
 * the [LovelaceSectionBackground] (if any) that paints behind it. Each enabled,
 * non-empty section becomes one run, in HA's reading (declaration) order. A
 * section with no background yields a run with [background] = null (the cards
 * render on the plain surface). Empty and disabled sections drop out, matching
 * [orderedSectionCards].
 */
data class SectionRun(
    val background: LovelaceSectionBackground?,
    val cards: List<LovelaceCard>,
)

/**
 * Group a sections-view's [sections] into background-tagged [SectionRun]s for
 * the single column. This is the section-background pass deferred from Batch M:
 * the flat [orderedSectionCards] list loses section boundaries, so the renderer
 * uses these runs (when the view carries no card overrides) to paint each
 * section's background behind its own run of cards. Concatenating the runs'
 * cards reproduces [orderedSectionCards] exactly, so the two paths stay in sync.
 */
fun sectionBackgroundRuns(sections: List<LovelaceSection>): List<SectionRun> =
    sections
        .filterNot { it.disabled }
        .filter { it.cards.isNotEmpty() }
        .map { SectionRun(background = it.background, cards = it.cards) }

/**
 * Decide whether a view should appear in the tab/page list for the current
 * user. Mirrors HA's hui-root tab filter:
 *  - a [ViewVisibility.AlwaysHidden] view (`visible: false`) is never listed;
 *  - a [ViewVisibility.Users] view is listed only when [currentUserId] is in
 *    its user set;
 *  - a view with no [ViewVisibility] (null) is always listed.
 *
 * Subviews are handled separately (they are never in the tab list regardless of
 * visibility); this function answers the per-user gate only. A hidden view stays
 * navigable by direct path, so this never affects rendering, only the tab list.
 */
fun isViewTabVisible(visible: ViewVisibility?, currentUserId: String?): Boolean =
    when (visible) {
        null -> true
        is ViewVisibility.AlwaysHidden -> false
        is ViewVisibility.Users -> currentUserId != null && currentUserId in visible.userIds
    }

/**
 * Whether a view should be listed as a top-level tab/page. Combines the subview
 * rule (subviews are reached by navigation, never listed) with the per-user
 * visibility gate. A subview, or a view hidden for the current user, returns
 * false; both remain navigable by direct path.
 */
fun isViewListed(view: LovelaceView, currentUserId: String?): Boolean =
    !view.subview && isViewTabVisible(view.visible, currentUserId)

/**
 * Whether the sections-view sidebar group should be shown. HA hides the sidebar
 * when its `visibility:` conditions fail. With no conditions the sidebar always
 * shows. [evaluate] runs one condition through the Batch B engine (the caller
 * supplies the bound evaluator); the sidebar shows only when every condition
 * passes (HA's all-must-pass semantics).
 */
fun resolveSidebarVisible(
    sidebar: LovelaceViewSidebar?,
    evaluate: (LovelaceCondition) -> Boolean,
): Boolean {
    if (sidebar == null) return false
    if (sidebar.visibility.isEmpty()) return true
    return sidebar.visibility.all(evaluate)
}

/**
 * Resolve a view inside [views] by HA's addressing rules:
 *  - a [target] equal to a view's `path` matches that view;
 *  - a numeric [target] ("0", "1", ...) addresses the view at that index when no
 *    path matches (HA accepts the index as a path);
 *  - a null / unmatched [target] falls back to the first view (HA's default).
 *
 * Returns null only when [views] is empty. Hidden / subview views remain
 * resolvable here (visibility gates the tab list, not direct addressing).
 */
fun resolveViewByPath(views: List<LovelaceView>, target: String?): LovelaceView? {
    if (views.isEmpty()) return null
    if (target != null) {
        views.firstOrNull { it.path == target }?.let { return it }
        target.toIntOrNull()?.let { idx -> views.getOrNull(idx)?.let { return it } }
    }
    return views.first()
}

/** What a view's tab/page indicator should display. */
enum class TabIndicator {
    /** Show the icon only (HA's default when an icon is present). */
    ICON,

    /** Show the title only (no icon, or an icon-less view). */
    TITLE,

    /** Show both the icon and the title (HA's `show_icon_and_title: true`). */
    ICON_AND_TITLE,
}

/**
 * Resolve what a view's tab/page indicator shows, mirroring HA's hui-root tab
 * logic: `show_icon_and_title && icon && title` -> both; an icon without that
 * flag -> icon only; otherwise the title. [hasIcon] / [hasTitle] report whether
 * the view actually carries a non-blank icon / title.
 */
fun resolveTabIndicator(
    showIconAndTitle: Boolean,
    hasIcon: Boolean,
    hasTitle: Boolean,
): TabIndicator = when {
    showIconAndTitle && hasIcon && hasTitle -> TabIndicator.ICON_AND_TITLE
    hasIcon -> TabIndicator.ICON
    else -> TabIndicator.TITLE
}
