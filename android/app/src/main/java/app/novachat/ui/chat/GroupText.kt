package app.novachat.ui.chat

import app.novachat.data.ChatMessage
import app.novachat.data.GroupEvent
import app.novachat.data.NovaJson
import app.novachat.data.User

/** "You", or the person's name, or their number. */
fun personName(id: String, me: String?, people: Map<String, User>): String =
    if (id == me) "You" else people[id]?.displayName ?: "Someone"

/** A group event as a sentence, such as "Ana added Ben and Cy". */
fun groupEventText(e: GroupEvent, me: String?, people: Map<String, User>): String {
    val actor = personName(e.actor, me, people)
    val targets = e.targets.map { personName(it, me, people) }.let { names ->
        when (names.size) {
            0 -> "someone"
            1 -> names[0]
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
    }
    val youTarget = e.targets.size == 1 && e.targets[0] == me
    return when (e.event) {
        "created" -> "$actor created the group \"${e.name.orEmpty()}\""
        "added" -> "$actor added $targets"
        "removed" -> "$actor removed $targets"
        "left" -> "$actor left"
        "joined" -> "$actor joined using an invite link"
        "promoted" -> if (youTarget) "You're now an admin" else "$targets is now an admin"
        "demoted" -> if (youTarget) "You're no longer an admin" else "$targets is no longer an admin"
        "renamed" -> "$actor changed the group name to \"${e.name.orEmpty()}\""
        "description" -> "$actor changed the group description"
        "photo" -> "$actor changed the group photo"
        "settings" -> "$actor changed the group settings"
        else -> "$actor updated the group"
    }
}

/** The text of a group event message. */
fun systemText(m: ChatMessage, me: String?, people: Map<String, User>): String =
    runCatching { NovaJson.decodeFromString<GroupEvent>(m.body) }.getOrNull()
        ?.let { groupEventText(it, me, people) } ?: "Group updated"

/** Pulls the invite code out of a pasted link, or takes the code itself. */
fun inviteCodeFrom(text: String): String? {
    val code = text.trim().substringAfterLast("/join/").substringAfterLast('/').trim()
    return code.takeIf { it.length in 8..64 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }
}
