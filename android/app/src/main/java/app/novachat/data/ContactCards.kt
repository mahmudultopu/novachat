package app.novachat.data

/** "👤 Ana Rahman", for the chat list and notifications. */
fun contactLabel(card: SharedContact): String = "👤 " + card.name

/**
 * A contact card trimmed to what NovaChat shows: a name of at most 100
 * characters and up to five distinct numbers. Null when there's nothing to
 * show, so junk from a broken sender is dropped.
 */
fun cleanContact(card: SharedContact): SharedContact? {
    val phones = card.phones
        .map { p -> p.filter { it.isDigit() || it in PHONE_CHARS }.trim().replace(Regex("\\s+"), " ") }
        .filter { p -> p.count(Char::isDigit) in 3..MAX_PHONE_DIGITS }
        .distinctBy { p -> p.filter { it.isDigit() || it == '+' } }
        .take(MAX_CARD_PHONES)
    val name = card.name.trim().replace(Regex("\\s+"), " ").take(MAX_CARD_NAME).ifEmpty { phones.firstOrNull() ?: return null }
    return SharedContact(name, phones)
}

/** "AR" for Ana Rahman, "A" for Ana, "#" when the name is only a number. */
fun contactInitials(name: String): String {
    val words = name.split(' ').filter { w -> w.firstOrNull()?.isLetter() == true }
    if (words.isEmpty()) return "#"
    return (words.first().take(1) + if (words.size > 1) words.last().take(1) else "").uppercase()
}

/** The first number written in +country form, which is what NovaChat accounts use. */
fun reachableNumber(card: SharedContact): String? =
    card.phones.firstNotNullOfOrNull { internationalNumber(it) }

const val MAX_CARD_NAME = 100
const val MAX_CARD_PHONES = 5
private const val MAX_PHONE_DIGITS = 20
private const val PHONE_CHARS = "+-() "
