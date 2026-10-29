package app.novachat.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import app.novachat.MainActivity
import app.novachat.R
import app.novachat.data.IncomingNotice
import app.novachat.data.MediaFiles

/**
 * Message notifications: one per chat, with the last few messages, a reply
 * box and "Mark as read". Shown only while the chat is not on screen.
 */
class Notifier(private val context: Context, private val media: MediaFiles) {
    private val manager = NotificationManagerCompat.from(context)

    /** The latest lines shown per chat, so a notification grows like a chat. */
    private val lines = mutableMapOf<String, MutableList<IncomingNotice>>()

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "New messages in your chats and groups"
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Incoming and ongoing voice and video calls"
                    // NovaChat plays the ringtone and vibrates itself, for as long as the call rings.
                    setSound(null, null)
                    enableVibration(false)
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_CONNECTION, "Background connection", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Keeps NovaChat connected so messages arrive right away"
                    setShowBadge(false)
                },
            )
        }
    }

    fun canNotify(): Boolean =
        manager.areNotificationsEnabled() && (
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )

    @Synchronized
    fun show(n: IncomingNotice) {
        if (!canNotify()) return
        val chat = lines.getOrPut(n.chatId) { mutableListOf() }
        chat += n
        while (chat.size > MAX_LINES) chat.removeAt(0)

        val me = Person.Builder().setName("You").build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(if (n.isGroup) n.title else null)
            .setGroupConversation(n.isGroup)
        for (line in chat) {
            val sender = Person.Builder().setName(line.senderName).setKey(line.senderId).apply {
                avatarIcon(line.senderAvatarId)?.let { setIcon(it) }
            }.build()
            style.addMessage(line.text, line.atMillis, sender)
        }

        val requestCode = n.chatId.hashCode()
        val open = PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_CHAT)
                .putExtra(EXTRA_CHAT, n.chatId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val replyFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val reply = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_CHAT, n.chatId),
            replyFlags,
        )
        val markRead = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_MARK_READ).putExtra(EXTRA_CHAT, n.chatId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val replyAction = NotificationCompat.Action.Builder(R.drawable.ic_reply, "Reply", reply)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Message").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(true)
            .setShowsUserInterface(false)
            .build()
        val readAction = NotificationCompat.Action.Builder(R.drawable.ic_chats, "Mark as read", markRead)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_logo)
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setNumber(chat.size)
            .addAction(replyAction)
            .addAction(readAction)
            .setGroup(GROUP_MESSAGES)
            .build()
        try {
            manager.notify(TAG_CHAT, requestCode, notification)
        } catch (e: SecurityException) {
            // Permission was taken away a moment ago.
        }
    }

    /** Clears a chat's notification, e.g. once it is opened or read. */
    @Synchronized
    fun clear(chatId: String) {
        lines.remove(chatId)
        manager.cancel(TAG_CHAT, chatId.hashCode())
    }

    @Synchronized
    fun clearAll() {
        lines.clear()
        manager.cancelAll()
    }

    /** The sender's cached profile photo, if this phone already has it. */
    private fun avatarIcon(avatarId: String?): IconCompat? {
        val file = avatarId?.let { media.avatarFile(it) }?.takeIf { it.exists() } ?: return null
        return BitmapFactory.decodeFile(file.path)?.let { IconCompat.createWithBitmap(it) }
    }

    /** The ringing call, over the lock screen, with Answer and Decline. */
    fun showIncomingCall(callerName: String, avatarId: String?, video: Boolean) {
        if (!canNotify()) return
        val caller = Person.Builder().setName(callerName).setImportant(true).apply {
            avatarIcon(avatarId)?.let { setIcon(it) }
        }.build()
        val show = callActivity(ACTION_SHOW_CALL, REQUEST_SHOW_CALL)
        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(callerName)
            .setContentText(if (video) "Incoming video call" else "Incoming voice call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(show)
            .setFullScreenIntent(show, true)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    caller,
                    callBroadcast(ACTION_DECLINE_CALL),
                    callActivity(ACTION_ACCEPT_CALL, REQUEST_ACCEPT_CALL),
                ).setIsVideo(video),
            )
            .build()
        try {
            manager.notify(TAG_CALL, ID_INCOMING_CALL, notification)
        } catch (e: SecurityException) {
            // Permission was taken away a moment ago.
        }
    }

    fun cancelIncomingCall() {
        manager.cancel(TAG_CALL, ID_INCOMING_CALL)
    }

    /** Shown by the call service while a call is on, with a hang-up button. */
    fun ongoingCallNotification(name: String, video: Boolean) = NotificationCompat.Builder(context, CHANNEL_CALLS)
        .setSmallIcon(R.drawable.ic_call)
        .setContentTitle(name)
        .setContentText(if (video) "Video call" else "Voice call")
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(callActivity(ACTION_SHOW_CALL, REQUEST_SHOW_CALL))
        .setStyle(
            NotificationCompat.CallStyle.forOngoingCall(
                Person.Builder().setName(name.ifBlank { "Call" }).build(),
                callBroadcast(ACTION_HANG_UP),
            ).setIsVideo(video),
        )
        .build()

    private fun callActivity(action: String, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, MainActivity::class.java)
            .setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun callBroadcast(action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        action.hashCode(),
        Intent(context, NotificationActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun connectionNotification() = NotificationCompat.Builder(context, CHANNEL_CONNECTION)
        .setSmallIcon(R.drawable.ic_logo)
        .setContentTitle("NovaChat is connected")
        .setContentText("So your messages arrive right away. You can hide this in the system settings.")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_CONNECTION = "connection"
        const val CHANNEL_CALLS = "calls"
        const val ACTION_SHOW_CALL = "app.novachat.SHOW_CALL"
        const val ACTION_ACCEPT_CALL = "app.novachat.ACCEPT_CALL"
        const val ACTION_DECLINE_CALL = "app.novachat.DECLINE_CALL"
        const val ACTION_HANG_UP = "app.novachat.HANG_UP"
        private const val TAG_CALL = "call"
        private const val ID_INCOMING_CALL = 1
        private const val REQUEST_SHOW_CALL = 101
        private const val REQUEST_ACCEPT_CALL = 102
        const val ACTION_OPEN_CHAT = "app.novachat.OPEN_CHAT"
        const val ACTION_REPLY = "app.novachat.REPLY"
        const val ACTION_MARK_READ = "app.novachat.MARK_READ"
        const val EXTRA_CHAT = "chat_id"
        const val KEY_REPLY = "reply"
        private const val TAG_CHAT = "chat"
        private const val GROUP_MESSAGES = "messages"
        private const val MAX_LINES = 6
    }
}
