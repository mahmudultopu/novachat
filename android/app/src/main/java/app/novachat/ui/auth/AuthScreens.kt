package app.novachat.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.novachat.R
import app.novachat.data.ApiException
import app.novachat.data.Session
import app.novachat.ui.appContainer
import kotlinx.coroutines.launch

@Composable
fun AuthFlow() {
    val container = appContainer()
    val vm: AuthViewModel = viewModel { AuthViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val serverUrl by vm.serverUrl.collectAsStateWithLifecycle(initialValue = "")
    var editServer by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .systemBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (state.step != AuthUiState.Step.Phone) {
                    IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
                IconButton(onClick = { editServer = true }) { Icon(Icons.Filled.Settings, "Server address") }
            }
            Spacer(Modifier.height(24.dp))
            Image(painterResource(R.drawable.ic_logo), null, Modifier.size(72.dp).align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(16.dp))

            when (state.step) {
                AuthUiState.Step.Phone -> PhoneStep(state, vm)
                AuthUiState.Step.Code -> CodeStep(state, vm)
                AuthUiState.Step.Pin -> PinStep(state, vm)
            }
        }
    }

    if (editServer) {
        ServerDialog(serverUrl, onDismiss = { editServer = false }, onSave = {
            vm.setServerUrl(it)
            editServer = false
        })
    }
}

@Composable
private fun PhoneStep(state: AuthUiState, vm: AuthViewModel) {
    Text("Welcome to NovaChat", style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(
        "Enter your phone number. We'll send a 6-digit code to confirm it's you.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(28.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = state.countryCode,
            onValueChange = vm::onCountryCode,
            label = { Text("Code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.width(96.dp),
        )
        Spacer(Modifier.width(12.dp))
        OutlinedTextField(
            value = state.number,
            onValueChange = vm::onNumber,
            label = { Text("Phone number") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
            modifier = Modifier.weight(1f),
        )
    }
    ErrorText(state.error)
    Spacer(Modifier.height(24.dp))
    PrimaryButton("Send code", enabled = state.number.count(Char::isDigit) >= 6, loading = state.loading, onClick = vm::requestCode)
}

@Composable
private fun CodeStep(state: AuthUiState, vm: AuthViewModel) {
    Text("Enter the code", style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(
        "We sent a code to ${state.normalizedPhone}.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(28.dp))
    OutlinedTextField(
        value = state.code,
        onValueChange = vm::onCode,
        placeholder = { Text("––– –––") },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge.copy(letterSpacing = 8.sp, textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
    state.devCode?.let { code ->
        Spacer(Modifier.height(12.dp))
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
            Text(
                "Dev server: your code is $code",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    ErrorText(state.error)
    Spacer(Modifier.height(24.dp))
    PrimaryButton("Verify", enabled = state.code.length == 6, loading = state.loading, onClick = vm::verify)
}

/** Two-step verification: the account's PIN, after the SMS code. */
@Composable
private fun ColumnScope.PinStep(state: AuthUiState, vm: AuthViewModel) {
    var confirmForgot by remember { mutableStateOf(false) }
    Text("Two-step verification", style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(
        "This account is protected with a PIN. Enter the 6-digit PIN you chose.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(28.dp))
    OutlinedTextField(
        value = state.pin,
        onValueChange = vm::onPin,
        placeholder = { Text("••••••") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        textStyle = MaterialTheme.typography.titleLarge.copy(letterSpacing = 8.sp, textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
    ErrorText(state.error)
    Spacer(Modifier.height(24.dp))
    PrimaryButton("Continue", enabled = state.pin.length == 6, loading = state.loading, onClick = vm::submitPin)
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { if (state.resetAt != null) vm.forgotPin() else confirmForgot = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
        Text(if (state.resetAt != null) "Sign in without PIN" else "Forgot PIN?")
    }
    if (confirmForgot) {
        AlertDialog(
            onDismissRequest = { confirmForgot = false },
            title = { Text("Reset your PIN?") },
            text = {
                Text(
                    "To protect your account, the reset takes 7 days. After that you can sign in with just the SMS code. " +
                        "If you remember your PIN before then, signing in with it cancels the reset.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmForgot = false
                    vm.forgotPin()
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmForgot = false }) { Text("Cancel") } },
        )
    }
}

/** First sign-in: ask for the name other people will see. */
@Composable
fun ProfileSetupScreen(session: Session) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.systemBarsPadding().imePadding().padding(24.dp)) {
            Spacer(Modifier.height(48.dp))
            Text("Your profile", style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "Add the name people see when you message them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(28.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(25); error = null },
                label = { Text("Your name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ErrorText(error)
            Spacer(Modifier.height(24.dp))
            PrimaryButton("Continue", enabled = name.isNotBlank(), loading = loading) {
                loading = true
                scope.launch {
                    try {
                        val user = container.api.updateName(session, name.trim())
                        container.sessionStore.updateUser(user)
                    } catch (e: ApiException) {
                        error = e.message
                    }
                    loading = false
                }
            }
        }
    }
}

@Composable
private fun ErrorText(error: String?) {
    if (error != null) {
        Spacer(Modifier.height(12.dp))
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, loading: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        } else {
            Text(text, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ServerDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var url by remember(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server address") },
        text = {
            Column {
                Text(
                    "Emulator: http://10.0.2.2:8080\nPhone on Wi-Fi: http://<laptop IP>:8080",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(url) }, enabled = url.startsWith("http")) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
