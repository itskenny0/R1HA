package com.github.itskenny0.r1ha.core.lovelace

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class LovelaceTileFeatureParserTest {

    private fun tile(raw: String): LovelaceCard.Tile =
        LovelaceParser.parseCard(Json.parseToJsonElement(raw) as JsonObject) as LovelaceCard.Tile

    @Test fun `parses media playback feature with explicit controls`() {
        val t = tile(
            """
            {"type":"tile","entity":"media_player.living","features":[
              {"type":"media-player-playback","controls":["media_play_pause","shuffle","repeat"]}
            ]}
            """.trimIndent(),
        )
        val f = t.features.single() as LovelaceTileFeature.MediaPlayback
        assertThat(f.controls).containsExactly("media_play_pause", "shuffle", "repeat").inOrder()
    }

    @Test fun `parses media source sound-mode and volume features`() {
        val t = tile(
            """
            {"type":"tile","entity":"media_player.living","features":[
              {"type":"media-player-source","sources":["Spotify","HDMI 1"]},
              {"type":"media-player-sound-mode"},
              {"type":"media-player-volume-buttons","step":10,"show_mute_button":true},
              {"type":"media-player-volume-slider"}
            ]}
            """.trimIndent(),
        )
        val src = t.features[0] as LovelaceTileFeature.MediaSource
        assertThat(src.sources).containsExactly("Spotify", "HDMI 1").inOrder()
        assertThat(t.features[1]).isInstanceOf(LovelaceTileFeature.MediaSoundMode::class.java)
        val vb = t.features[2] as LovelaceTileFeature.MediaVolumeButtons
        assertThat(vb.step).isEqualTo(10)
        assertThat(vb.showMute).isTrue()
        val vs = t.features[3] as LovelaceTileFeature.MediaVolumeSlider
        // show_mute_button was not set in the config, so it defaults to true (HA's default).
        assertThat(vs.showMute).isTrue()
    }

    @Test fun `parses weather forecast features with options`() {
        val t = tile(
            """
            {"type":"tile","entity":"weather.home","features":[
              {"type":"temperature-forecast","forecast_type":"daily","show_labels":true},
              {"type":"precipitation-forecast","forecast_type":"hourly","precipitation_type":"probability"}
            ]}
            """.trimIndent(),
        )
        val tf = t.features[0] as LovelaceTileFeature.TemperatureForecast
        assertThat(tf.forecastType).isEqualTo("daily")
        assertThat(tf.showLabels).isTrue()
        val pf = t.features[1] as LovelaceTileFeature.PrecipitationForecast
        assertThat(pf.forecastType).isEqualTo("hourly")
        assertThat(pf.precipitationType).isEqualTo("probability")
    }

    @Test fun `parses the registry-favorite feature types`() {
        val t = tile(
            """
            {"type":"tile","entity":"cover.blind","features":[
              {"type":"cover-position-favorite"},
              {"type":"cover-tilt-favorite"},
              {"type":"valve-position-favorite"},
              {"type":"light-color-favorites"}
            ]}
            """.trimIndent(),
        )
        assertThat(t.features[0]).isEqualTo(LovelaceTileFeature.CoverPositionFavorite)
        assertThat(t.features[1]).isEqualTo(LovelaceTileFeature.CoverTiltFavorite)
        assertThat(t.features[2]).isEqualTo(LovelaceTileFeature.ValvePositionFavorite)
        assertThat(t.features[3]).isEqualTo(LovelaceTileFeature.LightColorFavorites)
    }

    @Test fun `parses area-controls with mixed domain and entity controls`() {
        val t = tile(
            """
            {"type":"tile","entity":"light.a","features":[
              {"type":"area-controls","controls":["light","cover-shutter",{"entity_id":"switch.s"}]}
            ]}
            """.trimIndent(),
        )
        val f = t.features.single() as LovelaceTileFeature.AreaControls
        assertThat(f.controls).containsExactly("light", "cover-shutter", "switch.s").inOrder()
    }

    @Test fun `area-controls with no controls list parses to an empty list`() {
        val t = tile(
            """
            {"type":"tile","entity":"light.a","features":[{"type":"area-controls"}]}
            """.trimIndent(),
        )
        val f = t.features.single() as LovelaceTileFeature.AreaControls
        assertThat(f.controls).isEmpty()
    }

    @Test fun `parses button feature with and without action_name`() {
        val t = tile(
            """
            {"type":"tile","entity":"button.doorbell","features":[
              {"type":"button","action_name":"Ring"},
              {"type":"button"}
            ]}
            """.trimIndent(),
        )
        val named = t.features[0] as LovelaceTileFeature.ButtonFeature
        assertThat(named.actionName).isEqualTo("Ring")
        val unnamed = t.features[1] as LovelaceTileFeature.ButtonFeature
        assertThat(unnamed.actionName).isNull()
    }

    @Test fun `parses update-actions with string backup options`() {
        val yes = tile("""{"type":"tile","entity":"update.x","features":[{"type":"update-actions","backup":"yes"}]}""")
        assertThat((yes.features.single() as LovelaceTileFeature.UpdateActions).backup).isEqualTo("yes")
        val ask = tile("""{"type":"tile","entity":"update.x","features":[{"type":"update-actions","backup":"ask"}]}""")
        assertThat((ask.features.single() as LovelaceTileFeature.UpdateActions).backup).isEqualTo("ask")
        val no = tile("""{"type":"tile","entity":"update.x","features":[{"type":"update-actions"}]}""")
        assertThat((no.features.single() as LovelaceTileFeature.UpdateActions).backup).isEqualTo("no")
        // Legacy boolean true is coerced to "yes".
        val legacyTrue = tile("""{"type":"tile","entity":"update.x","features":[{"type":"update-actions","backup":true}]}""")
        assertThat((legacyTrue.features.single() as LovelaceTileFeature.UpdateActions).backup).isEqualTo("yes")
    }

    @Test fun `parses lawn-mower-commands with optional commands list`() {
        val all = tile("""{"type":"tile","entity":"lawn_mower.robot","features":[{"type":"lawn-mower-commands"}]}""")
        assertThat((all.features.single() as LovelaceTileFeature.LawnMowerCommands).commands).isEmpty()
        val filtered = tile(
            """
            {"type":"tile","entity":"lawn_mower.robot","features":[
              {"type":"lawn-mower-commands","commands":["dock"]}
            ]}
            """.trimIndent(),
        )
        assertThat((filtered.features.single() as LovelaceTileFeature.LawnMowerCommands).commands).containsExactly("dock")
    }

    @Test fun `show_mute_button defaults to true when absent`() {
        val t = tile(
            """
            {"type":"tile","entity":"media_player.x","features":[
              {"type":"media-player-volume-buttons"},
              {"type":"media-player-volume-slider"}
            ]}
            """.trimIndent(),
        )
        assertThat((t.features[0] as LovelaceTileFeature.MediaVolumeButtons).showMute).isTrue()
        assertThat((t.features[1] as LovelaceTileFeature.MediaVolumeSlider).showMute).isTrue()
    }

    @Test fun `show_mute_button explicit false overrides the default`() {
        val t = tile(
            """
            {"type":"tile","entity":"media_player.x","features":[
              {"type":"media-player-volume-buttons","show_mute_button":false}
            ]}
            """.trimIndent(),
        )
        assertThat((t.features.single() as LovelaceTileFeature.MediaVolumeButtons).showMute).isFalse()
    }
}
