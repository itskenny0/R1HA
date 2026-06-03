package com.github.itskenny0.r1ha.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Ambient controller that lets ANY screen's [R1TopBar] surface a pin / unpin toggle
 * without the screen having to know about the pin feature. The host (MainActivity)
 * provides one instance via [LocalSurfacePinner], built from the current nav route +
 * the persisted [com.github.itskenny0.r1ha.core.prefs.NavPanelSettings.pinnedSurfaces];
 * the top bar reads it and, when [pinnable] is true, draws a pin button that calls
 * [toggle].
 *
 * Keeping this as a CompositionLocal (rather than threading a parameter through every
 * screen) is what makes the affordance light up generically: the top bar is the single
 * choke point every sub-screen already routes through, so one provider at the NavHost
 * level lights every surface at once. [LocalSurfacePinner] defaults to null so any
 * composition that doesn't provide one (tests, previews, the card-stack chrome which
 * isn't an R1TopBar) simply shows no pin button.
 */
@Immutable
data class SurfacePinController(
    /** The route currently shown, used to decide pinnability + pinned-state. */
    val currentRoute: String?,
    /** True when [currentRoute] is a surface the user is allowed to pin. */
    val pinnable: Boolean,
    /** True when [currentRoute] is currently in the user's pinned list. */
    val isPinned: Boolean,
    /** Toggle the current route's pinned state. No-op when [pinnable] is false. */
    val toggle: () -> Unit,
)

/** Ambient pin controller; null when no host provides one (no pin button shown). */
val LocalSurfacePinner = staticCompositionLocalOf<SurfacePinController?> { null }
