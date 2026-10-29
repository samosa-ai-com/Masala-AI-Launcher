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
) {
    private val _state = MutableStateFlow<CommandState>(CommandState.Idle)
    val state: StateFlow<CommandState> = _state.asStateFlow()

    private var job: Job? = null
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
        job = scope.launch { handle(prompt, spoken) }
    }

    /** Push-to-talk pressed. Returns false if listening can't start. */
    fun startListening(): Boolean {
        val input = speech ?: return false
        job?.cancel()
        // The specific reason first; async errors during listening update the card too.
        var reason: String? = null
        val async: (String) -> Unit = { reason = it; _state.value = CommandState.Error("", it) }
        if (!input.start(async)) {
            _state.value = CommandState.Error("", reason ?: "Speech recognition isn't available")
            return false
        }
        _state.value = CommandState.Listening
        return true
    }

    /** Push-to-talk released: the transcript becomes a command. */
    fun stopListening() {
        val input = speech ?: return
        if (_state.value != CommandState.Listening) return
        _state.value = CommandState.Thinking("")
        job = scope.launch {
            input.stop()
                .onSuccess { ask(it, spoken = true) }
                .onFailure { _state.value = CommandState.Error("", it.message ?: "Didn't catch that") }
        }
    }

    private suspend fun handle(prompt: String, spoken: Boolean) {
        CommandFastPath.match(prompt, resolveApp)?.let { action ->
            _state.value = stateFor(prompt, run(action), spoken)
            return
        }
        // No model call from home: the skill's own run builds the app, with its few tools.
        SkillRouter.handoff(prompt, skills())?.let { skill ->
            _state.value = stateFor(prompt, run(MasalaAction.RunSkill(skill.id, prompt)), spoken, "Opened ${skill.title.ifBlank { skill.id }}.")
            return
        }
        val chip = SkillRouter.route(prompt, skills())?.let { SkillChip(it.id, it.title.ifBlank { it.id }) }
        // Asking for advice goes straight to the persona; asking the phone to act runs, with the persona offered beside it.
        if (chip != null && !SkillRouter.isCommand(prompt)) {
            _state.value = CommandState.Reply(prompt, "${chip.title} can help with that.", skill = chip, spoken = spoken)
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
                    is AgentEvent.Done -> _state.value = CommandState.Reply(prompt, event.text.ifBlank { "Done." }, steps.toList(), chip, spoken)
                    is AgentEvent.Failed -> _state.value = CommandState.Error(prompt, event.message)
                    is AgentEvent.NeedsCapability -> _state.value = CommandState.Error(prompt, event.reason, event.capability)
                    AgentEvent.StepLimit -> _state.value = CommandState.Error(prompt, "That took too many steps. Try asking in smaller pieces.")
                    else -> Unit
                }
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            history = agent.history
        }
    }

    private fun stateFor(prompt: String, outcome: ActionDispatcher.Outcome, spoken: Boolean, done: String? = null): CommandState = when (outcome) {
        is ActionDispatcher.Outcome.Done -> CommandState.Reply(prompt, done ?: outcome.message?.takeIf { it.isNotBlank() } ?: "Done.", spoken = spoken)
        ActionDispatcher.Outcome.Declined -> CommandState.Reply(prompt, "Okay, I didn't do it.", spoken = spoken)
        is ActionDispatcher.Outcome.Failed -> CommandState.Error(prompt, outcome.message)
        is ActionDispatcher.Outcome.NeedsCapability -> CommandState.Error(prompt, outcome.reason, outcome.marker)
    }
}
