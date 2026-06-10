package com.github.itskenny0.r1ha.feature.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.github.itskenny0.r1ha.MainActivity
import com.github.itskenny0.r1ha.R

/**
 * Home-screen widget — a single-tile quick-launch tile that opens R1HA's
 * main activity. Deliberately stays a dumb shortcut: no entity binding, no
 * poll, no per-instance state. Users who want live entity data on the home
 * screen use [FavoriteCardWidgetProvider], which accepts the RemoteViews +
 * bounded-poll tradeoffs this tile avoids; keeping the two widgets separate
 * means this one never wakes the network and never needs configuration.
 *
 * Tap target: the whole tile fires a single PendingIntent to MainActivity.
 * Future expansion: a configuration activity could let the user pick an
 * initial_route (assist / search / dashboard / today) — for now we always
 * launch the default screen so the widget is a clean app shortcut.
 */
class R1haWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) {
            updateOne(context, appWidgetManager, id)
        }
    }

    private fun updateOne(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
    ) {
        val views = RemoteViews(context.packageName, R.layout.r1ha_widget)
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            widgetId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Bind the tap intent to the two visible children. The root LinearLayout
        // carries no id of its own, so we can't attach a single click to it from
        // here; binding the icon and the label covers every pixel the user can
        // actually see. (Giving the root an id in the layout XML would let one
        // click cover the padding too: see the SHARED CHANGE REQUEST in the
        // widget layout.)
        views.setOnClickPendingIntent(R.id.widget_icon, pending)
        views.setOnClickPendingIntent(R.id.widget_label, pending)
        manager.updateAppWidget(widgetId, views)
    }

    companion object {
        /** Public helper to nudge all instances to repaint — used after a
         *  settings change (e.g. theme accent override) so the widget pulls
         *  the new colours. */
        fun nudgeAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, R1haWidgetProvider::class.java),
            )
            if (ids.isNotEmpty()) {
                val intent = Intent(context, R1haWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }
    }
}
