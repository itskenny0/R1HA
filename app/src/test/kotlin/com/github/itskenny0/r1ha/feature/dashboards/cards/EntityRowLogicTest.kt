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
 * Pure decision-logic coverage for the entities-card interactive rows: row-kind
 * dispatch, cover canOpen/canClose + tilt-only, media control set per
 * supported_features, update state line, script run-state, lock code
 * requirement, group recursive toggleability, and number slider-vs-stepper.
 */
class EntityRowLogicTest {

    private fun state(
        id: String,
        raw: String = "on",
        on: Boolean = true,
        available: Boolean = true,
        attrs: JsonObject = JsonObject(emptyMap()),
        mediaSf: Int = 0,
        percent: Int? = null,
        codeFormat: String? = null,
        targetTemp: Double? = null,
        minTemp: Double? = null,
        maxTemp: Double? = null,
        selectOptions: List<String> = emptyList(),
        currentOption: String? = null,
    ): EntityState = EntityState(
        id = EntityId(id),
        friendlyName = id,
        area = null,
        isOn = on,
        percent = percent,
        raw = null,
        lastChanged = Instant.EPOCH,
        isAvailable = available,
        rawState = raw,
        attributesJson = attrs,
        mediaSupportedFeatures = mediaSf,
        lockCodeFormat = codeFormat,
        climateTargetTemperature = targetTemp,
        climateMinTemp = minTemp,
        climateMaxTemp = maxTemp,
        selectOptions = selectOptions,
        currentOption = currentOption,
    )

    private fun sf(value: Int): JsonObject = buildJsonObject { put("supported_features", JsonPrimitive(value)) }

    // ── row-kind dispatch ───────────────────────────────────────────────────

    @Test fun `rowKind maps domains to their interactive rows`() {
        assertThat(rowKindFor("climate.t")).isEqualTo(RowKind.Climate)
        assertThat(rowKindFor("cover.c")).isEqualTo(RowKind.Cover)
        assertThat(rowKindFor("media_player.m")).isEqualTo(RowKind.MediaPlayer)
        assertThat(rowKindFor("lock.l")).isEqualTo(RowKind.Lock)
        assertThat(rowKindFor("script.s")).isEqualTo(RowKind.Script)
        assertThat(rowKindFor("update.u")).isEqualTo(RowKind.Update)
        assertThat(rowKindFor("light.x")).isEqualTo(RowKind.Toggle)
        assertThat(rowKindFor("switch.x")).isEqualTo(RowKind.Toggle)
        assertThat(rowKindFor("number.n")).isEqualTo(RowKind.Number)
        assertThat(rowKindFor("input_number.n")).isEqualTo(RowKind.InputNumber)
        assertThat(rowKindFor("select.s")).isEqualTo(RowKind.Select)
        // event / weather / timer route to their dedicated read-only display rows.
        assertThat(rowKindFor("event.doorbell")).isEqualTo(RowKind.Event)
        assertThat(rowKindFor("weather.home")).isEqualTo(RowKind.Weather)
        assertThat(rowKindFor("timer.laundry")).isEqualTo(RowKind.Timer)
        // remaining sensor-style domains fall through to the generic display row.
        assertThat(rowKindFor("sensor.temp")).isEqualTo(RowKind.Display)
        // text and the standalone date / time / datetime domains are editable,
        // sharing the input_text / input_datetime rows (service routed by domain).
        assertThat(rowKindFor("text.x")).isEqualTo(RowKind.InputText)
        assertThat(rowKindFor("date.x")).isEqualTo(RowKind.InputDatetime)
        assertThat(rowKindFor("time.x")).isEqualTo(RowKind.InputDatetime)
        assertThat(rowKindFor("datetime.x")).isEqualTo(RowKind.InputDatetime)
    }

    // ── cover gating ────────────────────────────────────────────────────────

    @Test fun `cover with open+close+stop is not tilt-only`() {
        val s = state("cover.c", attrs = sf(CoverBit.OPEN or CoverBit.CLOSE or CoverBit.STOP))
        assertThat(coverIsTiltOnly(s)).isFalse()
        assertThat(coverHasStop(s)).isTrue()
    }

    @Test fun `cover with only tilt bits is tilt-only`() {
        val s = state("cover.c", attrs = sf(CoverBit.OPEN_TILT or CoverBit.CLOSE_TILT))
        assertThat(coverIsTiltOnly(s)).isTrue()
    }

    @Test fun `fully open cover cannot open and can close`() {
        val attrs = buildJsonObject {
            put("supported_features", JsonPrimitive(CoverBit.OPEN or CoverBit.CLOSE))
            put("current_position", JsonPrimitive(100))
        }
        val s = state("cover.c", raw = "open", attrs = attrs)
        assertThat(coverCanOpen(s)).isFalse()
        assertThat(coverCanClose(s)).isTrue()
    }

    @Test fun `fully closed cover cannot close and can open`() {
        val attrs = buildJsonObject {
            put("supported_features", JsonPrimitive(CoverBit.OPEN or CoverBit.CLOSE))
            put("current_position", JsonPrimitive(0))
        }
        val s = state("cover.c", raw = "closed", attrs = attrs)
        assertThat(coverCanClose(s)).isFalse()
        assertThat(coverCanOpen(s)).isTrue()
    }

