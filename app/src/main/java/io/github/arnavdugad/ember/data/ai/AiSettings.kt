package io.github.arnavdugad.ember.data.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import java.time.YearMonth
import java.time.ZoneOffset
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Where AI replies for demo profiles come from. The user brings their own key. */
enum class AiProvider(
    val key: String,
    val label: String,
    val company: String,
    val defaultModel: String,
    val keyUrl: String,
    val keyHint: String,
) {
    Claude("claude", "Claude", "Anthropic", "claude-opus-5-5", "https://console.anthropic.com/settings/keys", "sk-ant-…"),
    Gemini("gemini", "Gemini", "Google", "gemini-2.5-flash", "https://aistudio.google.com/apikey", "AIza…"),
    OpenAI("openai", "ChatGPT", "OpenAI", "gpt-4o-mini", "https://platform.openai.com/api-keys", "sk-…"),

    /** Any OpenAI-compatible endpoint: OpenRouter, Groq, DeepSeek, Mistral, xAI… */
    Compatible("compatible", "Other", "your provider", "", "", "Your API key"),
    ;

    companion object {
        fun of(key: String?): AiProvider = entries.firstOrNull { it.key == key } ?: Claude
    }
}

/** One-tap base URLs for [AiProvider.Compatible]. */
data class AiEndpoint(val name: String, val baseUrl: String, val keyUrl: String, val model: String)

object AiEndpoints {
    val presets = listOf(
        AiEndpoint("OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/keys", ""),
        AiEndpoint("Groq", "https://api.groq.com/openai/v1", "https://console.groq.com/keys", ""),
        AiEndpoint("DeepSeek", "https://api.deepseek.com/v1", "https://platform.deepseek.com/api_keys", "deepseek-chat"),
        AiEndpoint("Mistral", "https://api.mistral.ai/v1", "https://console.mistral.ai/api-keys", "mistral-small-latest"),
        AiEndpoint("xAI", "https://api.x.ai/v1", "https://console.x.ai", ""),
    )
}

/** How quickly demo profiles answer. */
enum class AiPace(val key: String, val label: String, val blurb: String) {
    Natural("natural", "Like a person", "They read, think, then type"),
    Quick("quick", "Quick", "Replies land in seconds"),
    ;

    companion object {
        fun of(key: String?): AiPace = entries.firstOrNull { it.key == key } ?: Natural
    }
}

/** Everything about AI chat except the keys themselves. */
data class AiConfig(
    val enabled: Boolean = false,
    val provider: AiProvider = AiProvider.Claude,
    val models: Map<AiProvider, String> = emptyMap(),
    val baseUrl: String = "",
    val pace: AiPace = AiPace.Natural,
    /** Demo profiles may send the first message, and check in now and then. */
    val firstMove: Boolean = true,
    /** Demo profiles sometimes answer with a voice note (the phone's text-to-speech). */
    val voiceNotes: Boolean = true,
    /** Chats with demo profiles look like any other chat: no Demo badge there. */
    val immersive: Boolean = true,
    /** Replies per calendar month before AI pauses itself; 0 is no limit. */
    val monthlyCap: Int = 0,
    /** Keys and settings are kept in the user's Ember account, so they follow the login. */
    val cloudSync: Boolean = true,
    /** Providers with a saved key, and the key's last four characters for display. */
    val keyTails: Map<AiProvider, String> = emptyMap(),
) {
    val model: String get() = models[provider]?.ifBlank { null } ?: provider.defaultModel
    val hasKey: Boolean get() = provider in keyTails

    /** A key and model are set, whether or not demo replies are switched on. */
    val usable: Boolean
        get() = hasKey && model.isNotBlank() && (provider != AiProvider.Compatible || baseUrl.isNotBlank())

    /** On, with a key, and (for "Other") an endpoint and a model. */
    val ready: Boolean get() = enabled && usable

    /** Demo chats should look like any other chat. */
    val hidesDemo: Boolean get() = ready && immersive
}

/** This month's AI use on this phone. Cost is an estimate, and only for models with a known price. */
data class AiSpend(
    val month: String = "",
    val replies: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val costMicros: Long = 0,
    val priced: Boolean = true,
)

/** Everything worth keeping in the user's account, keys included (decrypted). */
data class AiSnapshot(
    val provider: AiProvider,
    val models: Map<AiProvider, String>,
    val baseUrl: String,
    val pace: AiPace,
    val enabled: Boolean,
    val firstMove: Boolean,
    val voiceNotes: Boolean,
    val immersive: Boolean,
    val monthlyCap: Int,
    val keys: Map<AiProvider, String>,
    val updatedAt: Long,
) {
    override fun toString() = "AiSnapshot(${provider.key}, keys=${keys.keys.map { it.key }})"
}

/** What a request needs: the provider, its model, the endpoint and the decrypted key. */
class AiCredentials(val provider: AiProvider, val model: String, val baseUrl: String, val apiKey: String) {
    override fun toString() = "AiCredentials(${provider.key}, $model)"
}

