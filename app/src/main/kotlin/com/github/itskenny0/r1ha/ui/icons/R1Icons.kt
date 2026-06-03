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
