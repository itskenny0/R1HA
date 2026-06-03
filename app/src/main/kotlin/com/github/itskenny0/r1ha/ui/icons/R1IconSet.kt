package com.github.itskenny0.r1ha.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The R1HA in-house icon set: hand-authored, ORIGINAL public-domain line
 * glyphs in the "Mission Control" aesthetic. Each is a 24x24 stroked
 * [ImageVector] with no intrinsic colour, meant to be tinted at the call site.
 *
 * All geometry here is original (simple rectangles, circles, arcs, polylines)
 * and contains no copied MDI / Material / third-party SVG path data.
 *
 * Resolution from HA domains / device-classes lives in [R1Icons]; this object
 * just holds the raw vectors so they can also be referenced directly.
 */
object R1IconSet {

    // --- Lighting & power switching ----------------------------------------

    /** light: a bulb with a small base and two short rays. */
    val Light: ImageVector = lineIcon("r1_light",
        // bulb outline + screw base
        {
            moveTo(8.5f, 14.5f)
            curveTo(7f, 13f, 6.5f, 11.5f, 7f, 9.5f)
            curveTo(7.6f, 7.1f, 9.6f, 5.5f, 12f, 5.5f)
            curveTo(14.4f, 5.5f, 16.4f, 7.1f, 17f, 9.5f)
            curveTo(17.5f, 11.5f, 17f, 13f, 15.5f, 14.5f)
            lineTo(15f, 16f)
            lineTo(9f, 16f)
            close()
            moveTo(9.5f, 18f)
            lineTo(14.5f, 18f)
            moveTo(10.5f, 20f)
            lineTo(13.5f, 20f)
        },
    )

    /** switch: a rounded pill toggle with the knob to the right (on). */
    val Switch: ImageVector = lineIcon("r1_switch",
        {
            moveTo(7f, 8f)
            lineTo(17f, 8f)
            arcTo(4f, 4f, 0f, true, true, 17f, 16f)
            lineTo(7f, 16f)
            arcTo(4f, 4f, 0f, true, true, 7f, 8f)
            close()
        },
        { circle(16.5f, 12f, 2.2f) },
    )

    /** outlet: a faceplate with two prong slots. */
    val Outlet: ImageVector = lineIcon("r1_outlet",
        { rect(6f, 4.5f, 18f, 19.5f) },
        {
            moveTo(10.5f, 9f)
            lineTo(10.5f, 12.5f)
            moveTo(13.5f, 9f)
            lineTo(13.5f, 12.5f)
            moveTo(9.5f, 16f)
            lineTo(14.5f, 16f)
        },
    )

    /** fan: three curved blades around a hub. */
    val Fan: ImageVector = lineIcon("r1_fan",
        {
            // top blade
            moveTo(12f, 12f)
            curveTo(12f, 8f, 14f, 5f, 16f, 6f)
            curveTo(18f, 7f, 16f, 11f, 12f, 12f)
            // lower-right blade
            curveTo(15.5f, 14f, 18f, 17f, 16.5f, 18.5f)
            curveTo(15f, 20f, 12.5f, 16.5f, 12f, 12f)
            // lower-left blade
            curveTo(8.5f, 13.5f, 5f, 14f, 5f, 11.5f)
            curveTo(5f, 9f, 9f, 9f, 12f, 12f)
            close()
        },
        { dot(12f, 12f, 1.1f) },
    )

    // --- Covers & openings -------------------------------------------------

    /** cover (blinds): a frame with three horizontal slats and a pull. */
    val Cover: ImageVector = lineIcon("r1_cover",
        { rect(5f, 4.5f, 19f, 16f) },
        {
            moveTo(5f, 8f)
            lineTo(19f, 8f)
            moveTo(5f, 11.5f)
            lineTo(19f, 11.5f)
            moveTo(12f, 16f)
            lineTo(12f, 19.5f)
        },
    )

    /** garage: a house roofline over a paneled door. */
    val Garage: ImageVector = lineIcon("r1_garage",
        {
            moveTo(4f, 11f)
            lineTo(12f, 5f)
            lineTo(20f, 11f)
            lineTo(20f, 20f)
            lineTo(4f, 20f)
            close()
        },
        {
            moveTo(7f, 13.5f)
            lineTo(17f, 13.5f)
            moveTo(7f, 16.5f)
            lineTo(17f, 16.5f)
            moveTo(7f, 13.5f)
            lineTo(7f, 20f)
            moveTo(17f, 13.5f)
            lineTo(17f, 20f)
        },
    )