private val Context.aiStore: DataStore<Preferences> by preferencesDataStore(name = "ember_ai")

/**
 * AI chat settings. API keys are encrypted with a hardware-backed Android Keystore key that never
 * leaves the device; only the ciphertext is stored, and the app is excluded from backups. With
 * account sync on, [AiCloudSync] also keeps a copy in the user's own Firestore account.
 */
@Singleton
class AiSettingsRepository @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.aiStore

    val config: Flow<AiConfig> = store.data.map { p ->
        AiConfig(
            enabled = p[ENABLED] ?: false,
            provider = AiProvider.of(p[PROVIDER]),
            models = AiProvider.entries.mapNotNull { provider -> p[model(provider)]?.let { provider to it } }.toMap(),
            baseUrl = p[BASE_URL].orEmpty(),
            pace = AiPace.of(p[PACE]),
            firstMove = p[FIRST_MOVE] ?: true,
            voiceNotes = p[VOICE_NOTES] ?: true,
            immersive = p[IMMERSIVE] ?: true,
            monthlyCap = p[MONTHLY_CAP] ?: 0,
            cloudSync = p[CLOUD_SYNC] ?: true,
            keyTails = AiProvider.entries.mapNotNull { provider -> p[tail(provider)]?.let { provider to it } }.toMap(),
        )
    }.distinctUntilChanged()

    suspend fun current(): AiConfig = config.first()

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires after every edit the user makes (not after [restore]), for account sync. */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    /**
     * The key and model to use, or null when there is none or the key can no longer be decrypted.
     * Demo replies need AI chat switched on ([forDemoReplies]); the helpers only need a key.
     */
    suspend fun credentials(forDemoReplies: Boolean = true): AiCredentials? {
        val config = current()
        if (if (forDemoReplies) !config.ready else !config.usable) return null
        val sealed = store.data.first()[secret(config.provider)] ?: return null
        val key = KeyVault.open(sealed) ?: return null
        return AiCredentials(config.provider, config.model, config.baseUrl, key)
    }

    /** The saved key for [provider], decrypted, for testing a connection. */
    suspend fun key(provider: AiProvider): String? = store.data.first()[secret(provider)]?.let(KeyVault::open)

    suspend fun setEnabled(enabled: Boolean) = change { it[ENABLED] = enabled }

    suspend fun setProvider(provider: AiProvider) = change { it[PROVIDER] = provider.key }

    suspend fun setModel(provider: AiProvider, model: String) = change { it[model(provider)] = model.trim() }

    suspend fun setBaseUrl(url: String) = change { it[BASE_URL] = url.trim().trimEnd('/') }

    suspend fun setPace(pace: AiPace) = change { it[PACE] = pace.key }

    suspend fun setFirstMove(enabled: Boolean) = change { it[FIRST_MOVE] = enabled }

    suspend fun setVoiceNotes(enabled: Boolean) = change { it[VOICE_NOTES] = enabled }

    suspend fun setImmersive(enabled: Boolean) = change { it[IMMERSIVE] = enabled }

    suspend fun setMonthlyCap(cap: Int) = change { it[MONTHLY_CAP] = cap.coerceAtLeast(0) }

    /** Not synced itself: it decides whether anything is. */
    suspend fun setCloudSync(enabled: Boolean) = store.edit { it[CLOUD_SYNC] = enabled }

    suspend fun saveKey(provider: AiProvider, apiKey: String) {
        val key = apiKey.trim()
        val sealed = KeyVault.seal(key)
        change {
            it[secret(provider)] = sealed
            it[tail(provider)] = key.takeLast(4)
        }
    }

    suspend fun removeKey(provider: AiProvider) = change {
        it.remove(secret(provider))
        it.remove(tail(provider))
    }

    /** Forgets everything, keys included (account deletion). */
    suspend fun wipe() = store.edit { it.clear() }

    /** Forgets keys and choices but keeps the sync preference and this month's spend (sign-out). */
    suspend fun forgetAccountData() = store.edit { prefs ->
        val keep = SPEND_KEYS + CLOUD_SYNC.name
        prefs.asMap().keys.filter { it.name !in keep }.forEach { prefs.remove(it) }
    }

    suspend fun snapshot(): AiSnapshot {
        val p = store.data.first()
        val config = current()
        return AiSnapshot(
            provider = config.provider,
            models = config.models,
            baseUrl = config.baseUrl,
            pace = config.pace,
            enabled = config.enabled,
            firstMove = config.firstMove,
            voiceNotes = config.voiceNotes,
            immersive = config.immersive,
            monthlyCap = config.monthlyCap,
            keys = AiProvider.entries.mapNotNull { provider -> p[secret(provider)]?.let(KeyVault::open)?.let { provider to it } }.toMap(),
            updatedAt = p[UPDATED_AT] ?: 0L,
        )
    }

    /** Replaces everything with what the account holds. Doesn't count as a user edit. */
    suspend fun restore(snapshot: AiSnapshot) {
        val sealed = snapshot.keys.mapValues { (_, key) -> KeyVault.seal(key) }
        store.edit { p ->
            p[PROVIDER] = snapshot.provider.key
            AiProvider.entries.forEach { provider ->
                val model = snapshot.models[provider]
                if (model == null) p.remove(model(provider)) else p[model(provider)] = model
                val key = snapshot.keys[provider]
                if (key == null) {
                    p.remove(secret(provider))
                    p.remove(tail(provider))
                } else {
                    p[secret(provider)] = sealed.getValue(provider)
                    p[tail(provider)] = key.takeLast(4)
                }
            }
            p[BASE_URL] = snapshot.baseUrl
            p[PACE] = snapshot.pace.key
            p[ENABLED] = snapshot.enabled
            p[FIRST_MOVE] = snapshot.firstMove
            p[VOICE_NOTES] = snapshot.voiceNotes
            p[IMMERSIVE] = snapshot.immersive
            p[MONTHLY_CAP] = snapshot.monthlyCap
            p[UPDATED_AT] = snapshot.updatedAt
        }
    }

    val spend: Flow<AiSpend> = store.data.map { p ->
        val month = currentMonth()
        if (p[SPEND_MONTH] != month) {
            AiSpend(month)
        } else {
            AiSpend(month, p[SPEND_REPLIES] ?: 0, p[SPEND_IN] ?: 0, p[SPEND_OUT] ?: 0, p[SPEND_COST] ?: 0, p[SPEND_PRICED] ?: true)
        }
    }.distinctUntilChanged()

    /** Counts one request against this month. [reply] is false for helper calls (suggestions, bios). */
    suspend fun recordUse(provider: AiProvider, model: String, usage: AiUsage, reply: Boolean) {
        store.edit { p ->
            val month = currentMonth()
            if (p[SPEND_MONTH] != month) {
                p[SPEND_MONTH] = month
                p[SPEND_REPLIES] = 0
                p[SPEND_IN] = 0
                p[SPEND_OUT] = 0
                p[SPEND_COST] = 0
                p[SPEND_PRICED] = true
            }
            if (reply) p[SPEND_REPLIES] = (p[SPEND_REPLIES] ?: 0) + 1
            p[SPEND_IN] = (p[SPEND_IN] ?: 0) + usage.inputTokens
            p[SPEND_OUT] = (p[SPEND_OUT] ?: 0) + usage.outputTokens
            val cost = AiPricing.costMicros(provider, model, usage)
            if (cost == null) p[SPEND_PRICED] = false else p[SPEND_COST] = (p[SPEND_COST] ?: 0) + cost
        }
    }

    /** True once this month's replies reach the user's cap. */
    suspend fun overCap(): Boolean {
        val cap = current().monthlyCap
        return cap > 0 && spend.first().replies >= cap
    }

    private suspend fun change(edit: (MutablePreferences) -> Unit) {
        store.edit {
            edit(it)
            it[UPDATED_AT] = System.currentTimeMillis()
        }
        _changes.tryEmit(Unit)
    }

    private fun currentMonth(): String = YearMonth.now(ZoneOffset.UTC).toString()

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val PROVIDER = stringPreferencesKey("provider")
        val BASE_URL = stringPreferencesKey("base_url")
        val PACE = stringPreferencesKey("pace")
        val FIRST_MOVE = booleanPreferencesKey("first_move")
        val VOICE_NOTES = booleanPreferencesKey("voice_notes")
        val IMMERSIVE = booleanPreferencesKey("immersive")
        val MONTHLY_CAP = intPreferencesKey("monthly_cap")
        val CLOUD_SYNC = booleanPreferencesKey("cloud_sync")
        val UPDATED_AT = longPreferencesKey("updated_at")
        val SPEND_MONTH = stringPreferencesKey("spend_month")
        val SPEND_REPLIES = intPreferencesKey("spend_replies")
        val SPEND_IN = longPreferencesKey("spend_in")
        val SPEND_OUT = longPreferencesKey("spend_out")
        val SPEND_COST = longPreferencesKey("spend_cost_micros")
        val SPEND_PRICED = booleanPreferencesKey("spend_priced")
        val SPEND_KEYS = setOf(SPEND_MONTH, SPEND_REPLIES, SPEND_IN, SPEND_OUT, SPEND_COST, SPEND_PRICED).map { it.name }.toSet()

        fun model(provider: AiProvider) = stringPreferencesKey("model_${provider.key}")
        fun secret(provider: AiProvider) = stringPreferencesKey("key_${provider.key}")
        fun tail(provider: AiProvider) = stringPreferencesKey("tail_${provider.key}")
    }
}

/** AES-256-GCM with a non-exportable Android Keystore key. Output is base64(iv + ciphertext). */
private object KeyVault {
    private const val ALIAS = "ember_ai_keys"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12

    fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    /** Null if the blob is damaged or the Keystore key is gone (e.g. after a device restore). */
    fun open(sealed: String): String? = runCatching {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
