package com.samosaai.masala.actions

import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.tool.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Asks the user a question with options, mid-run (the `question` tool). The chat shows [pending]
 * as a sheet; [answer] resolves it with an option index or free text, a dismissal counts as "no".
 * Like the confirmation gate, one question at a time and a timeout counts as unanswered.
 */
class QuestionGate(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {

    class Request(val question: String, val options: List<String>) {
        internal val answer = CompletableDeferred<Answer>()
    }

    sealed interface Answer {
        data class Option(val index: Int, val text: String) : Answer
        data class Custom(val text: String) : Answer
        data object Dismissed : Answer
    }

    private val queue = Mutex()
    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    suspend fun ask(question: String, options: List<String>): Answer = queue.withLock {
        val request = Request(question, options)
        _pending.value = request
        try {
            withTimeoutOrNull(timeoutMs) { request.answer.await() } ?: Answer.Dismissed
        } finally {
            _pending.value = null
        }
    }

    /** An option tap. Out-of-range taps are ignored, so a stale sheet can't answer wrong. */
    fun answer(request: Request, index: Int) {
        if (_pending.value !== request) return
        if (index in request.options.indices) {
            request.answer.complete(Answer.Option(index, request.options[index]))
        }
    }

    /** Free text, when the user's answer isn't one of the options. Blank text is ignored. */
    fun answer(request: Request, text: String) {
        if (text.isNotBlank() && _pending.value === request) {
            request.answer.complete(Answer.Custom(text.trim()))
        }
    }

    /** A swipe-away or back press. Only the request on screen can be dismissed. */
    fun dismiss(request: Request) {
        if (_pending.value === request) request.answer.complete(Answer.Dismissed)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val MAX_OPTIONS = 10

        /** The tool result for an answer, telling the model plainly what the user picked. */
        fun resultOf(answer: Answer): ToolResult = when (answer) {
            is Answer.Option -> ToolResult.Success("The user picked option ${answer.index + 1}: ${answer.text}")
            is Answer.Custom -> ToolResult.Success("The user answered in their own words: ${answer.text}")
            Answer.Dismissed -> ToolResult.Failure("The user didn't answer, so nothing was picked. Carry on without it.")
        }

        /** The tool result for [ActionResult]-shaped callers (tests, sheets without a run). */
        fun actionResultOf(answer: Answer): ActionResult = when (val r = resultOf(answer)) {
            is ToolResult.Success -> ActionResult.ok(r.output)
            is ToolResult.Failure -> ActionResult.error(r.message)
            is ToolResult.NeedsCapability -> ActionResult.error(r.reason)
        }
    }
}
