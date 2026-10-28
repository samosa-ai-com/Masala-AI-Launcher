package com.samosaai.masala.agent

import com.samosaai.masala.llm.ChatMessage
import com.samosaai.masala.llm.ChatResponse
import com.samosaai.masala.llm.FunctionDefinition
import com.samosaai.masala.llm.LLMClient
import com.samosaai.masala.llm.ToolDefinition
import com.samosaai.masala.llm.withValidToolCallArguments

/** One model turn: the conversation so far in, the model's next message (text and/or tool calls) out. */
fun interface AgentModel {
    suspend fun next(messages: List<ChatMessage>, tools: List<ToolDefinition>): ChatMessage
}

/** [AgentModel] over the OpenAI-compatible endpoint. [onResponse] sees each raw response (debug screen). */
class LlmAgentModel(
    private val client: LLMClient,
    private val onResponse: ((ChatResponse) -> Unit)? = null,
) : AgentModel {
    override suspend fun next(messages: List<ChatMessage>, tools: List<ToolDefinition>): ChatMessage {
        // A non-zero temperature skips LLMClient's response cache, so every run reaches the model.
        val response = client.chat(messages, tools = tools, temperature = 0.2f)
        onResponse?.invoke(response)
        return response.choices.firstOrNull()?.message?.withValidToolCallArguments()
            ?: throw IllegalStateException("The model returned no message.")
    }
}

internal fun com.samosaai.masala.core.tool.ToolDefinition.toLlm() =
    ToolDefinition(function = FunctionDefinition(name, description, parameters))
