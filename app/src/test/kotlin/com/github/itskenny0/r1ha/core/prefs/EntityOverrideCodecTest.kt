package com.github.itskenny0.r1ha.core.prefs

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round-trip tests for the per-entity override encoding. The format is pipe-separated
 * URL-encoded scale|pill|area|longpress|decimals|accent|ct so a single typo would silently
 * lose user customizations across a save/load cycle. These tests pin the format and the
 * "missing trailing field" backward-compatibility path so we don't break older saves.
 */
class EntityOverrideCodecTest {

    @Test fun `empty map round-trips to empty string`() {
        val encoded = encodeEntityOverrides_visibleForTesting(emptyMap())
        assertThat(encoded).isEmpty()
        assertThat(decodeEntityOverrides_visibleForTesting(encoded)).isEmpty()
    }

    @Test fun `default override round-trips`() {
        val map = mapOf("light.kitchen" to EntityOverride.NONE)
        val encoded = encodeEntityOverrides_visibleForTesting(map)
        val decoded = decodeEntityOverrides_visibleForTesting(encoded)
        assertThat(decoded).isEqualTo(map)
    }

    @Test fun `fully-customised override round-trips through encoder`() {
        val map = mapOf(
            "light.kitchen" to EntityOverride(
                textSizeSp = 28,
                showOnOffPill = true,
                showAreaLabel = false,
                longPressTarget = "scene.movie_night",
                maxDecimalPlaces = 1,
                accentColor = 0xFF52C77F.toInt(),
                lightColorTempK = 2700,
            ),
        )
        val encoded = encodeEntityOverrides_visibleForTesting(map)
        val decoded = decodeEntityOverrides_visibleForTesting(encoded)
        assertThat(decoded).isEqualTo(map)
    }

    @Test fun `multiple entries round-trip with their own settings`() {
        val map = mapOf(
            "light.kitchen" to EntityOverride(textSizeSp = 48, accentColor = 0xFFE53935.toInt()),
            "switch.kettle" to EntityOverride(showOnOffPill = false, longPressTarget = "script.boil"),
            "sensor.outdoor_temp" to EntityOverride(maxDecimalPlaces = 0),
        )
        val encoded = encodeEntityOverrides_visibleForTesting(map)
        val decoded = decodeEntityOverrides_visibleForTesting(encoded)
        assertThat(decoded).isEqualTo(map)
    }

    @Test fun `legacy float-scale value decodes into an absolute sp`() {
        // Pre-textSizeSp saves stored the size as a 0.1..2.0 multiplier of the 72 sp
        // default. We migrate on decode rather than rewriting every save on first load —
        // a save with the new format will replace the row, so legacy values self-heal
        // through normal use. 0.85 × 72 ≈ 61 sp.
        val legacy = "light.kitchen=0.85|?|?||?|?|?"
        val decoded = decodeEntityOverrides_visibleForTesting(legacy)
        val o = decoded["light.kitchen"]
        assertThat(o).isNotNull()
        assertThat(o!!.textSizeSp).isEqualTo(61)
    }

    @Test fun `null text size encodes as inherit sentinel and round-trips as null`() {
        val map = mapOf("light.kitchen" to EntityOverride(textSizeSp = null, showOnOffPill = true))
        val encoded = encodeEntityOverrides_visibleForTesting(map)
        assertThat(encoded).startsWith("light.kitchen=?|")
        val decoded = decodeEntityOverrides_visibleForTesting(encoded)
        assertThat(decoded["light.kitchen"]?.textSizeSp).isNull()
    }

    @Test fun `hidden light buttons round-trip via single-char codes`() {
        val map = mapOf(
            "light.kitchen" to EntityOverride(
                lightButtonsHidden = setOf(LightCardButton.WHITE, LightCardButton.EFFECTS),
            ),
        )
        val encoded = encodeEntityOverrides_visibleForTesting(map)
        // Codes are stored sorted alphabetically (F before W) so the encoded blob is
        // stable regardless of Set iteration order. Test by splitting on `|` and
        // checking the buttons slot directly — keeps the assertion robust to new
        // fields being appended to the encoded form in future schema changes.
        val buttonsSlot = encoded.substringAfter('=').split('|').getOrNull(7)
        assertThat(buttonsSlot).isEqualTo("FW")
        val decoded = decodeEntityOverrides_visibleForTesting(encoded)
        assertThat(decoded["light.kitchen"]?.lightButtonsHidden)
            .isEqualTo(setOf(LightCardButton.WHITE, LightCardButton.EFFECTS))
    }

