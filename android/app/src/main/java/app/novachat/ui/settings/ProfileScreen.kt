package app.novachat.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.novachat.R
import app.novachat.SessionState
import app.novachat.data.ApiException
import app.novachat.data.MediaException
import app.novachat.data.User
import app.novachat.ui.appContainer
import app.novachat.ui.common.Avatar
import kotlinx.coroutines.launch

/** Edit your own photo, name and about. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by container.session.collectAsStateWithLifecycle()
    val session = (state as? SessionState.SignedIn)?.session ?: return
    val me = session.user

    var name by rememberSaveable { mutableStateOf(me.name) }
    var about by rememberSaveable { mutableStateOf(me.about) }
    var savingPhoto by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    // Runs a profile change and keeps the signed-in user up to date.
    fun change(busy: (Boolean) -> Unit, done: String?, action: suspend () -> User) {
        busy(true)
        scope.launch {
            try {
                container.sessionStore.updateUser(action())
                done?.let(::toast)
            } catch (e: ApiException) {
                toast(e.message ?: "Couldn't save")
            } catch (e: MediaException) {
                toast(e.message ?: "Couldn't use that photo")
            }
            busy(false)
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            change({ savingPhoto = it }, null) {
                container.api.setAvatar(session, container.chats.media.prepareAvatar(uri))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Box(
                    Modifier.clip(CircleShape).clickable {
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Avatar(me.displayName, me.id, 140.dp, me.avatarId)
                    if (savingPhoto) CircularProgressIndicator(Modifier.size(40.dp))
                }
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_camera), "Change photo", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
            Row {
                TextButton(onClick = {
                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text(if (me.avatarId == null) "Add photo" else "Change photo") }
                if (me.avatarId != null) {
                    TextButton(onClick = {
                        change({ savingPhoto = it }, null) { container.api.removeAvatar(session) }
                    }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(25) },
                label = { Text("Name") },
                supportingText = { Text("People see this when you message them") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = about,
                onValueChange = { about = it.take(140) },
                label = { Text("About") },
                supportingText = { Text("${about.length}/140") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = me.phone,
                onValueChange = {},
                label = { Text("Phone") },
                readOnly = true,
                enabled = false,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
            val changed = name.trim() != me.name || about.trim() != me.about
            Button(
                onClick = {
                    change({ saving = it }, "Profile saved") {
                        container.api.updateProfile(session, name.trim(), about.trim())
                    }
                },
                enabled = changed && name.isNotBlank() && !saving,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Save")
            }
        }
    }
}
