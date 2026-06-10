package com.github.itskenny0.r1ha.feature.moreinfo

import com.github.itskenny0.r1ha.core.util.optionLabel

/**
 * Pure decision logic for the Batch O2 domain controls on the more-info sheet
 * (counter / timer / update / vacuum / lawn_mower / alarm / siren / remote / sun /
 * person / group / script). Kept free of Compose so the gating that mirrors HA's
 * `more-info-*` controls can be unit-tested directly.
 *
 * Sibling to [MoreInfoControls] (the O1 light / lock / fan / climate helpers); split
 * into its own object only to keep each file focused.
 */
object MoreInfoDomainControls {

    // ── counter ────────────────────────────────────────────────────────────────

    /**
     * Which counter buttons HA's more-info-counter enables. Increment disables at
     * the configured maximum, decrement at the minimum; reset is always enabled
     * (unless the whole control is unavailable, handled by the cross-cutting gate).
     * [value] / [minimum] / [maximum] are the parsed `state` / attributes; a null
     * bound means "no limit" so the matching button stays enabled.
     */
    data class CounterButtons(
        val canIncrement: Boolean,
        val canDecrement: Boolean,
        val canReset: Boolean,
    )

    fun counterButtons(value: Long?, minimum: Long?, maximum: Long?): CounterButtons =
        CounterButtons(
            canIncrement = value == null || maximum == null || value < maximum,
            canDecrement = value == null || minimum == null || value > minimum,
            canReset = true,
        )

    // ── timer ──────────────────────────────────────────────────────────────────

    /**
     * The timer transport buttons HA's more-info-timer shows for a given [state]
     * ("idle" / "active" / "paused"). Start shows when idle or paused; pause shows
     * when active; cancel + finish show when active or paused. Mirrors the exact
     * `state ===` branches in the HA control.
     */
    data class TimerButtons(
        val showStart: Boolean,
        val showPause: Boolean,
        val showCancel: Boolean,
        val showFinish: Boolean,
    )

    fun timerButtons(state: String?): TimerButtons {
        val s = state?.lowercase()
        val active = s == "active"
        val paused = s == "paused"
        val idle = s == "idle"
        return TimerButtons(
            showStart = idle || paused,
            showPause = active,
            showCancel = active || paused,
            showFinish = active || paused,
        )
    }

    /**
     * Seconds remaining on a timer, derived the way HA's timer card does:
     *  - active: count down from `finishes_at` against [nowEpochSeconds],
     *  - paused: the static `remaining` duration ("HH:MM:SS"),
     *  - idle: the full `duration` ("HH:MM:SS") if configured, else null.
     *
     * Returns null when nothing is parseable so the caller renders no countdown.
     * [finishesAtEpochSeconds] is the parsed `finishes_at` instant; the duration
     * strings are HA's "H:MM:SS" / "HH:MM:SS" attribute format.
     */
    fun timerRemainingSeconds(
        state: String?,
        nowEpochSeconds: Long,
        finishesAtEpochSeconds: Long?,
        remaining: String?,
        duration: String?,
    ): Long? = when (state?.lowercase()) {
        "active" -> finishesAtEpochSeconds?.let { (it - nowEpochSeconds).coerceAtLeast(0L) }
            ?: parseHmsSeconds(remaining)
        "paused" -> parseHmsSeconds(remaining)
        else -> parseHmsSeconds(duration)
    }

    /** Parse an HA "H:MM:SS" / "HH:MM:SS" duration string to whole seconds. Null on
     *  malformed input. Fractional seconds (the integration occasionally appends a
     *  ".NNNNNN") are truncated. */
    fun parseHmsSeconds(hms: String?): Long? {
        val s = hms?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = s.substringBefore('.').split(':')
        if (parts.size !in 2..3) return null
        val nums = parts.map { it.toLongOrNull() ?: return null }
        return when (nums.size) {
            3 -> nums[0] * 3600 + nums[1] * 60 + nums[2]
            else -> nums[0] * 60 + nums[1]
        }
    }

