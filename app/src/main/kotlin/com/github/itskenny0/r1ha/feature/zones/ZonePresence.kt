package com.github.itskenny0.r1ha.feature.zones

import androidx.compose.runtime.Stable
import java.util.Locale
import kotlin.math.cos

/**
 * Pure, Android-free derivation helpers for the Zones surface. Kept free of
 * Compose and HA types so they can be unit-tested directly. The screen and
 * view-model decode raw `/api/states` rows into the plain inputs below, run
 * these helpers, and render the results.
 *
 * Two responsibilities live here:
 *   - zone-membership resolution: matching each person / device_tracker to a
 *     zone by HA's own rule (state equals the zone friendly_name, plus the
 *     "home" special-case for the configured home zone);
 *   - lat/long-to-canvas projection: turning a geographic point into a canvas
 *     fraction inside the abstract map's bounding box.
 */

/** A minimal zone description decoded from a `zone.*` row. */
@Stable
data class ZoneInput(
    val entityId: String,
    val name: String,
    val latitude: Double?,
    val longitude: Double?,
    val radiusMeters: Double?,
    /** HA `icon` attribute, e.g. "mdi:home". Null when none configured. */
    val icon: String?,
    /**
     * True for HA's configured home zone. HA core does not expose an `is_home`
     * state attribute; the home zone is canonically the entity `zone.home`. The
     * reserved person/tracker state `"home"` resolves to this zone, so getting
     * this flag right is what keeps people who are at home from spilling into
     * the OUTSIDE bucket.
     */
    val isHome: Boolean,
    /**
     * HA `passive` attribute. Passive zones still detect presence but do not set
     * a person's state to the zone name, so HA treats them as orientation-only
     * geofences. Surfaced so the UI can mark them the way the HA map does.
     */
    val passive: Boolean = false,
)

/**
 * A person / device_tracker decoded from a `person.*` or `device_tracker.*`
 * row. [state] is the raw HA state (a zone friendly_name, "home", "not_home",
 * etc.). [latitude]/[longitude] are present when the tracker reports GPS.
 */
@Stable
data class TrackedInput(
    val entityId: String,
    val name: String,
    val state: String,
    val latitude: Double?,
    val longitude: Double?,
)

/** A resolved zone with the names of everyone currently inside it. */
@Stable
data class ResolvedZone(
    val entityId: String,
    val name: String,
    val latitude: Double?,
    val longitude: Double?,
    val radiusMeters: Double?,
    val icon: String?,
    val isHome: Boolean,
    val passive: Boolean,
    val occupants: List<String>,
)

/**
 * A tracked entity to plot on the abstract map. Only those reporting GPS make
 * it this far. [inZoneName] is the zone friendly_name they currently match (or
 * "home"/null when outside), used purely for colour grouping.
 */
@Stable
data class MappableTracker(
    val entityId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val home: Boolean,
)

/** Result bundle from [resolveZoneMembership]. */
@Stable
data class ZoneResolution(
    val zones: List<ResolvedZone>,
    /** Names of people/trackers not matched to any zone (away/unknown). */
    val outside: List<String>,
)

/** States that mean "not inside any configured zone". */
private val OUTSIDE_STATES = setOf("not_home", "away", "unknown", "unavailable", "")

/**
 * Resolve who is inside each zone. HA reports a person/device_tracker's
 * location as the friendly_name of whatever zone contains them, the reserved
 * "home" for the configured home zone, "not_home"/"away" when outside every
 * zone, or "unknown"/"unavailable" when location is lost.
 *
 * Matching rules (mirroring HA):
 *   - state == zone friendly_name (case-insensitive) puts the entity in that
 *     zone;
 *   - state == "home" puts the entity in whichever zone is flagged is_home;
 *   - outside/unknown states collect under [ZoneResolution.outside].
 *
 * Zones are returned most-occupied first so the busiest places sort to the
 * top of the list, with name as a stable tie-breaker so equal-occupancy zones
 * keep a fixed order across refreshes (an unstable sort would let rows swap
 * places and jump the scroll position). Occupant names are de-duplicated.
 */
