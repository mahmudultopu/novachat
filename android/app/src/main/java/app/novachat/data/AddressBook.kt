package app.novachat.data

import android.content.Context
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** A name and number from the phone's address book, with the number in +country form. */
data class PhoneContact(val name: String, val phone: String)

/** Reads the phone's contacts. Needs the READ_CONTACTS permission. */
class AddressBook(private val context: Context) {
    suspend fun read(): List<PhoneContact> = withContext(Dispatchers.IO) {
        val country = countryIso()
        val found = mutableListOf<PhoneContact>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)?.trim().orEmpty()
                val number = cursor.getString(1) ?: continue
                val phone = toE164(number, country) ?: continue
                found += PhoneContact(name.ifEmpty { phone }, phone)
            }
        }
        uniqueContacts(found)
    }

    private fun countryIso(): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        return listOf(tm?.simCountryIso, tm?.networkCountryIso, Locale.getDefault().country)
            .firstOrNull { !it.isNullOrBlank() }?.uppercase(Locale.ROOT) ?: "US"
    }

    private fun toE164(number: String, country: String): String? =
        internationalNumber(number) ?: PhoneNumberUtils.formatNumberToE164(number, country)
}

/**
 * Numbers already written with a country code ("+880 1712-345678", "00880...") as
 * +8801712345678, or null for local numbers, which need the phone's country.
 */
fun internationalNumber(raw: String): String? {
    val trimmed = raw.trim()
    val digits = trimmed.filter(Char::isDigit)
    val e164 = when {
        trimmed.startsWith("+") -> "+$digits"
        digits.startsWith("00") -> "+" + digits.removePrefix("00")
        else -> return null
    }
    return e164.takeIf { it.length in 9..16 && it[1] != '0' }
}

/** One entry per number, keeping the first name seen; sorted by name. */
fun uniqueContacts(contacts: List<PhoneContact>): List<PhoneContact> =
    contacts.distinctBy { it.phone }.sortedBy { it.name.lowercase(Locale.ROOT) }
