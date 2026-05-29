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

    // ---- /api/states through the production decoder ------------------------------------

    @Test fun `decodeStatesBody maps the REST body into typed EntityStates`() {
        // Drive the SAME pure decoder the live listAllEntities() path uses, so the
        // per-domain isOn / percent / raw / attribute typing is covered against a real
        // /api/states body rather than re-parsed raw JSON.
        // No-op loggers keep the decode off android.util.Log in this non-Robolectric test.
        val states = DefaultHaRepository.decodeStatesBody(
            fixture("rest_api_states.json"),
            logInfo = { _, _ -> },
            logWarn = { _, _ -> },
        )
        val byId = states.associateBy { it.id.value }

        // Every supported row survives the resilient per-row decode.
        assertThat(byId.keys).containsExactly(
            "light.living_room",
            "climate.thermostat",
            "sensor.outside_temperature",
            "media_player.living_room_speaker",
            "cover.garage_door",
            "person.alice",
        )

        // Light: on, brightness 204 -> ~80% via normaliseLightBrightness, raw brightness,
        // kelvin reading typed through.
        val light = byId.getValue("light.living_room")
        assertThat(light.isOn).isTrue()
        assertThat(light.isAvailable).isTrue()
        assertThat(light.friendlyName).isEqualTo("Living Room")
        assertThat(light.raw).isEqualTo(204)
        assertThat(light.percent).isEqualTo(EntityState.normaliseLightBrightness(204))
        assertThat(light.colorTempK).isEqualTo(3500)
        assertThat(light.supportsScalar).isTrue()

        // Climate: heat is "on", target 21.0, current 19.5, hvac mode mirrors state,
        // percent scaled into the 7..35 range.
        val climate = byId.getValue("climate.thermostat")
        assertThat(climate.isOn).isTrue()
        assertThat(climate.climateHvacMode).isEqualTo("heat")
        assertThat(climate.climateTargetTemperature).isEqualTo(21.0)
        assertThat(climate.climateCurrentTemperature).isEqualTo(19.5)
        assertThat(climate.raw).isEqualTo(21.0)
        // (21 - 7) / (35 - 7) * 100 = 50.
        assertThat(climate.percent).isEqualTo(50)

        // Sensor: read-only, numeric state surfaced as rawState, unit + device class typed.
        val sensor = byId.getValue("sensor.outside_temperature")
        assertThat(sensor.isOn).isFalse()
        assertThat(sensor.supportsScalar).isFalse()
        assertThat(sensor.rawState).isEqualTo("4.7")
        assertThat(sensor.unit).isEqualTo("°C")
        assertThat(sensor.deviceClass).isEqualTo("temperature")

        // Media player: playing -> on, volume 0.35 -> 35%, title typed through.
        val media = byId.getValue("media_player.living_room_speaker")
        assertThat(media.isOn).isTrue()
        assertThat(media.percent).isEqualTo(35)
        assertThat(media.mediaTitle).isEqualTo("Some Track")

        // Cover: closed -> not on; current_position 0 surfaces as percent 0.
        val cover = byId.getValue("cover.garage_door")
        assertThat(cover.isOn).isFalse()
        assertThat(cover.percent).isEqualTo(0)

        // Person: home -> on.
        assertThat(byId.getValue("person.alice").isOn).isTrue()
    }

    // ---- recorder/statistics_during_period through the production decoder ---------------

    @Test fun `decodeStatisticsBuckets parses ISO and epoch-millis buckets`() {
        val payload = fixtureObject("statistics_during_period.json")
        val result = DefaultHaRepository.decodeStatisticsBuckets(payload, period = "hour")

        assertThat(result.keys)
            .containsExactly("sensor.outside_temperature", "sensor.house_energy")

        // Measurement series: ISO-8601 boundaries, mean/min/max filled, sum/state/change null.
        val temp = result.getValue("sensor.outside_temperature")
        assertThat(temp).hasSize(3)
        val firstTemp = temp.first()
        assertThat(firstTemp.start).isEqualTo(java.time.Instant.parse("2024-12-20T00:00:00Z"))
        assertThat(firstTemp.end).isEqualTo(java.time.Instant.parse("2024-12-20T01:00:00Z"))
        assertThat(firstTemp.mean).isEqualTo(4.2)
        assertThat(firstTemp.min).isEqualTo(3.1)
        assertThat(firstTemp.max).isEqualTo(5.0)
        assertThat(firstTemp.sum).isNull()
        assertThat(firstTemp.change).isNull()

        // Energy series: epoch-millis boundaries (exercises the dual-format parser),
        // sum/state/change filled, mean/min/max null.
        val energy = result.getValue("sensor.house_energy")
        assertThat(energy).hasSize(2)
        val firstEnergy = energy.first()
        assertThat(firstEnergy.start).isEqualTo(java.time.Instant.parse("2024-12-20T00:00:00Z"))
        assertThat(firstEnergy.end).isEqualTo(java.time.Instant.parse("2024-12-20T01:00:00Z"))
        assertThat(firstEnergy.sum).isEqualTo(1450.5)
        assertThat(firstEnergy.state).isEqualTo(1450.5)
        assertThat(firstEnergy.change).isEqualTo(0.42)
        assertThat(firstEnergy.mean).isNull()
    }
}
