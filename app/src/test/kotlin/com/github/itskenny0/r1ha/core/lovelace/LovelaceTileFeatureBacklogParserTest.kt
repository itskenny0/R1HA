package com.github.itskenny0.r1ha.core.lovelace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests for the new tile-feature types added in the backlog batch.
 * Each test asserts that the `type:` string parses to the expected sealed-class
 * variant and that any config list (modes / commands) is parsed correctly.
 */
class LovelaceTileFeatureBacklogParserTest {

    private fun tile(featuresJson: String): List<LovelaceTileFeature> {
        val raw = """{"type":"tile","entity":"sensor.x","features":[$featuresJson]}"""
        val card = LovelaceParser.parseCard(Json.parseToJsonElement(raw) as JsonObject)
        return (card as LovelaceCard.Tile).features
    }

    private fun single(featureJson: String): LovelaceTileFeature =
        tile(featureJson).first()

    // ── Climate mode-pickers ─────────────────────────────────────────────────

    @Test fun `parses climate-fan-modes without filter`() {
        val f = single("""{"type":"climate-fan-modes"}""")
        assertTrue(f is LovelaceTileFeature.ClimateFanModes)
        assertEquals(emptyList<String>(), (f as LovelaceTileFeature.ClimateFanModes).fanModes)
    }

    @Test fun `parses climate-fan-modes with filter list`() {
        val f = single("""{"type":"climate-fan-modes","fan_modes":["auto","low"]}""")
        assertEquals(listOf("auto", "low"), (f as LovelaceTileFeature.ClimateFanModes).fanModes)
    }

    @Test fun `parses climate-preset-modes`() {
        val f = single("""{"type":"climate-preset-modes","preset_modes":["eco","boost"]}""")
        assertEquals(listOf("eco", "boost"), (f as LovelaceTileFeature.ClimatePresetModes).presetModes)
    }

    @Test fun `parses climate-swing-modes`() {
        val f = single("""{"type":"climate-swing-modes","swing_modes":["off","both"]}""")
        assertEquals(listOf("off", "both"), (f as LovelaceTileFeature.ClimateSwingModes).swingModes)
    }

    @Test fun `parses climate-swing-horizontal-modes`() {
        val f = single("""{"type":"climate-swing-horizontal-modes"}""")
        assertTrue(f is LovelaceTileFeature.ClimateSwingHorizontalModes)
        assertEquals(emptyList<String>(), (f as LovelaceTileFeature.ClimateSwingHorizontalModes).swingModes)
    }

    @Test fun `parses fan-preset-modes`() {
        val f = single("""{"type":"fan-preset-modes","preset_modes":["Smart","Sleep"]}""")
        assertEquals(listOf("Smart", "Sleep"), (f as LovelaceTileFeature.FanPresetModes).presetModes)
    }

    @Test fun `parses humidifier-modes`() {
        val f = single("""{"type":"humidifier-modes","modes":["normal","eco"]}""")
        assertEquals(listOf("normal", "eco"), (f as LovelaceTileFeature.HumidifierModes).modes)
    }

    @Test fun `parses water-heater-operation-modes`() {
        val f = single("""{"type":"water-heater-operation-modes","operation_modes":["eco","electric"]}""")
        assertEquals(listOf("eco", "electric"), (f as LovelaceTileFeature.WaterHeaterOperationModes).operationModes)
    }

    // ── Toggle / button-row features ─────────────────────────────────────────

    @Test fun `parses fan-direction`() {
        val f = single("""{"type":"fan-direction"}""")
        assertTrue(f is LovelaceTileFeature.FanDirection)
    }

    @Test fun `parses fan-oscillate`() {
        val f = single("""{"type":"fan-oscillate"}""")
        assertTrue(f is LovelaceTileFeature.FanOscillate)
    }

    @Test fun `parses humidifier-toggle`() {
        val f = single("""{"type":"humidifier-toggle"}""")
        assertTrue(f is LovelaceTileFeature.HumidifierToggle)
    }

    @Test fun `parses lawn-mower-commands`() {
        val f = single("""{"type":"lawn-mower-commands"}""")
        assertTrue(f is LovelaceTileFeature.LawnMowerCommands)
        assertEquals(emptyList<String>(), (f as LovelaceTileFeature.LawnMowerCommands).commands)
    }

    @Test fun `parses vacuum-commands without filter`() {
        val f = single("""{"type":"vacuum-commands"}""")
        assertTrue(f is LovelaceTileFeature.VacuumCommands)
        assertEquals(emptyList<String>(), (f as LovelaceTileFeature.VacuumCommands).commands)
    }

    @Test fun `parses vacuum-commands with filter`() {
        val f = single("""{"type":"vacuum-commands","commands":["start","pause"]}""")
        assertEquals(listOf("start", "pause"), (f as LovelaceTileFeature.VacuumCommands).commands)
    }

    @Test fun `parses cover-tilt`() {
        val f = single("""{"type":"cover-tilt"}""")
        assertTrue(f is LovelaceTileFeature.CoverTilt)
    }

    @Test fun `parses valve-open-close`() {
        val f = single("""{"type":"valve-open-close"}""")
        assertTrue(f is LovelaceTileFeature.ValveOpenClose)
    }

    @Test fun `parses lock-open-door`() {
        val f = single("""{"type":"lock-open-door"}""")
        assertTrue(f is LovelaceTileFeature.LockOpenDoor)
    }

