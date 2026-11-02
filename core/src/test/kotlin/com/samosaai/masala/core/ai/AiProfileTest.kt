package com.samosaai.masala.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiProfileTest {
    @Test
    fun `eleven presets in the agreed order, no Samosa, ids unique`() {
        assertEquals(
            listOf("openai", "meta", "anthropic", "gemini", "groq", "openrouter", "mistral", "lmstudio", "ollama", "speaches", "custom"),
            ProviderPresets.all.map { it.id },
        )
        assertEquals(ProviderPresets.all.size, ProviderPresets.all.map { it.id }.toSet().size)
    }

    @Test
    fun `only Meta names models, and every fixed URL is already normalized`() {
        assertEquals(listOf("meta"), ProviderPresets.all.filter { it.defaultChatModel.isNotBlank() }.map { it.id })
        assertEquals(listOf("meta"), ProviderPresets.all.filter { it.defaultImageModel.isNotBlank() }.map { it.id })
        ProviderPresets.all.filter { it.baseUrl.isNotBlank() }.forEach {
            assertEquals(it.baseUrl, AiValidation.normalizeBaseUrl(it.baseUrl).getOrThrow(), it.id)
        }
    }

    @Test
    fun `presets on the user's computer build their URL from the address typed`() {
        assertEquals("http://192.168.1.20:1234/v1/", ProviderPresets.LmStudio.baseUrlFor("192.168.1.20"))
        assertEquals("http://box.local:11434/v1/", ProviderPresets.Ollama.baseUrlFor(" http://box.local/ "))
        assertEquals(ProviderPresets.OpenAI.baseUrl, ProviderPresets.OpenAI.baseUrlFor("ignored"))
    }

    @Test
    fun `a URL is detected as its preset`() {
        assertEquals("meta", ProviderPresets.detect("https://api.meta.ai/v1/").id)
        assertEquals("groq", ProviderPresets.detect("https://API.groq.com/openai/v1").id)
        assertEquals("lmstudio", ProviderPresets.detect("http://10.0.2.2:1234/v1/").id)
        assertEquals("speaches", ProviderPresets.detect("http://192.168.1.5:8000/v1").id)
        assertEquals("custom", ProviderPresets.detect("https://llm.example.org/v1/").id)
        assertEquals("custom", ProviderPresets.detect("not a url").id)
    }

    @Test
    fun `base URLs get one trailing slash and bad ones are refused with a reason`() {
        assertEquals("https://api.openai.com/v1/", AiValidation.normalizeBaseUrl("  https://api.openai.com/v1  ").getOrThrow())
        assertEquals("http://10.0.2.2:1234/v1/", AiValidation.normalizeBaseUrl("HTTP://10.0.2.2:1234/v1///").getOrThrow())
        fun problem(url: String) = (AiValidation.normalizeBaseUrl(url).exceptionOrNull() as AiValidation.InvalidUrl).problem
        assertEquals(UrlProblem.EMPTY, problem(" "))
        assertEquals(UrlProblem.NOT_HTTP, problem("ftp://host/v1"))
        assertEquals(UrlProblem.NOT_HTTP, problem("api.openai.com/v1"))
        assertEquals(UrlProblem.NO_HOST, problem("https:///v1"))
        assertEquals(UrlProblem.HAS_QUERY, problem("https://host/v1?key=abc"))
    }

    @Test
    fun `image-capable presets are openai, meta, gemini, openrouter and custom`() {
        assertEquals(
            listOf("openai", "meta", "gemini", "openrouter", "custom"),
            ProviderPresets.all.filter { it.capabilities.image }.map { it.id },
        )
        assertEquals("muse-image-1.0", ProviderPresets.Meta.defaultImageModel)
    }

    @Test
    fun `a profile names what is missing before anything is sent`() {
        val ok = AiProfile(Endpoint("meta", "https://api.meta.ai/v1/", "muse-spark-1.3"))
        assertTrue(AiValidation.problems(ok) { true }.isEmpty())
        val noKey = AiValidation.problems(ok) { false }
        assertEquals(listOf("chat: no API key for Meta"), noKey)
        val lan = AiProfile(Endpoint("lmstudio", "http://10.0.2.2:1234/v1/", ""), voiceIn = Endpoint("custom", "ftp://x"))
        val problems = AiValidation.problems(lan) { false }
        assertTrue("chat: no model" in problems, problems.toString())
        assertTrue(problems.any { it.startsWith("voice in: NOT_HTTP") }, problems.toString())
        assertTrue(problems.none { it.startsWith("chat: no API key") }, "LM Studio needs no key: $problems")
    }

    @Test
    fun `image role is validated like the other server roles`() {
        val ok = AiProfile(
            Endpoint("meta", "https://api.meta.ai/v1/", "muse-spark-1.3"),
            image = Endpoint("meta", "https://api.meta.ai/v1/", "muse-image-1.0"),
        )
        assertTrue(AiValidation.problems(ok) { true }.isEmpty())
        // Blank image model is fine (the server is asked for its first image model).
        val blank = AiProfile(
            Endpoint("meta", "https://api.meta.ai/v1/", "muse-spark-1.3"),
            image = Endpoint("meta", "https://api.meta.ai/v1/", ""),
        )
        assertTrue(AiValidation.problems(blank) { true }.isEmpty())
        val bad = AiProfile(
            Endpoint("meta", "https://api.meta.ai/v1/", "muse-spark-1.3"),
            image = Endpoint("custom", "ftp://x"),
        )
        val problems = AiValidation.problems(bad) { false }
        assertTrue(problems.any { it.startsWith("image: NOT_HTTP") }, problems.toString())
        assertTrue(problems.any { it == "image: no API key for Custom" }, problems.toString())
    }
}
