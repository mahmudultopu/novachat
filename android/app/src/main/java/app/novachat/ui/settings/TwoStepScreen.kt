package app.novachat.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.novachat.data.ApiException
import app.novachat.ui.appContainer
import kotlinx.coroutines.launch

/** Where choosing a PIN is up to: the first entry, then typing it again. */
internal enum class PinEntry { First, Confirm }

/** Checks a new PIN typed twice; returns what's wrong, or null when it's good to save. */
internal fun newPinProblem(first: String, second: String): String? = when {
    first.length != 6 || first.any { !it.isDigit() } -> "The PIN must be 6 digits"
    first.toSet().size == 1 || "0123456789".contains(first) || "9876543210".contains(first) -> "Pick a PIN that's harder to guess"
    first != second -> "The PINs don't match. Try again."
    else -> null
}

/** Settings → Two-step verification: a PIN asked for when your number signs in on a new phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TwoStepScreen(onBack: () -> Unit) {
    val repo = appContainer().chats
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf<Boolean?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            enabled = repo.pinEnabled()
        } catch (e: ApiException) {
            error = e.message
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Two-step verification") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
            Text(
                "Add a 6-digit PIN that NovaChat asks for when your phone number signs in on a new phone. " +
                    "Someone who gets your SMS code still can't take over your account without it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            when (enabled) {
                null -> if (error == null) CircularProgressIndicator()
                true -> {
                    Text("Two-step verification is on.", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth()) { Text("Change PIN") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { confirmOff = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Turn off", color = MaterialTheme.colorScheme.error)
                    }
                }
                false -> Button(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth()) { Text("Turn on") }
            }
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "If you forget your PIN, you can reset it when signing in, but the reset takes 7 days.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (choosing) {
        ChoosePinDialog(onDismiss = { choosing = false }, onChosen = { pin ->
            scope.launch {
                try {
                    repo.setPin(pin)
                    enabled = true
                    error = null
                    choosing = false
                } catch (e: ApiException) {
                    error = e.message
                    choosing = false
                }
            }
        })
    }
    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text("Turn off two-step verification?") },
            text = { Text("Signing in on a new phone will only need the SMS code.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmOff = false
                    scope.launch {
                        try {
                            repo.setPin(null)
                            enabled = false
                            error = null
                        } catch (e: ApiException) {
                            error = e.message
                        }
                    }
                }) { Text("Turn off") }
            },
            dismissButton = { TextButton(onClick = { confirmOff = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ChoosePinDialog(onDismiss: () -> Unit, onChosen: (String) -> Unit) {
    var entry by remember { mutableStateOf(PinEntry.First) }
    var first by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }

    fun next() {
        if (entry == PinEntry.First) {
            val p = newPinProblem(value, value)
            if (p != null) {
                problem = p
                value = ""
                return
            }
            first = value
            value = ""
            entry = PinEntry.Confirm
        } else {
            val p = newPinProblem(first, value)
            if (p != null) {
                problem = p
                first = ""
                value = ""
                entry = PinEntry.First
                return
            }
            onChosen(first)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry == PinEntry.First) "Choose a 6-digit PIN" else "Type the PIN again") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.filter(Char::isDigit).take(6); problem = null },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = problem != null,
                )
                problem?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { next() }, enabled = value.length == 6) { Text(if (entry == PinEntry.First) "Next" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
