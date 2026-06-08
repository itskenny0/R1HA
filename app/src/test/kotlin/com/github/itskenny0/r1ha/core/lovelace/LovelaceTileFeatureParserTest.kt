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
        assertThat(vs.showMute).isFalse()
    }
}