    @Test fun `opening cover cannot open`() {
        val s = state("cover.c", raw = "opening", attrs = sf(CoverBit.OPEN or CoverBit.CLOSE))
        assertThat(coverCanOpen(s)).isFalse()
    }

    @Test fun `assumed-state cover can always open and close`() {
        val attrs = buildJsonObject {
            put("supported_features", JsonPrimitive(CoverBit.OPEN or CoverBit.CLOSE))
            put("assumed_state", JsonPrimitive(true))
            put("current_position", JsonPrimitive(100))
        }
        val s = state("cover.c", raw = "open", attrs = attrs)
        assertThat(coverCanOpen(s)).isTrue()
        assertThat(coverCanClose(s)).isTrue()
    }

    @Test fun `unavailable cover can neither open nor close`() {
        val s = state("cover.c", raw = "unavailable", available = false, attrs = sf(CoverBit.OPEN or CoverBit.CLOSE))
        assertThat(coverCanOpen(s)).isFalse()
        assertThat(coverCanClose(s)).isFalse()
    }

    // ── media-player control set ────────────────────────────────────────────

    @Test fun `playing player shows previous play-pause next`() {
        val mp = EntityState.MediaPlayerFeature
        val s = state(
            "media_player.m",
            raw = "playing",
            mediaSf = mp.PREVIOUS_TRACK or mp.NEXT_TRACK or mp.PAUSE or mp.PLAY,
        )
        assertThat(mediaControlSet(s)).containsExactly(
            MediaControl.PREVIOUS, MediaControl.PLAY_PAUSE, MediaControl.NEXT,
        ).inOrder()
    }

    @Test fun `off player with power support shows only power-on`() {
        val mp = EntityState.MediaPlayerFeature
        val s = state("media_player.m", raw = "off", on = false, mediaSf = mp.TURN_ON or mp.PLAY)
        assertThat(mediaControlSet(s)).containsExactly(MediaControl.TURN_ON)
    }

    @Test fun `active player with turn-off shows power-off`() {
        val mp = EntityState.MediaPlayerFeature
        val s = state("media_player.m", raw = "playing", mediaSf = mp.TURN_OFF or mp.PLAY or mp.PAUSE)
        assertThat(mediaControlSet(s)).contains(MediaControl.TURN_OFF)
    }

    @Test fun `volume-only player suppresses transport and shows volume`() {
        val mp = EntityState.MediaPlayerFeature
        val s = state("media_player.m", raw = "playing", mediaSf = mp.VOLUME_SET or mp.NEXT_TRACK)
        // Transport block is gated off when the player has volume control.
        assertThat(mediaControlSet(s)).doesNotContain(MediaControl.NEXT)
        assertThat(mediaShowsVolume(s)).isTrue()
        assertThat(mediaVolumeIsSlider(s)).isTrue()
    }

    @Test fun `idle player is inactive and shows no volume`() {
        val mp = EntityState.MediaPlayerFeature
        val s = state("media_player.m", raw = "idle", on = false, mediaSf = mp.VOLUME_SET)
        assertThat(mediaIsActive(s)).isFalse()
        assertThat(mediaShowsVolume(s)).isFalse()
    }

    @Test fun `media description reads artist for music`() {
        val attrs = buildJsonObject {
            put("media_content_type", JsonPrimitive("music"))
            put("media_artist", JsonPrimitive("Daft Punk"))
        }
        assertThat(mediaDescription(state("media_player.m", attrs = attrs))).isEqualTo("Daft Punk")
    }

    // ── update state line ───────────────────────────────────────────────────

    @Test fun `up-to-date update reads up-to-date`() {
        val s = state("update.u", raw = "off", on = false)
        assertThat(updateStateLine(s)).isEqualTo("Up-to-date")
        assertThat(updateCanInstall(s)).isFalse()
    }

    @Test fun `available update shows latest version and can install`() {
        val attrs = buildJsonObject {
            put("supported_features", JsonPrimitive(UpdateBit.INSTALL))
            put("latest_version", JsonPrimitive("2.0.0"))
        }
        val s = state("update.u", raw = "on", attrs = attrs)
        assertThat(updateStateLine(s)).isEqualTo("2.0.0")
        assertThat(updateCanInstall(s)).isTrue()
    }

    @Test fun `skipped update shows the skipped version`() {
        val attrs = buildJsonObject {
            put("latest_version", JsonPrimitive("2.0.0"))
            put("skipped_version", JsonPrimitive("2.0.0"))
        }
        val s = state("update.u", raw = "off", on = false, attrs = attrs)
        assertThat(updateStateLine(s)).isEqualTo("2.0.0")
    }

