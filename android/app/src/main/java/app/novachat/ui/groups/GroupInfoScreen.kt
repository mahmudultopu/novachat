package app.novachat.ui.groups

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.novachat.R
import app.novachat.SessionState
import app.novachat.data.ApiException
import app.novachat.data.GroupMember
import app.novachat.data.MediaException
import app.novachat.data.User
import app.novachat.ui.appContainer
import app.novachat.ui.chat.ChatWallpaperCard
import app.novachat.ui.chat.DisappearingCard
import app.novachat.ui.common.Avatar
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A group's photo, name, description, invite link, settings and members. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(groupId: String, onBack: () -> Unit, openChat: (String) -> Unit, onLeft: () -> Unit) {
    val container = appContainer()
    val repo = container.chats
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val group by remember(groupId) { repo.group(groupId) }.collectAsStateWithLifecycle(initialValue = null)
    val people by repo.people.collectAsStateWithLifecycle(initialValue = emptyMap())
    val state by container.session.collectAsStateWithLifecycle()
    val session = (state as? SessionState.SignedIn)?.session
    val me = session?.user?.id
    val g = group

    var busy by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(false) }
    var editDescription by remember { mutableStateOf(false) }
    var addPeople by remember { mutableStateOf(false) }
    var memberSheet by remember { mutableStateOf<GroupMember?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    // Runs one change on the server, showing its error if it fails.
    fun act(done: String? = null, action: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                action()
                done?.let(::toast)
            } catch (e: ApiException) {
                toast(e.message)
            } catch (e: MediaException) {
                toast(e.message ?: "Couldn't use that photo")
            }
            busy = false
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            act { repo.changeGroup { setGroupAvatar(it, groupId, repo.media.prepareAvatar(uri)) } }
        }
    }

    if (g == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val admin = g.isAdmin(me)
    val canEdit = g.canEditInfo(me)
    val inviteLink = g.inviteCode?.let { session?.serverUrl?.trimEnd('/') + "/join/" + it }

    if (editName) {
        TextEditDialog("Group name", g.name, 50, singleLine = true, onDismiss = { editName = false }) { text ->
            editName = false
            act { repo.changeGroup { updateGroup(it, groupId, buildJsonObject { put("name", text) }) } }
        }
    }
    if (editDescription) {
        TextEditDialog("Description", g.description, 500, singleLine = false, onDismiss = { editDescription = false }) { text ->
            editDescription = false
            act { repo.changeGroup { updateGroup(it, groupId, buildJsonObject { put("description", text) }) } }
        }
    }
    if (addPeople) {
        AddMembersSheet(
            existing = g.members.map { it.userId }.toSet() + listOfNotNull(me),
            people = people,
            onDismiss = { addPeople = false },
        ) { ids ->
            addPeople = false
            act { repo.changeGroup { addGroupMembers(it, groupId, ids) } }
        }
    }
    memberSheet?.let { m ->
        val u = people[m.userId]
        ModalBottomSheet(onDismissRequest = { memberSheet = null }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    u?.displayName ?: "Member",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                SheetAction("Message ${u?.displayName ?: ""}".trim()) {
                    memberSheet = null
                    openChat(m.userId)
                }
                if (admin) {
                    if (m.isAdmin) {
                        SheetAction("Dismiss as admin") {
                            memberSheet = null
                            act { repo.changeGroup { setGroupRole(it, groupId, m.userId, "member") } }
                        }
                    } else {
                        SheetAction("Make group admin") {
                            memberSheet = null
                            act { repo.changeGroup { setGroupRole(it, groupId, m.userId, "admin") } }
                        }
                    }
                    SheetAction("Remove from group", error = true) {
                        memberSheet = null
                        act { repo.changeGroup { removeGroupMember(it, groupId, m.userId) } }
                    }
                }
            }
        }
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave \"${g.name}\"?") },
            text = { Text("You'll stop getting messages from this group. The chat stays on your phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    act {
                        repo.leaveGroup(groupId)
                        onLeft()
                    }
                }) { Text("Leave", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Group info") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { if (busy) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 4.dp), strokeWidth = 2.dp) },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.clip(CircleShape).clickable(enabled = canEdit) {
                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            ) { Avatar(g.name, g.id, 120.dp, g.avatarId) }
            if (canEdit && g.avatarId != null) {
                TextButton(onClick = { act { repo.changeGroup { removeGroupAvatar(it, groupId) } } }) { Text("Remove photo") }
            } else {
                Spacer(Modifier.height(12.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(g.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                if (canEdit) IconButton(onClick = { editName = true }) { Icon(Icons.Filled.Edit, "Change name") }
            }
            Text(
                "Group · ${g.members.size} ${if (g.members.size == 1) "member" else "members"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (g.left) {
                Spacer(Modifier.height(12.dp))
                Text("You're no longer in this group.", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(20.dp))

            Card(onClick = if (canEdit) ({ editDescription = true }) else null) {
                Text("Description", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(
                    g.description.ifBlank { if (canEdit) "Add a group description" else "No description" },
                    color = if (g.description.isBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.height(12.dp))
            DisappearingCard(groupId, enabled = canEdit)
            Spacer(Modifier.height(12.dp))
            ChatWallpaperCard(groupId)

            if (admin) {
                Spacer(Modifier.height(12.dp))
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_link), null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text("Invite link", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (inviteLink == null) {
                        Text(
                            "Anyone with the link can join this group. Only share it with people you trust.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { act { repo.changeGroup { resetInvite(it, groupId) } } }) { Text("Create link") }
                    } else {
                        Text(inviteLink, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                clipboard.setText(AnnotatedString(inviteLink))
                                toast("Link copied")
                            }) { Text("Copy") }
                            OutlinedButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "Join my NovaChat group \"${g.name}\": $inviteLink")
                                }
                                context.startActivity(Intent.createChooser(send, "Share invite link"))
                            }) { Text("Share") }
                        }
                        Row {
                            TextButton(onClick = { act("New link made, the old one no longer works") { repo.changeGroup { resetInvite(it, groupId) } } }) {
                                Text("Reset link")
                            }
                            TextButton(onClick = { act("Invite link turned off") { repo.changeGroup { revokeInvite(it, groupId) } } }) {
                                Text("Turn off", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Card {
                    Text("Group settings", style = MaterialTheme.typography.titleSmall)
                    SettingSwitch("Only admins can send messages", g.onlyAdminsSend) { on ->
                        act { repo.changeGroup { updateGroup(it, groupId, buildJsonObject { put("only_admins_send", on) }) } }
                    }
                    SettingSwitch("Only admins can edit the group info", g.onlyAdminsEdit) { on ->
                        act { repo.changeGroup { updateGroup(it, groupId, buildJsonObject { put("only_admins_edit", on) }) } }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Card {
                Text(
                    "${g.members.size} ${if (g.members.size == 1) "member" else "members"}",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (admin) {
                    Row(
                        Modifier.fillMaxWidth().clickable { addPeople = true }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.onPrimary) }
                        Spacer(Modifier.width(14.dp))
                        Text("Add members", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                // Admins first, then by name; you at the top.
                val sorted = g.members.sortedWith(
                    compareBy<GroupMember>({ it.userId != me }, { !it.isAdmin }, { people[it.userId]?.displayName?.lowercase() ?: "" }),
                )
                for (m in sorted) {
                    val u = people[m.userId]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = m.userId != me) { memberSheet = m }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(u?.displayName ?: "…", m.userId, 44.dp, u?.avatarId)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (m.userId == me) "You" else u?.displayName ?: "…",
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            u?.about?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (m.isAdmin) {
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Text(
                                    "Admin",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (!g.left) {
                Spacer(Modifier.height(20.dp))
                OutlinedButton(onClick = { confirmLeave = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Leave group", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Card(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(16.dp),
        ) { content() }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SheetAction(label: String, error: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
    )
}

@Composable
private fun TextEditDialog(title: String, initial: String, max: Int, singleLine: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(max) },
                singleLine = singleLine,
                supportingText = { Text("${text.length}/$max") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = text.trim() != initial && (!singleLine || text.isNotBlank()), onClick = { onSave(text.trim()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMembersSheet(existing: Set<String>, people: Map<String, User>, onDismiss: () -> Unit, onAdd: (List<String>) -> Unit) {
    var picked by remember { mutableStateOf(listOf<String>()) }
    var found by remember { mutableStateOf(listOf<User>()) }
    val candidates = (people.values + found).distinctBy { it.id }.filter { it.id !in existing }.sortedBy { it.displayName.lowercase() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Text("Add members", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            AddByPhone(onFound = { u ->
                if (u.id !in existing) {
                    found = found + u
                    if (u.id !in picked) picked = picked + u.id
                }
            })
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(candidates, key = { it.id }) { u ->
                    PersonRow(u, checked = u.id in picked) { picked = if (u.id in picked) picked - u.id else picked + u.id }
                }
            }
            Button(
                onClick = { onAdd(picked) },
                enabled = picked.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).height(50.dp),
            ) { Text(if (picked.size > 1) "Add ${picked.size} people" else "Add") }
        }
    }
}
