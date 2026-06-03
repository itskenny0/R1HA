package com.github.itskenny0.r1ha.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Resolves a Home Assistant domain / entity / device-class to an R1HA in-house
 * icon ([R1IconSet]). This is the license-clean replacement for MDI lookups:
 * every glyph it returns is hand-authored original geometry under The Unlicense.
 *
 * Usage:
 * ```
 * Icon(
 *     imageVector = R1Icons.forDomain("light"),
 *     contentDescription = null,
 *     tint = R1.AccentWarm,
 * )
 * // or, given a full entity id and live state:
 * Icon(
 *     imageVector = R1Icons.forEntity("binary_sensor.front_door", deviceClass = "door", state = "on"),
 *     contentDescription = null,
 *     tint = R1.Ink,
 * )
 * ```
 *
 * Resolution order in [forEntity]:
 *   1. device_class (for `sensor` / `binary_sensor`, where the class is the
 *      real meaning, e.g. `sensor.x` with class `temperature`),
 *   2. otherwise the domain (the entity-id prefix before the first '.'),
 *   3. unknown -> [R1IconSet.Generic].
 */
object R1Icons {

    /**
     * Pick an icon for a bare HA [domain] (e.g. "light", "cover"). When the
     * domain is `sensor` or `binary_sensor`, pass [deviceClass] so the specific
     * measurement / opening glyph is chosen instead of the generic gauge.
     */
    fun forDomain(domain: String, deviceClass: String? = null): ImageVector {
        val d = domain.trim().lowercase()

        // For sensor-family domains the device_class carries the real meaning.
        if (d == "sensor" || d == "binary_sensor") {
            deviceClassIcon(deviceClass)?.let { return it }
            return if (d == "binary_sensor") R1IconSet.BinarySensor else R1IconSet.Sensor
        }

        return when (d) {
            "light" -> R1IconSet.Light
            "switch" -> R1IconSet.Switch
            "outlet" -> R1IconSet.Outlet
            "fan" -> R1IconSet.Fan
            "cover" -> R1IconSet.Cover
            "garage", "garage_door" -> R1IconSet.Garage
            "climate", "thermostat" -> R1IconSet.Climate
            "media_player" -> R1IconSet.MediaPlayer
            "speaker" -> R1IconSet.Speaker
            "tv" -> R1IconSet.Tv
            "lock" -> R1IconSet.Lock
            "motion" -> R1IconSet.Motion
            "door" -> R1IconSet.Door
            "window" -> R1IconSet.Window
            "person", "device_tracker" -> R1IconSet.Person
            "weather" -> R1IconSet.Weather
            "camera" -> R1IconSet.Camera
            "vacuum" -> R1IconSet.Vacuum
            "humidifier" -> R1IconSet.Humidifier
            "valve" -> R1IconSet.Valve
            "water_heater" -> R1IconSet.WaterHeater
            "lawn_mower" -> R1IconSet.LawnMower
            "scene" -> R1IconSet.Scene
            "script" -> R1IconSet.Script
            "automation" -> R1IconSet.Automation
            "input_boolean" -> R1IconSet.InputBoolean
            "number", "input_number" -> R1IconSet.Number
            "select", "input_select" -> R1IconSet.Select
            "text", "input_text" -> R1IconSet.Text
            "button", "input_button" -> R1IconSet.Button
            "timer" -> R1IconSet.Timer
            "counter" -> R1IconSet.Counter
            "update" -> R1IconSet.Update
            "sun" -> R1IconSet.Sun
            "zone" -> R1IconSet.Zone
            "calendar" -> R1IconSet.Calendar
            "todo" -> R1IconSet.Todo
            "alarm_control_panel" -> R1IconSet.AlarmControlPanel
            "siren" -> R1IconSet.Siren
            "remote" -> R1IconSet.Remote
            "battery" -> R1IconSet.Battery
            "power", "energy" -> R1IconSet.Power
            "temperature" -> R1IconSet.Temperature
            "humidity" -> R1IconSet.Humidity
            else -> R1IconSet.Generic
        }
    }

    /**
     * Pick an icon for a full [entityId] such as "light.kitchen". Splits off
     * the domain prefix and delegates to [forDomain], honouring [deviceClass]
     * for sensor-family entities. [state] is accepted for API symmetry and
     * future state-dependent glyphs (e.g. open vs closed covers); it does not
     * change the result today.
     */
    @Suppress("UNUSED_PARAMETER")
    fun forEntity(entityId: String, deviceClass: String? = null, state: String? = null): ImageVector {
        val domain = entityId.substringBefore('.', missingDelimiterValue = entityId)
        return forDomain(domain, deviceClass)
    }

