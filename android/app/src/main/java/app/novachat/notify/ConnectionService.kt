package app.novachat.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.novachat.NovaChatApp

/**
 * Keeps the app's process, and with it the encrypted connection, alive in
 * the background so messages arrive and notify right away. NovaChat has no
 * push service: a push provider would see who messages whom and when.
 */
class ConnectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notifier = (application as NovaChatApp).container.notifier
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notifier.connectionNotification(), type)
        } catch (e: RuntimeException) {
            // Not allowed to start from here (e.g. background start limits): run without it.
            stopSelf()
        }
        return START_STICKY
    }

    companion object {
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
            } catch (e: RuntimeException) {
                // Started from the background on Android 12+: the app is still fine while open.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}
