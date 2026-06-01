package com.github.itskenny0.r1ha.feature.settings

/**
 * Internal Settings information-architecture tree. The whole Settings surface is
 * a single composable ([SettingsScreen]) that drives an Android-Settings-style
 * drill-in experience from its OWN back-stack, kept entirely inside this feature
 * package rather than the app's top-level nav graph. Each [SettingsNode] is one
 * screen in that stack: tapping a category row pushes a node, the on-screen back
 * chevron and hardware Back pop exactly one node, and popping past [ROOT] exits
 * Settings via the host's `onBack`.
 *
 * Depth is encoded by the parent links in [SettingsNode.parent]; the tree goes
 * up to four levels where the content justifies it (e.g.
 * ROOT → Appearance → Cards → Value bar & pip). Routing is data here, rendering
 * lives in [SettingsScreen]; keeping the tree shape as a plain enum lets it be
 * unit-tested without Compose.
 */
enum class SettingsNode(
    /** Parent node, or null for [ROOT]. Drives the back-stack pop chain and the
     *  per-level top-bar title trail. */
    val parent: SettingsNode?,
    /** Title shown in the per-level top bar. */
    val title: String,
) {
    ROOT(null, "Settings"),

    // ── Connection & server ───────────────────────────────────────────────
    CONNECTION(ROOT, "Connection & server"),
    CONNECTION_ACCOUNT(CONNECTION, "Account & sign-in"),
    CONNECTION_BACKUP(CONNECTION, "Backup & restore"),
    CONNECTION_SECURITY(CONNECTION, "Security"),

    // ── Appearance ────────────────────────────────────────────────────────
    APPEARANCE(ROOT, "Appearance"),
    APPEARANCE_THEME(APPEARANCE, "Theme"),
    APPEARANCE_NAVPANEL(APPEARANCE, "Navigation panel"),
    APPEARANCE_CARDS(APPEARANCE, "Cards"),
    APPEARANCE_CARDS_VALUEBAR(APPEARANCE_CARDS, "Value bar & pip"),
    APPEARANCE_CARDS_CHROME(APPEARANCE_CARDS, "Chrome buttons"),

    // ── Input ─────────────────────────────────────────────────────────────
    INPUT(ROOT, "Input"),
    INPUT_WHEEL(INPUT, "Scroll wheel"),

    // ── Behaviour ─────────────────────────────────────────────────────────
    BEHAVIOUR(ROOT, "Behaviour"),
    BEHAVIOUR_QUICKTILES(BEHAVIOUR, "Quick Settings tiles"),

    // ── Today / Dashboard ─────────────────────────────────────────────────
    DASHBOARD(ROOT, "Today & Dashboard"),
    DASHBOARD_CARDS(DASHBOARD, "Visible cards"),
    DASHBOARD_THRESHOLDS(DASHBOARD, "Thresholds & intervals"),
    DASHBOARD_ORDER(DASHBOARD, "Tile order"),

    // ── Integrations ──────────────────────────────────────────────────────
    INTEGRATIONS(ROOT, "Integrations"),
    INTEGRATIONS_REFRESH(INTEGRATIONS, "Auto-refresh intervals"),
    INTEGRATIONS_CAMERAS(INTEGRATIONS, "Cameras"),
    INTEGRATIONS_DEFAULTS(INTEGRATIONS, "Defaults & limits"),

    // ── Advanced / Developer ──────────────────────────────────────────────
    ADVANCED(ROOT, "Advanced"),

    // ── Browse ────────────────────────────────────────────────────────────
    BROWSE(ROOT, "Browse"),
    BROWSE_TODAY(BROWSE, "Today"),
    BROWSE_TALK(BROWSE, "Talk & fire"),
    BROWSE_STATUS(BROWSE, "Status views"),
    BROWSE_POWER(BROWSE, "Power tools");

    /** Depth from [ROOT]: ROOT is 0, its children 1, and so on. Lets a test
     *  assert the tree reaches the intended four-level depth and that no node
     *  is orphaned above [ROOT]. */
    val depth: Int
        get() {
            var d = 0
            var n: SettingsNode? = parent
            while (n != null) {
                d++
                n = n.parent
            }
            return d
        }
}

