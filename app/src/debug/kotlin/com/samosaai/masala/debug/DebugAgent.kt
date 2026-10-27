package com.samosaai.masala.debug

import com.samosaai.masala.core.tool.Tool
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.llm.ChatMessage
import com.samosaai.masala.llm.FunctionDefinition
import com.samosaai.masala.llm.LLMClient
import com.samosaai.masala.llm.ToolDefinition
import com.samosaai.masala.llm.withValidToolCallArguments
import com.samosaai.masala.tools.MasalaToolExecutor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The smallest agent loop that exercises the model and the tools together (plan Phase 1 exit: "a
 * debug screen completes one LLM tool call"). Phase 4's MasalaAgent replaces it.
 */
class DebugAgent(private val client: LLMClient, private val tools: List<Tool>, private val executor: MasalaToolExecutor) {

    sealed interface Event {
        data class Raw(val json: String) : Event
        data class Call(val name: String, val args: String) : Event
        data class Result(val name: String, val result: ToolResult) : Event
        data class Answer(val text: String) : Event
        data class Error(val message: String) : Event
    }

    private val json = Json { prettyPrint = true; explicitNulls = false }

    suspend fun run(prompt: String, onEvent: (Event) -> Unit) {
        val definitions = tools.map { ToolDefinition(function = FunctionDefinition(it.definition.name, it.definition.description, it.definition.parameters)) }
        val messages = mutableListOf(
            ChatMessage("system", JsonPrimitive(systemPrompt())),
            ChatMessage("user", JsonPrimitive(prompt)),
        )
        repeat(MAX_STEPS) {
            // A non-zero temperature skips LLMClient's response cache, so every run reaches the model.
            val response = client.chat(messages, tools = definitions, temperature = 0.2f)
            onEvent(Event.Raw(json.encodeToString(response)))
            val message = response.choices.firstOrNull()?.message?.withValidToolCallArguments()
                ?: return onEvent(Event.Error("The model returned no message."))
            messages += message
            val calls = message.toolCalls.orEmpty()
            if (calls.isEmpty()) return onEvent(Event.Answer(message.textContent))
            for (call in calls) {
                val args = runCatching { Json.parseToJsonElement(call.function.arguments) as JsonObject }.getOrDefault(JsonObject(emptyMap()))
                onEvent(Event.Call(call.function.name, args.toString()))
                val result = executor.execute(call.function.name, args)
                onEvent(Event.Result(call.function.name, result))
                messages += ChatMessage("tool", JsonPrimitive(result.text()), toolCallId = call.id)
            }
        }
        onEvent(Event.Error("Stopped after $MAX_STEPS steps."))
    }

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.US).format(Date())
        return "You are Masala, the assistant built into an Android home screen. Use the tools to act on the " +
            "phone when the user asks for something a tool can do; otherwise answer briefly, in the " +
            "language the user writes in. " +
            "It is $now (${TimeZone.getDefault().id})."
    }

    private companion object {
        const val MAX_STEPS = 6

        fun ToolResult.text() = when (this) {
            is ToolResult.Success -> output
            is ToolResult.Failure -> "Error: $message"
            is ToolResult.NeedsCapability -> "Not run: $reason"
        }
    }
}
