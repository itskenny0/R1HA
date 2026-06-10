package com.github.itskenny0.r1ha.feature.widget

import androidx.compose.ui.graphics.toArgb
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.feature.dashboards.cards.domainGlyph
import com.github.itskenny0.r1ha.feature.moreinfo.accentForDomain
import com.github.itskenny0.r1ha.ui.components.binaryWord
import com.github.itskenny0.r1ha.ui.components.formatSensorValue
import java.util.Locale

/**
 * Everything the favorite-card widget renderer needs to paint one card.
 * Computed off the live entity state + the user's settings (rename, accent
 * and glyph overrides) by [buildFavoriteCardModel] so the bitmap drawing in
 * FavoriteCardRenderer stays a dumb painter and this mapping stays unit-
 * testable without a Canvas.
 */
data class FavoriteCardModel(
    val entityId: String,
    /** Effective display name: rename override > HA friendly_name > prettified object_id. */
    val name: String,
    /** Single-character glyph standing in for the domain (or the user's glyph override). */
    val glyph: String,
    /** The big readout: "87%", "ON", "21.7 °C", "LOCKED", "RUN", "UNAVAILABLE". */
    val stateText: String,
    /** Card accent as ARGB: per-entity override > domain/device-class accent. */
    val accentArgb: Int,
    /** False for unavailable/unknown entities — the renderer dims the whole card. */
    val available: Boolean,
)

/**
 * Should a tap on the widget act on the entity in place (toggle / activate)
 * rather than open the app? Mirrors the in-app tap-to-toggle scope plus the
 * fire-and-forget action domains; everything else (sensors, climate, media,
 * locks, alarms, ...) is deliberately read-only from the home screen — those
 * domains need the in-app panel's richer affordances (PIN keypad, transport
 * row, setpoint wheel) and a stray launcher tap shouldn't unlock a door.
 */
internal fun widgetTapActsInPlace(domain: Domain): Boolean = when (domain) {
    Domain.LIGHT, Domain.SWITCH, Domain.INPUT_BOOLEAN, Domain.FAN, Domain.COVER -> true
    else -> domain.isAction
}

/**
 * Build the widget's display model for [entityId]. [state] is the latest
 * one-shot REST snapshot (null when the fetch failed or the entity vanished
 * from HA); [settings] supplies the rename / accent / glyph overrides so the
 * widget echoes whatever the user customized in the card stack.
 */
fun buildFavoriteCardModel(
    entityId: String,
    state: EntityState?,
    settings: AppSettings,
): FavoriteCardModel {
    val override = settings.entityOverrides[entityId]
    val domain = runCatching { EntityId(entityId).domain }.getOrDefault(Domain.OTHER)
    val name = settings.nameOverrides[entityId]?.takeIf { it.isNotBlank() }
        ?: state?.friendlyName?.takeIf { it.isNotBlank() }
        ?: humanizeWidgetObjectId(entityId)
    val glyph = override?.glyphOverride?.takeIf { it.isNotBlank() }
        ?: domainGlyph(entityId, state)
    val available = state != null && state.isAvailable
    val accent = override?.accentColor
        ?: accentForDomain(domain, state?.deviceClass).toArgb()
    return FavoriteCardModel(
        entityId = entityId,
        name = name,
        glyph = glyph,
        stateText = widgetStateText(entityId, domain, state, settings),
        accentArgb = accent,
        available = available,
    )
}

/**
 * Format the entity's state the way the in-app card would word it: percent
 * readouts for scalar lights/fans, ON/OFF for plain toggles, the device-class
 * word for binary sensors, LOCKED/UNLOCKED, OPEN/CLOSED, RUN for action
 * entities, value + unit (override-aware decimals) for sensors, and the raw
 * sentinel word (UNAVAILABLE / UNKNOWN) when HA reports the entity gone.
 */
