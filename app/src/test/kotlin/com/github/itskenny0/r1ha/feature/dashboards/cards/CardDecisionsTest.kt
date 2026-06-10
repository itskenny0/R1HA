package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import java.time.Instant

/**
 * Pure decision-logic coverage for Batch I3's tile / thermostat / media-control
 * / toggle-group renderers: icon-action default, status-badge selection, icon
 * pulse, media control-set computation per state + feature bits, media
 * description by content type, toggle-group aggregate label + service, and
 * dual-setpoint clamping.
 */
class CardDecisionsTest {

    private fun state(
        id: String,
        raw: String = "on",
        on: Boolean = true,
        available: Boolean = true,
        attrs: JsonObject = JsonObject(emptyMap()),
        mediaSf: Int = 0,
        hvacAction: String? = null,
        artist: String? = null,
    ): EntityState = EntityState(
        id = EntityId(id),
        friendlyName = id,
        area = null,
        isOn = on,
        percent = null,
        raw = null,
        lastChanged = Instant.EPOCH,
        isAvailable = available,
        rawState = raw,
        attributesJson = attrs,
        mediaSupportedFeatures = mediaSf,
        climateHvacAction = hvacAction,
        mediaArtist = artist,
    )

    // ── Tile icon-action default ─────────────────────────────────────────────

    @Test fun `tile icon action toggles for toggleable domains, presses for buttons, else none`() {
        // HA's DOMAINS_TOGGLE (const.ts) toggle on icon-tap.
        assertThat(getEntityDefaultTileIconAction("light.x")).isEqualTo("toggle")
        assertThat(getEntityDefaultTileIconAction("switch.x")).isEqualTo("toggle")
        assertThat(getEntityDefaultTileIconAction("fan.f")).isEqualTo("toggle")
        assertThat(getEntityDefaultTileIconAction("valve.v")).isEqualTo("toggle")
        // button / input_button / scene press on icon-tap.
        assertThat(getEntityDefaultTileIconAction("button.b")).isEqualTo("toggle")
        assertThat(getEntityDefaultTileIconAction("input_button.b")).isEqualTo("toggle")
        assertThat(getEntityDefaultTileIconAction("scene.s")).isEqualTo("toggle")
        // Domains outside HA's DOMAINS_TOGGLE have no icon action (body still
        // more-infos): cover / lock / media_player / climate / sensors.
        assertThat(getEntityDefaultTileIconAction("cover.c")).isEqualTo("none")
        assertThat(getEntityDefaultTileIconAction("lock.l")).isEqualTo("none")
        assertThat(getEntityDefaultTileIconAction("sensor.temp")).isEqualTo("none")
        assertThat(getEntityDefaultTileIconAction("binary_sensor.m")).isEqualTo("none")
    }

    // ── Tile status badge selection ──────────────────────────────────────────

    @Test fun `unavailable entity gets the warning badge regardless of domain`() {
        val badge = tileBadgeFor("light.x", state("light.x", raw = "unavailable", available = false))
        assertThat(badge).isEqualTo(TileBadge.Unavailable)
    }

    @Test fun `unknown entity gets no badge`() {
        assertThat(tileBadgeFor("light.x", state("light.x", raw = "unknown"))).isNull()
    }

    @Test fun `person badge reflects home and away`() {
        assertThat(tileBadgeFor("person.me", state("person.me", raw = "home")))
            .isEqualTo(TileBadge.Person(home = true, away = false))
        assertThat(tileBadgeFor("device_tracker.phone", state("device_tracker.phone", raw = "not_home")))
            .isEqualTo(TileBadge.Person(home = false, away = true))
        // A named zone is neither home nor not_home but still a person badge.
        assertThat(tileBadgeFor("person.me", state("person.me", raw = "Work")))
            .isEqualTo(TileBadge.Person(home = false, away = false))
    }

    @Test fun `climate badge only for an active hvac action`() {
        assertThat(tileBadgeFor("climate.t", state("climate.t", hvacAction = "heating")))
            .isEqualTo(TileBadge.Climate("heating"))
        assertThat(tileBadgeFor("climate.t", state("climate.t", hvacAction = "off"))).isNull()
        assertThat(tileBadgeFor("climate.t", state("climate.t", hvacAction = null))).isNull()
    }

    @Test fun `humidifier badge reads the action attribute`() {
        val attrs = buildJsonObject { put("action", JsonPrimitive("humidifying")) }
        assertThat(tileBadgeFor("humidifier.h", state("humidifier.h", attrs = attrs)))
            .isEqualTo(TileBadge.Humidifier("humidifying"))
        val off = buildJsonObject { put("action", JsonPrimitive("off")) }
        assertThat(tileBadgeFor("humidifier.h", state("humidifier.h", attrs = off))).isNull()
    }

    // ── Tile icon pulse ──────────────────────────────────────────────────────

    @Test fun `icon pulses for alarm transitional states and jammed lock`() {
        assertThat(tileIconPulses("alarm_control_panel.a", state("alarm_control_panel.a", raw = "arming"))).isTrue()
        assertThat(tileIconPulses("alarm_control_panel.a", state("alarm_control_panel.a", raw = "pending"))).isTrue()
        assertThat(tileIconPulses("alarm_control_panel.a", state("alarm_control_panel.a", raw = "triggered"))).isTrue()
        assertThat(tileIconPulses("alarm_control_panel.a", state("alarm_control_panel.a", raw = "armed_away"))).isFalse()
        assertThat(tileIconPulses("lock.l", state("lock.l", raw = "jammed"))).isTrue()
        assertThat(tileIconPulses("lock.l", state("lock.l", raw = "locked"))).isFalse()
        assertThat(tileIconPulses("light.x", state("light.x"))).isFalse()
    }