    @Test fun `decoder ignores unknown light-button codes`() {
        // Defensive: a future build that ships a new button code (e.g. 'X') should
        // not crash older builds that decode the saved blob — they just ignore the
        // code and keep the known ones.
        val legacy = "light.kitchen=?|?|?||?|?|?|FXW"
        val decoded = decodeEntityOverrides_visibleForTesting(legacy)
        assertThat(decoded["light.kitchen"]?.lightButtonsHidden)
            .isEqualTo(setOf(LightCardButton.EFFECTS, LightCardButton.WHITE))
    }

    @Test fun `longpress with URL-special characters survives encoding`() {
        // Entity IDs are alphanumeric + dot + underscore, but be defensive against future
        // HA additions (slashes, pipes) — the URL-encoding wrapping should handle them.
        val map = mapOf(
            "light.kitchen" to EntityOverride(longPressTarget = "scene.movie|night with spaces"),
        )
        val encoded = encodeEntityOverrides_visibleForTesting(map)
        // The pipe inside the longpress value would otherwise split the parts list and
        // corrupt the decode — URL-encoding to %7C makes it safe.
        assertThat(encoded).doesNotContain("night with spaces")
        val decoded = decodeEntityOverrides_visibleForTesting(encoded)
        assertThat(decoded["light.kitchen"]?.longPressTarget).isEqualTo("scene.movie|night with spaces")
    }

    @Test fun `line without equals is skipped`() {
        // A line with no `=` separator can't be parsed as id=value — the decoder skips it
        // rather than crashing. Lines WITH `=` but malformed value parts get tolerant
        // defaults applied (that's exercised by the legacy-save test below).
        val encoded = listOf(
            "garbage no equals sign",
            "valid.entity=1.0|?|?||?|?|?",
        ).joinToString("\n")
        val decoded = decodeEntityOverrides_visibleForTesting(encoded)
        assertThat(decoded).containsKey("valid.entity")
        assertThat(decoded).hasSize(1)
    }

    @Test fun `older save with fewer trailing fields still decodes`() {
        // Synthesize what a pre-CT save (before the lightColorTempK field shipped) would
        // have looked like — six pipe-separated parts instead of seven. The decoder
        // should treat the missing trailing field as inherit/null. The first slot also
        // uses the legacy float-multiplier format ("1.0"), which migrates to 72 sp.
        val legacy = "light.kitchen=1.0|1|0|scene.foo|2|" + 0xFFF36F21.toInt()
        val decoded = decodeEntityOverrides_visibleForTesting(legacy)
        val o = decoded["light.kitchen"]
        assertThat(o).isNotNull()
        assertThat(o!!.lightColorTempK).isNull()
        assertThat(o.accentColor).isEqualTo(0xFFF36F21.toInt())
        assertThat(o.showOnOffPill).isTrue()
        assertThat(o.showAreaLabel).isFalse()
        assertThat(o.textSizeSp).isEqualTo(EntityOverride.DEFAULT_TEXT_SIZE_SP)
    }

    @Test fun `position dot location override round-trips through codec`() {
        val map = mapOf(
            "light.kitchen" to EntityOverride(
                positionDotLocation = PositionDotLocation.BOTTOM_RIGHT,
            ),
        )
        val encoded = encodeEntityOverrides_visibleForTesting(map)
        // Codec slot 14 — the position-pip code char, matching the
        // PositionDotLocation.code constant. BOTTOM_RIGHT = '8'.
        val pipSlot = encoded.substringAfter('=').split('|').getOrNull(14)
        assertThat(pipSlot).isEqualTo("8")
        val decoded = decodeEntityOverrides_visibleForTesting(encoded)
        assertThat(decoded["light.kitchen"]?.positionDotLocation)
            .isEqualTo(PositionDotLocation.BOTTOM_RIGHT)
    }

    @Test fun `null position dot location encodes as inherit and round-trips as null`() {
        val map = mapOf("light.kitchen" to EntityOverride(showOnOffPill = true))
        val encoded = encodeEntityOverrides_visibleForTesting(map)
        val pipSlot = encoded.substringAfter('=').split('|').getOrNull(14)
        assertThat(pipSlot).isEqualTo("?")
        val decoded = decodeEntityOverrides_visibleForTesting(encoded)
        assertThat(decoded["light.kitchen"]?.positionDotLocation).isNull()
    }

