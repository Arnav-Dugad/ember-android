package io.github.arnavdugad.ember.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resumeWithException

/** One turn of the conversation, from the persona's point of view: the user speaks, the persona answers. */
data class AiTurn(val fromUser: Boolean, val text: String)

/** A failed AI request, with a message fit to show as-is. Never contains the API key. */
class AiException(message: String, val kind: Kind) : Exception(message) {
    enum class Kind { BadKey, NoCredit, RateLimited, Model, Declined, Busy, Network, Setup, Other }
}

/**
 * Talks to the user's chosen AI provider directly from the phone with their own key: Anthropic's
 * Messages API, Google's Gemini API, OpenAI's Chat Completions, or any OpenAI-compatible endpoint.
 * Raw HTTP on the app's OkHttp rather than four vendor SDKs, so the APK stays small.
 */
@Singleton
class AiClient @Inject constructor(http: OkHttpClient, private val json: Json) {
    private val http = http.newBuilder().readTimeout(90, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()

    /** The persona's reply to [turns] (which must end with the user's turn). */
    suspend fun reply(credentials: AiCredentials, system: String, turns: List<AiTurn>): String = withContext(Dispatchers.IO) {
        val text = when (credentials.provider) {
            AiProvider.Claude -> claude(credentials, system, turns)
            AiProvider.Gemini -> gemini(credentials, system, turns)
            AiProvider.OpenAI, AiProvider.Compatible -> openAi(credentials, system, turns)
        }
        text.trim().ifEmpty { throw AiException("No reply came back. Try again.", AiException.Kind.Other) }
    }

    /** Model ids the key can use: also the cheapest way to check that a key works. */
    suspend fun models(provider: AiProvider, apiKey: String, baseUrl: String): List<String> = withContext(Dispatchers.IO) {
        val creds = AiCredentials(provider, "", baseUrl, apiKey)
        when (provider) {
            AiProvider.Claude -> {
                val body = execute(creds, claudeRequest(apiKey, "$CLAUDE/v1/models?limit=100").get().build())
                body.array("data").mapNotNull { it.string("id") }
            }
            AiProvider.Gemini -> {
                val body = execute(creds, Request.Builder().url("$GEMINI/v1beta/models?pageSize=1000").header("x-goog-api-key", apiKey).get().build())
                body.array("models")
                    .filter { model -> model.jsonObject["supportedGenerationMethods"]?.jsonArray?.any { it.jsonPrimitive.contentOrNull == "generateContent" } == true }
                    .mapNotNull { it.string("name")?.removePrefix("models/") }
            }
            AiProvider.OpenAI, AiProvider.Compatible -> {
                val body = execute(creds, Request.Builder().url("${openAiBase(creds)}/models").header("Authorization", "Bearer $apiKey").get().build())
                body.array("data").mapNotNull { it.string("id") }
            }
        }.sorted()
    }

    private suspend fun claude(c: AiCredentials, system: String, turns: List<AiTurn>): String {
        val fallbacks = c.model in CLAUDE_FALLBACK_MODELS
        val payload = buildJsonObject {
            put("model", c.model)
            put("max_tokens", MAX_TOKENS)
            put("system", system)
            putJsonArray("messages") {
                turns.forEach { turn ->
                    addJsonObject {
                        put("role", if (turn.fromUser) "user" else "assistant")
                        put("content", turn.text)
                    }
                }
            }
            // Chat is light work: low effort keeps replies quick and cheap.
            if (ModelPicker.claudeSupportsEffort(c.model)) putJsonObject("output_config") { put("effort", "low") }
            // A safety decline re-runs on Anthropic's recommended fallback instead of failing.
            if (fallbacks) put("fallbacks", "default")
        }
        val request = claudeRequest(c.apiKey, "$CLAUDE/v1/messages")
            .apply { if (fallbacks) header("anthropic-beta", "server-side-fallback-2026-07-01") }
            .post(payload.toString().toRequestBody(JSON))
            .build()
        val body = execute(c, request)
        if (body.string("stop_reason") == "refusal") throw declined()
        // Thinking blocks come back empty; only text blocks are the reply.
        return body.array("content").filter { it.string("type") == "text" }.joinToString("") { it.string("text").orEmpty() }
    }

    private fun claudeRequest(apiKey: String, url: String) = Request.Builder()
        .url(url)
        .header("x-api-key", apiKey)
        .header("anthropic-version", "2026-10-21")

    private suspend fun gemini(c: AiCredentials, system: String, turns: List<AiTurn>): String {
        val payload = buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", system) } } }
            putJsonArray("contents") {
                turns.forEach { turn ->
                    addJsonObject {
                        put("role", if (turn.fromUser) "user" else "model")
                        putJsonArray("parts") { addJsonObject { put("text", turn.text) } }
                    }
                }
            }
            putJsonObject("generationConfig") { put("maxOutputTokens", MAX_TOKENS) }
        }
        val model = c.model.removePrefix("models/")
        val request = Request.Builder()
            .url("$GEMINI/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", c.apiKey)
            .post(payload.toString().toRequestBody(JSON))
            .build()
        val body = execute(c, request)
        if (body["promptFeedback"]?.jsonObject?.get("blockReason") != null) throw declined()
        val candidate = body.array("candidates").firstOrNull()?.jsonObject ?: throw declined()
        if (candidate.string("finishReason") in GEMINI_BLOCKED) throw declined()
        val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty()
        // Thought summaries are flagged `thought: true`; skip them.
        return parts.filter { it.jsonObject["thought"]?.jsonPrimitive?.booleanOrNull != true }.joinToString("") { it.string("text").orEmpty() }
    }

