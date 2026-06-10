package com.github.itskenny0.r1ha.feature.moreinfo

import com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoDomainControls.AlarmPhase
import com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoDomainControls.EntityAlert
import com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoDomainControls.ScriptFieldType
import com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoDomainControls.SunEvent
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Unit tests for [MoreInfoDomainControls]: the Batch O2 domain gating
 *  (counter / timer / update / alarm / vacuum / sun / group / script / chrome). */
class MoreInfoDomainControlsTest {

    // ── counter ──────────────────────────────────────────────────────────────

    @Test fun `counter disables increment at maximum and decrement at minimum`() {
        val atMax = MoreInfoDomainControls.counterButtons(value = 10, minimum = 0, maximum = 10)
        assertThat(atMax.canIncrement).isFalse()
        assertThat(atMax.canDecrement).isTrue()
        assertThat(atMax.canReset).isTrue()

        val atMin = MoreInfoDomainControls.counterButtons(value = 0, minimum = 0, maximum = 10)
        assertThat(atMin.canDecrement).isFalse()
        assertThat(atMin.canIncrement).isTrue()
    }

    @Test fun `counter without bounds keeps both steppers enabled`() {
        val b = MoreInfoDomainControls.counterButtons(value = 5, minimum = null, maximum = null)
        assertThat(b.canIncrement).isTrue()
        assertThat(b.canDecrement).isTrue()
    }

    // ── timer ────────────────────────────────────────────────────────────────

    @Test fun `timer idle shows only start`() {
        val b = MoreInfoDomainControls.timerButtons("idle")
        assertThat(b.showStart).isTrue()
        assertThat(b.showPause).isFalse()
        assertThat(b.showCancel).isFalse()
        assertThat(b.showFinish).isFalse()
    }

    @Test fun `timer active shows pause cancel finish but not start`() {
        val b = MoreInfoDomainControls.timerButtons("active")
        assertThat(b.showStart).isFalse()
        assertThat(b.showPause).isTrue()
        assertThat(b.showCancel).isTrue()
        assertThat(b.showFinish).isTrue()
    }

    @Test fun `timer paused shows start cancel finish but not pause`() {
        val b = MoreInfoDomainControls.timerButtons("paused")
        assertThat(b.showStart).isTrue()
        assertThat(b.showPause).isFalse()
        assertThat(b.showCancel).isTrue()
        assertThat(b.showFinish).isTrue()
    }

    @Test fun `timer remaining counts down from finishes_at when active`() {
        val now = 1_000L
        val finishes = 1_090L
        val rem = MoreInfoDomainControls.timerRemainingSeconds(
            state = "active",
            nowEpochSeconds = now,
            finishesAtEpochSeconds = finishes,
            remaining = "0:05:00",
            duration = "0:10:00",
        )
        assertThat(rem).isEqualTo(90L)
    }

    @Test fun `timer remaining never goes negative`() {
        val rem = MoreInfoDomainControls.timerRemainingSeconds(
            state = "active",
            nowEpochSeconds = 2_000L,
            finishesAtEpochSeconds = 1_000L,
            remaining = null,
            duration = null,
        )
        assertThat(rem).isEqualTo(0L)
    }

    @Test fun `timer remaining uses remaining string when paused`() {
        val rem = MoreInfoDomainControls.timerRemainingSeconds(
            state = "paused",
            nowEpochSeconds = 0L,
            finishesAtEpochSeconds = null,
            remaining = "0:02:30",
            duration = "0:10:00",
        )
        assertThat(rem).isEqualTo(150L)
    }

    @Test fun `timer remaining uses duration when idle`() {
        val rem = MoreInfoDomainControls.timerRemainingSeconds(
            state = "idle",
            nowEpochSeconds = 0L,
            finishesAtEpochSeconds = null,
            remaining = null,
            duration = "1:00:00",
        )
        assertThat(rem).isEqualTo(3600L)
    }

    @Test fun `parseHmsSeconds handles HH MM SS and MM SS and rejects junk`() {
        assertThat(MoreInfoDomainControls.parseHmsSeconds("1:02:03")).isEqualTo(3723L)
        assertThat(MoreInfoDomainControls.parseHmsSeconds("05:00")).isEqualTo(300L)
        assertThat(MoreInfoDomainControls.parseHmsSeconds("0:10:00.500000")).isEqualTo(600L)
        assertThat(MoreInfoDomainControls.parseHmsSeconds("nope")).isNull()
        assertThat(MoreInfoDomainControls.parseHmsSeconds(null)).isNull()
    }

