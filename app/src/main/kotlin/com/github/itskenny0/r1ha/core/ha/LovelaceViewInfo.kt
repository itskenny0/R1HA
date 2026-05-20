package com.github.itskenny0.r1ha.core.ha

/**
 * A single tab/view from the HA Lovelace dashboard with the entity IDs
 * extracted from its cards — used by the Wear OS tab-navigation screen.
 *
 * [entityIds] preserves the order entities appear in the Lovelace config.
 * [hasRemoteCard] is true when the view contained at least one custom card
 * that looked like a mouse/remote-control card (no extractable entity IDs
 * but the view title suggests it's a remote-control tab).
 */
data class LovelaceViewInfo(
    val title: String,
    val entityIds: List<String>,
    val hasRemoteCard: Boolean = false,
)
