package com.github.itskenny0.r1ha.feature.template

/**
 * Pure, dependency-free spoken-label builders for the Templates surface. Kept
 * out of Compose so TalkBack content descriptions can be unit-tested directly
 * with no UI harness. Every builder returns a single merged sentence so a
 * screen reader announces one coherent phrase per element rather than reading
 * a row of disconnected tokens.
 */
object TemplateA11y {

    /** Spoken label for the Jinja editor field. */
    fun editorLabel(): String = "Jinja2 template editor. Type a template, then render."

    /**
     * Spoken label for an example "try this" chip. Tapping inserts the snippet
     * and renders it, so the announcement says so.
     */
    fun exampleChipLabel(name: String): String {
        val clean = name.trim().ifEmpty { "example" }
        return "Insert and render example $clean"
    }

    /** Spoken label for the AUTO render-on-type toggle. */
    fun autoToggleLabel(enabled: Boolean): String =
        if (enabled) "Auto render on. Renders as you type. Tap to turn off."
        else "Auto render off. Tap to render as you type."

    /** Spoken label for the LIVE subscription toggle. */
    fun liveToggleLabel(enabled: Boolean): String =
        if (enabled) "Live render on. Updates on every state change. Tap to turn off."
        else "Live render off. Tap to subscribe to state changes."

    /**
     * Spoken label for the result panel. Merges the panel heading (rendered /
     * an error kind) and the body so the panel announces as one phrase. The
     * body is included verbatim so a render error is read aloud.
     */
    fun resultLabel(heading: String, body: String): String {
        val h = heading.trim().ifEmpty { "Result" }
        val b = body.trim()
        return if (b.isEmpty()) h else "$h. $b"
    }

    /** Spoken label for a recalled recent-template row. */
    fun recentRowLabel(template: String): String {
        val clean = template.trim()
        return if (clean.isEmpty()) {
            "Recent template. Tap to load and render."
        } else {
            "Recent template $clean. Tap to load and render."
        }
    }
}
