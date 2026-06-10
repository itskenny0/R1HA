package com.github.itskenny0.r1ha.core.lovelace

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Special-row type keys (from HA's create-row-element dispatch table).
private val SPECIAL_ROW_TYPES = setOf(
    "section", "divider", "attribute", "button", "buttons",
    "call-service", "perform-action", "conditional", "text", "weblink", "cast",
)

// The HA `energy-*` / `*-sankey` card types, mapped to their R1 kind. The
// `energy-date-selection` host is parsed separately (it carries no kind).
private val ENERGY_CARD_TYPES_BY_TYPE: Map<String, EnergyCardKind> =
    EnergyCardKind.entries.associateBy { it.haType }

/**
 * Pure parser turning HA's raw `lovelace/config` JSON into the typed
 * [LovelaceConfig] tree. Stateless: no IO, no caching, no Compose.
 * Tested in isolation against fixture JSON.
 *
 * Design rules:
 *  - never throw on a card type / field we don't understand. Drop to
 *    [LovelaceCard.Unsupported] (whole card) or null (single field).
 *  - preserve the raw JSON inside every card so the editor + the debug
 *    expander on Unsupported cards can show the original payload.
 *  - mirror HA's tolerant shape: bare strings where objects are valid,
 *    missing keys with sensible defaults, mixed `service` / `action`
 *    field names for service calls.
 */
object LovelaceParser {

    /**
     * Matches a Home Assistant entity id (`domain.object_id`) embedded in a
     * larger string. Used to scrape an entity reference out of a Mushroom
     * template card's `primary` / `secondary` / `icon` template when the card
     * carries no explicit `entity` key.
     */
    private val ENTITY_ID_REGEX = Regex("""\b[a-z_]+\.[a-z0-9_]+\b""")

    /** HA's three-letter lowercase weekday tokens (WEEKDAYS_SHORT). A `time`
     *  condition's `weekdays:` list is validated against this set. */
    private val VALID_WEEKDAYS = setOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")

    /** Parse the top-level dashboard config returned by `lovelace/config`. */
    fun parseConfig(root: JsonObject): LovelaceConfig {
        val title = root["title"]?.asStringOrNull()
        val viewsArr = root["views"] as? JsonArray
        val views = viewsArr
            ?.mapIndexedNotNull { idx, el -> (el as? JsonObject)?.let { parseView(it, idx) } }
            ?: emptyList()
        // A fully strategy-generated dashboard (HA's auto default) returns
        // `{ "strategy": { ... } }` at the root with no concrete `views`. We
        // can't expand strategies locally, so flag it; the screen offers an
        // "open in Lovelace" affordance rather than rendering empty. Also flag
        // the degenerate case where the only views present are themselves all
        // strategy-driven (no concrete cards anywhere).
        val rootStrategy = root["strategy"] != null && (viewsArr == null || views.isEmpty())
        val allViewsStrategy = views.isNotEmpty() && views.all { it.isStrategyGenerated && it.cards.isEmpty() }
        return LovelaceConfig(
            title = title,
            views = views,
            isStrategyGenerated = rootStrategy || allViewsStrategy,
            background = parseViewBackground(root["background"]),
        )
    }

    /** Parse the dashboard list returned by `lovelace/dashboards/list`. */
    fun parseDashboards(arr: JsonArray): List<LovelaceDashboard> =
        arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val urlPath = obj["url_path"]?.asStringOrNull()
            val title = obj["title"]?.asStringOrNull() ?: urlPath ?: return@mapNotNull null
            LovelaceDashboard(
                id = obj["id"]?.asStringOrNull(),
                urlPath = urlPath,
                title = title,
                icon = obj["icon"]?.asStringOrNull(),
                showInSidebar = obj["show_in_sidebar"]?.asBooleanOrNull() ?: true,
                requireAdmin = obj["require_admin"]?.asBooleanOrNull() ?: false,
                mode = obj["mode"]?.asStringOrNull(),
            )
        }

    private fun parseView(obj: JsonObject, index: Int): LovelaceView {
        val directCards = (obj["cards"] as? JsonArray)
            ?.mapNotNull { el -> (el as? JsonObject)?.let(::parseCard) ?: nonObjectCardError(el) }
            ?: emptyList()
        // "sections" views (HA's default UI-editor layout since 2024.x) hold
        // their cards under sections[].cards rather than the legacy top-level
        // cards[]. Without this a sections dashboard parses to zero cards and
        // renders as "0 entities". Parse each concrete section's structure
        // (cards + span/disabled/background) so the flatten can honour reading
        // order and per-section chrome; a strategy section carries no cards and
        // resolves to an empty section, skipped from the order.
        val sectionsArr = obj["sections"] as? JsonArray
        val sections = sectionsArr
            ?.mapNotNull { sec -> (sec as? JsonObject)?.let(::parseSection) }
            ?: emptyList()
        // R1HA flattens sections/masonry into one 640px column. The flat card
        // list IS the equivalent of HA's grid here, so order the section cards
        // exactly as HA's reading order (orderedSectionCards) and drop disabled
        // sections. The dense/span/max_columns keys only reorder; they never
        // widen a card on the single column.
        val maxColumns = obj["max_columns"]?.asIntOrNull()
        val dense = obj["dense_section_placement"]?.asBooleanOrNull() ?: false
        val sectionCards = orderedSectionCards(sections, maxColumns, dense)
        // View header/footer (HA's `header:` / `footer:`) and the sections-view
        // `sidebar:`. The header card and footer card are NOT folded into the
        // flat [cards] list: the renderer draws them explicitly so the header's
        // badge placement (`badges_position`) and the non-sticky footer slot are
        // honoured on the single column. Their entity ids are unioned into the
        // subscription walk separately (see collectEntityIds over header/footer).
        val header = parseViewHeader(obj["header"])
        val footer = parseViewFooter(obj["footer"])
        val sidebar = parseViewSidebar(obj["sidebar"])
        // The flat render list: the main section/masonry cards in reading order,
        // then the sidebar group's cards (rendered after the main sections under
        // a divider). The sidebar's own visibility gate drives the divider at
        // render time; per-card visibility gates compose on top.
        val sidebarCards = sidebar?.let { sb ->
            orderedSectionCards(sb.sections, maxColumns, dense)
        } ?: emptyList()
        // HA's imported-cards quirk: a SECTIONS view that ALSO carries a
        // top-level `cards:` array (left over from a masonry import) HIDES those
        // cards in view mode, surfacing them only in the editor's "imported
        // cards" group. R1HA renders read-only, so it mirrors HA's view-mode
        // behaviour and drops the top-level cards on a sections view (they would
        // otherwise duplicate / mis-order content the author can't see together).
        // On a legacy masonry view (no `sections:`) the top-level cards ARE the
        // content and are kept. The reading-order contract (orderedSectionCards)
        // still governs the section cards themselves.
        val isSectionsView = sectionsArr != null
        val viewDirectCards = if (isSectionsView) emptyList() else directCards
        val flatCards = viewDirectCards + sectionCards + sidebarCards
        // A view is strategy-generated when it carries a `strategy:` key and no
        // concrete cards, OR every one of its sections is a strategy section
        // (carries `strategy` but no `cards`). Either way there is nothing for
        // us to render, so the screen can offer the Lovelace fallback per-view.
        val viewStrategy = obj["strategy"] != null
        val sectionsAllStrategy = sectionsArr != null && sectionsArr.isNotEmpty() &&
            sectionsArr.all { sec ->
                val s = sec as? JsonObject ?: return@all false
                s["strategy"] != null && (s["cards"] as? JsonArray).isNullOrEmpty()
            }
        val isStrategy = flatCards.isEmpty() && (viewStrategy || sectionsAllStrategy)
        return LovelaceView(
            title = obj["title"]?.asStringOrNull(),
            path = obj["path"]?.asStringOrNull() ?: index.toString(),
            icon = obj["icon"]?.asStringOrNull(),
            panel = obj["panel"]?.asBooleanOrNull() ?: false,
            cards = flatCards,
            badges = parseBadges(obj["badges"]),
            isStrategyGenerated = isStrategy,
            subview = obj["subview"]?.asBooleanOrNull() ?: false,
            header = header,
            footer = footer,
            sidebar = sidebar,
            background = parseViewBackground(obj["background"]),
            visible = parseViewVisibility(obj["visible"]),
            backPath = obj["back_path"]?.asStringOrNull(),
            showIconAndTitle = obj["show_icon_and_title"]?.asBooleanOrNull() ?: false,
            maxColumns = maxColumns,
            denseSectionPlacement = dense,
            topMargin = obj["top_margin"]?.asBooleanOrNull() ?: false,
            sections = sections,
            theme = obj["theme"]?.asStringOrNull(),
        )
    }

    /**
     * Parse one section of a sections-view. Folds its `header:` / `footer:` card
     * slots into the section's flat [LovelaceSection.cards] (matching the view's
     * own flat list) and pushes the section's `visibility:` gate onto each card,
     * the same way [parseSectionCards] did, so a section-level gate survives the
     * single-column flatten. A strategy section (no `cards`) yields an empty,
     * non-disabled section that drops out of the order.
     */
    private fun parseSection(section: JsonObject): LovelaceSection {
        val cards = parseSectionCards(section)
        val backgroundEl = section["background"]
        val background = when {
            backgroundEl == null || backgroundEl is JsonNull -> null
            // `background: true` -> default-opacity surface; `false` -> none.
            backgroundEl is JsonPrimitive && backgroundEl.booleanOrNull != null ->
                if (backgroundEl.booleanOrNull == true) LovelaceSectionBackground() else null
            backgroundEl is JsonObject -> LovelaceSectionBackground(
                color = backgroundEl["color"]?.asStringOrNull(),
                opacity = backgroundEl["opacity"]?.asIntOrNull(),
            )
            else -> null
        }
        return LovelaceSection(
            cards = cards,
            disabled = section["disabled"]?.asBooleanOrNull() ?: false,
            columnSpan = section["column_span"]?.asIntOrNull(),
            rowSpan = section["row_span"]?.asIntOrNull(),
            background = background,
            topMargin = section["top_margin"]?.asBooleanOrNull() ?: false,
            theme = section["theme"]?.asStringOrNull(),
        )
    }

    /** Parse the view `header:` slot. Returns null when absent. The header card
     *  is any card (HA's default is a text-only markdown title). */
    private fun parseViewHeader(el: JsonElement?): LovelaceViewHeader? {
        val obj = el as? JsonObject ?: return null
        val card = (obj["card"] as? JsonObject)?.let(::parseCard)
        // A header with neither a card nor any layout option is inert; drop it
        // so the renderer doesn't reserve a row for nothing.
        if (card == null && obj["layout"] == null && obj["badges_position"] == null &&
            obj["badges_wrap"] == null
        ) {
            return null
        }
        return LovelaceViewHeader(
            card = card,
            layout = obj["layout"]?.asStringOrNull(),
            badgesPosition = obj["badges_position"]?.asStringOrNull(),
            badgesWrap = obj["badges_wrap"]?.asStringOrNull(),
        )
    }

    /** Parse the view `footer:` slot. Returns null when it carries no card. */
    private fun parseViewFooter(el: JsonElement?): LovelaceViewFooter? {
        val obj = el as? JsonObject ?: return null
        val card = (obj["card"] as? JsonObject)?.let(::parseCard) ?: return null
        return LovelaceViewFooter(
            card = card,
            maxWidth = obj["max_width"]?.asIntOrNull(),
        )
    }

    /** Parse the sections-view `sidebar:` slot. Returns null when absent. */
    private fun parseViewSidebar(el: JsonElement?): LovelaceViewSidebar? {
        val obj = el as? JsonObject ?: return null
        val sections = (obj["sections"] as? JsonArray)
            ?.mapNotNull { sec -> (sec as? JsonObject)?.let(::parseSection) }
            ?: emptyList()
        // A sidebar with no sections and no visibility gate carries nothing; drop
        // it so the renderer emits no empty divider.
        if (sections.isEmpty() && (obj["visibility"] as? JsonArray).isNullOrEmpty()) {
            return null
        }
        return LovelaceViewSidebar(
            sections = sections,
            contentLabel = obj["content_label"]?.asStringOrNull(),
            sidebarLabel = obj["sidebar_label"]?.asStringOrNull(),
            visibility = parseConditions(obj["visibility"]),
        )
    }

    /** Parse the view `background:` slot. HA accepts a bare string (a CSS/url
     *  value) or an object; both resolve to [LovelaceViewBackground]. */
    private fun parseViewBackground(el: JsonElement?): LovelaceViewBackground? = when {
        el == null || el is JsonNull -> null
        el is JsonPrimitive && el.isString -> {
            val s = el.content.trim()
            if (s.isEmpty()) null
            else LovelaceViewBackground(
                // A bare string is HA's CSS background shorthand. Use it as the
                // image source when it looks like a url/path, otherwise keep it
                // in rawString so a gradient/theme token is not dropped.
                image = s.takeIf { looksLikeImageRef(it) },
                rawString = s,
            )
        }
        el is JsonObject -> {
            // HA's `image` may itself be a media-source object {media_content_id}.
            val imageEl = el["image"]
            val image = when {
                imageEl is JsonPrimitive && imageEl.isString -> imageEl.content
                imageEl is JsonObject -> imageEl["media_content_id"]?.asStringOrNull()
                else -> null
            }
            LovelaceViewBackground(
                image = image,
                opacity = el["opacity"]?.asIntOrNull(),
                size = el["size"]?.asStringOrNull(),
                alignment = el["alignment"]?.asStringOrNull(),
                repeat = el["repeat"]?.asStringOrNull(),
                attachment = el["attachment"]?.asStringOrNull(),
            )
        }
        else -> null
    }

    /** Parse the view `visible:` key. `false` -> AlwaysHidden, a `[{user}]`
     *  array -> Users, `true` / anything else -> null (always visible). */
    private fun parseViewVisibility(el: JsonElement?): ViewVisibility? = when {
        el is JsonPrimitive && el.booleanOrNull == false -> ViewVisibility.AlwaysHidden
        el is JsonPrimitive && el.booleanOrNull == true -> null
        el is JsonArray -> {
            val users = el.mapNotNull { item ->
                (item as? JsonObject)?.get("user")?.asStringOrNull()
            }.toSet()
            // An empty / userless array shows the view to nobody, matching HA
            // (the `some(user === id)` test can never pass).
            ViewVisibility.Users(users)
        }
        else -> null
    }

    /** Heuristic: does a bare background string name an image rather than a CSS
     *  colour/gradient? Mirrors how HA's hui-view-background treats a plain
     *  string url. Accepts http(s) urls, absolute/relative paths, and
     *  media-source ids. A `linear-gradient(...)` / `#rrggbb` / colour name
     *  returns false so it stays in rawString. */
    private fun looksLikeImageRef(s: String): Boolean {
        val t = s.trim()
        return t.startsWith("http://") || t.startsWith("https://") ||
            t.startsWith("/") || t.startsWith("media-source://") ||
            t.endsWith(".png") || t.endsWith(".jpg") || t.endsWith(".jpeg") ||
            t.endsWith(".gif") || t.endsWith(".webp") || t.endsWith(".svg")
    }

    /**
     * Parse a view's `badges:` array or a heading card's `badges:` array. HA
     * accepts each entry as:
     *  - a bare entity-id string: normalised to `show_name=true` (matching HA's
     *    `ensureBadgeConfig` which expands a string to `{type: entity, show_name: true}`).
     *  - a `type: entity` object: the standard entity-badge shape.
     *  - a `type: state-label` object: legacy badge; show_name defaults true.
     *  - a `type: entity-filter` object: filter-badge, entries expanded to one
     *    badge per entity that passes the filter at parse time.
     *  - a `type: shortcut` object: action-driven badge with optional icon/color.
     *  - a heading-badge `type: button` object: action chip (text + icon).
     *  - any other / custom type: best-effort chip from entity/name when present.
     *
     * Every object badge also reads `visibility:` conditions (Batch B gate) and
     * `disabled: true` (maps to a never-passing condition, hiding the badge).
     * An entry with neither entity, name, icon, nor a viable action is skipped.
     */
    /**
     * Legacy heading-card migration (HA's `migrateHeadingCardConfig`): an old
     * heading config carrying `entities: [...]` had them rendered as heading
     * badges. HA appends the legacy entities after any explicit `badges:`
     * (`badges = [...(badges||[]), ...entities]`) and drops the `entities` key.
     * We reproduce that by concatenating the two arrays; [parseBadges] then
     * normalises each entry (string or object) into a [LovelaceBadge].
     */
    private fun mergeHeadingEntitiesIntoBadges(obj: JsonObject): JsonElement? {
        val entities = obj["entities"] as? JsonArray
        if (entities.isNullOrEmpty()) return obj["badges"]
        val badges = obj["badges"] as? JsonArray
        return buildJsonArray {
            badges?.forEach { add(it) }
            entities.forEach { add(it) }
        }
    }

    private fun parseBadges(el: JsonElement?): List<LovelaceBadge> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.flatMap { item ->
            when (item) {
                // Bare entity-id string: HA normalises to show_name=true.
                is JsonPrimitive -> if (item.isString && item.content.looksLikeEntityId()) {
                    listOf(LovelaceBadge(
                        entityId = item.content,
                        name = null,
                        icon = null,
                        color = null,
                        showName = true,
                        showState = true,
                        showIcon = true,
                        tapAction = null,
                        isLegacyBareString = true,
                    ))
                } else {
                    emptyList()
                }
                is JsonObject -> {
                    val parsed = parseBadgeObject(item)
                    if (parsed == null) emptyList() else listOf(parsed)
                }
                else -> emptyList()
            }
        }
    }

    /**
     * Parse one badge object. Returns null for entries we can't render at all
     * (no entity, no name, no icon, no action). Handles the visibility / disabled
     * gate by injecting conditions onto the returned badge.
     */
    private fun parseBadgeObject(item: JsonObject): LovelaceBadge? {
        val entity = item["entity"]?.asStringOrNull()?.takeIf { it.looksLikeEntityId() }
        val name = item["name"]?.asStringOrNull()
        val badgeType = item["type"]?.asStringOrNull()?.lowercase()
        val tap = parseAction(item["tap_action"] as? JsonObject)
        val hold = parseAction(item["hold_action"] as? JsonObject)
        val doubleTap = parseAction(item["double_tap_action"] as? JsonObject)

        // Visibility conditions: `disabled: true` maps to Never (always hidden),
        // `visibility:` array gates visibility with the Batch B evaluator.
        val conditions: List<LovelaceCondition> = when {
            item["disabled"]?.asBooleanOrNull() == true -> listOf(LovelaceCondition.Never)
            else -> {
                val vis = item["visibility"]
                if (vis is JsonArray && vis.isNotEmpty()) parseConditions(vis) else emptyList()
            }
        }

        // A shortcut / button heading badge is action-driven and may carry only
        // an icon/text; keep it as long as it has something to show or do.
        val isShortcutLike = badgeType == "shortcut" || badgeType == "button"
        if (entity == null && name.isNullOrBlank() &&
            !(isShortcutLike && (tap != null || hold != null || item["icon"] != null || item["text"] != null))) {
            // A custom: (or unrecognised) badge type with neither entity nor
            // name is otherwise silently dropped. Emit a minimal placeholder
            // chip labelled with the type so the user can see that a
            // configured badge was not rendered rather than having it vanish.
            // Pure shortcut/button badges with no action/icon are still
            // dropped (no meaningful content to show).
            if (badgeType != null && badgeType.startsWith("custom:")) {
                val label = badgeType.removePrefix("custom:")
                return LovelaceBadge(
                    entityId = null,
                    name = label.ifBlank { badgeType },
                    icon = null,
                    color = null,
                    showName = true,
                    showState = false,
                    showIcon = false,
                    tapAction = tap,
                    conditions = conditions,
                )
            }
            return null
        }

        // `type: state-label` (legacy): show_name defaults true.
        val isStateLabelLegacy = badgeType == "state-label"
        // `type: shortcut` / `type: button`: color applied to icon and text unconditionally.
        val isActionBadge = badgeType == "shortcut" || badgeType == "button"

        // `image:` key on state-label badges replaces the entity icon (stored as icon,
        // treated as a URL at render time; the renderer checks for a slash-prefix).
        val iconOrImage = item["icon"]?.asStringOrNull()
            ?: (if (isStateLabelLegacy) item["image"]?.asStringOrNull() else null)

        // Legacy `display_type` migration (HA migrateLegacyEntityBadgeConfig):
        // `complete` -> show_name true (when show_name unset); `minimal` ->
        // show_state false (when show_state unset); `standard` keeps the
        // defaults. An explicit show_name / show_state always wins.
        val displayType = item["display_type"]?.asStringOrNull()?.lowercase()

        return LovelaceBadge(
            entityId = entity,
            // For button/shortcut heading badges `text:` maps to the name slot.
            name = name ?: (if (isActionBadge) item["text"]?.asStringOrNull() else null),
            icon = iconOrImage,
            color = item["color"]?.asStringOrNull(),
            // HA's entity-badge defaults: state on, name off, icon on.
            // state-label legacy default: name on.
            showName = item["show_name"]?.asBooleanOrNull()
                ?: when {
                    displayType == "complete" -> true
                    isStateLabelLegacy -> true
                    else -> false
                },
            showState = item["show_state"]?.asBooleanOrNull()
                ?: when {
                    displayType == "minimal" -> false
                    isActionBadge -> false
                    else -> true
                },
            showIcon = item["show_icon"]?.asBooleanOrNull() ?: true,
            tapAction = tap,
            holdAction = hold,
            doubleTapAction = doubleTap,
            size = item["size"]?.asStringOrNull(),
            stateContent = parseStringList(item["state_content"]),
            showEntityPicture = item["show_entity_picture"]?.asBooleanOrNull() ?: false,
            conditions = conditions,
            nameItems = parseStructuredName(item["name"]),
        )
    }

    /** Cards inside one section of a "sections" view. Strategy sections carry
     *  no concrete `cards` array and resolve to an empty list.
     *
     *  A section can carry its OWN `visibility:` conditions (HA gates the whole
     *  section on them). Because we flatten sections into the view's flat card
     *  list, that section-level gate would otherwise be lost and the section's
     *  cards would always show. Push the gate down onto every card in the
     *  section, wrapping each in a [LovelaceCard.Conditional]; this composes
     *  with any per-card `visibility:` (already applied by [parseCard]). */
    private fun parseSectionCards(section: JsonObject): List<LovelaceCard> {
        val header = (section["header"] as? JsonObject)?.let(::parseCard)
        val body = (section["cards"] as? JsonArray)
            ?.mapNotNull { el -> (el as? JsonObject)?.let(::parseCard) ?: nonObjectCardError(el) }
            ?: emptyList()
        // Footer cards (HA 2026.3): a single card slot rendered at the section's
        // end. R1HA flattens sections into one scroll column, so the footer
        // renders inline after the section's cards (not pinned/sticky).
        val footer = (section["footer"] as? JsonObject)?.let(::parseCard)
        val cards = listOfNotNull(header) + body + listOfNotNull(footer)
        val visibility = section["visibility"]
        if (visibility is JsonArray && visibility.isNotEmpty()) {
            val conditions = parseConditions(visibility)
            if (conditions.isNotEmpty()) {
                return cards.map { c ->
                    LovelaceCard.Conditional(raw = section, conditions = conditions, card = c)
                }
            }
        }
        return cards
    }

    /**
     * Recursive: stack and conditional cards re-enter the parser for
     * their children. Cycles aren't possible (HA's config is a tree).
     *
     * Honours the per-card `visibility:` key HA applies to ANY card type
     * (src/panels/lovelace/cards/hui-card.ts): a card with a non-empty
     * `visibility:` array is wrapped in a [LovelaceCard.Conditional] so it
     * renders only when every listed condition passes, exactly like an
     * explicit conditional card. This keeps a single evaluation + slicing
     * path for both forms.
     */
    fun parseCard(obj: JsonObject): LovelaceCard {
        val card = parseCardInner(obj)
        // `disabled: true` (HA's card config key): the card renders nothing in
        // view mode. Model it as a conditional gated on a never-passing rule so
        // the existing "hidden conditional consumes no layout" path applies with
        // no new card type; the editor still reaches the original config via raw.
        if (obj["disabled"]?.asBooleanOrNull() == true) {
            return LovelaceCard.Conditional(
                raw = obj,
                conditions = listOf(LovelaceCondition.Never),
                card = card,
            )
        }
        // Per-card `visibility:` wraps ANY card in conditional gating. HA
        // applies these in addition to (and outside of) a conditional card's
        // own conditions, so wrapping here composes correctly even when the
        // card is itself a `type: conditional`.
        val visibility = obj["visibility"]
        if (visibility is JsonArray && visibility.isNotEmpty()) {
            val conditions = parseConditions(visibility)
            if (conditions.isNotEmpty()) {
                return LovelaceCard.Conditional(
                    raw = obj,
                    conditions = conditions,
                    card = card,
                )
            }
        }
        return card
    }

    private fun parseCardInner(obj: JsonObject): LovelaceCard {
        val type = obj["type"]?.asStringOrNull()?.lowercase() ?: return LovelaceCard.Unsupported(obj, type = "(missing type)")
        return when (type) {
            "entities" -> LovelaceCard.Entities(
                raw = obj,
                title = obj["title"]?.asStringOrNull(),
                showHeaderToggle = obj["show_header_toggle"]?.asBooleanOrNull(),
                rowItems = parseEntitiesItems(obj["entities"]),
                header = parseHeaderFooter(obj["header"]),
                footer = parseHeaderFooter(obj["footer"]),
            )
            "glance" -> LovelaceCard.Glance(
                raw = obj,
                title = obj["title"]?.asStringOrNull(),
                entities = parseEntityRows(obj["entities"]),
                columns = obj["columns"]?.asIntOrNull(),
                showName = obj["show_name"]?.asBooleanOrNull() ?: true,
                showState = obj["show_state"]?.asBooleanOrNull() ?: true,
                showIcon = obj["show_icon"]?.asBooleanOrNull() ?: true,
                stateColor = obj["state_color"]?.asBooleanOrNull() ?: true,
            )
            "button", "entity-button" -> LovelaceCard.Button(
                raw = obj,
                entityId = obj["entity"]?.asStringOrNull(),
                name = obj["name"]?.asStringOrNull(),
                icon = obj["icon"]?.asStringOrNull(),
                showName = obj["show_name"]?.asBooleanOrNull() ?: true,
                showIcon = obj["show_icon"]?.asBooleanOrNull() ?: true,
                showState = obj["show_state"]?.asBooleanOrNull() ?: false,
                tapAction = parseAction(obj["tap_action"] as? JsonObject),
                holdAction = parseAction(obj["hold_action"] as? JsonObject),
                doubleTapAction = parseAction(obj["double_tap_action"] as? JsonObject),
                color = obj["color"]?.asStringOrNull(),
                stateColor = obj["state_color"]?.asBooleanOrNull() ?: true,
                nameItems = parseStructuredName(obj["name"]),
                iconHeight = obj["icon_height"]?.asStringOrNull(),
            )
            "shortcut" -> LovelaceCard.Shortcut(
                raw = obj,
                name = obj["name"]?.asStringOrNull(),
                label = obj["label"]?.asStringOrNull(),
                icon = obj["icon"]?.asStringOrNull(),
                color = obj["color"]?.asStringOrNull(),
                tapAction = parseAction(obj["tap_action"] as? JsonObject),
                holdAction = parseAction(obj["hold_action"] as? JsonObject),
                doubleTapAction = parseAction(obj["double_tap_action"] as? JsonObject),
                description = obj["description"]?.asStringOrNull(),
                vertical = obj["vertical"]?.asBooleanOrNull() ?: false,
            )
            "tile" -> {
                val entity = obj["entity"]?.asStringOrNull() ?: return LovelaceCard.Unsupported(obj, type)
                LovelaceCard.Tile(
                    raw = obj,
                    entityId = entity,
                    name = obj["name"]?.asStringOrNull(),
                    icon = obj["icon"]?.asStringOrNull(),
                    hideState = obj["hide_state"]?.asBooleanOrNull() ?: false,
                    vertical = obj["vertical"]?.asBooleanOrNull() ?: false,
                    color = obj["color"]?.asStringOrNull(),
                    stateColor = obj["state_color"]?.asBooleanOrNull() ?: true,
                    tapAction = parseAction(obj["tap_action"] as? JsonObject),
                    holdAction = parseAction(obj["hold_action"] as? JsonObject),
                    doubleTapAction = parseAction(obj["double_tap_action"] as? JsonObject),
                    iconTapAction = parseAction(obj["icon_tap_action"] as? JsonObject),
                    iconHoldAction = parseAction(obj["icon_hold_action"] as? JsonObject),
                    iconDoubleTapAction = parseAction(obj["icon_double_tap_action"] as? JsonObject),
                    showEntityPicture = obj["show_entity_picture"]?.asBooleanOrNull() ?: false,
                    featuresPosition = obj["features_position"]?.asStringOrNull(),
                    features = parseTileFeatures(obj["features"]),
                    stateContent = parseStringList(obj["state_content"]),
                    nameType = obj["name_type"]?.asStringOrNull(),
                    nameItems = parseStructuredName(obj["name"]),
                )
            }
            "light" -> {
                val entity = obj["entity"]?.asStringOrNull() ?: return LovelaceCard.Unsupported(obj, type)
                LovelaceCard.Light(
                    raw = obj,
                    entityId = entity,
                    name = obj["name"]?.asStringOrNull(),
                    icon = obj["icon"]?.asStringOrNull(),
                    tapAction = parseAction(obj["tap_action"] as? JsonObject),
                    holdAction = parseAction(obj["hold_action"] as? JsonObject),
                    doubleTapAction = parseAction(obj["double_tap_action"] as? JsonObject),
                    theme = obj["theme"]?.asStringOrNull(),
                )
            }
            "gauge" -> {
                val entity = obj["entity"]?.asStringOrNull() ?: return LovelaceCard.Unsupported(obj, type)
                LovelaceCard.Gauge(
                    raw = obj,
                    entityId = entity,
                    name = obj["name"]?.asStringOrNull(),
                    unit = obj["unit"]?.asStringOrNull(),
                    min = obj["min"]?.asDoubleOrNull() ?: 0.0,
                    max = obj["max"]?.asDoubleOrNull() ?: 100.0,
                    needle = obj["needle"]?.asBooleanOrNull() ?: false,
                    severity = (obj["severity"] as? JsonObject)?.let { sev ->
                        GaugeSeverity(
                            green = sev["green"]?.asDoubleOrNull(),
                            yellow = sev["yellow"]?.asDoubleOrNull(),
                            red = sev["red"]?.asDoubleOrNull(),
                        )
                    },
                    segments = parseGaugeSegments(obj["segments"]),
                    attribute = obj["attribute"]?.asStringOrNull(),
                    tapAction = parseAction(obj["tap_action"] as? JsonObject),
                    holdAction = parseAction(obj["hold_action"] as? JsonObject),
                    doubleTapAction = parseAction(obj["double_tap_action"] as? JsonObject),
                )
            }
            "weather-forecast" -> {
                val entity = obj["entity"]?.asStringOrNull() ?: return LovelaceCard.Unsupported(obj, type)
                LovelaceCard.WeatherForecast(
                    raw = obj,
                    entityId = entity,
                    name = obj["name"]?.asStringOrNull(),
                    showCurrent = obj["show_current"]?.asBooleanOrNull() ?: true,
                    showForecast = obj["show_forecast"]?.asBooleanOrNull() ?: true,
                    forecastType = obj["forecast_type"]?.asStringOrNull()?.lowercase(),
                    forecastSlots = obj["forecast_slots"]?.asIntOrNull(),
                    secondaryInfoAttribute = obj["secondary_info_attribute"]?.asStringOrNull(),
                    roundTemperature = obj["round_temperature"]?.asBooleanOrNull() ?: false,
                    tapAction = parseAction(obj["tap_action"] as? JsonObject),
                    holdAction = parseAction(obj["hold_action"] as? JsonObject),
                    doubleTapAction = parseAction(obj["double_tap_action"] as? JsonObject),
                )
            }
            "markdown" -> LovelaceCard.Markdown(
                raw = obj,
                title = obj["title"]?.asStringOrNull(),
                content = obj["content"]?.asStringOrNull().orEmpty(),
                tapAction = parseAction(obj["tap_action"] as? JsonObject),
                holdAction = parseAction(obj["hold_action"] as? JsonObject),
                doubleTapAction = parseAction(obj["double_tap_action"] as? JsonObject),
                // `entity_id` may be a single id string or a list; accept both.
                entityIds = parseEntityIdScope(obj["entity_id"]),
                textOnly = obj["text_only"]?.asBooleanOrNull() ?: false,
                showEmpty = obj["show_empty"]?.asBooleanOrNull() ?: true,
            )
            "heading" -> LovelaceCard.Heading(
                raw = obj,
                heading = obj["heading"]?.asStringOrNull().orEmpty(),
                headingStyle = obj["heading_style"]?.asStringOrNull() ?: "title",
                icon = obj["icon"]?.asStringOrNull(),
                badges = parseBadges(mergeHeadingEntitiesIntoBadges(obj)),
                tapAction = parseAction(obj["tap_action"] as? JsonObject),
                holdAction = parseAction(obj["hold_action"] as? JsonObject),
                doubleTapAction = parseAction(obj["double_tap_action"] as? JsonObject),
            )
            "vertical-stack" -> LovelaceCard.VerticalStack(
                raw = obj,
                title = obj["title"]?.asStringOrNull(),
                cards = parseChildCards(obj["cards"]),
            )
            "horizontal-stack" -> LovelaceCard.HorizontalStack(
                raw = obj,
                title = obj["title"]?.asStringOrNull(),
                cards = parseChildCards(obj["cards"]),
            )
            "grid" -> LovelaceCard.Grid(
                raw = obj,
                title = obj["title"]?.asStringOrNull(),
                columns = obj["columns"]?.asIntOrNull() ?: 3,
                square = obj["square"]?.asBooleanOrNull() ?: true,
                cards = parseChildCards(obj["cards"]),
            )
            "conditional" -> {
                val inner = obj["card"] as? JsonObject
                    ?: return LovelaceCard.Unsupported(obj, type)
                LovelaceCard.Conditional(
                    raw = obj,
                    conditions = parseConditions(obj["conditions"]),
                    card = parseCard(inner),
                )
            }
            "sensor" -> {
                val entity = obj["entity"]?.asStringOrNull() ?: return LovelaceCard.Unsupported(obj, type)
                val limits = obj["limits"] as? JsonObject
                LovelaceCard.Sensor(
                    raw = obj,
                    entityId = entity,
                    name = obj["name"]?.asStringOrNull(),
                    icon = obj["icon"]?.asStringOrNull(),
                    unit = obj["unit"]?.asStringOrNull(),
                    // HA's only graph value that draws a line is "line"; "none"
                    // (the default) shows just the readout.
                    graph = obj["graph"]?.asStringOrNull()?.equals("line", ignoreCase = true) ?: false,
                    hoursToShow = obj["hours_to_show"]?.asIntOrNull() ?: 24,
                    detail = obj["detail"]?.asIntOrNull(),
                    limitMin = limits?.get("min")?.asDoubleOrNull(),
                    limitMax = limits?.get("max")?.asDoubleOrNull(),
                    stateColor = obj["state_color"]?.asBooleanOrNull() ?: false,
                    attribute = obj["attribute"]?.asStringOrNull(),
                    tapAction = parseAction(obj["tap_action"] as? JsonObject),
                    holdAction = parseAction(obj["hold_action"] as? JsonObject),
                    doubleTapAction = parseAction(obj["double_tap_action"] as? JsonObject),
                )
            }
            "picture-glance" -> LovelaceCard.PictureGlance(
                raw = obj,
                title = obj["title"]?.asStringOrNull(),
                image = obj["image"]?.asStringOrNull(),
                cameraImage = obj["camera_image"]?.asStringOrNull()
                    ?: obj["image_entity"]?.asStringOrNull()
                    ?: obj["entity"]?.asStringOrNull(),
                entities = parseEntityRows(obj["entities"]),
                tapAction = parseAction(obj["tap_action"] as? JsonObject),
                holdAction = parseAction(obj["hold_action"] as? JsonObject),
                doubleTapAction = parseAction(obj["double_tap_action"] as? JsonObject),
                showState = obj["show_state"]?.asBooleanOrNull() ?: false,
                forceDialog = obj["force_dialog"]?.asBooleanOrNull() ?: false,
                fitMode = obj["fit_mode"]?.asStringOrNull(),
                aspectRatio = obj["aspect_ratio"]?.asStringOrNull(),
                cameraView = obj["camera_view"]?.asStringOrNull(),
                stateImage = parseStateImageMap(obj["state_image"]),
                filter = obj["filter"]?.asStringOrNull(),
                stateFilter = parseStateFilterMap(obj["state_filter"]),
                darkModeImage = obj["dark_mode_image"]?.asStringOrNull(),
                darkModeFilter = obj["dark_mode_filter"]?.asStringOrNull(),
            )
            "picture-entity" -> {
                val entity = obj["entity"]?.asStringOrNull() ?: return LovelaceCard.Unsupported(obj, type)
                LovelaceCard.PictureEntity(
                    raw = obj,
                    entityId = entity,
                    name = obj["name"]?.asStringOrNull(),
                    image = obj["image"]?.asStringOrNull(),
                    imageEntity = obj["image_entity"]?.asStringOrNull(),
                    showName = obj["show_name"]?.asBooleanOrNull() ?: true,
                    showState = obj["show_state"]?.asBooleanOrNull() ?: true,
                    showEntityPicture = obj["show_entity_picture"]?.asBooleanOrNull() ?: false,
                    tapAction = parseAction(obj["tap_action"] as? JsonObject),
                    holdAction = parseAction(obj["hold_action"] as? JsonObject),
                    doubleTapAction = parseAction(obj["double_tap_action"] as? JsonObject),
                    fitMode = obj["fit_mode"]?.asStringOrNull(),
                    aspectRatio = obj["aspect_ratio"]?.asStringOrNull(),
                    cameraImage = obj["camera_image"]?.asStringOrNull(),
                    cameraView = obj["camera_view"]?.asStringOrNull(),
                    stateImage = parseStateImageMap(obj["state_image"]),
                    filter = obj["filter"]?.asStringOrNull(),
                    stateFilter = parseStateFilterMap(obj["state_filter"]),
                    darkModeImage = obj["dark_mode_image"]?.asStringOrNull(),
                    darkModeFilter = obj["dark_mode_filter"]?.asStringOrNull(),
                )
            }
            "area" -> {
                val area = obj["area"]?.asStringOrNull() ?: return LovelaceCard.Unsupported(obj, type)
                val navPath = obj["navigation_path"]?.asStringOrNull()
                val explicitTap = parseAction(obj["tap_action"] as? JsonObject)
                LovelaceCard.Area(
                    raw = obj,
                    area = area,
                    name = obj["name"]?.asStringOrNull(),
                    image = obj["image"]?.asStringOrNull(),
                    entities = parseEntityRows(obj["entities"]),
                    navigationPath = navPath,
                    sensorClasses = parseStringList(obj["sensor_classes"]),
                    alertClasses = parseStringList(obj["alert_classes"]),
                    displayType = obj["display_type"]?.asStringOrNull(),
                    showCamera = obj["show_camera"]?.asBooleanOrNull() ?: false,
                    cameraView = obj["camera_view"]?.asStringOrNull(),
                    vertical = obj["vertical"]?.asBooleanOrNull() ?: false,
                    aspectRatio = obj["aspect_ratio"]?.asStringOrNull(),
                    excludeEntities = parseFocusEntities(obj["exclude_entities"]),
                    color = obj["color"]?.asStringOrNull(),
                    features = parseTileFeatures(obj["features"]),
                    // A bare navigation_path with no explicit tap_action becomes a
                    // Navigate tap (HA's area-card default); an explicit tap wins.
                    tapAction = explicitTap
                        ?: navPath?.let { LovelaceAction.Navigate(it) },
                    holdAction = parseAction(obj["hold_action"] as? JsonObject),
                    doubleTapAction = parseAction(obj["double_tap_action"] as? JsonObject),
                )
            }
            "history-graph" -> {
                val hoursExact = obj["hours_to_show"]?.asDoubleOrNull() ?: 24.0
                LovelaceCard.HistoryGraph(
                    raw = obj,
                    title = obj["title"]?.asStringOrNull(),
                    entities = parseEntityRows(obj["entities"]),
                    hoursToShowExact = hoursExact,
                    hoursToShow = hoursExact.coerceAtLeast(1.0).toInt(),
                    splitDeviceClasses = obj["split_device_classes"]?.asBooleanOrNull() ?: false,
                    entityColors = parseEntityColors(obj["entities"]),
                    showNames = obj["show_names"]?.asBooleanOrNull() ?: true,
                    logarithmicScale = obj["logarithmic_scale"]?.asBooleanOrNull() ?: false,
                    minYAxis = obj["min_y_axis"]?.asDoubleOrNull(),
                    maxYAxis = obj["max_y_axis"]?.asDoubleOrNull(),
                    fitYData = obj["fit_y_data"]?.asBooleanOrNull() ?: false,
                    expandLegend = obj["expand_legend"]?.asBooleanOrNull() ?: false,
                )
            }
            "alarm-panel" -> {
                val entity = obj["entity"]?.asStringOrNull() ?: return LovelaceCard.Unsupported(obj, type)
                LovelaceCard.AlarmPanel(
                    raw = obj,
                    entityId = entity,
                    name = obj["name"]?.asStringOrNull(),
                    states = (obj["states"] as? JsonArray)
                        ?.mapNotNull { it.asStringOrNull() }
                        ?: emptyList(),
                    nameItems = parseStructuredName(obj["name"]),
                )
            }
            "map" -> LovelaceCard.Map(
                raw = obj,
                title = obj["title"]?.asStringOrNull(),
                entities = parseEntityRows(obj["entities"]),
                hoursToShow = obj["hours_to_show"]?.asIntOrNull(),
                labelMode = obj["label_mode"]?.asStringOrNull(),
                focusEntities = parseFocusEntities(obj["focus_entities"]),
                markers = parseMapMarkers(obj["entities"]),
                labelAttribute = obj["attribute"]?.asStringOrNull(),
                theme = obj["theme"]?.asStringOrNull(),
                showAll = obj["show_all"]?.asBooleanOrNull() ?: false,
                fitZones = obj["fit_zones"]?.asBooleanOrNull() ?: false,
                cluster = obj["cluster"]?.asBooleanOrNull() ?: true,
                geoLocationSources = parseStringList(obj["geo_location_sources"]),
                conditions = (obj["conditions"] as? JsonArray)?.let { parseConditions(it) } ?: emptyList(),
            )
            "thermostat" -> {
                val entity = obj["entity"]?.asStringOrNull() ?: return LovelaceCard.Unsupported(obj, type)
                LovelaceCard.Thermostat(
                    raw = obj,
                    entityId = entity,
                    name = obj["name"]?.asStringOrNull(),
                    showCurrentTemperature = obj["show_current_temperature"]?.asBooleanOrNull() ?: true,
                    showCurrentAsPrimary = obj["show_current_as_primary"]?.asBooleanOrNull() ?: false,
                    features = parseTileFeatures(obj["features"]),
                    nameItems = parseStructuredName(obj["name"]),
                )
            }
            "media-control" -> {
                val entity = obj["entity"]?.asStringOrNull() ?: return LovelaceCard.Unsupported(obj, type)
                LovelaceCard.MediaControl(
                    raw = obj,
                    entityId = entity,
                    name = obj["name"]?.asStringOrNull(),
                )
            }
            "humidifier" -> {
                val entity = obj["entity"]?.asStringOrNull() ?: return LovelaceCard.Unsupported(obj, type)
                LovelaceCard.Humidifier(
                    raw = obj,
                    entityId = entity,
                    name = obj["name"]?.asStringOrNull(),
                    showCurrentTemperature = obj["show_current_temperature"]?.asBooleanOrNull() ?: true,
                    showCurrentAsPrimary = obj["show_current_as_primary"]?.asBooleanOrNull() ?: false,
                    features = parseTileFeatures(obj["features"]),
                    theme = obj["theme"]?.asStringOrNull(),
                )
            }
            "entity-filter" -> {
                // A wrapped `card:` renders the survivors as that card type (the
                // entities list is injected at render time). Reject a wrapped
                // entity-filter to avoid infinite recursion; null then falls back
                // to the default entities card in the renderer.
                val wrapped = (obj["card"] as? JsonObject)
                    ?.takeUnless { (it["type"]?.asStringOrNull()?.lowercase()) == "entity-filter" }
                LovelaceCard.EntityFilter(
                    raw = obj,
                    title = obj["title"]?.asStringOrNull(),
                    entries = parseEntityFilterEntries(obj["entities"]),
                    stateFilter = parseStateFilterRules(obj["state_filter"]),
                    conditions = parseConditions(obj["conditions"]),
                    showEmpty = obj["show_empty"]?.asBooleanOrNull() ?: true,
                    wrappedCard = wrapped,
                )
            }
            "statistic" -> {
                val entity = parseStatisticEntity(obj) ?: return LovelaceCard.Unsupported(obj, type)
                LovelaceCard.Statistic(
                    raw = obj,
                    entityId = entity,
                    name = obj["name"]?.asStringOrNull(),
                    statType = obj["stat_type"]?.asStringOrNull()?.lowercase() ?: "mean",
                    period = parseStatisticPeriod(obj["period"]),
                    periodSpec = parseStatisticPeriodConfig(obj["period"]),
                    icon = obj["icon"]?.asStringOrNull(),
                    unit = obj["unit"]?.asStringOrNull(),
                    collectionKey = parseCollectionKey(obj),
                )
            }
            "logbook" -> LovelaceCard.Logbook(
                raw = obj,
                title = obj["title"]?.asStringOrNull(),
                entities = parseLogbookEntities(obj),
                // HA's DEFAULT_HOURS_TO_SHOW is 24; a bare config shows the full day.
                hoursToShow = obj["hours_to_show"]?.asIntOrNull() ?: 24,
                target = parseLogbookTarget(obj["target"] as? JsonObject),
                stateFilter = parseStringList(obj["state_filter"]),
                theme = obj["theme"]?.asStringOrNull(),
            )
            "clock" -> LovelaceCard.Clock(
                raw = obj,
                title = obj["title"]?.asStringOrNull(),
                showSeconds = obj["show_seconds"]?.asBooleanOrNull() ?: false,
                analog = obj["clock_style"]?.asStringOrNull()?.equals("analog", ignoreCase = true) ?: false,
                clockSize = obj["clock_size"]?.asStringOrNull(),
                timeFormat = obj["time_format"]?.asStringOrNull(),
                timeZone = obj["time_zone"]?.asStringOrNull(),
                noBackground = obj["no_background"]?.asBooleanOrNull() ?: false,
            )
            "distribution" -> LovelaceCard.Distribution(
                raw = obj,
                title = obj["title"]?.asStringOrNull(),
                entries = parseDistributionEntries(obj["entities"]),
            )
            "energy-date-selection" -> LovelaceCard.EnergyDateSelection(
                raw = obj,
                collectionKey = parseCollectionKey(obj),
            )
            in ENERGY_CARD_TYPES_BY_TYPE.keys -> LovelaceCard.Energy(
                raw = obj,
                kind = ENERGY_CARD_TYPES_BY_TYPE.getValue(type),
                title = obj["title"]?.asStringOrNull(),
                collectionKey = parseCollectionKey(obj),
            )
            "statistics-graph" -> {
                val ids = parseStatisticsGraphEntities(obj)
                if (ids.isEmpty()) return bestEffortUnsupported(obj, type)
                // HA accepts stat_types as a single string or a list.
                val statTypes = (obj["stat_types"]?.asStringOrNull()?.let { listOf(it.lowercase()) }
                    ?: parseStringList(obj["stat_types"]).map { it.lowercase() })
                    .takeIf { it.isNotEmpty() } ?: listOf("mean")
                LovelaceCard.StatisticsGraph(
                    raw = obj,
                    title = obj["title"]?.asStringOrNull(),
                    entityIds = ids,
                    statTypes = statTypes,
                    // statistics-graph `period:` is a recorder bucket size string.
                    period = obj["period"]?.asStringOrNull()?.lowercase() ?: "hour",
                    chartType = obj["chart_type"]?.asStringOrNull()?.lowercase() ?: "line",
                    daysToShow = obj["days_to_show"]?.asIntOrNull(),
                    entityNames = parseStatisticsGraphEntityNames(obj),
                    entityColors = parseEntityColors(obj["entities"]),
                    minYAxis = obj["min_y_axis"]?.asDoubleOrNull(),
                    maxYAxis = obj["max_y_axis"]?.asDoubleOrNull(),
                    fitYData = obj["fit_y_data"]?.asBooleanOrNull() ?: false,
                    logarithmicScale = obj["logarithmic_scale"]?.asBooleanOrNull() ?: false,
                    unit = obj["unit"]?.asStringOrNull(),
                    hideLegend = obj["hide_legend"]?.asBooleanOrNull() ?: false,
                    expandLegend = obj["expand_legend"]?.asBooleanOrNull() ?: false,
                    collectionKey = parseCollectionKey(obj),
                )
            }
            "picture" -> {
                val image = obj["image"]?.asStringOrNull()
                val imageEntity = obj["image_entity"]?.asStringOrNull()
                val cameraImage = obj["camera_image"]?.asStringOrNull()
                if (image.isNullOrBlank() && imageEntity.isNullOrBlank() && cameraImage.isNullOrBlank()) {
                    return bestEffortUnsupported(obj, type)
                }
                LovelaceCard.Picture(
                    raw = obj,
                    image = image?.takeUnless { it.isBlank() },
                    imageEntity = imageEntity?.takeUnless { it.isBlank() },
                    tapAction = parseAction(obj["tap_action"] as? JsonObject),
                    holdAction = parseAction(obj["hold_action"] as? JsonObject),
                    doubleTapAction = parseAction(obj["double_tap_action"] as? JsonObject),
                    aspectRatio = obj["aspect_ratio"]?.asStringOrNull(),
                    cameraImage = cameraImage?.takeUnless { it.isBlank() },
                    cameraView = obj["camera_view"]?.asStringOrNull(),
                    stateImage = parseStateImageMap(obj["state_image"]),
                    filter = obj["filter"]?.asStringOrNull(),
                    stateFilter = parseStateFilterMap(obj["state_filter"]),
                    darkModeImage = obj["dark_mode_image"]?.asStringOrNull(),
                    darkModeFilter = obj["dark_mode_filter"]?.asStringOrNull(),
                )
            }
            "picture-elements" -> LovelaceCard.PictureElements(
                raw = obj,
                image = obj["image"]?.asStringOrNull(),
                cameraImage = obj["camera_image"]?.asStringOrNull(),
                elements = parseElements(obj["elements"] as? JsonArray),
                aspectRatio = obj["aspect_ratio"]?.asStringOrNull(),
                cameraView = obj["camera_view"]?.asStringOrNull(),
                title = obj["title"]?.asStringOrNull(),
                entity = obj["entity"]?.asStringOrNull(),
                imageEntity = obj["image_entity"]?.asStringOrNull(),
                stateImage = parseStateImageMap(obj["state_image"]),
                stateFilter = parseStateFilterMap(obj["state_filter"]),
                filter = obj["filter"]?.asStringOrNull(),
                darkModeImage = obj["dark_mode_image"]?.asStringOrNull(),
                darkModeFilter = obj["dark_mode_filter"]?.asStringOrNull(),
            )
            "calendar" -> {
                val ids = parseCalendarEntityIds(obj["entities"])
                if (ids.isEmpty()) return bestEffortUnsupported(obj, type)
                LovelaceCard.Calendar(
                    raw = obj,
                    title = obj["title"]?.asStringOrNull(),
                    entityIds = ids,
                    initialView = obj["initial_view"]?.asStringOrNull(),
                )
            }
            "home-summary" -> {
                val summary = obj["summary"]?.asStringOrNull()?.lowercase()
                    ?: return bestEffortUnsupported(obj, type)
                LovelaceCard.HomeSummary(
                    raw = obj,
                    summary = summary,
                    vertical = obj["vertical"]?.asBooleanOrNull() ?: false,
                    tapAction = parseAction(obj["tap_action"] as? JsonObject),
                    holdAction = parseAction(obj["hold_action"] as? JsonObject),
                    doubleTapAction = parseAction(obj["double_tap_action"] as? JsonObject),
                )
            }
            "updates" -> LovelaceCard.Updates(
                raw = obj,
                hideEmpty = obj["hide_empty"]?.asBooleanOrNull() ?: false,
                vertical = obj["vertical"]?.asBooleanOrNull() ?: false,
                tapAction = parseAction(obj["tap_action"] as? JsonObject),
                holdAction = parseAction(obj["hold_action"] as? JsonObject),
                doubleTapAction = parseAction(obj["double_tap_action"] as? JsonObject),
            )
            "repairs" -> LovelaceCard.Repairs(
                raw = obj,
                hideEmpty = obj["hide_empty"]?.asBooleanOrNull() ?: false,
                vertical = obj["vertical"]?.asBooleanOrNull() ?: false,
                tapAction = parseAction(obj["tap_action"] as? JsonObject),
                holdAction = parseAction(obj["hold_action"] as? JsonObject),
                doubleTapAction = parseAction(obj["double_tap_action"] as? JsonObject),
            )
            "empty-state" -> LovelaceCard.EmptyState(
                raw = obj,
                title = obj["title"]?.asStringOrNull(),
                content = obj["content"]?.asStringOrNull(),
                icon = obj["icon"]?.asStringOrNull(),
                contentOnly = obj["content_only"]?.asBooleanOrNull() ?: false,
            )
            "iframe" -> bestEffortUnsupported(obj, type)
            else -> mapCustomCard(obj, type) ?: bestEffortUnsupported(obj, type)
        }
    }

    /**
     * Route a recognised community / custom card (`custom:*`) to the nearest
     * native [LovelaceCard] so it renders as a first-class tile instead of the
     * generic best-effort fallback with a raw "type:" caption.
     *
     * Returns null when the type isn't one we map, or when a mapping needs an
     * entity the config doesn't provide. The caller then falls through to
     * [bestEffortUnsupported], which keeps a tasteful placeholder.
     *
     * We deliberately reuse the existing native card variants rather than
     * inventing new ones: mushroom-* cards are thin styling wrappers around the
     * same entity controls HA's own cards expose, so a `mushroom-light-card`
     * maps cleanly onto our [LovelaceCard.Light], a `mushroom-climate-card`
     * onto [LovelaceCard.Thermostat], and so on. Cards without a dedicated
     * native twin (fan / cover / person / generic entity / template) map onto
     * [LovelaceCard.Tile], whose tap action already toggles or drills into the
     * entity per its domain. `mushroom-chips-card` maps onto [LovelaceCard.Glance]
     * (a compact row of entity chips), the closest native idiom.
     */
    private fun mapCustomCard(obj: JsonObject, type: String): LovelaceCard? {
        val normalized = type.removePrefix("custom:").lowercase()
        return when (normalized) {
            "mushroom-light-card" ->
                obj.entityIfPresent()?.let { entity ->
                    LovelaceCard.Light(
                        raw = obj,
                        entityId = entity,
                        name = obj["name"]?.asStringOrNull(),
                        icon = obj["icon"]?.asStringOrNull(),
                    )
                }
            "mushroom-media-player-card" ->
                obj.entityIfPresent()?.let { entity ->
                    LovelaceCard.MediaControl(
                        raw = obj,
                        entityId = entity,
                        name = obj["name"]?.asStringOrNull(),
                    )
                }
            "mushroom-climate-card" ->
                obj.entityIfPresent()?.let { entity ->
                    LovelaceCard.Thermostat(
                        raw = obj,
                        entityId = entity,
                        name = obj["name"]?.asStringOrNull(),
                    )
                }
            // Fan / cover / person / generic entity all map to the native Tile,
            // whose domain-aware default tap action does the right thing (toggle
            // a fan, open/close a cover, more-info a person) when no explicit
            // tap_action is configured.
            "mushroom-fan-card",
            "mushroom-cover-card",
            "mushroom-person-card",
            "mushroom-entity-card",
            ->
                obj.entityIfPresent()?.let { entity ->
                    tileFor(obj, entity, obj["name"]?.asStringOrNull(), obj["icon"]?.asStringOrNull())
                }
            "mushroom-template-card" -> mapTemplateCard(obj)
            "mushroom-chips-card" -> mapChipsCard(obj)
            "button-card" -> mapButtonCard(obj)
            else -> null
        }
    }

    /**
     * Mushroom template cards render arbitrary Jinja. We can't evaluate templates
     * client-side, so we map to a [LovelaceCard.Tile] best-effort: prefer the
     * explicit `entity`, else scrape the first entity id out of the `primary`,
     * `secondary`, or `icon` templates. `primary` becomes the tile name only when
     * it's plain text (no template markers); an `icon` is passed through only when
     * it's a literal `mdi:` glyph rather than a template.
     */
    private fun mapTemplateCard(obj: JsonObject): LovelaceCard? {
        val primary = obj["primary"]?.asStringOrNull()
        val secondary = obj["secondary"]?.asStringOrNull()
        val iconRaw = obj["icon"]?.asStringOrNull()
        val entity = obj.entityIfPresent()
            ?: extractEntityId(primary)
            ?: extractEntityId(secondary)
            ?: extractEntityId(iconRaw)
            ?: return null
        val name = primary?.takeIf { it.isPlainText() }
        val icon = iconRaw?.takeIf { it.isPlainText() }
        return tileFor(obj, entity, name, icon)
    }

    /** Build a Tile carrying the card's parsed tap_action (or null for the
     *  domain default applied at render time). */
    private fun tileFor(obj: JsonObject, entity: String, name: String?, icon: String?): LovelaceCard.Tile =
        LovelaceCard.Tile(
            raw = obj,
            entityId = entity,
            name = name,
            icon = icon,
            hideState = false,
            vertical = false,
            color = obj["icon_color"]?.asStringOrNull() ?: obj["color"]?.asStringOrNull(),
            tapAction = parseAction(obj["tap_action"] as? JsonObject),
        )

    /**
     * Map a mushroom chips card onto a native [LovelaceCard.Glance]: one chip per
     * entry that names an entity. Chips that carry no entity (weather / template /
     * conditional chips we can't resolve) are dropped. Returns null when nothing
     * usable remains, so the card falls back rather than rendering an empty glance.
     */
    private fun mapChipsCard(obj: JsonObject): LovelaceCard? {
        val chips = obj["chips"] as? JsonArray ?: return null
        val rows = chips.mapNotNull { chip ->
            val co = chip as? JsonObject ?: return@mapNotNull null
            val entity = co["entity"]?.asStringOrNull()?.takeIf { it.looksLikeEntityId() }
                ?: return@mapNotNull null
            EntityRow(
                entityId = entity,
                name = co["name"]?.asStringOrNull(),
                icon = co["icon"]?.asStringOrNull(),
                secondaryInfo = null,
            )
        }
        if (rows.isEmpty()) return null
        return LovelaceCard.Glance(
            raw = obj,
            title = obj["title"]?.asStringOrNull(),
            entities = rows,
            columns = null,
            showName = true,
            showState = true,
            showIcon = true,
        )
    }

    /**
     * Map a `custom:button-card` onto the native [LovelaceCard.Button]. button-card
     * configs almost always carry an `entity` and/or `name`; we surface both plus
     * the icon and preserve any `tap_action`, so it renders as a labelled,
     * actionable button rather than a bare "TAP" box. Requires at least an entity
     * or a name (a totally empty button-card has nothing to show).
     */
    private fun mapButtonCard(obj: JsonObject): LovelaceCard? {
        val entity = obj.entityIfPresent()
        val name = obj["name"]?.asStringOrNull()
        if (entity == null && name == null) return null
        return LovelaceCard.Button(
            raw = obj,
            entityId = entity,
            name = name,
            icon = obj["icon"]?.asStringOrNull(),
            showName = obj["show_name"]?.asBooleanOrNull() ?: true,
            showIcon = obj["show_icon"]?.asBooleanOrNull() ?: true,
            showState = obj["show_state"]?.asBooleanOrNull() ?: false,
            tapAction = parseAction(obj["tap_action"] as? JsonObject),
        )
    }

    /** The card's `entity` value, but only when it parses to a real entity id. */
    private fun JsonObject.entityIfPresent(): String? =
        this["entity"]?.asStringOrNull()?.takeIf { it.looksLikeEntityId() }

    /** First `domain.object_id` entity reference inside a (template) string, if any. */
    private fun extractEntityId(text: String?): String? {
        if (text == null) return null
        return ENTITY_ID_REGEX.find(text)?.value
    }

    /** True when a string is a literal value, not a Jinja template fragment. */
    private fun String.isPlainText(): Boolean = !contains("{{") && !contains("{%")

    /**
     * Build an [LovelaceCard.Unsupported] that captures whatever the renderer
     * can still make use of: entity refs (from `entity` / `entities`) and an
     * iframe `url`. Covers the long tail of `custom:*` cards so the renderer
     * can fall back to generic tiles / an embedded frame rather than a bare
     * placeholder. Cards with neither still resolve to a plain Unsupported and
     * keep the raw-JSON expander.
     */
    private fun bestEffortUnsupported(obj: JsonObject, type: String): LovelaceCard.Unsupported {
        val refs = LinkedHashSet<String>()
        obj["entity"]?.asStringOrNull()?.let { if (it.looksLikeEntityId()) refs.add(it) }
        (obj["entities"] as? JsonArray)?.forEach { item ->
            when (item) {
                is JsonPrimitive -> if (item.isString && item.content.looksLikeEntityId()) refs.add(item.content)
                is JsonObject -> item["entity"]?.asStringOrNull()?.let { if (it.looksLikeEntityId()) refs.add(it) }
                else -> Unit
            }
        }
        val url = obj["url"]?.asStringOrNull()?.takeUnless { it.isBlank() }
        // Strip a leading "custom:" so the caption reads "mushroom-light"
        // rather than "custom:mushroom-light".
        val friendly = type.removePrefix("custom:").ifBlank { type }
        // The iframe substrate honours `title:` / `hide_background:`. Other
        // unsupported cards ignore these (the renderer only reads them on the
        // iframe path), so capturing them unconditionally is harmless.
        return LovelaceCard.Unsupported(
            raw = obj,
            type = type,
            entityRefs = refs.toList(),
            url = url,
            friendlyType = friendly,
            iframeTitle = obj["title"]?.asStringOrNull(),
            hideBackground = obj["hide_background"]?.asBooleanOrNull() ?: false,
            theme = obj["theme"]?.asStringOrNull(),
        )
    }

    /** Loose `domain.object_id` shape check so we don't pull non-entity
     *  strings (template snippets, css selectors) into the entity refs. */
    private fun String.looksLikeEntityId(): Boolean {
        val dot = indexOf('.')
        return dot > 0 && dot < length - 1 && none { it.isWhitespace() }
    }

    private fun parseChildCards(el: JsonElement?): List<LovelaceCard> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            (item as? JsonObject)?.let(::parseCard)
                ?: nonObjectCardError(item)
        }
    }

    /**
     * Non-object card entry (bare string, number, null, or array) in a cards
     * array. HA renders an error card reading "Config is not an object" for
     * this case; R1HA emits an [LovelaceCard.Unsupported] with the same message
     * so the user sees a visible placeholder rather than a silent gap. A `null`
     * JSON value from a partially-parsed config is the most common trigger.
     */
    private fun nonObjectCardError(item: JsonElement): LovelaceCard.Unsupported? {
        // JsonNull is a legitimate intentional skip (no meaningful content to
        // surface); only non-null non-object entries become visible errors.
        if (item is JsonNull) return null
        val typeLabel = when (item) {
            is JsonPrimitive -> if (item.isString) "\"${item.content}\"" else item.content
            is JsonArray -> "(array)"
            else -> "(unknown)"
        }
        return LovelaceCard.Unsupported(
            raw = JsonObject(emptyMap()),
            type = "(config error)",
            friendlyType = "Config is not an object: $typeLabel",
        )
    }

    /**
     * Parse a tile card's `features:` array. Each entry is a `{type: ...}`
     * object (src/panels/lovelace/card-features/types.ts). We map the
     * high-value feature types onto the typed [LovelaceTileFeature] hierarchy
     * and keep an [LovelaceTileFeature.Unsupported] placeholder for the rest so
     * a future renderer can grow into them without a parser change. A bare
     * string or an entry without a `type` is dropped.
     */
    private fun parseTileFeatures(el: JsonElement?): List<LovelaceTileFeature> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val type = obj["type"]?.asStringOrNull()?.lowercase() ?: return@mapNotNull null
            when (type) {
                "cover-open-close" -> LovelaceTileFeature.CoverOpenClose
                "cover-position" -> LovelaceTileFeature.CoverPosition
                "light-brightness" -> LovelaceTileFeature.LightBrightness
                "fan-speed" -> LovelaceTileFeature.FanSpeed
                "climate-hvac-modes" -> LovelaceTileFeature.ClimateHvacModes(parseStringList(obj["hvac_modes"]))
                "alarm-modes" -> LovelaceTileFeature.AlarmModes(parseStringList(obj["modes"]))
                "lock-commands" -> LovelaceTileFeature.LockCommands
                "toggle" -> LovelaceTileFeature.Toggle
                // button / input_button / scene / script: a labeled press-button row.
                "button" -> LovelaceTileFeature.ButtonFeature(
                    actionName = obj["action_name"]?.asStringOrNull()?.takeUnless { it.isBlank() },
                )
                "target-temperature" -> LovelaceTileFeature.TargetTemperature
                "select-options" -> LovelaceTileFeature.SelectOptions(
                    options = parseStringList(obj["options"]),
                    dropdown = obj["style"]?.asStringOrNull() == "dropdown",
                )
                "media-player-playback" -> LovelaceTileFeature.MediaPlayback(parseStringList(obj["controls"]))
                "media-player-source" -> LovelaceTileFeature.MediaSource(parseStringList(obj["sources"]))
                "media-player-sound-mode" -> LovelaceTileFeature.MediaSoundMode(parseStringList(obj["sound_modes"]))
                "media-player-volume-buttons" -> LovelaceTileFeature.MediaVolumeButtons(
                    step = obj["step"]?.asIntOrNull() ?: 5,
                    // HA default is true (renderMuteButton: showMuteButton ?? true).
                    showMute = obj["show_mute_button"]?.asBooleanOrNull() ?: true,
                )
                "media-player-volume-slider" -> LovelaceTileFeature.MediaVolumeSlider(
                    // HA default is true (renderMuteButton: showMuteButton ?? true).
                    showMute = obj["show_mute_button"]?.asBooleanOrNull() ?: true,
                )
                // forecast_type absent -> null = resolve from the entity's
                // supported bits at render time (HA: daily > twice_daily > hourly).
                // show_labels defaults to TRUE, matching HA's `!== false` default.
                "temperature-forecast" -> LovelaceTileFeature.TemperatureForecast(
                    forecastType = obj["forecast_type"]?.asStringOrNull()?.lowercase(),
                    color = obj["color"]?.asStringOrNull(),
                    showLabels = obj["show_labels"]?.asBooleanOrNull() ?: true,
                    daysToShow = obj["days_to_show"]?.asIntOrNull(),
                    hoursToShow = obj["hours_to_show"]?.asIntOrNull(),
                )
                "precipitation-forecast" -> LovelaceTileFeature.PrecipitationForecast(
                    forecastType = obj["forecast_type"]?.asStringOrNull()?.lowercase(),
                    precipitationType = obj["precipitation_type"]?.asStringOrNull()?.lowercase() ?: "amount",
                    color = obj["color"]?.asStringOrNull(),
                    showLabels = obj["show_labels"]?.asBooleanOrNull() ?: true,
                    daysToShow = obj["days_to_show"]?.asIntOrNull(),
                    hoursToShow = obj["hours_to_show"]?.asIntOrNull(),
                )
                // Climate mode-pickers
                "climate-fan-modes" -> LovelaceTileFeature.ClimateFanModes(parseStringList(obj["fan_modes"]))
                "climate-preset-modes" -> LovelaceTileFeature.ClimatePresetModes(parseStringList(obj["preset_modes"]))
                "climate-swing-modes" -> LovelaceTileFeature.ClimateSwingModes(parseStringList(obj["swing_modes"]))
                // HA's config key is `swing_horizontal_modes` (not `swing_modes`);
                // reading the wrong key silently dropped any narrowing list.
                "climate-swing-horizontal-modes" -> LovelaceTileFeature.ClimateSwingHorizontalModes(parseStringList(obj["swing_horizontal_modes"]))
                // Fan mode-pickers and toggles
                "fan-preset-modes" -> LovelaceTileFeature.FanPresetModes(parseStringList(obj["preset_modes"]))
                "fan-direction" -> LovelaceTileFeature.FanDirection
                "fan-oscillate" -> LovelaceTileFeature.FanOscillate
                // Humidifier
                "humidifier-modes" -> LovelaceTileFeature.HumidifierModes(parseStringList(obj["modes"]))
                "humidifier-toggle" -> LovelaceTileFeature.HumidifierToggle
                // Water heater
                "water-heater-operation-modes" -> LovelaceTileFeature.WaterHeaterOperationModes(parseStringList(obj["operation_modes"]))
                // Lawn mower and vacuum
                "lawn-mower-commands" -> LovelaceTileFeature.LawnMowerCommands(parseStringList(obj["commands"]))
                "vacuum-commands" -> LovelaceTileFeature.VacuumCommands(parseStringList(obj["commands"]))
                // Cover tilt
                "cover-tilt" -> LovelaceTileFeature.CoverTilt
                "cover-tilt-position" -> LovelaceTileFeature.CoverTiltPosition
                // Valve
                "valve-open-close" -> LovelaceTileFeature.ValveOpenClose
                "valve-position" -> LovelaceTileFeature.ValvePosition
                // Lock
                "lock-open-door" -> LovelaceTileFeature.LockOpenDoor
                // Counter
                "counter-actions" -> LovelaceTileFeature.CounterActions(parseStringList(obj["actions"]))
                // Update: backup is "yes"/"no"/"ask" (HA UpdateActionsCardFeatureConfig).
                // A bare boolean true/false from old configs is coerced to "yes"/"no".
                "update-actions" -> LovelaceTileFeature.UpdateActions(
                    backup = when (val raw = obj["backup"]) {
                        is kotlinx.serialization.json.JsonPrimitive -> when {
                            raw.isString -> raw.content.lowercase().takeIf { it in setOf("yes", "no", "ask") } ?: "no"
                            raw.booleanOrNull == true -> "yes"
                            else -> "no"
                        }
                        else -> "no"
                    },
                )
                // Humidifier target humidity
                "target-humidity" -> LovelaceTileFeature.TargetHumidity
                // Number / input_number
                "numeric-input" -> LovelaceTileFeature.NumericInput
                // Light colour temperature
                "light-color-temp" -> LovelaceTileFeature.LightColorTemp
                // Bar-gauge (HA 2025.9)
                "bar-gauge" -> LovelaceTileFeature.BarGauge(
                    attribute = obj["attribute"]?.asStringOrNull(),
                    min = obj["min"]?.asDoubleOrNull() ?: 0.0,
                    max = obj["max"]?.asDoubleOrNull() ?: 100.0,
                    color = obj["color"]?.asStringOrNull(),
                )
                // Trend-graph (HA 2025.9)
                "trend-graph" -> LovelaceTileFeature.TrendGraph(
                    hoursToShow = obj["hours_to_show"]?.asIntOrNull() ?: 24,
                    // HA's `detail` defaults to true (draw every point); false
                    // downsamples to ~1 point/hour.
                    detail = obj["detail"]?.asBooleanOrNull() ?: true,
                )
                // Date-set (HA 2025.9)
                "date-set" -> LovelaceTileFeature.DateSet
                // Registry-favorite features
                "cover-position-favorite" -> LovelaceTileFeature.CoverPositionFavorite
                "cover-tilt-favorite" -> LovelaceTileFeature.CoverTiltFavorite
                "valve-position-favorite" -> LovelaceTileFeature.ValvePositionFavorite
                "light-color-favorites" -> LovelaceTileFeature.LightColorFavorites
                // Area-controls (default feature on area cards)
                "area-controls" -> LovelaceTileFeature.AreaControls(parseAreaControls(obj["controls"]))
                else -> LovelaceTileFeature.Unsupported(type)
            }
        }
    }

    /**
     * Parse a gauge card's `segments:` array (HA's modern band format). Each
     * entry carries `from` (the lower bound the band starts at) and `color`
     * (a theme-colour name or `#rrggbb`). Entries missing either are dropped;
     * the list is sorted ascending by `from` so the renderer can fill each band
     * up to the next band's start, matching hui-gauge-card's `_severityLevels`.
     */
    private fun parseGaugeSegments(el: JsonElement?): List<GaugeSegment> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val from = obj["from"]?.asDoubleOrNull() ?: return@mapNotNull null
            val color = obj["color"]?.asStringOrNull()?.takeUnless { it.isBlank() } ?: return@mapNotNull null
            GaugeSegment(from = from, color = color, label = obj["label"]?.asStringOrNull())
        }.sortedBy { it.from }
    }

    /** Pull a list of plain strings out of a JSON array (filter feature configs
     *  like `hvac_modes:` / `modes:` / `options:`). Non-string entries dropped. */
    private fun parseStringList(el: JsonElement?): List<String> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.let { p -> if (p.isString) p.content else null } }
    }

    /** Parse HA's `entity_id` scope key, which is either a single id string or a
     *  list of id strings. Blank entries are dropped. */
    private fun parseEntityIdScope(el: JsonElement?): List<String> = when (el) {
        is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { s -> s.isNotBlank() } }
        is JsonPrimitive -> if (el.isString) el.content.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList() else emptyList()
        else -> emptyList()
    }

    /**
     * Parse the area-controls feature's `controls:` list. HA accepts either a
     * bare domain/control token string (e.g. "light", "cover-shutter") or an
     * explicit `{entity_id: ...}` object; the object form is flattened to the
     * entity id, which the renderer treats as a single-entity control. Both forms
     * stay as plain strings so the renderer normalises them the same way HA does.
     */
    private fun parseAreaControls(el: JsonElement?): List<String> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> if (item.isString) item.content else null
                is JsonObject -> (item["entity_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                else -> null
            }
        }
    }

    /**
     * Parse the map card's `focus_entities:` list. HA accepts a plain list of
     * entity id strings; objects with `entity` / `focus: false` are also legal
     * but we only extract ids where focus is true (or implied by being a bare
     * string). Returns a set for O(1) lookup in the renderer.
     */
    private fun parseFocusEntities(el: JsonElement?): Set<String> {
        val arr = el as? JsonArray ?: return emptySet()
        val out = LinkedHashSet<String>()
        arr.forEach { item ->
            when (item) {
                is JsonPrimitive -> if (item.isString) out.add(item.content)
                is JsonObject -> {
                    // Respect explicit `focus: false` to opt an entity out.
                    val focus = item["focus"]?.asBooleanOrNull() ?: true
                    if (focus) item["entity"]?.asStringOrNull()?.let { out.add(it) }
                }
                else -> Unit
            }
        }
        return out
    }

    /**
     * Parse the map card's `entities:` list into per-marker config (declaration
     * order preserved so palette indices match HA's getColorByIndex). A bare
     * string entity gets a default [MapMarkerConfig]; an object pulls `color`,
     * `label_mode`, `attribute`, and `focus`. Entries without an `entity:` key
     * are dropped (mirrors [parseEntityRows]).
     */
    private fun parseMapMarkers(el: JsonElement?): List<MapMarkerConfig> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> if (item.isString) MapMarkerConfig(entityId = item.content) else null
                is JsonObject -> {
                    val entity = item["entity"]?.asStringOrNull() ?: return@mapNotNull null
                    MapMarkerConfig(
                        entityId = entity,
                        color = item["color"]?.asStringOrNull(),
                        labelMode = item["label_mode"]?.asStringOrNull(),
                        attribute = item["attribute"]?.asStringOrNull(),
                        focus = item["focus"]?.asBooleanOrNull() ?: true,
                    )
                }
                else -> null
            }
        }
    }

    /**
     * Parse a logbook card `target:` block into [LogbookTarget]. Reads the four
     * registry-group selectors (`device_id` / `area_id` / `floor_id` /
     * `label_id`), each accepting a single string or a list. The `entity_id`
     * selector is handled by [parseLogbookEntities] (it merges straight into the
     * entity list), so it is intentionally skipped here.
     */
    private fun parseLogbookTarget(obj: JsonObject?): LogbookTarget {
        if (obj == null) return LogbookTarget()
        fun ids(key: String): List<String> {
            val out = LinkedHashSet<String>()
            when (val v = obj[key]) {
                is JsonPrimitive -> if (v.isString) out.add(v.content)
                is JsonArray -> v.forEach { it.asStringOrNull()?.let(out::add) }
                else -> Unit
            }
            return out.toList()
        }
        return LogbookTarget(
            deviceIds = ids("device_id"),
            areaIds = ids("area_id"),
            floorIds = ids("floor_id"),
            labelIds = ids("label_id"),
        )
    }

    private fun parseEntityRows(el: JsonElement?): List<EntityRow> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> if (item.isString) {
                    EntityRow(
                        entityId = item.content,
                        name = null,
                        icon = null,
                        secondaryInfo = null,
                    )
                } else null
                is JsonObject -> parseEntityRowObject(item)
                else -> null
            }
        }
    }

    /** Parse one `{entity: ..., name: ..., ...}` row object into an [EntityRow].
     *  Returns null when there is no `entity:` key. Shared by the entities card,
     *  the entity-filter card, and any other entity-row list. */
    private fun parseEntityRowObject(item: JsonObject): EntityRow? {
        val entity = item["entity"]?.asStringOrNull() ?: return null
        return EntityRow(
            entityId = entity,
            name = item["name"]?.asStringOrNull(),
            icon = item["icon"]?.asStringOrNull(),
            secondaryInfo = item["secondary_info"]?.asStringOrNull(),
            nameType = item["name_type"]?.asStringOrNull(),
            tapAction = parseAction(item["tap_action"] as? JsonObject),
            holdAction = parseAction(item["hold_action"] as? JsonObject),
            doubleTapAction = parseAction(item["double_tap_action"] as? JsonObject),
            format = parseTimestampFormat(item["format"]?.asStringOrNull()),
            confirmation = parseConfirmation(item["confirmation"]),
            actionName = item["action_name"]?.asStringOrNull(),
            image = item["image"]?.asStringOrNull(),
            showState = item["show_state"]?.asBooleanOrNull(),
            attribute = item["attribute"]?.asStringOrNull(),
            prefix = item["prefix"]?.asStringOrNull(),
            suffix = item["suffix"]?.asStringOrNull(),
            stateColor = item["state_color"]?.asBooleanOrNull(),
            nameItems = parseStructuredName(item["name"]),
            showLastChanged = item["show_last_changed"]?.asBooleanOrNull() ?: false,
        )
    }

    /**
     * Parse the `entities:` array of an entities card into a mixed list of
     * [EntitiesItem.Entity] and [EntitiesItem.Special] items. Preserves the
     * original order so dividers / section headers land between the entity rows
     * they were placed between in the config.
     *
     * An object with a `type:` matching a special-row type is parsed as a
     * [SpecialRow] even when it also carries an `entity:` key (HA's explicit
     * type always wins). An object without `type:` that has no `entity:` is
     * kept as an [EntitiesItem.Special] with [SpecialRow.Unknown] so it doesn't
     * silently vanish — that matches HA's warning-row behaviour for unresolvable
     * entries.
     */
    private fun parseEntitiesItems(el: JsonElement?): List<EntitiesItem> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> if (item.isString) {
                    EntitiesItem.Entity(
                        EntityRow(
                            entityId = item.content,
                            name = null,
                            icon = null,
                            secondaryInfo = null,
                        ),
                    )
                } else null
                is JsonObject -> {
                    val type = item["type"]?.asStringOrNull()?.lowercase()
                    if (type != null && type in SPECIAL_ROW_TYPES) {
                        parseSpecialRow(item, type)?.let { EntitiesItem.Special(it) }
                    } else {
                        val entity = item["entity"]?.asStringOrNull()
                        if (entity != null) {
                            // Regular entity row; explicit `type:` (e.g. "simple") stored for
                            // the renderer to enforce the explicit row variant. The full
                            // generic-row contract (per-row tap / hold / double-tap,
                            // confirmation, action_name, image) parses the same way it does
                            // for the list-only [parseEntityRows] path.
                            EntitiesItem.Entity(
                                EntityRow(
                                    entityId = entity,
                                    name = item["name"]?.asStringOrNull(),
                                    icon = item["icon"]?.asStringOrNull(),
                                    secondaryInfo = item["secondary_info"]?.asStringOrNull(),
                                    nameType = item["name_type"]?.asStringOrNull(),
                                    tapAction = parseAction(item["tap_action"] as? JsonObject),
                                    holdAction = parseAction(item["hold_action"] as? JsonObject),
                                    doubleTapAction = parseAction(item["double_tap_action"] as? JsonObject),
                                    format = parseTimestampFormat(item["format"]?.asStringOrNull()),
                                    confirmation = parseConfirmation(item["confirmation"]),
                                    actionName = item["action_name"]?.asStringOrNull(),
                                    image = item["image"]?.asStringOrNull(),
                                    explicitType = type,
                                    nameItems = parseStructuredName(item["name"]),
                                ),
                            )
                        } else if (type != null) {
                            // Has a type we don't know, but no entity — keep as Unknown special row
                            // rather than dropping so the user sees something rather than nothing.
                            EntitiesItem.Special(SpecialRow.Unknown(raw = item, typeName = type))
                        } else {
                            null
                        }
                    }
                }
                else -> null
            }
        }
    }

    /**
     * Parse one special row object. Returns null only when the required fields
     * for that type are missing (e.g. a `button` row with no name and no entity),
     * otherwise always returns a [SpecialRow] so the card preserves the item's
     * position in the list.
     */
    private fun parseSpecialRow(obj: JsonObject, type: String): SpecialRow? {
        return when (type) {
        "section" -> SpecialRow.Section(
            raw = obj,
            label = obj["label"]?.asStringOrNull(),
        )
        "divider" -> SpecialRow.Divider(raw = obj)
        "attribute" -> {
            val entity = obj["entity"]?.asStringOrNull() ?: return null
            val attribute = obj["attribute"]?.asStringOrNull() ?: return null
            SpecialRow.Attribute(
                raw = obj,
                entityId = entity,
                attribute = attribute,
                name = obj["name"]?.asStringOrNull(),
                icon = obj["icon"]?.asStringOrNull(),
                prefix = obj["prefix"]?.asStringOrNull(),
                suffix = obj["suffix"]?.asStringOrNull(),
                format = obj["format"]?.asStringOrNull()?.let(::parseTimestampFormat),
            )
        }
        // button row and call-service / perform-action share the same rendering shape.
        // call-service wires its `action`/`service` + `data`/`service_data` fields onto
        // a tap_action of type perform-action before handing off to the button renderer.
        "button" -> {
            val name = obj["name"]?.asStringOrNull()
            val entity = obj["entity"]?.asStringOrNull()
            if (name.isNullOrBlank() && entity.isNullOrBlank()) return null
            SpecialRow.Button(
                raw = obj,
                entityId = entity,
                name = name,
                icon = obj["icon"]?.asStringOrNull(),
                actionName = obj["action_name"]?.asStringOrNull(),
                tapAction = parseAction(obj["tap_action"] as? JsonObject),
                holdAction = parseAction(obj["hold_action"] as? JsonObject),
                doubleTapAction = parseAction(obj["double_tap_action"] as? JsonObject),
            )
        }
        "call-service", "perform-action" -> {
            val name = obj["name"]?.asStringOrNull()
            if (name.isNullOrBlank()) return null
            val service = obj["action"]?.asStringOrNull() ?: obj["service"]?.asStringOrNull()
            val tapAction: LovelaceAction? = if (service != null) {
                val target = obj["target"] as? JsonObject
                val entityId = target?.get("entity_id")?.asStringOrNull()
                    ?: obj["entity_id"]?.asStringOrNull()
                LovelaceAction.CallService(
                    service = service,
                    entityId = entityId,
                    data = (obj["data"] as? JsonObject) ?: (obj["service_data"] as? JsonObject),
                )
            } else {
                parseAction(obj["tap_action"] as? JsonObject)
            }
            SpecialRow.Button(
                raw = obj,
                entityId = obj["entity"]?.asStringOrNull(),
                name = name,
                icon = obj["icon"]?.asStringOrNull(),
                actionName = obj["action_name"]?.asStringOrNull(),
                tapAction = tapAction,
                holdAction = parseAction(obj["hold_action"] as? JsonObject),
                doubleTapAction = parseAction(obj["double_tap_action"] as? JsonObject),
            )
        }
        "buttons" -> {
            val entries = parseButtonsEntries(obj["entities"])
            SpecialRow.Buttons(raw = obj, entries = entries)
        }
        "conditional" -> {
            val rowPayload = parseConditionalRowPayload(obj["row"]) ?: return null
            SpecialRow.Conditional(
                raw = obj,
                conditions = parseConditions(obj["conditions"]),
                row = rowPayload,
            )
        }
        "text" -> {
            val name = obj["name"]?.asStringOrNull() ?: return null
            val text = obj["text"]?.asStringOrNull() ?: return null
            SpecialRow.Text(
                raw = obj,
                name = name,
                text = text,
                icon = obj["icon"]?.asStringOrNull(),
            )
        }
        "weblink" -> {
            val url = obj["url"]?.asStringOrNull() ?: return null
            SpecialRow.Weblink(
                raw = obj,
                name = obj["name"]?.asStringOrNull() ?: url,
                url = url,
                icon = obj["icon"]?.asStringOrNull() ?: "mdi:link",
            )
        }
        "cast" -> SpecialRow.Cast(raw = obj)
        else -> SpecialRow.Unknown(raw = obj, typeName = type)
        }
    }

    /** Parse the `entities:` array of a `type: buttons` row. Each entry may be a bare
     *  entity-id string or an object carrying entity/icon/name/tap_action. */
    private fun parseButtonsEntries(el: JsonElement?): List<SpecialRow.Buttons.ButtonEntry> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> if (item.isString) {
                    SpecialRow.Buttons.ButtonEntry(
                        entityId = item.content,
                        icon = null,
                        name = null,
                        tapAction = null,
                    )
                } else null
                is JsonObject -> SpecialRow.Buttons.ButtonEntry(
                    entityId = item["entity"]?.asStringOrNull(),
                    icon = item["icon"]?.asStringOrNull(),
                    name = item["name"]?.asStringOrNull(),
                    tapAction = parseAction(item["tap_action"] as? JsonObject),
                )
                else -> null
            }
        }
    }

    /**
     * Parse the `row:` key of a `type: conditional` special row. Returns null when the
     * `row:` key is absent or unparseable so the conditional row itself is dropped.
     * Accepts both entity rows (has `entity:` or is a bare string) and special rows
     * (has a `type:` in SPECIAL_ROW_TYPES).
     */
    private fun parseConditionalRowPayload(el: JsonElement?): ConditionalRowPayload? {
        return when (el) {
            is JsonPrimitive -> if (el.isString) {
                ConditionalRowPayload.EntityRowPayload(
                    EntityRow(entityId = el.content, name = null, icon = null, secondaryInfo = null),
                )
            } else null
            is JsonObject -> {
                val type = el["type"]?.asStringOrNull()?.lowercase()
                if (type != null && type in SPECIAL_ROW_TYPES) {
                    val sr = parseSpecialRow(el, type) ?: return null
                    ConditionalRowPayload.SpecialRowPayload(sr)
                } else {
                    val entity = el["entity"]?.asStringOrNull() ?: return null
                    ConditionalRowPayload.EntityRowPayload(
                        EntityRow(
                            entityId = entity,
                            name = el["name"]?.asStringOrNull(),
                            icon = el["icon"]?.asStringOrNull(),
                            secondaryInfo = el["secondary_info"]?.asStringOrNull(),
                            nameType = el["name_type"]?.asStringOrNull(),
                            format = el["format"]?.asStringOrNull()?.let(::parseTimestampFormat),
                            explicitType = type,
                        ),
                    )
                }
            }
            else -> null
        }
    }

    /**
     * Parse HA's `format:` key on entity rows and badge rows. Accepts the five
     * values from `TimestampRenderingFormat` (hui-timestamp-display.ts):
     * relative | total | date | time | datetime. Unknown values and null yield
     * null (the renderer applies the device-class default: timestamp -> relative,
     * uptime -> total, everything else -> raw state).
     */
    private fun parseTimestampFormat(raw: String?): TimestampFormat? = when (raw?.trim()?.lowercase()) {
        "relative" -> TimestampFormat.RELATIVE
        "total" -> TimestampFormat.TOTAL
        "date" -> TimestampFormat.DATE
        "time" -> TimestampFormat.TIME
        "datetime" -> TimestampFormat.DATETIME
        else -> null
    }

    /**
     * Parse HA 2025.11+ structured `name` into a list of [EntityNameItem]. A
     * plain-string name returns an empty list (the existing string `name:` path
     * handles it). An object `{type: ...}` or an array of such objects is parsed
     * into parts:
     *  - `{type: entity|device|area|floor}` -> [EntityNameItem.Part]
     *  - `{type: text, text: "..."}` -> [EntityNameItem.Text]
     * Unknown item shapes are dropped. The resulting list is resolved at render
     * time against the entity's registry data; an empty list means "no structured
     * name", so callers keep their plain-string / friendly-name behaviour.
     */
    private fun parseStructuredName(el: JsonElement?): List<EntityNameItem> {
        fun parseItem(o: JsonObject): EntityNameItem? {
            val itemType = o["type"]?.asStringOrNull()?.trim()?.lowercase() ?: return null
            return when (itemType) {
                "text" -> o["text"]?.asStringOrNull()?.let { EntityNameItem.Text(it) }
                "entity", "device", "area", "floor" -> EntityNameItem.Part(itemType)
                else -> null
            }
        }
        return when (el) {
            is JsonObject -> listOfNotNull(parseItem(el))
            is JsonArray -> el.mapNotNull { (it as? JsonObject)?.let(::parseItem) }
            else -> emptyList()
        }
    }

    /**
     * Parse the calendar card's `entities:` into a list of calendar entity ids.
     * HA accepts bare strings ("calendar.work") or objects with an `entity` key
     * (with optional `color` which we don't use - color derives from the entity id
     * hash). Entries without a usable id are dropped.
     */
    private fun parseCalendarEntityIds(el: JsonElement?): List<String> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> if (item.isString) item.content.takeUnless { it.isBlank() } else null
                is JsonObject -> item["entity"]?.asStringOrNull()?.takeUnless { it.isBlank() }
                else -> null
            }
        }
    }

    /** Parse a distribution card's `entities:`: a bare id string or an object
     *  carrying `entity` / `name` / `color`. Entries without a usable id drop. */
    private fun parseDistributionEntries(el: JsonElement?): List<DistributionEntry> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> if (item.isString) {
                    DistributionEntry(entityId = item.content, name = null, color = null)
                } else null
                is JsonObject -> {
                    val entity = item["entity"]?.asStringOrNull() ?: return@mapNotNull null
                    DistributionEntry(
                        entityId = entity,
                        name = item["name"]?.asStringOrNull(),
                        color = item["color"]?.asStringOrNull(),
                    )
                }
                else -> null
            }
        }
    }

    /**
     * Parse the `elements:` array of a picture-elements card. Each entry is an
     * object with a required `type` key. Entries with no `type` are dropped.
     * Recognised types: `state-badge`, `state-icon`, `state-label`, `icon`,
     * `image`, `service-button` / `action-button`, and `conditional` (which
     * wraps a nested `elements:` list behind its `conditions:`). Any other type
     * is kept as an `unknown` placeholder element so the renderer can show a
     * small labelled chip rather than crash or drop it silently.
     *
     * Position is read from `style.top` / `style.left` as percentages, plain
     * numbers, or pixel values (see [parseStylePosition]); when absent the value
     * defaults to the centre.
     */
    private fun parseElements(arr: JsonArray?): List<PictureElement> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            parseElement(obj)
        }
    }

    private fun parseElement(obj: JsonObject): PictureElement? {
        val rawType = obj["type"]?.asStringOrNull() ?: return null
        val type = rawType.lowercase()
        val style = obj["style"] as? JsonObject
        val known = setOf(
            "state-badge", "state-icon", "state-label", "icon", "image",
            "service-button", "action-button", "conditional",
        )
        // Normalise the displayed type so the renderer's switch is exhaustive;
        // anything outside the known set becomes an "unknown" placeholder that
        // still carries its position and (for surfacing) its original type name.
        val normalised = if (type in known) type else "unknown"
        val serviceAction = obj["action"]?.asStringOrNull() ?: obj["service"]?.asStringOrNull()
        val serviceTarget = parseActionTarget(obj["target"] as? JsonObject)
        return PictureElement(
            type = normalised,
            entityId = obj["entity"]?.asStringOrNull(),
            icon = obj["icon"]?.asStringOrNull(),
            // For an unknown element keep the original type as a fallback label.
            name = obj["name"]?.asStringOrNull()
                ?: if (normalised == "unknown") rawType else null,
            attribute = obj["attribute"]?.asStringOrNull(),
            prefix = obj["prefix"]?.asStringOrNull(),
            suffix = obj["suffix"]?.asStringOrNull(),
            top = parseStylePosition(style?.get("top")),
            left = parseStylePosition(style?.get("left")),
            tapAction = parseAction(obj["tap_action"] as? JsonObject),
            holdAction = parseAction(obj["hold_action"] as? JsonObject),
            doubleTapAction = parseAction(obj["double_tap_action"] as? JsonObject),
            image = obj["image"]?.asStringOrNull(),
            imageEntity = obj["image_entity"]?.asStringOrNull(),
            cameraImage = obj["camera_image"]?.asStringOrNull(),
            cameraView = obj["camera_view"]?.asStringOrNull(),
            stateImage = parseStateImageMap(obj["state_image"]),
            stateFilter = parseStateFilterMap(obj["state_filter"]),
            filter = obj["filter"]?.asStringOrNull(),
            aspectRatio = obj["aspect_ratio"]?.asStringOrNull(),
            // state-icon defaults state_color on; others leave it on but only
            // state-icon reads it.
            stateColor = obj["state_color"]?.asBooleanOrNull() ?: true,
            serviceAction = serviceAction,
            serviceData = (obj["data"] as? JsonObject) ?: (obj["service_data"] as? JsonObject),
            serviceTarget = serviceTarget,
            title = obj["title"]?.asStringOrNull(),
            conditions = if (normalised == "conditional") parseConditions(obj["conditions"]) else emptyList(),
            children = if (normalised == "conditional") parseElements(obj["elements"] as? JsonArray) else emptyList(),
            transformOverride = (style?.get("transform"))?.asStringOrNull(),
            widthPx = parsePixelWidth(style?.get("width")),
        )
    }

    /**
     * Parse a `style.top` / `style.left` value into a typed [PicturePosition].
     * Accepts "25%" / "25" (percent of the image box), a raw JSON number
     * (percent), or "120px" (absolute pixels). Absent or unparseable input
     * yields the centre (50%).
     */
    private fun parseStylePosition(el: JsonElement?): PicturePosition {
        if (el == null) return PicturePosition.CENTER
        el.asDoubleOrNull()?.let { return PicturePosition(it, isPixel = false) }
        val s = el.asStringOrNull()?.trim() ?: return PicturePosition.CENTER
        return when {
            s.endsWith("px", ignoreCase = true) ->
                s.dropLast(2).trim().toDoubleOrNull()
                    ?.let { PicturePosition(it, isPixel = true) } ?: PicturePosition.CENTER
            s.endsWith("%") ->
                s.dropLast(1).trim().toDoubleOrNull()
                    ?.let { PicturePosition(it, isPixel = false) } ?: PicturePosition.CENTER
            else ->
                s.toDoubleOrNull()?.let { PicturePosition(it, isPixel = false) } ?: PicturePosition.CENTER
        }
    }

    /** Parse a `style.width` pixel value ("120px" / "120"). Returns null when
     *  absent or expressed in a non-pixel unit we don't size against. */
    private fun parsePixelWidth(el: JsonElement?): Double? {
        if (el == null) return null
        el.asDoubleOrNull()?.let { return it }
        val s = el.asStringOrNull()?.trim() ?: return null
        return when {
            s.endsWith("px", ignoreCase = true) -> s.dropLast(2).trim().toDoubleOrNull()
            else -> s.toDoubleOrNull()
        }
    }

    /**
     * Parse an entity-filter card's `entities:` list into [EntityFilterEntry]s.
     * Each entry is a bare string row or an object row; an object row may also
     * carry its own `state_filter:` / `conditions:` per-entity filter override
     * (mutually exclusive in HA; we keep both lists and let the evaluator apply
     * conditions-first precedence). Entries without a usable entity are dropped.
     */
    private fun parseEntityFilterEntries(el: JsonElement?): List<EntityFilterEntry> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> if (item.isString) {
                    EntityFilterEntry(EntityRow(item.content, null, null, null))
                } else null
                is JsonObject -> parseEntityRowObject(item)?.let { row ->
                    EntityFilterEntry(
                        row = row,
                        stateFilter = parseStateFilterRules(item["state_filter"]),
                        conditions = parseConditions(item["conditions"]),
                    )
                }
                else -> null
            }
        }
    }

    /**
     * Parse a `state_filter:` array into operator-form [StateFilterRule]s,
     * mirroring HA's `LegacyStateFilter`. Accepts the bare-string / bare-number
     * shorthand (operator `==`, comparing the entity state) and the object form
     * `{operator, value, attribute}`. An object with an unknown operator, or one
     * missing its required `value`, is dropped. An empty result means "no filter".
     */
    private fun parseStateFilterRules(el: JsonElement?): List<StateFilterRule> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            when (item) {
                is JsonPrimitive ->
                    // Bare string OR number shorthand: equality against the state.
                    item.asStringOrNull()?.let {
                        StateFilterRule(StateFilterOperator.EQ, listOf(it))
                    }
                is JsonObject -> {
                    val operator = StateFilterOperator.fromToken(
                        item["operator"]?.asStringOrNull() ?: "==",
                    ) ?: return@mapNotNull null
                    val values = parseStateFilterValues(item["value"]) ?: return@mapNotNull null
                    StateFilterRule(
                        operator = operator,
                        values = values,
                        attribute = item["attribute"]?.asStringOrNull(),
                    )
                }
                else -> null
            }
        }
    }

    /** Pull a state-filter rule's `value:` into a string list. HA accepts a
     *  scalar (string/number) or, for in / not in, an array; both stringify. */
    private fun parseStateFilterValues(el: JsonElement?): List<String>? = when (el) {
        is JsonPrimitive -> el.asStringOrNull()?.let { listOf(it) }
        is JsonArray -> el.mapNotNull { it.asStringOrNull() }.takeIf { it.isNotEmpty() }
        else -> null
    }

    /**
     * Parse a card-level `header:` / `footer:` slot into [LovelaceHeaderFooter].
     * Mirrors HA's three header-footer types (buttons / graph / picture). A slot
     * with a `type:` we don't model, or one missing its required field, returns
     * [LovelaceHeaderFooter.Unsupported] (renders nothing) rather than null so the
     * config round-trips.
     */
    fun parseHeaderFooter(el: JsonElement?): LovelaceHeaderFooter? {
        val obj = el as? JsonObject ?: return null
        return when (obj["type"]?.asStringOrNull()?.lowercase()) {
            "buttons" -> LovelaceHeaderFooter.Buttons(parseHeaderFooterButtons(obj["entities"]))
            "graph" -> {
                val entity = obj["entity"]?.asStringOrNull()
                    ?: return LovelaceHeaderFooter.Unsupported("graph")
                LovelaceHeaderFooter.Graph(
                    entityId = entity,
                    hoursToShow = obj["hours_to_show"]?.asIntOrNull() ?: 24,
                    // HA clamps detail to 1 or 2; anything else becomes 1.
                    detail = (obj["detail"]?.asIntOrNull() ?: 1).let { if (it == 2) 2 else 1 },
                    limitMin = (obj["limits"] as? JsonObject)?.get("min")?.asDoubleOrNull(),
                    limitMax = (obj["limits"] as? JsonObject)?.get("max")?.asDoubleOrNull(),
                )
            }
            "picture" -> {
                val image = obj["image"]?.asStringOrNull()
                    ?: return LovelaceHeaderFooter.Unsupported("picture")
                LovelaceHeaderFooter.Picture(
                    image = image,
                    altText = obj["alt_text"]?.asStringOrNull(),
                    tapAction = parseAction(obj["tap_action"] as? JsonObject),
                    holdAction = parseAction(obj["hold_action"] as? JsonObject),
                    doubleTapAction = parseAction(obj["double_tap_action"] as? JsonObject),
                )
            }
            else -> obj["type"]?.asStringOrNull()?.let { LovelaceHeaderFooter.Unsupported(it) }
        }
    }

    /** Parse a buttons header/footer's `entities:` into button entries. HA's
     *  per-entry tap defaults to `toggle` (scene entries to `scene.turn_on`),
     *  hold to `more-info`; we apply those defaults at render time so an absent
     *  tap_action here stays null and the dispatcher resolves the default. */
    private fun parseHeaderFooterButtons(el: JsonElement?): List<LovelaceHeaderFooter.Buttons.ButtonEntry> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> if (item.isString) {
                    LovelaceHeaderFooter.Buttons.ButtonEntry(item.content, null, null, null)
                } else null
                is JsonObject -> {
                    val entity = item["entity"]?.asStringOrNull() ?: return@mapNotNull null
                    LovelaceHeaderFooter.Buttons.ButtonEntry(
                        entityId = entity,
                        icon = item["icon"]?.asStringOrNull(),
                        name = item["name"]?.asStringOrNull(),
                        tapAction = parseAction(item["tap_action"] as? JsonObject),
                        holdAction = parseAction(item["hold_action"] as? JsonObject),
                    )
                }
                else -> null
            }
        }
    }

    /**
     * Parse a `state_image:` map: keys are entity state strings, values are
     * image URLs. HA YAML looks like `state_image: {on: "/local/on.png", off: "/local/off.png"}`.
     * Returns null when the element is absent or not an object.
     */
    private fun parseStateImageMap(el: JsonElement?): Map<String, String>? {
        val obj = el as? JsonObject ?: return null
        val result = mutableMapOf<String, String>()
        obj.entries.forEach { (key, value) ->
            val url = value.asStringOrNull()
            if (!url.isNullOrBlank()) result[key] = url
        }
        return result.takeUnless { it.isEmpty() }
    }

    /**
     * Parse a `state_filter:` map: keys are entity state strings, values are
     * CSS-ish filter strings. Absent or non-object elements return null.
     */
    private fun parseStateFilterMap(el: JsonElement?): Map<String, String>? {
        val obj = el as? JsonObject ?: return null
        val result = mutableMapOf<String, String>()
        obj.entries.forEach { (key, value) ->
            val filter = value.asStringOrNull()
            if (!filter.isNullOrBlank()) result[key] = filter
        }
        return result.takeUnless { it.isEmpty() }
    }

    /** Parse the statistics-graph `entities:` list. Each entry is either a bare
     *  statistic id string or an object carrying `entity` / `name`. Returns the
     *  ordered list of entity/statistic ids; entries without a usable id are dropped. */
    private fun parseStatisticsGraphEntities(obj: JsonObject): List<String> {
        val arr = obj["entities"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> if (item.isString) item.content.takeUnless { it.isBlank() } else null
                is JsonObject -> item["entity"]?.asStringOrNull()?.takeUnless { it.isBlank() }
                else -> null
            }
        }
    }

    /** Per-entity display-name overrides for a statistics-graph card, keyed by
     *  entity id. Only object entries carrying both `entity` and `name`
     *  contribute. */
    private fun parseStatisticsGraphEntityNames(obj: JsonObject): Map<String, String> {
        val arr = obj["entities"] as? JsonArray ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        arr.forEach { item ->
            val o = item as? JsonObject ?: return@forEach
            val id = o["entity"]?.asStringOrNull() ?: return@forEach
            o["name"]?.asStringOrNull()?.let { out[id] = it }
        }
        return out
    }

    /** Per-entity colour overrides for a history-graph / statistics-graph card,
     *  keyed by entity id. Only object entries carrying both `entity` and
     *  `color` contribute (the bare-string entity form has no colour). */
    private fun parseEntityColors(el: JsonElement?): Map<String, String> {
        val arr = el as? JsonArray ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        arr.forEach { item ->
            val o = item as? JsonObject ?: return@forEach
            val id = o["entity"]?.asStringOrNull() ?: return@forEach
            o["color"]?.asStringOrNull()?.let { out[id] = it }
        }
        return out
    }

    /** HA `energy_date_selection` (boolean) / `collection_key` (string). When
     *  energy_date_selection is true and no explicit key is given, HA uses the
     *  default energy collection; we surface a sentinel so the energy batch can
     *  bind it later. Null when neither is set. */
    private fun parseCollectionKey(obj: JsonObject): String? {
        obj["collection_key"]?.asStringOrNull()?.let { return it }
        if (obj["energy_date_selection"]?.asBooleanOrNull() == true) return "energy_date_selection"
        return null
    }

    /**
     * Parse the statistic card's `period:` into a [StatisticPeriodConfig].
     * Accepts the rich object forms (calendar / fixed_period / rolling_window)
     * and the legacy bare-string labels. The string-collapsing [parseStatisticPeriod]
     * stays for the coarse label; this is the precise resolution path.
     */
    private fun parseStatisticPeriodConfig(el: JsonElement?): StatisticPeriodConfig {
        when (el) {
            is JsonPrimitive -> if (el.isString) {
                return StatisticPeriodConfig.Calendar(el.content.lowercase(), 0)
            }
            is JsonObject -> {
                (el["calendar"] as? JsonObject)?.let { cal ->
                    val period = cal["period"]?.asStringOrNull()?.lowercase() ?: "day"
                    val offset = cal["offset"]?.asIntOrNull() ?: 0
                    return StatisticPeriodConfig.Calendar(period, offset)
                }
                (el["fixed_period"] as? JsonObject)?.let { fp ->
                    return StatisticPeriodConfig.Fixed(
                        startMillis = parseIsoMillis(fp["start"]?.asStringOrNull()),
                        endMillis = parseIsoMillis(fp["end"]?.asStringOrNull()),
                    )
                }
                (el["rolling_window"] as? JsonObject)?.let { rw ->
                    return StatisticPeriodConfig.Rolling(
                        durationMillis = parseDurationMillis(rw["duration"] as? JsonObject),
                        offsetMillis = parseDurationMillis(rw["offset"] as? JsonObject),
                    )
                }
            }
            else -> Unit
        }
        return StatisticPeriodConfig.Rolling(604_800_000L)
    }

    /** Parse an ISO-8601 instant string to epoch millis, or null. Uses the
     *  app's desugar-safe parser rather than Instant.parse so HA's +00:00
     *  offset is accepted on minSdk-23 devices. */
    private fun parseIsoMillis(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return com.github.itskenny0.r1ha.core.ha.parseHaInstant(s)?.toEpochMilli()
    }

    /** Parse HA's `{hours, minutes, seconds, days}` duration object to millis.
     *  Absent keys count as zero; an all-absent object yields zero. */
    private fun parseDurationMillis(o: JsonObject?): Long {
        if (o == null) return 0L
        val days = o["days"]?.asDoubleOrNull() ?: 0.0
        val hours = o["hours"]?.asDoubleOrNull() ?: 0.0
        val minutes = o["minutes"]?.asDoubleOrNull() ?: 0.0
        val seconds = o["seconds"]?.asDoubleOrNull() ?: 0.0
        return ((days * 86_400 + hours * 3_600 + minutes * 60 + seconds) * 1000).toLong()
    }

    /** Resolve the statistic card's entity id, accepting either the modern
     *  `entities: [id]` list or a bare `entity` string. */
    private fun parseStatisticEntity(obj: JsonObject): String? {
        (obj["entities"] as? JsonArray)?.firstOrNull()?.let { first ->
            when (first) {
                is JsonPrimitive -> if (first.isString) return first.content
                is JsonObject -> first["entity"]?.asStringOrNull()?.let { return it }
                else -> Unit
            }
        }
        return obj["entity"]?.asStringOrNull()
    }

    /** Normalise the statistic card's `period`. HA accepts a rich object
     *  (fixed_period / calendar / rolling_window) plus legacy string forms;
     *  we collapse to a coarse lookback bucket the renderer can act on. */
    private fun parseStatisticPeriod(el: JsonElement?): String {
        when (el) {
            is JsonPrimitive -> if (el.isString) return el.content.lowercase()
            is JsonObject -> {
                (el["calendar"] as? JsonObject)?.get("period")?.asStringOrNull()?.let { return it.lowercase() }
                if (el["fixed_period"] != null) return "month"
                if (el["rolling_window"] != null) return "week"
            }
            else -> Unit
        }
        return "day"
    }

    /** Logbook entity scope: prefer the modern `target.entity_id`, fall back
     *  to the deprecated `entities` list. Accepts a single id or an array. */
    private fun parseLogbookEntities(obj: JsonObject): List<String> {
        val out = LinkedHashSet<String>()
        val targetEntity = (obj["target"] as? JsonObject)?.get("entity_id")
        when (targetEntity) {
            is JsonPrimitive -> if (targetEntity.isString) out.add(targetEntity.content)
            is JsonArray -> targetEntity.forEach { it.asStringOrNull()?.let(out::add) }
            else -> Unit
        }
        when (val ents = obj["entities"]) {
            is JsonArray -> ents.forEach { item ->
                when (item) {
                    is JsonPrimitive -> if (item.isString) out.add(item.content)
                    is JsonObject -> item["entity"]?.asStringOrNull()?.let(out::add)
                    else -> Unit
                }
            }
            is JsonPrimitive -> if (ents.isString) out.add(ents.content)
            else -> Unit
        }
        return out.toList()
    }

    /** Public entry point for parsing an action object out of a raw card config
     *  key (e.g. the entity card, which renders off its raw JSON). Mirrors the
     *  internal [parseAction] used by the typed-card parse paths. */
    fun parseActionConfig(obj: JsonObject?): LovelaceAction? = parseAction(obj)

    /** Public entry point for parsing a structured `name:` (EntityNameItem
     *  object/array) out of a raw card config key, for cards (entity / sensor)
     *  that render off their raw JSON rather than a typed model. Empty list when
     *  `name:` was a plain string / absent. */
    fun parseStructuredNameConfig(el: JsonElement?): List<EntityNameItem> = parseStructuredName(el)

    private fun parseAction(obj: JsonObject?): LovelaceAction? {
        if (obj == null) return null
        val actionName = obj["action"]?.asStringOrNull()?.lowercase() ?: return null
        val confirmation = parseConfirmation(obj["confirmation"])
        return when (actionName) {
            "call-service", "perform-action" -> {
                // HA renamed `service` → `perform_action` in 2025.x; accept both.
                // A call-service with no service is a misconfiguration HA toasts
                // about; surface it as Invalid so the dispatcher does the same
                // rather than the card silently falling back to its domain default.
                val service = obj["service"]?.asStringOrNull()
                    ?: obj["perform_action"]?.asStringOrNull()
                    ?: return LovelaceAction.Invalid("Action has no service", confirmation)
                val target = parseActionTarget(obj["target"] as? JsonObject)
                // Legacy single-target convenience: `entity_id` at the action root
                // (not inside `target:`) still resolves the entity for our
                // EntityId-typed WS call path.
                val legacyEntity = obj["entity_id"]?.asStringOrNull()
                val entity = target?.entityId?.firstOrNull() ?: legacyEntity
                LovelaceAction.CallService(
                    service = service,
                    entityId = entity,
                    data = (obj["data"] as? JsonObject) ?: (obj["service_data"] as? JsonObject),
                    target = target,
                    confirmation = confirmation,
                )
            }
            "navigate" -> {
                val path = obj["navigation_path"]?.asStringOrNull()
                    ?: return LovelaceAction.Invalid("Navigate has no path", confirmation)
                LovelaceAction.Navigate(
                    path = path,
                    replace = obj["navigation_replace"]?.asBooleanOrNull() ?: false,
                    confirmation = confirmation,
                )
            }
            "url" -> {
                val url = obj["url_path"]?.asStringOrNull() ?: obj["url"]?.asStringOrNull()
                    ?: return LovelaceAction.Invalid("URL action has no url", confirmation)
                LovelaceAction.Url(url = url, confirmation = confirmation)
            }
            "more-info" -> LovelaceAction.Builtin(
                name = actionName,
                // HA's action-level `entity:` override: open more-info for a
                // different entity than the card's. Null leaves the dispatcher to
                // fall back to the card entity.
                entityId = obj["entity"]?.asStringOrNull(),
                confirmation = confirmation,
            )
            "assist" -> LovelaceAction.Builtin(
                name = actionName,
                pipelineId = obj["pipeline_id"]?.asStringOrNull(),
                startListening = obj["start_listening"]?.asBooleanOrNull() ?: false,
                confirmation = confirmation,
            )
            "toggle", "none" -> LovelaceAction.Builtin(name = actionName, confirmation = confirmation)
            // `fire-dom-event` and any custom action we can't satisfy: keep as a
            // Builtin so a `none`-like no-op is the safe default rather than
            // firing the wrong thing. The dispatcher treats unknown names as no-op.
            else -> LovelaceAction.Builtin(name = actionName, confirmation = confirmation)
        }
    }

    /**
     * Parse HA's `confirmation:` key. `true` → a generic prompt (all-null
     * fields); an object → the custom text/title/buttons plus the exempt
     * user-id list. Anything else (absent / `false`) → null (no gate).
     */
    private fun parseConfirmation(el: JsonElement?): ActionConfirmation? = when (el) {
        is JsonPrimitive -> if (el.asBooleanOrNull() == true) ActionConfirmation() else null
        is JsonObject -> ActionConfirmation(
            text = el["text"]?.asStringOrNull(),
            title = el["title"]?.asStringOrNull(),
            confirmText = el["confirm_text"]?.asStringOrNull(),
            dismissText = el["dismiss_text"]?.asStringOrNull(),
            exemptions = (el["exemptions"] as? JsonArray).orEmptyExemptionUsers(),
        )
        else -> null
    }

    /** Pull the `user` ids out of a confirmation `exemptions:` array. Each entry
     *  is `{user: <id>}`; entries without a user id are skipped. */
    private fun JsonArray?.orEmptyExemptionUsers(): List<String> {
        if (this == null) return emptyList()
        return mapNotNull { (it as? JsonObject)?.get("user")?.asStringOrNull() }
    }

    /**
     * Parse HA's service `target:` block. Each id key accepts a single string or
     * a list of strings; we normalise both to a list and keep the whole target
     * so device/area/floor/label expansion can be passed through to HA verbatim.
     * Returns null when there is no `target:` block at all.
     */
    private fun parseActionTarget(obj: JsonObject?): ActionTarget? {
        if (obj == null) return null
        fun ids(key: String): List<String> = when (val v = obj[key]) {
            is JsonPrimitive -> v.asStringOrNull()?.let { listOf(it) } ?: emptyList()
            is JsonArray -> v.mapNotNull { it.asStringOrNull() }
            else -> emptyList()
        }
        return ActionTarget(
            entityId = ids("entity_id"),
            deviceId = ids("device_id"),
            areaId = ids("area_id"),
            floorId = ids("floor_id"),
            labelId = ids("label_id"),
        )
    }

    fun parseConditions(el: JsonElement?): List<LovelaceCondition> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { cond -> (cond as? JsonObject)?.let(::parseCondition) }
    }

    /**
     * Parse one condition object. Recursive for the `and` / `or` / `not`
     * groups. Mirrors HA's `checkConditionsMet` switch: a recognised shape we
     * can evaluate maps to its typed condition; a shape we can't (`time`,
     * `location`, `view_columns`, a `template`, or a malformed rule) maps to
     * [Never] so the card fails closed. `screen` fails open and `user` is
     * modelled (the evaluator fails it open today).
     */
    private fun parseCondition(obj: JsonObject): LovelaceCondition {
        val condition = obj["condition"]?.asStringOrNull()?.lowercase()
        val entity = obj["entity"]?.asStringOrNull()
        val attribute = obj["attribute"]?.asStringOrNull()
        return when {
            // `state` / `state_not` — HA's most common gate. Accepts a single
            // `state:` value or a `state:` list; `state_not` negates the match.
            // A state condition with neither state nor state_not can't be
            // evaluated, so it fails closed. `entity:` may be omitted: the
            // evaluator then falls back to the host card's own entity (HA's
            // `context.entity_id`), so a null entity is preserved, not rejected.
            condition == "state" || condition == "state_not" ||
                (condition == null && entity != null) -> {
                val negate = condition == "state_not" || obj["state_not"] != null
                val raw = if (negate) obj["state_not"] else obj["state"]
                val states = parseConditionStates(raw)
                if (states.isEmpty()) LovelaceCondition.Never
                else LovelaceCondition.StateEquals(
                    entityId = entity,
                    states = states,
                    negate = negate,
                    attribute = attribute,
                )
            }
            condition == "numeric_state" -> {
                // A bound is either a literal number or a reference to another
                // entity's numeric state (HA's `above: sensor.x` form). We split
                // the two: a numeric string / number becomes a literal bound, an
                // entity-id-shaped string becomes an entity reference resolved at
                // evaluation time. `entity:` may be omitted (context fallback).
                val (above, aboveEntity) = parseNumericBound(obj["above"])
                val (below, belowEntity) = parseNumericBound(obj["below"])
                // A numeric_state with no usable bound at all can never be proven
                // (the reported `above: never` on a timestamp helper parsed to an
                // unbounded range that matched everything and leaked the card). With
                // nothing to compare on either side, fail closed.
                if (above == null && below == null && aboveEntity == null && belowEntity == null) {
                    LovelaceCondition.Never
                } else {
                    LovelaceCondition.NumericState(
                        entityId = entity,
                        above = above,
                        below = below,
                        aboveEntity = aboveEntity,
                        belowEntity = belowEntity,
                        attribute = attribute,
                    )
                }
            }
            // Logical groups recurse. An empty group is vacuously true for `and`
            // / `or` (matching HA), and a `not` over an empty group is true.
            // Unparseable children become [Never] (not dropped) so a group that
            // contains a condition we can't evaluate fails closed exactly the way
            // HA would, instead of silently passing on the evaluable siblings.
            condition == "and" -> LovelaceCondition.And(parseConditions(obj["conditions"]))
            condition == "or" -> LovelaceCondition.Or(parseConditions(obj["conditions"]))
            condition == "not" -> LovelaceCondition.Not(parseConditions(obj["conditions"]))
            // `user`: matched against the current logged-in user. HA's
            // validateUserCondition requires the `users:` key be present (even
            // if empty); an empty/absent list can never match, so fail closed.
            condition == "user" -> {
                val users = parseConditionStates(obj["users"])
                if (users.isEmpty()) LovelaceCondition.Never else LovelaceCondition.User(users)
            }
            // `screen`: a CSS media query. HA requires the `media_query:` key
            // (validateScreenCondition); a screen condition without one is
            // degenerate. We keep the query string and evaluate it against the
            // real window at render time.
            condition == "screen" -> {
                val mq = obj["media_query"]?.asStringOrNull()?.takeUnless { it.isBlank() }
                if (mq == null) LovelaceCondition.Never else LovelaceCondition.Screen(mq)
            }
            // `time`: after/before window and/or weekday allow-list. HA's
            // validateTimeCondition requires at least one bound or one weekday,
            // valid HH:MM[:SS] strings, valid weekday tokens, and after != before;
            // a config failing any of these is dropped to [Never].
            condition == "time" -> parseTimeCondition(obj)
            // `location`: passes when the current user's person entity sits in
            // one of the listed zones. HA requires the `locations:` key; an empty
            // list can never match, so fail closed.
            condition == "location" -> {
                val locations = parseConditionStates(obj["locations"])
                if (locations.isEmpty()) LovelaceCondition.Never else LovelaceCondition.Location(locations)
            }
            // `view_columns`: min/max against the hosting view's column count.
            // HA requires at least one of min/max (validateViewColumnsCondition).
            condition == "view_columns" -> {
                val min = obj["min"]?.asIntOrNull()
                val max = obj["max"]?.asIntOrNull()
                if (min == null && max == null) LovelaceCondition.Never
                else LovelaceCondition.ViewColumns(min = min, max = max)
            }
            // A `template` condition or a malformed rule: HA evaluates templates
            // server/client-side; we can't, so fail closed and hide the card
            // rather than leaking it.
            else -> LovelaceCondition.Never
        }
    }

    /**
     * Parse a `time` condition, mirroring HA's `validateTimeCondition` gates.
     * Drops to [LovelaceCondition.Never] when nothing is constrained, a bound is
     * not a valid HH:MM[:SS] string, a weekday token is unknown, or after equals
     * before (a zero-length window). The weekday tokens are normalised to HA's
     * lowercase three-letter form.
     */
    private fun parseTimeCondition(obj: JsonObject): LovelaceCondition {
        val afterRaw = obj["after"]?.asStringOrNull()?.takeUnless { it.isBlank() }
        val beforeRaw = obj["before"]?.asStringOrNull()?.takeUnless { it.isBlank() }
        val weekdaysRaw = parseConditionStates(obj["weekdays"]).map { it.lowercase() }

        val hasTime = afterRaw != null || beforeRaw != null
        val hasWeekdays = weekdaysRaw.isNotEmpty()
        if (!hasTime && !hasWeekdays) return LovelaceCondition.Never

        if (hasWeekdays && weekdaysRaw.any { it !in VALID_WEEKDAYS }) return LovelaceCondition.Never

        val after = afterRaw?.let { TimeOfDay.parse(it) ?: return LovelaceCondition.Never }
        val before = beforeRaw?.let { TimeOfDay.parse(it) ?: return LovelaceCondition.Never }
        // HA rejects after == before (a zero-length interval). Compare on the raw
        // strings as HA does, so "08:00" and "08:00:00" stay distinct.
        if (afterRaw != null && beforeRaw != null && afterRaw == beforeRaw) return LovelaceCondition.Never

        return LovelaceCondition.Time(after = after, before = before, weekdays = weekdaysRaw)
    }

    /**
     * Split a numeric_state bound into (literalNumber, entityReference). HA
     * accepts a number, a numeric string, or an entity id (`sensor.x`) whose
     * live numeric state supplies the bound. Returns (null, null) for an
     * absent/unparseable bound, (n, null) for a literal, (null, id) for a
     * reference. A non-numeric, non-entity string (e.g. `never`) yields
     * (null, null) so the caller can fail closed.
     */
    private fun parseNumericBound(el: JsonElement?): Pair<Double?, String?> {
        if (el == null) return null to null
        el.asDoubleOrNull()?.let { return it to null }
        val s = el.asStringOrNull() ?: return null to null
        return if (s.looksLikeEntityId()) null to s else null to null
    }

    /**
     * Pull the accepted-state list out of a `state:` / `state_not:` value. HA
     * accepts either a single string or a list of strings; numbers/booleans are
     * coerced to their string form so `state: 1` and `state: on` both match.
     */
    private fun parseConditionStates(el: JsonElement?): List<String> = when (el) {
        null -> emptyList()
        is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull() }
        is JsonPrimitive -> el.contentOrNull()?.let { listOf(it) } ?: emptyList()
        else -> emptyList()
    }

    // Per-element accessors that mirror HA's loose JSON shape: numbers
    // sometimes arrive as strings, booleans as 0/1, etc. Returning null
    // on shape-mismatch (rather than throwing) is intentional. every
    // call site has a sensible default to fall back to.

    private fun JsonElement.asStringOrNull(): String? =
        (this as? JsonPrimitive)?.contentOrNull()

    private fun JsonElement.asBooleanOrNull(): Boolean? {
        val prim = this as? JsonPrimitive ?: return null
        prim.booleanOrNull?.let { return it }
        return when (prim.content.lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }
    }

    private fun JsonElement.asIntOrNull(): Int? {
        val prim = this as? JsonPrimitive ?: return null
        return prim.intOrNull ?: prim.content.toIntOrNull()
    }

    private fun JsonElement.asDoubleOrNull(): Double? {
        val prim = this as? JsonPrimitive ?: return null
        return prim.doubleOrNull ?: prim.content.toDoubleOrNull()
    }

    private fun JsonPrimitive.contentOrNull(): String? =
        if (this is JsonNull) null else content
}
