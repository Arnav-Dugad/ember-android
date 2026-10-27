package io.github.arnavdugad.ember.ui.auth

import android.app.Activity
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.ember.data.preview.DemoContent
import io.github.arnavdugad.ember.data.preview.DemoData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.ember.data.repo.AuthRepository
import io.github.arnavdugad.ember.data.repo.BackendMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val info: String? = null,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: AuthRepository,
    @ApplicationContext context: Context,
) : ViewModel() {
    /** Three portraits (of people who don't exist) for the welcome hero; empty if none are bundled. */
    val heroPhotos: List<String> = DemoContent.load(context).portraits.let { all ->
        listOfNotNull(
            all.firstOrNull { it.gender == "woman" },
            all.firstOrNull { it.gender == "man" },
            all.filter { it.gender == "woman" }.getOrNull(1) ?: all.firstOrNull { it.gender == "nonbinary" },
        ).map { DemoData.PORTRAIT_BASE + it.file }
    }

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    val isPreview: Boolean get() = auth.mode == BackendMode.Preview

    fun enterPreview() = run { auth.enterPreview() }

    fun signInWithGoogle(activity: Activity) = run { auth.signInWithGoogle(activity) }

    fun emailAuth(email: String, password: String, createAccount: Boolean) {
        if (!email.contains('@')) return fail("Enter a valid email address.")
        if (password.length < 8) return fail("Passwords need at least 8 characters.")
        run { if (createAccount) auth.signUpWithEmail(email, password) else auth.signInWithEmail(email, password) }
    }

    fun resetPassword(email: String) {
        if (!email.contains('@')) return fail("Enter your email above first.")
        run {
            auth.sendPasswordReset(email)
            _state.update { it.copy(info = "Check your inbox for a reset link.") }
        }
    }

    fun reset() = _state.update { AuthUiState() }

    fun clearMessages() = _state.update { it.copy(error = null, info = null) }

    private fun fail(message: String) = _state.update { it.copy(error = message, info = null) }

    private fun run(block: suspend () -> Unit) {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, info = null) }
            val error = runCatching { block() }.exceptionOrNull()
            _state.update { it.copy(loading = false, error = error?.let(::friendly)) }
        }
    }

    private fun friendly(e: Throwable): String? = when (e) {
        is GetCredentialCancellationException -> null
        is FirebaseAuthWeakPasswordException -> "That password is too easy to guess."
        is FirebaseAuthUserCollisionException -> "That email already has an account. Sign in, or use Continue with Google if that's how you joined."
        is FirebaseAuthInvalidUserException -> "We couldn't find that account."
        is FirebaseAuthInvalidCredentialsException -> "That didn't match. If you joined with Google, tap Continue with Google instead."
        is FirebaseNetworkException -> "You're offline. Check your connection."
        is androidx.credentials.exceptions.NoCredentialException -> "Add a Google account to this phone first, then try again."
        else -> e.message ?: "Something went wrong. Please try again."
    }
}
