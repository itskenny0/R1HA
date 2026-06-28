package com.github.itskenny0.r1ha.feature.widget

import androidx.compose.ui.graphics.toArgb
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.EntityOverride
import com.github.itskenny0.r1ha.core.prefs.UiOptions
import com.github.itskenny0.r1ha.feature.moreinfo.accentForDomain
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Locks in [buildFavoriteCardModel]'s state-to-display mapping: the per-domain
 * state wording, the rename / accent / glyph override precedence, the dimmed
 * unavailable treatment, and the in-place-tap domain classification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FavoriteCardModelTest {

    private fun state(
        id: String,
        isOn: Boolean = false,
        percent: Int? = null,
        rawState: String? = null,
        unit: String? = null,
        deviceClass: String? = null,
        isAvailable: Boolean = true,
        supportsScalar: Boolean = true,
        friendlyName: String = "Friendly",
    ) = EntityState(
        id = EntityId(id),
        friendlyName = friendlyName,
        area = null,
        isOn = isOn,
        percent = percent,
        raw = null,
        lastChanged = Instant.EPOCH,
        isAvailable = isAvailable,
        supportsScalar = supportsScalar,
        rawState = rawState,
        unit = unit,
        deviceClass = deviceClass,
    )

    private val defaults = AppSettings()

    @Test fun lightOnShowsPercentReadout() {
        val model = buildFavoriteCardModel(
            "light.kitchen",
            state("light.kitchen", isOn = true, percent = 87, rawState = "on"),
            defaults,
        )
        assertThat(model.stateText).isEqualTo("87%")
        assertThat(model.glyph).isEqualTo("☀")
        assertThat(model.available).isTrue()
    }

    @Test fun onOffOnlyLightFallsBackToWords() {
        val on = buildFavoriteCardModel(
            "light.shelf",
            state("light.shelf", isOn = true, percent = null, supportsScalar = false),
            defaults,
        )
        val off = buildFavoriteCardModel(
            "light.shelf",
            state("light.shelf", isOn = false, supportsScalar = false),
            defaults,
        )
        assertThat(on.stateText).isEqualTo("ON")
        assertThat(off.stateText).isEqualTo("OFF")
    }

    @Test fun switchUsesOnOffWording() {
        val model = buildFavoriteCardModel(
            "switch.heater",
            state("switch.heater", isOn = false, rawState = "off"),
            defaults,
        )
        assertThat(model.stateText).isEqualTo("OFF")
        assertThat(model.glyph).isEqualTo("▯")
    }

    @Test fun sensorShowsValueWithUnitAndGlobalDecimalCap() {
        val model = buildFavoriteCardModel(
            "sensor.office_temp",
            state("sensor.office_temp", rawState = "21.736", unit = "°C", deviceClass = "temperature"),
            defaults,
        )
        // Global UiOptions default caps at 2 decimals; trailing zeros trimmed.
        assertThat(model.stateText).isEqualTo("21.74 °C")
        // Temperature device class reads cool, mirroring the in-app accent map.
        assertThat(model.accentArgb)
            .isEqualTo(accentForDomain(Domain.SENSOR, "temperature").toArgb())
    }

    @Test fun perCardDecimalOverrideBeatsGlobalCap() {
        val settings = AppSettings(
            entityOverrides = mapOf("sensor.power" to EntityOverride(maxDecimalPlaces = 0)),
            ui = UiOptions(maxDecimalPlaces = 2),
        )
        val model = buildFavoriteCardModel(
            "sensor.power",
            state("sensor.power", rawState = "1432.81", unit = "W", deviceClass = "power"),
            settings,
        )
        // Four integer digits stay ungrouped (thousands separators start at five).
        assertThat(model.stateText).isEqualTo("1433 W")
    }

    @Test fun lockAndBinarySensorUseDomainWording() {
        val lock = buildFavoriteCardModel(
            "lock.front_door",
            state("lock.front_door", rawState = "locked"),
            defaults,
        )
        val door = buildFavoriteCardModel(
            "binary_sensor.porch_door",
            state("binary_sensor.porch_door", isOn = true, deviceClass = "door"),
            defaults,
        )
        assertThat(lock.stateText).isEqualTo("LOCKED")
        assertThat(door.stateText).isEqualTo("OPEN")
    }

    @Test fun actionDomainsReadRun() {
        val model = buildFavoriteCardModel(
            "scene.movie_night",
            state("scene.movie_night", rawState = "scening"),
            defaults,
        )
        assertThat(model.stateText).isEqualTo("RUN")
    }

    @Test fun unavailableRendersSentinelAndDims() {
        val model = buildFavoriteCardModel(
            "light.garage",
            state("light.garage", rawState = "unavailable", isAvailable = false),
            defaults,
        )
        assertThat(model.stateText).isEqualTo("UNAVAILABLE")
        assertThat(model.available).isFalse()
    }

    @Test fun missingStateRendersDashNotCrash() {
        val model = buildFavoriteCardModel("light.gone", null, defaults)
        assertThat(model.stateText).isEqualTo("—")
        assertThat(model.available).isFalse()
        // No friendly name to fall back on: prettified object id.
        assertThat(model.name).isEqualTo("Gone")
    }

    @Test fun overridesWinForNameAccentAndGlyph() {
        val settings = AppSettings(
            nameOverrides = mapOf("light.kitchen" to "Cooking lights"),
            entityOverrides = mapOf(
                "light.kitchen" to EntityOverride(
                    accentColor = 0xFF26C6DA.toInt(),
                    glyphOverride = "✹",
                ),
            ),
        )
        val model = buildFavoriteCardModel(
            "light.kitchen",
            state("light.kitchen", isOn = true, percent = 50),
            settings,
        )
        assertThat(model.name).isEqualTo("Cooking lights")
        assertThat(model.accentArgb).isEqualTo(0xFF26C6DA.toInt())
        assertThat(model.glyph).isEqualTo("✹")
    }

    @Test fun humanizedObjectIdFallbackSentenceCases() {
        assertThat(humanizeWidgetObjectId("sensor.office_temp_2")).isEqualTo("Office temp 2")
        assertThat(humanizeWidgetObjectId("noseparator")).isEqualTo("Noseparator")
    }

    @Test fun tapClassificationMatchesSpec() {
        assertThat(widgetTapActsInPlace(Domain.LIGHT)).isTrue()
        assertThat(widgetTapActsInPlace(Domain.SWITCH)).isTrue()
        assertThat(widgetTapActsInPlace(Domain.INPUT_BOOLEAN)).isTrue()
        assertThat(widgetTapActsInPlace(Domain.FAN)).isTrue()
        assertThat(widgetTapActsInPlace(Domain.COVER)).isTrue()
        assertThat(widgetTapActsInPlace(Domain.SCENE)).isTrue()
        assertThat(widgetTapActsInPlace(Domain.SCRIPT)).isTrue()
        assertThat(widgetTapActsInPlace(Domain.BUTTON)).isTrue()
        // Read-only / rich-panel domains open the app instead.
        assertThat(widgetTapActsInPlace(Domain.SENSOR)).isFalse()
        assertThat(widgetTapActsInPlace(Domain.CLIMATE)).isFalse()
        assertThat(widgetTapActsInPlace(Domain.LOCK)).isFalse()
        assertThat(widgetTapActsInPlace(Domain.MEDIA_PLAYER)).isFalse()
        assertThat(widgetTapActsInPlace(Domain.OTHER)).isFalse()
    }

    @Test fun onToggleIsOnAndActsInPlace() {
        val model = buildFavoriteCardModel(
            "switch.heater",
            state("switch.heater", isOn = true, rawState = "on"),
            defaults,
        )
        assertThat(model.isOn).isTrue()
        assertThat(model.actsInPlace).isTrue()
    }

    @Test fun offToggleActsInPlaceButIsNotOn() {
        val model = buildFavoriteCardModel(
            "switch.heater",
            state("switch.heater", isOn = false, rawState = "off"),
            defaults,
        )
        assertThat(model.isOn).isFalse()
        assertThat(model.actsInPlace).isTrue()
    }

    @Test fun sensorDoesNotActInPlace() {
        val model = buildFavoriteCardModel(
            "sensor.office_temp",
            state("sensor.office_temp", rawState = "21.7", unit = "°C", deviceClass = "temperature"),
            defaults,
        )
        assertThat(model.actsInPlace).isFalse()
    }
}
