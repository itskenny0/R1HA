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
)
