package com.samosaai.masala.actions

import com.samosaai.masala.core.tool.Tool
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.core.tool.ToolSensitivity
import com.samosaai.masala.tools.ActionLog
import com.samosaai.masala.tools.ToolGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Asks the user before anything that isn't read-only runs (arch §3.6, plan Phase 2). The home screen shows
 * [pending] as a bottom sheet; [confirm] waits for the answer, up to [timeoutMs], and a timeout counts as
 * "no". One question at a time. Every answer, including timeouts, goes to the [ActionLog], so what was
 * allowed or refused can be audited. It is also the executor's [ToolGate].
 */
class ConfirmationGate(
    /** Where every answer goes: `ActionLog::record` in the app. */
    private val record: (subject: String, summary: String, result: ToolResult) -> Unit,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : ToolGate {

    constructor(log: ActionLog, timeoutMs: Long = DEFAULT_TIMEOUT_MS) : this(log::record, timeoutMs)

    enum class Decision { ALLOWED, DECLINED, TIMED_OUT }

    /** The question on screen. [destructive] asks in a stronger voice and says something is lost. */
    class Request(val summary: String, val destructive: Boolean) {
        internal val answer = CompletableDeferred<Boolean>()
    }

    private val queue = Mutex()
    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    suspend fun confirm(summary: String, destructive: Boolean = false, subject: String = "action"): Decision =
        queue.withLock {
            val request = Request(summary, destructive)
            _pending.value = request
            val answer = try {
                withTimeoutOrNull(timeoutMs) { request.answer.await() }
            } finally {
                _pending.value = null
            }
            val decision = when (answer) {
                true -> Decision.ALLOWED
                false -> Decision.DECLINED
                null -> Decision.TIMED_OUT
            }
            record(
                "confirm:$subject",
                summary,
                if (decision == Decision.ALLOWED) ToolResult.Success("allowed") else ToolResult.Failure(decision.name.lowercase().replace('_', ' ')),
            )
            decision
        }

    /** The user's tap on the sheet. */
    fun answer(allowed: Boolean) {
        _pending.value?.answer?.complete(allowed)
    }

    override suspend fun check(tool: Tool, args: JsonObject): ToolResult? {
        val decision = confirm(
            summary = summarize(tool.definition.name, args),
            destructive = tool.sensitivity == ToolSensitivity.DESTRUCTIVE,
            subject = tool.definition.name,
        )
        return when (decision) {
            Decision.ALLOWED -> null
            Decision.DECLINED -> ToolResult.Failure("The user declined.")
            Decision.TIMED_OUT -> ToolResult.Failure("The user didn't answer in time, so nothing was done.")
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
        private const val MAX_VALUE_CHARS = 120

        /**
         * The tool and its arguments as the sheet shows them. A long text value (a mini app's HTML, a
         * layout) is cut to its start and its size, so the sheet's buttons stay on screen.
         */
        internal fun summarize(name: String, args: JsonObject): String {
            // Making an app is confirmed in the user's words, not as a wall of HTML.
            (args["title"] as? JsonPrimitive)?.takeIf { it.isString && name == "create_mini_app" }?.let {
                return "Add \"${it.content.take(60)}\" to your home screen as an app"
            }
            if (name == "update_mini_app") return "Change that app"
            val shown = JsonObject(args.mapValues { (_, value) ->
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: value.toString()
                if (text.length <= MAX_VALUE_CHARS) value
                else JsonPrimitive(text.take(MAX_VALUE_CHARS) + "… (${text.length} characters)")
            })
            return "$name $shown".trim()
        }
    }
}
