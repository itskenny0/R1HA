package com.github.itskenny0.r1ha.core.ambient

import com.github.itskenny0.r1ha.core.prefs.AmbientScope
import com.github.itskenny0.r1ha.core.prefs.AmbientSettings
import com.github.itskenny0.r1ha.nav.Routes
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AmbientLogicTest {

    @Test fun `night window handles same-day and wrap-around ranges`() {
        // Wrap-around 22..6: 23:00 is night, 12:00 is day, 06:00 is day (end exclusive).
        assertThat(AmbientLogic.isNightWindow(23, 22, 6)).isTrue()
        assertThat(AmbientLogic.isNightWindow(12, 22, 6)).isFalse()
        assertThat(AmbientLogic.isNightWindow(6, 22, 6)).isFalse()
        // Same-day 9..17: 12 is "night-window", 8 is not.
        assertThat(AmbientLogic.isNightWindow(12, 9, 17)).isTrue()
        assertThat(AmbientLogic.isNightWindow(8, 9, 17)).isFalse()
        // Empty window (start == end) is never night.
        assertThat(AmbientLogic.isNightWindow(3, 5, 5)).isFalse()
    }

    @Test fun `brightness returns restore sentinel when not idle`() {
        assertThat(AmbientLogic.brightness(idle = false, night = false, s = AmbientSettings()))
            .isEqualTo(-1f)
    }

    @Test fun `brightness uses day or night percent and clamps to the floor`() {
        val s = AmbientSettings(dayBrightnessPct = 40, nightBrightnessPct = 6)
        assertThat(AmbientLogic.brightness(idle = true, night = false, s = s)).isWithin(0.001f).of(0.40f)
        assertThat(AmbientLogic.brightness(idle = true, night = true, s = s)).isWithin(0.001f).of(0.06f)
        // A zero night percent is floored to AMBIENT_MIN_BRIGHTNESS_PCT (1%), never 0.
        val floored = AmbientLogic.brightness(idle = true, night = true, s = s.copy(nightBrightnessPct = 0))
        assertThat(floored).isWithin(0.001f).of(0.01f)
    }

    @Test fun `routeInScope respects the chosen scope`() {
        assertThat(AmbientLogic.routeInScope(Routes.SETTINGS, AmbientScope.ANYWHERE)).isTrue()
        assertThat(AmbientLogic.routeInScope(Routes.SETTINGS, AmbientScope.TODAY_ONLY)).isFalse()
        assertThat(AmbientLogic.routeInScope(Routes.DASHBOARD, AmbientScope.TODAY_ONLY)).isTrue()
        assertThat(AmbientLogic.routeInScope(Routes.CARD_STACK, AmbientScope.TODAY_PLUS_CARDSTACK)).isTrue()
        assertThat(AmbientLogic.routeInScope(Routes.SETTINGS, AmbientScope.TODAY_PLUS_CARDSTACK)).isFalse()
    }

    @Test fun `shouldSuppress blocks onboarding, IME, out-of-scope, and camera when opted`() {
        // Onboarding always suppressed regardless of scope.
        assertThat(
            AmbientLogic.shouldSuppress(Routes.ONBOARDING, AmbientScope.ANYWHERE, imeVisible = false, suppressOverCamera = false),
        ).isTrue()
        // IME visible suppresses.
        assertThat(
            AmbientLogic.shouldSuppress(Routes.CARD_STACK, AmbientScope.ANYWHERE, imeVisible = true, suppressOverCamera = false),
        ).isTrue()
        // Camera suppressed only when the toggle is on.
        assertThat(
            AmbientLogic.shouldSuppress(Routes.CAMERAS, AmbientScope.ANYWHERE, imeVisible = false, suppressOverCamera = true),
        ).isTrue()
        assertThat(
            AmbientLogic.shouldSuppress(Routes.CAMERAS, AmbientScope.ANYWHERE, imeVisible = false, suppressOverCamera = false),
        ).isFalse()
        // A normal in-scope screen is not suppressed.
        assertThat(
            AmbientLogic.shouldSuppress(Routes.CARD_STACK, AmbientScope.ANYWHERE, imeVisible = false, suppressOverCamera = false),
        ).isFalse()
    }

    @Test fun `power severity crosses amber and red thresholds`() {
        assertThat(AmbientLogic.powerSeverity(100.0, amberW = 500, redW = 2000))
            .isEqualTo(AmbientLogic.PowerSeverity.NORMAL)
        assertThat(AmbientLogic.powerSeverity(900.0, amberW = 500, redW = 2000))
            .isEqualTo(AmbientLogic.PowerSeverity.AMBER)
        assertThat(AmbientLogic.powerSeverity(2500.0, amberW = 500, redW = 2000))
            .isEqualTo(AmbientLogic.PowerSeverity.RED)
        assertThat(AmbientLogic.powerSeverity(null, amberW = 500, redW = 2000))
            .isEqualTo(AmbientLogic.PowerSeverity.NORMAL)
    }
}
