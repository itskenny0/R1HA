package com.github.itskenny0.r1ha.feature.widget

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose

/**
 * Widget-instance to entity binding for the favorite-card widget. Plain
 * SharedPreferences (not the DataStore-backed settings blob) because widget
 * callbacks are synchronous broadcast handlers that may run before the app's
 * dependency graph is warm, and the bindings are launcher-side state: they
 * belong to widget instances, not to the user's portable settings, so they
 * deliberately stay out of AppBackup / HA settings sync (a restored backup on
 * a new device has different widget ids anyway).
 *
 * Keys are the stringified widgetId; values are raw HA entity ids. Writes use
 * commit() rather than apply() because the provider's goAsync window is the
 * only thing keeping the process alive during onDeleted / onDisabled, and an
 * un-flushed apply() can be lost when the OS kills the process right after
 * the receiver returns.
 */
object FavoriteCardWidgetStore {

    private const val PREFS_NAME = "r1ha_favorite_card_widgets"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Bind [widgetId] to [entityId], replacing any previous binding. */
    fun bind(context: Context, widgetId: Int, entityId: String) {
        if (entityId.isBlank()) return
        prefs(context).edit().putString(widgetId.toString(), entityId).commit()
    }

    /** The entity bound to [widgetId], or null when the widget is unconfigured. */
    fun entityFor(context: Context, widgetId: Int): String? =
        prefs(context).getString(widgetId.toString(), null)?.takeIf { it.isNotBlank() }

    /** Drop the bindings for the given widget ids (host deleted the instances). */
    fun unbind(context: Context, widgetIds: IntArray) {
        if (widgetIds.isEmpty()) return
        val editor = prefs(context).edit()
        for (id in widgetIds) editor.remove(id.toString())
        editor.commit()
    }

    /** Drop every binding (host removed the last instance — onDisabled). */
    fun clearAll(context: Context) {
        prefs(context).edit().clear().commit()
    }

    /** Every current binding, keyed by widgetId. Skips non-numeric keys defensively. */
    fun allBindings(context: Context): Map<Int, String> =
        prefs(context).all.entries.mapNotNull { (key, value) ->
            val id = key.toIntOrNull() ?: return@mapNotNull null
            val entity = (value as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            id to entity
        }.toMap()

    /**
     * [allBindings] as a cold flow: emits the current map immediately, then on
     * every binding change (configure / re-bind / delete). Drives the live
     * widget refresher so it re-targets its observation without polling.
     */
    fun bindingsFlow(context: Context): kotlinx.coroutines.flow.Flow<Map<Int, String>> =
        kotlinx.coroutines.flow.callbackFlow {
            val p = prefs(context)
            // The listener reference must stay strong for the flow's lifetime:
            // SharedPreferences holds listeners weakly and a lambda-only
            // registration gets GC'd silently.
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                trySend(allBindings(context))
            }
            p.registerOnSharedPreferenceChangeListener(listener)
            trySend(allBindings(context))
            awaitClose { p.unregisterOnSharedPreferenceChangeListener(listener) }
        }
}