    /**
     * Map an HA `icon:` config string to an in-house icon. Accepts the slug
     * with or without the leading `mdi:` prefix (e.g. "mdi:lightbulb" or
     * "lightbulb"). Returns null for any slug we don't curate so the caller can
     * fall back to [forEntity] / [forDomain] (the domain-derived glyph). The
     * curated set covers the common dashboard slugs; unknown slugs intentionally
     * fall through rather than guessing.
     */
    fun forMdi(slug: String?): ImageVector? {
        if (slug.isNullOrBlank()) return null
        val s = slug.trim().removePrefix("mdi:").trim().lowercase()
        if (s.isEmpty()) return null
        return when (s) {
            // Lighting
            "lightbulb", "lightbulb-outline", "lightbulb-on", "lightbulb-on-outline",
            "ceiling-light", "floor-lamp", "lamp", "led-strip", "track-light",
            "spotlight", "spotlight-beam", "wall-sconce", "bulb",
            -> R1IconSet.Light
            // Switches / outlets / power
            "toggle-switch", "toggle-switch-outline", "light-switch", "electric-switch",
            -> R1IconSet.Switch
            "power-plug", "power-plug-outline", "power-socket", "power-socket-eu",
            "power-socket-us", "outlet",
            -> R1IconSet.Outlet
            "power", "power-standby", "power-on", "power-off", "flash", "flash-outline",
            "lightning-bolt", "lightning-bolt-outline",
            -> R1IconSet.Power
            "transmission-tower", "meter-electric", "meter-electric-outline",
            "home-lightning-bolt", "gauge", "gauge-low", "gauge-full", "speedometer",
            -> R1IconSet.Power
            // Climate / temperature / humidity
            "thermostat", "thermostat-box", "hvac", "air-conditioner",
            -> R1IconSet.Climate
            "thermometer", "thermometer-lines", "temperature-celsius",
            "temperature-fahrenheit", "home-thermometer", "coolant-temperature",
            -> R1IconSet.Temperature
            "water-percent", "water-percent-alert", "cloud-percent",
            -> R1IconSet.Humidity
            "air-humidifier", "air-humidifier-off",
            -> R1IconSet.Humidifier
            // Air / fans
            "fan", "fan-off", "fan-speed-1", "fan-speed-2", "fan-speed-3", "ceiling-fan",
            -> R1IconSet.Fan
            // Covers / openings
            "window-shutter", "window-shutter-open", "window-shutter-alert",
            "blinds", "blinds-open", "roller-shade", "curtains", "window-shutter-cog",
            -> R1IconSet.Cover
            "garage", "garage-open", "garage-variant", "garage-alert",
            -> R1IconSet.Garage
            "door", "door-open", "door-closed", "door-closed-lock",
            -> R1IconSet.Door
            "window-open", "window-closed", "window-open-variant", "window-closed-variant",
            -> R1IconSet.Window
            // Security
            "lock", "lock-outline", "lock-open", "lock-open-outline", "lock-smart",
            -> R1IconSet.Lock
            "shield-home", "shield-lock", "shield-check", "security",
            "alarm-panel", "home-lock",
            -> R1IconSet.AlarmControlPanel
            "alarm-light", "bullhorn", "bell-ring", "bell-alert",
            -> R1IconSet.Siren
            // Sensors
            "motion-sensor", "motion-sensor-off", "walk", "run", "run-fast",
            -> R1IconSet.Motion
            "account-eye", "cctv", "human-greeting",
            -> R1IconSet.Occupancy
            "smoke-detector", "smoke", "fire", "gas-cylinder", "molecule-co2",
            -> R1IconSet.Smoke
            "water", "water-alert", "water-outline", "cup-water", "waves",
            -> R1IconSet.Moisture
            "gauge-empty", "car-brake-low-pressure", "gas-burner",
            -> R1IconSet.Pressure
            "brightness-5", "brightness-6", "brightness-7", "white-balance-sunny",
            "weather-sunny",
            -> R1IconSet.Illuminance
            "thermometer-water", "eye",
            -> R1IconSet.Sensor
            // Battery / energy
            "battery", "battery-outline", "battery-charging", "battery-50",
            "battery-high", "battery-medium", "battery-low", "battery-alert",
            -> R1IconSet.Battery
            "solar-power", "solar-panel", "solar-panel-large",
            -> R1IconSet.Power
            // Weather
            "weather-cloudy", "weather-partly-cloudy", "weather-rainy",
            "weather-pouring", "weather-snowy", "weather-fog", "weather-windy",
            "weather-lightning", "weather-night", "cloud",
            -> R1IconSet.Weather
            "white-balance-sunny-alert", "sun-clock", "sun-thermometer", "weather-sunset",
            -> R1IconSet.Sun
            // Media
            "play", "play-circle", "pause", "music", "music-note", "playlist-music",
            -> R1IconSet.MediaPlayer
            "speaker", "speaker-wireless", "cast-audio", "volume-high",
            -> R1IconSet.Speaker
            "television", "television-classic", "monitor", "remote-tv",
            -> R1IconSet.Tv
            "remote", "remote-control",
            -> R1IconSet.Remote
            "camera", "camera-outline", "video", "webcam",
            -> R1IconSet.Camera
            // People / places
            "account", "account-outline", "account-circle", "human", "face-man",
            -> R1IconSet.Person
            "home", "home-outline", "home-variant", "house", "home-assistant",
            -> R1IconSet.Generic
            "map-marker", "map-marker-radius", "crosshairs-gps",
            -> R1IconSet.Zone
            // Helpers / logic
            "robot", "robot-outline", "cog", "cog-outline", "auto-fix",
            -> R1IconSet.Automation
            "script-text", "script", "file-document",
            -> R1IconSet.Script
            "palette", "movie-roll", "movie-open", "star-four-points",
            -> R1IconSet.Scene
            "gesture-tap-button", "gesture-tap",
            -> R1IconSet.Button
            "toggle-switch-variant", "check-circle",
            -> R1IconSet.InputBoolean
            "form-textbox", "text", "text-box",
            -> R1IconSet.Text
            "format-list-bulleted", "menu", "arrow-down-drop-circle",
            -> R1IconSet.Select
            "numeric", "pound", "counter",
            -> R1IconSet.Counter
            "timer", "timer-outline", "timer-sand", "clock", "clock-outline",
            -> R1IconSet.Timer
            "calendar", "calendar-outline", "calendar-month", "calendar-clock",
            -> R1IconSet.Calendar
            "format-list-checks", "playlist-check", "check-all", "clipboard-list",
            -> R1IconSet.Todo
            "update", "package-up", "download", "cloud-download", "arrow-up-bold",
            -> R1IconSet.Update
            // Appliances
            "robot-vacuum", "robot-vacuum-variant",
            -> R1IconSet.Vacuum
            "valve", "valve-open", "valve-closed", "pipe-valve",
            -> R1IconSet.Valve
            "water-boiler", "water-pump", "heating-coil",
            -> R1IconSet.WaterHeater
            "robot-mower", "robot-mower-outline", "mower",
            -> R1IconSet.LawnMower
            else -> null
        }
    }

