package com.github.itskenny0.r1ha.core.lovelace.strategies

/**
 * The registry + state snapshot a strategy expansion reads. Mirrors the subset
 * of HA's `hass` object the strategy generators touch (`hass.areas`,
 * `hass.devices`, `hass.entities`, `hass.states`, `hass.floors`, energy prefs,
 * usage prediction). Deliberately plain data so [StrategyEngine] stays pure and
 * unit-testable on fixtures: the live loader ([StrategyDataLoader]) fills these
 * in from the repository, tests build them by hand.
 *
 * Registry-data gaps and the resulting strategy degradations are documented on
 * the individual fields. R1HA can always reach the entity *state* list and the
 * area registry (via templates), so area grouping and original-states grouping
 * are faithful; the device registry and entity registry are reachable over the
 * WS so device grouping and the hidden/category filtering work too. Floors and
 * the usage-prediction WS command may be absent on older servers, and each
 * strategy degrades locally rather than failing the whole expansion.
 */
data class StrategyData(
    /** Every live entity, keyed by raw `domain.object_id`. */
    val states: Map<String, StrategyEntity>,
    /** area_id -> area metadata, in HA's registry order. */
    val areas: Map<String, StrategyArea>,
    /** device_id -> device metadata. */
    val devices: Map<String, StrategyDevice>,
    /** entity_id -> registry entry (area, device, category, hidden/disabled). */
    val entities: Map<String, StrategyRegistryEntity>,
    /** floor_id -> floor metadata, in level order. Empty on a server without floors. */
    val floors: Map<String, StrategyFloor> = emptyMap(),
    /**
     * `true` when the energy integration is configured AND a grid source with a
     * `stat_energy_from` exists. Gates the auto energy-distribution card in
     * original-states. Computed by the loader; false when unknown.
     */
    val hasEnergyGrid: Boolean = false,
    /**
     * The usage-prediction common-control list (ordered) when the server's
     * `usage_prediction/common_control` command answered, else null. Null means
     * the integration isn't loaded, so the common-controls strategy falls back
     * to recently-changed toggleables.
     */
    val commonControls: List<String>? = null,
    /**
     * HA `hass.config.state == STATE_NOT_RUNNING`. When true every strategy
     * returns the "starting" placeholder rather than a real layout.
     */
    val starting: Boolean = false,
    /** HA `hass.config.recovery_mode`. When true strategies return the
     *  recovery-mode placeholder. */
    val recoveryMode: Boolean = false,
)

/** One entity's live state, slimmed to the attributes the strategies read. */
data class StrategyEntity(
    val entityId: String,
    val friendlyName: String,
    /** Lower-cased raw state string. */
    val state: String,
    /** Epoch millis of the entity's last_changed (drives the recently-changed
     *  fallback for common-controls). */
    val lastChangedMs: Long = 0L,
    /** `true` when the entity reports a non-empty `entity_picture` attribute
     *  (person/camera/image tiles set `show_entity_picture`). */
    val hasEntityPicture: Boolean = false,
    /** HA `hvac_modes` length, used to decide whether a thermostat gets the
     *  hvac-modes feature in original-states. */
    val hvacModesCount: Int = 0,
    /** HA `device_class` attribute (lower-cased). Used by the area-strategy
     *  cover group to pull door/window/garage binary_sensors. Null when unset. */
    val deviceClass: String? = null,
) {
    val domain: String get() = entityId.substringBefore('.', "")
}

/** One area-registry entry. */
data class StrategyArea(
    val areaId: String,
    val name: String,
    val floorId: String? = null,
    val icon: String? = null,
    val temperatureEntityId: String? = null,
    val humidityEntityId: String? = null,
)

/** One device-registry entry (only the grouping fields). */
data class StrategyDevice(
    val id: String,
    val displayName: String,
    val areaId: String? = null,
)

/** One entity-registry entry (the filtering + assignment fields). */
data class StrategyRegistryEntity(
    val entityId: String,
    val areaId: String? = null,
    val deviceId: String? = null,
    val platform: String? = null,
    val entityCategory: String? = null,
    val hiddenBy: String? = null,
    val disabledBy: String? = null,
)

/** One floor-registry entry. */
data class StrategyFloor(
    val floorId: String,
    val name: String,
    val level: Int? = null,
    val icon: String? = null,
)
