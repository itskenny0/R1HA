package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.ui.components.attrBoolean
import com.github.itskenny0.r1ha.ui.components.attrInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Pure decision logic for the J1 card-features (gating, command sets, favorite
 * defaults, area-control fan-out). Kept Compose-free so it can be unit-tested
 * without a renderer. Mirrors the matching helpers in HA's `card-features/` and
 * `data/` sources.
 */

/** HA's `DEFAULT_COVER_FAVORITE_POSITIONS` / `DEFAULT_VALVE_FAVORITE_POSITIONS`. */
val DEFAULT_FAVORITE_POSITIONS = listOf(0, 25, 75, 100)

/**
 * The favorite positions to render: the registry's explicit list when present
 * (already normalised), else HA's defaults. Mirrors the numeric-favorite base's
 * `getFavoritePositions(entry) ?? defaultFavoritePositions`.
 */
fun resolveFavoritePositions(registryPositions: List<Int>, hasRegistryList: Boolean): List<Int> =
    if (hasRegistryList) registryPositions else DEFAULT_FAVORITE_POSITIONS

// ── Cover open/close gating (HA src/data/cover.ts) ──────────────────────────

/**
 * Whether a cover's open / stop / close buttons can act, given its raw
 * `supported_features` bitmask and current state. Mirrors HA's canOpen / canStop
 * / canClose: a fully-open cover can't open further, opening/closing covers can
 * stop, and an assumed-state cover ignores the position limits (always enabled).
 */
data class CoverOpenCloseGate(
    val showOpen: Boolean,
    val showStop: Boolean,
    val showClose: Boolean,
    val canOpen: Boolean,
    val canStop: Boolean,
    val canClose: Boolean,
)

fun coverOpenCloseGate(state: EntityState): CoverOpenCloseGate {
    val sf = state.attrInt("supported_features") ?: 0
    // Forgive an omitted bitmask for the visible-button gate (CoverPanel idiom),
    // but require an explicit STOP bit so a plain blind shows no STOP button.
    fun bit(b: Int) = sf == 0 || (sf and b) != 0
    val showOpen = bit(EntityState.CoverFeature.OPEN)
    val showClose = bit(EntityState.CoverFeature.CLOSE)
    val showStop = sf != 0 && (sf and EntityState.CoverFeature.STOP) != 0
    val raw = state.rawState.orEmpty().lowercase()
    val assumed = state.attrBoolean("assumed_state") == true
    val unavailable = raw == "unavailable"
    // HA canOpen/canClose: an assumed-state cover ignores limits; otherwise a
    // fully-open cover can't open and an in-motion cover can't restart that way.
    val position = state.attrInt("current_position")
    val isFullyOpen = position?.let { it >= 100 } ?: (raw == "open")
    val isFullyClosed = position?.let { it <= 0 } ?: (raw == "closed")
    val canOpen = !unavailable && (assumed || (!isFullyOpen && raw != "opening"))
    val canClose = !unavailable && (assumed || (!isFullyClosed && raw != "closing"))
    return CoverOpenCloseGate(
        showOpen = showOpen,
        showStop = showStop,
        showClose = showClose,
        canOpen = canOpen,
        // STOP has no travel-limit gate in HA; only disabled when unavailable.
        canStop = !unavailable,
        canClose = canClose,
    )
}

// ── Vacuum commands (HA card-features/hui-vacuum-commands-card-feature.ts) ───

/** HA's `VACUUM_COMMANDS` config-key order. */
val VACUUM_COMMAND_KEYS = listOf("start_pause", "stop", "clean_spot", "locate", "return_home")

/** The resolved button for one vacuum command key: which `vacuum.<service>` to
 *  call, the label, and whether it is disabled in the current state. */
data class VacuumButton(val key: String, val service: String, val label: String, val enabled: Boolean)

/** Whether the vacuum advertises any feature backing a command key. */
fun vacuumSupportsCommand(state: EntityState, key: String): Boolean {
    val f = EntityState.VacuumFeature
    return when (key) {
        "start_pause" -> state.hasVacuumFeature(f.PAUSE) || state.hasVacuumFeature(f.START)
        "stop" -> state.hasVacuumFeature(f.STOP)
        "clean_spot" -> state.hasVacuumFeature(f.CLEAN_SPOT)
        "locate" -> state.hasVacuumFeature(f.LOCATE)
        "return_home" -> state.hasVacuumFeature(f.RETURN_HOME)
        else -> false
    }
}