    // ── Media control-set computation ────────────────────────────────────────

    private val F = EntityState.MediaPlayerFeature

    @Test fun `unavailable media player has no controls`() {
        val s = state("media_player.m", raw = "unavailable", available = false, on = false)
        assertThat(computeMediaControls(s)).isEmpty()
    }

    @Test fun `inactive player shows only turn-on when supported`() {
        val withOn = state("media_player.m", raw = "off", on = false, mediaSf = F.TURN_ON)
        assertThat(computeMediaControls(withOn).map { it.action }).containsExactly("turn_on")
        val withoutOn = state("media_player.m", raw = "off", on = false, mediaSf = 0)
        assertThat(computeMediaControls(withoutOn)).isEmpty()
    }

    @Test fun `playing player shows prev, pause, next per supported features`() {
        val sf = F.PREVIOUS_TRACK or F.NEXT_TRACK or F.PAUSE or F.PLAY
        val s = state("media_player.m", raw = "playing", on = true, mediaSf = sf)
        val actions = computeMediaControls(s)
        assertThat(actions.map { it.action })
            .containsExactly("media_previous_track", "media_pause", "media_next_track").inOrder()
        // Central control is the primary one.
        assertThat(actions.single { it.primary }.action).isEqualTo("media_pause")
    }

    @Test fun `paused player offers play, playing-without-pause falls back to stop`() {
        val paused = state("media_player.m", raw = "paused", on = true, mediaSf = F.PLAY)
        assertThat(computeMediaControls(paused).single { it.primary }.action).isEqualTo("media_play")
        // Playing, supports STOP but not PAUSE: central is stop.
        val playingStop = state("media_player.m", raw = "playing", on = true, mediaSf = F.STOP)
        assertThat(computeMediaControls(playingStop).single { it.primary }.action).isEqualTo("media_stop")
    }

    @Test fun `turn-off button shown when feature present`() {
        val sf = F.TURN_OFF or F.PLAY
        val s = state("media_player.m", raw = "paused", on = true, mediaSf = sf)
        assertThat(computeMediaControls(s).map { it.action }).contains("turn_off")
    }

    // ── Media description ────────────────────────────────────────────────────

    @Test fun `music description is the artist`() {
        val attrs = buildJsonObject {
            put("media_content_type", JsonPrimitive("music"))
            put("media_artist", JsonPrimitive("Boards of Canada"))
        }
        assertThat(computeMediaDescription(state("media_player.m", attrs = attrs)))
            .isEqualTo("Boards of Canada")
    }

    @Test fun `tvshow description joins series, season and episode`() {
        val attrs = buildJsonObject {
            put("media_content_type", JsonPrimitive("tvshow"))
            put("media_series_title", JsonPrimitive("Severance"))
            put("media_season", JsonPrimitive("2"))
            put("media_episode", JsonPrimitive("5"))
        }
        assertThat(computeMediaDescription(state("media_player.m", attrs = attrs)))
            .isEqualTo("Severance S2E5")
    }

    @Test fun `channel description is the channel, default falls back to app name`() {
        val ch = buildJsonObject {
            put("media_content_type", JsonPrimitive("channel"))
            put("media_channel", JsonPrimitive("BBC One"))
        }
        assertThat(computeMediaDescription(state("media_player.m", attrs = ch))).isEqualTo("BBC One")
        val app = buildJsonObject { put("app_name", JsonPrimitive("Spotify")) }
        assertThat(computeMediaDescription(state("media_player.m", attrs = app))).isEqualTo("Spotify")
    }

    // ── Toggle-group label + service ─────────────────────────────────────────

    @Test fun `toggle-group label summarises the on-count`() {
        assertThat(toggleGroupLabel(0, 3)).isEqualTo("All off")
        assertThat(toggleGroupLabel(3, 3)).isEqualTo("All on")
        assertThat(toggleGroupLabel(2, 3)).isEqualTo("2 on")
        assertThat(toggleGroupLabel(0, 0)).isEqualTo("")
    }

    @Test fun `toggle-group service flips the set, cover uses open-close`() {
        assertThat(toggleGroupService("light", anyOn = true)).isEqualTo("light" to "turn_off")
        assertThat(toggleGroupService("light", anyOn = false)).isEqualTo("light" to "turn_on")
        assertThat(toggleGroupService("cover", anyOn = true)).isEqualTo("cover" to "close_cover")
        assertThat(toggleGroupService("cover", anyOn = false)).isEqualTo("cover" to "open_cover")
    }

    // ── Dual-setpoint clamp ──────────────────────────────────────────────────

    @Test fun `dual setpoint nudges the chosen bound without crossing`() {
        // Raise low toward high, clamped at high.
        assertThat(nudgeDualSetpoint(low = 19.0, high = 20.0, editingLow = true, direction = +1, step = 0.5, min = 15.0, max = 30.0))
            .isEqualTo(19.5)
        // Low can't exceed high.
        assertThat(nudgeDualSetpoint(low = 20.0, high = 20.0, editingLow = true, direction = +1, step = 0.5, min = 15.0, max = 30.0))
            .isEqualTo(20.0)
        // High can't drop below low.
        assertThat(nudgeDualSetpoint(low = 20.0, high = 20.0, editingLow = false, direction = -1, step = 0.5, min = 15.0, max = 30.0))
            .isEqualTo(20.0)
        // Low clamped at configured min.
        assertThat(nudgeDualSetpoint(low = 15.0, high = 25.0, editingLow = true, direction = -1, step = 1.0, min = 15.0, max = 30.0))
            .isEqualTo(15.0)
    }
}
