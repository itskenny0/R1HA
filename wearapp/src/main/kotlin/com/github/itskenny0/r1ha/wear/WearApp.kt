package com.github.itskenny0.r1ha.wear

import android.app.Application
import com.github.itskenny0.r1ha.AppGraph
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Wear OS Application entry point.
 *
 * Reuses [AppGraph] (from the shared :app source set) unchanged — all core
 * services (HA WebSocket, DataStore prefs, TokenStore, WheelInput) are
 * platform-agnostic and run identically on watch hardware. The only
 * Wear-specific concern here is that we skip the phone-app crash logger and
 * self-updater, both of which live in the phone-only `App` class.
 *
 * The [graph] property is accessed from [WearMainActivity] via
 * `(application as WearApp).graph`.
 */
class WearApp : Application() {

    val graph: AppGraph by lazy { AppGraph(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        R1Log.i("WearApp.onCreate", "starting HA repository")
        appScope.launch {
            graph.haRepository.start()
        }
    }
}