    @Test fun `glyph override survives encoding and decode`() {
        // Glyph slot is URL-encoded so emoji / spaces / pipes can't break the
        // pipe-separated row format. Verifies the encoded blob does NOT contain
        // a raw pipe inside the glyph text and that the round-trip restores
        // the original codepoint sequence exactly.
        val map = mapOf(
            "light.kitchen" to EntityOverride(glyphOverride = "★"),
        )
        val encoded = encodeEntityOverrides_visibleForTesting(map)
        val glyphSlot = encoded.substringAfter('=').split('|').getOrNull(15)
        // Star encodes to %E2%98%85 — no raw pipe / equals / newline in the slot.
        assertThat(glyphSlot).isEqualTo("%E2%98%85")
        val decoded = decodeEntityOverrides_visibleForTesting(encoded)
        assertThat(decoded["light.kitchen"]?.glyphOverride).isEqualTo("★")
    }

    @Test fun `tap and wheel-press action overrides round-trip via single-char codes`() {
        val map = mapOf(
            "light.kitchen" to EntityOverride(
                actionOnTap = TapAction.NAVIGATE_HISTORY,
                actionOnWheelPress = TapAction.NOOP,
            ),
        )
        val encoded = encodeEntityOverrides_visibleForTesting(map)
        val parts = encoded.substringAfter('=').split('|')
        // Slot 16 = tap action ('H' = NAVIGATE_HISTORY), slot 17 = wheel-press ('0' = NOOP).
        assertThat(parts.getOrNull(16)).isEqualTo("H")
        assertThat(parts.getOrNull(17)).isEqualTo("0")
        val decoded = decodeEntityOverrides_visibleForTesting(encoded)
        assertThat(decoded["light.kitchen"]?.actionOnTap).isEqualTo(TapAction.NAVIGATE_HISTORY)
        assertThat(decoded["light.kitchen"]?.actionOnWheelPress).isEqualTo(TapAction.NOOP)
    }

    @Test fun `decoder ignores unknown position-dot codes`() {
        // Defensive: a future build that ships a new location code (e.g. 'X')
        // should not crash older builds. Unknown code = inherit (null).
        val encoded = "light.kitchen=?|?|?||?|?|?|||?|?|?||?||X"
        val decoded = decodeEntityOverrides_visibleForTesting(encoded)
        assertThat(decoded["light.kitchen"]?.positionDotLocation).isNull()
    }

    @Test fun `older save without slots 14 through 17 still decodes`() {
        // Synthesize a save predating the position-pip / glyph / action-on-tap
        // additions (15 slots instead of 18). New fields should land as null
        // / inherit so the user's existing customizations are preserved.
        val legacy = "light.kitchen=28|1|0|scene.foo|2|" + 0xFFF36F21.toInt() +
            "|2700|F||1|?|?||?|"
        val decoded = decodeEntityOverrides_visibleForTesting(legacy)
        val o = decoded["light.kitchen"]
        assertThat(o).isNotNull()
        assertThat(o!!.textSizeSp).isEqualTo(28)
        assertThat(o.lightColorTempK).isEqualTo(2700)
        // New fields: all null / inherit.
        assertThat(o.positionDotLocation).isNull()
        assertThat(o.glyphOverride).isNull()
        assertThat(o.actionOnTap).isNull()
        assertThat(o.actionOnWheelPress).isNull()
    }

    @Test fun `fully populated override with new fields round-trips`() {
        val map = mapOf(
            "light.kitchen" to EntityOverride(
                textSizeSp = 28,
                showOnOffPill = true,
                showAreaLabel = false,
                longPressTarget = "scene.movie_night",
                maxDecimalPlaces = 1,
                accentColor = 0xFF52C77F.toInt(),
                lightColorTempK = 2700,
                positionDotLocation = PositionDotLocation.LEFT_CENTER,
                glyphOverride = "🔥",
                actionOnTap = TapAction.TOGGLE,
                actionOnWheelPress = TapAction.FIRE,
                valueBarLocation = ValueBarLocation.TOP,
                moreInfoEnabled = false,
            ),
        )
        val encoded = encodeEntityOverrides_visibleForTesting(map)
        val decoded = decodeEntityOverrides_visibleForTesting(encoded)
        assertThat(decoded).isEqualTo(map)
    }