    /** Format whole [seconds] as "H:MM:SS" (hours dropped when zero -> "M:SS"). Used
     *  for the live countdown readout; never negative. */
    fun formatRemaining(seconds: Long): String {
        val s = seconds.coerceAtLeast(0L)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) {
            "%d:%02d:%02d".format(java.util.Locale.US, h, m, sec)
        } else {
            "%d:%02d".format(java.util.Locale.US, m, sec)
        }
    }

    // ── update ─────────────────────────────────────────────────────────────────

    /** HA `UpdateEntityFeature` bits. */
    object UpdateFeature {
        const val INSTALL = 1
        const val SPECIFIC_VERSION = 2
        const val PROGRESS = 4
        const val BACKUP = 8
        const val RELEASE_NOTES = 16
    }

    /**
     * The update controls HA's more-info-update offers, derived from
     * `supported_features` + state. An update entity is "on" when an update is
     * available (state == "on"). Install shows when the entity supports INSTALL and
     * an update is pending and no install is already in progress; skip shows in the
     * same situation; release-notes fetch is available when RELEASE_NOTES is set.
     */
    data class UpdateControls(
        val updateAvailable: Boolean,
        val canInstall: Boolean,
        val canSkip: Boolean,
        val supportsSpecificVersion: Boolean,
        val supportsReleaseNotes: Boolean,
        val supportsBackup: Boolean,
        val inProgress: Boolean,
    )

    fun updateControls(
        rawState: String?,
        supportedFeatures: Int,
        inProgress: Boolean,
    ): UpdateControls {
        val available = rawState.equals("on", ignoreCase = true)
        fun has(bit: Int) = (supportedFeatures and bit) != 0
        return UpdateControls(
            updateAvailable = available,
            canInstall = available && has(UpdateFeature.INSTALL) && !inProgress,
            canSkip = available && !inProgress,
            supportsSpecificVersion = has(UpdateFeature.SPECIFIC_VERSION),
            supportsReleaseNotes = has(UpdateFeature.RELEASE_NOTES),
            supportsBackup = has(UpdateFeature.BACKUP),
            inProgress = inProgress,
        )
    }

    // ── alarm_control_panel ──────────────────────────────────────────────────────

    /**
     * Phase of an alarm panel, used to drive the special "arming" / "pending" /
     * "triggered" UI HA shows on top of the keypad. ACTIVE covers a normal armed /
     * disarmed state where only the keypad matters.
     */
    enum class AlarmPhase { ARMING, PENDING, TRIGGERED, ACTIVE }

    fun alarmPhase(rawState: String?): AlarmPhase = when (rawState?.lowercase()) {
        "arming" -> AlarmPhase.ARMING
        "pending" -> AlarmPhase.PENDING
        "triggered" -> AlarmPhase.TRIGGERED
        else -> AlarmPhase.ACTIVE
    }

    /**
     * Total seconds the arming / pending countdown runs, read from the panel's
     * `*_arm_required` style delay attributes. HA exposes `arming_time`,
     * `delay_time` and `trigger_time`; we read whichever matches the phase so a
     * countdown ring has a denominator. Null when the integration omits the timing
     * (then the caller shows the phase label without a ring).
     */
    fun alarmPhaseTotalSeconds(phase: AlarmPhase, armingTime: Long?, delayTime: Long?): Long? =
        when (phase) {
            AlarmPhase.ARMING -> armingTime
            AlarmPhase.PENDING -> delayTime
            else -> null
        }

    /** Whether the disarm / arm keypad can skip the code prompt: HA fills in the
     *  registry `default_code` when present, mirroring the lock-open path. Returns
     *  the code to send when skippable, else null. */
    fun alarmDefaultCode(defaultCode: String?): String? =
        defaultCode?.takeIf { it.isNotBlank() }

    // ── vacuum / lawn_mower battery ──────────────────────────────────────────────

    /**
     * The battery percentage to surface for a vacuum, preferring the dedicated
     * `battery_level` field, then a `battery_level` attribute. Null when neither is
     * present (older vacuums route battery through a separate sensor entity we can't
     * resolve here). Clamped to 0..100.
     */
    fun vacuumBatteryPercent(batteryLevel: Int?, batteryAttr: Double?): Int? {
        val v = batteryLevel ?: batteryAttr?.toInt() ?: return null
        return v.coerceIn(0, 100)
    }

    /** Status word HA shows for a vacuum, preferring the `status` attribute (richer
     *  per-integration wording) over the bare state. Humanised for display. Null
     *  when neither is meaningful. */
    fun vacuumStatusLabel(status: String?, rawState: String?): String? {
        val s = status?.takeIf { it.isNotBlank() && it != "null" }
            ?: rawState?.takeIf { it.isNotBlank() && it != "null" }
            ?: return null
        return optionLabel(s)
    }

    // ── sun ──────────────────────────────────────────────────────────────────────

    /**
     * The ordered rising / setting rows HA's more-info-sun renders: whichever event
     * comes first chronologically is listed first. [nextRisingEpoch] /
     * [nextSettingEpoch] are the parsed `next_rising` / `next_setting` instants.
     * Returns the labels in display order; an absent timestamp drops that row.
     */
    enum class SunEvent { RISING, SETTING }

    fun sunEventOrder(nextRisingEpoch: Long?, nextSettingEpoch: Long?): List<SunEvent> {
        if (nextRisingEpoch == null && nextSettingEpoch == null) return emptyList()
        if (nextRisingEpoch == null) return listOf(SunEvent.SETTING)
        if (nextSettingEpoch == null) return listOf(SunEvent.RISING)
        // HA lists the sooner event first; ties keep rising first.
        return if (nextRisingEpoch <= nextSettingEpoch) {
            listOf(SunEvent.RISING, SunEvent.SETTING)
        } else {
            listOf(SunEvent.SETTING, SunEvent.RISING)
        }
    }

    // ── group ────────────────────────────────────────────────────────────────────

    /**
     * Whether a group-member entity should get a compact inline toggle on the sheet.
     * Mirrors the domains HA's group card treats as toggleable (anything whose state
     * is on/off and that exposes a turn_on/turn_off-style service). The sheet routes
     * everything else to a plain "tap to open" row.
     */
    private val TOGGLEABLE_MEMBER_DOMAINS = setOf(
        "light", "switch", "fan", "input_boolean", "automation", "siren",
        "humidifier", "remote", "media_player",
    )

    fun memberIsToggleable(memberEntityId: String): Boolean =
        memberEntityId.substringBefore('.') in TOGGLEABLE_MEMBER_DOMAINS

    // ── script fields ────────────────────────────────────────────────────────────

    /** Input kind for a script field, derived from the field's `selector` / type. */
    enum class ScriptFieldType { TEXT, NUMBER, BOOLEAN, SELECT }

    /**
     * One field from a script's `fields:` definition, normalised to a typed input.
     * [key] is the field name passed in the service data; [options] is non-empty only
     * for [ScriptFieldType.SELECT].
     */
    data class ScriptField(
        val key: String,
        val name: String,
        val description: String?,
        val type: ScriptFieldType,
        val required: Boolean,
        val options: List<String> = emptyList(),
        val defaultText: String? = null,
    )

    /**
     * Classify a script field given its selector key (the single key under
     * `selector:`, e.g. "number" / "boolean" / "select" / "text") and any inline
     * `options`. Unknown / absent selectors fall back to a free-text input, which is
     * the safe default (HA renders a text box for an undescribed field too).
     */
    fun classifyScriptField(selectorKey: String?, hasOptions: Boolean): ScriptFieldType = when {
        hasOptions || selectorKey == "select" -> ScriptFieldType.SELECT
        selectorKey == "number" -> ScriptFieldType.NUMBER
        selectorKey == "boolean" -> ScriptFieldType.BOOLEAN
        else -> ScriptFieldType.TEXT
    }

    /** Whether a script is currently running (state == "on"), so the sheet shows a
     *  cancel button instead of (or beside) run. */
    fun scriptIsRunning(rawState: String?): Boolean = rawState.equals("on", ignoreCase = true)

    // ── sheet-chrome alerts ──────────────────────────────────────────────────────

    /** The top-of-sheet alert to show for an entity's lifecycle state, mirroring HA's
     *  more-info "this entity is not available" / "restored" banners. NONE when the
     *  entity is live. */
    enum class EntityAlert { NONE, UNAVAILABLE, UNKNOWN, RESTORED }

    fun entityAlert(rawState: String?, restored: Boolean): EntityAlert = when {
        rawState.equals("unavailable", ignoreCase = true) -> EntityAlert.UNAVAILABLE
        restored -> EntityAlert.RESTORED
        rawState.equals("unknown", ignoreCase = true) -> EntityAlert.UNKNOWN
        else -> EntityAlert.NONE
    }
}
