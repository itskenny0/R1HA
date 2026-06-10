package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AlarmPanelLogicTest {

    private fun panel(
        rawState: String = "disarmed",
        supportedFeatures: Int = 0,
        codeFormat: String? = null,
        codeArmRequired: Boolean = true,
    ): EntityState = EntityState(
        id = EntityId("alarm_control_panel.home"),
        friendlyName = "Home",
        area = null,
        isOn = false,
        percent = null,
        raw = null,
        lastChanged = Instant.EPOCH,
        isAvailable = true,
        rawState = rawState,
        supportedFeatures = supportedFeatures,
        alarmCodeFormat = codeFormat,
        alarmCodeArmRequired = codeArmRequired,
    )

    // ── code_format regression (the real bug) ──────────────────────────────

    @Test fun `code_format number resolves to digit keypad`() {
        assertEquals(AlarmCodeMode.NUMBER, alarmCodeMode("number", codeArmRequired = true, arming = false))
        assertEquals(AlarmCodeMode.NUMBER, alarmCodeMode("number", codeArmRequired = true, arming = true))
    }

    @Test fun `code_format text resolves to free-text field`() {
        assertEquals(AlarmCodeMode.TEXT, alarmCodeMode("text", codeArmRequired = true, arming = false))
    }

    @Test fun `absent code_format means no prompt`() {
        assertEquals(AlarmCodeMode.NONE, alarmCodeMode(null, codeArmRequired = true, arming = false))
        assertEquals(AlarmCodeMode.NONE, alarmCodeMode("", codeArmRequired = true, arming = false))
        assertEquals(AlarmCodeMode.NONE, alarmCodeMode("  ", codeArmRequired = true, arming = true))
    }

    @Test fun `arming skips the prompt when code_arm_required is false`() {
        // Disarm still prompts, arming does not.
        assertEquals(AlarmCodeMode.NONE, alarmCodeMode("number", codeArmRequired = false, arming = true))
        assertEquals(AlarmCodeMode.NUMBER, alarmCodeMode("number", codeArmRequired = false, arming = false))
    }

    @Test fun `a digit code with number format validates the OK button`() {
        // The old regex path compiled "number" as a Regex, so OK never enabled
        // for a real PIN. The enum path validates any digit string.
        assertTrue(alarmCodeValid(AlarmCodeMode.NUMBER, "1234"))
        assertFalse(alarmCodeValid(AlarmCodeMode.NUMBER, ""))
        assertFalse(alarmCodeValid(AlarmCodeMode.NUMBER, "12ab"))
    }

    @Test fun `text mode accepts any non-blank entry`() {
        assertTrue(alarmCodeValid(AlarmCodeMode.TEXT, "letmein"))
        assertFalse(alarmCodeValid(AlarmCodeMode.TEXT, "   "))
    }

    // ── states default + supported_features filtering ───────────────────────

    @Test fun `default arm modes are home and away`() {
        // supported_features == 0 => the panel forgives and advertises everything,
        // so the [arm_home, arm_away] default survives intact.
        assertEquals(listOf("arm_home", "arm_away"), alarmArmModes(emptyList(), panel()))
    }

    @Test fun `supported_features trims unsupported default modes`() {
        // Only ARM_AWAY advertised: HOME default chip is dropped.
        val s = panel(supportedFeatures = EntityState.AlarmFeature.ARM_AWAY)
        assertEquals(listOf("arm_away"), alarmArmModes(emptyList(), s))
    }

    @Test fun `config states are honoured and filtered by features`() {
        val s = panel(
            supportedFeatures = EntityState.AlarmFeature.ARM_HOME or EntityState.AlarmFeature.ARM_NIGHT,
        )
        // Config asks for home + away + night; away isn't advertised, so it's cut.
        val out = alarmArmModes(listOf("arm_home", "arm_away", "arm_night"), s)
        assertEquals(listOf("arm_home", "arm_night"), out)
    }

    @Test fun `disarm token is never an arm-mode chip`() {
        val out = alarmArmModes(listOf("disarm", "arm_home"), panel())
        assertEquals(listOf("arm_home"), out)
    }

    @Test fun `null state falls back to the requested set`() {
        assertEquals(listOf("arm_home", "arm_away"), alarmArmModes(emptyList(), null))
        assertEquals(listOf("arm_night"), alarmArmModes(listOf("arm_night"), null))
    }
}
