package com.github.itskenny0.r1ha.ui.components

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Ambient controller that lets the card-stack chrome open the navigation slide-out
 * without owning its state. The shell ([AdaptiveNavShell]) hosts the slide-out panel on
 * portrait phone tiers and provides one instance via [LocalNavDrawerController]; the
 * card-stack hamburger reads it and, when [available] is true, calls [open] instead of
 * raising the QuickActions modal.
 *
 * [available] folds together every precondition the host already resolved: a portrait
 * phone tier, the side panel enabled, and the user's
 * [com.github.itskenny0.r1ha.core.prefs.PhoneNavStyle] set to SLIDEOUT. When it is false
 * the controller is inert ([open] is a no-op) so the hamburger falls back to the modal.
 * Defaults to null so any composition without a host (tablet tiers, tests, previews)
 * simply has no slide-out and keeps the modal.
 */
@Stable
class NavDrawerController(
    val available: Boolean,
    private val openState: MutableState<Boolean>,
) {
    /** Whether the slide-out is currently shown. Reading this in a composable subscribes
     *  to it, so the card stack can fold it into its wheel modal-gate. */
    val isOpen: Boolean get() = openState.value

    /** Open the slide-out. No-op when [available] is false. */
    fun open() {
        if (available) openState.value = true
    }

    /** Close the slide-out. */
    fun close() {
        openState.value = false
    }
}

/** Ambient nav-drawer controller; null when no host provides one (no slide-out shown). */
val LocalNavDrawerController = staticCompositionLocalOf<NavDrawerController?> { null }
