package com.github.itskenny0.r1ha.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * Strict-connection-mode multiplier applied to every [AutoRefresh] cadence. 1 (the default,
 * provided when strict mode is off) leaves every surface polling at its configured interval;
 * a higher value makes the Dashboard / Notifications / Logbook / Weather / Persons / Calendars
 * surfaces poll proportionally less often, cutting background request volume against a strict HA.
 * Provided once high in the tree (MainActivity) from the user's ConnectionSettings.
 */
val LocalBackgroundRefreshMultiplier = staticCompositionLocalOf { 1 }

/**
 * Re-runs [block] on entry and then every [everyMillis] for as long
 * as the composable is part of the composition AND the host
 * lifecycle is STARTED or RESUMED (i.e. the activity is visible).
 *
 * Polling pauses on PAUSE (app sent to background), resumes on
 * RESUME (app foregrounded again) — repeatOnLifecycle handles the
 * suspend/resume orchestration. This stops the dashboard / logbook /
 * etc. from beating the network on a handheld R1 left running in
 * the user's pocket.
 *
 * Replaces the per-screen `LaunchedEffect(Unit) { while(true) { ... ;
 * delay(N) } }` pattern that landed across the Dashboard,
 * Notifications, Logbook, Weather, Persons, and Calendars screens.
 * Same semantics, one call site to tune.
 */
@Composable
fun AutoRefresh(everyMillis: Long, block: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // Stretch the cadence by the strict-mode multiplier. Keyed below so flipping strict mode
    // re-arms the loop at the new interval rather than waiting out the current delay.
    val multiplier = LocalBackgroundRefreshMultiplier.current.coerceAtLeast(1)
    val effectiveMillis = everyMillis * multiplier
    LaunchedEffect(effectiveMillis, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                block()
                delay(effectiveMillis)
            }
        }
    }
}
