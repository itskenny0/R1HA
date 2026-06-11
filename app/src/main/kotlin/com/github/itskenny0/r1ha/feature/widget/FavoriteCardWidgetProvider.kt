package com.github.itskenny0.r1ha.feature.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import com.github.itskenny0.r1ha.App
import com.github.itskenny0.r1ha.MainActivity
import com.github.itskenny0.r1ha.R
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Home-screen widget that renders one R1HA favorite as a live card: glyph,
 * display name, current state and the card's accent — the in-app card look,
 * painted into a Bitmap because RemoteViews can't host Compose. Each instance
 * binds to one entity via [FavoriteCardWidgetConfigActivity]; the binding
 * lives in [FavoriteCardWidgetStore].
 *
 * Refresh model: a one-shot REST `/api/states` fetch per update broadcast
 * (one fetch covers every instance, however many favorites are bound), driven
 * by the 30-minute updatePeriodMillis in the provider XML plus an immediate
 * repaint after configuration and on host resize. No WebSocket is opened from
 * the widget path — a persistent socket from a launcher process would fight
 * Doze for power, which is why the original quick-launch widget refused live
 * data entirely; the bounded poll here is the deliberate middle ground.
 *
 * Tap: toggle-capable domains (light / switch / input_boolean / fan / cover)
 * and action domains (scene / script / button) act in place via a self-
 * broadcast that fires the REST service call and repaints; read-only domains,
 * signed-out installs and unconfigured instances open the app instead.
 */
class FavoriteCardWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        refreshAsync(context, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        // Repaint at the new cell size so the bitmap isn't stretched.
        refreshAsync(context, intArrayOf(appWidgetId))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        FavoriteCardWidgetStore.unbind(context, appWidgetIds)
    }

    override fun onDisabled(context: Context) {
        FavoriteCardWidgetStore.clearAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_CARD_TAP) {
            val widgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                handleTapAsync(context, widgetId)
            }
            return
        }
        super.onReceive(context, intent)
    }

    /**
     * Fetch + repaint on a background coroutine, holding the broadcast alive
     * via goAsync until the network round-trip lands. Failures degrade to the
     * stale-data dash card rather than crashing the host's binder call.
     */
    private fun refreshAsync(context: Context, widgetIds: IntArray) {
        if (widgetIds.isEmpty()) return
        val result = goAsync()
        scope.launch {
            try {
                renderAll(context, widgetIds)
            } catch (t: Throwable) {
                R1Log.w(TAG, "refresh failed: ${t.message}")
            } finally {
                result.finish()
            }
        }
    }

    /**
     * Single tap on an act-in-place card: fire the toggle / activation over
     * REST, give HA a moment to settle (same 600 ms the quick tiles use, so
     * the repaint shows the echoed state, not the stale one), then repaint.
     */
    private fun handleTapAsync(context: Context, widgetId: Int) {
        val result = goAsync()
        scope.launch {
            try {
                val graph = (context.applicationContext as App).graph
                val rawId = FavoriteCardWidgetStore.entityFor(context, widgetId)
                val entityId = rawId?.let { runCatching { EntityId(it) }.getOrNull() }
                if (entityId != null) {
                    val live = graph.haRepository.listAllEntitiesForSearch().getOrNull()
                        ?.firstOrNull { it.id.value == entityId.value }
                    // Don't toggle an unavailable entity (HA would reject the
                    // call); actions stay fireable, mirroring the quick tiles.
                    if (live != null && (live.isAvailable || live.id.domain.isAction)) {
                        val call = ServiceCall.tapAction(live.id, live.isOn)
                        // One-shot REST dispatch — the repo's call() rides the
                        // WebSocket, which the widget path never opens.
                        val payload = buildJsonObject {
                            call.data.forEach { (k, v) -> put(k, v) }
                            put("entity_id", JsonPrimitive(entityId.value))
                        }
                        graph.haRepository
                            .callRawService(call.haDomain, call.service, payload)
                            .onFailure { R1Log.w(TAG, "${entityId.value}/${call.service} failed: ${it.message}") }
                        delay(600L)
                    }
                }
                renderAll(context, intArrayOf(widgetId))
            } catch (t: Throwable) {
                R1Log.w(TAG, "tap failed: ${t.message}")
            } finally {
                result.finish()
            }
        }
    }

    private suspend fun renderAll(context: Context, widgetIds: IntArray) {
        val manager = AppWidgetManager.getInstance(context)
        val graph = (context.applicationContext as App).graph
        val settings = runCatching { graph.settings.settings.first() }.getOrNull()
        val signedIn = settings?.server != null
        val anyBound = widgetIds.any { FavoriteCardWidgetStore.entityFor(context, it) != null }
        // One fetch serves every instance in this broadcast. The search
        // variant keeps unmodelled domains (device_tracker etc.) so any
        // favorite renders, and it carries the same 401-refresh retry the
        // in-app pickers rely on. Failure → null → dash cards with stale-data
        // semantics rather than an error card.
        val states: Map<String, EntityState>? = if (signedIn && anyBound) {
            graph.haRepository.listAllEntitiesForSearch().getOrNull()
                ?.associateBy { it.id.value }
        } else {
            null
        }
        for (widgetId in widgetIds) {
            val views = buildViews(context, manager, widgetId, settings, signedIn, states)
            manager.updateAppWidget(widgetId, views)
        }
    }

    private fun buildViews(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        settings: AppSettings?,
        signedIn: Boolean,
        states: Map<String, EntityState>?,
    ): RemoteViews {
        val entityId = FavoriteCardWidgetStore.entityFor(context, widgetId)
        val model: FavoriteCardModel
        val tapPending: PendingIntent
        when {
            entityId == null -> {
                model = placeholderModel("Favorite card", "TAP TO SET UP")
                tapPending = configPending(context, widgetId)
            }
            !signedIn || settings == null -> {
                model = placeholderModel("R1HA", "SIGNED OUT")
                tapPending = openAppPending(context, widgetId)
            }
            else -> {
                model = buildFavoriteCardModel(entityId, states?.get(entityId), settings)
                val domain = runCatching { EntityId(entityId).domain }.getOrDefault(Domain.OTHER)
                tapPending = if (widgetTapActsInPlace(domain)) {
                    tapPending(context, widgetId)
                } else {
                    openAppPending(context, widgetId)
                }
            }
        }
        val (wPx, hPx) = widgetSizePx(context, manager, widgetId)
        val bitmap = FavoriteCardRenderer.render(
            model,
            wPx,
            hPx,
            context.resources.displayMetrics.density,
            cornerPx = systemWidgetCornerPx(context),
        )
        return RemoteViews(context.packageName, R.layout.favorite_card_widget).apply {
            setImageViewBitmap(R.id.favorite_card_image, bitmap)
            setContentDescription(R.id.favorite_card_image, "${model.name}: ${model.stateText}")
            setOnClickPendingIntent(R.id.favorite_card_root, tapPending)
        }
    }

    /** Setup / signed-out stand-in card: neutral grey, no live data. */
    private fun placeholderModel(name: String, stateText: String) = FavoriteCardModel(
        entityId = "",
        name = name,
        glyph = "·",
        stateText = stateText,
        accentArgb = 0xFFB0B0B0.toInt(),
        available = true,
    )

    /**
     * Current widget cell size in pixels, from the host's options bundle.
     * minWidth x maxHeight is the portrait-orientation convention; zero /
     * missing options (older launchers right after placement) fall back to
     * the provider XML's default 3x2-cell footprint. Capped so a maximal
     * resize can't exceed the RemoteViews bitmap transport budget.
     */
    private fun widgetSizePx(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
    ): Pair<Int, Int> {
        val options = manager.getAppWidgetOptions(widgetId)
        val wDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            .takeIf { it > 0 } ?: DEFAULT_WIDTH_DP
        val hDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            .takeIf { it > 0 } ?: DEFAULT_HEIGHT_DP
        val density = context.resources.displayMetrics.density
        val w = (wDp * density).toInt().coerceIn(48, 1200)
        val h = (hDp * density).toInt().coerceIn(48, 800)
        return w to h
    }

    /**
     * The corner radius the launcher clips this widget to. Android 12+ exposes
     * it as `android.R.dimen.system_app_widget_background_radius`; drawing the
     * card with the same radius keeps our border visible all the way around
     * instead of being sliced off at the launcher's rounder corners. Pre-31
     * launchers don't clip, so the card keeps the in-app 4dp idiom there.
     */
    private fun systemWidgetCornerPx(context: Context): Float {
        val density = context.resources.displayMetrics.density
        if (android.os.Build.VERSION.SDK_INT < 31) return 4f * density
        return runCatching {
            context.resources.getDimension(android.R.dimen.system_app_widget_background_radius)
        }.getOrDefault(16f * density)
    }

    private fun tapPending(context: Context, widgetId: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            widgetId,
            Intent(context, FavoriteCardWidgetProvider::class.java).apply {
                action = ACTION_CARD_TAP
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openAppPending(context: Context, widgetId: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            widgetId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun configPending(context: Context, widgetId: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            widgetId,
            Intent(context, FavoriteCardWidgetConfigActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        private const val TAG = "FavoriteCardWidget"
        const val ACTION_CARD_TAP = "com.github.itskenny0.r1ha.action.FAVORITE_CARD_TAP"

        /** Matches the provider XML's 3x2-cell default footprint. */
        private const val DEFAULT_WIDTH_DP = 180
        private const val DEFAULT_HEIGHT_DP = 110

        /**
         * Process-scoped IO scope shared by every broadcast (provider
         * instances are throwaway; the work must outlive them up to the
         * goAsync window). SupervisorJob so one failed repaint doesn't
         * cancel a sibling's.
         */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Ask the host to repaint [widgetId] now — used right after configuration. */
        fun requestUpdate(context: Context, widgetId: Int) {
            val intent = Intent(context, FavoriteCardWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
            }
            context.sendBroadcast(intent)
        }
    }
}
