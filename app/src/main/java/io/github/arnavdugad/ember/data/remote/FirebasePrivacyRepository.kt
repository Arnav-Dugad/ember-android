package io.github.arnavdugad.ember.data.remote

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import io.github.arnavdugad.ember.data.model.Presence
import io.github.arnavdugad.ember.data.repo.PrivacyRepository
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebasePrivacyRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
    private val account: Account,
) : PrivacyRepository {
    private val users get() = db.collection("users")

    override suspend fun setHideDistance(hide: Boolean) = setFlag("hideDistance", hide)

    override suspend fun setPaused(paused: Boolean) = setFlag("paused", paused)

    /**
     * Hiding also rewrites `lastActiveAt` to today's UTC midnight in the same write, so the exact
     * time of the last heartbeat doesn't linger (the rules require a midnight while hidden).
     */
    override suspend fun setHideActivity(hide: Boolean) {
        val uid = auth.requireUid()
        val stamp: Any = if (hide) Timestamp(Presence.dayStart(Instant.now())) else FieldValue.serverTimestamp()
        users.document(uid).update(account.profileFields(mapOf("hideActivity" to hide, "lastActiveAt" to stamp))).await()
    }

    private suspend fun setFlag(field: String, value: Boolean) {
        val uid = auth.requireUid()
        users.document(uid).update(account.profileFields(mapOf(field to value))).await()
    }

    /**
     * Reads everything the rules let the user read about themselves. Image bytes are replaced by
     * their size: the photos are already on their profile and in their album, and inlining them
     * would make the file many megabytes.
     */
    override suspend fun exportMyData(): String {
        val user = auth.currentUser ?: error("You're signed out.")
        val uid = user.uid
        val me = users.document(uid)
        val matches = db.collection("matches").whereArrayContains("users", uid).get().await().documents.map { match ->
            match to match.reference.collection("messages").whereEqualTo("senderId", uid).get().await().documents
        }
        val account = me.collection("private").document("account").get().await().data.toJson()
        val profile = me.get().await().data.toJson()
        val matchOnly = me.collection("matchOnly").document("details").get().await().data.toJson()
        val swipes = me.collection("swipes").dump()
        val blocks = me.collection("blocks").dump()
        val limits = me.collection("limits").dump()
        val albumPhotos = db.collection("albums").document(uid).collection("photos").dump()
        val albumAccess = db.collection("albums").document(uid).collection("access").dump()
        val root = buildJsonObject {
            put("format", "Ember data export, version 1")
            put("exportedAt", Instant.now().toString())
            putJsonObject("login") {
                put("uid", uid)
                put("email", user.email)
                putJsonArray("signInMethods") {
                    user.providerData.map { it.providerId }.filter { it != "firebase" }.distinct().forEach { add(JsonPrimitive(it)) }
                }
            }
            put("account", account)
            put("profile", profile)
            put("matchOnlyDetails", matchOnly)
            put("swipes", swipes)
            put("blocks", blocks)
            put("reportCounters", limits)
            put("privateAlbumPhotos", albumPhotos)
            put("privateAlbumAccess", albumAccess)
            putJsonArray("matches") {
                matches.forEach { (match, sent) ->
                    add(
                        buildJsonObject {
                            put("id", match.id)
                            put("match", match.data.toJson())
                            putJsonArray("messagesISent") {
                                sent.sortedBy { it.getTimestamp("createdAt") }.forEach { m ->
                                    add(buildJsonObject { put("id", m.id); put("message", m.data.toJson()) })
                                }
                            }
                        },
                    )
                }
            }
        }
        return PrettyJson.encodeToString(JsonObject.serializer(), root)
    }

    private suspend fun CollectionReference.dump(): JsonObject =
        JsonObject(get().await().documents.associate { it.id to it.data.toJson() })

    private companion object {
        val PrettyJson = Json { prettyPrint = true }
    }
}

/** Firestore values as JSON: timestamps become ISO-8601 strings, image bytes become their size. */
internal fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Timestamp -> JsonPrimitive(toDate().toInstant().toString())
    is Blob -> JsonPrimitive("(${toBytes().size} bytes of image data, not included)")
    is GeoPoint -> buildJsonObject {
        put("lat", latitude)
        put("lng", longitude)
    }
    is DocumentReference -> JsonPrimitive(path)
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJson() })
    is List<*> -> JsonArray(map { it.toJson() })
    else -> JsonPrimitive(toString())
}
