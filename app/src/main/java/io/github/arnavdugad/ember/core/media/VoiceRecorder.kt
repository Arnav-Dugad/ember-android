package io.github.arnavdugad.ember.core.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import io.github.arnavdugad.ember.data.model.VoiceClip
import java.io.File
import java.util.UUID
import kotlin.math.log10

/** Where voice notes live on the device. */
object VoiceFiles {
    private val safeId = Regex("[A-Za-z0-9_-]{1,128}")

    /** Downloaded (and just-sent) voice notes, by audio id. The system may clear these. */
    fun cached(context: Context, audioId: String): File {
        require(safeId.matches(audioId)) { "Not a voice note." }
        return File(context.cacheDir, "voice/$audioId.m4a")
    }

    /** A fresh file for a new recording. */
    fun newRecording(context: Context): File =
        File(context.cacheDir, "voice-rec/${UUID.randomUUID()}.m4a").apply { parentFile?.mkdirs() }
}

/**
 * Hold-to-record voice notes: AAC in MPEG-4, mono, 24 kHz at about 32 kbps. That is roughly
 * 4 KB a second, so a full minute stays far below the 900 KB a Firestore document may hold.
 * Not thread-safe: drive it from the main thread.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    /** False when the microphone couldn't be opened (busy, or permission missing). */
    fun start(): Boolean {
        cancel()
        val target = VoiceFiles.newRecording(context)
        val next = newRecorder()
        return try {
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioChannels(1)
            next.setAudioSamplingRate(SAMPLE_RATE)
            next.setAudioEncodingBitRate(BIT_RATE)
            // A safety net only: the UI finishes the note at MAX_DURATION_MS itself, because a
            // recorder that hit its own limit can no longer be stopped cleanly.
            next.setMaxDuration((MAX_DURATION_MS + 2_000).toInt())
            next.setOutputFile(target.path)
            next.prepare()
            next.start()
            recorder = next
            file = target
            startedAt = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            // IOException from prepare(), IllegalStateException or RuntimeException when the
            // microphone is unavailable.
            next.release()
            target.delete()
            false
        }
    }

    fun elapsedMs(): Long = if (recorder == null) 0L else SystemClock.elapsedRealtime() - startedAt

    /** Input level from 0 to 1, on a decibel scale so quiet speech still moves the meter. */
    fun level(): Float {
        val amplitude = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        if (amplitude <= 0) return 0f
        val db = 20 * log10(amplitude / 32_767f)
        return ((db + FLOOR_DB) / FLOOR_DB).coerceIn(0f, 1f)
    }

    /** Stops and returns the clip, or null when it was too short or the recording failed. */
    fun stop(): VoiceClip? {
        val current = recorder ?: return null
        val target = file
        val duration = elapsedMs().coerceAtMost(MAX_DURATION_MS)
        recorder = null
        file = null
        // stop() throws when no audio was captured at all.
        val finished = runCatching { current.stop() }.isSuccess
        current.release()
        if (!finished || target == null || duration < MIN_DURATION_MS || target.length() == 0L) {
            target?.delete()
            return null
        }
        return VoiceClip(target, duration)
    }

    fun cancel() {
        val current = recorder ?: return
        val target = file
        recorder = null
        file = null
        runCatching { current.stop() }
        current.release()
        target?.delete()
    }

    private fun newRecorder(): MediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(context)
    } else {
        @Suppress("DEPRECATION")
        MediaRecorder()
    }

    companion object {
        const val MAX_DURATION_MS = 60_000L

        /** Shorter than this is a mis-tap, not a message. */
        const val MIN_DURATION_MS = 700L
        private const val SAMPLE_RATE = 24_000
        private const val BIT_RATE = 32_000
        private const val FLOOR_DB = 50f
    }
}
