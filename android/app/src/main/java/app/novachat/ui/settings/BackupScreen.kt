package app.novachat.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.novachat.data.BackupException
import app.novachat.data.backupPasswordProblem
import app.novachat.ui.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val KEY_LAST_BACKUP = "last_backup"

/** A file name for a backup made at [millis], such as novachat-backup-20261030.nbk. */
internal fun backupFileName(millis: Long): String =
    "novachat-backup-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(millis)) + ".nbk"

/** Settings → Chat backup: save chats to a password-locked file, or bring them back from one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val repo = appContainer().chats
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var lastBackup by remember { mutableLongStateOf(prefs.getLong(KEY_LAST_BACKUP, 0L)) }
    var busy by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var askNewPassword by remember { mutableStateOf(false) }
    // Password for the backup being saved, kept until the user picks where.
    var newPassword by remember { mutableStateOf<String?>(null) }
    var restoreFrom by remember { mutableStateOf<Uri?>(null) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val password = newPassword
        newPassword = null
        if (uri == null || password == null) return@rememberLauncherForActivityResult
        busy = "Backing up…"
        scope.launch {
            notice = try {
                withContext(Dispatchers.IO) {
                    val bytes = repo.createBackup(password)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("can't write")
                }
                val now = System.currentTimeMillis()
                prefs.edit().putLong(KEY_LAST_BACKUP, now).apply()
                lastBackup = now
                "Backup saved. Keep the password somewhere safe: without it the file can't be opened."
            } catch (e: Exception) {
                "Couldn't save the backup. Try another place."
            }
            busy = null
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreFrom = uri
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat backup") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Back up your chats", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Your messages are end-to-end encrypted, so only this phone can read them. " +
                            "Save them to a file locked with a password, and keep it on Google Drive, " +
                            "a memory card or your computer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (lastBackup == 0L) "No backup yet"
                        else "Last backup: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastBackup)),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { askNewPassword = true }, enabled = busy == null, modifier = Modifier.fillMaxWidth()) {
                        Text("Back up now")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Restore", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "New phone? Sign in with the same number, then pick your backup file here. " +
                            "Messages already on this phone are kept. Photos and videos come back " +
                            "as downloads while the server still has them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { open.launch(arrayOf("*/*")) }, enabled = busy == null, modifier = Modifier.fillMaxWidth()) {
                        Text("Restore from file")
                    }
                }
            }
            busy?.let {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(it)
                }
            }
            notice?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    if (askNewPassword) {
        PasswordDialog(
            title = "Lock the backup",
            confirmTwice = true,
            onDismiss = { askNewPassword = false },
            onDone = { password ->
                askNewPassword = false
                newPassword = password
                notice = null
                save.launch(backupFileName(System.currentTimeMillis()))
            },
        )
    }
    restoreFrom?.let { uri ->
        PasswordDialog(
            title = "Backup password",
            confirmTwice = false,
            onDismiss = { restoreFrom = null },
            onDone = { password ->
                restoreFrom = null
                busy = "Restoring…"
                notice = null
                scope.launch {
                    notice = try {
                        val count = withContext(Dispatchers.IO) {
                            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("can't read")
                            repo.restoreBackup(bytes, password)
                        }
                        if (count == 1) "Restored 1 message." else "Restored $count messages."
                    } catch (e: BackupException) {
                        e.message
                    } catch (e: Exception) {
                        "Couldn't read that file."
                    }
                    busy = null
                }
            },
        )
    }
}

@Composable
private fun PasswordDialog(title: String, confirmTwice: Boolean, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (confirmTwice) {
                    Text(
                        "Pick a password. You'll need it to restore, and nobody can recover it for you.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it; error = null },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (confirmTwice) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = second,
                        onValueChange = { second = it; error = null },
                        label = { Text("Type it again") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val problem = if (confirmTwice) backupPasswordProblem(first, second) else null
                if (problem != null) error = problem else if (first.isNotEmpty()) onDone(first)
            }) { Text(if (confirmTwice) "Choose where to save" else "Restore") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
