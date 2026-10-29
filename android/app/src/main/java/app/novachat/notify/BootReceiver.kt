package app.novachat.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.novachat.NovaChatApp

/** Reconnects after the phone restarts, so messages keep arriving. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Creating the app starts the connection; the container starts the service if wanted.
        (context.applicationContext as NovaChatApp).container
    }
}
