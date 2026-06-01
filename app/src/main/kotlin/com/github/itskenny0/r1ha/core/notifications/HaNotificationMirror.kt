package com.github.itskenny0.r1ha.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.github.itskenny0.r1ha.App
import com.github.itskenny0.r1ha.R
import com.github.itskenny0.r1ha.core.ha.PersistentNotification
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Mirrors HA's persistent notifications into the Android system notification shade so the
 * user can see HA's warnings without opening the app. Each posted notification carries a
 * DISMISS action that fires `persistent_notification.dismiss` on the server.
 *
 * Poll-based rather than push-based: we already fetch persistent_notifications every 30 s
 * for the Notifications screen, so this collector piggybacks on the same scheduled work
 * (via the App scope, not the Notifications screen's lifecycle) and only forwards rows
 * that weren't visible in the previous tick.
 *
 * Off by default. Flipped on via the Advanced toggle, which also triggers a runtime
 * POST_NOTIFICATIONS permission request on Android 13+.
 */
object HaNotificationMirror {

    private const val CHANNEL_ID = "ha_persistent"
    private const val GROUP_ID = "ha_persistent_group"

    /** Stable id-prefix range so concurrent updates to the same HA notification id keep
     *  the same notification rather than stacking duplicates. We hash the HA id into the
     *  Android int space. */
    private fun notifIdFor(haId: String): Int = haId.hashCode() and 0x7FFFFFFF or 0x10000000

    private var collectorJob: Job? = null

    /** Lazily-created channel. Notification channels are sticky once created so this is
     *  a one-time-per-install operation; we still call it on every enable to handle the
     *  edge case of the user manually deleting the channel from System Settings. */
    fun ensureChannel(context: Context) {
        // Notification channels are an API-26 concept; on older devices
        // NotificationCompat ignores the channel id and posts directly.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Home Assistant alerts",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Persistent notifications from the connected HA instance"
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    /** Start the long-running collector. Cancels any prior collector first so a toggle
     *  flip-flop doesn't double-mirror. Safe to call before HA is connected: the
     *  listPersistentNotifications call returns failure and the collector retries on the
     *  next interval. */
    fun start(app: App) {
        collectorJob?.cancel()
        ensureChannel(app)
        val scope = CoroutineScope(Dispatchers.IO)
        collectorJob = scope.launch {
            val knownIds = mutableSetOf<String>()
            // The settings flow's poll-interval lives in IntegrationsSettings; reuse it
            // so a user who tightened the foreground refresh interval also gets a
            // tighter mirror cadence. Coerce to a sane minimum so a 0 ("manual only")
            // configured for the in-app screen doesn't burn us into a tight loop.
            while (isActive) {
                val current = app.graph.settings.settings.first()
                val intervalSec = current.integrations.notificationsRefreshSec.coerceAtLeast(30)
                app.graph.haRepository.listPersistentNotifications().fold(
                    onSuccess = { rows ->
                        val seenIds = rows.map { it.notificationId }.toSet()
                        val fresh = rows.filter { it.notificationId !in knownIds }
                        for (row in fresh) {
                            postOne(app, row)
                        }
                        // Forget ids that HA no longer reports so a re-raised notification
                        // gets re-posted (otherwise the user would never see it twice).
                        knownIds.retainAll(seenIds)
                        knownIds.addAll(seenIds)
                    },
                    onFailure = { t ->
                        R1Log.d("HaNotificationMirror", "fetch failed: ${t.message}")
                    },
                )
                delay(intervalSec * 1000L)
            }
        }
    }

    fun stop() {
        collectorJob?.cancel()
        collectorJob = null
    }

    private fun postOne(app: App, row: PersistentNotification) {
        val nmc = NotificationManagerCompat.from(app)
        if (!nmc.areNotificationsEnabled()) {
            R1Log.d("HaNotificationMirror", "notifications disabled by user/OS — skip post")
            return
        }
        val dismissIntent = Intent(app, DismissActionReceiver::class.java).apply {
            action = ACTION_DISMISS_HA_NOTIF
            putExtra(EXTRA_HA_NOTIF_ID, row.notificationId)
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val dismissPending = PendingIntent.getBroadcast(
            app,
            row.notificationId.hashCode(),
            dismissIntent,
            pendingFlags,
        )
        val builder = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(row.title?.takeIf { it.isNotBlank() } ?: row.notificationId)
            .setContentText(row.message ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(row.message ?: ""))
            .setGroup(GROUP_ID)
            .setAutoCancel(true)
            .addAction(0, "DISMISS IN HA", dismissPending)
        // POST_NOTIFICATIONS check: NotificationManagerCompat.notify throws SecurityException
        // on Android 13+ if we lack the runtime permission. Catching here is the simplest
        // path; the toggle prompts the user at flip time so this should only fire when
        // they explicitly denied + the toggle is still on.
        runCatching {
            nmc.notify(notifIdFor(row.notificationId), builder.build())
        }.onFailure { t ->
            R1Log.w("HaNotificationMirror", "post failed: ${t.message}")
        }
    }

    /** Broadcast receiver for the DISMISS action button. Bound by manifest. */
    class DismissActionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_DISMISS_HA_NOTIF) return
            val id = intent.getStringExtra(EXTRA_HA_NOTIF_ID) ?: return
            val app = context.applicationContext as? App ?: return
            val pending = goAsync()
            val scope = CoroutineScope(Dispatchers.IO)
            scope.launch {
                runCatching {
                    app.graph.haRepository.dismissPersistentNotification(id).fold(
                        onSuccess = {
                            // Also cancel the Android-side notification so the user
                            // doesn't see a stale row after acting on it.
                            withContext(Dispatchers.Main) {
                                NotificationManagerCompat.from(app).cancel(notifIdFor(id))
                            }
                            R1Log.i("HaNotificationMirror.dismiss", "dismissed $id")
                        },
                        onFailure = { t ->
                            R1Log.w("HaNotificationMirror.dismiss", "$id failed: ${t.message}")
                        },
                    )
                }
                pending.finish()
            }
        }
    }

    const val ACTION_DISMISS_HA_NOTIF = "com.github.itskenny0.r1ha.action.DISMISS_HA_NOTIF"
    const val EXTRA_HA_NOTIF_ID = "ha_notif_id"
}