    /** door: a door slab with a knob. */
    val Door: ImageVector = lineIcon("r1_door",
        { rect(6f, 4f, 18f, 20f) },
        { dot(15f, 12f, 0.9f) },
    )

    /** window: a frame split into four panes. */
    val Window: ImageVector = lineIcon("r1_window",
        { rect(5f, 5f, 19f, 19f) },
        {
            moveTo(12f, 5f)
            lineTo(12f, 19f)
            moveTo(5f, 12f)
            lineTo(19f, 12f)
        },
    )

    // --- Climate / environment ---------------------------------------------

    /** climate / thermostat: a thermometer with a bulb reservoir. */
    val Climate: ImageVector = lineIcon("r1_climate",
        {
            moveTo(10.5f, 13.2f)
            lineTo(10.5f, 6f)
            arcTo(1.5f, 1.5f, 0f, true, true, 13.5f, 6f)
            lineTo(13.5f, 13.2f)
            arcTo(3.2f, 3.2f, 0f, true, true, 10.5f, 13.2f)
            close()
        },
        { dot(12f, 16f, 1.2f) },
    )

    /** temperature: a slim thermometer (sensor device_class temperature). */
    val Temperature: ImageVector = lineIcon("r1_temperature",
        {
            moveTo(10.5f, 13.2f)
            lineTo(10.5f, 5.5f)
            arcTo(1.5f, 1.5f, 0f, true, true, 13.5f, 5.5f)
            lineTo(13.5f, 13.2f)
            arcTo(3.2f, 3.2f, 0f, true, true, 10.5f, 13.2f)
            close()
        },
    )

    /** humidity: a droplet outline. */
    val Humidity: ImageVector = lineIcon("r1_humidity",
        {
            moveTo(12f, 4.5f)
            curveTo(12f, 4.5f, 6.5f, 11f, 6.5f, 15f)
            arcTo(5.5f, 5.5f, 0f, true, false, 17.5f, 15f)
            curveTo(17.5f, 11f, 12f, 4.5f, 12f, 4.5f)
            close()
        },
    )

    /** humidifier: a droplet with rising vapor lines. */
    val Humidifier: ImageVector = lineIcon("r1_humidifier",
        {
            moveTo(12f, 9.5f)
            curveTo(12f, 9.5f, 8.5f, 13.5f, 8.5f, 16f)
            arcTo(3.5f, 3.5f, 0f, true, false, 15.5f, 16f)
            curveTo(15.5f, 13.5f, 12f, 9.5f, 12f, 9.5f)
            close()
        },
        {
            moveTo(9f, 6f)
            curveTo(8f, 5f, 10f, 4f, 9f, 3f)
            moveTo(15f, 6f)
            curveTo(14f, 5f, 16f, 4f, 15f, 3f)
        },
    )

    /** water_heater: a tank with a heat-coil line. */
    val WaterHeater: ImageVector = lineIcon("r1_water_heater",
        {
            moveTo(7f, 7f)
            lineTo(17f, 7f)
            lineTo(17f, 19f)
            lineTo(7f, 19f)
            close()
        },
        {
            moveTo(9.5f, 11f)
            curveTo(11f, 11f, 11f, 13f, 12.5f, 13f)
            curveTo(14f, 13f, 14f, 11f, 14.5f, 11f)
            moveTo(8.5f, 7f)
            lineTo(8.5f, 4.5f)
            moveTo(15.5f, 7f)
            lineTo(15.5f, 4.5f)
        },
    )

    // --- Weather -----------------------------------------------------------

    /** weather: a sun behind a cloud. */
    val Weather: ImageVector = lineIcon("r1_weather",
        { circle(9f, 9f, 3f) },
        {
            // a couple of sun rays peeking out
            moveTo(9f, 3.5f)
            lineTo(9f, 5f)
            moveTo(4.6f, 5.6f)
            lineTo(5.7f, 6.7f)
            moveTo(3.5f, 10f)
            lineTo(5f, 10f)
        },
        {
            // cloud lump in the lower-right
            moveTo(10f, 18f)
            arcTo(3f, 3f, 0f, false, true, 11f, 12.4f)
            arcTo(3.5f, 3.5f, 0f, false, true, 17.3f, 14f)
            arcTo(2.4f, 2.4f, 0f, false, true, 17.5f, 18f)
            close()
        },
    )

