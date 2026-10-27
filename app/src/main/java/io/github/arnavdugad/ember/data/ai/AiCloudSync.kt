package io.github.arnavdugad.ember.data.ai

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import io.github.arnavdugad.ember.data.remote.uidFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Keeps AI chat settings and keys in the user's own account (`users/{uid}/ai/config`, readable and
 * writable only by them under the security rules), so they're back after signing in on any phone.
 *
 * On sign-in the newer of phone and account wins. Every change on the phone is pushed shortly
 * after. On sign-out the phone forgets the keys, so the next person to sign in doesn't inherit
 * them. Switching sync off deletes the account copy and keeps the phone's.
 */
@Singleton
class AiCloudSync @Inject constructor(
    private val settings: AiSettingsRepository,
    private val auth: Provider<FirebaseAuth>,
    private val db: Provider<FirebaseFirestore>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var uid: String? = null

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            auth.get().uidFlow().distinctUntilChanged().collect { next ->
                val previous = uid
                uid = next
                when {
                    next != null -> runCatching { pull(next) }
                    previous != null && settings.current().cloudSync -> settings.forgetAccountData()
                }
            }
        }
        scope.launch {
            settings.changes.debounce(PUSH_DEBOUNCE_MS).collect { runCatching { push() } }
        }
    }

    /** Turns account sync on or off. Off deletes the account copy; the phone keeps its own. */
    suspend fun setEnabled(enabled: Boolean) {
        settings.setCloudSync(enabled)
        val user = uid ?: return
        if (enabled) push() else runCatching { withTimeoutOrNull(TIMEOUT_MS) { doc(user).delete().await() } }
    }

    /** Removes the account copy (account deletion). */
    suspend fun deleteFromAccount() {
        val user = uid ?: return
        runCatching { withTimeoutOrNull(TIMEOUT_MS) { doc(user).delete().await() } }
    }

    private suspend fun pull(user: String) {
        if (!settings.current().cloudSync) return
        val snap = withTimeoutOrNull(TIMEOUT_MS) { doc(user).get().await() } ?: return
        val remote = snap.takeIf { it.exists() }?.let(::decode)
        val local = settings.snapshot()
        when {
            remote != null && (remote.updatedAt >= local.updatedAt || local.keys.isEmpty()) -> settings.restore(remote)
            local.keys.isNotEmpty() -> push()
        }
    }

    private suspend fun push() {
        val user = uid ?: return
        if (!settings.current().cloudSync) return
        val s = settings.snapshot()
        val data = mapOf(
            "provider" to s.provider.key,
            "models" to s.models.mapKeys { it.key.key },
            "baseUrl" to s.baseUrl,
            "pace" to s.pace.key,
            "enabled" to s.enabled,
            "firstMove" to s.firstMove,
            "voiceNotes" to s.voiceNotes,
            "immersive" to s.immersive,
            "monthlyCap" to s.monthlyCap,
            "keys" to s.keys.mapKeys { it.key.key },
            "changedAt" to s.updatedAt,
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        // Writes queue offline and complete later; don't hold the caller hostage to that.
        withTimeoutOrNull(TIMEOUT_MS) { doc(user).set(data).await() }
    }

    private fun decode(snap: DocumentSnapshot): AiSnapshot {
        @Suppress("UNCHECKED_CAST")
        fun providerMap(field: String) = (snap.get(field) as? Map<String, Any?>).orEmpty()
            .mapNotNull { (key, value) -> AiProvider.entries.firstOrNull { it.key == key }?.let { p -> (value as? String)?.let { p to it } } }
            .toMap()
        return AiSnapshot(
            provider = AiProvider.of(snap.getString("provider")),
            models = providerMap("models"),
            baseUrl = snap.getString("baseUrl").orEmpty(),
            pace = AiPace.of(snap.getString("pace")),
            enabled = snap.getBoolean("enabled") ?: false,
            firstMove = snap.getBoolean("firstMove") ?: true,
            voiceNotes = snap.getBoolean("voiceNotes") ?: true,
            immersive = snap.getBoolean("immersive") ?: true,
            monthlyCap = snap.getLong("monthlyCap")?.toInt() ?: 0,
            keys = providerMap("keys"),
            updatedAt = snap.getLong("changedAt") ?: 0L,
        )
    }

    private fun doc(user: String) = db.get().collection("users").document(user).collection("ai").document("config")

    private companion object {
        const val PUSH_DEBOUNCE_MS = 1_500L
        const val TIMEOUT_MS = 10_000L
    }
}
