package com.samosaai.masala.layout

import com.samosaai.masala.llm.ChatMessage
import com.samosaai.masala.llm.LLMClient
import com.samosaai.masala.llm.ResponseFormat

/** [ChatCaller] over the real model endpoint. Transport trouble surfaces as an exception. */
class LlmChatCaller(private val client: LLMClient) : ChatCaller {
    override suspend fun call(
        messages: List<ChatMessage>,
        responseFormat: ResponseFormat?,
        maxTokens: Int,
        temperature: Float?,
    ): String {
        // Blank text passes through: the checker's parse problem sends it to the repair turn,
        // which is how the generator heals a flaky empty reply. No choices at all is a broken
        // response, so that still throws.
        val message = client.chat(
            messages,
            responseFormat = responseFormat,
            maxTokens = maxTokens,
            temperature = temperature,
        ).choices.firstOrNull()?.message
            ?: throw IllegalStateException("The model returned no message.")
        return message.textContent
    }
}
