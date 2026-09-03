package com.github.itskenny0.r1ha.core.ambient

import com.github.itskenny0.r1ha.core.prefs.AMBIENT_MIN_BRIGHTNESS_PCT
import com.github.itskenny0.r1ha.core.prefs.AmbientScope
import com.github.itskenny0.r1ha.core.prefs.AmbientSettings
import com.github.itskenny0.r1ha.nav.Routes

/**
 * Pure decision core for ambient mode. No Android / Compose types so the idle
 * state machine, brightness ramp, and screensaver guardrails are unit-testable
 * without a device. The composable layer ([AmbientOverlay]) is a thin shell over
 * these functions.
 */
object AmbientLogic {

    enum class PowerSeverity { NORMAL, AMBER, RED }

    /** True when [hour] (0..23) falls inside the night window. Wrap-around aware
     *  (22..6) and end-exclusive, matching the auto-theme switch. An empty window
     *  (start == end) is never night. */
    fun isNightWindow(hour: Int, nightStartHour: Int, nightEndHour: Int): Boolean = when {
        nightStartHour == nightEndHour -> false
        nightStartHour < nightEndHour -> hour in nightStartHour until nightEndHour
        else -> hour >= nightStartHour || hour < nightEndHour
    }

    /** Window brightness for the current state. Returns the RESTORE sentinel
     *  (-1f, BRIGHTNESS_OVERRIDE_NONE) when not idle so the system/user
     *  brightness applies; otherwise the configured day/night percent as a
     *  0f..1f fraction, floored so the screen never goes fully black. */
    fun brightness(idle: Boolean, night: Boolean, s: AmbientSettings): Float {
        if (!idle) return -1f
        val pct = if (night) s.nightBrightnessPct else s.dayBrightnessPct
        val clamped = pct.coerceIn(AMBIENT_MIN_BRIGHTNESS_PCT, 100)
        return clamped / 100f
    }

    /** Whether [route] is eligible for the idle face under [scope]. Card-stack
     *  focus deep-links count as the card stack. */
    fun routeInScope(route: String?, scope: AmbientScope): Boolean = when (scope) {
        AmbientScope.ANYWHERE -> true
        AmbientScope.TODAY_ONLY -> route == Routes.DASHBOARD
        AmbientScope.TODAY_PLUS_CARDSTACK ->
            route == Routes.DASHBOARD || route == Routes.CARD_STACK || route == Routes.CARD_STACK_FOCUS
    }

    /** Routes where the idle face must never appear regardless of scope: the
     *  setup flows (no app yet) and a null route (pre-navigation boot). */
    fun isBlockedRoute(route: String?): Boolean =
        route == null || route == Routes.ONBOARDING || route == Routes.LONG_LIVED_TOKEN

    /** Camera / live-video surfaces, matched by route prefix. */
    fun isCameraRoute(route: String?): Boolean =
        route != null && route.startsWith(Routes.CAMERAS)

    /** Single screensaver guardrail predicate combining blocked routes, scope,
     *  active text entry, and the optional camera exception. */
    fun shouldSuppress(
        route: String?,
        scope: AmbientScope,
        imeVisible: Boolean,
        suppressOverCamera: Boolean,
    ): Boolean =
        isBlockedRoute(route) ||
            !routeInScope(route, scope) ||
            imeVisible ||
            (suppressOverCamera && isCameraRoute(route))

    /** Map a power-draw reading to a severity for the idle-face DRAW chip. */
    fun powerSeverity(watts: Double?, amberW: Int, redW: Int): PowerSeverity = when {
        watts == null -> PowerSeverity.NORMAL
        watts >= redW -> PowerSeverity.RED
        watts >= amberW -> PowerSeverity.AMBER
        else -> PowerSeverity.NORMAL
    }

    /** Minimum glance-refresh period, seconds. */
    const val MIN_REFRESH_SEC = 30

    /** Refresh cadence for the idle-face summary, derived from the dashboard
     *  interval but never faster than [MIN_REFRESH_SEC]; 0 / negative means
     *  "default" (60 s). */
    fun refreshPeriodSec(dashboardIntervalSec: Int): Int =
        (if (dashboardIntervalSec > 0) dashboardIntervalSec else 60).coerceAtLeast(MIN_REFRESH_SEC)

    /** Maps a raw Settings.System.SCREEN_BRIGHTNESS value (0..255) to the
     *  0f..1f window-brightness scale; null when unavailable or out of range. */
    fun systemBrightnessFraction(raw: Int?): Float? =
        raw?.takeIf { it in 0..255 }?.let { it / 255f }
}