fun resolveZoneMembership(
    zones: List<ZoneInput>,
    trackers: List<TrackedInput>,
): ZoneResolution {
    // The home zone is resolved first and authoritatively: the reserved state
    // "home" belongs to it and to no one else, even if a second zone happens to
    // be named "Home". Keep its lower-cased name so a tracker reporting the home
    // zone by name routes there too (and isn't double-counted by a twin).
    val homeZone = zones.firstOrNull { it.isHome }
    val homeName = homeZone?.name?.trim()?.lowercase(Locale.US)

    // Index non-home zones by lower-cased name for a name-state match. The home
    // zone is excluded so the reserved "home" state, not the name bucket, is
    // what fills it; a non-home zone literally named "home" would otherwise
    // steal the reserved state.
    val zoneByName = HashMap<String, ZoneInput>()
    for (z in zones) {
        if (z.isHome) continue
        zoneByName.putIfAbsent(z.name.trim().lowercase(Locale.US), z)
    }

    // Each tracker resolves to at most one zone, recorded by entity_id so the
    // same tracker can never be counted in two zones (the old friendly_name +
    // "home" double-count) and de-duping keys on the stable id, not the name.
    val occupantsByZoneEntity = HashMap<String, LinkedHashMap<String, String>>()
    val outside = LinkedHashMap<String, String>()
    for (t in trackers) {
        val key = t.state.trim().lowercase(Locale.US)
        if (key in OUTSIDE_STATES) {
            // Away / unknown / unavailable: surfaced under the OUTSIDE bucket.
            outside.putIfAbsent(t.entityId, t.name)
            continue
        }
        val target: ZoneInput? = when {
            // "home" resolves strictly to the configured home zone.
            key == "home" -> homeZone
            // A tracker reporting the home zone by friendly_name is also at home.
            homeName != null && key == homeName -> homeZone
            else -> zoneByName[key]
        }
        // A non-outside state that matches no zone (e.g. "home" with no home zone
        // configured, or an unknown zone name) places the tracker nowhere:
        // neither in a zone nor genuinely outside, so it is dropped rather than
        // wrongly bucketed under OUTSIDE.
        if (target != null) {
            occupantsByZoneEntity
                .getOrPut(target.entityId) { LinkedHashMap() }
                .putIfAbsent(t.entityId, t.name)
        }
    }

    val resolved = zones.map { z ->
        // Keyed on entity_id above so a tracker can't count twice; the display
        // list is then name-de-duped so a person and their identically named
        // phone tracker collapse to one "Alice" rather than "Alice · Alice".
        val occupants = occupantsByZoneEntity[z.entityId]?.values?.distinct().orEmpty()
        ResolvedZone(
            entityId = z.entityId,
            name = z.name,
            latitude = z.latitude,
            longitude = z.longitude,
            radiusMeters = z.radiusMeters,
            icon = z.icon,
            isHome = z.isHome,
            passive = z.passive,
            occupants = occupants,
        )
    }.sortedWith(
        compareByDescending<ResolvedZone> { it.occupants.size }
            .thenBy { it.name.lowercase(Locale.US) }
            .thenBy { it.entityId },
    )
    return ZoneResolution(zones = resolved, outside = outside.values.distinct())
}

/**
 * Select the trackers that can be drawn on the abstract map: those reporting a
 * finite lat/lon. The [home] flag is true when the tracker's state resolves to
 * the configured home zone (state "home" or that zone's friendly_name), used
 * only to colour-group the plotted markers.
 */
fun mappableTrackers(
    trackers: List<TrackedInput>,
    homeZoneName: String?,
): List<MappableTracker> {
    val home = homeZoneName?.trim()?.lowercase(Locale.US)
    return trackers.mapNotNull { t ->
        val lat = t.latitude ?: return@mapNotNull null
        val lon = t.longitude ?: return@mapNotNull null
        if (!lat.isFinite() || !lon.isFinite()) return@mapNotNull null
        val key = t.state.trim().lowercase(Locale.US)
        MappableTracker(
            entityId = t.entityId,
            name = t.name,
            latitude = lat,
            longitude = lon,
            home = key == "home" || (home != null && key == home),
        )
    }
}

/** A geographic bounding box, padded so edge markers keep a margin. */
@Stable
data class GeoBounds(
    val latMin: Double,
    val latMax: Double,
    val lonMin: Double,
    val lonMax: Double,
) {
    val latSpan: Double get() = (latMax - latMin).takeIf { it > 1e-9 } ?: 0.01
    val lonSpan: Double get() = (lonMax - lonMin).takeIf { it > 1e-9 } ?: 0.01
    val midLat: Double get() = (latMin + latMax) / 2.0
}

/**
 * Bounding box across every supplied lat/lon point. Returns null when there is
 * nothing to bound. Callers union zone centres and tracker positions so the
 * map frames both.
 */
fun geoBounds(points: List<Pair<Double, Double>>): GeoBounds? {
    if (points.isEmpty()) return null
    val lats = points.map { it.first }
    val lons = points.map { it.second }
    return GeoBounds(
        latMin = lats.min(),
        latMax = lats.max(),
        lonMin = lons.min(),
        lonMax = lons.max(),
    )
}

/**
 * Project a geographic point onto the unit canvas square. Returns the (x, y)
 * fraction in [0..1], where x grows east and y grows *down* (north is up, so
 * the latitude axis is inverted). Points are normalised into the inner [margin
 * .. 1-margin] band so markers near the bounding-box edge keep clear of the
 * canvas border.
 *
 * Pure and side-effect free: the Canvas multiplies the returned fractions by
 * its pixel width/height. Tested directly.
 */
fun projectToCanvasFraction(
    latitude: Double,
    longitude: Double,
    bounds: GeoBounds,
    margin: Float = 0.1f,
): Pair<Float, Float> {
    val inner = 1f - 2f * margin
    val xFrac = ((longitude - bounds.lonMin) / bounds.lonSpan).toFloat() * inner + margin
    val yRaw = ((latitude - bounds.latMin) / bounds.latSpan).toFloat() * inner + margin
    return xFrac to (1f - yRaw)
}

/** Approximate metres covered by one degree of longitude at [midLat]. */
fun metersPerLonDegree(midLat: Double): Double =
    111_320.0 * cos(Math.toRadians(midLat))

/** Metres per degree of latitude is near-constant across the globe. */
const val METERS_PER_LAT_DEGREE: Double = 111_320.0
