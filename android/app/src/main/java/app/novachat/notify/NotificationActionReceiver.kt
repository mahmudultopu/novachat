package app.novachat.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import app.novachat.NovaChatApp

/** "Reply" and "Mark as read" on a message notification. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as NovaChatApp).container
        val chatId = intent.getStringExtra(Notifier.EXTRA_CHAT) ?: return
        when (intent.action) {
            Notifier.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString()
                if (!text.isNullOrBlank()) container.chats.send(chatId, text)
                container.chats.markChatRead(chatId)
            }
            Notifier.ACTION_MARK_READ -> container.chats.markChatRead(chatId)
        }
        container.notifier.clear(chatId)
    }
}