    @Test fun `formatRemaining drops hours when zero`() {
        assertThat(MoreInfoDomainControls.formatRemaining(90L)).isEqualTo("1:30")
        assertThat(MoreInfoDomainControls.formatRemaining(3723L)).isEqualTo("1:02:03")
        assertThat(MoreInfoDomainControls.formatRemaining(-5L)).isEqualTo("0:00")
    }

    // ── update ───────────────────────────────────────────────────────────────

    @Test fun `update install shown only when available, supported, and not in progress`() {
        val full = MoreInfoDomainControls.UpdateFeature.INSTALL or
            MoreInfoDomainControls.UpdateFeature.RELEASE_NOTES or
            MoreInfoDomainControls.UpdateFeature.SPECIFIC_VERSION or
            MoreInfoDomainControls.UpdateFeature.BACKUP
        val avail = MoreInfoDomainControls.updateControls("on", full, inProgress = false)
        assertThat(avail.updateAvailable).isTrue()
        assertThat(avail.canInstall).isTrue()
        assertThat(avail.canSkip).isTrue()
        assertThat(avail.supportsReleaseNotes).isTrue()
        assertThat(avail.supportsSpecificVersion).isTrue()
        assertThat(avail.supportsBackup).isTrue()

        val installing = MoreInfoDomainControls.updateControls("on", full, inProgress = true)
        assertThat(installing.canInstall).isFalse()
        assertThat(installing.canSkip).isFalse()
        assertThat(installing.inProgress).isTrue()

        val upToDate = MoreInfoDomainControls.updateControls("off", full, inProgress = false)
        assertThat(upToDate.updateAvailable).isFalse()
        assertThat(upToDate.canInstall).isFalse()
    }

    @Test fun `update without install feature cannot install`() {
        val c = MoreInfoDomainControls.updateControls(
            "on",
            MoreInfoDomainControls.UpdateFeature.RELEASE_NOTES,
            inProgress = false,
        )
        assertThat(c.canInstall).isFalse()
        assertThat(c.supportsReleaseNotes).isTrue()
    }

    // ── alarm ────────────────────────────────────────────────────────────────

    @Test fun `alarm phase classification`() {
        assertThat(MoreInfoDomainControls.alarmPhase("arming")).isEqualTo(AlarmPhase.ARMING)
        assertThat(MoreInfoDomainControls.alarmPhase("pending")).isEqualTo(AlarmPhase.PENDING)
        assertThat(MoreInfoDomainControls.alarmPhase("triggered")).isEqualTo(AlarmPhase.TRIGGERED)
        assertThat(MoreInfoDomainControls.alarmPhase("armed_away")).isEqualTo(AlarmPhase.ACTIVE)
        assertThat(MoreInfoDomainControls.alarmPhase("disarmed")).isEqualTo(AlarmPhase.ACTIVE)
    }

    @Test fun `alarm countdown denominator picks the matching delay per phase`() {
        assertThat(
            MoreInfoDomainControls.alarmPhaseTotalSeconds(AlarmPhase.ARMING, armingTime = 30, delayTime = 10),
        ).isEqualTo(30L)
        assertThat(
            MoreInfoDomainControls.alarmPhaseTotalSeconds(AlarmPhase.PENDING, armingTime = 30, delayTime = 10),
        ).isEqualTo(10L)
        assertThat(
            MoreInfoDomainControls.alarmPhaseTotalSeconds(AlarmPhase.TRIGGERED, armingTime = 30, delayTime = 10),
        ).isNull()
    }

    @Test fun `alarm default code skips keypad`() {
        assertThat(MoreInfoDomainControls.alarmDefaultCode("4321")).isEqualTo("4321")
        assertThat(MoreInfoDomainControls.alarmDefaultCode("")).isNull()
        assertThat(MoreInfoDomainControls.alarmDefaultCode(null)).isNull()
    }

    // ── vacuum ───────────────────────────────────────────────────────────────

    @Test fun `vacuum battery prefers dedicated field then attribute`() {
        assertThat(MoreInfoDomainControls.vacuumBatteryPercent(72, null)).isEqualTo(72)
        assertThat(MoreInfoDomainControls.vacuumBatteryPercent(null, 55.0)).isEqualTo(55)
        assertThat(MoreInfoDomainControls.vacuumBatteryPercent(null, null)).isNull()
        assertThat(MoreInfoDomainControls.vacuumBatteryPercent(150, null)).isEqualTo(100)
    }

