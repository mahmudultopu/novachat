package app.novachat.ui.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.novachat.SessionState
import app.novachat.data.ApiException
import app.novachat.data.User
import app.novachat.ui.appContainer
import app.novachat.ui.common.Avatar
import kotlinx.coroutines.launch

/** Pick people, then name the group. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewGroupScreen(onBack: () -> Unit, onCreated: (String) -> Unit) {
    val container = appContainer()
    val repo = container.chats
    val scope = rememberCoroutineScope()
    val session by container.session.collectAsStateWithLifecycle()
    val me = (session as? SessionState.SignedIn)?.session?.user?.id
    val people by repo.people.collectAsStateWithLifecycle(initialValue = emptyMap())
    val candidates = people.values.filter { it.id != me }.sortedBy { it.displayName.lowercase() }

    var picked by remember { mutableStateOf(listOf<String>()) }
    var naming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (naming) "Name the group" else "New group")
                        Text(
                            if (picked.isEmpty()) "Add people" else "${picked.size} selected",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (naming) naming = false else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            if (picked.isNotEmpty() && !busy) {
                FloatingActionButton(
                    onClick = {
                        if (!naming) {
                            naming = true
                        } else if (name.isNotBlank()) {
                            busy = true
                            scope.launch {
                                try {
                                    onCreated(repo.createGroup(name, picked))
                                } catch (e: ApiException) {
                                    error = e.message
                                }
                                busy = false
                            }
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    if (naming) Icon(Icons.Filled.Check, "Create group") else Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next")
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (picked.isNotEmpty()) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                ) {
                    items(picked, key = { it }) { id ->
                        val u = people[id]
                        Column(
                            Modifier.width(64.dp).clickable(enabled = !naming) { picked = picked - id },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Avatar(u?.displayName ?: "…", id, 48.dp, u?.avatarId)
                            Text(
                                u?.displayName ?: "…",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                HorizontalDivider()
            }
            if (naming) {
                Column(Modifier.padding(24.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(50); error = null },
                        label = { Text("Group name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "You can add a photo and description from the group's info page.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (busy) {
                        Spacer(Modifier.height(16.dp))
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                    error?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            } else {
                AddByPhone(onFound = { u -> if (u.id != me && u.id !in picked) picked = picked + u.id })
                if (candidates.isEmpty()) {
                    Text(
                        "People you chat with show up here. You can also add someone by phone number above.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(candidates, key = { it.id }) { u ->
                        PersonRow(u, checked = u.id in picked) {
                            picked = if (u.id in picked) picked - u.id else picked + u.id
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PersonRow(u: User, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(u.displayName, u.id, 44.dp, u.avatarId)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(u.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                u.about.ifBlank { u.phone },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Checkbox(checked = checked, onCheckedChange = { onClick() })
    }
}

/** A phone number field that finds someone on NovaChat. */
@Composable
internal fun AddByPhone(onFound: (User) -> Unit) {
    val repo = appContainer().chats
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("+") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it; error = null },
                label = { Text("Add by phone number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                isError = error != null,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                enabled = !busy && phone.count(Char::isDigit) >= 6,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            onFound(repo.findByPhone(phone))
                            phone = "+"
                        } catch (e: ApiException) {
                            error = e.message
                        }
                        busy = false
                    }
                },
            ) { Text("Add") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
