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
        val reply = client.chat(
            messages,
            responseFormat = responseFormat,
            maxTokens = maxTokens,
            temperature = temperature,
        ).choices.firstOrNull()?.message?.textContent.orEmpty()
        if (reply.isBlank()) throw IllegalStateException("The model returned no text.")
        return reply
    }
}
