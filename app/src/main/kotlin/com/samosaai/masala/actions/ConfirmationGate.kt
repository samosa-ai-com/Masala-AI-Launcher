package com.samosaai.masala.actions

import com.samosaai.masala.core.tool.FunctionTool
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
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

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

    /**
     * The question on screen: [summary] in plain words, and the exact call in [details] behind a tap
     * (null when the summary says it all). [destructive] asks in a stronger voice and says something is lost.
     */
    class Request(val summary: String, val destructive: Boolean, val details: String? = null) {
        internal val answer = CompletableDeferred<Boolean>()
    }

    private val queue = Mutex()
    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    /**
     * A chat's "Allow all" switch, carried in the coroutine context of that chat's turn so it never
     * reaches other chats or background runs. Read at ask time, so flipping it mid-turn applies to the
     * next question. Destructive questions are always asked.
     */
    class AllowAll(val enabled: () -> Boolean) : AbstractCoroutineContextElement(AllowAll) {
        companion object Key : CoroutineContext.Key<AllowAll>
    }

    suspend fun confirm(
        summary: String,
        destructive: Boolean = false,
        subject: String = "action",
        details: String? = null,
    ): Decision {
        // The log keeps the exact call when there is one, so an audit sees what ran, not the sentence.
        val logged = details ?: summary
        if (!destructive && coroutineContext[AllowAll]?.enabled?.invoke() == true) {
            record("confirm:$subject", logged, ToolResult.Success("allowed (allow all)"))
            return Decision.ALLOWED
        }
        return queue.withLock {
            val request = Request(summary, destructive, details?.takeIf { it != summary })
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
                logged,
                if (decision == Decision.ALLOWED) ToolResult.Success("allowed") else ToolResult.Failure(decision.name.lowercase().replace('_', ' ')),
            )
            decision
        }
    }

    /** The user's tap on the sheet. */
    fun answer(allowed: Boolean) {
        _pending.value?.answer?.complete(allowed)
    }

    override suspend fun check(tool: Tool, args: JsonObject): ToolResult? {
        val name = tool.definition.name
        // A tool's own sentence is already plain and says what matters (a widget's hosts): it is shown
        // and logged as is. Otherwise the sheet gets a sentence and the exact call goes under Details.
        val own = (tool as? FunctionTool)?.confirmSummary?.invoke(args)
        val decision = confirm(
            summary = own ?: describe(name, args),
            destructive = tool.sensitivity == ToolSensitivity.DESTRUCTIVE,
            subject = name,
            details = if (own == null) summarize(name, args) else null,
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
        private const val MAX_QUOTED_CHARS = 80

        /** Arguments that name what a call is about, in the order [describe] looks for one. */
        private val NAMING_ARGS = listOf("label", "title", "name", "app", "text", "message", "query", "id")

        /**
         * The call as one plain sentence for the sheet: what it does and, when an argument names it, to
         * what. Never JSON: the exact call is shown under Details and logged as [summarize] writes it.
         */
        internal fun describe(name: String, args: JsonObject): String {
            fun arg(key: String): String? =
                (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
            fun quoted(text: String) = "\u201C" + (if (text.length > MAX_QUOTED_CHARS) text.take(MAX_QUOTED_CHARS).trimEnd() + "…" else text) + "\u201D"
            plainSentence(name, args)?.let { return it }
            when (name) {
                "edit_layout" -> {
                    val all = (args["replace_all"] as? JsonPrimitive)?.content == "true"
                    val what = if (all) "Replace your whole home screen" else "Change your home screen"
                    return arg("label")?.let { "$what: ${quoted(it)}" } ?: what
                }
                "set_mode" -> {
                    val on = (args["on"] as? JsonPrimitive)?.content != "false"
                    val mode = arg("id")?.let { "the ${quoted(it)} mode" } ?: "a mode"
                    return "Turn $mode ${if (on) "on" else "off"}"
                }
            }
            val action = name.split('_').filter { it.isNotEmpty() }.joinToString(" ")
                .replaceFirstChar { it.uppercase() }.ifEmpty { "Run an action" }
            val subject = NAMING_ARGS.firstNotNullOfOrNull { key -> arg(key)?.takeIf { '\n' !in it && !it.startsWith("{") } }
            return if (subject != null) "$action: ${quoted(subject)}" else action
        }

        /** Calls that already read as a sentence, worded the same on the sheet and in the log. */
        private fun plainSentence(name: String, args: JsonObject): String? {
            fun arg(key: String): String? =
                (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
            return when (name) {
                // Making an app is confirmed in the user's words, not as a wall of HTML.
                "create_mini_app" -> arg("title")?.take(60)?.let { "Add \"$it\" to your home screen as an app" }
                    ?: "Add a new app to your home screen"
                "update_mini_app" -> "Change that app"
                "place_widget" -> arg("id")?.let { "Put \"$it\" on home" } ?: "Put a widget on home"
                "remove_widget" -> arg("id")?.let { "Take \"$it\" off home" } ?: "Take a widget off home"
                "delete_widget" -> arg("id")?.let { "Delete the \"$it\" widget everywhere" } ?: "Delete a widget everywhere"
                "add_stream_source" -> arg("name")?.let { "Add the \"$it\" camera" } ?: "Add a camera or stream"
                else -> null
            }
        }

        /**
         * The tool and its arguments as the sheet shows them. A long text value (a mini app's HTML, a
         * layout) is cut to its start and its size, so the sheet's buttons stay on screen.
         */
        internal fun summarize(name: String, args: JsonObject): String {
            plainSentence(name, args)?.let { return it }
            val shown = JsonObject(args.mapValues { (_, value) ->
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: value.toString()
                if (text.length <= MAX_VALUE_CHARS) value
                else JsonPrimitive(text.take(MAX_VALUE_CHARS) + "… (${text.length} characters)")
            })
            return "$name $shown".trim()
        }
    }
}
