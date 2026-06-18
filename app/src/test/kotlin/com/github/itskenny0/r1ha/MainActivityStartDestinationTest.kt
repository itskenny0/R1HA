package com.github.itskenny0.r1ha

import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.ServerConfig
import com.github.itskenny0.r1ha.nav.Routes
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-logic coverage for [resolveStartDestination] — the locked start-destination
 * decision. No Activity / Compose, so it runs as a plain JVM unit test.
 */
class MainActivityStartDestinationTest {

    @Test fun onboarding_when_no_server() {
        assertThat(resolveStartDestination(AppSettings())).isEqualTo(Routes.ONBOARDING)
    }

    @Test fun cardStack_when_server_but_default_behavior() {
        val s = AppSettings(server = ServerConfig(url = "http://h"))
        assertThat(resolveStartDestination(s)).isEqualTo(Routes.CARD_STACK)
    }

    @Test fun dashboard_when_startOnDashboard_and_today_visible() {
        val s = AppSettings(server = ServerConfig(url = "http://h"))
            .let { it.copy(behavior = it.behavior.copy(startOnDashboard = true)) }
        // The legacy (R1HAL) build drops the dashboard, so a persisted "start on
        // dashboard" still lands on the card stack there; the full builds honour it.
        val expected = if (BuildConfig.IS_LEGACY) Routes.CARD_STACK else Routes.DASHBOARD
        assertThat(resolveStartDestination(s)).isEqualTo(expected)
    }

    @Test fun cardStack_when_startOnDashboard_but_today_hidden() {
        val s = AppSettings(server = ServerConfig(url = "http://h"))
            .let {
                it.copy(
                    behavior = it.behavior.copy(startOnDashboard = true),
                    navPanel = it.navPanel.copy(hiddenNavItems = setOf("today")),
                )
            }
        assertThat(resolveStartDestination(s)).isEqualTo(Routes.CARD_STACK)
    }
}
