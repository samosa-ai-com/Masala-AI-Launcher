package com.samosaai.masala.llm

import android.content.Context
import com.samosaai.masala.BuildConfig
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

// From the private prototype's llm/LLMClient.kt. Changes: no SAMOSA_* BuildConfig or settings coupling (the caller
// passes the endpoint, key and model, see LlmConfig), no dictation cleanup (that comes with speech),
// and an optional response_format for structured output (plan Phase 1, lane B).

/** the private prototype's default API timeout. */
const val DEFAULT_API_TIMEOUT_SECONDS = 180L

/** Longest a connection to the model server may take to open, however long the API timeout is. */
private const val MAX_CONNECT_TIMEOUT_SECONDS = 15L

/** OkHttp timeouts, in seconds, for one API timeout setting. 0 means no timeout. */
internal data class LlmTimeouts(val connectSeconds: Long, val readSeconds: Long, val writeSeconds: Long)

/**
 * Splits the user's API timeout into OkHttp's timeouts (#104).
 *
 * Read and write are gaps between bytes, not a cap on the whole request, so a
 * reply that keeps arriving is never cut off; there is deliberately no call
 * timeout. Connecting never needs minutes, so it is capped at
 * [MAX_CONNECT_TIMEOUT_SECONDS]. 0 is the user asking for no timeout at all and
 * is passed through to every one of them.
 */
internal fun llmTimeouts(apiTimeoutSeconds: Long): LlmTimeouts {
    val timeout = apiTimeoutSeconds.coerceAtLeast(0L)
    if (timeout == 0L) return LlmTimeouts(0L, 0L, 0L)
    return LlmTimeouts(
        connectSeconds = timeout.coerceAtMost(MAX_CONNECT_TIMEOUT_SECONDS),
        readSeconds = timeout,
        writeSeconds = timeout
    )
}

class LLMClient(
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String = "chai-small",
    context: Context? = null,
    private val apiTimeoutSeconds: Long = DEFAULT_API_TIMEOUT_SECONDS,
    private val cache: LLMCache = LLMCache(context),
    /**
     * Invoked when the server responds 401 (e.g. an expired/blacklisted Samosa
     * JWT). Lets the caller clear the stored token and prompt re-authentication.
     * No-op for the OpenAI-compatible flow unless a caller opts in.
     */
    private val onUnauthorized: (() -> Unit)? = null
) {
    private val apiService: ApiService

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    init {
        val logging = HttpLoggingInterceptor().apply {
            // BODY tracing writes full prompts/replies to logcat — never in release.
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            redactHeader("Authorization")
        }

        val timeouts = llmTimeouts(apiTimeoutSeconds)
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(timeouts.connectSeconds, TimeUnit.SECONDS)
            .readTimeout(timeouts.readSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeouts.writeSeconds, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                // Servers on the user's own network take no key; send none rather than an empty bearer.
                if (apiKey.isNotBlank()) builder.addHeader("Authorization", "Bearer $apiKey")
                val response = chain.proceed(builder.build())
                if (response.code == 401) {
                    onUnauthorized?.invoke()
                }
                response
            }
            .addInterceptor(logging)
            .build()

        val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        val retrofit = Retrofit.Builder()
            .baseUrl(normalizedBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        apiService = retrofit.create(ApiService::class.java)
    }

    suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
        temperature: Float? = null,
        sessionId: String? = null,
        modelOverride: String? = null,
        responseFormat: ResponseFormat? = null,
        maxTokens: Int? = null
    ): ChatResponse {
        val targetModel = modelOverride ?: model
        // Safety net for histories that carried a malformed tool call before the
        // append-time sanitization existed (or were restored from an old session):
        // never transmit arguments that aren't valid JSON, or the server 400s the
        // whole request and every later turn (issue #13). Runs before the cache
        // key so identical (sanitized) histories share a key.
        val sanitizedMessages = messages.map { it.withValidToolCallArguments() }
        val cacheKey = buildCacheKey(targetModel, sanitizedMessages, tools, responseFormat, maxTokens)

        if (temperature == null || temperature == 0f) {
            cache.get(cacheKey)?.let { return it }
        }

        val request = ChatRequest(
            model = targetModel,
            messages = sanitizedMessages,
            tools = tools.ifEmpty { null },
            temperature = temperature,
            promptCacheKey = sessionId,
            responseFormat = responseFormat,
            maxTokens = maxTokens
        )

        var response = apiService.chat(request, sessionId)
        response = normalizeResponse(response)

        val shouldCache = (temperature == null || temperature == 0f) &&
            response.choices.isNotEmpty() &&
            response.choices.none { it.message.toolCalls?.isNotEmpty() == true }

        if (shouldCache) {
            cache.put(cacheKey, response)
        }

        return response
    }

    fun clearCache() {
        cache.clear()
    }

    /** Chat models on the server; speech models (by the server's hint or their name) are left out. */
    suspend fun listModels(): Result<List<String>> = runCatching {
        val speech = Regex("tts|stt|whisper|speech|transcri")
        apiService.listModels().data
            .filterNot { info -> listOfNotNull(info.task, info.providerType, info.id).any { speech.containsMatchIn(it.lowercase()) } }
            .map { it.id }
            .sorted()
    }

    private fun buildCacheKey(
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        responseFormat: ResponseFormat?,
        maxTokens: Int?
    ): String {
        val serialized = buildString {
            append(model)
            append("|")
            append(json.encodeToString(ListSerializer(ChatMessage.serializer()), messages))
            append("|")
            append(json.encodeToString(ListSerializer(ToolDefinition.serializer()), tools))
            append("|")
            if (responseFormat != null) append(json.encodeToString(ResponseFormat.serializer(), responseFormat))
            append("|")
            if (maxTokens != null) append(maxTokens)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(serialized.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun normalizeResponse(response: ChatResponse): ChatResponse {
        val newChoices = response.choices.map { choice ->
            var msg = choice.message
            var text = msg.textContent
            var reasoning = msg.reasoningContent ?: ""

            // 1. Extract <think> tags from text
            val thinkRegex = "(?s)<think>(.*?)</think>".toRegex()
            val thinkMatches = thinkRegex.findAll(text)
            for (match in thinkMatches) {
                if (reasoning.isNotBlank()) reasoning += "\n"
                reasoning += match.groupValues[1].trim()
            }
            if (thinkMatches.any()) {
                text = thinkRegex.replace(text, "").trim()
            }

            val toolCalls = msg.toolCalls?.toMutableList() ?: mutableListOf()

            // 2. Extract XML tool calls if native toolCalls is empty
            if (toolCalls.isEmpty()) {
                val combinedText = "$reasoning\n$text"
                val toolCallRegex = "(?s)<tool_call>\\s*<function=([a-zA-Z0-9_]+)>\\s*(.*?)\\s*</function>\\s*</tool_call>".toRegex()
                val matches = toolCallRegex.findAll(combinedText)
                for (match in matches) {
                    val name = match.groupValues[1]
                    val argsRaw = match.groupValues[2].trim()
                    val args = if (argsRaw.isBlank()) "{}" else argsRaw
                    toolCalls.add(
                        ToolCall(
                            id = "call_" + java.util.UUID.randomUUID().toString().substring(0, 8),
                            function = FunctionCall(name, args)
                        )
                    )
                }
            }

            msg = msg.copy(
                content = kotlinx.serialization.json.JsonPrimitive(text),
                reasoningContent = reasoning.trim().ifEmpty { null },
                toolCalls = toolCalls.ifEmpty { null }
            )
            choice.copy(message = msg)
        }
        return response.copy(choices = newChoices)
    }
}
