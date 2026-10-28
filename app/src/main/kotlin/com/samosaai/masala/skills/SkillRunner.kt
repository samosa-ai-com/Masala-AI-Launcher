package com.samosaai.masala.skills

import com.samosaai.masala.agent.AgentEvent
import com.samosaai.masala.agent.MasalaAgent
import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.data.SkillArtifactDao
import com.samosaai.masala.data.SkillArtifactEntity
import com.samosaai.masala.data.SkillRunDao
import com.samosaai.masala.data.SkillRunEntity
import com.samosaai.masala.llm.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs skills (plan Phase 4, M2): a `skill_runs` row, a [MasalaAgent] with the persona's and skill's
 * instructions and the skill's tool scope, the agent's events turned into [SkillRunEvent]s, the
 * transcript saved after every turn, and result files recorded as `skill_artifacts`. App-scoped: a run
 * goes on when the sheet closes, and the widget shows its result.
 *
 * [agentFor] builds the agent for a skill; the app passes one with the skill's [com.samosaai.masala.core.agent.ToolScope].
 */
class SkillRunner(
    private val scope: CoroutineScope,
    private val runs: SkillRunDao,
    private val artifacts: SkillArtifactDao,
    private val skill: (String) -> MasalaSkill?,
    private val agentFor: (MasalaSkill) -> MasalaAgent,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Active(val skill: MasalaSkill, val agent: MasalaAgent, val state: MutableStateFlow<SkillRunState>) {
        var job: Job? = null
    }

    private val active = ConcurrentHashMap<Long, Active>()
    private val transcript = ListSerializer(ChatMessage.serializer())

    /** Starts a new run of [skillId]; null if there is no such skill. */
    suspend fun start(skillId: String, message: ChatMessage): Long? {
        val skill = skill(skillId) ?: return null
        val id = runs.insert(
            SkillRunEntity(skillId = skillId, prompt = message.textContent, status = "running", transcriptJson = "[]", startedAt = clock()),
        )
        val run = Active(skill, agentFor(skill), MutableStateFlow(SkillRunState(id, skillId)))
        active[id] = run
        turn(id, run, message)
        return id
    }

    /** A follow-up in an existing run. Ignored while a turn is still going. */
    fun send(runId: Long, message: ChatMessage) {
        val run = active[runId] ?: return
        if (run.state.value.running) return
        scope.launch { turn(runId, run, message) }
    }

    /** The run's state, restoring it from the database if it isn't in memory (after the app was killed). */
    suspend fun open(runId: Long): StateFlow<SkillRunState>? {
        active[runId]?.let { return it.state.asStateFlow() }
        val row = runs.get(runId) ?: return null
        val skill = skill(row.skillId) ?: return null
        val history = runCatching { MasalaJson.lenient.decodeFromString(transcript, row.transcriptJson) }.getOrDefault(emptyList())
        val agent = agentFor(skill).also { it.restore(history) }
        val state = MutableStateFlow(SkillRunState(runId, row.skillId, eventsFrom(agent.history), running = false))
        return (active.putIfAbsent(runId, Active(skill, agent, state)) ?: active[runId]!!).state.asStateFlow()
    }

    fun stop(runId: Long) {
        active[runId]?.job?.cancel()
    }

    private suspend fun turn(runId: Long, run: Active, message: ChatMessage) {
        run.state.update { it.copy(events = it.events + SkillRunEvent.Message(SkillRunEvent.Message.Role.USER, message.textContent), running = true) }
        save(runId, run, "running", null)
        run.job = scope.launch {
            var status = "done"
            var error: String? = null
            try {
                run.agent.run(message).collect { event -> error = handle(runId, run, event) ?: error }
                if (error != null) status = "failed"
            } catch (e: CancellationException) {
                status = "cancelled"
                run.state.update { it.copy(events = it.events.settled() + SkillRunEvent.Failed("Stopped.")) }
                throw e
            } finally {
                // Saved before the state says the run is over, so whoever sees it end can read the row.
                withContext(NonCancellable) { save(runId, run, status, error, finished = true) }
                run.state.update { it.copy(running = false, events = it.events.withoutProgress()) }
            }
        }
    }

    /** Returns an error message when the event ends the run badly. */
    private suspend fun handle(runId: Long, run: Active, event: AgentEvent): String? {
        fun add(e: SkillRunEvent) = run.state.update { it.copy(events = it.events.withoutProgress() + e) }
        when (event) {
            AgentEvent.Thinking -> run.state.update { it.copy(events = it.events.withoutProgress() + SkillRunEvent.Progress("Thinking")) }
            is AgentEvent.Text -> add(SkillRunEvent.Message(SkillRunEvent.Message.Role.ASSISTANT, event.text))
            is AgentEvent.ToolCall -> add(SkillRunEvent.ToolStep(event.name, null))
            is AgentEvent.ToolDone -> run.state.update { it.copy(events = it.events.settleStep(event.name, event.result !is ToolResult.Failure)) }
            is AgentEvent.Files -> for (path in event.paths) {
                val title = File(path).name
                artifacts.insert(SkillArtifactEntity(runId = runId, kind = kindOf(path), title = title, ref = path, createdAt = clock()))
                add(SkillRunEvent.Result(kindOf(path), title, path))
            }
            is AgentEvent.Done -> {
                add(SkillRunEvent.Message(SkillRunEvent.Message.Role.ASSISTANT, event.text))
                add(SkillRunEvent.Done)
            }
            is AgentEvent.NeedsCapability -> {
                add(SkillRunEvent.NeedsCapability(event.capability, event.reason))
                return event.reason
            }
            is AgentEvent.Failed -> {
                add(SkillRunEvent.Failed(event.message))
                return event.message
            }
            AgentEvent.StepLimit -> {
                add(SkillRunEvent.Failed("Stopped after too many steps."))
                return "step limit"
            }
        }
        return null
    }

    private suspend fun save(runId: Long, run: Active, status: String, error: String?, finished: Boolean = false) {
        val json = MasalaJson.strict.encodeToString(transcript, run.agent.history)
        val row = runs.get(runId) ?: return
        runs.update(row.copy(status = status, transcriptJson = json, error = error, finishedAt = if (finished) clock() else row.finishedAt))
    }

    companion object {
        /** The run's last answer, for the widget's result line. */
        fun lastAnswer(transcriptJson: String): String? = runCatching {
            MasalaJson.lenient.decodeFromString(ListSerializer(ChatMessage.serializer()), transcriptJson)
                .lastOrNull { it.role == "assistant" && it.hasText }?.textContent
        }.getOrNull()

        /** Rebuilds what the sheet shows from a saved conversation. */
        fun eventsFrom(history: List<ChatMessage>): List<SkillRunEvent> = history.mapNotNull { m ->
            when {
                m.role == "user" && !m.textContent.startsWith("[") -> SkillRunEvent.Message(SkillRunEvent.Message.Role.USER, m.textContent)
                m.role == "assistant" && m.hasText -> SkillRunEvent.Message(SkillRunEvent.Message.Role.ASSISTANT, m.textContent)
                else -> null
            }
        }

        private fun kindOf(path: String) = when (File(path).extension.lowercase()) {
            "mp3", "m4a", "wav", "ogg", "flac", "aac" -> "audio"
            "mp4", "mkv", "webm", "mov", "3gp" -> "video"
            else -> "file"
        }

        private fun List<SkillRunEvent>.withoutProgress() = filterNot { it is SkillRunEvent.Progress }

        /** Steps still marked as running when a run is cut short. */
        private fun List<SkillRunEvent>.settled() = withoutProgress().map { if (it is SkillRunEvent.ToolStep && it.done == null) it.copy(done = false) else it }

        private fun List<SkillRunEvent>.settleStep(name: String, ok: Boolean): List<SkillRunEvent> {
            val i = indexOfLast { it is SkillRunEvent.ToolStep && it.name == name && it.done == null }
            return if (i < 0) this else toMutableList().also { it[i] = SkillRunEvent.ToolStep(name, ok) }
        }
    }
}
