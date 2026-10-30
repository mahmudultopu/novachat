package app.novachat.ui.auth

import app.novachat.data.ApiErrorBody
import app.novachat.data.ApiException
import app.novachat.ui.settings.newPinProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TwoStepPinTest {
    @Test
    fun newPinsMustBeSixDigitsNotObviousAndMatch() {
        assertNull(newPinProblem("275193", "275193"))
        assertEquals("The PIN must be 6 digits", newPinProblem("1234", "1234"))
        assertEquals("Pick a PIN that's harder to guess", newPinProblem("111111", "111111"))
        assertEquals("Pick a PIN that's harder to guess", newPinProblem("123456", "123456"))
        assertEquals("Pick a PIN that's harder to guess", newPinProblem("654321", "654321"))
        assertEquals("The PINs don't match. Try again.", newPinProblem("275193", "275194"))
    }

    @Test
    fun wrongPinMessagesSayWhatHappensNext() {
        fun err(code: String, body: ApiErrorBody) = ApiException(401, code, "server text", body)
        assertEquals("Wrong PIN. 3 tries left before sign-in locks.", pinErrorText(err("bad_pin", ApiErrorBody(attemptsLeft = 3))))
        assertEquals("Wrong PIN. 1 try left before sign-in locks.", pinErrorText(err("bad_pin", ApiErrorBody(attemptsLeft = 1))))
        assertTrue(pinErrorText(err("pin_locked", ApiErrorBody(lockedUntil = "2026-11-02T10:00:00Z"))).startsWith("Too many wrong PINs. Try again after "))
        assertEquals("server text", pinErrorText(err("other", ApiErrorBody())))
        assertEquals("a while", shortDate("not a date"))
    }
}
