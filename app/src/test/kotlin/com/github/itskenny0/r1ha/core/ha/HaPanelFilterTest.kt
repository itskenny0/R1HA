package com.github.itskenny0.r1ha.core.ha

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HaPanelFilterTest {

    private fun panel(
        urlPath: String,
        componentName: String,
        title: String? = null,
        icon: String? = null,
    ) = HaPanel(urlPath = urlPath, title = title, icon = icon, componentName = componentName)

    // ── isNativelyRendered: url_path-based exclusions ──

    @Test fun `lovelace url_path is native`() {
        assertThat(panel("lovelace", "lovelace").isNativelyRendered()).isTrue()
    }

    @Test fun `config url_path is native`() {
        assertThat(panel("config", "config").isNativelyRendered()).isTrue()
    }

    @Test fun `energy url_path is native`() {
        assertThat(panel("energy", "custom").isNativelyRendered()).isTrue()
    }

    @Test fun `history url_path is native`() {
        assertThat(panel("history", "custom").isNativelyRendered()).isTrue()
    }

    @Test fun `logbook url_path is native`() {
        assertThat(panel("logbook", "custom").isNativelyRendered()).isTrue()
    }

    @Test fun `map url_path is native`() {
        assertThat(panel("map", "custom").isNativelyRendered()).isTrue()
    }

    @Test fun `todo url_path is native`() {
        assertThat(panel("todo", "custom").isNativelyRendered()).isTrue()
    }

    @Test fun `media-browser url_path is native`() {
        assertThat(panel("media-browser", "custom").isNativelyRendered()).isTrue()
    }

    @Test fun `developer-tools url_path is native`() {
        assertThat(panel("developer-tools", "custom").isNativelyRendered()).isTrue()
    }

    @Test fun `profile url_path is native`() {
        assertThat(panel("profile", "custom").isNativelyRendered()).isTrue()
    }

    // ── isNativelyRendered: component_name-based exclusions ──

    @Test fun `lovelace component_name is native even with non-standard url_path`() {
        // Custom dashboards register as "lovelace" with their own url_path.
        assertThat(panel("lovelace-smart-home", "lovelace").isNativelyRendered()).isTrue()
        assertThat(panel("my-dashboard", "lovelace").isNativelyRendered()).isTrue()
    }

    @Test fun `config component_name is native even with non-standard url_path`() {
        assertThat(panel("system-settings", "config").isNativelyRendered()).isTrue()
    }

    // ── isNativelyRendered: external panels are NOT native ──

    @Test fun `hacs panel is not native`() {
        assertThat(panel("hacs", "custom").isNativelyRendered()).isFalse()
    }

    @Test fun `esphome panel is not native`() {
        assertThat(panel("esphome", "custom").isNativelyRendered()).isFalse()
    }

    @Test fun `zigbee2mqtt iframe panel is not native`() {
        assertThat(panel("zigbee2mqtt", "iframe").isNativelyRendered()).isFalse()
    }

    @Test fun `nodered panel is not native`() {
        assertThat(panel("hassio", "custom").isNativelyRendered()).isFalse()
    }

    @Test fun `unknown custom component is not native`() {
        assertThat(panel("my-custom-panel", "custom").isNativelyRendered()).isFalse()
    }

    // ── Filtering a list of panels keeps only external ones ──

    @Test fun `filtering a mixed list retains only external panels`() {
        val all = listOf(
            panel("lovelace", "lovelace"),
            panel("config", "config"),
            panel("energy", "custom"),
            panel("hacs", "custom"),
            panel("esphome", "custom"),
            panel("my-dashboard", "lovelace"),
            panel("zigbee2mqtt", "iframe"),
        )
        val external = all.filterNot { it.isNativelyRendered() }
        assertThat(external.map { it.urlPath }).containsExactly("hacs", "esphome", "zigbee2mqtt")
    }

    @Test fun `filtering an all-native list returns empty`() {
        val native = listOf(
            panel("lovelace", "lovelace"),
            panel("config", "config"),
            panel("history", "history"),
        )
        assertThat(native.filterNot { it.isNativelyRendered() }).isEmpty()
    }
}
