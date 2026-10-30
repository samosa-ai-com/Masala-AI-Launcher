package com.samosaai.masala.core.ai

/** Whether a provider wants an API key, or runs on the user's own network without one. */
enum class KeyKind { KEY, NONE }

/** What a provider is expected to offer. Only the default toggles: Test connection decides. */
data class Capabilities(val chat: Boolean, val voiceIn: Boolean, val voiceOut: Boolean)

/**
 * A provider preset (plan UI refresh U2, decision 9): picking one fills in the OpenAI-compatible base
 * URL. Providers on the user's own computer have no fixed host, so they carry [lanPort] and the URL
 * is built from the address the user types ([baseUrlFor]). Model ids come from Fetch list
 * (`GET /models`); only Meta's current chat model is set here.
 */
data class ProviderPreset(
    val id: String,
    val name: String,
    /** Two letters for the preset tile. */
    val mono: String,
    val baseUrl: String,
    val keyKind: KeyKind,
    val capabilities: Capabilities,
    val defaultChatModel: String = "",
    /** Set for providers on the local network: the port on the user's computer. */
    val lanPort: Int? = null,
) {
    val onLan get() = lanPort != null

    /** The base URL for a computer at [host] (LAN presets), else the fixed one. */
    fun baseUrlFor(host: String): String =
        if (lanPort == null) baseUrl else "http://${host.trim().removePrefix("http://").trimEnd('/')}:$lanPort/v1/"
}

object ProviderPresets {
    val OpenAI = ProviderPreset("openai", "OpenAI", "OA", "https://api.openai.com/v1/", KeyKind.KEY, Capabilities(chat = true, voiceIn = true, voiceOut = true))
    val Meta = ProviderPreset("meta", "Meta", "Me", "https://api.meta.ai/v1/", KeyKind.KEY, Capabilities(chat = true, voiceIn = true, voiceOut = false), defaultChatModel = "muse-spark-1.3")
    val Anthropic = ProviderPreset("anthropic", "Anthropic", "An", "https://api.anthropic.com/v1/", KeyKind.KEY, Capabilities(chat = true, voiceIn = false, voiceOut = false))
    val Gemini = ProviderPreset("gemini", "Gemini", "Ge", "https://generativelanguage.googleapis.com/v1beta/openai/", KeyKind.KEY, Capabilities(chat = true, voiceIn = false, voiceOut = false))
    val Groq = ProviderPreset("groq", "Groq", "Gq", "https://api.groq.com/openai/v1/", KeyKind.KEY, Capabilities(chat = true, voiceIn = true, voiceOut = false))
    val OpenRouter = ProviderPreset("openrouter", "OpenRouter", "OR", "https://openrouter.ai/api/v1/", KeyKind.KEY, Capabilities(chat = true, voiceIn = false, voiceOut = false))
    val Mistral = ProviderPreset("mistral", "Mistral", "Mi", "https://api.mistral.ai/v1/", KeyKind.KEY, Capabilities(chat = true, voiceIn = false, voiceOut = false))
    val LmStudio = ProviderPreset("lmstudio", "LM Studio", "LM", "", KeyKind.NONE, Capabilities(chat = true, voiceIn = false, voiceOut = false), lanPort = 1234)
    val Ollama = ProviderPreset("ollama", "Ollama", "Ol", "", KeyKind.NONE, Capabilities(chat = true, voiceIn = false, voiceOut = false), lanPort = 11434)
    val Speaches = ProviderPreset("speaches", "Speaches", "Sp", "", KeyKind.KEY, Capabilities(chat = false, voiceIn = true, voiceOut = true), lanPort = 8000)
    val Custom = ProviderPreset("custom", "Custom", "+", "", KeyKind.KEY, Capabilities(chat = true, voiceIn = true, voiceOut = true))

    /** The eleven presets, in the order the grid shows them. No Samosa preset until sign-in exists. */
    val all = listOf(OpenAI, Meta, Anthropic, Gemini, Groq, OpenRouter, Mistral, LmStudio, Ollama, Speaches, Custom)

    fun byId(id: String?): ProviderPreset = all.firstOrNull { it.id == id } ?: Custom

    /**
     * The preset a URL most likely belongs to (seeding from build config): a fixed preset whose host
     * matches, a LAN preset whose port matches, else Custom.
     */
    fun detect(baseUrl: String): ProviderPreset {
        val host = hostOf(baseUrl) ?: return Custom
        all.firstOrNull { it.baseUrl.isNotBlank() && hostOf(it.baseUrl) == host }?.let { return it }
        val port = Regex(""":(\d+)(/|$)""").find(baseUrl.substringAfter("://"))?.groupValues?.get(1)?.toIntOrNull()
        return all.firstOrNull { it.lanPort != null && it.lanPort == port } ?: Custom
    }

    private fun hostOf(url: String): String? =
        url.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase().takeIf { it.isNotBlank() }
}
