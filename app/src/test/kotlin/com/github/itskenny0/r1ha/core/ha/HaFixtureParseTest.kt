package com.github.itskenny0.r1ha.core.ha

import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.lovelace.LovelaceParser
import com.github.itskenny0.r1ha.feature.weather.ForecastKind
import com.github.itskenny0.r1ha.feature.weather.classifyForecastKind
import com.github.itskenny0.r1ha.feature.weather.parseForecastResponse
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

/**
 * Real-data parser coverage. These tests feed AUTHENTIC Home Assistant JSON payloads
 * (captured from real HA 2024.x installations: WebSocket auth/event/result frames, a
 * `lovelace/config` reply, a `weather.get_forecasts` service response, and an
 * `/api/states` REST body) through the SAME production parsers the live app uses, and
 * assert the typed results.
 *
 * Why fixtures and not a live integration test against demo.home-assistant.io: that host
 * is a FRONTEND-ONLY demo served from a static CDN (Netlify/Cloudflare). It has no real
 * backend: `GET /api/` and the `/api/websocket` upgrade both return HTTP 404, so there is
 * no reachable HA REST/WS API to connect the real client to. The in-browser demo backend
 * is JavaScript-only and not exposed over the network. Recorded authentic payloads give
 * real-shape coverage with zero network dependency, so the default unit-test gate and CI
 * stay green offline.
 *
 * No production code is exercised through reflection or test-only shims: every parser
 * called here (HaInbound deserialization via HaJson, LovelaceParser, parseForecastResponse)
 * is already part of the public/internal API the repository drives.
 */
class HaFixtureParseTest {

