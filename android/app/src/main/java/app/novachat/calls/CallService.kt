package app.novachat.calls

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
 * Runs while a call is on, so Android lets it keep the microphone (and
 * camera) when NovaChat leaves the screen, and shows the ongoing call with
 * a hang-up button.
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notifier = (application as NovaChatApp).container.notifier
        val name = intent?.getStringExtra(EXTRA_NAME).orEmpty()
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) == true
        val notification = notifier.ongoingCallNotification(name, video)
        val microphone = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        val camera = if (Build.VERSION.SDK_INT >= 30 && video) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, microphone or camera)
        } catch (e: RuntimeException) {
            // No camera permission, or not allowed from here: keep at least the microphone.
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, microphone)
            } catch (e: RuntimeException) {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val NOTIFICATION_ID = 2
        private const val EXTRA_NAME = "name"
        private const val EXTRA_VIDEO = "video"

        fun start(context: Context, name: String, video: Boolean) {
            val intent = Intent(context, CallService::class.java).putExtra(EXTRA_NAME, name).putExtra(EXTRA_VIDEO, video)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: RuntimeException) {
                // Not allowed right now: the call still works while NovaChat is on screen.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}
