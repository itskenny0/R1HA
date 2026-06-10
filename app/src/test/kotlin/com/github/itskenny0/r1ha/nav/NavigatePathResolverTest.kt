package com.github.itskenny0.r1ha.nav

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Coverage for the `navigate` action path -> native target mapping. */
class NavigatePathResolverTest {

    @Test fun `empty path opens the lovelace webview`() {
        assertThat(resolveNavigateTarget("", "home")).isEqualTo(NavigateTarget.Lovelace)
        assertThat(resolveNavigateTarget("/", "home")).isEqualTo(NavigateTarget.Lovelace)
    }

    @Test fun `lovelace view path resolves to the current dashboard`() {
        val t = resolveNavigateTarget("/lovelace/lights", "home") as NavigateTarget.DashboardView
        assertThat(t.dashboard).isEqualTo("home")
        assertThat(t.view).isEqualTo("lights")
    }

    @Test fun `bare view path resolves to the current dashboard`() {
        val t = resolveNavigateTarget("lights", "home") as NavigateTarget.DashboardView
        assertThat(t.dashboard).isEqualTo("home")
        assertThat(t.view).isEqualTo("lights")
    }

    @Test fun `dashboard slash view path resolves to that dashboard`() {
        val t = resolveNavigateTarget("/my-dash/lights", "home") as NavigateTarget.DashboardView
        assertThat(t.dashboard).isEqualTo("my-dash")
        assertThat(t.view).isEqualTo("lights")
    }

    @Test fun `system panel paths fall back to the lovelace webview`() {
        assertThat(resolveNavigateTarget("/config/areas", "home")).isEqualTo(NavigateTarget.Lovelace)
        assertThat(resolveNavigateTarget("/history", "home")).isEqualTo(NavigateTarget.Lovelace)
        assertThat(resolveNavigateTarget("/developer-tools/state", "home")).isEqualTo(NavigateTarget.Lovelace)
    }

    @Test fun `subview deeper paths take the last segment as the view`() {
        val t = resolveNavigateTarget("/my-dash/area/kitchen", "home") as NavigateTarget.DashboardView
        assertThat(t.dashboard).isEqualTo("my-dash")
        assertThat(t.view).isEqualTo("kitchen")
    }
}
