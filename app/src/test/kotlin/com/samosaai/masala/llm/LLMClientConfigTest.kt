package com.samosaai.masala.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LLMClientConfigTest {
    @Test
    fun blankEndpointSaysWhereToSetOneUp() {
        val e = assertFailsWith<IllegalArgumentException> { LLMClient(apiKey = "", baseUrl = "") }
        assertEquals(NOT_CONFIGURED_MESSAGE, e.message)
    }

    @Test
    fun releaseDefaultsAreNotConfigured() {
        assertEquals(false, LlmConfig(baseUrl = "", apiKey = "", model = "").isConfigured)
    }
}
