package io.github.arnavdugad.ember.core.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/** A finished recording: an AAC/MPEG-4 file and its length. */
class VoiceClip(val file: File, val durationMs: Int)

/**
 * Records the 30-second voice intro: AAC in an MPEG-4 container, mono, about 32 kbps, so a full
 * clip is ~120 KB, far below the 900 KB a Firestore blob document allows.
 */
class VoiceRecorder @Inject constructor(@ApplicationContext private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    /** Milliseconds since [start], or 0 when idle. */
    val elapsedMs: Long get() = if (recorder != null) SystemClock.elapsedRealtime() - startedAt else 0L

    /** Needs RECORD_AUDIO. Throws if the microphone can't be opened. */
    fun start() {
        cancel()
        val target = File(context.cacheDir, "voice-intro-${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(SAMPLE_RATE)
            r.setAudioEncodingBitRate(BIT_RATE)
            // A safety net only: the caller stops at MAX_MS, before the recorder would cut itself off.
            r.setMaxDuration(MAX_MS + 1_500)
            r.setOutputFile(target.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            target.delete()
            throw IllegalStateException("Couldn't open the microphone.", e)
        }
        recorder = r
        file = target
        startedAt = SystemClock.elapsedRealtime()
    }

    /** Stops and returns the clip, or null if it was too short to keep. */
    fun stop(): VoiceClip? {
        val r = recorder ?: return null
        val target = file
        val elapsed = elapsedMs
        recorder = null
        file = null
        val stopped = runCatching { r.stop() }.isSuccess
        r.release()
        if (target == null || !stopped || elapsed < MIN_MS) {
            target?.delete()
            return null
        }
        val measured = runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(target.absolutePath)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull()
                } finally {
                    release()
                }
            }
        }.getOrNull()
        return VoiceClip(target, (measured ?: elapsed.toInt()).coerceIn(MIN_MS.toInt(), MAX_MS))
    }

    fun cancel() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        r.release()
        file?.delete()
        file = null
    }

    companion object {
        const val MAX_MS = 30_000
        const val MIN_MS = 1_000L
        private const val SAMPLE_RATE = 22_050
        private const val BIT_RATE = 32_000
    }
}
