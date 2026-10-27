package io.github.arnavdugad.ember.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import io.github.arnavdugad.ember.data.model.Ages
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The signed-in user's birth date (from `users/{uid}/private/account`), cached per uid.
 *
 * Security rules recompute age from that document and reject any profile write whose `age`
 * disagrees, so every profile write goes through [profileFields] to carry a fresh age.
 */
@Singleton
class Account @Inject constructor(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
) {
    private var cachedUid: String? = null
    private var cachedBirth: LocalDate? = null

    fun accountRef(uid: String) = db.collection("users").document(uid).collection("private").document("account")

    suspend fun birthDate(): LocalDate? {
        val uid = auth.currentUser?.uid ?: return null
        if (uid == cachedUid && cachedBirth != null) return cachedBirth
        val data = accountRef(uid).get().await().data ?: return null
        val birth = runCatching { LocalDate.of(data.int("birthYear")!!, data.int("birthMonth")!!, data.int("birthDay")!!) }.getOrNull()
        cachedUid = uid
        cachedBirth = birth
        return birth
    }

    fun remember(uid: String, birth: LocalDate) {
        cachedUid = uid
        cachedBirth = birth
    }

    fun forget() {
        cachedUid = null
        cachedBirth = null
    }

    /** [fields] plus the current `age` and `updatedAt`, as the profile rules require. */
    suspend fun profileFields(fields: Map<String, Any?>): Map<String, Any?> {
        val birth = birthDate() ?: error("Please confirm your date of birth first.")
        return fields + mapOf("age" to Ages.ageOn(birth, LocalDate.now()), "updatedAt" to FieldValue.serverTimestamp())
    }
}
