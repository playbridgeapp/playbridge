package com.playbridge.sender.cast.dlna

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.playbridge.sender.R

/**
 * Foreground service that keeps the process alive (with a notification) while a cast
 * is active, so the [LocalProxyServer] keeps serving when the screen is off or the
 * app is backgrounded. Started when a target is selected / a local file is cast,
 * stopped when casting ends. Mirrors the app's existing dataSync FGS pattern.
 */
class DlnaProxyService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("PlayBridge")
            .setContentText("Casting to a nearby device")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Casting", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "dlna_cast_channel"
        private const val NOTIF_ID = 4711

        fun start(context: Context) {
            val intent = Intent(context, DlnaProxyService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DlnaProxyService::class.java))
        }
    }
}
