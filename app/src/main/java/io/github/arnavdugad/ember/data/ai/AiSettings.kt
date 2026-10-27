package io.github.arnavdugad.ember.data.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
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
    /** Demo profiles may send the first message. */
    val firstMove: Boolean = true,
    /** Providers with a saved key, and the key's last four characters for display. */
    val keyTails: Map<AiProvider, String> = emptyMap(),
) {
    val model: String get() = models[provider]?.ifBlank { null } ?: provider.defaultModel
    val hasKey: Boolean get() = provider in keyTails

    /** On, with a key, and (for "Other") an endpoint and a model. */
    val ready: Boolean
        get() = enabled && hasKey && model.isNotBlank() && (provider != AiProvider.Compatible || baseUrl.isNotBlank())
}

/** What a request needs: the provider, its model, the endpoint and the decrypted key. */
class AiCredentials(val provider: AiProvider, val model: String, val baseUrl: String, val apiKey: String) {
    override fun toString() = "AiCredentials(${provider.key}, $model)"
}

private val Context.aiStore: DataStore<Preferences> by preferencesDataStore(name = "ember_ai")

/**
 * AI chat settings. API keys are encrypted with a hardware-backed Android Keystore key that never
 * leaves the device; only the ciphertext is stored, and the app is excluded from backups.
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
            keyTails = AiProvider.entries.mapNotNull { provider -> p[tail(provider)]?.let { provider to it } }.toMap(),
        )
    }.distinctUntilChanged()

    suspend fun current(): AiConfig = config.first()

    /** Null unless AI chat is on and fully set up, or if the key can no longer be decrypted. */
    suspend fun credentials(): AiCredentials? {
        val config = current()
        if (!config.ready) return null
        val sealed = store.data.first()[secret(config.provider)] ?: return null
        val key = KeyVault.open(sealed) ?: return null
        return AiCredentials(config.provider, config.model, config.baseUrl, key)
    }

    /** The saved key for [provider], decrypted, for testing a connection. */
    suspend fun key(provider: AiProvider): String? = store.data.first()[secret(provider)]?.let(KeyVault::open)

    suspend fun setEnabled(enabled: Boolean) = store.edit { it[ENABLED] = enabled }

    suspend fun setProvider(provider: AiProvider) = store.edit { it[PROVIDER] = provider.key }

    suspend fun setModel(provider: AiProvider, model: String) = store.edit { it[model(provider)] = model.trim() }

    suspend fun setBaseUrl(url: String) = store.edit { it[BASE_URL] = url.trim().trimEnd('/') }

    suspend fun setPace(pace: AiPace) = store.edit { it[PACE] = pace.key }

    suspend fun setFirstMove(enabled: Boolean) = store.edit { it[FIRST_MOVE] = enabled }

    suspend fun saveKey(provider: AiProvider, apiKey: String) {
        val key = apiKey.trim()
        val sealed = KeyVault.seal(key)
        store.edit {
            it[secret(provider)] = sealed
            it[tail(provider)] = key.takeLast(4)
        }
    }

    suspend fun removeKey(provider: AiProvider) = store.edit {
        it.remove(secret(provider))
        it.remove(tail(provider))
    }

    /** Forgets everything, keys included (account deletion). */
    suspend fun wipe() = store.edit { it.clear() }

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val PROVIDER = stringPreferencesKey("provider")
        val BASE_URL = stringPreferencesKey("base_url")
        val PACE = stringPreferencesKey("pace")
        val FIRST_MOVE = booleanPreferencesKey("first_move")

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