/**
 * Pure back-stack model for the internal Settings drill-in. Holds the path from
 * [SettingsNode.ROOT] to the current node. [push] descends, [pop] ascends one
 * level. Popping at [SettingsNode.ROOT] is reported as [PopResult.Exit] so the
 * caller can defer to the host screen's back handler (leave Settings entirely).
 *
 * Kept immutable + value-returning so the back-stack logic is unit-testable with
 * no Compose runtime. The Compose layer wraps the current value in a
 * `mutableStateOf` and replaces it on each navigation.
 */
data class SettingsBackStack(
    val path: List<SettingsNode> = listOf(SettingsNode.ROOT),
) {
    init {
        require(path.isNotEmpty()) { "back-stack path must never be empty" }
        require(path.first() == SettingsNode.ROOT) { "back-stack must be rooted at ROOT" }
    }

    /** The node currently shown. */
    val current: SettingsNode get() = path.last()

    /** True when sitting at the top-level Settings list. */
    val atRoot: Boolean get() = path.size == 1

    /** Descend into [node]. If [node] is already the current node the stack is
     *  returned unchanged (defends against a double-tap pushing a duplicate). */
    fun push(node: SettingsNode): SettingsBackStack {
        if (node == current) return this
        return copy(path = path + node)
    }

    /** Ascend one level. Returns the popped stack plus a flag telling the caller
     *  whether the pop happened in-tree ([PopResult.Popped]) or whether the
     *  caller should now exit Settings ([PopResult.Exit] when already at ROOT). */
    fun pop(): PopResult =
        if (path.size <= 1) {
            PopResult.Exit
        } else {
            PopResult.Popped(copy(path = path.dropLast(1)))
        }
}

/** Outcome of [SettingsBackStack.pop]. */
sealed interface PopResult {
    /** Popped one level inside Settings; [stack] is the new state. */
    data class Popped(val stack: SettingsBackStack) : PopResult

    /** Already at ROOT: the host should leave the Settings surface. */
    data object Exit : PopResult
}

/**
 * Map the uppercase section name a deep-link sender stages on
 * [com.github.itskenny0.r1ha.core.util.SettingsFocusBus] (the same strings
 * [sectionNameForCategory] returns) to the internal node the user should land
 * on. Returns the full back-stack path so the focus jump restores a sane
 * parent trail (so a single Back lands on the category, not all the way out).
 * Unknown / unmapped names fall back to [SettingsNode.ROOT].
 */
fun focusPathForSection(sectionName: String): List<SettingsNode> = when (sectionName) {
    "SERVER" -> listOf(SettingsNode.ROOT, SettingsNode.CONNECTION)
    "SCROLL WHEEL" -> listOf(SettingsNode.ROOT, SettingsNode.INPUT, SettingsNode.INPUT_WHEEL)
    "CARD UI" -> listOf(SettingsNode.ROOT, SettingsNode.APPEARANCE, SettingsNode.APPEARANCE_CARDS)
    "BEHAVIOUR" -> listOf(SettingsNode.ROOT, SettingsNode.BEHAVIOUR)
    "APPEARANCE" -> listOf(SettingsNode.ROOT, SettingsNode.APPEARANCE, SettingsNode.APPEARANCE_THEME)
    "INTEGRATIONS" -> listOf(SettingsNode.ROOT, SettingsNode.INTEGRATIONS)
    "DASHBOARD" -> listOf(SettingsNode.ROOT, SettingsNode.DASHBOARD)
    else -> listOf(SettingsNode.ROOT)
}

/** Title-case a ThemeId-style enum name: `PRAGMATIC_HYBRID` -> `Pragmatic hybrid`.
 *  Shared by every Settings row that surfaces a theme value so the formatting
 *  lives in one tested place. */
fun prettyEnumName(raw: String): String =
    raw.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }

/** Render a night-theme window as Android-style secondary text: `22:00 -> 6:00`. */
fun nightWindowSummary(startHour: Int, endHour: Int): String =
    "${startHour}:00 → ${endHour}:00"