    @Test fun `value bar location override round-trips through codec`() {
        val map = mapOf(
            "light.kitchen" to EntityOverride(valueBarLocation = ValueBarLocation.BOTTOM),
        )
        val encoded = encodeEntityOverrides_visibleForTesting(map)
        // Codec slot 18 — the value-bar code char. BOTTOM = 'B'.
        val barSlot = encoded.substringAfter('=').split('|').getOrNull(18)
        assertThat(barSlot).isEqualTo("B")
        val decoded = decodeEntityOverrides_visibleForTesting(encoded)
        assertThat(decoded["light.kitchen"]?.valueBarLocation)
            .isEqualTo(ValueBarLocation.BOTTOM)
    }

    @Test fun `null value bar location encodes as inherit and round-trips as null`() {
        val map = mapOf("light.kitchen" to EntityOverride(showOnOffPill = true))
        val encoded = encodeEntityOverrides_visibleForTesting(map)
        val barSlot = encoded.substringAfter('=').split('|').getOrNull(18)
        assertThat(barSlot).isEqualTo("?")
        val decoded = decodeEntityOverrides_visibleForTesting(encoded)
        assertThat(decoded["light.kitchen"]?.valueBarLocation).isNull()
    }

    @Test fun `decoder ignores unknown value-bar codes`() {
        // Defensive: a future build that ships a new value-bar code (e.g. 'Z')
        // should not crash older builds. Unknown code = inherit (null).
        val encoded = "light.kitchen=?|?|?||?|?|?||?|?|?||?||?||?|?|Z"
        val decoded = decodeEntityOverrides_visibleForTesting(encoded)
        assertThat(decoded["light.kitchen"]?.valueBarLocation).isNull()
    }

    @Test fun `more-info enabled override round-trips through codec`() {
        val on = mapOf("light.kitchen" to EntityOverride(moreInfoEnabled = true))
        val onEnc = encodeEntityOverrides_visibleForTesting(on)
        // Codec slot 19 — the ultra-detail more-info tri-state. true = "1".
        assertThat(onEnc.substringAfter('=').split('|').getOrNull(19)).isEqualTo("1")
        assertThat(decodeEntityOverrides_visibleForTesting(onEnc)["light.kitchen"]?.moreInfoEnabled)
            .isTrue()

        val off = mapOf("light.kitchen" to EntityOverride(moreInfoEnabled = false))
        val offEnc = encodeEntityOverrides_visibleForTesting(off)
        assertThat(offEnc.substringAfter('=').split('|').getOrNull(19)).isEqualTo("0")
        assertThat(decodeEntityOverrides_visibleForTesting(offEnc)["light.kitchen"]?.moreInfoEnabled)
            .isFalse()
    }

    @Test fun `null more-info enabled encodes as inherit and round-trips as null`() {
        val map = mapOf("light.kitchen" to EntityOverride(showOnOffPill = true))
        val encoded = encodeEntityOverrides_visibleForTesting(map)
        assertThat(encoded.substringAfter('=').split('|').getOrNull(19)).isEqualTo("?")
        assertThat(decodeEntityOverrides_visibleForTesting(encoded)["light.kitchen"]?.moreInfoEnabled)
            .isNull()
    }

    @Test fun `older save without slot 19 decodes more-info as null`() {
        // 19-slot save (no trailing more-info slot). The missing field should
        // land as null (inherit the global default) rather than crashing.
        val encoded = "light.kitchen=?|1|?||?|?|?||?|?|?||?||?||?|?|R"
        val decoded = decodeEntityOverrides_visibleForTesting(encoded)
        assertThat(decoded["light.kitchen"]?.moreInfoEnabled).isNull()
        assertThat(decoded["light.kitchen"]?.showOnOffPill).isTrue()
    }

    @Test fun `older save without slot 18 still decodes value bar as null`() {
        // Synthesize a save predating the value-bar field (18 slots, the last
        // being the wheel-press action). The new field should land as null /
        // inherit so existing customizations are preserved.
        // Slots 0..17: size|pill|area|lp|dec|accent|ct|btns|tap|wheel|hide|
        // custom|pinReq|pinHash|pip|glyph|tapAction|wheelPress. No slot 18.
        val legacy = "light.kitchen=28|1|0|scene.foo|2|" + 0xFFF36F21.toInt() +
            "|2700|F||1|?|?|||?||T|F"
        val decoded = decodeEntityOverrides_visibleForTesting(legacy)
        val o = decoded["light.kitchen"]
        assertThat(o).isNotNull()
        assertThat(o!!.textSizeSp).isEqualTo(28)
        assertThat(o.actionOnTap).isEqualTo(TapAction.TOGGLE)
        assertThat(o.actionOnWheelPress).isEqualTo(TapAction.FIRE)
        assertThat(o.valueBarLocation).isNull()
    }