private fun vacuumIsCleaning(raw: String) = raw == "cleaning" || raw == "on"
private fun vacuumCanStart(raw: String) = raw != "unavailable" && !vacuumIsCleaning(raw)
private fun vacuumCanStop(raw: String) = raw !in setOf("docked", "off", "idle")
private fun vacuumCanReturnHome(raw: String) = raw != "unavailable" && raw != "returning"

/**
 * Resolve one vacuum command key to its dynamic button, mirroring HA's
 * `VACUUM_COMMANDS_BUTTONS`. `start_pause` becomes PAUSE while cleaning (when the
 * vacuum supports PAUSE), else START (disabled unless it canStart); it falls back
 * to the legacy `start_pause` service for old PAUSE-only entities. Everything is
 * additionally disabled when the entity is unavailable.
 */
fun vacuumButtonFor(state: EntityState, key: String): VacuumButton {
    val f = EntityState.VacuumFeature
    val raw = state.rawState.orEmpty().lowercase()
    val unavailable = raw == "unavailable"
    return when (key) {
        "start_pause" -> {
            val startPauseOnly = !state.hasVacuumFeature(f.STATE) &&
                !state.hasVacuumFeature(f.START) && state.hasVacuumFeature(f.PAUSE)
            when {
                startPauseOnly -> VacuumButton(key, "start_pause", "START/PAUSE", !unavailable)
                vacuumIsCleaning(raw) && state.hasVacuumFeature(f.PAUSE) ->
                    VacuumButton(key, "pause", "PAUSE", !unavailable)
                else -> VacuumButton(key, "start", "START", vacuumCanStart(raw))
            }
        }
        "stop" -> VacuumButton(key, "stop", "STOP", !unavailable && vacuumCanStop(raw))
        "clean_spot" -> VacuumButton(key, "clean_spot", "SPOT", !unavailable)
        "locate" -> VacuumButton(key, "locate", "LOCATE", !unavailable)
        "return_home" -> VacuumButton(key, "return_to_base", "DOCK", !unavailable && vacuumCanReturnHome(raw))
        else -> VacuumButton(key, key, key.uppercase(), !unavailable)
    }
}

/**
 * The vacuum command keys to render. HA shows only the keys listed in `commands:`
 * that the vacuum also supports; when `commands:` is unset the editor stub seeds
 * the first three supported keys, so R1HA mirrors that by defaulting to all
 * supported keys (capped at three) rather than rendering nothing.
 */
fun vacuumVisibleCommands(state: EntityState, configCommands: List<String>): List<String> {
    val supported = VACUUM_COMMAND_KEYS.filter { vacuumSupportsCommand(state, it) }
    if (configCommands.isEmpty()) return supported.take(3)
    return VACUUM_COMMAND_KEYS.filter { it in configCommands && it in supported }
}

// ── Lock gating (HA src/data/lock.ts) ───────────────────────────────────────

private fun lockIsWaiting(raw: String) = raw in setOf("opening", "unlocking", "locking")

/** HA `canLock`: assumed-state locks always; else not already locked and not in motion. */
fun lockCanLock(state: EntityState): Boolean {
    val raw = state.rawState.orEmpty().lowercase()
    if (raw == "unavailable") return false
    val assumed = state.attrBoolean("assumed_state") == true
    return assumed || (raw != "locked" && !lockIsWaiting(raw))
}

/** HA `canUnlock`: assumed-state locks always; else not already unlocked and not in motion. */
fun lockCanUnlock(state: EntityState): Boolean {
    val raw = state.rawState.orEmpty().lowercase()
    if (raw == "unavailable") return false
    val assumed = state.attrBoolean("assumed_state") == true
    return assumed || (raw != "unlocked" && !lockIsWaiting(raw))
}

