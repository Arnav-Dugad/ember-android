package io.github.arnavdugad.ember.data.preview

import android.content.Context
import android.net.Uri
import io.github.arnavdugad.ember.core.util.Images
import io.github.arnavdugad.ember.data.model.Gender
import io.github.arnavdugad.ember.data.model.LookingFor
import io.github.arnavdugad.ember.data.model.MatchOnlyDetails
import io.github.arnavdugad.ember.data.model.Photo
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.model.ProfilePrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.UUID

@Serializable
internal data class StoredPhoto(val id: String, val url: String, val blurHash: String? = null)

@Serializable
internal data class StoredPrompt(val id: String, val question: String, val answer: String)

/** The Preview user's own profile, so onboarding survives an app restart. */
@Serializable
internal data class StoredProfile(
    val age: Int,
    val onboarded: Boolean = false,
    val name: String = "",
    val gender: String? = null,
    val interestedIn: List<String> = emptyList(),
    val bio: String = "",
    val photos: List<StoredPhoto> = emptyList(),
    val prompts: List<StoredPrompt> = emptyList(),
    val interests: List<String> = emptyList(),
    val vibes: List<String> = emptyList(),
    val lookingFor: String = LookingFor.Dating.key,
    val heightCm: Int? = null,
    val city: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val afterDark: Boolean = false,
    val voiceDurationMs: Int? = null,
    val freeTonightUntil: Long? = null,
    val boundaries: List<String> = emptyList(),
    val turnOns: List<String> = emptyList(),
    val album: List<StoredPhoto> = emptyList(),
    val hideDistance: Boolean = false,
    val paused: Boolean = false,
    val hideActivity: Boolean = false,
    val spotlightUntil: Long? = null,
    /** The UTC day (yyyy-MM-dd) of the last Spotlight: one a day, as with Firebase. */
    val spotlightDay: String? = null,
)

/**
 * App-private storage for Preview mode. Picked photos are copied in (re-encoded, so location
 * metadata is stripped) because a photo-picker grant does not outlive the process.
 */
internal class PreviewStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = File(context.filesDir, "preview/profile.json")
    private val photos get() = File(context.filesDir, "preview/photos").apply { mkdirs() }

    /** The Preview user's voice intro. */
    val voice: File get() = File(context.filesDir, "preview/voice.m4a")

    suspend fun load(): StoredProfile? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<StoredProfile>(file.readText()) }.getOrNull()
    }

    suspend fun save(profile: StoredProfile) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "profile.json.tmp")
        tmp.writeText(json.encodeToString(StoredProfile.serializer(), profile))
        tmp.renameTo(file)
    }

    suspend fun copyIn(uri: Uri): StoredPhoto {
        val prepared = Images.prepare(context, uri)
        val id = UUID.randomUUID().toString()
        val target = File(photos, "$id.jpg")
        withContext(Dispatchers.IO) { target.writeBytes(prepared.bytes) }
        return StoredPhoto(id, Uri.fromFile(target).toString(), prepared.blurHash)
    }

    suspend fun delete(url: String) = withContext(Dispatchers.IO) {
        val path = Uri.parse(url).path ?: return@withContext
        val f = File(path)
        if (f.parentFile?.canonicalPath == photos.canonicalPath) f.delete()
    }

    suspend fun wipe() = withContext(Dispatchers.IO) {
        File(context.filesDir, "preview").deleteRecursively()
    }
}

internal fun StoredPhoto.toPhoto() = Photo(id = id, url = url, blurHash = blurHash)
internal fun Photo.toStored() = StoredPhoto(id, url, blurHash)

internal fun StoredProfile.toProfile(uid: String, albumCount: Int) = Profile(
    uid = uid,
    name = name,
    age = age,
    gender = Gender.of(gender),
    interestedIn = interestedIn.mapNotNull(Gender::of).toSet(),
    bio = bio,
    photos = photos.map { it.toPhoto() },
    prompts = prompts.map { ProfilePrompt(it.id, it.question, it.answer) },
    interests = interests,
    vibes = vibes,
    lookingFor = LookingFor.of(lookingFor),
    heightCm = heightCm,
    city = city,
    afterDark = afterDark,
    privateAlbumCount = albumCount,
    onboarded = onboarded,
    lat = lat,
    lng = lng,
    hideDistance = hideDistance,
    paused = paused,
    freeTonightUntil = freeTonightUntil?.let(Instant::ofEpochMilli),
    voiceDurationMs = voiceDurationMs,
    hideActivity = hideActivity,
    spotlightUntil = spotlightUntil?.let(Instant::ofEpochMilli),
)

internal fun StoredProfile.matchOnly() = MatchOnlyDetails(boundaries, turnOns)