    @Test fun `parses counter-actions without filter`() {
        val f = single("""{"type":"counter-actions"}""")
        assertTrue(f is LovelaceTileFeature.CounterActions)
        assertEquals(emptyList<String>(), (f as LovelaceTileFeature.CounterActions).actions)
    }

    @Test fun `parses counter-actions with filter`() {
        val f = single("""{"type":"counter-actions","actions":["increment","reset"]}""")
        assertEquals(listOf("increment", "reset"), (f as LovelaceTileFeature.CounterActions).actions)
    }

    @Test fun `parses update-actions without backup defaults to no`() {
        val f = single("""{"type":"update-actions"}""")
        assertTrue(f is LovelaceTileFeature.UpdateActions)
        assertEquals("no", (f as LovelaceTileFeature.UpdateActions).backup)
    }

    @Test fun `parses update-actions with backup string options`() {
        assertEquals("yes", (single("""{"type":"update-actions","backup":"yes"}""") as LovelaceTileFeature.UpdateActions).backup)
        assertEquals("ask", (single("""{"type":"update-actions","backup":"ask"}""") as LovelaceTileFeature.UpdateActions).backup)
        // Legacy boolean true coerces to "yes".
        assertEquals("yes", (single("""{"type":"update-actions","backup":true}""") as LovelaceTileFeature.UpdateActions).backup)
    }

    // ── Scalar-stepper features ──────────────────────────────────────────────

    @Test fun `parses cover-tilt-position`() {
        val f = single("""{"type":"cover-tilt-position"}""")
        assertTrue(f is LovelaceTileFeature.CoverTiltPosition)
    }

    @Test fun `parses valve-position`() {
        val f = single("""{"type":"valve-position"}""")
        assertTrue(f is LovelaceTileFeature.ValvePosition)
    }

    @Test fun `parses target-humidity`() {
        val f = single("""{"type":"target-humidity"}""")
        assertTrue(f is LovelaceTileFeature.TargetHumidity)
    }

    // ── Arbitrary-range stepper features ────────────────────────────────────

    @Test fun `parses numeric-input`() {
        val f = single("""{"type":"numeric-input"}""")
        assertTrue(f is LovelaceTileFeature.NumericInput)
    }

    @Test fun `parses light-color-temp`() {
        val f = single("""{"type":"light-color-temp"}""")
        assertTrue(f is LovelaceTileFeature.LightColorTemp)
    }

    // ── Multi-feature tile parses all in one shot ────────────────────────────

    @Test fun `parses all new features in one tile`() {
        val features = tile(
            """
            {"type":"climate-fan-modes"},
            {"type":"climate-preset-modes"},
            {"type":"climate-swing-modes"},
            {"type":"climate-swing-horizontal-modes"},
            {"type":"fan-preset-modes"},
            {"type":"humidifier-modes"},
            {"type":"water-heater-operation-modes"},
            {"type":"fan-direction"},
            {"type":"fan-oscillate"},
            {"type":"humidifier-toggle"},
            {"type":"lawn-mower-commands"},
            {"type":"vacuum-commands"},
            {"type":"cover-tilt"},
            {"type":"valve-open-close"},
            {"type":"lock-open-door"},
            {"type":"counter-actions"},
            {"type":"update-actions"},
            {"type":"cover-tilt-position"},
            {"type":"valve-position"},
            {"type":"target-humidity"},
            {"type":"numeric-input"},
            {"type":"light-color-temp"}
            """.trimIndent(),
        )
        assertEquals(22, features.size)
        assertTrue(features[0] is LovelaceTileFeature.ClimateFanModes)
        assertTrue(features[1] is LovelaceTileFeature.ClimatePresetModes)
        assertTrue(features[2] is LovelaceTileFeature.ClimateSwingModes)
        assertTrue(features[3] is LovelaceTileFeature.ClimateSwingHorizontalModes)
        assertTrue(features[4] is LovelaceTileFeature.FanPresetModes)
        assertTrue(features[5] is LovelaceTileFeature.HumidifierModes)
        assertTrue(features[6] is LovelaceTileFeature.WaterHeaterOperationModes)
        assertTrue(features[7] is LovelaceTileFeature.FanDirection)
        assertTrue(features[8] is LovelaceTileFeature.FanOscillate)
        assertTrue(features[9] is LovelaceTileFeature.HumidifierToggle)
        assertTrue(features[10] is LovelaceTileFeature.LawnMowerCommands)
        assertTrue(features[11] is LovelaceTileFeature.VacuumCommands)
        assertTrue(features[12] is LovelaceTileFeature.CoverTilt)
        assertTrue(features[13] is LovelaceTileFeature.ValveOpenClose)
        assertTrue(features[14] is LovelaceTileFeature.LockOpenDoor)
        assertTrue(features[15] is LovelaceTileFeature.CounterActions)
        assertTrue(features[16] is LovelaceTileFeature.UpdateActions)
        assertTrue(features[17] is LovelaceTileFeature.CoverTiltPosition)
        assertTrue(features[18] is LovelaceTileFeature.ValvePosition)
        assertTrue(features[19] is LovelaceTileFeature.TargetHumidity)
        assertTrue(features[20] is LovelaceTileFeature.NumericInput)
        assertTrue(features[21] is LovelaceTileFeature.LightColorTemp)
    }
}