internal fun widgetStateText(
    entityId: String,
    domain: Domain,
    state: EntityState?,
    settings: AppSettings,
): String {
    if (state == null) return "—"
    if (!state.isAvailable) return (state.rawState ?: "unavailable").uppercase()
    return when {
        domain.isAction -> "RUN"
        domain == Domain.LOCK ->
            (state.rawState ?: if (state.isOn) "unlocked" else "locked").uppercase()
        domain == Domain.COVER || domain == Domain.VALVE ->
            (state.rawState?.takeIf { it.isNotBlank() } ?: if (state.isOn) "open" else "closed")
                .uppercase()
        domain == Domain.BINARY_SENSOR -> binaryWord(state.deviceClass, state.isOn)
        // HVAC mode word — same readout the climate card leads with.
        domain == Domain.CLIMATE || domain == Domain.WATER_HEATER ->
            (state.rawState ?: if (state.isOn) "on" else "off").uppercase()
        // Scalar-bearing domains show the live percent while on, matching the
        // card's hero readout; on/off-only instances fall through to the word.
        domain == Domain.LIGHT || domain == Domain.FAN -> when {
            state.isOn && state.supportsScalar && state.percent != null -> "${state.percent}%"
            state.isOn -> "ON"
            else -> "OFF"
        }
        domain.isToggleLike -> if (state.isOn) "ON" else "OFF"
        // Sensors and every read-only / unmodelled domain: numeric formatting
        // with the per-card decimal override (else the global cap), plus the
        // unit suffix the in-app SensorCard renders inline.
        else -> {
            val decimals = settings.entityOverrides[entityId]?.maxDecimalPlaces
                ?: settings.ui.maxDecimalPlaces
            val value = formatSensorValue(state.rawState, maxDecimals = decimals)
            val unit = state.unit?.takeIf { it.isNotBlank() }
            if (unit != null && value != "—") "$value $unit" else value
        }
    }
}

/** Domains whose card is the binary ON/OFF switch archetype. */
private val Domain.isToggleLike: Boolean
    get() = when (this) {
        Domain.SWITCH, Domain.INPUT_BOOLEAN, Domain.AUTOMATION, Domain.SIREN,
        Domain.REMOTE, Domain.HUMIDIFIER, Domain.MEDIA_PLAYER, Domain.VACUUM,
        Domain.LAWN_MOWER,
        -> true
        else -> false
    }

/**
 * Last-resort display name when neither a rename override nor a live
 * friendly_name is at hand: "sensor.office_temp_2" reads as "Office temp 2".
 * Sentence case (not Title Case) to match [humanizeKey]'s convention.
 */
internal fun humanizeWidgetObjectId(entityId: String): String {
    val objectId = entityId.substringAfter('.', missingDelimiterValue = entityId)
    val words = objectId.split('_').filter { it.isNotEmpty() }
    if (words.isEmpty()) return entityId
    return words.joinToString(" ") { it.lowercase() }
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
}

/** One pickable favorite in the widget configuration list. */
data class WidgetFavoriteEntry(
    val entityId: String,
    /** Rename override > live friendly_name > prettified object_id. */
    val displayName: String,
)

/** One card-stack page worth of favorites for the grouped configuration list. */
data class WidgetFavoritePage(
    val pageId: String,
    val pageName: String,
    /** Per-page accent (tab chip colour) for the group header; null = default. */
    val accentArgb: Int?,
    val entries: List<WidgetFavoriteEntry>,
)

/**
 * Flatten the user's favorite pages into the grouped listing the widget
 * configuration activity renders. [friendlyNames] is the best-effort
 * entity_id to friendly_name map from a one-shot REST fetch; entries fall
 * back to a prettified object_id when neither it nor a rename override has
 * a name. Pages with no favorites are skipped. When the settings blob
 * predates pages entirely (the repository flow normally materializes a HOME
 * page, but a raw snapshot may not have run that migration), the legacy flat
 * favorites list stands in as a single unnamed page.
 */
fun buildWidgetFavoritePages(
    settings: AppSettings,
    friendlyNames: Map<String, String> = emptyMap(),
): List<WidgetFavoritePage> {
    fun entry(id: String) = WidgetFavoriteEntry(
        entityId = id,
        displayName = settings.nameOverrides[id]?.takeIf { it.isNotBlank() }
            ?: friendlyNames[id]?.takeIf { it.isNotBlank() }
            ?: humanizeWidgetObjectId(id),
    )
    val pages = settings.pages
    if (pages.isEmpty()) {
        if (settings.favorites.isEmpty()) return emptyList()
        return listOf(
            WidgetFavoritePage(
                pageId = "",
                pageName = "HOME",
                accentArgb = null,
                entries = settings.favorites.map(::entry),
            ),
        )
    }
    return pages.mapNotNull { page ->
        if (page.favorites.isEmpty()) return@mapNotNull null
        WidgetFavoritePage(
            pageId = page.id,
            pageName = page.name,
            accentArgb = page.accentArgb,
            entries = page.favorites.map(::entry),
        )
    }
}
