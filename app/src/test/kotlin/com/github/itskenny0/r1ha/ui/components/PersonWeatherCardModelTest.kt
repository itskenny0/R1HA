package com.github.itskenny0.r1ha.ui.components

import com.github.itskenny0.r1ha.core.theme.CardRenderModel
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks in the pure PERSON / WEATHER card mappings: presence label + accent, weather
 * condition glyph + label + accent. These drive the distinct read-only card treatments
 * for the person and weather domains, so a regression here would silently degrade them
 * back to a generic sensor look.
 */
class PersonWeatherCardModelTest {

    // ── PERSON ──────────────────────────────────────────────────────────────────────

    @Test fun `home maps to HOME label`() {
        assertThat(PersonWeatherCardModel.personPresenceLabel("home")).isEqualTo("HOME")
        assertThat(PersonWeatherCardModel.personPresenceLabel("HOME")).isEqualTo("HOME")
    }

    @Test fun `not_home and away map to AWAY`() {
        assertThat(PersonWeatherCardModel.personPresenceLabel("not_home")).isEqualTo("AWAY")
        assertThat(PersonWeatherCardModel.personPresenceLabel("away")).isEqualTo("AWAY")
    }

    @Test fun `custom zone is title-cased with underscores as spaces`() {
        assertThat(PersonWeatherCardModel.personPresenceLabel("work")).isEqualTo("Work")
        assertThat(PersonWeatherCardModel.personPresenceLabel("secret_lab")).isEqualTo("Secret Lab")
    }

    @Test fun `blank or null presence falls back to UNKNOWN`() {
        assertThat(PersonWeatherCardModel.personPresenceLabel(null)).isEqualTo("UNKNOWN")
        assertThat(PersonWeatherCardModel.personPresenceLabel("")).isEqualTo("UNKNOWN")
        assertThat(PersonWeatherCardModel.personPresenceLabel("  ")).isEqualTo("UNKNOWN")
        assertThat(PersonWeatherCardModel.personPresenceLabel("unavailable")).isEqualTo("UNKNOWN")
    }

    @Test fun `personIsHome is true only for home`() {
        assertThat(PersonWeatherCardModel.personIsHome("home")).isTrue()
        assertThat(PersonWeatherCardModel.personIsHome("Home")).isTrue()
        assertThat(PersonWeatherCardModel.personIsHome("not_home")).isFalse()
        assertThat(PersonWeatherCardModel.personIsHome("Work")).isFalse()
        assertThat(PersonWeatherCardModel.personIsHome(null)).isFalse()
    }

    @Test fun `person accent is green when home and neutral otherwise`() {
        assertThat(PersonWeatherCardModel.personAccent("home"))
            .isEqualTo(CardRenderModel.AccentRole.GREEN)
        assertThat(PersonWeatherCardModel.personAccent("not_home"))
            .isEqualTo(CardRenderModel.AccentRole.NEUTRAL)
        assertThat(PersonWeatherCardModel.personAccent("Work"))
            .isEqualTo(CardRenderModel.AccentRole.NEUTRAL)
        assertThat(PersonWeatherCardModel.personAccent(null))
            .isEqualTo(CardRenderModel.AccentRole.NEUTRAL)
    }

    // ── WEATHER ─────────────────────────────────────────────────────────────────────

    @Test fun `weather glyphs differ across the common conditions`() {
        val sunny = PersonWeatherCardModel.weatherConditionGlyph("sunny")
        val rainy = PersonWeatherCardModel.weatherConditionGlyph("rainy")
        val snowy = PersonWeatherCardModel.weatherConditionGlyph("snowy")
        val storm = PersonWeatherCardModel.weatherConditionGlyph("lightning")
        val cloudy = PersonWeatherCardModel.weatherConditionGlyph("cloudy")
        assertThat(setOf(sunny, rainy, snowy, storm, cloudy)).hasSize(5)
    }

    @Test fun `weather glyph is case-insensitive`() {
        assertThat(PersonWeatherCardModel.weatherConditionGlyph("SUNNY"))
            .isEqualTo(PersonWeatherCardModel.weatherConditionGlyph("sunny"))
    }

    @Test fun `unknown weather condition still yields a non-blank glyph`() {
        assertThat(PersonWeatherCardModel.weatherConditionGlyph("made-up")).isNotEmpty()
        assertThat(PersonWeatherCardModel.weatherConditionGlyph(null)).isNotEmpty()
    }

    @Test fun `weather label tidies separators and capitalises`() {
        assertThat(PersonWeatherCardModel.weatherConditionLabel("snowy-rainy")).isEqualTo("Snowy rainy")
        assertThat(PersonWeatherCardModel.weatherConditionLabel("clear_night")).isEqualTo("Clear night")
        assertThat(PersonWeatherCardModel.weatherConditionLabel("sunny")).isEqualTo("Sunny")
    }

    @Test fun `blank weather label falls back to UNKNOWN`() {
        assertThat(PersonWeatherCardModel.weatherConditionLabel(null)).isEqualTo("UNKNOWN")
        assertThat(PersonWeatherCardModel.weatherConditionLabel("")).isEqualTo("UNKNOWN")
    }

    @Test fun `weather accent reflects condition family`() {
        assertThat(PersonWeatherCardModel.weatherAccent("sunny"))
            .isEqualTo(CardRenderModel.AccentRole.WARM)
        assertThat(PersonWeatherCardModel.weatherAccent("rainy"))
            .isEqualTo(CardRenderModel.AccentRole.COOL)
        assertThat(PersonWeatherCardModel.weatherAccent("lightning"))
            .isEqualTo(CardRenderModel.AccentRole.WARM)
        assertThat(PersonWeatherCardModel.weatherAccent("windy"))
            .isEqualTo(CardRenderModel.AccentRole.GREEN)
        assertThat(PersonWeatherCardModel.weatherAccent("partlycloudy"))
            .isEqualTo(CardRenderModel.AccentRole.NEUTRAL)
    }
}