    /**
     * Resolve an HA weather `condition` slug (the raw `weather.*` state, e.g.
     * "partlycloudy", "clear-night", "lightning-rainy") to an in-house weather
     * glyph. Covers the full HA standard condition vocabulary; unknown / future
     * conditions fall back to [R1IconSet.Weather] (the sun-behind-cloud glyph)
     * so the layout never breaks. Tint at the call site (conditionAccent).
     */
    fun conditionIcon(condition: String): ImageVector = when (condition.trim().lowercase()) {
        "sunny", "clear", "clear-day", "exceptional-clear" -> R1IconSet.Sun
        "clear-night", "night" -> R1IconSet.ClearNight
        "partlycloudy", "partly-cloudy", "partlycloudy-night" -> R1IconSet.PartlyCloudy
        "cloudy", "overcast" -> R1IconSet.Cloudy
        "rainy", "snowy-rainy", "hail-rainy" -> R1IconSet.Rainy
        "pouring" -> R1IconSet.Pouring
        "snowy" -> R1IconSet.Snowy
        "fog", "mist", "haze" -> R1IconSet.Fog
        "lightning", "lightning-rainy", "thunderstorm" -> R1IconSet.Lightning
        "windy", "windy-variant" -> R1IconSet.Windy
        "hail" -> R1IconSet.Hail
        "exceptional" -> R1IconSet.Exceptional
        else -> R1IconSet.Weather
    }

    /**
     * Map a HA sensor / binary_sensor `device_class` to an icon, or null when
     * the class is unknown / absent (caller then falls back to the generic
     * sensor glyph).
     */
    private fun deviceClassIcon(deviceClass: String?): ImageVector? {
        return when (deviceClass?.trim()?.lowercase()) {
            "temperature" -> R1IconSet.Temperature
            "humidity" -> R1IconSet.Humidity
            "power", "energy" -> R1IconSet.Power
            "battery" -> R1IconSet.Battery
            "pressure" -> R1IconSet.Pressure
            "illuminance" -> R1IconSet.Illuminance
            "motion", "moving" -> R1IconSet.Motion
            "door", "garage_door" -> R1IconSet.Door
            "window" -> R1IconSet.Window
            "opening" -> R1IconSet.Door
            "occupancy", "presence" -> R1IconSet.Occupancy
            "smoke", "gas" -> R1IconSet.Smoke
            "moisture" -> R1IconSet.Moisture
            else -> null
        }
    }
}
