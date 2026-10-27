package io.github.arnavdugad.ember.ui.root

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Account deletion, as the root screen sees it. The Firestore docs go first and the login last,
 * so for a moment the user is still signed in without an account doc, which normally means "show
 * the age gate". From the start of a deletion until the login is gone, [inProgress] tells the
 * root to keep the current screen; then it lands on the welcome screen with [notice].
 */
@Singleton
class AccountDeletion @Inject constructor() {
    private val _inProgress = MutableStateFlow(false)
    val inProgress: StateFlow<Boolean> = _inProgress.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)

    /** A short message for the welcome screen ("Account deleted"), shown once. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    @Volatile private var signedOut = false

    suspend fun run(delete: suspend () -> Unit) {
        signedOut = false
        _inProgress.value = true
        try {
            delete()
            _notice.value = "Account deleted"
            // Stays in progress until the root sees the sign-out (the login was just deleted).
            if (signedOut) _inProgress.value = false
        } catch (e: Throwable) {
            _inProgress.value = false
            // Signed out part-way (Firebase wanted a fresh sign-in): the settings screen that would
            // show the error is gone, so the welcome screen says it instead.
            if (signedOut) _notice.value = e.message
            throw e
        }
    }

    /** The root saw the user signed out. */
    fun onSignedOut() {
        signedOut = true
        if (_notice.value != null) _inProgress.value = false
    }

    fun consumeNotice() {
        _notice.value = null
    }
}