    /** sun: a disc with eight rays. */
    val Sun: ImageVector = lineIcon("r1_sun",
        { circle(12f, 12f, 3.5f) },
        {
            moveTo(12f, 3f)
            lineTo(12f, 5.5f)
            moveTo(12f, 18.5f)
            lineTo(12f, 21f)
            moveTo(3f, 12f)
            lineTo(5.5f, 12f)
            moveTo(18.5f, 12f)
            lineTo(21f, 12f)
            moveTo(5.6f, 5.6f)
            lineTo(7.4f, 7.4f)
            moveTo(16.6f, 16.6f)
            lineTo(18.4f, 18.4f)
            moveTo(18.4f, 5.6f)
            lineTo(16.6f, 7.4f)
            moveTo(7.4f, 16.6f)
            lineTo(5.6f, 18.4f)
        },
    )

    /** clear-night: a crescent moon. */
    val ClearNight: ImageVector = lineIcon("r1_clear_night",
        {
            moveTo(15.5f, 4.5f)
            arcTo(8f, 8f, 0f, true, false, 19.5f, 14.5f)
            arcTo(6.2f, 6.2f, 0f, true, true, 15.5f, 4.5f)
            close()
        },
    )

    /** partly cloudy: a sun peeking from behind a cloud. */
    val PartlyCloudy: ImageVector = lineIcon("r1_partlycloudy",
        { circle(9f, 8.5f, 2.6f) },
        {
            moveTo(9f, 3.2f)
            lineTo(9f, 4.6f)
            moveTo(4.4f, 5.4f)
            lineTo(5.4f, 6.4f)
            moveTo(3.2f, 9f)
            lineTo(4.6f, 9f)
        },
        {
            moveTo(9.5f, 19f)
            arcTo(3f, 3f, 0f, false, true, 10.5f, 13.2f)
            arcTo(3.6f, 3.6f, 0f, false, true, 17f, 14.8f)
            arcTo(2.4f, 2.4f, 0f, false, true, 17.2f, 19f)
            close()
        },
    )

    /** cloudy: a single full cloud outline. */
    val Cloudy: ImageVector = lineIcon("r1_cloudy",
        {
            moveTo(7f, 18f)
            arcTo(3.4f, 3.4f, 0f, false, true, 7.6f, 11.4f)
            arcTo(4f, 4f, 0f, false, true, 15f, 9.8f)
            arcTo(3.4f, 3.4f, 0f, false, true, 17.8f, 18f)
            close()
        },
    )

    /** rainy: a cloud with a few short rain streaks. */
    val Rainy: ImageVector = lineIcon("r1_rainy",
        {
            moveTo(7f, 14f)
            arcTo(3.2f, 3.2f, 0f, false, true, 7.6f, 8f)
            arcTo(3.8f, 3.8f, 0f, false, true, 14.8f, 6.6f)
            arcTo(3.2f, 3.2f, 0f, false, true, 17.4f, 14f)
            close()
        },
        {
            moveTo(9f, 17f)
            lineTo(8f, 20f)
            moveTo(12f, 17f)
            lineTo(11f, 20f)
            moveTo(15f, 17f)
            lineTo(14f, 20f)
        },
    )

    /** pouring: a cloud with heavier, longer rain streaks. */
    val Pouring: ImageVector = lineIcon("r1_pouring",
        {
            moveTo(7f, 13f)
            arcTo(3.2f, 3.2f, 0f, false, true, 7.6f, 7f)
            arcTo(3.8f, 3.8f, 0f, false, true, 14.8f, 5.6f)
            arcTo(3.2f, 3.2f, 0f, false, true, 17.4f, 13f)
            close()
        },
        {
            moveTo(8.5f, 15f)
            lineTo(7f, 20.5f)
            moveTo(12f, 15f)
            lineTo(10.5f, 20.5f)
            moveTo(15.5f, 15f)
            lineTo(14f, 20.5f)
        },
    )

