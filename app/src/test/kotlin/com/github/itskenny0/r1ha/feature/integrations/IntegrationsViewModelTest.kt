package com.github.itskenny0.r1ha.feature.integrations

import com.github.itskenny0.r1ha.core.ha.ConfigEntry
import com.github.itskenny0.r1ha.core.ha.DeviceInfo
import com.github.itskenny0.r1ha.core.ha.EntityRegistryEntry
import com.github.itskenny0.r1ha.feature.integrations.IntegrationsViewModel.DomainCounts
import com.github.itskenny0.r1ha.feature.integrations.IntegrationsViewModel.Filter
import com.github.itskenny0.r1ha.feature.integrations.IntegrationsViewModel.StateBucket
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure-helper coverage for the Integrations surface: state bucketing +
 * labelling, the filter / search predicates, domain grouping, and the
 * registry roll-up that drives the per-domain device / entity counts.
 * All run with no repository / Android dependencies.
 */
class IntegrationsViewModelTest {

    private fun entry(
        entryId: String = "id",
        domain: String,
        title: String,
        state: String = "loaded",
    ) = ConfigEntry(
        entryId = entryId,
        domain = domain,
        title = title,
        source = "user",
        state = state,
        supportsOptions = false,
        supportsRemoveDevice = false,
        supportsUnload = true,
        prefDisableNewEntities = false,
        prefDisablePolling = false,
        reason = null,
        disabledBy = null,
    )

    private fun device(id: String, domain: String?) = DeviceInfo(
        id = id,
        name = id,
        nameByUser = null,
        manufacturer = null,
        model = null,
        areaId = null,
        disabledBy = null,
        viaDeviceId = null,
        swVersion = null,
        hwVersion = null,
        configurationUrl = null,
        identifiers = if (domain != null) listOf(domain to "x-$id") else emptyList(),
        connections = emptyList(),
    )

    private fun entity(entityId: String, platform: String?) = EntityRegistryEntry(
        entityId = entityId,
        name = null,
        originalName = null,
        deviceId = null,
        areaId = null,
        platform = platform,
        disabledBy = null,
        hiddenBy = null,
    )

    // --- stateRank -------------------------------------------------------

    @Test fun `stateRank classifies the known lifecycle tokens`() {
        assertThat(IntegrationsViewModel.stateRank("loaded")).isEqualTo(StateBucket.LOADED)
        assertThat(IntegrationsViewModel.stateRank("setup_error")).isEqualTo(StateBucket.FAILED)
        assertThat(IntegrationsViewModel.stateRank("migration_error")).isEqualTo(StateBucket.FAILED)
        assertThat(IntegrationsViewModel.stateRank("failed_unload")).isEqualTo(StateBucket.FAILED)
        assertThat(IntegrationsViewModel.stateRank("setup_retry")).isEqualTo(StateBucket.FAILED)
        assertThat(IntegrationsViewModel.stateRank("not_loaded")).isEqualTo(StateBucket.PENDING)
        assertThat(IntegrationsViewModel.stateRank("setup_in_progress")).isEqualTo(StateBucket.PENDING)
    }

    @Test fun `stateRank is case-insensitive and buckets unknown tokens as OTHER`() {
        assertThat(IntegrationsViewModel.stateRank("LOADED")).isEqualTo(StateBucket.LOADED)
        assertThat(IntegrationsViewModel.stateRank("brand_new_state")).isEqualTo(StateBucket.OTHER)
    }

    // --- stateLabel ------------------------------------------------------

    @Test fun `stateLabel spaces the known snake_case tokens`() {
        assertThat(IntegrationsViewModel.stateLabel("loaded")).isEqualTo("LOADED")
        assertThat(IntegrationsViewModel.stateLabel("setup_error")).isEqualTo("SETUP ERROR")
        assertThat(IntegrationsViewModel.stateLabel("setup_retry")).isEqualTo("SETUP RETRY")
        assertThat(IntegrationsViewModel.stateLabel("not_loaded")).isEqualTo("NOT LOADED")
        assertThat(IntegrationsViewModel.stateLabel("migration_error")).isEqualTo("MIGRATION ERROR")
        assertThat(IntegrationsViewModel.stateLabel("setup_in_progress")).isEqualTo("SETTING UP")
        assertThat(IntegrationsViewModel.stateLabel("failed_unload")).isEqualTo("UNLOAD FAILED")
    }

    @Test fun `stateLabel upcases and despaces unknown tokens`() {
        assertThat(IntegrationsViewModel.stateLabel("some_new_token")).isEqualTo("SOME NEW TOKEN")
        assertThat(IntegrationsViewModel.stateLabel("LOADED")).isEqualTo("LOADED")
    }

    // --- matchesFilter ---------------------------------------------------

    @Test fun `matchesFilter ALL passes everything`() {
        for (s in listOf("loaded", "setup_error", "not_loaded", "weird")) {
            assertThat(IntegrationsViewModel.matchesFilter(entry(domain = "d", title = "t", state = s), Filter.ALL))
                .isTrue()
        }
    }

