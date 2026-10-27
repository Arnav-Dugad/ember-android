package io.github.arnavdugad.ember.data.ai

import android.content.Context
import android.media.MediaMetadataRetriever
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.ember.data.model.Gender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Voice notes for demo profiles, spoken by the phone's own text-to-speech: nothing leaves the
 * device. Each person gets the same voice, pitch and pace every time, picked from the installed
 * offline English voices. Returns null whenever the engine can't help, and the text is sent instead.
 */
@Singleton
class AiVoice @Inject constructor(@ApplicationContext private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val lock = Mutex()

    /** Speaks [text] into a WAV under [dir]; returns the file and its length, or null. */
    suspend fun speak(text: String, personaUid: String, gender: Gender?, dir: File): Pair<File, Long>? = lock.withLock {
        val engine = engine() ?: return null
        val out = File(dir.apply { mkdirs() }, "${UUID.randomUUID()}.wav")
        val id = out.name
        val done = CompletableDeferred<Boolean>().also { pending[id] = it }
        withContext(Dispatchers.Main) {
            val r = Random(personaUid.hashCode())
            pickVoice(engine, r)?.let { engine.voice = it }
            engine.setPitch(
                when (gender) {
                    Gender.Woman -> 1.05f + r.nextFloat() * 0.2f
                    Gender.Man -> 0.82f + r.nextFloat() * 0.13f
                    else -> 0.95f + r.nextFloat() * 0.1f
                },
            )
            engine.setSpeechRate(0.95f + r.nextFloat() * 0.12f)
            engine.synthesizeToFile(text, null, out, id)
        }
        val ok = withTimeoutOrNull(SYNTH_TIMEOUT_MS) { done.await() } == true
        pending.remove(id)
        if (!ok || !out.exists() || out.length() < MIN_BYTES) {
            out.delete()
            return null
        }
        val duration = withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(out.path)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
                } finally {
                    retriever.release()
                }
            }.getOrNull()
        } ?: (text.length * 70L)
        out to duration
    }

    private suspend fun engine(): TextToSpeech? {
        val waiting = ready ?: CompletableDeferred<Boolean>().also { deferred ->
            ready = deferred
            withContext(Dispatchers.Main) {
                tts = TextToSpeech(context) { status -> deferred.complete(status == TextToSpeech.SUCCESS) }.apply {
                    setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String) = Unit
                        override fun onDone(utteranceId: String) { pending[utteranceId]?.complete(true) }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String) { pending[utteranceId]?.complete(false) }
                        override fun onError(utteranceId: String, errorCode: Int) { pending[utteranceId]?.complete(false) }
                    })
                }
            }
        }
        return if (withTimeoutOrNull(INIT_TIMEOUT_MS) { waiting.await() } == true) tts else null
    }

    /** An installed, offline English voice, chosen the same way every time for the same person. */
    private fun pickVoice(engine: TextToSpeech, random: Random): Voice? {
        val voices = runCatching { engine.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == Locale.ENGLISH.language && !it.isNetworkConnectionRequired && "notInstalled" !in it.features.orEmpty() }
            .sortedBy { it.name }
        if (voices.isEmpty()) return null
        val local = voices.filter { it.locale.country == Locale.getDefault().country }.ifEmpty { voices }
        return local[random.nextInt(local.size)]
    }

    private companion object {
        const val INIT_TIMEOUT_MS = 5_000L
        const val SYNTH_TIMEOUT_MS = 15_000L
        const val MIN_BYTES = 2_000L
    }
}
