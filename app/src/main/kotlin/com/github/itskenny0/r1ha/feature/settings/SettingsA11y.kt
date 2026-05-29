package com.github.itskenny0.r1ha.feature.settings

/**
 * Pure, Compose-free label builders for the Settings surface. Kept out of
 * [SettingsScreen] so the spoken wording is unit-testable without a Compose
 * runtime and lives in one auditable place.
 *
 * Settings rows pack several visible cues a screen reader can't perceive: a
 * toggle's on/off is communicated by the switch graphic alone, a static
 * info row splits its label and value into two read-as-separate fragments,
 * and a category row's "you changed N things here" badge is a bare number
 * glyph. These helpers fold each into one spoken phrase that states the value
 * in words.
 */
object SettingsA11y {

    /**
     * Merged description for a [SwitchRow]. Reads the label, the optional
     * subtitle, then the on/off state in words ("on" / "off") so the toggle
     * state is conveyed without seeing the switch, e.g.
     * "Haptic feedback, Vibrate on tap, on".
     */
    fun switchRowDescription(label: String, subtitle: String?, checked: Boolean): String {
        val parts = mutableListOf(label.trim())
        subtitle?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
        parts += if (checked) "on" else "off"
        return parts.joinToString(", ")
    }

    /** Spoken state-change verb for a switch, used as the toggle's action hint. */
    fun switchToggleHint(checked: Boolean): String =
        if (checked) "Double tap to turn off" else "Double tap to turn on"

    /**
     * Merged description for a static [InfoRow]. The label and value render as
     * two columns; spoken as one phrase they read naturally, e.g.
     * "HA version, 2025.5.1". A blank value collapses to the label alone.
     */
    fun infoRowDescription(label: String, value: String): String {
        val v = value.trim()
        return if (v.isEmpty()) label.trim() else "${label.trim()}, $v"
    }

    /**
     * Merged description for a category / sub-category row that may carry a
     * "modified settings" badge. The badge is a bare count glyph; spell it out
     * so the reader hears how many overrides live behind the row, e.g.
     * "Open Appearance, 3 changed settings". Zero badge omits the clause.
     */
    fun categoryRowDescription(title: String, badge: Int): String {
        val base = "Open ${title.trim()}"
        if (badge <= 0) return base
        val noun = if (badge == 1) "changed setting" else "changed settings"
        return "$base, $badge $noun"
    }

    /** Spoken description for the modified-count badge in isolation. */
    fun modifiedBadgeDescription(count: Int): String {
        val noun = if (count == 1) "changed setting" else "changed settings"
        return "$count $noun"
    }
}
