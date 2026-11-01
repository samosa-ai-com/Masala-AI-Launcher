package com.samosaai.masala.agent

import com.samosaai.masala.chat.ConsultSession
import com.samosaai.masala.chat.ConsultSessions
import com.samosaai.masala.chat.ConsultTurn
import com.samosaai.masala.chat.TurnRole
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.llm.ChatMessage
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive

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
        data class Answered(val session: ConsultSession, val exchange: Int, val answer: String, val text: String) : Outcome
        data class Refused(val reason: String) : Outcome
    }

    /** One consult at a time: a second question while the expert is answering is refused, not queued. */
    private val busy = mutableSetOf<String>()
    private val busyLock = Mutex()

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
                    return Outcome.Refused("Consult $consultId belongs to '${found.title}', not '$title'. Use its own id, or leave consult_id out for a new consult.")
                }
                if (found.exchanges >= ConsultSessions.MAX_EXCHANGES) return full(found)
                found
            }
            else -> {
                val open = sessions.newest(chatId, skillId)
                if (open != null && open.exchanges >= ConsultSessions.MAX_EXCHANGES) return full(open)
                open ?: sessions.create(chatId, skillId, title)
                    ?: return Outcome.Refused(
                        "This chat has already consulted ${ConsultSessions.MAX_PER_CHAT} experts. " +
                            "Answer from what you know, or ask the user to start a new chat for another.",
                    )
            }
        }

        val key = "${session.chatId}/${session.id}"
        busyLock.withLock {
            if (!busy.add(key)) return Outcome.Refused("$title is still answering the previous question.")
        }
        try {
            val history = session.turns
            val afterQuestion = sessions.append(chatId, session.id, ConsultTurn(TurnRole.USER, asked))
                ?: return full(session)

            val agent = agentFor(expert)
            agent.restore(history.map { turn ->
                ChatMessage(
                    if (turn.role == TurnRole.USER) "user" else "assistant",
                    JsonPrimitive(turn.text),
                )
            })

            var answer: String? = null
            var failure: String? = null
            val finished = withTimeoutOrNull(timeoutMs) {
                agent.run(ChatMessage("user", JsonPrimitive(asked))).collect { event ->
                    when (event) {
                        is AgentEvent.Done -> answer = event.text.ifBlank { null }
                        is AgentEvent.Failed -> failure = event.message
                        AgentEvent.StepLimit -> failure = "It took too many steps."
                        else -> Unit
                    }
                }
                true
            }
            if (finished == null) failure = "It didn't answer in time."

            val text = answer
            if (text == null) {
                val reason = failure ?: "It didn't answer."
                // The question was asked, so the record keeps it and the failure honestly.
                sessions.append(chatId, session.id, ConsultTurn(TurnRole.ERROR, reason))
                return Outcome.Refused("$title couldn't answer: $reason")
            }

            val saved = sessions.append(chatId, session.id, ConsultTurn(TurnRole.MASALA, text)) ?: afterQuestion
            return Outcome.Answered(
                session = saved,
                exchange = saved.exchanges,
                answer = text,
                text = render(saved, text, title),
            )
        } finally {
            busyLock.withLock { busy.remove(key) }
        }
    }

    private suspend fun full(session: ConsultSession): Outcome.Refused = Outcome.Refused(
        "${session.title} has answered ${ConsultSessions.MAX_EXCHANGES} times in this chat. " +
            "Use what you have, or ask the user to start a new chat.",
    )

    /**
     * What the main agent reads: where the answer came from, the answer, and the ids it needs to
     * come back — including after its own history was compacted.
     */
    private suspend fun render(session: ConsultSession, answer: String, title: String): String {
        val earlier = session.pairs().dropLast(1)
        val recap = if (earlier.isEmpty()) "" else buildString {
            appendLine()
            appendLine()
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
            append(recap.trimEnd())
            if (others.isNotBlank()) {
                if (recap.isNotBlank()) appendLine() else appendLine().appendLine()
                append("Consults in this chat: ")
                append(others)
            }
        }.trimEnd()
    }

    private fun oneLine(text: String) = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim().take(60)

    companion object {
        /** Long enough for a slow model, short enough that a stuck expert can't hold the turn. */
        const val TIMEOUT_MS = 90_000L
    }
}
