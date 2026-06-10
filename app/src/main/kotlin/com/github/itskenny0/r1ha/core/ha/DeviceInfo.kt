package com.github.itskenny0.r1ha.core.ha

import androidx.compose.runtime.Stable

/**
 * One row from HA's `config/device_registry/list` reply. The device
 * registry is HA's flat list of every physical / logical device an
 * integration has surfaced (a Hue bulb, a Z-Wave switch, the Hue Bridge
 * itself); entities then point back to a device via `device_id`.
 *
 * Only the fields the browse surface renders are carried here. HA's
 * payload also includes `entry_type`, `serial_number` etc.; pull them
 * in as needed.
 */
@Stable
data class DeviceInfo(
    /** Stable server-assigned id, e.g. "abc123def456…". */
    val id: String,
    /** Integration-supplied name; user override [nameByUser] wins when present. */
    val name: String?,
    /** User-set rename via the HA frontend. When non-null, displayed in
     *  place of [name]. */
    val nameByUser: String?,
    val manufacturer: String?,
    val model: String?,
    /** Area assignment id (matches [AreaInfo.areaId]); null when the device
     *  is unassigned. */
    val areaId: String?,
    /** Set by HA when the user (or an integration) has disabled the device.
     *  Values include "user", "integration", "config_entry". Null means
     *  enabled. */
    val disabledBy: String?,
    /** Parent device id when this device sits behind a hub (Hue bulb →
     *  Hue Bridge). Null for top-level devices. */
    val viaDeviceId: String?,
    val swVersion: String?,
    val hwVersion: String?,
    /** Optional integration-provided link to the device's web admin UI
     *  (e.g. a router's local IP). Surfaced as a chip on the drill-in. */
    val configurationUrl: String?,
    /** Integration identifiers as (domain, id) pairs, e.g.
     *  ("zha", "00:11:22:33"). HA sends these as a JSON array of 2-tuples. */
    val identifiers: List<Pair<String, String>> = emptyList(),
    /** Physical connections as (type, value) pairs, e.g. ("mac", "aa:bb:..").
     *  HA sends these as a JSON array of 2-tuples. */
    val connections: List<Pair<String, String>> = emptyList(),
) {
    /** Display name; user override wins, otherwise integration name,
     *  otherwise the bare id as a last-resort label. */
    val displayName: String
        get() = nameByUser?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: id
}

/**
 * One row from HA's `config/entity_registry/list` reply. Slim: only the
 * fields the device drill-in needs to render its entity list. The full
 * registry entry carries far more (capabilities, options, original_*),
 * none of which the read-only browser surfaces today.
 */
@Stable
data class EntityRegistryEntry(
    val entityId: String,
    /** User rename via the HA frontend; falls back to [originalName] then
     *  the entity id when null. */
    val name: String?,
    val originalName: String?,
    /** Owning device, when one exists. Most entities have one; some
     *  helpers (input_*, template) don't and surface a null. */
    val deviceId: String?,
    val areaId: String?,
    val platform: String?,
    val disabledBy: String?,
    val hiddenBy: String?,
    /**
     * HA's `entity_category` (`config` / `diagnostic` / null). The original-states
     * and areas strategies treat any categorised entity as a "config/diagnostic"
     * helper and exclude it from the main grouped cards (HA's
     * `computeDefaultViewStates` filters `entry.entity_category`, and the area
     * grouping filters use `entity_category: "none"`). Null = a primary entity.
     */
    val entityCategory: String? = null,
) {
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: originalName?.takeIf { it.isNotBlank() }
            ?: entityId
}
