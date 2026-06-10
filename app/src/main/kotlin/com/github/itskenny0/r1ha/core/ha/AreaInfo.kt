package com.github.itskenny0.r1ha.core.ha

/**
 * One entry from HA's `config/area_registry/list` reply. The registry tracks
 * the user's logical areas (kitchen, bedroom, garage, etc.) and is the
 * source-of-truth for entity-area assignment.
 *
 * HA's payload includes more fields (aliases, icon, picture); we carry the
 * fields the picker UI and the name resolver use. Future expansion would
 * append fields without breaking the existing call sites.
 */
data class AreaInfo(
    /** Stable server-assigned id, e.g. "kitchen". */
    val areaId: String,
    /** Human-friendly label, e.g. "Kitchen". */
    val name: String,
    /**
     * The floor this area belongs to, when the user has organised areas
     * into floors. Null when no floor assignment exists. Corresponds to
     * the `floor_id` field in HA's `config/area_registry/list` payload.
     */
    val floorId: String? = null,
    /** HA area-registry `icon`. Null when the area uses the default. */
    val icon: String? = null,
    /** HA area-registry `picture` (a static URL). Null when unset. */
    val picture: String? = null,
    /**
     * HA area-registry `temperature_entity_id`: the user-chosen representative
     * temperature sensor for the area. The areas strategy renders this as a red
     * badge on the per-area subview. Null when no representative sensor is set.
     */
    val temperatureEntityId: String? = null,
    /** HA area-registry `humidity_entity_id`: the representative humidity sensor.
     *  Rendered as an indigo badge on the per-area subview. Null when unset. */
    val humidityEntityId: String? = null,
)

/**
 * One entry from HA's `config/floor_registry/list` reply (HA 2024.x). Floors
 * organise areas into levels (Ground Floor, First Floor, Basement). Carried so
 * the areas / home strategies can section their area-card overview by floor.
 */
data class FloorInfo(
    /** Stable server-assigned id, e.g. "ground_floor". */
    val floorId: String,
    /** Human-friendly label, e.g. "Ground Floor". */
    val name: String,
    /** HA `level` ordering integer (lower = lower floor). Null when unset. */
    val level: Int? = null,
    /** HA floor-registry `icon`. Null when the floor uses HA's level default. */
    val icon: String? = null,
)