    private suspend fun openAi(c: AiCredentials, system: String, turns: List<AiTurn>): String {
        val payload = buildJsonObject {
            put("model", c.model)
            // OpenAI renamed the limit; most compatible servers still take the old name.
            put(if (c.provider == AiProvider.OpenAI) "max_completion_tokens" else "max_tokens", MAX_TOKENS)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", system)
                }
                turns.forEach { turn ->
                    addJsonObject {
                        put("role", if (turn.fromUser) "user" else "assistant")
                        put("content", turn.text)
                    }
                }
            }
        }
        val request = Request.Builder()
            .url("${openAiBase(c)}/chat/completions")
            .header("Authorization", "Bearer ${c.apiKey}")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        val body = execute(c, request)
        val message = body.array("choices").firstOrNull()?.jsonObject?.get("message")?.jsonObject ?: throw declined()
        if (message.string("refusal") != null) throw declined()
        return message.string("content").orEmpty()
    }

    private fun openAiBase(c: AiCredentials): String {
        if (c.provider == AiProvider.OpenAI) return OPENAI
        val url = c.baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw AiException("Add your provider's API address (it starts with https://).", AiException.Kind.Setup)
        if (!url.isHttps) throw AiException("The API address must start with https:// so your key stays encrypted.", AiException.Kind.Setup)
        return url.toString().trimEnd('/')
    }

    /** Runs the call, and turns any failure into an [AiException] with a readable message. */
    private suspend fun execute(c: AiCredentials, request: Request): JsonObject {
        val response = try {
            http.newCall(request.newBuilder().header("Content-Type", "application/json").build()).await()
        } catch (e: IOException) {
            throw AiException("Couldn't reach ${c.provider.label}. Check your connection.", AiException.Kind.Network)
        }
        response.use {
            val raw = it.body.string()
            val body = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            if (it.isSuccessful && body != null) return body
            throw failure(c, it.code, body?.let(::errorMessage).orEmpty())
        }
    }

    private fun failure(c: AiCredentials, code: Int, detail: String): AiException {
        val name = c.provider.label
        val text = detail.replace(c.apiKey, "•••").lowercase()
        return when {
            code == 401 || code == 403 || "api key not valid" in text || "api_key_invalid" in text || "invalid x-api-key" in text ->
                AiException("$name rejected the API key. Check it in Settings → AI chat.", AiException.Kind.BadKey)
            "credit" in text || "quota" in text || "billing" in text || "balance" in text || code == 402 ->
                AiException("Your $name account is out of credit or quota. Top it up, or pick another provider.", AiException.Kind.NoCredit)
            code == 429 -> AiException("$name is rate-limiting your key. Give it a minute.", AiException.Kind.RateLimited)
            code == 404 || ("model" in text && ("not found" in text || "does not exist" in text || "not supported" in text)) ->
                AiException("Your key can't use the model \"${c.model}\". Pick another in Settings → AI chat.", AiException.Kind.Model)
            code == 529 || code >= 500 -> AiException("$name is busy right now. Try again in a moment.", AiException.Kind.Busy)
            else -> AiException(
                "$name said: ${detail.replace(c.apiKey, "•••").take(160).ifBlank { "error $code" }}",
                AiException.Kind.Other,
            )
        }
    }

    /** Anthropic, OpenAI and Gemini all nest the text under `error.message`. */
    private fun errorMessage(body: JsonObject): String = when (val error = body["error"]) {
        is JsonObject -> error.string("message").orEmpty()
        else -> error?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    private fun declined() = AiException("They left that one on read (the AI declined to answer). Try saying it differently.", AiException.Kind.Declined)

    private fun JsonObject.array(name: String): JsonArray = (this[name] as? JsonArray) ?: JsonArray(emptyList())

    private fun JsonElement.string(name: String): String? = (this as? JsonObject)?.get(name)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }

    private companion object {
        const val CLAUDE = "https://api.anthropic.com"
        const val GEMINI = "https://generativelanguage.googleapis.com"
        const val OPENAI = "https://api.openai.com/v1"

        /** Room for thinking plus a few short texts. */
        const val MAX_TOKENS = 2048
        val JSON = "application/json".toMediaType()
        val GEMINI_BLOCKED = setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION")

        /** Models that take the server-side `fallbacks: "default"` opt-in. */
        val CLAUDE_FALLBACK_MODELS = setOf("claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5", "claude-fable-5-1")
    }
}

