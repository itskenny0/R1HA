package com.github.itskenny0.r1ha.feature.quicktile

import android.os.Build
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.github.itskenny0.r1ha.App
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Android Quick-Settings tile bound to slot A (the legacy default). The bound entity_id
 * lives in [AppSettings.behavior.quickTileEntityId]; empty/null = the tile shows a
 * 'tap to set up' label and opens the app on tap.
 *
 * Concrete subclass of an abstract base because Android requires one TileService class
 * per declared tile. Subclasses for slots B/C/D differ only in the [entityIdSelector]
 * they hand to [QuickTileLogic].
 */
@RequiresApi(Build.VERSION_CODES.N)
class HaQuickTileService : BaseQuickTileService() {
    override fun entityIdSelector(s: AppSettings): String? = s.behavior.quickTileEntityId
}

/** Slot B. Bound to [AppSettings.behavior.quickTileEntityIdB]. */
@RequiresApi(Build.VERSION_CODES.N)
class HaQuickTileServiceB : BaseQuickTileService() {
    override fun entityIdSelector(s: AppSettings): String? = s.behavior.quickTileEntityIdB
}

/** Slot C. Bound to [AppSettings.behavior.quickTileEntityIdC]. */
@RequiresApi(Build.VERSION_CODES.N)
class HaQuickTileServiceC : BaseQuickTileService() {
    override fun entityIdSelector(s: AppSettings): String? = s.behavior.quickTileEntityIdC
}

/** Slot D. Bound to [AppSettings.behavior.quickTileEntityIdD]. */
@RequiresApi(Build.VERSION_CODES.N)
class HaQuickTileServiceD : BaseQuickTileService() {
    override fun entityIdSelector(s: AppSettings): String? = s.behavior.quickTileEntityIdD
}

// Quick Settings tiles arrived in API 24. Below that the OS never binds these
// services (the feature doesn't exist) and the manifest <service> entries are
// inert, so gating the classes here loses nothing on older devices.
@RequiresApi(Build.VERSION_CODES.N)
abstract class BaseQuickTileService : TileService() {

    /** SupervisorJob so a single failed toggle doesn't cascade into killing the next refresh. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Concrete subclasses return the [AppSettings.behavior] field that holds this tile's
     *  bound entity_id. Pure function so [QuickTileLogic] can read it fresh on every call
     *  without state lifecycle worries. */
    protected abstract fun entityIdSelector(s: AppSettings): String?

    override fun onCreate() {
        super.onCreate()
        // Eagerly warm the dependency graph so the first onClick doesn't pay the
        // DataStore disk-read latency.
        runCatching { (applicationContext as App).graph }
    }

    override fun onStartListening() {
        super.onStartListening()
        QuickTileLogic.refresh(applicationContext, qsTile, scope, ::entityIdSelector)
    }

    override fun onClick() {
        super.onClick()
        QuickTileLogic.click(
            context = applicationContext,
            qsTile = qsTile,
            scope = scope,
            selector = ::entityIdSelector,
            launchAppForSetup = ::launchAppForSetup,
        )
    }

    private fun launchAppForSetup() {
        val ctx = applicationContext
        val launchIntent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?: return
        launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        // startActivityAndCollapse is the Quick-Settings panel idiom for launching a
        // foreground activity. Two overloads:
        //   API ≥ 34: takes a PendingIntent
        //   API 33: only the Intent overload exists (deprecated in 34)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val pi = android.app.PendingIntent.getActivity(
                ctx,
                0,
                launchIntent,
                android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launchIntent)
        }
    }
}