    @Test fun `mid-slot tri-state booleans round-trip independently`() {
        // Slots 8/9/10 (tapToToggle / wheelEnabled / hideWhenUnavailable) are
        // three-state. Pin each combination so a future slot reshuffle that
        // crossed two of them would fail loudly here rather than silently
        // swapping a user's per-card toggles.
        val map = mapOf(
            "light.kitchen" to EntityOverride(
                tapToToggle = true,
                wheelEnabled = false,
                hideWhenUnavailable = true,
            ),
        )
        val encoded = encodeEntityOverrides_visibleForTesting(map)
        val parts = encoded.substringAfter('=').split('|')
        assertThat(parts.getOrNull(8)).isEqualTo("1")
        assertThat(parts.getOrNull(9)).isEqualTo("0")
        assertThat(parts.getOrNull(10)).isEqualTo("1")
        val decoded = decodeEntityOverrides_visibleForTesting(encoded)
        val o = decoded["light.kitchen"]
        assertThat(o?.tapToToggle).isTrue()
        assertThat(o?.wheelEnabled).isFalse()
        assertThat(o?.hideWhenUnavailable).isTrue()
    }

    @Test fun `custom actions with awkward payload survive the pipe-separated row`() {
        // The custom-action JSON (slot 11) is URL-encoded so its commas, quotes,
        // braces and especially a literal pipe inside the user's service_data
        // can't split the row. This is the slot most likely to corrupt the
        // whole map if the encoding ever regressed, so assert both the absence
        // of a raw pipe in the slot and a clean structural round-trip.
        val map = mapOf(
            "fan.bedroom" to EntityOverride(
                customActions = listOf(
                    CustomAction(
                        label = "Natural|mode",
                        service = "xiaomi_miio_fan.fan_set_natural_mode_on",
                        dataJson = """{"speed":"high","note":"a|b=c"}""",
                        targetEntityId = "fan.other",
                    ),
                    CustomAction(label = "Boil", service = "script.boil"),
                ),
            ),
        )
        val encoded = encodeEntityOverrides_visibleForTesting(map)
        val slot = encoded.substringAfter('=').split('|').getOrNull(11)
        assertThat(slot).isNotNull()
        assertThat(slot).doesNotContain("=")
        val decoded = decodeEntityOverrides_visibleForTesting(encoded)
        assertThat(decoded["fan.bedroom"]?.customActions).isEqualTo(map["fan.bedroom"]?.customActions)
    }

    @Test fun `lock pin gate and hash round-trip`() {
        // Slots 12/13 — the per-card lock PIN gate flag and its hashed PIN.
        // The hash is plain hex so it shares the row directly; verify it
        // survives alongside the tri-state gate without disturbing neighbours.
        val map = mapOf(
            "lock.front_door" to EntityOverride(
                requirePinToUnlock = true,
                requirePinHash = "abc123def456",
            ),
        )
        val encoded = encodeEntityOverrides_visibleForTesting(map)
        val parts = encoded.substringAfter('=').split('|')
        assertThat(parts.getOrNull(12)).isEqualTo("1")
        assertThat(parts.getOrNull(13)).isEqualTo("abc123def456")
        val decoded = decodeEntityOverrides_visibleForTesting(encoded)
        val o = decoded["lock.front_door"]
        assertThat(o?.requirePinToUnlock).isTrue()
        assertThat(o?.requirePinHash).isEqualTo("abc123def456")
    }

    @Test fun `decoder ignores unknown tap and wheel-press action codes`() {
        // Defensive: a future build that ships a new TapAction code (e.g. 'Z')
        // in slots 16/17 must not crash older builds; an unrecognised code
        // decodes as inherit (null) rather than throwing.
        val encoded = "light.kitchen=?|?|?||?|?|?||?|?|?||?||?||Z|Q"
        val decoded = decodeEntityOverrides_visibleForTesting(encoded)
        val o = decoded["light.kitchen"]
        assertThat(o).isNotNull()
        assertThat(o!!.actionOnTap).isNull()
        assertThat(o.actionOnWheelPress).isNull()
    }
}
