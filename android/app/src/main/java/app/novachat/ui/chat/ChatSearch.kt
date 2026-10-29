package app.novachat.ui.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import app.novachat.data.ChatMessage
import app.novachat.data.MessageRef

/** Messages whose text contains [query], newest first. */
fun searchMatches(messages: List<ChatMessage>, query: String): List<MessageRef> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    return messages.asReversed()
        .filter { !it.deleted && !it.undecryptable && !it.system && it.body.contains(q, ignoreCase = true) }
        .map { it.ref }
}

/** The search box that replaces a chat's title while searching it. */
@Composable
fun ChatSearchField(query: String, onQuery: (String) -> Unit, onSearch: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text("Search this chat") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.focusRequester(focus),
    )
}

/** "2 of 5" with arrows to older and newer matches. [index] 0 is the newest. */
@Composable
fun ChatSearchArrows(index: Int, count: Int, onOlder: () -> Unit, onNewer: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (count > 0) {
            Text("${index + 1} of $count", style = MaterialTheme.typography.labelMedium)
        }
        IconButton(onClick = onOlder, enabled = index + 1 < count) { Icon(Icons.Filled.KeyboardArrowUp, "Older match") }
        IconButton(onClick = onNewer, enabled = index > 0) { Icon(Icons.Filled.KeyboardArrowDown, "Newer match") }
    }
}