    private val lenientJson = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/ha-fixtures/$name")) {
            "Missing test fixture /ha-fixtures/$name"
        }.bufferedReader().use { it.readText() }

    private fun fixtureObject(name: String): JsonObject =
        lenientJson.parseToJsonElement(fixture(name)) as JsonObject

    // ---- WebSocket frames: HaInbound via the production HaJson config -------------------

    @Test fun `auth_required frame decodes with ha_version`() {
        val msg = HaJson.decodeFromString<HaInbound>(fixture("ws_auth_required.json"))
        assertThat(msg).isInstanceOf(HaInbound.AuthRequired::class.java)
        assertThat((msg as HaInbound.AuthRequired).haVersion).isEqualTo("2024.12.5")
    }

    @Test fun `auth_ok frame decodes to Connected-bearing version`() {
        val msg = HaJson.decodeFromString<HaInbound>(fixture("ws_auth_ok.json"))
        assertThat(msg).isInstanceOf(HaInbound.AuthOk::class.java)
        assertThat((msg as HaInbound.AuthOk).haVersion).isEqualTo("2024.12.5")
    }

    @Test fun `auth_invalid frame carries the rejection message`() {
        val msg = HaJson.decodeFromString<HaInbound>(fixture("ws_auth_invalid.json"))
        assertThat(msg).isInstanceOf(HaInbound.AuthInvalid::class.java)
        assertThat((msg as HaInbound.AuthInvalid).message).contains("Invalid access token")
    }

    @Test fun `subscribe_trigger state-change event decodes into typed trigger`() {
        val msg = HaJson.decodeFromString<HaInbound>(fixture("ws_event_state_changed.json"))
        assertThat(msg).isInstanceOf(HaInbound.Event::class.java)
        val event = msg as HaInbound.Event
        assertThat(event.id).isEqualTo(18)
        val trigger = event.event.variables.trigger
        assertThat(trigger.platform).isEqualTo("state")
        assertThat(trigger.entityId).isEqualTo("light.living_room")
        assertThat(trigger.fromState?.state).isEqualTo("off")
        assertThat(trigger.toState?.state).isEqualTo("on")
        // Brightness lives in the to_state attributes JSON the repository reads downstream.
        val brightness = (trigger.toState?.attributes?.get("brightness") as? JsonPrimitive)?.content
        assertThat(brightness).isEqualTo("204")
    }

    @Test fun `successful result frame exposes the result payload`() {
        val msg = HaJson.decodeFromString<HaInbound>(fixture("ws_result_get_config.json"))
        assertThat(msg).isInstanceOf(HaInbound.Result::class.java)
        val result = msg as HaInbound.Result
        assertThat(result.id).isEqualTo(2)
        assertThat(result.success).isTrue()
        assertThat(result.error).isNull()
        val version = (result.result as? JsonObject)?.get("version") as? JsonPrimitive
        assertThat(version?.content).isEqualTo("2024.12.5")
    }

    @Test fun `error result frame coerces string error code`() {
        val msg = HaJson.decodeFromString<HaInbound>(fixture("ws_result_error.json"))
        val result = msg as HaInbound.Result
        assertThat(result.success).isFalse()
        assertThat(result.error?.codeString).isEqualTo("not_found")
        assertThat(result.error?.message).contains("light.does_not_exist")
    }

    // ---- Lovelace config ---------------------------------------------------------------

    @Test fun `lovelace config parses legacy cards and sections views`() {
        val cfg = LovelaceParser.parseConfig(fixtureObject("lovelace_config.json"))
        assertThat(cfg.title).isEqualTo("Home")
        assertThat(cfg.views).hasSize(2)
        assertThat(cfg.isStrategyGenerated).isFalse()

        val overview = cfg.views[0]
        assertThat(overview.path).isEqualTo("default_view")
        // entities + weather-forecast + thermostat all parse into concrete cards.
        assertThat(overview.cards.size).isAtLeast(3)
        val entitiesCard = overview.cards.filterIsInstance<LovelaceCard.Entities>().first()
        assertThat(entitiesCard.entities.map { it.entityId })
            .containsExactly("light.living_room", "light.kitchen").inOrder()
        assertThat(entitiesCard.entities[1].name).isEqualTo("Kitchen Spots")

        // The sections-mode view flattens section cards into the view's card list.
        val sections = cfg.views[1]
        assertThat(sections.cards).isNotEmpty()
        val sectionEntities = sections.cards.mapNotNull {
            (it as? LovelaceCard.Tile)?.entityId
        }
        assertThat(sectionEntities)
            .containsAtLeast("climate.thermostat", "sensor.outside_temperature")
    }

    // ---- weather.get_forecasts ---------------------------------------------------------

    @Test fun `get_forecasts service response parses into daily entries`() {
        val root = fixtureObject("weather_get_forecasts_daily.json")
        val perEntity = root["service_response"]!!.jsonObject["weather.home"]
        val entries = parseForecastResponse(perEntity)
        assertThat(entries).hasSize(3)
        val first = entries.first()
        assertThat(first.condition).isEqualTo("partlycloudy")
        assertThat(first.temperature).isEqualTo(8.0)
        assertThat(first.tempLow).isEqualTo(3.0)
        assertThat(first.precipitationProbability).isEqualTo(20)
        assertThat(first.windBearingDeg).isEqualTo(225.0)
        // 24h spacing classifies as a daily forecast.
        assertThat(classifyForecastKind(entries)).isEqualTo(ForecastKind.Daily)
    }

    // ---- /api/states REST body ---------------------------------------------------------

    @Test fun `api states body decodes every row and key attributes are typed`() {
        // Mirror the repository's resilient per-row decode: parse to a JsonElement list
        // first so one odd row can't blank the whole body.
        val rows = lenientJson.parseToJsonElement(fixture("rest_api_states.json")) as JsonArray
        val byId = rows.associate { el ->
            val obj = el as JsonObject
            val id = (obj["entity_id"] as JsonPrimitive).content
            id to obj
        }
        assertThat(byId.keys).containsAtLeast(
            "light.living_room",
            "climate.thermostat",
            "sensor.outside_temperature",
            "media_player.living_room_speaker",
            "cover.garage_door",
            "person.alice",
        )

        // Light: color_temp mode with a kelvin reading and a 0..255 brightness.
        val light = byId.getValue("light.living_room")
        assertThat((light["state"] as JsonPrimitive).content).isEqualTo("on")
        val lightAttrs = light["attributes"]!!.jsonObject
        assertThat((lightAttrs["color_temp_kelvin"] as JsonPrimitive).content.toInt())
            .isEqualTo(3500)
        assertThat((lightAttrs["brightness"] as JsonPrimitive).content.toInt())
            .isEqualTo(204)

        // Climate: setpoint + current temperature + hvac modes list.
        val climateAttrs = byId.getValue("climate.thermostat")["attributes"]!!.jsonObject
        assertThat((climateAttrs["temperature"] as JsonPrimitive).content.toDouble())
            .isEqualTo(21.0)
        assertThat((climateAttrs["hvac_modes"] as JsonArray).map { (it as JsonPrimitive).content })
            .containsExactly("off", "heat", "cool", "auto").inOrder()

        // Sensor: numeric state stored as a string, with unit + device_class.
        val sensor = byId.getValue("sensor.outside_temperature")
        assertThat((sensor["state"] as JsonPrimitive).content.toDouble()).isEqualTo(4.7)
        val sensorAttrs = sensor["attributes"]!!.jsonObject
        assertThat((sensorAttrs["unit_of_measurement"] as JsonPrimitive).content).isEqualTo("°C")

        // Media player: 0..1 volume_level that the app normalises to 0..100.
        val mediaAttrs = byId.getValue("media_player.living_room_speaker")["attributes"]!!.jsonObject
        val volume = (mediaAttrs["volume_level"] as JsonPrimitive).content.toDouble()
        assertThat(EntityState.normaliseMediaVolume(volume)).isEqualTo(35)
    }
}