/** HA `canOpen` (lock door): assumed-state always; else not open and not in motion. */
fun lockCanOpen(state: EntityState): Boolean {
    val raw = state.rawState.orEmpty().lowercase()
    if (raw == "unavailable") return false
    val assumed = state.attrBoolean("assumed_state") == true
    return assumed || (raw != "open" && !lockIsWaiting(raw))
}

/** HA `LockEntityFeature.OPEN` bit (1) read from raw supported_features. */
fun lockSupportsOpen(state: EntityState): Boolean =
    ((state.attrInt("supported_features") ?: 0) and 1) != 0

// ── Area-controls (HA src/data/area/area_controls.ts) ───────────────────────

/** HA's default `AREA_CONTROL_DOMAINS` order (capped at four when controls unset). */
val AREA_CONTROL_DOMAINS = listOf(
    "light", "fan", "cover-shutter", "cover-blind", "cover-curtain", "cover-shade",
    "cover-awning", "cover-garage", "cover-gate", "cover-door", "cover-window",
    "cover-damper", "switch",
)

const val MAX_DEFAULT_AREA_CONTROLS = 4

/** A normalised area control: either a domain/device-class group or one entity. */
sealed class AreaControl {
    data class DomainGroup(val token: String) : AreaControl()
    data class Entity(val entityId: String) : AreaControl()
}

/** Normalise one raw control token: a known domain token stays a group, anything
 *  else (an entity id) becomes an entity control. Mirrors HA `_normalizeControl`. */
fun normalizeAreaControl(token: String): AreaControl =
    if (token in AREA_CONTROL_DOMAINS) AreaControl.DomainGroup(token) else AreaControl.Entity(token)

/** The base domain a control token acts on ("cover-blind" -> "cover"). */
fun areaControlDomain(token: String): String =
    if (token.startsWith("cover-")) "cover" else token

/** The cover device-class a "cover-<class>" token filters on, or null. */
fun areaControlDeviceClass(token: String): String? =
    if (token.startsWith("cover-")) token.removePrefix("cover-") else null

/**
 * The member entity ids a domain control acts on: every area member in the
 * control's domain (and, for cover tokens, matching device_class), excluding the
 * card's exclude list and entity-category entities. Mirrors `getAreaControlEntities`
 * (entity_category "none" is approximated by dropping config/diagnostic entities,
 * which R1HA's member snapshot doesn't tag, so all domain members are kept).
 */
fun areaControlEntities(
    token: String,
    members: List<EntityState>,
    excludeEntities: Set<String>,
): List<EntityState> {
    val domain = areaControlDomain(token)
    val deviceClass = areaControlDeviceClass(token)
    return members.filter { m ->
        m.id.value !in excludeEntities &&
            m.id.value.substringBefore('.') == domain &&
            (deviceClass == null || m.deviceClass.equals(deviceClass, ignoreCase = true))
    }
}

/** HA's group on/off state for a control group, used for active colouring and to
 *  pick the toggle direction. "open" for any open/opening/closing cover, "on" for
 *  any on member, else "off" / "closed". Empty groups are inactive. */
fun areaGroupIsActive(token: String, entities: List<EntityState>): Boolean {
    if (entities.isEmpty()) return false
    val domain = areaControlDomain(token)
    if (domain == "cover") {
        return entities.any {
            val s = it.rawState.orEmpty().lowercase()
            s == "open" || s == "opening" || s == "closing"
        }
    }
    return entities.any { it.rawState.equals("on", ignoreCase = true) }
}

/** The fan-out service for toggling a control group, mirroring `toggleGroupEntities`. */
fun areaToggleService(token: String, entities: List<EntityState>): String {
    val domain = areaControlDomain(token)
    val active = areaGroupIsActive(token, entities)
    if (domain == "cover") {
        val moving = entities.any {
            val s = it.rawState.orEmpty().lowercase()
            s == "opening" || s == "closing"
        }
        return when {
            moving -> "cover.stop_cover"
            active -> "cover.close_cover"
            else -> "cover.open_cover"
        }
    }
    return if (active) "$domain.turn_off" else "$domain.turn_on"
}

/**
 * The controls to display for an area-controls feature, in config order, dropping
 * domain groups with no matching entities and entity controls whose entity is
 * absent / excluded. When `controls:` is unset, HA caps the default list at four.
 */