    /** snowy: a cloud with falling flake dots. */
    val Snowy: ImageVector = lineIcon("r1_snowy",
        {
            moveTo(7f, 14f)
            arcTo(3.2f, 3.2f, 0f, false, true, 7.6f, 8f)
            arcTo(3.8f, 3.8f, 0f, false, true, 14.8f, 6.6f)
            arcTo(3.2f, 3.2f, 0f, false, true, 17.4f, 14f)
            close()
        },
        { dot(9f, 18f, 0.7f) },
        { dot(12f, 20f, 0.7f) },
        { dot(15f, 18f, 0.7f) },
    )

    /** fog: four stacked horizontal mist lines. */
    val Fog: ImageVector = lineIcon("r1_fog",
        {
            moveTo(4.5f, 7f)
            lineTo(19.5f, 7f)
            moveTo(4.5f, 11f)
            lineTo(19.5f, 11f)
            moveTo(4.5f, 15f)
            lineTo(19.5f, 15f)
            moveTo(4.5f, 19f)
            lineTo(15f, 19f)
        },
    )

    /** lightning: a single bolt (reuses the power-bolt shape). */
    val Lightning: ImageVector = lineIcon("r1_lightning",
        {
            moveTo(13f, 3f)
            lineTo(6f, 13f)
            lineTo(11f, 13f)
            lineTo(10f, 21f)
            lineTo(18f, 10f)
            lineTo(13f, 10f)
            close()
        },
    )

    /** windy: two horizontal gust curls. */
    val Windy: ImageVector = lineIcon("r1_windy",
        {
            moveTo(4f, 9f)
            lineTo(14f, 9f)
            arcTo(2.2f, 2.2f, 0f, true, false, 11.8f, 6.8f)
        },
        {
            moveTo(4f, 14f)
            lineTo(17f, 14f)
            arcTo(2.4f, 2.4f, 0f, true, true, 14.6f, 16.4f)
        },
    )

    /** hail: a cloud with falling pellets. */
    val Hail: ImageVector = lineIcon("r1_hail",
        {
            moveTo(7f, 13f)
            arcTo(3.2f, 3.2f, 0f, false, true, 7.6f, 7f)
            arcTo(3.8f, 3.8f, 0f, false, true, 14.8f, 5.6f)
            arcTo(3.2f, 3.2f, 0f, false, true, 17.4f, 13f)
            close()
        },
        { dot(9f, 17f, 0.9f) },
        { dot(13f, 19f, 0.9f) },
        { dot(16f, 16f, 0.9f) },
    )

    /** exceptional (severe): a triangle with a bang. */
    val Exceptional: ImageVector = lineIcon("r1_exceptional",
        {
            moveTo(12f, 4f)
            lineTo(20.5f, 19.5f)
            lineTo(3.5f, 19.5f)
            close()
        },
        {
            moveTo(12f, 9.5f)
            lineTo(12f, 14f)
        },
        { dot(12f, 17f, 0.8f) },
    )

    // --- Media -------------------------------------------------------------

    /** media_player: a play triangle inside a rounded panel. */
    val MediaPlayer: ImageVector = lineIcon("r1_media_player",
        { rect(4f, 5f, 20f, 19f) },
        {
            moveTo(10f, 9f)
            lineTo(15.5f, 12f)
            lineTo(10f, 15f)
            close()
        },
    )

    /** speaker: a tall cabinet with two driver circles. */
    val Speaker: ImageVector = lineIcon("r1_speaker",
        { rect(7f, 3.5f, 17f, 20.5f) },
        { circle(12f, 15f, 3f) },
        { dot(12f, 7f, 1f) },
    )

    /** tv: a screen on a short stand. */
    val Tv: ImageVector = lineIcon("r1_tv",
        { rect(4f, 5f, 20f, 16f) },
        {
            moveTo(9f, 19.5f)
            lineTo(15f, 19.5f)
        },
    )