    @Test fun `installing update with progress shows percentage`() {
        val attrs = buildJsonObject {
            put("supported_features", JsonPrimitive(UpdateBit.INSTALL or UpdateBit.PROGRESS))
            put("in_progress", JsonPrimitive(true))
            put("update_percentage", JsonPrimitive(42))
        }
        val s = state("update.u", raw = "on", attrs = attrs)
        assertThat(updateStateLine(s)).isEqualTo("Installing 42%")
        // While installing the install button is suppressed.
        assertThat(updateIsInstalling(s)).isTrue()
    }

    // ── script run-state ────────────────────────────────────────────────────

    @Test fun `off script shows run and can run`() {
        val s = state("script.s", raw = "off", on = false)
        assertThat(scriptIsRunning(s)).isFalse()
        assertThat(scriptShowsRun(s)).isTrue()
        assertThat(scriptCanRun(s)).isTrue()
    }

    @Test fun `parallel script reports running count`() {
        val attrs = buildJsonObject {
            put("mode", JsonPrimitive("parallel"))
            put("current", JsonPrimitive(3))
            put("max", JsonPrimitive(10))
        }
        val s = state("script.s", raw = "on", attrs = attrs)
        assertThat(scriptIsRunning(s)).isTrue()
        assertThat(scriptRunningCount(s)).isEqualTo(3)
        assertThat(scriptShowsRun(s)).isTrue()
        assertThat(scriptCanRun(s)).isTrue()
    }

    @Test fun `single-mode running script has no count`() {
        val attrs = buildJsonObject {
            put("mode", JsonPrimitive("single"))
            put("current", JsonPrimitive(1))
        }
        val s = state("script.s", raw = "on", attrs = attrs)
        assertThat(scriptRunningCount(s)).isNull()
    }

    @Test fun `parallel script at max cannot run`() {
        val attrs = buildJsonObject {
            put("mode", JsonPrimitive("parallel"))
            put("current", JsonPrimitive(5))
            put("max", JsonPrimitive(5))
        }
        val s = state("script.s", raw = "on", attrs = attrs)
        assertThat(scriptCanRun(s)).isFalse()
    }

    // ── lock code requirement ───────────────────────────────────────────────

    @Test fun `lock with code_format requires a code`() {
        val s = state("lock.l", raw = "locked", codeFormat = "^\\d{4}$")
        assertThat(lockRequiresCode(s)).isTrue()
        assertThat(lockToggleService(s)).isEqualTo("lock.unlock")
    }

    @Test fun `lock without code_format toggles directly`() {
        val s = state("lock.l", raw = "unlocked")
        assertThat(lockRequiresCode(s)).isFalse()
        assertThat(lockToggleService(s)).isEqualTo("lock.lock")
    }

    // ── group recursive toggleability ───────────────────────────────────────

    @Test fun `group with a toggleable member can toggle`() {
        val canToggle = groupCanToggle(listOf("sensor.x", "light.y")) { null }
        assertThat(canToggle).isTrue()
    }

    @Test fun `group of pure sensors cannot toggle`() {
        val canToggle = groupCanToggle(listOf("sensor.x", "binary_sensor.y")) { null }
        assertThat(canToggle).isFalse()
    }

    @Test fun `nested group resolves recursively to toggleable member`() {
        val members = mapOf("group.inner" to listOf("switch.z"))
        val canToggle = groupCanToggle(listOf("sensor.x", "group.inner")) { members[it] }
        assertThat(canToggle).isTrue()
    }

    @Test fun `unresolved nested group does not toggle`() {
        val canToggle = groupCanToggle(listOf("group.unknown")) { null }
        assertThat(canToggle).isFalse()
    }

    // ── number slider vs stepper ────────────────────────────────────────────

    @Test fun `slider mode uses slider`() {
        val s = state("number.n", attrs = buildJsonObject { put("mode", JsonPrimitive("slider")) })
        assertThat(numberUsesSlider(s, isInputNumber = false)).isTrue()
    }

    @Test fun `box mode uses stepper`() {
        val s = state("number.n", attrs = buildJsonObject { put("mode", JsonPrimitive("box")) })
        assertThat(numberUsesSlider(s, isInputNumber = false)).isFalse()
    }

    @Test fun `auto mode within range threshold uses slider for number`() {
        val attrs = buildJsonObject {
            put("mode", JsonPrimitive("auto"))
            put("min", JsonPrimitive(0))
            put("max", JsonPrimitive(100))
            put("step", JsonPrimitive(1))
        }
        val s = state("number.n", attrs = attrs)
        assertThat(numberUsesSlider(s, isInputNumber = false)).isTrue()
    }

    @Test fun `auto mode over range threshold uses stepper`() {
        val attrs = buildJsonObject {
            put("mode", JsonPrimitive("auto"))
            put("min", JsonPrimitive(0))
            put("max", JsonPrimitive(100000))
            put("step", JsonPrimitive(1))
        }
        val s = state("number.n", attrs = attrs)
        assertThat(numberUsesSlider(s, isInputNumber = false)).isFalse()
    }

    @Test fun `input_number auto mode never sliders`() {
        val s = state("input_number.n", attrs = buildJsonObject { put("mode", JsonPrimitive("auto")) })
        assertThat(numberUsesSlider(s, isInputNumber = true)).isFalse()
    }
}
