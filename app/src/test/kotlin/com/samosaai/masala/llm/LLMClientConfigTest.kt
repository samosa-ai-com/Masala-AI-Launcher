package com.samosaai.masala.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class LLMClientConfigTest {
    @Test
    fun blankEndpointSaysWhereToSetOneUp() {
        // Still an IllegalArgumentException (what Retrofit threw before), with a useful message for
        // callers that only log it.
        val e = assertFailsWith<ModelNotConfiguredException> { LLMClient(apiKey = "", baseUrl = "") }
        assertIs<IllegalArgumentException>(e)
        assertEquals(NOT_CONFIGURED_MESSAGE, e.message)
    }

    @Test
    fun chatShowsTheLocalizedText() {
        val text = "Noch kein Modell eingerichtet."
        assertEquals(text, com.samosaai.masala.agent.friendlyTurnError(ModelNotConfiguredException(), text))
        assertEquals(NOT_CONFIGURED_MESSAGE, com.samosaai.masala.agent.friendlyTurnError(ModelNotConfiguredException()))
    }

    @Test
    fun releaseDefaultsAreNotConfigured() {
        assertEquals(false, LlmConfig(baseUrl = "", apiKey = "", model = "").isConfigured)
    }
}