fun resolveAreaControls(
    configControls: List<String>,
    members: List<EntityState>,
    excludeEntities: Set<String>,
): List<AreaControl> {
    val tokens = configControls.ifEmpty { AREA_CONTROL_DOMAINS }
    val memberIds = members.map { it.id.value }.toSet()
    val resolved = tokens.map { normalizeAreaControl(it) }.filter { control ->
        when (control) {
            is AreaControl.DomainGroup ->
                areaControlEntities(control.token, members, excludeEntities).isNotEmpty()
            is AreaControl.Entity ->
                control.entityId in memberIds && control.entityId !in excludeEntities
        }
    }
    return if (configControls.isEmpty()) resolved.take(MAX_DEFAULT_AREA_CONTROLS) else resolved
}

// ── Light favorite colours (HA src/data/light.ts) ───────────────────────────

private val LIGHT_COLOR_MODES = setOf("hs", "xy", "rgb", "rgbw", "rgbww")

/** HA `lightSupportsColor`: any colour-capable mode in supported_color_modes. */
fun lightSupportsColor(state: EntityState): Boolean =
    state.supportedColorModes.any { it.lowercase() in LIGHT_COLOR_MODES }

/** HA `lightSupportsColorMode(COLOR_TEMP)`. */
fun lightSupportsColorTemp(state: EntityState): Boolean =
    state.supportedColorModes.any { it.equals("color_temp", ignoreCase = true) }

private val LIGHT_BRIGHTNESS_MODES =
    setOf("color_temp", "brightness", "hs", "xy", "rgb", "rgbw", "rgbww", "white")

/** HA `lightSupportsBrightness`: any brightness-capable mode in supported_color_modes.
 *  An on/off-only light (modes [] or [onoff]) returns false, so no slider shows. */
fun lightSupportsBrightness(state: EntityState): Boolean =
    state.supportedColorModes.any { it.lowercase() in LIGHT_BRIGHTNESS_MODES }

/** HA `lightSupportsFavoriteColors`: colour or color_temp capable. */
fun lightSupportsFavoriteColors(state: EntityState): Boolean =
    lightSupportsColor(state) || lightSupportsColorTemp(state)

/** HA's four default coloured swatches (rgb_color). */
private val DEFAULT_COLORED_COLORS: List<JsonObject> = listOf(
    rgbColor(127, 172, 255),
    rgbColor(215, 150, 255),
    rgbColor(255, 158, 243),
    rgbColor(255, 110, 84),
)

private fun rgbColor(r: Int, g: Int, b: Int): JsonObject = buildJsonObject {
    put("rgb_color", JsonArray(listOf(JsonPrimitive(r), JsonPrimitive(g), JsonPrimitive(b))))
}

/**
 * HA `computeDefaultFavoriteColors`: four color-temp swatches spanning the
 * light's kelvin range (or 2000..6500 rgb swatches when only colour is
 * supported), plus the four default coloured swatches when colour is supported.
 * Each swatch is the raw light.turn_on payload (color_temp_kelvin / rgb_color).
 */
fun computeDefaultFavoriteColors(state: EntityState): List<JsonObject> {
    val colors = mutableListOf<JsonObject>()
    val supportsColorTemp = lightSupportsColorTemp(state)
    val supportsColor = lightSupportsColor(state)
    val count = 4
    if (supportsColorTemp) {
        val min = state.minColorTempK ?: 2000
        val max = state.maxColorTempK ?: 6500
        val step = (max - min).toDouble() / (count - 1)
        for (i in 0 until count) {
            val k = Math.round(min + step * i).toInt()
            colors.add(buildJsonObject { put("color_temp_kelvin", JsonPrimitive(k)) })
        }
    } else if (supportsColor) {
        val min = 2000
        val max = 6500
        val step = (max - min).toDouble() / (count - 1)
        for (i in 0 until count) {
            val (r, g, b) = kelvinToRgb(Math.round(min + step * i).toInt())
            colors.add(rgbColor(r, g, b))
        }
    }
    if (supportsColor) colors.addAll(DEFAULT_COLORED_COLORS)
    return colors
}

