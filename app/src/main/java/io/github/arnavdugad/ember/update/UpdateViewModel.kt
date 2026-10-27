package io.github.arnavdugad.ember.update

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.ember.BuildConfig
import io.github.arnavdugad.ember.data.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UpdateUiState(
    val update: AvailableUpdate? = null,
    val sheetVisible: Boolean = false,
    val download: DownloadState = DownloadState.Idle,
    val needsInstallPermission: Boolean = false,
    val checking: Boolean = false,
    /** One-shot message for a manual check ("You're up to date"). */
    val message: String? = null,
)

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updates: UpdateRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /** Intents the activity must launch (installer, unknown-sources settings). */
    private val _launch = MutableStateFlow<Intent?>(null)
    val launch: StateFlow<Intent?> = _launch.asStateFlow()

    private var downloadJob: Job? = null

    val installedVersion: String get() = updates.installedVersion

    init {
        // Debug builds can't be updated by a release APK (different id and key), so they only
        // check when asked to from Settings.
        if (!BuildConfig.DEBUG) check(manual = false)
    }

    fun check(manual: Boolean) {
        if (_state.value.checking) return
        viewModelScope.launch {
            _state.update { it.copy(checking = true) }
            val result = runCatching { updates.latest() }
            val update = result.getOrNull()
            val snoozed = update != null && !update.mandatory && !manual &&
                settings.updateSnoozedUntil(update.version) > System.currentTimeMillis()
            _state.update {
                it.copy(
                    checking = false,
                    update = update,
                    sheetVisible = update != null && !snoozed,
                    message = when {
                        !manual -> null
                        result.isFailure -> "Couldn't reach GitHub. Check your connection."
                        update == null -> "You're on the latest version (${updates.installedVersion})."
                        else -> null
                    },
                )
            }
        }
    }

    fun remindLater() {
        val update = _state.value.update ?: return
        if (update.mandatory) return
        viewModelScope.launch { settings.snoozeUpdate(update.version, System.currentTimeMillis() + SNOOZE_MILLIS) }
        downloadJob?.cancel()
        _state.update { it.copy(sheetVisible = false, download = DownloadState.Idle) }
    }

    fun startUpdate() {
        val update = _state.value.update ?: return
        val current = _state.value.download
        if (current is DownloadState.Ready) {
            install(current)
            return
        }
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            updates.download(update)
                .catch { e -> emit(DownloadState.Failed(e.message ?: "The download failed.")) }
                .collect { state ->
                    _state.update { it.copy(download = state) }
                    if (state is DownloadState.Ready) install(state)
                }
        }
    }

    /** Called when the user comes back from the "install unknown apps" screen. */
    fun onResume() {
        val ready = _state.value.download as? DownloadState.Ready ?: return
        if (_state.value.needsInstallPermission && updates.canInstall()) install(ready)
    }

    fun openInstallPermission() {
        _launch.value = updates.unknownSourcesIntent()
    }

    fun consumeLaunch() {
        _launch.value = null
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun install(ready: DownloadState.Ready) {
        if (!updates.canInstall()) {
            _state.update { it.copy(needsInstallPermission = true) }
            return
        }
        _state.update { it.copy(needsInstallPermission = false) }
        _launch.value = updates.installIntent(ready.file)
    }

    private companion object {
        const val SNOOZE_MILLIS = 24L * 60 * 60 * 1000
    }
}
