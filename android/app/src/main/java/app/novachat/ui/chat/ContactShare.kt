package app.novachat.ui.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.ContactsContract
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.novachat.data.SharedContact
import app.novachat.data.contactInitials
import app.novachat.data.reachableNumber

/** Shows the contact picked from the address book before it's sent. */
@Composable
fun SendContactDialog(card: SharedContact, onDismiss: () -> Unit, onSend: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send contact?") },
        text = {
            Column {
                ContactHeader(card, MaterialTheme.colorScheme.onSurface)
                if (card.phones.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("This contact has no phone number.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onSend) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A contact card in a chat bubble: who it is, their numbers, and what you can do with it. */
@Composable
fun ContactCard(card: SharedContact, fg: Color, onMessage: () -> Unit) {
    val context = LocalContext.current
    val save = {
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.NAME, card.name)
            val keys = listOf(
                ContactsContract.Intents.Insert.PHONE,
                ContactsContract.Intents.Insert.SECONDARY_PHONE,
                ContactsContract.Intents.Insert.TERTIARY_PHONE,
            )
            card.phones.zip(keys).forEach { (phone, key) -> putExtra(key, phone) }
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No contacts app on this phone", Toast.LENGTH_SHORT).show()
        }
    }
    Column(Modifier.widthIn(min = 220.dp, max = 260.dp)) {
        ContactHeader(card, fg)
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = fg.copy(alpha = 0.15f))
        Row(Modifier.fillMaxWidth()) {
            if (reachableNumber(card) != null) {
                TextButton(onClick = onMessage, modifier = Modifier.weight(1f)) { Text("Message", color = fg) }
            }
            TextButton(onClick = save, modifier = Modifier.weight(1f)) { Text("Add contact", color = fg) }
        }
    }
}

@Composable
private fun ContactHeader(card: SharedContact, fg: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                contactInitials(card.name),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                card.name,
                color = fg,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            card.phones.forEach { phone ->
                Text(phone, color = fg.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
