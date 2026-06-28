package com.github.itskenny0.r1ha.feature.widget

/**
 * A second favorite-card widget whose only difference is a single-cell default
 * footprint, so users can drop a 1x1 tile straight from the picker on launchers
 * with unreliable resize (FireOS among them). Everything else is inherited:
 * the renderer paints the compact face automatically at this size, and tap,
 * live refresh, and configuration all key off the widget id through the base
 * [FavoriteCardWidgetProvider], so no logic is duplicated here.
 */
class FavoriteCardWidgetProviderCompact : FavoriteCardWidgetProvider()
