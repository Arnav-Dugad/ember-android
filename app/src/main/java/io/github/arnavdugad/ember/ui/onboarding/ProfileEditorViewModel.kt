package io.github.arnavdugad.ember.ui.onboarding

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.ember.core.audio.VoiceRecorder
import io.github.arnavdugad.ember.data.location.CitySuggestion
import io.github.arnavdugad.ember.data.location.LocationRepository
import io.github.arnavdugad.ember.data.model.Gender
import io.github.arnavdugad.ember.data.model.LookingFor
import io.github.arnavdugad.ember.data.model.Photo
import io.github.arnavdugad.ember.data.model.Place
import io.github.arnavdugad.ember.data.model.ProfileDraft
import io.github.arnavdugad.ember.data.model.ProfilePrompt
import io.github.arnavdugad.ember.data.model.PromptTemplate
import io.github.arnavdugad.ember.data.model.toDraft
import io.github.arnavdugad.ember.data.repo.AuthRepository
import io.github.arnavdugad.ember.data.repo.ProfileRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import io.github.arnavdugad.ember.data.ai.AiAssist

data class EditorState(
    val draft: ProfileDraft = ProfileDraft(),
    val loaded: Boolean = false,
    val uploading: Int = 0,
    val locating: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
    /** The signed-in uid once the existing profile has loaded (for playing the saved voice intro). */
    val uid: String? = null,
    /** Milliseconds recorded so far, or null when not recording. */
    val recordingMs: Long? = null,
    val voiceUploading: Boolean = false,
    /** This session's fresh recording, played locally. */
    val recordedVoice: File? = null,
    /** The saved voice intro was removed; its document is deleted on save. */
    val voiceRemoved: Boolean = false,
    /** The user has an AI key, so the writing help buttons show. */
    val aiWriting: Boolean = false,
    /** The open AI writing sheet, if any. */
    val writer: WriterState? = null,
) {
    val canAddPhotos: Boolean get() = draft.photos.size + uploading < MAX_PHOTOS

    companion object {
        const val MAX_PHOTOS = 6
        const val MAX_PROMPTS = 3
        const val MAX_VIBES = 6
        const val MAX_INTERESTS = 10
    }
}