/** The favorite colours to render: the registry list when present, else the
 *  computed defaults. Mirrors hui-light-color-favorites-card-feature's updated(). */
fun resolveFavoriteColors(state: EntityState, registryColors: List<JsonObject>, hasRegistryList: Boolean): List<JsonObject> =
    if (hasRegistryList) registryColors else computeDefaultFavoriteColors(state)

/**
 * The 0xAARRGGBB swatch colour for a stored favorite-colour payload, for the
 * preview pill. Reads rgb_color / rgbw_color / rgbww_color directly, converts
 * hs_color, and approximates color_temp_kelvin via [kelvinToRgb]. Returns null
 * when the payload carries none of those (the renderer then uses the accent).
 */
fun favoriteColorSwatchArgb(color: JsonObject): Long? {
    (color["rgb_color"] as? JsonArray)?.let { return rgbArrayToArgb(it) }
    (color["rgbw_color"] as? JsonArray)?.let { return rgbArrayToArgb(it) }
    (color["rgbww_color"] as? JsonArray)?.let { return rgbArrayToArgb(it) }
    (color["color_temp_kelvin"] as? JsonPrimitive)?.intOrNull?.let {
        val (r, g, b) = kelvinToRgb(it)
        return argb(r, g, b)
    }
    (color["hs_color"] as? JsonArray)?.let { hs ->
        val h = (hs.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return null
        val s = (hs.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return null
        val (r, g, b) = hsToRgb(h, s / 100.0)
        return argb(r, g, b)
    }
    return null
}

private fun rgbArrayToArgb(arr: JsonArray): Long? {
    val r = (arr.getOrNull(0) as? JsonPrimitive)?.intOrNull ?: return null
    val g = (arr.getOrNull(1) as? JsonPrimitive)?.intOrNull ?: return null
    val b = (arr.getOrNull(2) as? JsonPrimitive)?.intOrNull ?: return null
    return argb(r, g, b)
}

private fun argb(r: Int, g: Int, b: Int): Long =
    0xFF000000L or (r.coerceIn(0, 255).toLong() shl 16) or
        (g.coerceIn(0, 255).toLong() shl 8) or b.coerceIn(0, 255).toLong()

/** Tanner Helland's blackbody-temperature to RGB approximation (HA's algorithm). */
fun kelvinToRgb(kelvin: Int): Triple<Int, Int, Int> {
    val temp = kelvin / 100.0
    val r: Double
    val g: Double
    val b: Double
    r = if (temp <= 66) 255.0 else (329.698727446 * Math.pow(temp - 60, -0.1332047592))
    g = if (temp <= 66) {
        99.4708025861 * Math.log(temp) - 161.1195681661
    } else {
        288.1221695283 * Math.pow(temp - 60, -0.0755148492)
    }
    b = when {
        temp >= 66 -> 255.0
        temp <= 19 -> 0.0
        else -> 138.5177312231 * Math.log(temp - 10) - 305.0447927307
    }
    return Triple(
        r.coerceIn(0.0, 255.0).toInt(),
        g.coerceIn(0.0, 255.0).toInt(),
        b.coerceIn(0.0, 255.0).toInt(),
    )
}

/** HS (hue 0..360, sat 0..1, full value) to RGB, for an hs_color swatch preview. */
fun hsToRgb(hue: Double, sat: Double): Triple<Int, Int, Int> {
    val h = ((hue % 360) + 360) % 360 / 60.0
    val c = sat.coerceIn(0.0, 1.0)
    val x = c * (1 - Math.abs(h % 2 - 1))
    val (r1, g1, b1) = when (h.toInt()) {
        0 -> Triple(c, x, 0.0)
        1 -> Triple(x, c, 0.0)
        2 -> Triple(0.0, c, x)
        3 -> Triple(0.0, x, c)
        4 -> Triple(x, 0.0, c)
        else -> Triple(c, 0.0, x)
    }
    val m = 1 - c
    return Triple(
        Math.round((r1 + m) * 255).toInt(),
        Math.round((g1 + m) * 255).toInt(),
        Math.round((b1 + m) * 255).toInt(),
    )
}