    /** remote: a handheld with a directional button and two keys. */
    val Remote: ImageVector = lineIcon("r1_remote",
        {
            moveTo(9f, 3.5f)
            lineTo(15f, 3.5f)
            arcTo(2f, 2f, 0f, false, true, 17f, 5.5f)
            lineTo(17f, 18.5f)
            arcTo(2f, 2f, 0f, false, true, 15f, 20.5f)
            lineTo(9f, 20.5f)
            arcTo(2f, 2f, 0f, false, true, 7f, 18.5f)
            lineTo(7f, 5.5f)
            arcTo(2f, 2f, 0f, false, true, 9f, 3.5f)
            close()
        },
        { circle(12f, 9f, 2f) },
        { dot(10f, 15.5f, 0.8f) },
        { dot(14f, 15.5f, 0.8f) },
    )

    // --- Cameras & security ------------------------------------------------

    /** camera: a body with a lens and a small viewfinder bump. */
    val Camera: ImageVector = lineIcon("r1_camera",
        {
            moveTo(4f, 8f)
            lineTo(8f, 8f)
            lineTo(9.5f, 6f)
            lineTo(14.5f, 6f)
            lineTo(16f, 8f)
            lineTo(20f, 8f)
            lineTo(20f, 18f)
            lineTo(4f, 18f)
            close()
        },
        { circle(12f, 13f, 3.2f) },
    )

    /** lock: a closed padlock (shackle + body). */
    val Lock: ImageVector = lineIcon("r1_lock",
        {
            moveTo(8f, 10f)
            lineTo(8f, 7.5f)
            arcTo(4f, 4f, 0f, false, true, 16f, 7.5f)
            lineTo(16f, 10f)
        },
        { rect(6f, 10f, 18f, 20f) },
        {
            moveTo(12f, 13.5f)
            lineTo(12f, 16.5f)
        },
    )

    /** alarm_control_panel: a shield. */
    val AlarmControlPanel: ImageVector = lineIcon("r1_alarm_control_panel",
        {
            moveTo(12f, 3.5f)
            lineTo(19f, 6f)
            lineTo(19f, 12f)
            curveTo(19f, 16.5f, 16f, 19.5f, 12f, 21f)
            curveTo(8f, 19.5f, 5f, 16.5f, 5f, 12f)
            lineTo(5f, 6f)
            close()
        },
    )

    /** siren: a horn/speaker emitting sound waves. */
    val Siren: ImageVector = lineIcon("r1_siren",
        {
            moveTo(4f, 10f)
            lineTo(8f, 10f)
            lineTo(13f, 6f)
            lineTo(13f, 18f)
            lineTo(8f, 14f)
            lineTo(4f, 14f)
            close()
        },
        {
            moveTo(16f, 9f)
            curveTo(18f, 11f, 18f, 13f, 16f, 15f)
            moveTo(18.5f, 7f)
            curveTo(21.5f, 10f, 21.5f, 14f, 18.5f, 17f)
        },
    )

    // --- Sensors -----------------------------------------------------------

    /** sensor: a gauge dial with a needle. */
    val Sensor: ImageVector = lineIcon("r1_sensor",
        {
            // half-dial arc
            moveTo(5f, 16f)
            arcTo(7f, 7f, 0f, true, true, 19f, 16f)
        },
        {
            // needle
            moveTo(12f, 16f)
            lineTo(15.5f, 11f)
        },
        { dot(12f, 16f, 0.9f) },
    )

    /** binary_sensor: a dashed/segmented ring suggesting on/off detection. */
    val BinarySensor: ImageVector = lineIcon("r1_binary_sensor",
        { circle(12f, 12f, 7f) },
        { dot(12f, 12f, 2.2f) },
    )

    /** motion: a walking figure with motion lines. */
    val Motion: ImageVector = lineIcon("r1_motion",
        { dot(13f, 5.5f, 1.4f) },
        {
            // torso + legs
            moveTo(13f, 7.5f)
            lineTo(12f, 13f)
            lineTo(9f, 19f)
            moveTo(12f, 13f)
            lineTo(15f, 17f)
            // arms
            moveTo(9f, 10f)
            lineTo(13f, 11f)
            lineTo(16.5f, 9f)
        },
        {
            // motion swooshes
            moveTo(4f, 9f)
            lineTo(6f, 9f)
            moveTo(3.5f, 12f)
            lineTo(6.5f, 12f)
        },
    )