/** Backs both the onboarding wizard and the profile editor. */
@HiltViewModel
class ProfileEditorViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val auth: AuthRepository,
    private val location: LocationRepository,
    private val recorder: VoiceRecorder,
    private val assist: AiAssist,
) : ViewModel() {
    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val available = runCatching { assist.available() }.getOrDefault(false)
            _state.update { it.copy(aiWriting = available) }
        }
        viewModelScope.launch {
            val existing = withTimeoutOrNull(4_000) { profiles.observeMyProfile().filterNotNull().first() }
            val matchOnly = withTimeoutOrNull(2_000) { profiles.observeMyMatchOnly().firstOrNull() }
            _state.update { s ->
                val draft = existing?.takeIf { it.name.isNotBlank() }?.toDraft(matchOnly) ?: s.draft
                s.copy(draft = draft, loaded = true, uid = existing?.uid)
            }
        }
    }

    private fun edit(block: ProfileDraft.() -> ProfileDraft) = _state.update { it.copy(draft = it.draft.block(), error = null, saved = false) }

    fun setName(value: String) = edit { copy(name = value) }
    fun setGender(value: Gender) = edit { copy(gender = value) }
    fun toggleInterestedIn(value: Gender) = edit { copy(interestedIn = interestedIn.toggle(value)) }
    fun setBio(value: String) = edit { copy(bio = value) }

    /** Asks the user's AI for bio drafts, or answers to prompt [promptId]. */
    fun writeWithAi(promptId: String? = null) {
        val draft = _state.value.draft
        val prompt = promptId?.let { id -> draft.prompts.firstOrNull { it.id == id } }
        _state.update { it.copy(writer = WriterState(promptId = prompt?.id, title = prompt?.question ?: "Bio ideas")) }
        viewModelScope.launch {
            val result = runCatching { if (prompt == null) assist.bios(draft) else assist.promptAnswers(prompt.question, draft) }
            _state.update { s ->
                val open = s.writer ?: return@update s
                s.copy(
                    writer = result.fold(
                        onSuccess = { ideas -> open.copy(loading = false, ideas = ideas, error = if (ideas.isEmpty()) "No ideas came back. Try again." else null) },
                        onFailure = { e -> open.copy(loading = false, error = e.message ?: "Couldn't get ideas. Try again.") },
                    ),
                )
            }
        }
    }

    /** Uses an idea as the bio or the prompt's answer; the user can still edit it. */
    fun useIdea(text: String) {
        val writer = _state.value.writer ?: return
        if (writer.promptId == null) setBio(text.take(500)) else answerPrompt(writer.promptId, text.take(160))
        closeWriter()
    }

    fun closeWriter() = _state.update { it.copy(writer = null) }
    fun setHeight(value: Int?) = edit { copy(heightCm = value) }
    fun setLookingFor(value: LookingFor) = edit { copy(lookingFor = value) }
    fun setAfterDark(value: Boolean) = edit { copy(afterDark = value) }

    /** From the city picker: the name plus city-level coordinates. */
    fun setPlace(place: Place) = edit { copy(city = place.name, lat = place.lat, lng = place.lng) }

    suspend fun searchCities(query: String): List<CitySuggestion> = location.searchCities(query)

    fun toggleVibe(key: String) = edit { copy(vibes = vibes.toggleCapped(key, EditorState.MAX_VIBES)) }
    fun toggleInterest(value: String) = edit { copy(interests = interests.toggleCapped(value, EditorState.MAX_INTERESTS)) }
    fun toggleBoundary(key: String) = edit { copy(matchOnly = matchOnly.copy(boundaries = matchOnly.boundaries.toggleCapped(key, 8))) }
    fun toggleTurnOn(key: String) = edit { copy(matchOnly = matchOnly.copy(turnOns = matchOnly.turnOns.toggleCapped(key, 8))) }

    fun addPrompt(template: PromptTemplate) = edit {
        if (prompts.size >= EditorState.MAX_PROMPTS || prompts.any { it.id == template.id }) this
        else copy(prompts = prompts + ProfilePrompt(template.id, template.question, ""))
    }

    fun answerPrompt(id: String, answer: String) = edit {
        copy(prompts = prompts.map { if (it.id == id) it.copy(answer = answer.take(160)) else it })
    }

    fun removePrompt(id: String) = edit { copy(prompts = prompts.filterNot { it.id == id }) }

    fun addPhotos(uris: List<Uri>) {
        val room = EditorState.MAX_PHOTOS - _state.value.draft.photos.size - _state.value.uploading
        uris.take(room.coerceAtLeast(0)).forEach { uri ->
            viewModelScope.launch {
                _state.update { it.copy(uploading = it.uploading + 1) }
                val result = runCatching { profiles.uploadPhoto(uri) }
                _state.update { s ->
                    val photo = result.getOrNull()
                    s.copy(
                        uploading = s.uploading - 1,
                        draft = if (photo != null) s.draft.copy(photos = s.draft.photos + photo) else s.draft,
                        error = result.exceptionOrNull()?.let { "Couldn't upload that photo: ${it.message}" },
                    )
                }
            }
        }
    }

    fun removePhoto(photo: Photo) {
        edit { copy(photos = photos.filterNot { it.id == photo.id }) }
        viewModelScope.launch { runCatching { profiles.deletePhoto(photo) } }
    }

    private var recordingJob: Job? = null

    /** Starts the voice intro recording (RECORD_AUDIO already granted); it stops itself at 30 s. */
    fun startRecording() {
        if (recorder.isRecording || _state.value.voiceUploading) return
        val started = runCatching { recorder.start() }
        if (started.isFailure) {
            _state.update { it.copy(error = started.exceptionOrNull()?.message ?: "Couldn't start recording.") }
            return
        }
        _state.update { it.copy(recordingMs = 0L, error = null) }
        recordingJob = viewModelScope.launch {
            while (recorder.isRecording) {
                val elapsed = recorder.elapsedMs
                _state.update { it.copy(recordingMs = elapsed) }
                if (elapsed >= VoiceRecorder.MAX_MS) {
                    finishRecording()
                    break
                }
                delay(50)
            }
        }
    }

    fun stopRecording() {
        recordingJob?.cancel()
        recordingJob = null
        finishRecording()
    }

    /** Stops, then uploads the clip; the profile's duration is written on save. */
    private fun finishRecording() {
        val clip = recorder.stop()
        if (clip == null) {
            _state.update { it.copy(recordingMs = null, error = "That was a bit short. Hold on for at least a second.") }
            return
        }
        _state.update { it.copy(recordingMs = null, voiceUploading = true) }
        viewModelScope.launch {
            val result = runCatching { profiles.uploadVoice(clip.file, clip.durationMs) }
            _state.update { s ->
                if (result.isSuccess) {
                    s.copy(
                        voiceUploading = false,
                        recordedVoice = clip.file,
                        voiceRemoved = false,
                        draft = s.draft.copy(voiceDurationMs = clip.durationMs),
                        saved = false,
                    )
                } else {
                    clip.file.delete()
                    s.copy(voiceUploading = false, error = "Couldn't save your voice intro: ${result.exceptionOrNull()?.message}")
                }
            }
        }
    }

    fun removeVoice() = _state.update {
        it.copy(draft = it.draft.copy(voiceDurationMs = null), recordedVoice = null, voiceRemoved = true, error = null, saved = false)
    }

    override fun onCleared() {
        recorder.cancel()
    }

    fun makeMainPhoto(photo: Photo) = edit { copy(photos = listOf(photo) + photos.filterNot { it.id == photo.id }) }

    fun locate() {
        viewModelScope.launch {
            _state.update { it.copy(locating = true) }
            val city = runCatching { location.currentCity() }.getOrNull()
            _state.update { s ->
                if (city == null) {
                    s.copy(locating = false, error = "Couldn't find your city. You can pick it from the list instead.")
                } else {
                    s.copy(locating = false, draft = s.draft.copy(city = city.city, lat = city.lat, lng = city.lng))
                }
            }
        }
    }

    fun save(finishOnboarding: Boolean) {
        val draft = _state.value.draft
        val problem = validate()
        if (problem != null) {
            _state.update { it.copy(error = problem) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            val result = runCatching {
                var cleaned = draft.copy(prompts = draft.prompts.filter { it.answer.isNotBlank() })
                // A typed city gets coordinates too, so distances and nearby-first ordering work.
                if (cleaned.lat == null && cleaned.city.isNotBlank()) {
                    location.locate(cleaned.city)?.let { cleaned = cleaned.copy(lat = it.lat, lng = it.lng) }
                }
                profiles.saveProfile(cleaned, finishOnboarding)
                if (_state.value.voiceRemoved && cleaned.voiceDurationMs == null) runCatching { profiles.deleteVoice() }
            }
            _state.update {
                it.copy(saving = false, saved = result.isSuccess, error = result.exceptionOrNull()?.let { e -> e.message ?: "Couldn't save. Try again." })
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { auth.signOut() }
    }

    /** Step-level validation for the wizard; null when the step is complete. */
    fun stepProblem(step: SetupStep): String? {
        val d = _state.value.draft
        return when (step) {
            SetupStep.Basics -> when {
                d.name.isBlank() -> "What should we call you?"
                d.gender == null -> "Pick the option that fits you best."
                d.interestedIn.isEmpty() -> "Who would you like to meet?"
                else -> null
            }
            SetupStep.Photos -> if (d.photos.isEmpty()) "Add at least one photo." else if (_state.value.uploading > 0) "Hang on, still uploading." else null
            SetupStep.Location -> if (d.city.isBlank()) "Add your city so we can show people nearby." else null
            else -> null
        }
    }

    /** Unanswered prompts are dropped on save rather than blocking it. */
    private fun validate(): String? = SetupStep.entries.firstNotNullOfOrNull { stepProblem(it) }

    private fun <T> Set<T>.toggle(value: T) = if (value in this) this - value else this + value
    private fun List<String>.toggleCapped(value: String, cap: Int) = when {
        value in this -> this - value
        size >= cap -> this
        else -> this + value
    }
}

enum class SetupStep(val title: String, val subtitle: String) {
    Basics("The basics", "First impressions start here."),
    Photos("Show your best side", "Up to six photos. Keep them flirty, not explicit."),
    About("About you", "A little mystery is good. A little info is better."),
    Prompts("Break the ice", "Pick up to three cheeky prompts. Your answers spark the best openers."),
    Vibes("Your vibe", "What are you into? What's your energy?"),
    Intent("What you're after", "Be upfront. It saves everyone time."),
    Location("Where are you?", "We only ever show your city, never your exact location."),
}

/** AI writing help: bio drafts ([promptId] null) or answers to one prompt. */
data class WriterState(
    val promptId: String?,
    val title: String,
    val loading: Boolean = true,
    val ideas: List<String> = emptyList(),
    val error: String? = null,
)
