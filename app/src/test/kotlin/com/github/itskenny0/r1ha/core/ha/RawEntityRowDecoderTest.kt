package com.github.itskenny0.r1ha.core.ha

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Test

/**
 * Coverage for the domain-agnostic raw-state decoders that back the dashboards
 * renderer's [HaRepository.observeRawRows] path. The point of these is that they
 * keep entities of EVERY domain, including ones the typed [Domain] enum drops, so
 * a sun.sun / custom-domain card can show its last-known value instead of a blank
 * box.
 *
 * Pure Kotlin, no Android dependencies, no network: fixture in, model out.
 */
class RawEntityRowDecoderTest {

    @Test
    fun `decodeRawRow keeps an unsupported-domain entity`() {
        val row = buildJsonObject {
            put("entity_id", "sun.sun")
            put("state", "above_horizon")
            put("last_changed", "2026-05-29T08:00:00+00:00")
            putJsonObject("attributes") {
                put("friendly_name", "Sun")
                put("elevation", "42.0")
            }
        }
        val raw = decodeRawRow(row)
        assertThat(raw).isNotNull()
        assertThat(raw!!.entityId).isEqualTo("sun.sun")
        assertThat(raw.state).isEqualTo("above_horizon")
        assertThat(raw.friendlyName).isEqualTo("Sun")
        assertThat(raw.lastChanged).isNotNull()
        // Full attributes survive so the renderer can read domain-specific fields.
        assertThat(raw.attributes.containsKey("elevation")).isTrue()
    }

    @Test
    fun `decodeRawRow falls back to entity_id when friendly_name missing`() {
        val row = buildJsonObject {
            put("entity_id", "custom_domain.widget")
            put("state", "running")
        }
        val raw = decodeRawRow(row)
        assertThat(raw).isNotNull()
        assertThat(raw!!.friendlyName).isEqualTo("custom_domain.widget")
        assertThat(raw.lastChanged).isNull()
        assertThat(raw.attributes.isEmpty()).isTrue()
    }

    @Test
    fun `decodeRawRow keeps unavailable state verbatim`() {
        val unavailable = decodeRawRow(
            buildJsonObject {
                put("entity_id", "sensor.flaky")
                put("state", "unavailable")
            },
        )
        assertThat(unavailable).isNotNull()
        assertThat(unavailable!!.state).isEqualTo("unavailable")
    }

    @Test
    fun `decodeRawRow returns null without entity_id or state or domain dot`() {
        assertThat(decodeRawRow(buildJsonObject { put("state", "on") })).isNull()
        assertThat(
            decodeRawRow(buildJsonObject { put("entity_id", "sensor.x") }),
        ).isNull()
        assertThat(
            decodeRawRow(
                buildJsonObject {
                    put("entity_id", "nodot")
                    put("state", "on")
                },
            ),
        ).isNull()
    }

    @Test
    fun `decodeRawStatesBody keeps every domain unlike the typed decoder`() {
        val body = """
            [
              {"entity_id":"light.kitchen","state":"on","attributes":{"friendly_name":"Kitchen"}},
              {"entity_id":"sun.sun","state":"above_horizon","attributes":{"friendly_name":"Sun"}},
              {"entity_id":"device_tracker.phone","state":"home","attributes":{}}
            ]
        """.trimIndent()
        val raw = decodeRawStatesBody(body)
        // sun.sun and device_tracker.* are unmodelled domains, but the raw decoder
        // keeps them so the dashboards renderer can show their state.
        assertThat(raw.map { it.entityId })
            .containsExactly("light.kitchen", "sun.sun", "device_tracker.phone")
        // The typed decoder still drops the unmodelled domains.
        val typed = DefaultHaRepository.decodeStatesBody(
            body,
            logInfo = { _, _ -> },
            logWarn = { _, _ -> },
        )
        assertThat(typed.map { it.id.value }).containsExactly("light.kitchen")
        // ...but the search path (includeUnsupported = true) keeps them as OTHER records so
        // Universal Search can find every entity the user owns.
        val forSearch = DefaultHaRepository.decodeStatesBody(
            body,
            logInfo = { _, _ -> },
            logWarn = { _, _ -> },
            includeUnsupported = true,
        )
        assertThat(forSearch.map { it.id.value })
            .containsExactly("light.kitchen", "sun.sun", "device_tracker.phone")
        assertThat(forSearch.first { it.id.value == "device_tracker.phone" }.id.domain)
            .isEqualTo(Domain.OTHER)
    }

    @Test
    fun `decodeRawStatesBody skips a malformed row without blanking the rest`() {
        val body = """
            [
              {"entity_id":"sun.sun","state":"above_horizon"},
              {"not_an_entity":true},
              {"entity_id":"weather.home","state":"sunny"}
            ]
        """.trimIndent()
        assertThat(decodeRawStatesBody(body).map { it.entityId })
            .containsExactly("sun.sun", "weather.home")
    }
}
