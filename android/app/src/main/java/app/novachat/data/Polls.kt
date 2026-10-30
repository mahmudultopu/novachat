package app.novachat.data

/** "📊 Where should we eat?", for the chat list and notifications. */
fun pollLabel(poll: Poll): String = "📊 " + poll.question

/**
 * A poll trimmed to what NovaChat shows: a question of at most 200
 * characters and 2 to 12 distinct options of at most 100. Null when it's
 * not a real poll, so junk from a broken sender is dropped.
 */
fun cleanPoll(poll: Poll): Poll? {
    val question = poll.question.trim().take(MAX_POLL_QUESTION)
    val options = poll.options.map { it.trim().take(MAX_POLL_OPTION) }.filter { it.isNotEmpty() }.distinct().take(MAX_POLL_OPTIONS)
    if (question.isEmpty() || options.size < 2) return null
    return Poll(question, options, poll.multi)
}

/** What's wrong with a poll being written, or null when it's ready to send. */
fun pollProblem(question: String, options: List<String>): String? {
    val filled = options.map { it.trim() }.filter { it.isNotEmpty() }
    return when {
        question.isBlank() -> "Ask a question"
        filled.size < 2 -> "Add at least 2 options"
        filled.size != filled.distinct().size -> "Each option must be different"
        else -> null
    }
}

/** A vote that fits [poll]: real options only, each once, and just one for single-choice polls. */
fun cleanChoices(poll: Poll, choices: List<Int>): List<Int> {
    val valid = choices.filter { it in poll.options.indices }.distinct().sorted()
    return if (poll.multi) valid else valid.take(1)
}

/** Your new picks after tapping option [index]. */
fun toggleChoice(poll: Poll, mine: List<Int>, index: Int): List<Int> = when {
    index in mine -> mine - index
    poll.multi -> (mine + index).sorted()
    else -> listOf(index)
}

/** How many people picked each option, in option order. */
fun tally(poll: Poll, votes: Map<String, List<Int>>): List<Int> =
    poll.options.indices.map { i -> votes.values.count { i in it } }

/** "3 votes", "1 vote", "No votes yet": counts people, not picks. */
fun votersText(votes: Map<String, List<Int>>): String = when (val n = votes.values.count { it.isNotEmpty() }) {
    0 -> "No votes yet"
    1 -> "1 vote"
    else -> "$n votes"
}

const val MAX_POLL_QUESTION = 200
const val MAX_POLL_OPTION = 100
const val MAX_POLL_OPTIONS = 12
