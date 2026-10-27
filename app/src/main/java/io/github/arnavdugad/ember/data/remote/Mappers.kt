package io.github.arnavdugad.ember.data.remote

import com.google.firebase.firestore.FieldValue
import io.github.arnavdugad.ember.core.util.Geo
import io.github.arnavdugad.ember.data.model.Gender
import io.github.arnavdugad.ember.data.model.LikeNote
import io.github.arnavdugad.ember.data.model.LikeNoteKind
import io.github.arnavdugad.ember.data.model.LookingFor
import io.github.arnavdugad.ember.data.model.MatchOnlyDetails
import io.github.arnavdugad.ember.data.model.Photo
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.model.ProfileDraft
import io.github.arnavdugad.ember.data.model.ProfilePrompt

internal fun Map<*, *>.toProfile(uid: String): Profile = Profile(
    uid = uid,
    name = str("name").orEmpty(),
    age = int("age") ?: 0,
    gender = Gender.of(str("gender")),
    interestedIn = strings("interestedIn").mapNotNull(Gender::of).toSet(),
    bio = str("bio").orEmpty(),
    photos = maps("photos").mapNotNull { p ->
        val url = p.str("url") ?: return@mapNotNull null
        Photo(id = p.str("id") ?: url, url = url, path = p.str("path").orEmpty(), blurHash = p.str("blurhash"))
    },
    prompts = maps("prompts").mapNotNull { p ->
        val answer = p.str("answer")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ProfilePrompt(id = p.str("id").orEmpty(), question = p.str("question").orEmpty(), answer = answer)
    },
    interests = strings("interests"),
    vibes = strings("vibes"),
    lookingFor = LookingFor.of(str("lookingFor")),
    heightCm = int("heightCm"),
    city = str("city").orEmpty(),
    distanceKm = int("distanceKm"),
    afterDark = bool("afterDark"),
    privateAlbumCount = int("privateAlbumCount") ?: 0,
    isDemo = bool("isDemo"),
    onboarded = bool("onboarded"),
    lat = dbl("lat"),
    lng = dbl("lng"),
    hideDistance = bool("hideDistance"),
    paused = bool("paused"),
    freeTonightUntil = instant("freeTonightUntil"),
    voiceDurationMs = int("voiceDurationMs")?.takeIf { it > 0 },
    lastActiveAt = instant("lastActiveAt"),
    hideActivity = bool("hideActivity"),
    spotlightUntil = instant("spotlightUntil"),
)

/** A like's `note` map, as the rules shape it: `{kind, ref, text}`. Anything else reads as no note. */
internal fun Map<*, *>.toLikeNote(): LikeNote? {
    val kind = LikeNoteKind.of(str("kind")) ?: return null
    return LikeNote.of(kind, str("ref") ?: return null, str("text").orEmpty())
}

internal fun LikeNote.toFirestore(): Map<String, Any> = mapOf("kind" to kind.key, "ref" to ref, "text" to text)

internal fun Map<*, *>.toMatchOnly() = MatchOnlyDetails(boundaries = strings("boundaries"), turnOns = strings("turnOns"))

/** The client-writable profile fields. Server-owned ones (age, isDemo) are never sent. */
internal fun ProfileDraft.toFirestore(uid: String): Map<String, Any?> {
    val lat = lat?.let(Geo::coarse)
    val lng = lng?.let(Geo::coarse)
    // Rules reject null for these, so absent values are left out rather than written as null.
    val optional = buildMap {
        gender?.let { put("gender", it.key) }
        if (lat != null && lng != null) {
            put("lat", lat)
            put("lng", lng)
            put("geohash", Geo.geohash(lat, lng))
        }
    }
    return optional + mapOf(
        "uid" to uid,
        "name" to name.trim(),
        "interestedIn" to interestedIn.map(Gender::key),
        "bio" to bio.trim(),
        "photos" to photos.map { mapOf("id" to it.id, "url" to it.url, "blurhash" to it.blurHash) },
        "prompts" to prompts.map { mapOf("id" to it.id, "question" to it.question, "answer" to it.answer.trim()) },
        "interests" to interests,
        "vibes" to vibes,
        "lookingFor" to lookingFor.key,
        "heightCm" to heightCm,
        "city" to city.trim(),
        "afterDark" to afterDark,
        // Written with set(merge), so a removed voice intro has to delete the field explicitly.
        "voiceDurationMs" to (voiceDurationMs ?: FieldValue.delete()),
    )
}
