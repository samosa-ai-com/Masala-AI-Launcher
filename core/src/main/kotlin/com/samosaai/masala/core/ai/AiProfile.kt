package com.samosaai.masala.core.ai

import kotlinx.serialization.Serializable

/** One model endpoint: a preset, its base URL and a model id (blank = ask the server). Keys live elsewhere. */
@Serializable
data class Endpoint(val presetId: String, val baseUrl: String, val model: String = "") {
    val preset get() = ProviderPresets.byId(presetId)
}

/** How the mic button works (decision 6): tap to talk with auto-stop on a pause, or hold to talk. */
@Serializable
enum class MicMode { TAP, HOLD }

/**
 * The AI and voice choices (plan UI refresh U2): chat, voice in and voice out, each a provider and
 * model, mixed freely. Voice in and out may be "on this phone" (null endpoint): Android's recognizer
 * and text-to-speech. Pure data, so `:core` tests cover validation without Android.
 */
@Serializable
data class AiProfile(
    val chat: Endpoint,
    /** Null: on this phone. */
    val voiceIn: Endpoint? = null,
    /** Null: the phone's voice. */
    val voiceOut: Endpoint? = null,
    val micMode: MicMode = MicMode.TAP,
    /** ISO 639-1 code, blank = the phone's language. */
    val voiceLanguage: String = "",
    /** An API voice name, or an Android voice name for the phone's voice; blank = the default. */
    val voice: String = "",
    val speechRate: Float = 1f,
) {
    /** Every endpoint the profile uses, for "what leaves the phone" and key lookups. */
    val endpoints: List<Endpoint> get() = listOfNotNull(chat, voiceIn, voiceOut)

    companion object {
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 2f
    }
}

/** Why a base URL was refused. */
enum class UrlProblem { EMPTY, NOT_HTTP, NO_HOST, HAS_QUERY }

object AiValidation {
    /**
     * A base URL as Masala stores it: trimmed, http or https, a host, no query or fragment, and one
     * trailing slash (Retrofit resolves relative paths against it, so `…/v1` would drop the `v1`).
     */
    fun normalizeBaseUrl(raw: String): Result<String> {
        val url = raw.trim()
        if (url.isEmpty()) return Result.failure(InvalidUrl(UrlProblem.EMPTY))
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return Result.failure(InvalidUrl(UrlProblem.NOT_HTTP))
        val rest = url.substringAfter("://")
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (host.isBlank() || host.startsWith(":")) return Result.failure(InvalidUrl(UrlProblem.NO_HOST))
        if ('?' in rest || '#' in rest) return Result.failure(InvalidUrl(UrlProblem.HAS_QUERY))
        return Result.success("$scheme://" + rest.trimEnd('/') + "/")
    }

    /** Problems with [profile] that would make a call fail before it is sent; empty when usable. */
    fun problems(profile: AiProfile, hasKey: (presetId: String) -> Boolean): List<String> = buildList {
        for ((role, e) in listOf("chat" to profile.chat, "voice in" to profile.voiceIn, "voice out" to profile.voiceOut)) {
            if (e == null) continue
            normalizeBaseUrl(e.baseUrl).onFailure { add("$role: ${(it as? InvalidUrl)?.problem ?: UrlProblem.EMPTY}") }
            if (e.preset.keyKind == KeyKind.KEY && !hasKey(e.presetId)) add("$role: no API key for ${e.preset.name}")
        }
        if (profile.chat.model.isBlank()) add("chat: no model")
        if (profile.speechRate !in AiProfile.MIN_RATE..AiProfile.MAX_RATE) add("voice out: speed out of range")
    }

    class InvalidUrl(val problem: UrlProblem) : IllegalArgumentException(problem.name)
}