    @Test fun `matchesFilter LOADED is loaded-only and case-insensitive`() {
        assertThat(IntegrationsViewModel.matchesFilter(entry(domain = "d", title = "t", state = "LOADED"), Filter.LOADED))
            .isTrue()
        assertThat(IntegrationsViewModel.matchesFilter(entry(domain = "d", title = "t", state = "setup_retry"), Filter.LOADED))
            .isFalse()
    }

    @Test fun `matchesFilter FAILED only matches failed bucket`() {
        assertThat(IntegrationsViewModel.matchesFilter(entry(domain = "d", title = "t", state = "setup_error"), Filter.FAILED))
            .isTrue()
        // setup_retry is an error state (HA's ERROR_STATES), so it belongs to FAILED;
        // not_loaded is the genuinely-non-failed negative example here.
        assertThat(IntegrationsViewModel.matchesFilter(entry(domain = "d", title = "t", state = "setup_retry"), Filter.FAILED))
            .isTrue()
        assertThat(IntegrationsViewModel.matchesFilter(entry(domain = "d", title = "t", state = "not_loaded"), Filter.FAILED))
            .isFalse()
    }

    // --- matchesQuery ----------------------------------------------------

    @Test fun `matchesQuery blank or whitespace matches everything`() {
        val e = entry(domain = "mqtt", title = "Mosquitto")
        assertThat(IntegrationsViewModel.matchesQuery(e, "")).isTrue()
        assertThat(IntegrationsViewModel.matchesQuery(e, "   ")).isTrue()
    }

    @Test fun `matchesQuery matches domain or title case-insensitively`() {
        val e = entry(domain = "mqtt", title = "Mosquitto Broker")
        assertThat(IntegrationsViewModel.matchesQuery(e, "MQ")).isTrue()
        assertThat(IntegrationsViewModel.matchesQuery(e, "broker")).isTrue()
        assertThat(IntegrationsViewModel.matchesQuery(e, "  Mosquitto ")).isTrue()
        assertThat(IntegrationsViewModel.matchesQuery(e, "zigbee")).isFalse()
    }

    // --- groupByDomain ---------------------------------------------------

    @Test fun `groupByDomain sorts sections by domain and entries by title`() {
        val entries = listOf(
            entry(entryId = "1", domain = "zwave", title = "Stick B"),
            entry(entryId = "2", domain = "hue", title = "Living Room"),
            entry(entryId = "3", domain = "hue", title = "Bedroom"),
            entry(entryId = "4", domain = "zwave", title = "stick A"),
        )
        val sections = IntegrationsViewModel.groupByDomain(entries)
        assertThat(sections.map { it.first }).containsExactly("hue", "zwave").inOrder()
        assertThat(sections[0].second.map { it.title })
            .containsExactly("Bedroom", "Living Room").inOrder()
        // Title sort is case-insensitive: "stick A" precedes "Stick B".
        assertThat(sections[1].second.map { it.title })
            .containsExactly("stick A", "Stick B").inOrder()
    }

    @Test fun `groupByDomain on empty input yields no sections`() {
        assertThat(IntegrationsViewModel.groupByDomain(emptyList())).isEmpty()
    }

    // --- countsByDomain --------------------------------------------------

    @Test fun `countsByDomain rolls devices and entities up per domain`() {
        val devices = listOf(
            device("d1", "hue"),
            device("d2", "hue"),
            device("d3", "zwave"),
        )
        val entities = listOf(
            entity("light.a", "hue"),
            entity("light.b", "hue"),
            entity("light.c", "hue"),
            entity("sensor.x", "zwave"),
        )
        val counts = IntegrationsViewModel.countsByDomain(devices, entities)
        assertThat(counts["hue"]).isEqualTo(DomainCounts(devices = 2, entities = 3))
        assertThat(counts["zwave"]).isEqualTo(DomainCounts(devices = 1, entities = 1))
    }

    @Test fun `countsByDomain is case-insensitive on domain keys`() {
        val counts = IntegrationsViewModel.countsByDomain(
            devices = listOf(device("d1", "Hue")),
            entities = listOf(entity("light.a", "HUE")),
        )
        assertThat(counts["hue"]).isEqualTo(DomainCounts(devices = 1, entities = 1))
    }

    @Test fun `countsByDomain drops rows that cannot be attributed`() {
        val counts = IntegrationsViewModel.countsByDomain(
            devices = listOf(device("d1", null)),
            entities = listOf(entity("light.a", null), entity("light.b", "  ")),
        )
        assertThat(counts).isEmpty()
    }

    @Test fun `countsByDomain covers a domain present in only one registry`() {
        val counts = IntegrationsViewModel.countsByDomain(
            devices = listOf(device("d1", "shelly")),
            entities = emptyList(),
        )
        assertThat(counts["shelly"]).isEqualTo(DomainCounts(devices = 1, entities = 0))
    }
}
