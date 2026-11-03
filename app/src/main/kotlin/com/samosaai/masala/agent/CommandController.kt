package com.samosaai.masala.agent

import com.samosaai.masala.actions.ActionDispatcher
import com.samosaai.masala.actions.ConfirmationGate
import com.samosaai.masala.audio.PttEngine
import com.samosaai.masala.core.agent.CommandFastPath
import com.samosaai.masala.core.agent.NameCorrector
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.llm.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

/**
 * Typed and spoken commands (plan Phase 4 M3). Simple ones (timers, alarms, open an app) skip the
 * model; a request for an app-making skill ("make me a snake game") opens its sheet; a request for a
 * persona's advice gets a "Continue in …" chip; the rest goes to a
 * [MasalaAgent] with every tool (a persona that matches is offered next to its answer). App-scoped, so a command goes on if home is left, and the agent
 * keeps the last few exchanges for follow-ups.
 */
class CommandController(
    private val scope: CoroutineScope,
    private val resolveApp: (String) -> MasalaAction.LaunchApp?,
    private val names: () -> List<String>,
    private val run: suspend (MasalaAction) -> ActionDispatcher.Outcome,
    private val skills: () -> List<MasalaSkill>,
    private val newAgent: () -> MasalaAgent,
    private val speech: SpeechInput? = null,
    private val speechStrings: SpeechStrings = SpeechStrings("", ""),
    /**
     * Turns a long conversation into a short summary the model can keep in its context
     * once the history outgrows the budget; null means compaction is off.
     */
    private val summarize: (suspend (String) -> String?)? = null,
    /** Called when a turn starts, so per-turn budgets (expert consults) reset with it. */
    private val onTurnStart: (() -> Unit)? = null,
    /** Tidies a spoken transcript before it is sent; null sends it as heard. */
    private val cleaner: TranscriptCleaner? = null,
    /** The context budget for this chat, in tokens; compaction fires at [MasalaAgent.COMPACT_AT_FRACTION] of it. */
    private val maxContextTokens: Int = MasalaAgent.DEFAULT_MAX_CONTEXT_TOKENS,
) {
    private val _state = MutableStateFlow<CommandState>(CommandState.Idle)
    /**
     * Sets the state and, for a finished turn, hands it to [onTurn] so it lands in the
     * conversation. Live states (listening, thinking) are not turns.
     */
    private fun publish(state: CommandState) {
        // The same finished state can be set twice (a synchronous start failure calls the
        // async handler and then the caller does): record its turn once.
        if (_state.value === state) return
        val turn = when (state) {
            is CommandState.Reply -> com.samosaai.masala.chat.ChatTurn(
                com.samosaai.masala.chat.TurnRole.MASALA, state.text, state.steps,
                experts = state.experts, results = state.results,
            )
            is CommandState.Error -> com.samosaai.masala.chat.ChatTurn(
                com.samosaai.masala.chat.TurnRole.ERROR, state.message,
            )
            else -> null
        }
        // Hand the turn over before the state wakes anyone: a reader of the state must never see a
        // reply that the session doesn't have yet (that race failed the CI suite once).
        turn?.let { onTurn?.invoke(it) }
        _state.value = state
    }

    val state: StateFlow<CommandState> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * Set when the user hides the chat mid-turn (sheet X, Back, drawer): the turn
     * keeps running and an ongoing notification carries the progress. Only an
     * explicit Stop ([dismiss]) cancels. Cleared by any new turn or focus.
     */
    private val _minimized = MutableStateFlow(false)
    val minimized: StateFlow<Boolean> = _minimized.asStateFlow()

    /**
     * This chat's "Allow all": its turns skip the confirmation sheet for anything that isn't
     * destructive. Every new chat and every switch to another one starts from the global default
     * (Settings → Permissions); coming back to the same chat (after minimizing) keeps it.
     */
    private var allowAllDefault = false
    private val _allowAll = MutableStateFlow(false)
    val allowAll: StateFlow<Boolean> = _allowAll.asStateFlow()

    /** The user flipped this chat's switch: the global default no longer moves it. */
    private var allowAllOverridden = false

    fun setAllowAll(on: Boolean) {
        allowAllOverridden = true
        _allowAll.value = on
    }

    /** The global default changed: chats start from it, and the open chat follows unless its switch was flipped. */
    fun setAllowAllDefault(on: Boolean) {
        allowAllDefault = on
        if (!allowAllOverridden) _allowAll.value = on
    }

    /** Hides the chat without cancelling the running turn. */
    fun minimize() {
        _minimized.value = true
    }

    /** The chat is visible again (or a new turn starts): auto-open may resume. */
    fun focus() {
        _minimized.value = false
    }

    /**
     * Every finished turn is handed here so the conversation can be stored and shown;
     * null until the app wires its chat store.
     */
    var onTurn: ((com.samosaai.masala.chat.ChatTurn) -> Unit)? = null

    /** The mic level while listening, for the listening overlay (UI refresh U4). */
    val micLevel: StateFlow<Float> get() = speech?.level ?: kotlinx.coroutines.flow.MutableStateFlow(0f)

    /** ms since the live take (re)started; the UI timer and the near-cap warning read this. */
    private val _listeningElapsed = MutableStateFlow(0L)
    val listeningElapsed: StateFlow<Long> = _listeningElapsed.asStateFlow()

    /** True once the live take is really capturing; the UI holds "Get ready…" until then. */
    val listeningReady: StateFlow<Boolean> get() = speech?.ready ?: neverReady

    private var ticker: Job? = null
    private var takeSinceMs = 0L
    private val neverReady = MutableStateFlow(false)

    private fun startTicker() {
        stopTicker()
        takeSinceMs = System.currentTimeMillis()
        _listeningElapsed.value = 0L
        ticker = scope.launch {
            // The Android recognizer has no engine-side cap (it restarts forever), so the
            // clock enforces the same 5-minute budget the API capture thread enforces itself.
            while (_state.value == CommandState.Listening) {
                kotlinx.coroutines.delay(500)
                val elapsed = System.currentTimeMillis() - takeSinceMs
                _listeningElapsed.value = elapsed
                if (elapsed >= com.samosaai.masala.audio.SttEngine.MAX_VAD_RECORDING_MS) pauseTake()
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private var history: List<ChatMessage> = emptyList()

    /** Prompt tokens the server last reported for this chat; 0 until a reply carries `usage`. */
    private var lastPromptTokens: Int = 0

    /**
     * Bumped whenever the conversation is replaced, so a run that was cancelled mid-flight
     * can't write its token count into the chat that replaced it.
     */
    private var generation: Int = 0
    private var readAloud: CommandState.Reply? = null

    /**
     * True the first time it is asked about [reply], so "Read replies aloud" speaks a reply once,
     * however often a card showing it is composed again.
     */
    fun claimReadAloud(reply: CommandState.Reply): Boolean {
        if (readAloud === reply) return false
        readAloud = reply
        return true
    }

    /** Text the input card starts with, taken once (the Create buttons in Settings). */
    private var draft = ""
    fun takeDraft(): String = draft.also { draft = "" }

    /** Shows the card with its input field and nothing else. */
    fun openInput(prefill: String = "") {
        job?.cancel()
        draft = prefill
        // Asking to type is coming back: a chat minimized earlier would hide the card.
        _minimized.value = false
        _state.value = CommandState.Input
    }

    fun dismiss() {
        job?.cancel()
        speech?.cancel()
        stopTicker()
        _minimized.value = false
        _listeningElapsed.value = 0L
        _state.value = CommandState.Idle
    }

    /** [spoken] transcripts get their names corrected against apps, modes and skills the phone knows. */
    fun ask(text: String, spoken: Boolean = false, images: List<String> = emptyList()) {
        _minimized.value = false
        val raw = text.trim()
        if (raw.isEmpty() && images.isEmpty()) return openInput()
        job?.cancel()
        val prompt = if (spoken) NameCorrector(names()).correct(raw) else raw
        _state.value = CommandState.Thinking(if (prompt.isBlank()) com.samosaai.masala.chat.IMAGE_PLACEHOLDER else prompt)
        job = scope.launch {
            // Compact first: the summary must appear above the turn that triggered it, not
            // below it, and the new prompt must not be part of what gets summarised.
            compactIfNeeded()
            onTurn?.invoke(com.samosaai.masala.chat.ChatTurn(com.samosaai.masala.chat.TurnRole.USER, if (prompt.isBlank()) com.samosaai.masala.chat.IMAGE_PLACEHOLDER else prompt))
            handle(prompt, spoken, images)
        }
    }

    /** Tap on the mic. Returns false if listening can't start. */
    fun startListening(): Boolean {
        val input = speech ?: return false
        job?.cancel()
        // The specific reason first; async errors during listening update the card too.
        var reason: String? = null
        val async: (String) -> Unit = { reason = it; publish(CommandState.Error("", it)) }
        if (!input.start(async)) {
            publish(CommandState.Error("", reason ?: "Speech recognition isn't available"))
            return false
        }
        // A take that ends by itself must still leave Listening: a dead capture shows
        // its error at once, the 5-minute cap pauses with its audio kept (never auto-sends).
        input.setTakeAutoEndListener { end ->
            when (end) {
                is PttEngine.TakeAutoEnd.Failed -> async(end.message)
                PttEngine.TakeAutoEnd.CapReached -> pauseTake()
            }
        }
        _state.value = CommandState.Listening
        startTicker()
        return true
    }

    /** True while a pause is settling; the clock must not stack a second one. */
    private var pauseInflight = false

    /**
     * The cap paused the take (or the clock did on the Android path): the capture stops
     * but its audio/transcript is kept. The UI offers Continue / Send / Cancel from here.
     *
     * The engine stop is awaited before advertising Paused: Continue is only offered once
     * the recorder/recognizer is really down, so resume can never race a half-stopped
     * take into an auto-send. Send/Cancel keep working meanwhile (the state is still
     * Listening), and if the pause itself fails the take is sent instead of stranded.
     */
    private fun pauseTake() {
        val input = speech ?: return
        if (_state.value != CommandState.Listening || pauseInflight) return
        pauseInflight = true
        job = scope.launch {
            val ok = try {
                // API cap path already stopped its thread (a no-op true); the Android
                // clock path stops its recognizer here, keeping the transcript.
                input.pause()
            } catch (_: Exception) {
                false
            } finally {
                pauseInflight = false
            }
            if (_state.value != CommandState.Listening) return@launch
            if (ok) {
                stopTicker()
                _state.value = CommandState.ListeningPaused
            } else {
                stopListening()
            }
        }
    }

    /**
     * Continue a paused take: capture restarts into the same take with a fresh 5-minute
     * budget. When the recorder won't restart, fall back to sending what was kept.
     */
    fun continueListening() {
        val input = speech ?: return
        if (_state.value != CommandState.ListeningPaused) return
        _state.value = CommandState.Listening
        startTicker()
        job = scope.launch {
            if (!input.resume()) stopListening()
        }
    }

    /**
     * The listening overlay's send button: ends the take now and transcribes it.
     * Listening ends on Send, Cancel, a dead capture — or the 5-minute cap, which pauses
     * instead of sending.
     */
    fun sendNow() = stopListening()

    /**
     * Continues a stored conversation: its turns become the agent's context, so
     * "go back to that chat and keep talking" answers with what came before.
     */
    fun restoreSession(turns: List<com.samosaai.masala.chat.ChatTurn>, sameChat: Boolean = false) {
        job?.cancel()
        speech?.cancel()
        // A summary written earlier stands in for everything before it, so reopening a very
        // long chat does not hand the model its whole transcript.
        val summaryIndex = turns.indexOfLast { it.role == com.samosaai.masala.chat.TurnRole.SYSTEM }
        val summary = turns.getOrNull(summaryIndex)?.text
        // Only what came after the summary: everything before it is already condensed, and
        // replaying it would put the transcript right back over the budget.
        val spoken = turns.drop(summaryIndex + 1)
            .filter {
                it.text.isNotBlank() &&
                    it.role != com.samosaai.masala.chat.TurnRole.ERROR &&
                    it.role != com.samosaai.masala.chat.TurnRole.SYSTEM
            }
            .map {
                com.samosaai.masala.llm.ChatMessage(
                    if (it.role == com.samosaai.masala.chat.TurnRole.USER) "user" else "assistant",
                    JsonPrimitive(it.text),
                )
            }
        if (!sameChat) {
            allowAllOverridden = false
            _allowAll.value = allowAllDefault
        }
        // A different conversation starts its own budget: the last chat's prompt size says
        // nothing about this one's, and a stale large number would compact a short chat at once.
        generation++
        lastPromptTokens = 0
        history = listOfNotNull(summary?.let { com.samosaai.masala.llm.ChatMessage("assistant", JsonPrimitive(it)) }) + spoken
        _state.value = CommandState.Idle
    }

    /**
     * Keeps the model's context bounded without losing the thread: once the history is long,
     * it becomes one summary plus the recent turns. The visible transcript is untouched, and
     * the summary is written into it as a system note so the change is never silent.
     *
     * The prompt that triggered compaction is not in [history] yet ([ask] compacts first), so the
     * whole history is summarised and the new question is simply appended afterwards.
     */
    private suspend fun compactIfNeeded() {
        if (history.isEmpty()) return
        val tokens = maxOf(lastPromptTokens, AgentHistory.tokensOf(history))
        if (tokens < (maxContextTokens * MasalaAgent.COMPACT_AT_FRACTION).toInt()) return
        val readable = history.joinToString("\n\n") { message ->
            // A tool-call turn has no text of its own; naming the calls keeps the summary honest.
            val body = message.textContent.ifBlank {
                message.toolCalls?.joinToString(", ") { "called ${it.function.name}" }.orEmpty()
            }
            "[${message.role}] $body"
        }
        val summary = summarize?.invoke(readable)?.trim()?.takeIf { it.isNotBlank() }
        if (summary != null) {
            history = listOf(com.samosaai.masala.llm.ChatMessage("assistant", JsonPrimitive(summary)))
            onTurn?.invoke(com.samosaai.masala.chat.ChatTurn(com.samosaai.masala.chat.TurnRole.SYSTEM, summary))
        } else {
            // No summariser, or it failed: keep what fits rather than sending everything.
            history = AgentHistory.trimToTokens(history, maxContextTokens)
        }
    }

    /**
     * Send ends the take now; what was said still runs. Works from Listening and from the
     * cap-paused state — both transcribe what was kept. Only the final transcript is used.
     */
    fun stopListening() {
        val input = speech ?: return
        if (_state.value != CommandState.Listening && _state.value != CommandState.ListeningPaused) return
        stopTicker()
        // Sending is not instantaneous: the take still has to be transcribed and cleaned.
        // A blank-prompt Thinking is the "Transcribing…" row until the text lands.
        _state.value = CommandState.Thinking("")
        job = scope.launch {
            input.stop()
                .onSuccess { heard ->
                    // A command the fast path already matches as heard ("open camera") skips
                    // the cleanup call: it would only add the model's round trip.
                    val quick = heard.isBlank() ||
                        CommandFastPath.match(NameCorrector(names()).correct(heard.trim()), resolveApp) != null
                    val text = if (quick) heard else cleaner?.clean(heard) ?: heard
                    ask(text, spoken = true)
                }
                .onFailure { publish(CommandState.Error("", friendlySttMessage(it.message, speechStrings))) }
        }
    }

    private suspend fun handle(prompt: String, spoken: Boolean, images: List<String> = emptyList()) =
        withContext(ConfirmationGate.AllowAll { _allowAll.value }) { runTurn(prompt, spoken, images) }

    private suspend fun runTurn(prompt: String, spoken: Boolean, images: List<String>) {
        val gen = generation
        onTurnStart?.invoke()
        CommandFastPath.match(prompt, resolveApp)?.let { action ->
            publish(stateFor(prompt, run(action), spoken))
            return
        }
        // Advice in an expert's area goes to the agent too: it consults the expert itself, in place.
        val agent = newAgent().also { it.restore(history) }
        val steps = mutableListOf<String>()
        // Who answered, in order of first use; the badge on the reply and the stored turn.
        val consulted = linkedMapOf<String, com.samosaai.masala.chat.ExpertCredit>()
        // Access a step asked for and did not have: the answer still comes, plus a Grant row.
        val missingAccess = linkedSetOf<String>()
        // What the turn made, for the card under the answer.
        val results = linkedMapOf<String, com.samosaai.masala.chat.ChatResult>()
        // Attached photos ride as vision parts; image bytes stay in-memory, the stored
        // turn keeps the placeholder text so reopened chats still read sensibly.
        val userMessage = if (images.isEmpty()) ChatMessage("user", JsonPrimitive(prompt))
        else com.samosaai.masala.llm.attachmentsUserMessage(prompt, emptyList(), images)
        // What's shown while working: an image-only turn still names itself.
        val display = if (prompt.isBlank() && images.isNotEmpty()) com.samosaai.masala.chat.IMAGE_PLACEHOLDER else prompt
        try {
            agent.run(userMessage).collect { event ->
                when (event) {
                    is AgentEvent.ToolCall -> {
                        val expert = consultCredit(event.name, event.args, skills())
                        if (expert == null) {
                            steps += event.name
                        } else {
                            consulted[expert.id] = expert
                            steps += "Consulting ${expert.title}"
                        }
                        _state.value = CommandState.Thinking(display, steps.toList())
                    }
                    is AgentEvent.Results -> event.items.forEach { item ->
                        results[item.ref] = com.samosaai.masala.chat.ChatResult(item.kind, item.title, item.ref)
                    }
                    is AgentEvent.Done -> publish(
                        CommandState.Reply(
                            prompt,
                            event.text.ifBlank { "Done." },
                            steps.toList(),
                            spoken = spoken,
                            experts = consulted.values.toList(),
                            capabilities = missingAccess.toList(),
                            results = results.values.toList(),
                        ),
                    )
                    is AgentEvent.Failed -> publish(CommandState.Error(prompt, friendlyModelError(event.message)))
                    // Recorded, not fatal: the model keeps going and finishes around the gap.
                    is AgentEvent.NeedsCapability -> missingAccess += event.capability
                    AgentEvent.StepLimit -> publish(CommandState.Error(prompt, "That took too many steps. Try asking in smaller pieces."))
                    else -> Unit
                }
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            // A chat switch while this run was in flight owns the history now; leave it alone.
            if (gen == generation) {
                // The whole conversation, not the agent's five-exchange window: otherwise
                // compaction could never summarise turns the agent has already let go of.
                history = agent.transcript
                // Real server numbers when it reports them, the character estimate otherwise.
                lastPromptTokens = agent.lastPromptTokens ?: AgentHistory.tokensOf(history)
            }
        }
    }

    private fun stateFor(prompt: String, outcome: ActionDispatcher.Outcome, spoken: Boolean, done: String? = null): CommandState = when (outcome) {
        is ActionDispatcher.Outcome.Done -> CommandState.Reply(prompt, done ?: outcome.message?.takeIf { it.isNotBlank() } ?: "Done.", spoken = spoken)
        ActionDispatcher.Outcome.Declined -> CommandState.Reply(prompt, "Okay, I didn't do it.", spoken = spoken)
        is ActionDispatcher.Outcome.Failed -> CommandState.Error(prompt, outcome.message)
        is ActionDispatcher.Outcome.NeedsCapability -> CommandState.Error(prompt, outcome.reason, outcome.marker)
    }
}

/**
 * What a failed model call should read like. "HTTP 400" tells a person nothing actionable, so the
 * common ones get a sentence that names the fix; anything else stays as the server said it.
 */
internal fun friendlyModelError(raw: String?): String {
    val message = raw?.trim().orEmpty()
    if (message.isEmpty()) return "The model didn't answer. Try again."
    // Only a real status line counts: "tool returned 400 items" is not an HTTP failure.
    val code = Regex("""\bHTTP[\s:]+(\d{3})\b""").find(message)?.groupValues?.get(1)
    return when (code) {
        "400" -> "The model refused that request (HTTP 400). If this chat is long, start a new one and ask again there."
        "401", "403" -> "The model server refused the key. Check it in Settings → Models & keys."
        "413" -> "That was too much for one request. Try again with a shorter message or fewer files."
        "429" -> "The model server is busy or rate-limited. Try again in a moment."
        "500", "502", "503", "504" -> "The model server had a problem (HTTP $code). Try again in a moment."
        else -> message
    }
}

/**
 * The expert a tool call consulted, or null when the call isn't one. The title comes from the
 * installed skill, so the thread names it the way the user knows it.
 */
internal fun consultCredit(
    name: String,
    args: kotlinx.serialization.json.JsonObject,
    skills: List<MasalaSkill>,
): com.samosaai.masala.chat.ExpertCredit? {
    if (name != "consult_expert") return null
    val id = (args["skill_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return null
    val title = skills.firstOrNull { it.id == id }?.title?.ifBlank { id } ?: id
    return com.samosaai.masala.chat.ExpertCredit(id, title)
}
