package com.nico7an.terminal

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Keeps the process (and so the SSH connections) alive while tabs are open. */
class SshService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE_ALL) {
            Sessions.closeAll()
            return START_NOT_STICKY
        }
        val count = Sessions.tabs.size
        if (count == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = notification(count)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    private fun notification(count: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, TerminalActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val closeAll = PendingIntent.getService(
            this, 1, Intent(this, SshService::class.java).setAction(ACTION_CLOSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val names = Sessions.tabs.joinToString(", ") { it.server.name }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_prompt)
            .setContentTitle(if (count == 1) "1 session" else "$count sessions")
            .setContentText(names)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, "Tout fermer", closeAll).build())
            .build()
    }

    companion object {
        const val CHANNEL_ID = "sessions"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_CLOSE_ALL = "close_all"

        /** Start, refresh or stop the service to match the open tabs. */
        fun sync(context: Context) {
            val intent = Intent(context, SshService::class.java)
            if (Sessions.tabs.isEmpty()) {
                context.stopService(intent)
            } else {
                runCatching { context.startForegroundService(intent) }
            }
        }
    }
}
