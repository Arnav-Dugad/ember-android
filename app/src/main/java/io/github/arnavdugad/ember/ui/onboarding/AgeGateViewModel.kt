package io.github.arnavdugad.ember.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.ember.data.model.Ages
import io.github.arnavdugad.ember.data.model.Catalog
import io.github.arnavdugad.ember.data.repo.AuthRepository
import io.github.arnavdugad.ember.data.repo.ProfileRepository
import io.github.arnavdugad.ember.data.repo.UnderageException
import io.github.arnavdugad.ember.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class AgeGateState(
    val birthDate: LocalDate? = null,
    val age: Int? = null,
    val submitting: Boolean = false,
    val error: String? = null,
)

/**
 * The client-side check is only a courtesy. The server (completeAgeGate) recomputes the age and
 * is the one that grants the `adult` claim every other rule depends on.
 */
@HiltViewModel
class AgeGateViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val auth: AuthRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AgeGateState())
    val state: StateFlow<AgeGateState> = _state.asStateFlow()

    fun setBirthDate(date: LocalDate) {
        _state.update { it.copy(birthDate = date, age = Ages.ageOn(date, LocalDate.now()), error = null) }
    }

    fun submit() {
        val date = _state.value.birthDate ?: return
        // Under-18 dates are still sent: the server disables the account and deletes its data,
        // so a blocked minor can't simply reinstall and try again with the same login.
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            try {
                profiles.completeAgeGate(date, Catalog.TERMS_VERSION)
                auth.refreshClaims()
            } catch (e: UnderageException) {
                block()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Couldn't verify your age. Try again.") }
            } finally {
                _state.update { it.copy(submitting = false) }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { auth.signOut() }
    }

    private suspend fun block() {
        settings.setAgeGateBlocked()
        auth.signOut()
    }
}
