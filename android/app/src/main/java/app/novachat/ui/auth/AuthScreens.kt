package app.novachat.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.novachat.R
import app.novachat.data.ApiException
import app.novachat.data.Session
import app.novachat.ui.appContainer
import app.novachat.ui.theme.NovaAqua
import app.novachat.ui.theme.NovaViolet
import kotlinx.coroutines.launch

@Composable
fun AuthFlow() {
    val container = appContainer()
    val vm: AuthViewModel = viewModel { AuthViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val serverUrl by vm.serverUrl.collectAsStateWithLifecycle(initialValue = "")
    var editServer by remember { mutableStateOf(false) }

    val welcome = when (state.step) {
        AuthUiState.Step.Phone ->
            if (state.signUp) "Join NovaChat. Private chats in seconds." else "Welcome back! Log in to continue."
        AuthUiState.Step.Code -> "Almost there. Check your messages."
        AuthUiState.Step.Pin -> "One more check to keep your account safe."
        AuthUiState.Step.Link -> "Use NovaChat on more than one device."
    }

    AuthScaffold(
        welcome = welcome,
        actions = {
            if (state.step != AuthUiState.Step.Phone) {
                IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            } else {
                Spacer(Modifier.size(48.dp))
            }
            IconButton(onClick = { editServer = true }) { Icon(Icons.Filled.Settings, "Server address", tint = Color.White) }
        },
    ) {
        when (state.step) {
            AuthUiState.Step.Phone -> if (state.signUp) SignUpStep(state, vm) else LoginStep(state, vm)
            AuthUiState.Step.Code -> CodeStep(state, vm)
            AuthUiState.Step.Pin -> PinStep(state, vm)
            AuthUiState.Step.Link -> LinkStep(state, vm)
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
private fun ColumnScope.LoginStep(state: AuthUiState, vm: AuthViewModel) {
    AuthTitle("Log in")
    PhoneFields(state, vm)
    ErrorText(state.error)
    Spacer(Modifier.height(28.dp))
    PillButton("Send code", enabled = state.number.count(Char::isDigit) >= 6, loading = state.loading, onClick = vm::requestCode)
    OrDivider()
    // Joining an account that's already on a phone is the other way in.
    OutlinedButton(
        onClick = vm::startLink,
        enabled = !state.loading,
        shape = CircleShape,
        modifier = Modifier.fillMaxWidth().height(54.dp),
    ) {
        Icon(painterResource(R.drawable.ic_devices), null, Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text("Link to NovaChat on your phone", fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(28.dp))
    SwitchPrompt("Don't have an account?", "Sign up") { vm.setSignUp(true) }
}

@Composable
private fun ColumnScope.SignUpStep(state: AuthUiState, vm: AuthViewModel) {
    AuthTitle("Create account")
    FieldLabel("Your name")
    NovaField(
        value = state.name,
        onValueChange = vm::onName,
        placeholder = "What should people call you?",
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(18.dp))
    PhoneFields(state, vm)
    Spacer(Modifier.height(12.dp))
    Text(
        "We'll text you a 6-digit code to confirm your number. Your chats are end-to-end encrypted.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ErrorText(state.error)
    Spacer(Modifier.height(28.dp))
    PillButton(
        "Create account",
        enabled = state.name.isNotBlank() && state.number.count(Char::isDigit) >= 6,
        loading = state.loading,
        onClick = vm::requestCode,
    )
    Spacer(Modifier.height(28.dp))
    SwitchPrompt("Already have an account?", "Log in") { vm.setSignUp(false) }
}

@Composable
private fun PhoneFields(state: AuthUiState, vm: AuthViewModel) {
    FieldLabel("Phone number")
    Row(verticalAlignment = Alignment.CenterVertically) {
        NovaField(
            value = state.countryCode,
            onValueChange = vm::onCountryCode,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.width(92.dp),
        )
        Spacer(Modifier.width(10.dp))
        NovaField(
            value = state.number,
            onValueChange = vm::onNumber,
            placeholder = "1712 345678",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
            modifier = Modifier.weight(1f),
        )
    }
}

/** A device joining an account that's on a phone: shows a code to type on the phone. */
@Composable
private fun ColumnScope.LinkStep(state: AuthUiState, vm: AuthViewModel) {
    AuthTitle("Link a device")
    Text(
        "On your phone, open NovaChat, go to Settings, then Linked devices, tap Link a device and type this code.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    val code = state.linkCode
    if (code != null) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                code,
                style = MaterialTheme.typography.displaySmall.copy(letterSpacing = 4.sp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 20.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Waiting for your phone…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Messages from before you link stay on your phone. Restore a chat backup here to bring them over.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    ErrorText(state.error)
    if (code == null) {
        Spacer(Modifier.height(24.dp))
        PillButton("Get a new code", enabled = true, loading = state.loading, onClick = vm::startLink)
    }
}

@Composable
private fun ColumnScope.CodeStep(state: AuthUiState, vm: AuthViewModel) {
    AuthTitle("Verify")
    FieldLabel("Code sent to ${state.normalizedPhone}")
    NovaField(
        value = state.code,
        onValueChange = vm::onCode,
        placeholder = "––– –––",
        textStyle = MaterialTheme.typography.titleLarge.copy(letterSpacing = 8.sp, textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = vm::back, enabled = !state.loading) { Text("Change number") }
        TextButton(onClick = vm::requestCode, enabled = !state.loading) { Text("Resend code", fontWeight = FontWeight.SemiBold) }
    }
    state.devCode?.let { code ->
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(14.dp)) {
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
    PillButton("Verify", enabled = state.code.length == 6, loading = state.loading, onClick = vm::verify)
}

/** Two-step verification: the account's PIN, after the SMS code. */
@Composable
private fun ColumnScope.PinStep(state: AuthUiState, vm: AuthViewModel) {
    var confirmForgot by remember { mutableStateOf(false) }
    var showPin by remember { mutableStateOf(false) }
    AuthTitle("Two-step PIN")
    Text(
        "This account is protected with a PIN. Enter the 6-digit PIN you chose.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))
    FieldLabel("PIN")
    NovaField(
        value = state.pin,
        onValueChange = vm::onPin,
        placeholder = "••••••",
        visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
        textStyle = MaterialTheme.typography.titleLarge.copy(letterSpacing = 8.sp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        trailingIcon = {
            IconButton(onClick = { showPin = !showPin }) {
                Icon(
                    painterResource(if (showPin) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                    if (showPin) "Hide PIN" else "Show PIN",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    TextButton(
        onClick = { if (state.resetAt != null) vm.forgotPin() else confirmForgot = true },
        modifier = Modifier.align(Alignment.End),
    ) {
        Text(if (state.resetAt != null) "Sign in without PIN" else "Forgot PIN?", fontWeight = FontWeight.SemiBold)
    }
    ErrorText(state.error)
    Spacer(Modifier.height(16.dp))
    PillButton("Continue", enabled = state.pin.length == 6, loading = state.loading, onClick = vm::submitPin)
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

    AuthScaffold(welcome = "You're in! Let's set up your profile.") {
        AuthTitle("Your profile")
        FieldLabel("Your name")
        NovaField(
            value = name,
            onValueChange = { name = it.take(25); error = null },
            placeholder = "The name people see when you message them",
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorText(error)
        Spacer(Modifier.height(28.dp))
        PillButton("Continue", enabled = name.isNotBlank(), loading = loading) {
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

// ---- Shared pieces of the sign-in screens ----

/** The bottom edge sweeps up from the left corner to the right side. */
private val HeaderCurve = GenericShape { size, _ ->
    moveTo(0f, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width, size.height * 0.58f)
    quadraticBezierTo(size.width * 0.78f, size.height * 1.04f, 0f, size.height)
    close()
}

private val HeaderBrush = Brush.linearGradient(
    listOf(Color(0xFF22107A), Color(0xFF3A1FC9), NovaViolet),
    start = Offset.Zero,
    end = Offset(900f, 700f),
)

/**
 * A violet header with the NovaChat mark and a welcome line, and the form below it.
 * The whole page scrolls so the keyboard never hides a field.
 */
@Composable
private fun AuthScaffold(
    welcome: String,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .imePadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(HeaderCurve)
                    .background(HeaderBrush),
            ) {
                // Two soft glows: an aqua spark top right and a violet bloom lower down.
                Box(Modifier.matchParentSize()) {
                    Box(
                        Modifier
                            .size(220.dp)
                            .align(Alignment.TopEnd)
                            .offset(x = 70.dp, y = (-60).dp)
                            .background(Brush.radialGradient(listOf(NovaAqua.copy(alpha = 0.35f), Color.Transparent))),
                    )
                    Box(
                        Modifier
                            .size(260.dp)
                            .align(Alignment.BottomStart)
                            .offset(x = (-90).dp, y = 60.dp)
                            .background(Brush.radialGradient(listOf(Color(0xFF8B5CF6).copy(alpha = 0.55f), Color.Transparent))),
                    )
                }
                Column(
                    Modifier
                        .statusBarsPadding()
                        .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, content = actions)
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.ic_logo), null, Modifier.size(44.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "NOVACHAT",
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge.copy(letterSpacing = 5.sp, fontWeight = FontWeight.ExtraBold),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        welcome,
                        color = Color.White.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), content = content)
        }
    }
}

@Composable
private fun AuthTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineLarge.copy(fontSize = 34.sp, lineHeight = 42.sp))
    Spacer(Modifier.height(22.dp))
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
}

/** A filled, rounded field that gets a brand-coloured ring while you type in it. */
@Composable
private fun NovaField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(20.dp)
    val ring = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = placeholder?.let { { Text(it, style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)) } },
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        textStyle = textStyle,
        trailingIcon = trailingIcon,
        interactionSource = interaction,
        shape = shape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surface,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        modifier = modifier.border(if (focused) 1.5.dp else 1.dp, ring, shape),
    )
}

@Composable
private fun OrDivider() {
    Row(Modifier.fillMaxWidth().padding(vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            "or",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** "Don't have an account? Sign up" and its opposite. */
@Composable
private fun SwitchPrompt(question: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text(question, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onClick) { Text(action, fontWeight = FontWeight.ExtraBold) }
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
private fun PillButton(text: String, enabled: Boolean, loading: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        } else {
            Text(text, style = MaterialTheme.typography.titleMedium)
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
