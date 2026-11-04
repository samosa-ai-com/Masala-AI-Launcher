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

    /**
     * Prompt tokens the server reported for the newest request, or null when it doesn't report
     * them. Lets the chat compact on real numbers rather than a character estimate.
     */
    val lastPromptTokens: Int? get() = null
}

/** [AgentModel] over the OpenAI-compatible endpoint. [onResponse] sees each raw response (debug screen). */
class LlmAgentModel(
    private val client: LLMClient,
    private val onResponse: ((ChatResponse) -> Unit)? = null,
) : AgentModel {
    @Volatile
    override var lastPromptTokens: Int? = null
        private set

    override suspend fun next(messages: List<ChatMessage>, tools: List<ToolDefinition>): ChatMessage {
        // A non-zero temperature skips LLMClient's response cache, so every run reaches the model.
        val response = client.chat(messages, tools = tools, temperature = 0.2f)
        lastPromptTokens = response.usage?.promptTokens?.takeIf { it > 0 }
        onResponse?.invoke(response)
        return response.choices.firstOrNull()?.message?.withValidToolCallArguments()
            ?: throw IllegalStateException("The model returned no message.")
    }
}

internal fun com.samosaai.masala.core.tool.ToolDefinition.toLlm() =
    ToolDefinition(function = FunctionDefinition(name, description, withProperties(parameters)))

/**
 * An object schema always carries `properties`, `{}` for a tool with no arguments. Hosted APIs accept
 * a bare `{"type":"object"}`, but LM Studio rejects the whole request (HTTP 400, "properties
 * Required"), so one argument-free tool broke every chat on a self-hosted model.
 */
internal fun withProperties(parameters: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject =
    if (parameters["type"]?.toString()?.trim('"') == "object" && "properties" !in parameters) {
        kotlinx.serialization.json.JsonObject(parameters + ("properties" to kotlinx.serialization.json.JsonObject(emptyMap())))
    } else {
        parameters
    }
