package app.novachat.ui.auth

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.novachat.AppContainer
import app.novachat.data.ApiException
import app.novachat.data.Session
import app.novachat.data.User
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val step: Step = Step.Phone,
    val countryCode: String = "+880",
    val number: String = "",
    val normalizedPhone: String = "",
    val code: String = "",
    val devCode: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** Two-step verification: the server's ticket for this sign-in, and the PIN typed so far. */
    val challenge: String? = null,
    val pin: String = "",
    /** When a "forgot PIN" reset takes effect, as the server sent it. */
    val resetAt: String? = null,
    /** Linking to a phone: the code to type there, or null once it expired. */
    val linkCode: String? = null,
    /** Creating an account rather than logging in; the name to give it. */
    val signUp: Boolean = false,
    val name: String = "",
) {
    enum class Step { Phone, Code, Pin, Link }
}

/** What to tell someone whose PIN didn't work. */
internal fun pinErrorText(e: ApiException): String = when (e.code) {
    "bad_pin" -> e.body?.attemptsLeft?.let { left ->
        if (left == 1) "Wrong PIN. 1 try left before sign-in locks." else "Wrong PIN. $left tries left before sign-in locks."
    } ?: "Wrong PIN"
    "pin_locked" -> "Too many wrong PINs. Try again after ${shortDate(e.body?.lockedUntil)}."
    "pin_reset_pending" -> "Your PIN will be reset on ${shortDate(e.body?.resetAt)}. Come back then to sign in without it."
    "bad_challenge" -> "This sign-in took too long. Go back and request a new code."
    else -> e.message
}

/** "12 Nov, 14:05" in the phone's time zone, from the server's timestamp. */
internal fun shortDate(iso: String?): String = try {
    java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm"))
} catch (e: Exception) {
    "a while"
}

class AuthViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state

    val serverUrl = container.sessionStore.serverUrl

    fun onCountryCode(v: String) = _state.update { it.copy(countryCode = "+" + v.filter(Char::isDigit).take(4), error = null) }
    fun onNumber(v: String) = _state.update { it.copy(number = v.filter { c -> c.isDigit() || c == ' ' }.take(16), error = null) }
    fun onName(v: String) = _state.update { it.copy(name = v.take(25), error = null) }

    /** Switch between "Log in" and "Create account". Both use the same SMS sign-in. */
    fun setSignUp(on: Boolean) = _state.update { it.copy(signUp = on, error = null) }

    fun onCode(v: String) {
        val digits = v.filter(Char::isDigit).take(6)
        _state.update { it.copy(code = digits, error = null) }
        if (digits.length == 6) verify()
    }

    fun onPin(v: String) {
        val digits = v.filter(Char::isDigit).take(6)
        _state.update { it.copy(pin = digits, error = null) }
        if (digits.length == 6) submitPin()
    }

    fun back() {
        linking?.cancel()
        _state.update {
            it.copy(step = AuthUiState.Step.Phone, code = "", error = null, challenge = null, pin = "", resetAt = null, linkCode = null)
        }
    }

    private var linking: Job? = null

    /** Use this device with an account that's already on a phone: show a code to type there. */
    fun startLink() = launchWithErrors {
        val url = container.sessionStore.currentServerUrl()
        val start = container.api.startLink(url, "${Build.MANUFACTURER} ${Build.MODEL}")
        _state.update { it.copy(step = AuthUiState.Step.Link, linkCode = start.code, error = null) }
        linking?.cancel()
        linking = viewModelScope.launch { waitForPhone(url, start.poll, start.expiresIn) }
    }

    private suspend fun waitForPhone(url: String, poll: String, expiresInSec: Int) {
        val until = System.currentTimeMillis() + expiresInSec * 1000L
        while (System.currentTimeMillis() < until) {
            delay(LINK_POLL_MS)
            val res = try {
                container.api.pollLink(url, poll)
            } catch (e: ApiException) {
                if (e.status == 404) break
                continue // offline for a moment
            } ?: continue
            signIn(res.token, res.user)
            return
        }
        _state.update { it.copy(linkCode = null, error = "The code expired. Get a new one to try again.") }
    }

    fun setServerUrl(url: String) = viewModelScope.launch { container.sessionStore.setServerUrl(url) }

    fun requestCode() = launchWithErrors {
        val s = _state.value
        val res = container.api.requestCode(container.sessionStore.currentServerUrl(), s.countryCode + s.number)
        _state.update {
            it.copy(step = AuthUiState.Step.Code, normalizedPhone = res.phone, devCode = res.devCode, code = "")
        }
    }

    fun verify() = launchWithErrors {
        val s = _state.value
        val res = try {
            container.api.verify(
                container.sessionStore.currentServerUrl(),
                s.normalizedPhone,
                s.code,
                "${Build.MANUFACTURER} ${Build.MODEL}",
            )
        } catch (e: ApiException) {
            val challenge = e.body?.challenge
            if (e.code != "pin_required" || challenge == null) throw e
            _state.update { it.copy(step = AuthUiState.Step.Pin, challenge = challenge, pin = "") }
            return@launchWithErrors
        }
        signIn(res.token, res.user)
    }

    fun submitPin() = finishWithPin(forgot = false)

    fun forgotPin() = finishWithPin(forgot = true)

    private fun finishWithPin(forgot: Boolean) = launchWithErrors {
        val s = _state.value
        val challenge = s.challenge ?: return@launchWithErrors
        val res = try {
            container.api.verifyPin(container.sessionStore.currentServerUrl(), challenge, s.pin, forgot)
        } catch (e: ApiException) {
            _state.update { it.copy(pin = "", resetAt = e.body?.resetAt ?: it.resetAt) }
            throw ApiException(e.status, e.code, pinErrorText(e), e.body)
        }
        signIn(res.token, res.user)
    }

    /**
     * A new account made on the sign-up screen gets the name typed there, so the
     * profile step can be skipped. An existing account keeps the name it has.
     */
    private suspend fun signIn(token: String, user: User) {
        val name = _state.value.name.trim()
        var signedIn = user
        if (_state.value.signUp && name.isNotEmpty() && user.name.isBlank()) {
            val url = container.sessionStore.currentServerUrl()
            signedIn = try {
                container.api.updateName(Session(token, user, url), name)
            } catch (e: ApiException) {
                user // the profile step asks again
            }
        }
        container.sessionStore.signIn(token, signedIn)
    }

    private companion object {
        const val LINK_POLL_MS = 2_000L
    }

    private fun launchWithErrors(block: suspend () -> Unit) {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                block()
                _state.update { it.copy(loading = false) }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }
}
