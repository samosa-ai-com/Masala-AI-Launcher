package com.samosaai.masala.llm

import android.content.Context
import com.samosaai.masala.BuildConfig

/**
 * Where model calls go: any OpenAI-compatible endpoint. [default] is the chat choice in Settings → Masala
 * AI → Models & keys (`AiSettingsStore`), which was seeded once from `local.properties` through
 * BuildConfig. A blank key is allowed: servers on the user's own network (LM Studio, Ollama) take none.
 */
data class LlmConfig(val baseUrl: String, val apiKey: String, val model: String) {
    val isConfigured get() = baseUrl.isNotBlank() && model.isNotBlank()

    /** A server on the user's own network (LM Studio, Ollama): slower than a cloud API, so it waits longer. */
    val isOnLocalNetwork: Boolean
        get() = runCatching { java.net.URI(baseUrl).host }.getOrNull()
            ?.let { com.samosaai.masala.connectors.mcp.isLocalHost(it) } ?: false

    fun client(context: Context) = LLMClient(
        apiKey = apiKey,
        baseUrl = baseUrl,
        model = model,
        context = context,
        apiTimeoutSeconds = if (isOnLocalNetwork) LOCAL_API_TIMEOUT_SECONDS else DEFAULT_API_TIMEOUT_SECONDS,
    )

    companion object {
        /** From `local.properties`, compiled in (decision 8); the store's first-run seed. */
        val fromBuild get() = LlmConfig(BuildConfig.MASALA_LLM_BASE_URL, BuildConfig.MASALA_LLM_API_KEY, BuildConfig.MASALA_LLM_MODEL)

        /** Set by `MasalaApp` to read the store; build config until then (and in unit tests). */
        @Volatile
        var source: () -> LlmConfig = { fromBuild }

        val default: LlmConfig get() = source()
    }
}