    /** occupancy: a person inside a room outline. */
    val Occupancy: ImageVector = lineIcon("r1_occupancy",
        { rect(4f, 4f, 20f, 20f) },
        { dot(12f, 10f, 1.6f) },
        {
            moveTo(8.5f, 18f)
            curveTo(8.5f, 14f, 15.5f, 14f, 15.5f, 18f)
        },
    )

    /** smoke: a cloud/puff with rising wisps. */
    val Smoke: ImageVector = lineIcon("r1_smoke",
        {
            moveTo(7f, 18f)
            arcTo(3.2f, 3.2f, 0f, false, true, 7.6f, 12f)
            arcTo(3.6f, 3.6f, 0f, false, true, 14f, 10.5f)
            arcTo(3f, 3f, 0f, false, true, 17.5f, 14f)
            arcTo(2.6f, 2.6f, 0f, false, true, 17.6f, 18f)
            close()
        },
        {
            moveTo(9f, 8f)
            curveTo(8f, 7f, 10f, 6f, 9f, 5f)
            moveTo(14f, 8f)
            curveTo(13f, 7f, 15f, 6f, 14f, 5f)
        },
    )

    /** moisture: a droplet with a small base ripple. */
    val Moisture: ImageVector = lineIcon("r1_moisture",
        {
            moveTo(12f, 4f)
            curveTo(12f, 4f, 6.5f, 10.5f, 6.5f, 14.5f)
            arcTo(5.5f, 5.5f, 0f, true, false, 17.5f, 14.5f)
            curveTo(17.5f, 10.5f, 12f, 4f, 12f, 4f)
            close()
        },
        {
            moveTo(9.5f, 15.5f)
            curveTo(9.5f, 17.5f, 11f, 18f, 11f, 18f)
        },
    )

    /** pressure: a circular gauge (used for sensor device_class pressure). */
    val Pressure: ImageVector = lineIcon("r1_pressure",
        { circle(12f, 12f, 7.5f) },
        {
            moveTo(12f, 12f)
            lineTo(16f, 8.5f)
        },
        { dot(12f, 12f, 0.9f) },
    )

    /** illuminance: a small sun/aperture (sensor device_class illuminance). */
    val Illuminance: ImageVector = lineIcon("r1_illuminance",
        { circle(12f, 12f, 3f) },
        {
            moveTo(12f, 4f)
            lineTo(12f, 6.5f)
            moveTo(12f, 17.5f)
            lineTo(12f, 20f)
            moveTo(4f, 12f)
            lineTo(6.5f, 12f)
            moveTo(17.5f, 12f)
            lineTo(20f, 12f)
            moveTo(6.5f, 6.5f)
            lineTo(8.3f, 8.3f)
            moveTo(15.7f, 15.7f)
            lineTo(17.5f, 17.5f)
        },
    )

    // --- Power / energy ----------------------------------------------------

    /** power / energy: a lightning bolt (outline). */
    val Power: ImageVector = lineIcon("r1_power",
        {
            moveTo(13f, 3f)
            lineTo(6f, 13f)
            lineTo(11f, 13f)
            lineTo(10f, 21f)
            lineTo(18f, 10f)
            lineTo(13f, 10f)
            close()
        },
    )

    /** battery: a cell with a terminal nub and a charge tick. */
    val Battery: ImageVector = lineIcon("r1_battery",
        {
            moveTo(4f, 8f)
            lineTo(17f, 8f)
            lineTo(17f, 16f)
            lineTo(4f, 16f)
            close()
        },
        {
            moveTo(17f, 10.5f)
            lineTo(20f, 10.5f)
            lineTo(20f, 13.5f)
            lineTo(17f, 13.5f)
        },
        {
            moveTo(6.5f, 12f)
            lineTo(9.5f, 12f)
        },
    )

    // --- People & places ---------------------------------------------------

    /** person: a head and shoulders. */
    val Person: ImageVector = lineIcon("r1_person",
        { circle(12f, 8f, 3.2f) },
        {
            moveTo(5.5f, 20f)
            curveTo(5.5f, 14f, 18.5f, 14f, 18.5f, 20f)
        },
    )

