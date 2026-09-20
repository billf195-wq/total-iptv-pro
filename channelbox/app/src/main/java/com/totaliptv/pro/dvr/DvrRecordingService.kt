package com.totaliptv.pro.dvr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.totaliptv.pro.MainActivity

class DvrRecordingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Live"
        ensureChannel()
        val notification = buildNotification(title)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        return START_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Personal DVR", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(title: String): Notification {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Recording on this device")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(launch)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "total_iptv_dvr"
        private const val NOTIF_ID = 7124
        const val EXTRA_TITLE = "title"
        const val ACTION_STOP = "com.totaliptv.pro.dvr.STOP"

        fun start(context: Context, title: String) {
            val intent = Intent(context, DvrRecordingService::class.java).putExtra(EXTRA_TITLE, title)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, DvrRecordingService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
