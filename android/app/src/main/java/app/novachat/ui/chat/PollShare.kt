package app.novachat.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.novachat.data.MAX_POLL_OPTION
import app.novachat.data.MAX_POLL_OPTIONS
import app.novachat.data.MAX_POLL_QUESTION
import app.novachat.data.Poll
import app.novachat.data.pollProblem
import app.novachat.data.tally
import app.novachat.data.toggleChoice
import app.novachat.data.votersText

/** Writing a poll: a question, 2 to 12 options, and whether people may pick several. */
@Composable
fun CreatePollDialog(onDismiss: () -> Unit, onSend: (Poll) -> Unit) {
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    var multi by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New poll") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it.take(MAX_POLL_QUESTION); error = null },
                    label = { Text("Question") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                options.forEachIndexed { i, option ->
                    OutlinedTextField(
                        value = option,
                        onValueChange = { v ->
                            options[i] = v.take(MAX_POLL_OPTION)
                            error = null
                            // A new empty box appears once the last one is used.
                            if (i == options.lastIndex && v.isNotBlank() && options.size < MAX_POLL_OPTIONS) options.add("")
                        },
                        label = { Text("Option ${i + 1}") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Allow multiple answers", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = multi, onCheckedChange = { multi = it })
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val problem = pollProblem(question, options)
                if (problem != null) error = problem else onSend(Poll(question.trim(), options.map { it.trim() }.filter { it.isNotEmpty() }, multi))
            }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A poll in a chat bubble: tap an option to vote, tap it again to take the vote back. */
@Composable
fun PollCard(
    poll: Poll,
    votes: Map<String, List<Int>>,
    myId: String?,
    fg: Color,
    onVote: (List<Int>) -> Unit,
    nameOf: (String) -> String,
) {
    val mine = myId?.let { votes[it] }.orEmpty()
    val counts = tally(poll, votes)
    val most = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
    var showVotes by remember { mutableStateOf(false) }
    Column(Modifier.widthIn(min = 230.dp, max = 270.dp)) {
        Text(poll.question, color = fg, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            if (poll.multi) "Select one or more" else "Select one",
            color = fg.copy(alpha = 0.65f),
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(6.dp))
        poll.options.forEachIndexed { i, option ->
            val picked = i in mine
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable { onVote(toggleChoice(poll, mine, i)) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (poll.multi) {
                    Checkbox(
                        checked = picked,
                        onCheckedChange = null,
                        colors = CheckboxDefaults.colors(checkedColor = fg, uncheckedColor = fg.copy(alpha = 0.6f), checkmarkColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.size(32.dp),
                    )
                } else {
                    RadioButton(
                        selected = picked,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = fg, unselectedColor = fg.copy(alpha = 0.6f)),
                        modifier = Modifier.size(32.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(option, color = fg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(counts[i].toString(), color = fg.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { counts[i].toFloat() / most },
                        color = fg,
                        trackColor = fg.copy(alpha = 0.15f),
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = fg.copy(alpha = 0.15f))
        TextButton(onClick = { showVotes = true }, enabled = votes.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(if (votes.isEmpty()) votersText(votes) else "View votes · " + votersText(votes), color = fg)
        }
    }
    if (showVotes) {
        AlertDialog(
            onDismissRequest = { showVotes = false },
            title = { Text(poll.question) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    poll.options.forEachIndexed { i, option ->
                        val voters = votes.filterValues { i in it }.keys.map(nameOf).sorted()
                        Text("$option (${voters.size})", style = MaterialTheme.typography.titleSmall)
                        voters.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp, top = 2.dp)) }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showVotes = false }) { Text("Done") } },
        )
    }
}
