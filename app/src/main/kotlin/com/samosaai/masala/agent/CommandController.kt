package com.samosaai.masala.agent

import com.samosaai.masala.actions.ActionDispatcher
import com.samosaai.masala.actions.ConfirmationGate
import com.samosaai.masala.audio.PttEngine
import com.samosaai.masala.chat.modelTextWithImagePaths
import com.samosaai.masala.core.agent.CommandFastPath
import com.samosaai.masala.core.agent.NameCorrector
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.llm.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.UUID

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
    /**
     * Writes a send's images to app-private storage and returns their absolute paths, so the
     * model text can name files the media tools accept. Null (tests, previews) sends vision
     * parts only. Never throws out of [ask]: a failure just means no path lines.
     */
    private val materializeAttachments: (suspend (messageId: String, images: List<String>) -> List<String>)? = null,
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
                com.samosaai.masala.chat.TurnRole.ERROR, state.message, steps = state.steps,
            )
            else -> null
        }
        // Hand the turn over before the state wakes anyone: a reader of the state must never see a
        // reply that the session doesn't have yet (that race failed the CI suite once).
        turn?.let { onTurn?.invoke(it) }
        _state.value = state
    }

    val state: StateFlow<CommandState> = _state.asStateFlow()

    /**
     * True while a turn is live (thinking, listening, or a take paused at the cap). One chat at a
     * time: while this is true no new turn can start and the thread cannot be replaced, so a
     * running turn can never be cancelled out from under itself (the crash on + / History / a
     * second send).
     */
    val busy: StateFlow<Boolean> = state
        .map { it.isLive() }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** The same answer, read synchronously: a guard must not wait for a flow dispatch. */
    private fun isBusy(): Boolean = _state.value.isLive()

    private fun CommandState.isLive(): Boolean =
        this is CommandState.Thinking || this == CommandState.Listening || this == CommandState.ListeningPaused

    private var job: Job? = null

    /**
     * The tool steps the current turn has taken, readable from outside [runTurn] so a failure or
     * a Stop can record how far it got (and a reopened chat shows the trace).
     */
    @Volatile
    private var turnSteps: List<String> = emptyList()

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

    /**
     * Shows the card with its input field and nothing else. Returns false (and changes nothing)
     * while a turn is live: replacing the working card would have to cancel that turn, which is
     * what used to crash the app.
     */
    fun openInput(prefill: String = ""): Boolean {
        if (isBusy()) return false
        job?.cancel()
        draft = prefill
        // Asking to type is coming back: a chat minimized earlier would hide the card.
        _minimized.value = false
        _state.value = CommandState.Input
        return true
    }

    /**
     * Stop: cancels a running turn and records what it had done as a [TurnRole.STATUS] note, so
     * reopening the chat shows the trace instead of only the user's message. Nothing was sent
     * while listening, so a cancelled take records nothing.
     */
    fun stop() {
        val thinking = _state.value as? CommandState.Thinking
        dismiss()
        // A blank prompt is the "Transcribing…" state between Send and the transcript: nothing
        // has been sent to the model yet, so there is no turn to note (and no steps of its own).
        if (thinking == null || thinking.prompt.isBlank()) return
        val steps = if (thinking.steps.isNotEmpty()) thinking.steps else turnSteps
        onTurn?.invoke(
            com.samosaai.masala.chat.ChatTurn(
                com.samosaai.masala.chat.TurnRole.STATUS,
                STOPPED_NOTE,
                steps = steps,
            ),
        )
    }

    fun dismiss() {
        job?.cancel()
        speech?.cancel()
        stopTicker()
        _minimized.value = false
        _listeningElapsed.value = 0L
        _state.value = CommandState.Idle
    }

    /**
     * Sends a typed or spoken message. Returns false when a turn is already running: cancelling it
     * to start another is what used to crash the app, and losing a half-finished reply is worse
     * than asking the user to wait. Blank text opens the input card instead.
     *
     * [spoken] transcripts get their names corrected against apps, modes and skills the phone knows.
     */
    fun ask(text: String, spoken: Boolean = false, images: List<String> = emptyList()): Boolean {
        val raw = text.trim()
        if (raw.isEmpty() && images.isEmpty()) return openInput()
        if (isBusy()) return false
        startTurn(raw, spoken, images)
        return true
    }

    /** Starts a turn whatever [busy] says: the take that just ended is the only live work. */
    private fun startTurn(raw: String, spoken: Boolean, images: List<String>) {
        _minimized.value = false
        job?.cancel()
        val prompt = if (spoken) NameCorrector(names()).correct(raw) else raw
        _state.value = CommandState.Thinking(if (prompt.isBlank()) com.samosaai.masala.chat.IMAGE_PLACEHOLDER else prompt)
        turnSteps = emptyList()
        // One stable folder for this send's images, so the model text can name them.
        val messageId = if (images.isEmpty()) null else UUID.randomUUID().toString()
        val transcript = if (prompt.isBlank()) com.samosaai.masala.chat.IMAGE_PLACEHOLDER else prompt
        job = scope.launch {
            // Everything about the turn lives behind this boundary: a tool, the model client or
            // the chat store failing must show an error in the thread, never kill the process
            // (and never lose the steps it had already run).
            try {
                // Compact first: the summary must appear above the turn that triggered it, not
                // below it, and the new prompt must not be part of what gets summarised.
                compactIfNeeded()
                // The transcript keeps the placeholder; only the model text carries path lines,
                // so the thread still reads the same after a reload.
                val paths = if (messageId == null) emptyList()
                else runCatching { materializeAttachments?.invoke(messageId, images) }.getOrNull().orEmpty()
                onTurn?.invoke(
                    com.samosaai.masala.chat.ChatTurn(
                        com.samosaai.masala.chat.TurnRole.USER, transcript, attachmentPaths = paths,
                    ),
                )
                handle(modelTextWithImagePaths(prompt, paths), spoken, images, transcript)
            } catch (e: CancellationException) {
                // Stop, a chat switch or process teardown: not a failure, and the turn is
                // recorded by whoever cancelled it.
                throw e
            } catch (e: Throwable) {
                // Logging must never be the thing that fails: JVM tests don't mock android.util.Log.
                runCatching { android.util.Log.e("CommandController", "turn failed", e) }
                publish(CommandState.Error(transcript, friendlyTurnError(e), steps = turnSteps))
            }
        }
    }

    /** Tap on the mic. Returns false if listening can't start. */
    fun startListening(): Boolean {
        val input = speech ?: return false
        // The mic is another way into the same single chat: it can't cancel a running turn.
        if (isBusy()) return false
        job?.cancel()
        // The specific reason first; async errors during listening update the card too.
        var reason: String? = null
        // The engine may report the failure through this callback *and* return false; the card
        // must get one error, not a duplicate turn.
        var reported = false
        val async: (String) -> Unit = { reason = it; reported = true; publish(CommandState.Error("", it)) }
        if (!input.start(async)) {
            if (!reported) publish(CommandState.Error("", reason ?: "Speech recognition isn't available"))
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
                // Reopened chats keep no image bytes, but the materialized files may still be on
                // disk: re-supply the paths that survived so media tools can read them, and leave
                // out whatever is gone rather than naming files that don't exist.
                val text = if (it.role == com.samosaai.masala.chat.TurnRole.USER && it.attachmentPaths.isNotEmpty()) {
                    modelTextWithImagePaths(it.text, it.attachmentPaths.filter { path -> File(path).isFile })
                } else {
                    it.text
                }
                com.samosaai.masala.llm.ChatMessage(
                    if (it.role == com.samosaai.masala.chat.TurnRole.USER) "user" else "assistant",
                    JsonPrimitive(text),
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
        // No model turn has started: a Stop here must not report the previous turn's steps.
        turnSteps = emptyList()
        job = scope.launch {
            input.stop()
                .onSuccess { heard ->
                    // A command the fast path already matches as heard ("open camera") skips
                    // the cleanup call: it would only add the model's round trip.
                    val quick = heard.isBlank() ||
                        CommandFastPath.match(NameCorrector(names()).correct(heard.trim()), resolveApp) != null
                    val text = if (quick) heard else cleaner?.clean(heard) ?: heard
                    // The take just ended, so this is the only live work: start it directly
                    // rather than through ask's one-at-a-time guard.
                    startTurn(text.trim(), spoken = true, images = emptyList())
                }
                .onFailure { publish(CommandState.Error("", friendlySttMessage(it.message, speechStrings))) }
        }
    }

    private suspend fun handle(prompt: String, spoken: Boolean, images: List<String> = emptyList(), transcript: String = prompt) =
        withContext(ConfirmationGate.AllowAll { _allowAll.value }) { runTurn(prompt, spoken, images, transcript) }

    /**
     * [prompt] is the model text (with image path lines when the send had images); [transcript]
     * is what the thread shows, without them. Fast-path matching, the working title and the
     * reply/error prompts all read the transcript, so paths never leak into the UI.
     */
    private suspend fun runTurn(prompt: String, spoken: Boolean, images: List<String>, transcript: String = prompt) {
        val gen = generation
        onTurnStart?.invoke()
        CommandFastPath.match(transcript, resolveApp)?.let { action ->
            publish(stateFor(transcript, run(action), spoken))
            return
        }
        // Advice in an expert's area goes to the agent too: it consults the expert itself, in place.
        val agent = newAgent().also { it.restore(history) }
        val steps = mutableListOf<String>()
        turnSteps = emptyList()
        // Who answered, in order of first use; the badge on the reply and the stored turn.
        val consulted = linkedMapOf<String, com.samosaai.masala.chat.ExpertCredit>()
        // Access a step asked for and did not have: the answer still comes, plus a Grant row.
        val missingAccess = linkedSetOf<String>()
        // What the turn made, for the card under the answer.
        val results = linkedMapOf<String, com.samosaai.masala.chat.ChatResult>()
        // Attached photos ride as vision parts; the model text also names their files so
        // path-taking tools can read them. The stored turn keeps the transcript text.
        val userMessage = if (images.isEmpty()) ChatMessage("user", JsonPrimitive(prompt))
        else com.samosaai.masala.llm.attachmentsUserMessage(prompt, emptyList(), images)
        // What's shown while working: paths never reach the UI.
        val display = transcript
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
                        turnSteps = steps.toList()
                        _state.value = CommandState.Thinking(display, steps.toList())
                    }
                    is AgentEvent.Results -> event.items.forEach { item ->
                        results[item.ref] = com.samosaai.masala.chat.ChatResult(item.kind, item.title, item.ref)
                    }
                    is AgentEvent.Done -> publish(
                        CommandState.Reply(
                            transcript,
                            event.text.ifBlank { "Done." },
                            steps.toList(),
                            spoken = spoken,
                            experts = consulted.values.toList(),
                            capabilities = missingAccess.toList(),
                            results = results.values.toList(),
                        ),
                    )
                    is AgentEvent.Failed -> publish(
                        CommandState.Error(transcript, friendlyModelError(event.message), steps = steps.toList()),
                    )
                    // Recorded, not fatal: the model keeps going and finishes around the gap.
                    is AgentEvent.NeedsCapability -> missingAccess += event.capability
                    AgentEvent.StepLimit -> publish(
                        CommandState.Error(
                            transcript,
                            "That took too many steps. Try asking in smaller pieces.",
                            steps = steps.toList(),
                        ),
                    )
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

/** Phrases a socket error carries when the network goes away mid-turn. */
private val NETWORK_WORDS = listOf(
    "connection abort", "connection reset", "failed to connect", "unable to resolve host",
    "connection refused", "broken pipe", "network is unreachable", "timeout",
)

/** What the thread says when the user stops a running turn (the steps follow under it). */
private const val STOPPED_NOTE = "Stopped. This is what it had done so far."

/**
 * A failure that isn't a model status line: name the step-free case plainly, otherwise keep the
 * server's words so the user can act on them.
 */
internal fun friendlyTurnError(error: Throwable): String {
    if (error is java.io.IOException) {
        return "The connection dropped while working on that. Check the network and try again."
    }
    val message = error.message?.trim().orEmpty()
    return friendlyModelError(message).takeIf { it != "The model didn't answer. Try again." }
        ?: "Something went wrong while working on that."
}

/**
 * What a failed model call should read like. "HTTP 400" tells a person nothing actionable, so the
 * common ones get a sentence that names the fix; anything else stays as the server said it.
 */
internal fun friendlyModelError(raw: String?): String {
    val message = raw?.trim().orEmpty()
    if (message.isEmpty()) return "The model didn't answer. Try again."
    // A dropped socket reaches here as its raw errno text ("Software caused connection abort"),
    // because the agent turns any exception into a Failed event. Say it in words.
    if (NETWORK_WORDS.any { message.contains(it, ignoreCase = true) }) {
        return "The connection dropped while working on that. Check the network and try again."
    }
    // Not the conversation: one step returned more than the model can read at once (a huge file,
    // a base64 image, a directory listing). The agent drops the oversized output and retries, so
    // seeing this means even the trimmed step was too big.
    if (AgentHistory.isContextOverflow(message)) {
        return "That step returned more data than the model can read at once. Try again with a narrower " +
            "request — one file, a folder, or a search instead of a whole directory."
    }
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
