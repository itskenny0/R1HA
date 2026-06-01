package com.github.itskenny0.r1ha.core.webhook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.github.itskenny0.r1ha.App
import com.github.itskenny0.r1ha.MainActivity
import com.github.itskenny0.r1ha.R
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster

/**
 * Foreground service that owns a [WebhookServer]. Android requires anything
 * listening on a socket indefinitely to be a foreground service with a
 * persistent notification — without this Doze would suspend the thread within
 * minutes of the app being backgrounded.
 *
 * Lifecycle is driven by [App]'s settings observer:
 *  - flipping `advanced.webhookEnabled` true → start the service
 *  - flipping it false → stop the service (which tears down the socket)
 *
 * The persistent notification is required by the platform; we keep its copy
 * informational ("R1HA webhook listener · port 8765") so the user understands
 * why they see an icon in their notification shade.
 */
class WebhookListenerService : Service() {

    private var server: WebhookServer? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
        val id = intent?.getStringExtra(EXTRA_WEBHOOK_ID) ?: DEFAULT_WEBHOOK_ID
        startForeground(NOTIF_ID, buildNotification(port, id))
        server?.stop()
        server = WebhookServer(
            port = port,
            webhookId = id,
            onWebhook = { body, remote -> dispatch(body, remote) },
        ).also { it.start() }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dispatch(body: String, remote: String) {
        // First-class action: surface the body as an expandable toast so the user
        // sees the trigger reach them. Future paths could route to a service-call
        // dispatcher or a custom intent — for now the toast confirms the listener
        // is wired and lets the user inspect the payload.
        val short = "Webhook from $remote"
        val full = if (body.isBlank()) "(empty body)" else body.take(2000)
        Toaster.showExpandable(shortText = short, fullText = full)
        R1Log.i("Webhook.dispatch", "fired short='$short' bodyLen=${body.length}")
    }

    private fun buildNotification(port: Int, id: String): Notification {
        val launchPending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("R1HA webhook listener")
            .setContentText("Listening on :$port /webhook/$id")
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(launchPending)
            .build()
    }

    companion object {
        const val DEFAULT_PORT = 8765
        const val DEFAULT_WEBHOOK_ID = "r1"
        const val EXTRA_PORT = "port"
        const val EXTRA_WEBHOOK_ID = "webhook_id"
        private const val CHANNEL_ID = "webhook_listener"
        private const val NOTIF_ID = 0x71BA1701

        fun ensureChannel(context: Context) {
            // Notification channels are an API-26 concept; on older devices
            // NotificationCompat ignores the channel id and posts directly.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Webhook listener",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Background HTTP listener for HA webhook automations" }
            manager.createNotificationChannel(channel)
        }

        fun start(context: Context, port: Int, webhookId: String) {
            ensureChannel(context)
            val intent = Intent(context, WebhookListenerService::class.java).apply {
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_WEBHOOK_ID, webhookId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WebhookListenerService::class.java))
        }
    }
}