    @Test fun `vacuum status label prefers status attribute`() {
        assertThat(MoreInfoDomainControls.vacuumStatusLabel("cleaning_room", "docked"))
            .isEqualTo("CLEANING ROOM")
        assertThat(MoreInfoDomainControls.vacuumStatusLabel(null, "returning")).isEqualTo("RETURNING")
        assertThat(MoreInfoDomainControls.vacuumStatusLabel(null, null)).isNull()
    }

    // ── sun ──────────────────────────────────────────────────────────────────

    @Test fun `sun lists the sooner event first`() {
        assertThat(MoreInfoDomainControls.sunEventOrder(nextRisingEpoch = 100, nextSettingEpoch = 200))
            .containsExactly(SunEvent.RISING, SunEvent.SETTING).inOrder()
        assertThat(MoreInfoDomainControls.sunEventOrder(nextRisingEpoch = 300, nextSettingEpoch = 200))
            .containsExactly(SunEvent.SETTING, SunEvent.RISING).inOrder()
    }

    @Test fun `sun drops a missing event row`() {
        assertThat(MoreInfoDomainControls.sunEventOrder(nextRisingEpoch = 100, nextSettingEpoch = null))
            .containsExactly(SunEvent.RISING)
        assertThat(MoreInfoDomainControls.sunEventOrder(nextRisingEpoch = null, nextSettingEpoch = null))
            .isEmpty()
    }

    // ── group ────────────────────────────────────────────────────────────────

    @Test fun `group member toggleable for switchy domains only`() {
        assertThat(MoreInfoDomainControls.memberIsToggleable("light.kitchen")).isTrue()
        assertThat(MoreInfoDomainControls.memberIsToggleable("switch.fan")).isTrue()
        assertThat(MoreInfoDomainControls.memberIsToggleable("sensor.temp")).isFalse()
        assertThat(MoreInfoDomainControls.memberIsToggleable("lock.front")).isFalse()
    }

    // ── script ───────────────────────────────────────────────────────────────

    @Test fun `script field classification`() {
        assertThat(MoreInfoDomainControls.classifyScriptField("number", hasOptions = false))
            .isEqualTo(ScriptFieldType.NUMBER)
        assertThat(MoreInfoDomainControls.classifyScriptField("boolean", hasOptions = false))
            .isEqualTo(ScriptFieldType.BOOLEAN)
        assertThat(MoreInfoDomainControls.classifyScriptField("select", hasOptions = false))
            .isEqualTo(ScriptFieldType.SELECT)
        assertThat(MoreInfoDomainControls.classifyScriptField("text", hasOptions = false))
            .isEqualTo(ScriptFieldType.TEXT)
        // Inline options force a select even without a select selector.
        assertThat(MoreInfoDomainControls.classifyScriptField(null, hasOptions = true))
            .isEqualTo(ScriptFieldType.SELECT)
        // Unknown selector falls back to text.
        assertThat(MoreInfoDomainControls.classifyScriptField("entity", hasOptions = false))
            .isEqualTo(ScriptFieldType.TEXT)
    }

    @Test fun `script running detection`() {
        assertThat(MoreInfoDomainControls.scriptIsRunning("on")).isTrue()
        assertThat(MoreInfoDomainControls.scriptIsRunning("off")).isFalse()
        assertThat(MoreInfoDomainControls.scriptIsRunning(null)).isFalse()
    }

    // ── chrome alerts ─────────────────────────────────────────────────────────

    @Test fun `entity alert classification`() {
        assertThat(MoreInfoDomainControls.entityAlert("unavailable", restored = false))
            .isEqualTo(EntityAlert.UNAVAILABLE)
        assertThat(MoreInfoDomainControls.entityAlert("on", restored = true))
            .isEqualTo(EntityAlert.RESTORED)
        assertThat(MoreInfoDomainControls.entityAlert("unknown", restored = false))
            .isEqualTo(EntityAlert.UNKNOWN)
        assertThat(MoreInfoDomainControls.entityAlert("playing", restored = false))
            .isEqualTo(EntityAlert.NONE)
        // unavailable takes priority over restored.
        assertThat(MoreInfoDomainControls.entityAlert("unavailable", restored = true))
            .isEqualTo(EntityAlert.UNAVAILABLE)
    }
}
