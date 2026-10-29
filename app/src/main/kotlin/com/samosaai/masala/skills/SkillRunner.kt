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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs skills (plan Phase 4, M2): a `skill_runs` row, a [MasalaAgent] with the persona's and skill's
 * instructions and the skill's tool scope, the agent's events turned into [SkillRunEvent]s, the
 * transcript saved after every turn, and result files recorded as `skill_artifacts`. App-scoped: a run
 * goes on when the sheet closes, and the widget shows its result.
 *
 * [agentFor] builds the agent for a skill; the app passes one with the skill's [com.samosaai.masala.core.agent.ToolScope],
 * cut down to read-only tools when the run is `unattended` (scheduled runs nobody watches, arch §3.9).
 *
 * The whole conversation is saved in [transcripts], one file per run: tool replies can be large, and a
 * database row past 2 MB can't be read back. The row keeps only the last answer, for the widget.
 */
class SkillRunner(
    private val scope: CoroutineScope,
    private val runs: SkillRunDao,
    private val artifacts: SkillArtifactDao,
    private val transcripts: TranscriptFiles,
    private val skill: (String) -> MasalaSkill?,
    private val agentFor: (skill: MasalaSkill, unattended: Boolean) -> MasalaAgent,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Active(val skill: MasalaSkill, var agent: MasalaAgent, var unattended: Boolean, val state: MutableStateFlow<SkillRunState>) {
        var job: Job? = null
        @Volatile var lastUsed = 0L
    }

    private val active = ConcurrentHashMap<Long, Active>()

    /** Orders [Active.lastUsed]; a clock would tie when runs end within the same millisecond. */
    private val uses = AtomicLong()

    /** Starts a new run of [skillId]; null if there is no such skill. */
    suspend fun start(skillId: String, message: ChatMessage, unattended: Boolean = false): Long? {
        val skill = skill(skillId) ?: return null
        val id = runs.insert(
            SkillRunEntity(skillId = skillId, prompt = message.textContent, status = "running", transcriptJson = "[]", startedAt = clock()),
        )
        val run = Active(skill, agentFor(skill, unattended), unattended, MutableStateFlow(SkillRunState(id, skillId)))
        active[id] = run
        turn(id, run, message)
        return id
    }

    /**
     * A follow-up from the user in an existing run, restoring it first if it was let go. Ignored while a
     * turn is still going. Returns the run's state, which is the one to watch from now on.
     */
    suspend fun send(runId: Long, message: ChatMessage): StateFlow<SkillRunState>? {
        val state = open(runId) ?: return null
        val run = active[runId] ?: return null
        if (run.state.value.running) return state
        if (run.unattended) {
            // Someone is answering now, so the run gets its skill's full tools, like a run started from the sheet.
            run.agent = agentFor(run.skill, false).also { it.restore(run.agent.transcript) }
            run.unattended = false
        }
        turn(runId, run, message)
        return state
    }

    /** The run's state, restoring it from storage if it isn't in memory (after the app was killed or it was let go). */
    suspend fun open(runId: Long): StateFlow<SkillRunState>? {
        active[runId]?.let { it.lastUsed = uses.incrementAndGet(); return it.state.asStateFlow() }
        val row = runs.get(runId) ?: return null
        val skill = skill(row.skillId) ?: return null
        val history = transcripts.read(runId) ?: decode(row.transcriptJson)
        val agent = agentFor(skill, false).also { it.restore(history) }
        val results = artifacts.forRun(runId).first().map { SkillRunEvent.Result(it.kind, it.title, it.ref) }
        val state = MutableStateFlow(SkillRunState(runId, row.skillId, eventsFrom(agent.history) + results, running = false))
        val run = Active(skill, agent, false, state).also { it.lastUsed = uses.incrementAndGet() }
        return (active.putIfAbsent(runId, run) ?: active[runId]!!).state.asStateFlow()
    }

    /** How a finished run ended, as saved: the status (`done`, `failed`, `cancelled`) and its error, or null for an unknown run. */
    suspend fun outcome(runId: Long): Pair<String, String?>? = runs.get(runId)?.let { it.status to it.error }

    /** Whether the run is in memory (for tests). */
    internal fun holds(runId: Long) = active.containsKey(runId)

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
                run.lastUsed = uses.incrementAndGet()
                run.state.update { it.copy(running = false, events = it.events.withoutProgress()) }
                evictIdle()
            }
        }
    }

    /**
     * Lets go of finished runs past the newest [KEEP_IDLE], so daily scheduled runs don't pile up in
     * memory. A run that is going or still on screen stays; a let-go run is restored from storage when
     * it is opened again.
     */
    private fun evictIdle() {
        active.entries
            .filter { (_, run) -> !run.state.value.running && run.job?.isActive != true && run.state.subscriptionCount.value == 0 }
            .sortedByDescending { it.value.lastUsed }
            .drop(KEEP_IDLE)
            .forEach { (id, run) -> active.remove(id, run) }
    }

    /** Returns an error message when the event ends the run badly. */
    private suspend fun handle(runId: Long, run: Active, event: AgentEvent): String? {
        fun add(e: SkillRunEvent) = run.state.update { it.copy(events = it.events.withoutProgress() + e) }
        when (event) {
            AgentEvent.Thinking -> run.state.update { it.copy(events = it.events.withoutProgress() + SkillRunEvent.Progress("Thinking")) }
            is AgentEvent.Text -> add(SkillRunEvent.Message(SkillRunEvent.Message.Role.ASSISTANT, event.text))
            is AgentEvent.ToolCall -> add(SkillRunEvent.ToolStep(event.name, null))
            is AgentEvent.ToolDone -> run.state.update { it.copy(events = it.events.settleStep(event.name, event.result !is ToolResult.Failure)) }
            is AgentEvent.Results -> for (item in event.items) {
                // The same result again (a mini app updated after its create) replaces the earlier card.
                artifacts.deleteSame(runId, item.kind, item.ref)
                artifacts.insert(SkillArtifactEntity(runId = runId, kind = item.kind, title = item.title, ref = item.ref, createdAt = clock()))
                val result = SkillRunEvent.Result(item.kind, item.title, item.ref)
                run.state.update { s ->
                    s.copy(events = s.events.withoutProgress().filterNot { it is SkillRunEvent.Result && it.kind == item.kind && it.ref == item.ref } + result)
                }
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
        val history = withoutImages(run.agent.transcript)
        transcripts.write(runId, history)
        val row = runs.get(runId) ?: return
        runs.update(row.copy(status = status, transcriptJson = preview(history), error = error, finishedAt = if (finished) clock() else row.finishedAt))
    }

    companion object {
        /** Finished runs kept in memory; older ones are restored from storage when opened. */
        const val KEEP_IDLE = 8

        /** How much of the last answer the row keeps for the widget. */
        const val PREVIEW_CHARS = 4_000

        private val transcript = ListSerializer(ChatMessage.serializer())

        /** The run's last answer, for the widget's result line. */
        fun lastAnswer(transcriptJson: String): String? =
            decode(transcriptJson).lastOrNull { it.role == "assistant" && it.hasText }?.textContent

        /** What the row stores: just the last answer, cut to [PREVIEW_CHARS], so the row stays small. */
        internal fun preview(history: List<ChatMessage>): String {
            val answer = history.lastOrNull { it.role == "assistant" && it.hasText }
                ?.let { listOf(ChatMessage("assistant", JsonPrimitive(it.textContent.take(PREVIEW_CHARS)))) }
            return MasalaJson.strict.encodeToString(transcript, answer.orEmpty())
        }

        internal fun decode(json: String): List<ChatMessage> =
            runCatching { MasalaJson.lenient.decodeFromString(transcript, json) }.getOrDefault(emptyList())

        /** Rebuilds what the sheet shows from a saved conversation. */
        fun eventsFrom(history: List<ChatMessage>): List<SkillRunEvent> = history.mapNotNull { m ->
            when {
                m.role == "user" && !m.textContent.startsWith("[") -> SkillRunEvent.Message(SkillRunEvent.Message.Role.USER, m.textContent)
                m.role == "assistant" && m.hasText -> SkillRunEvent.Message(SkillRunEvent.Message.Role.ASSISTANT, m.textContent)
                else -> null
            }
        }

        /**
         * The transcript as stored: photos are dropped (the text part stays), since a few base64 images
         * would outgrow the 2 MB a database row can be read back with. The run in memory keeps them.
         */
        internal fun withoutImages(history: List<ChatMessage>): List<ChatMessage> = history.map { m ->
            val parts = m.content as? JsonArray ?: return@map m
            val text = parts.filterNot { ((it as? JsonObject)?.get("type") as? JsonPrimitive)?.content == "image_url" }
            if (text.size == parts.size) m else m.copy(content = JsonArray(text))
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
