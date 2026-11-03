package com.samosaai.masala.agent

import com.samosaai.masala.chat.ConsultSession
import com.samosaai.masala.chat.ConsultSessions
import com.samosaai.masala.chat.ConsultTurn
import com.samosaai.masala.chat.TurnRole
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.llm.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * One exchange with a consulted expert. The expert owns its turn — its persona prompt is the only
 * instruction it has — and its record lives in [ConsultSessions], so a follow-up in the same chat
 * reaches the same expert with the same memory. The main agent only sees the answer.
 *
 * Nothing here is a chat: no session is created in the chat store, and nothing is listed.
 */
class ConsultService(
    private val sessions: ConsultSessions,
    private val skill: (String) -> MasalaSkill?,
    private val agentFor: (MasalaSkill) -> MasalaAgent,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    sealed interface Outcome {
        /** [text] is what the main agent receives: the answer plus where it came from. */
        data class Answered(
            val session: ConsultSession,
            val exchange: Int,
            val answer: String,
            val text: String,
            /** What the expert made (a mini app, a file), so the chat can offer it. */
            val produced: List<com.samosaai.masala.core.tool.Produced> = emptyList(),
        ) : Outcome
        data class Refused(val reason: String) : Outcome
    }

    /**
     * One consult at a time. A concurrent map rather than a mutex: a cancelled turn must still be
     * able to clear its entry, and a cancelled coroutine cannot take a suspending lock.
     */
    private val busy = ConcurrentHashMap.newKeySet<String>()

    suspend fun consult(chatId: String, skillId: String, question: String, consultId: String?): Outcome {
        val asked = question.trim()
        if (asked.isBlank()) return Outcome.Refused("Say what to ask the expert.")
        val expert = skill(skillId) ?: return Outcome.Refused("There is no skill '$skillId'.")
        if (expert.persona == null) {
            return Outcome.Refused("'${expert.title.ifBlank { expert.id }}' is a skill, not a specialist. Start it with run_skill.")
        }
        val title = expert.title.ifBlank { expert.id }

        val session = when {
            consultId != null -> {
                val found = sessions.get(chatId, consultId)
                    ?: return Outcome.Refused("There is no consult '$consultId' in this chat.")
                if (found.skillId != skillId) {
                    return Outcome.Refused("Consult $consultId belongs to '${found.title}', not '$title'. Use its own id, or leave consult_id out for a fresh consult.")
                }
                if (found.questions >= ConsultSessions.MAX_EXCHANGES) {
                    return Outcome.Refused(
                        "That consult has asked its ${ConsultSessions.MAX_EXCHANGES} questions. " +
                            "Leave consult_id out to start a fresh consult with $title, or use what you have.",
                    )
                }
                found
            }
            else -> sessions.newest(chatId, skillId)?.takeIf { it.questions < ConsultSessions.MAX_EXCHANGES }
                ?: sessions.create(chatId, skillId, title)
                ?: return Outcome.Refused(
                    "This chat has already consulted ${ConsultSessions.MAX_PER_CHAT} experts. " +
                        "Answer from what you know, or ask the user to start a new chat for another.",
                )
        }

        val key = "${session.chatId}/${session.id}"
        if (!busy.add(key)) return Outcome.Refused("$title is still answering the previous question.")
        try {
            val agent = agentFor(expert)
            // Only answered exchanges are replayed: a failure is not something the expert said.
            agent.restore(session.pairs().flatMap { (q, a) ->
                listOf(
                    ChatMessage("user", JsonPrimitive(q)),
                    ChatMessage("assistant", JsonPrimitive(a)),
                )
            })

            var answer: String? = null
            var failure: String? = null
            var missingAccess: String? = null
            val produced = mutableListOf<com.samosaai.masala.core.tool.Produced>()
            try {
                val finished = withTimeoutOrNull(timeoutMs) {
                    agent.run(ChatMessage("user", JsonPrimitive(asked))).collect { event ->
                        when (event) {
                            is AgentEvent.Done -> answer = event.text.ifBlank { null }
                            is AgentEvent.Failed -> failure = event.message
                            AgentEvent.StepLimit -> failure = "It took too many steps."
                            // Not fatal: the expert finishes around it, and the gap is reported.
                            is AgentEvent.NeedsCapability -> missingAccess = event.capability
                            is AgentEvent.Results -> event.items.forEach { item ->
                                // A rebuild of the same app updates its entry instead of being dropped.
                                produced.removeAll { it.ref == item.ref }
                                produced += item
                            }
                            else -> Unit
                        }
                    }
                    true
                }
                if (finished == null) failure = "It didn't answer in time."
            } catch (e: CancellationException) {
                // Nothing was written yet, so a cancelled turn leaves no dangling question behind.
                throw e
            } catch (e: Exception) {
                failure = e.message ?: e::class.java.simpleName
            }

            // What the expert saved goes into the text the main agent reads and the consult replays:
            // `produced` alone only reaches the chat UI, so a follow-up consult rebuilt the same app.
            val made = producedNote(produced)
            val text = answer?.let { if (made.isEmpty()) it else it.trimEnd() + "\n\n" + made }
            val reason = (failure ?: "It didn't answer.") + if (made.isEmpty()) "" else " $made"
            // One write: the question and its outcome land together or not at all.
            val turns = if (text != null) {
                listOf(ConsultTurn(TurnRole.USER, asked), ConsultTurn(TurnRole.MASALA, text))
            } else {
                listOf(ConsultTurn(TurnRole.USER, asked), ConsultTurn(TurnRole.ERROR, reason))
            }
            val saved = sessions.append(chatId, session.id, turns)
                ?: return Outcome.Refused("$title answered, but the consult couldn't be saved. Don't retry; answer from what you know.")

            if (text == null) {
                val why = missingAccess?.let { "it needs $it, which isn't granted" } ?: reason
                return Outcome.Refused("$title couldn't answer: $why")
            }
            return Outcome.Answered(
                session = saved,
                exchange = saved.exchanges,
                answer = text,
                text = render(saved, text, title, expert.persona?.disclaimer, missingAccess),
                produced = produced.toList(),
            )
        } finally {
            busy.remove(key)
        }
    }

    /**
     * What the main agent reads: where the answer came from, the answer, and the ids it needs to
     * come back — including after its own history was compacted.
     */
    private suspend fun render(
        session: ConsultSession,
        answer: String,
        title: String,
        disclaimer: String?,
        missingAccess: String?,
    ): String {
        val earlier = session.pairs().dropLast(1)
        val recap = if (earlier.isEmpty()) "" else buildString {
            appendLine("Earlier in this consult:")
            earlier.forEach { (q, a) -> appendLine("- Q: ${oneLine(q)} → A: ${oneLine(a)}") }
        }
        val others = sessions.list(session.chatId)
            .joinToString(", ") { "${it.id} ${it.title} (${it.exchanges}/${ConsultSessions.MAX_EXCHANGES})" }
        return buildString {
            append("[")
            append(title)
            append(" · consult ")
            append(session.id)
            append(" · exchange ")
            append(session.exchanges)
            append(" of ")
            append(ConsultSessions.MAX_EXCHANGES)
            appendLine("]")
            appendLine(answer.trim())
            if (!disclaimer.isNullOrBlank()) {
                appendLine()
                appendLine("Disclaimer to pass on: ${disclaimer.trim()}")
            }
            if (!missingAccess.isNullOrBlank()) {
                appendLine()
                appendLine("A step needed $missingAccess, which is not granted. Mention it only if it changes your answer.")
            }
            if (recap.isNotBlank()) {
                appendLine()
                append(recap.trimEnd())
                appendLine()
            }
            if (others.isNotBlank()) {
                appendLine()
                append("Consults in this chat: ")
                append(others)
            }
        }.trimEnd()
    }

    /** One line naming what the consult saved, with ids, so nobody creates it a second time. */
    internal fun producedNote(produced: List<com.samosaai.masala.core.tool.Produced>): String =
        if (produced.isEmpty()) {
            ""
        } else {
            "Already saved in this consult (don't create again; change it by id): " +
                produced.joinToString("; ") { "${it.kind} '${it.title}' id ${it.ref}" } + "."
        }

    private fun oneLine(text: String) = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim().take(60)

    companion object {
        /**
         * Long enough for a slow model, short enough that a stuck expert can't hold the turn. An app
         * build writes a whole single-file app in one model call; at 90 s the first software-engineer
         * consult timed out on every build on the phone, and the retry rebuilt it from scratch.
         */
        const val TIMEOUT_MS = 180_000L
    }
}
