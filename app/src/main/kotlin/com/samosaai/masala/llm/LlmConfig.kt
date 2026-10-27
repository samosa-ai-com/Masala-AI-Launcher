package com.samosaai.masala.llm

import android.content.Context
import com.samosaai.masala.BuildConfig

/**
 * Where model calls go: any OpenAI-compatible endpoint. Defaults come from `local.properties` through
 * BuildConfig (Samosa's proxy unless `MASALA_LLM_*` override it); the debug screen can change them.
 * Samosa's proxy wants a signed-in user's token as the key, and Masala has no sign-in yet.
 */
data class LlmConfig(val baseUrl: String, val apiKey: String, val model: String) {
    val isConfigured get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    fun client(context: Context) = LLMClient(apiKey = apiKey, baseUrl = baseUrl, model = model, context = context)

    companion object {
        val default = LlmConfig(BuildConfig.MASALA_LLM_BASE_URL, BuildConfig.MASALA_LLM_API_KEY, BuildConfig.MASALA_LLM_MODEL)
    }
}