/** Cancels the HTTP call when the coroutine is cancelled (the user left, or sent another message). */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, value, _ -> value.close() }
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
    })
}

/** Picks a sensible model from what a key can use. Pure, so it's unit-tested. */
object ModelPicker {
    private val OPENAI_SKIP = Regex("audio|realtime|transcribe|tts|image|search|embedding|moderation|instruct|codex|preview|-\\d{4}-\\d{2}-\\d{2}")
    private val GEMINI_SKIP = Regex("lite|image|tts|live|audio|embedding|exp|preview|thinking|aqa|gemma|learnlm|robotics|computer")
    private val VERSION = Regex("""(\d+(?:\.\d+)?)""")

    /** The models worth listing for [provider]: chat models only, best first. */
    fun chatModels(provider: AiProvider, ids: List<String>): List<String> = when (provider) {
        AiProvider.Claude -> ids.filter { it.startsWith("claude-") }.sortedByDescending(::claudeRank)
        AiProvider.Gemini -> ids.filter { it.startsWith("gemini-") && !it.contains("embedding") }.sortedByDescending(::version)
        AiProvider.OpenAI -> ids.filter { (it.startsWith("gpt-") || it.startsWith("chatgpt-")) && !OPENAI_SKIP.containsMatchIn(it) }
            .sortedByDescending(::version)
        AiProvider.Compatible -> ids
    }

    /** [current] if the key can use it, else the provider's default, else the best fit. */
    fun preferred(provider: AiProvider, ids: List<String>, current: String): String {
        if (ids.isEmpty()) return current.ifBlank { provider.defaultModel }
        if (current.isNotBlank() && current in ids) return current
        if (provider.defaultModel in ids) return provider.defaultModel
        return when (provider) {
            AiProvider.Claude -> chatModels(provider, ids).firstOrNull()
            AiProvider.Gemini -> ids.filter { it.startsWith("gemini-") && "flash" in it && !GEMINI_SKIP.containsMatchIn(it) }.maxByOrNull(::version)
            AiProvider.OpenAI -> chatModels(provider, ids).filter { it.endsWith("-mini") }.maxByOrNull(::version)
            AiProvider.Compatible -> null
        } ?: chatModels(provider, ids).firstOrNull() ?: ids.first()
    }

    /** Whether a Claude model takes `output_config.effort` (Haiku 4.5, Sonnet 4.5 and older reject it). */
    fun claudeSupportsEffort(model: String): Boolean {
        val match = Regex("""claude-(opus|sonnet|haiku|fable|mythos)-(\d{1,2})(?:-(\d{1,2}))?(?!\d)""").find(model) ?: return false
        val family = match.groupValues[1]
        val major = match.groupValues[2].toInt()
        val minor = match.groupValues[3].toIntOrNull() ?: 0
        return when (family) {
            "fable", "mythos" -> true
            "opus" -> major > 4 || (major == 4 && minor >= 5)
            "sonnet" -> major > 4 || (major == 4 && minor >= 6)
            else -> major >= 5
        }
    }

    /** Opus first, then Sonnet, then Haiku; newer first within each. */
    private fun claudeRank(id: String): Double {
        val tier = when {
            "fable" in id -> 4
            "opus" in id -> 3
            "sonnet" in id -> 2
            "haiku" in id -> 1
            else -> 0
        }
        val numbers = Regex("""-(\d+)(?:-(\d{1,2}))?(?:-|$)""").find(id)
        val major = numbers?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it < 100 } ?: 0
        val minor = numbers?.groupValues?.get(2)?.toIntOrNull() ?: 0
        return tier * 10_000.0 + major * 100 + minor
    }

    private fun version(id: String): Double = VERSION.find(id)?.value?.toDoubleOrNull() ?: 0.0
}