    /** zone: a map pin with a hollow centre. */
    val Zone: ImageVector = lineIcon("r1_zone",
        {
            moveTo(12f, 21f)
            curveTo(7f, 15f, 5.5f, 12f, 5.5f, 9.5f)
            arcTo(6.5f, 6.5f, 0f, true, true, 18.5f, 9.5f)
            curveTo(18.5f, 12f, 17f, 15f, 12f, 21f)
            close()
        },
        { dot(12f, 9.5f, 1.4f) },
    )

    // --- Time & lists ------------------------------------------------------

    /** calendar: a grid header with a hanging-day dot. */
    val Calendar: ImageVector = lineIcon("r1_calendar",
        { rect(4f, 5f, 20f, 20f) },
        {
            moveTo(4f, 9f)
            lineTo(20f, 9f)
            moveTo(8f, 3.5f)
            lineTo(8f, 6.5f)
            moveTo(16f, 3.5f)
            lineTo(16f, 6.5f)
        },
        { dot(12f, 14f, 1f) },
    )

    /** todo: a clipboard list with a checkmark. */
    val Todo: ImageVector = lineIcon("r1_todo",
        { rect(6f, 5f, 18f, 20f) },
        {
            moveTo(9f, 9f)
            lineTo(15f, 9f)
            moveTo(9f, 13f)
            lineTo(15f, 13f)
            moveTo(9f, 17f)
            lineTo(13f, 17f)
        },
    )

    /** timer: a clock face with hands and a stem. */
    val Timer: ImageVector = lineIcon("r1_timer",
        { circle(12f, 13f, 7f) },
        {
            moveTo(12f, 13f)
            lineTo(12f, 9.5f)
            moveTo(12f, 13f)
            lineTo(14.5f, 14.5f)
            moveTo(10f, 3.5f)
            lineTo(14f, 3.5f)
            moveTo(12f, 3.5f)
            lineTo(12f, 6f)
        },
    )

    /** counter: a digit window with up/down chevrons. */
    val Counter: ImageVector = lineIcon("r1_counter",
        { rect(6f, 6f, 18f, 18f) },
        {
            moveTo(9f, 10f)
            lineTo(11f, 8f)
            lineTo(13f, 10f)
            moveTo(9f, 14f)
            lineTo(11f, 16f)
            lineTo(13f, 14f)
            moveTo(15f, 9f)
            lineTo(15f, 15f)
        },
    )

    // --- Helpers / config entities -----------------------------------------

    /** scene: overlapping play/star spark (a four-point sparkle). */
    val Scene: ImageVector = lineIcon("r1_scene",
        {
            moveTo(12f, 4f)
            lineTo(13.5f, 10.5f)
            lineTo(20f, 12f)
            lineTo(13.5f, 13.5f)
            lineTo(12f, 20f)
            lineTo(10.5f, 13.5f)
            lineTo(4f, 12f)
            lineTo(10.5f, 10.5f)
            close()
        },
    )

    /** script: a scroll/document with run lines. */
    val Script: ImageVector = lineIcon("r1_script",
        {
            moveTo(7f, 4.5f)
            lineTo(15f, 4.5f)
            lineTo(17f, 6.5f)
            lineTo(17f, 19.5f)
            lineTo(7f, 19.5f)
            close()
        },
        {
            moveTo(9.5f, 9f)
            lineTo(14.5f, 9f)
            moveTo(9.5f, 12f)
            lineTo(14.5f, 12f)
            moveTo(9.5f, 15f)
            lineTo(12.5f, 15f)
        },
    )

    /** automation: two interlocked gear-like arrows (a recycling/loop). */
    val Automation: ImageVector = lineIcon("r1_automation",
        {
            // top arc with arrowhead pointing right-down
            moveTo(6f, 9f)
            arcTo(6.5f, 6.5f, 0f, false, true, 17f, 7.5f)
            moveTo(17f, 7.5f)
            lineTo(17f, 4.5f)
            moveTo(17f, 7.5f)
            lineTo(14f, 7.5f)
        },
        {
            // bottom arc with arrowhead pointing left-up
            moveTo(18f, 15f)
            arcTo(6.5f, 6.5f, 0f, false, true, 7f, 16.5f)
            moveTo(7f, 16.5f)
            lineTo(7f, 19.5f)
            moveTo(7f, 16.5f)
            lineTo(10f, 16.5f)
        },
    )

