package app.novachat.ui.auth

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.novachat.AppContainer
import app.novachat.data.ApiException
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
) {
    enum class Step { Phone, Code }
}

class AuthViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state

    val serverUrl = container.sessionStore.serverUrl

    fun onCountryCode(v: String) = _state.update { it.copy(countryCode = "+" + v.filter(Char::isDigit).take(4), error = null) }
    fun onNumber(v: String) = _state.update { it.copy(number = v.filter { c -> c.isDigit() || c == ' ' }.take(16), error = null) }
    fun onCode(v: String) {
        val digits = v.filter(Char::isDigit).take(6)
        _state.update { it.copy(code = digits, error = null) }
        if (digits.length == 6) verify()
    }

    fun back() = _state.update { it.copy(step = AuthUiState.Step.Phone, code = "", error = null) }

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
        val res = container.api.verify(
            container.sessionStore.currentServerUrl(),
            s.normalizedPhone,
            s.code,
            "${Build.MANUFACTURER} ${Build.MODEL}",
        )
        container.sessionStore.signIn(res.token, res.user)
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
