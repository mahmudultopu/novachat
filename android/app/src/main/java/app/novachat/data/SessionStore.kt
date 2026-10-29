package app.novachat.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.novachat.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "session")

/**
 * Keeps the signed-in session and the server address on disk.
 *
 * TODO: before release, keep the token encrypted with a key from the
 * Android Keystore (see the guide's security section).
 */
class SessionStore(private val context: Context) {
    private object Keys {
        val token = stringPreferencesKey("token")
        val userId = stringPreferencesKey("user_id")
        val phone = stringPreferencesKey("phone")
        val name = stringPreferencesKey("name")
        val about = stringPreferencesKey("about")
        val avatarId = stringPreferencesKey("avatar_id")
        val serverUrl = stringPreferencesKey("server_url")
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { it.serverUrl() }

    val session: Flow<Session?> = context.dataStore.data.map { prefs ->
        val token = prefs[Keys.token] ?: return@map null
        Session(
            token = token,
            user = User(
                id = prefs[Keys.userId].orEmpty(),
                phone = prefs[Keys.phone].orEmpty(),
                name = prefs[Keys.name].orEmpty(),
                about = prefs[Keys.about].orEmpty(),
                avatarId = prefs[Keys.avatarId],
            ),
            serverUrl = prefs.serverUrl(),
        )
    }

    suspend fun currentServerUrl(): String = serverUrl.first()

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { it[Keys.serverUrl] = url.trim().trimEnd('/') }
    }

    suspend fun signIn(token: String, user: User) {
        context.dataStore.edit {
            it[Keys.token] = token
            it.putUser(user)
        }
    }

    suspend fun updateUser(user: User) {
        context.dataStore.edit { it.putUser(user) }
    }

    suspend fun signOut() {
        context.dataStore.edit {
            it.remove(Keys.token)
            it.remove(Keys.userId)
            it.remove(Keys.phone)
            it.remove(Keys.name)
            it.remove(Keys.about)
            it.remove(Keys.avatarId)
        }
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.putUser(user: User) {
        this[Keys.userId] = user.id
        this[Keys.phone] = user.phone
        this[Keys.name] = user.name
        this[Keys.about] = user.about
        if (user.avatarId != null) this[Keys.avatarId] = user.avatarId else remove(Keys.avatarId)
    }

    private fun Preferences.serverUrl() = this[Keys.serverUrl] ?: BuildConfig.DEFAULT_SERVER_URL
}