    /** input_boolean (toggle): a pill toggle with the knob to the left (off-ish/neutral). */
    val InputBoolean: ImageVector = lineIcon("r1_input_boolean",
        {
            moveTo(8f, 8f)
            lineTo(16f, 8f)
            arcTo(4f, 4f, 0f, true, true, 16f, 16f)
            lineTo(8f, 16f)
            arcTo(4f, 4f, 0f, true, true, 8f, 8f)
            close()
        },
        { circle(8f, 12f, 2.2f) },
    )

    /** number: a slider track with a handle. */
    val Number: ImageVector = lineIcon("r1_number",
        {
            moveTo(4f, 12f)
            lineTo(20f, 12f)
        },
        { circle(14f, 12f, 2.6f) },
    )

    /** select (list): a dropdown chevron over stacked lines. */
    val Select: ImageVector = lineIcon("r1_select",
        {
            moveTo(5f, 7f)
            lineTo(15f, 7f)
            moveTo(5f, 12f)
            lineTo(13f, 12f)
            moveTo(5f, 17f)
            lineTo(11f, 17f)
        },
        {
            moveTo(16f, 13f)
            lineTo(18.5f, 16f)
            lineTo(21f, 13f)
        },
    )

    /** text: an underscored "A" / text-cursor field. */
    val Text: ImageVector = lineIcon("r1_text",
        {
            moveTo(7f, 6f)
            lineTo(17f, 6f)
            moveTo(12f, 6f)
            lineTo(12f, 16f)
            moveTo(8f, 19.5f)
            lineTo(16f, 19.5f)
        },
    )

    /** button: a rounded pressable with a centre dot. */
    val Button: ImageVector = lineIcon("r1_button",
        {
            moveTo(7f, 8f)
            lineTo(17f, 8f)
            arcTo(2.5f, 2.5f, 0f, false, true, 19.5f, 10.5f)
            lineTo(19.5f, 13.5f)
            arcTo(2.5f, 2.5f, 0f, false, true, 17f, 16f)
            lineTo(7f, 16f)
            arcTo(2.5f, 2.5f, 0f, false, true, 4.5f, 13.5f)
            lineTo(4.5f, 10.5f)
            arcTo(2.5f, 2.5f, 0f, false, true, 7f, 8f)
            close()
        },
        { dot(12f, 12f, 1.2f) },
    )

    /** update: a down-into-tray arrow (install/update). */
    val Update: ImageVector = lineIcon("r1_update",
        {
            moveTo(12f, 4f)
            lineTo(12f, 14f)
            moveTo(8f, 10f)
            lineTo(12f, 14f)
            lineTo(16f, 10f)
        },
        {
            moveTo(5f, 18f)
            lineTo(19f, 18f)
        },
    )

    // --- Devices -----------------------------------------------------------

    /** vacuum: a round robot with a bumper line. */
    val Vacuum: ImageVector = lineIcon("r1_vacuum",
        { circle(12f, 12f, 8f) },
        { circle(12f, 12f, 3f) },
        {
            moveTo(6f, 8f)
            arcTo(8f, 8f, 0f, false, true, 18f, 8f)
        },
    )

    /** valve: a butterfly valve (circle bisected with a handle). */
    val Valve: ImageVector = lineIcon("r1_valve",
        { circle(12f, 13f, 5.5f) },
        {
            moveTo(7f, 13f)
            lineTo(17f, 13f)
            moveTo(12f, 7.5f)
            lineTo(12f, 3.5f)
            moveTo(9.5f, 3.5f)
            lineTo(14.5f, 3.5f)
        },
    )

    /** lawn_mower: a wheeled deck with a handle. */
    val LawnMower: ImageVector = lineIcon("r1_lawn_mower",
        {
            moveTo(4f, 15f)
            lineTo(14f, 15f)
            lineTo(14f, 11f)
            lineTo(10f, 11f)
            moveTo(14f, 12f)
            lineTo(20f, 8f)
        },
        { circle(6f, 18f, 2f) },
        { circle(15f, 18f, 2f) },
    )

    // --- Generic fallback --------------------------------------------------

    /** generic fallback: a simple cube/box outline with a centre dot. */
    val Generic: ImageVector = lineIcon("r1_generic",
        { rect(5f, 5f, 19f, 19f) },
        { dot(12f, 12f, 1.3f) },
    )
}
