package com.github.itskenny0.r1ha.core.prefs

import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the pinPanel / unpinPanel persistence round-trip: panels written via
 * [SettingsRepository.pinPanel] survive through the DataStore codec and come back
 * correctly from [SettingsRepository.settings].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PinnedPanelPersistenceTest {

    private fun newRepo(): SettingsRepository =
        SettingsRepository.forTesting(
            ApplicationProvider.getApplicationContext(),
            datastoreName = "test_panels_${System.nanoTime()}",
        )

    @Test fun `pinPanel adds an entry to navPanel pinnedPanels`() = runTest {
        val repo = newRepo()
        repo.pinPanel(urlPath = "hacs", title = "HACS", icon = "mdi:home-assistant-community-store")
        repo.settings.test {
            val s = awaitItem()
            val panels = s.navPanel.pinnedPanels
            assertThat(panels).hasSize(1)
            assertThat(panels[0].urlPath).isEqualTo("hacs")
            assertThat(panels[0].title).isEqualTo("HACS")
            assertThat(panels[0].icon).isEqualTo("mdi:home-assistant-community-store")
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test fun `pinPanel is a no-op for duplicate urlPath but refreshes title and icon`() = runTest {
        val repo = newRepo()
        repo.pinPanel(urlPath = "esphome", title = "ESPHome", icon = null)
        repo.pinPanel(urlPath = "esphome", title = "ESPHome (updated)", icon = "mdi:chip")
        repo.settings.test {
            val s = awaitItem()
            val panels = s.navPanel.pinnedPanels
            // Only one entry; position unchanged; title + icon refreshed.
            assertThat(panels).hasSize(1)
            assertThat(panels[0].title).isEqualTo("ESPHome (updated)")
            assertThat(panels[0].icon).isEqualTo("mdi:chip")
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test fun `unpinPanel removes the entry`() = runTest {
        val repo = newRepo()
        repo.pinPanel(urlPath = "hacs", title = "HACS")
        repo.pinPanel(urlPath = "esphome", title = "ESPHome")
        repo.unpinPanel(urlPath = "hacs")
        repo.settings.test {
            val s = awaitItem()
            val panels = s.navPanel.pinnedPanels
            assertThat(panels).hasSize(1)
            assertThat(panels[0].urlPath).isEqualTo("esphome")
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test fun `unpinPanel on unknown urlPath is a no-op`() = runTest {
        val repo = newRepo()
        repo.pinPanel(urlPath = "hacs", title = "HACS")
        repo.unpinPanel(urlPath = "zigbee2mqtt")
        repo.settings.test {
            val s = awaitItem()
            assertThat(s.navPanel.pinnedPanels).hasSize(1)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test fun `pinning multiple panels preserves insertion order`() = runTest {
        val repo = newRepo()
        repo.pinPanel(urlPath = "hacs", title = "HACS")
        repo.pinPanel(urlPath = "esphome", title = "ESPHome")
        repo.pinPanel(urlPath = "zigbee2mqtt", title = "Zigbee2MQTT")
        repo.settings.test {
            val s = awaitItem()
            assertThat(s.navPanel.pinnedPanels.map { it.urlPath })
                .containsExactly("hacs", "esphome", "zigbee2mqtt")
                .inOrder()
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test fun `pinnedPanels survive a backup round-trip`() {
        // AppBackup carries navPanel which carries pinnedPanels. Verify the
        // encode -> decode cycle preserves the list without any migration logic.
        val source = AppSettings(
            navPanel = NavPanelSettings(
                pinnedPanels = listOf(
                    PinnedPanel(urlPath = "hacs", title = "HACS"),
                    PinnedPanel(urlPath = "esphome", title = "ESPHome", icon = "mdi:chip"),
                ),
            ),
        )
        val raw = encodeBackup(source.toBackup(createdAt = "2026-06-10T00:00:00Z"))
        val restored = decodeBackup(raw).applyOnto(AppSettings())

        assertThat(restored.navPanel.pinnedPanels).hasSize(2)
        val hacs = restored.navPanel.pinnedPanels[0]
        assertThat(hacs.urlPath).isEqualTo("hacs")
        assertThat(hacs.title).isEqualTo("HACS")
        assertThat(hacs.icon).isNull()
        val esphome = restored.navPanel.pinnedPanels[1]
        assertThat(esphome.urlPath).isEqualTo("esphome")
        assertThat(esphome.icon).isEqualTo("mdi:chip")
    }

    @Test fun `NavPanelSettings without pinnedPanels decodes as empty list`() {
        // Simulate a pre-feature backup: decode a NavPanelSettings JSON that has no
        // "pinnedPanels" field. The ignoreUnknownKeys + default-value codec must
        // fall back to an empty list rather than throwing.
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        val oldJson = """{"sidePanelEnabled":true,"phoneNavStyle":"SLIDEOUT","hiddenNavItems":[],""" +
            """"pinnedSurfaces":[],"pinnedDashboards":[]}"""
        val decoded = json.decodeFromString(NavPanelSettings.serializer(), oldJson)
        assertThat(decoded.pinnedPanels).isEmpty()
    }
}
