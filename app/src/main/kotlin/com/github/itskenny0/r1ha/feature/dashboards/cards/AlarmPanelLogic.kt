package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.EntityState

/**
 * Pure decision logic for the alarm-panel card, split out from the Compose
 * renderer so the mode-filtering and code-prompt rules are unit-tested without
 * a UI harness. None of this touches Compose or IO.
 */

/** How an alarm panel collects the user's code before firing an arm/disarm. */
enum class AlarmCodeMode {
    /** No code prompt: fire the service immediately. */
    NONE,

    /** A digits-only PIN keypad (HA's `code_format: number`). */
    NUMBER,

    /** A free-text password field (HA's `code_format: text`). */
    TEXT,
}

/**
 * Resolve the code-entry mode for an alarm action.
 *
 * Mirrors HA's alarm-panel handling of `code_format`. Modern HA reports the
 * literal enum `"number"` or `"text"`; an absent / blank format means the panel
 * takes no code. The old renderer compiled `code_format` as a regex and gated
 * the keypad's OK button on `containsMatchIn`, so a literal "number" never
 * matched digits and OK could never enable. This replaces that with the
 * enum-driven branch HA actually uses.
 *
 * [arming] is true for an arm action, false for disarm. HA always requires the
 * code for disarm when a format is set; for arming it additionally honours the
 * panel's `code_arm_required` attribute (when false, arming skips the prompt).
 */
fun alarmCodeMode(
    codeFormat: String?,
    codeArmRequired: Boolean,
    arming: Boolean,
): AlarmCodeMode {
    val fmt = codeFormat?.trim()?.lowercase()
    if (fmt.isNullOrBlank()) return AlarmCodeMode.NONE
    // Arming may be exempt from the code prompt even when a format is set.
    if (arming && !codeArmRequired) return AlarmCodeMode.NONE
    return when (fmt) {
        "text" -> AlarmCodeMode.TEXT
        // "number" and any other non-empty format default to the digit keypad;
        // HA only ships number/text, and a digit pad is the safe fallback.
        else -> AlarmCodeMode.NUMBER
    }
}

/**
 * Whether an entered code is acceptable to enable the confirm button, given the
 * resolved [mode]. A digit keypad requires at least one digit; a text field
 * requires a non-empty entry. [AlarmCodeMode.NONE] never reaches a prompt, so it
 * always validates.
 */
fun alarmCodeValid(mode: AlarmCodeMode, entered: String): Boolean = when (mode) {
    AlarmCodeMode.NONE -> true
    AlarmCodeMode.NUMBER -> entered.isNotEmpty() && entered.all { it.isDigit() }
    AlarmCodeMode.TEXT -> entered.isNotBlank()
}

/**
 * The arm-mode chips an alarm-panel card should surface, in display order.
 *
 * When the config lists `states:` we honour it verbatim (still filtered by what
 * the entity advertises). When it's absent HA defaults to `[arm_home, arm_away]`
 * intersected with the panel's `supported_features`, so a panel that can't arm
 * home never shows a dead HOME chip. "disarm" is always offered and is not part
 * of this arm-mode list.
 *
 * Returned values are the bare mode tokens HA uses: arm_home, arm_away,
 * arm_night, arm_vacation, arm_custom_bypass.
 */
fun alarmArmModes(configStates: List<String>, state: EntityState?): List<String> {
    // supportedAlarmModes returns "disarmed" + the armed_* the panel advertises;
    // map those onto the arm_* tokens the card config speaks.
    val advertised = state?.let { supportedAlarmModes(it) }.orEmpty()
        .mapNotNull { mode ->
            when (mode) {
                "armed_home" -> "arm_home"
                "armed_away" -> "arm_away"
                "armed_night" -> "arm_night"
                "armed_vacation" -> "arm_vacation"
                "armed_custom_bypass" -> "arm_custom_bypass"
                else -> null
            }
        }
    val requested = if (configStates.isEmpty()) {
        listOf("arm_home", "arm_away")
    } else {
        configStates.map { it.lowercase() }.filter { it != "disarm" && it != "disarmed" }
    }
    // When the panel advertises nothing (supported_features not populated yet),
    // don't hide every chip: fall back to the requested set so the card is still
    // usable. Once features arrive, the intersection trims unsupported modes.
    if (advertised.isEmpty()) return requested
    return requested.filter { advertised.contains(it) }
}
