package com.samosaai.masala.agent

import com.samosaai.masala.actions.ActionDispatcher
import com.samosaai.masala.core.agent.CommandFastPath
import com.samosaai.masala.core.agent.NameCorrector
import com.samosaai.masala.core.agent.SkillRouter
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
        _state.value = state
        val turn = when (state) {
            is CommandState.Reply -> com.samosaai.masala.chat.ChatTurn(
                com.samosaai.masala.chat.TurnRole.MASALA, state.text, state.steps,
            )
            is CommandState.Error -> com.samosaai.masala.chat.ChatTurn(
                com.samosaai.masala.chat.TurnRole.ERROR, state.message,
            )
            else -> null
        }
        turn?.let { onTurn?.invoke(it) }
    }

    val state: StateFlow<CommandState> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * Every finished turn is handed here so the conversation can be stored and shown;
     * null until the app wires its chat store.
     */
    var onTurn: ((com.samosaai.masala.chat.ChatTurn) -> Unit)? = null

    /** True while a hold-to-talk session runs: the release sends, not a detected pause. */
    private val _walkieTalkie = MutableStateFlow(false)
    val walkieTalkie: kotlinx.coroutines.flow.StateFlow<Boolean> = _walkieTalkie.asStateFlow()

    /** The mic level and live words while listening, for the listening overlay (UI refresh U4). */
    val micLevel: StateFlow<Float> get() = speech?.level ?: kotlinx.coroutines.flow.MutableStateFlow(0f)
    val liveWords: StateFlow<String> get() = speech?.partial ?: kotlinx.coroutines.flow.MutableStateFlow("")

    /**
     * Whether a tap may start a take that ends by itself here. False makes the mic
     * hold-to-talk only, which is what the on-device recognizer needs.
     */
    var handsFreeAvailable: () -> Boolean = { true }

    /** Seconds until the take ends by itself, when the provider works that way. */
    val autoSendIn: StateFlow<Int?> get() = speech?.autoSendIn ?: kotlinx.coroutines.flow.MutableStateFlow(null)
    private var history: List<ChatMessage> = emptyList()
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

    /** Shows the card with its input field and nothing else. */
    fun openInput() {
        job?.cancel()
        _state.value = CommandState.Input
    }

    fun dismiss() {
        job?.cancel()
        _walkieTalkie.value = false
        speech?.cancel()
        _state.value = CommandState.Idle
    }

    /** [spoken] transcripts get their names corrected against apps, modes and skills the phone knows. */
    fun ask(text: String, spoken: Boolean = false) {
        val raw = text.trim()
        if (raw.isEmpty()) return openInput()
        job?.cancel()
        val prompt = if (spoken) NameCorrector(names()).correct(raw) else raw
        _state.value = CommandState.Thinking(prompt)
        job = scope.launch {
            // Compact first: the summary must appear above the turn that triggered it, not
            // below it, and the new prompt must not be part of what gets summarised.
            compactIfNeeded()
            onTurn?.invoke(com.samosaai.masala.chat.ChatTurn(com.samosaai.masala.chat.TurnRole.USER, prompt))
            handle(prompt, spoken)
        }
    }

    /** Push-to-talk pressed. Returns false if listening can't start. */
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
        _walkieTalkie.value = true
        _state.value = CommandState.Listening
        return true
    }

    /**
     * Tap to talk (decision 6): listens until the speaker pauses, then runs the transcript. A second tap
     * while listening ends it early ([finishListening]).
     */
    fun tapToTalk() {
        val input = speech ?: return
        if (_state.value == CommandState.Listening) return finishListening()
        job?.cancel()
        _walkieTalkie.value = false
        _state.value = CommandState.Listening
        job = scope.launch {
            input.listenOnce()
                .onSuccess { if (it.isBlank()) publish(CommandState.Error("", speechStrings.didntCatch)) else ask(it, spoken = true) }
                .onFailure { publish(CommandState.Error("", friendlySttMessage(it.message, speechStrings))) }
        }
    }

    /**
     * The listening overlay's send button, whatever started the session: a held session
     * stops and transcribes, a hands-free one ends its utterance early.
     */
    fun sendNow() {
        if (_walkieTalkie.value) stopListening() else finishListening()
    }

    /**
     * Continues a stored conversation: its turns become the agent's context, so
     * "go back to that chat and keep talking" answers with what came before.
     */
    fun restoreSession(turns: List<com.samosaai.masala.chat.ChatTurn>) {
        job?.cancel()
        _walkieTalkie.value = false
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
        history = listOfNotNull(summary?.let { com.samosaai.masala.llm.ChatMessage("assistant", JsonPrimitive(it)) }) + spoken
        _state.value = CommandState.Idle
    }

    /**
     * Keeps the model's context bounded without losing the thread: once the history is long,
     * it becomes one summary plus the recent turns. The visible transcript is untouched, and
     * the summary is written into it as a system note so the change is never silent.
     */
    private suspend fun compactIfNeeded() {
        val chars = history.sumOf { it.textContent.length }
        if (chars < COMPACT_AT_CHARS) return
        val readable = history.joinToString("\n\n") { "[${it.role}] ${it.textContent}" }
        val summary = summarize?.invoke(readable)?.trim()?.takeIf { it.isNotBlank() }
        if (summary != null) {
            history = listOf(com.samosaai.masala.llm.ChatMessage("assistant", JsonPrimitive(summary)))
            onTurn?.invoke(com.samosaai.masala.chat.ChatTurn(com.samosaai.masala.chat.TurnRole.SYSTEM, summary))
        } else {
            // No summariser, or it failed: keep what fits rather than sending everything.
            history = com.samosaai.masala.agent.AgentHistory.trim(history, MasalaAgent.DEFAULT_MAX_HISTORY_CHARS)
        }
    }

    /** Ends tap-to-talk listening now; what was said still runs. */
    fun finishListening() {
        // Sending is not instantaneous: the take still has to be transcribed. Say so at
        // once instead of leaving "Listening…" on screen until the text lands.
        if (_state.value == CommandState.Listening) {
            _walkieTalkie.value = false
            _state.value = CommandState.Thinking("", listOf("Transcribing"))
        }
        speech?.finish()
    }

    /** Push-to-talk released: the transcript becomes a command. */
    fun stopListening() {
        val input = speech ?: return
        if (_state.value != CommandState.Listening) return
        _walkieTalkie.value = false
        _state.value = CommandState.Thinking("")
        job = scope.launch {
            input.stop()
                .onSuccess { ask(it, spoken = true) }
                .onFailure { publish(CommandState.Error("", friendlySttMessage(it.message, speechStrings))) }
        }
    }

    private suspend fun handle(prompt: String, spoken: Boolean) {
        CommandFastPath.match(prompt, resolveApp)?.let { action ->
            publish(stateFor(prompt, run(action), spoken))
            return
        }
        // No model call from home: the skill's own run builds the app, with its few tools.
        SkillRouter.handoff(prompt, skills())?.let { skill ->
            publish(stateFor(prompt, run(MasalaAction.RunSkill(skill.id, prompt)), spoken, "Opened ${skill.title.ifBlank { skill.id }}."))
            return
        }
        val chip = SkillRouter.route(prompt, skills())?.let { SkillChip(it.id, it.title.ifBlank { it.id }) }
        // Asking for advice goes straight to the persona; asking the phone to act runs, with the persona offered beside it.
        if (chip != null && !SkillRouter.isCommand(prompt)) {
            publish(CommandState.Reply(prompt, "${chip.title} can help with that.", skill = chip, spoken = spoken))
            return
        }
        val agent = newAgent().also { it.restore(history) }
        val steps = mutableListOf<String>()
        try {
            agent.run(ChatMessage("user", JsonPrimitive(prompt))).collect { event ->
                when (event) {
                    is AgentEvent.ToolCall -> {
                        steps += event.name
                        _state.value = CommandState.Thinking(prompt, steps.toList())
                    }
                    is AgentEvent.Done -> publish(CommandState.Reply(prompt, event.text.ifBlank { "Done." }, steps.toList(), chip, spoken))
                    is AgentEvent.Failed -> publish(CommandState.Error(prompt, event.message))
                    is AgentEvent.NeedsCapability -> publish(CommandState.Error(prompt, event.reason, event.capability))
                    AgentEvent.StepLimit -> publish(CommandState.Error(prompt, "That took too many steps. Try asking in smaller pieces."))
                    else -> Unit
                }
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            history = agent.history
        }
    }

    private companion object {
        /** Roughly 7.5k tokens: compact before the agent's own 40k-char trim would kick in. */
        const val COMPACT_AT_CHARS = 30_000
    }

    private fun stateFor(prompt: String, outcome: ActionDispatcher.Outcome, spoken: Boolean, done: String? = null): CommandState = when (outcome) {
        is ActionDispatcher.Outcome.Done -> CommandState.Reply(prompt, done ?: outcome.message?.takeIf { it.isNotBlank() } ?: "Done.", spoken = spoken)
        ActionDispatcher.Outcome.Declined -> CommandState.Reply(prompt, "Okay, I didn't do it.", spoken = spoken)
        is ActionDispatcher.Outcome.Failed -> CommandState.Error(prompt, outcome.message)
        is ActionDispatcher.Outcome.NeedsCapability -> CommandState.Error(prompt, outcome.reason, outcome.marker)
    }
}
