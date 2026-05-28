package com.github.itskenny0.r1ha.core.ha

import androidx.compose.runtime.Stable

/**
 * One row from HA's `config_entries/get` reply: a single configured
 * integration instance (one row per "MQTT (homeassistant)" tile in the
 * HA frontend's Devices & Services page).
 *
 * The native browser is read-mostly + reload-only. Editing, removing,
 * and the multi-step setup flow live in HA's own UI because they're
 * inherently web-driven (dynamic schemas, OAuth handoffs, integration-
 * provided dialogs). Reload covers the common operational case of
 * "this integration is wedged; kick it".
 */
@Stable
data class ConfigEntry(
    val entryId: String,
    /** Integration domain, e.g. "mqtt", "hue", "shelly". */
    val domain: String,
    /** User-facing title HA assigned the entry (often the host or
     *  bridge name; falls back to the domain). */
    val title: String,
    /** How HA discovered the entry: "user" (manually added), "discovery"
     *  (zeroconf / SSDP / etc.), "import" (YAML migration), "reauth". */
    val source: String,
    /** Lifecycle state HA reports. Common values: "loaded" (running
     *  fine), "setup_error" (setup raised), "setup_retry" (will retry
     *  on a timer), "not_loaded" (manually unloaded), "migration_error"
     *  (config-version migration failed), "failed_unload". The UI
     *  colors the chip per state. */
    val state: String,
    val supportsOptions: Boolean,
    val supportsRemoveDevice: Boolean,
    val supportsUnload: Boolean,
    /** When true, HA won't auto-enable new entities the integration
     *  surfaces (the user opted in to per-entity opt-in). */
    val prefDisableNewEntities: Boolean,
    /** Polling-disabled flag for integrations that support a poll
     *  toggle. */
    val prefDisablePolling: Boolean,
    /** Free-text reason HA attached to a non-loaded state (e.g. a
     *  failing endpoint URL). Surfaced under the row when present. */
    val reason: String?,
    /** Who disabled the entry: "user" / "integration" / null when
     *  enabled. */
    val disabledBy: String?,
)
