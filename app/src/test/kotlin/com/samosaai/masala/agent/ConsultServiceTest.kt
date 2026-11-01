package com.samosaai.masala.agent

import com.samosaai.masala.chat.ConsultSessions
import com.samosaai.masala.chat.TurnRole
import com.samosaai.masala.core.agent.ToolScope
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.contract.Persona
import com.samosaai.masala.core.agent.ToolInfo
import com.samosaai.masala.core.tool.ToolDefinition
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.core.tool.ToolSensitivity
import com.samosaai.masala.llm.FunctionCall
import com.samosaai.masala.llm.ToolCall
import kotlinx.serialization.json.JsonObject
import com.samosaai.masala.llm.ChatMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.serialization.json.JsonPrimitive

/** One exchange with an expert: who it creates, what it remembers, and where it stops. */
class ConsultServiceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val doctor = MasalaSkill(
        id = "doctor",
        title = "Doctor",
        instructions = "Talk as a GP.",
        persona = Persona(systemPrompt = "You are a doctor.", disclaimer = "Not a diagnosis."),
    )
    private val chef = MasalaSkill(
        id = "chef",
        title = "Chef",
        instructions = "Talk as a cook.",
        persona = Persona(systemPrompt = "You are a chef."),
    )
    private val gameMaker = MasalaSkill(id = "game_maker", title = "Game maker", instructions = "Build a game.")

    private class Script(private val replies: ArrayDeque<String>) : AgentModel {
        val requests = mutableListOf<List<ChatMessage>>()
        override suspend fun next(messages: List<ChatMessage>, tools: List<com.samosaai.masala.llm.ToolDefinition>): ChatMessage {
            requests += messages
            return ChatMessage("assistant", JsonPrimitive(replies.removeFirst()))
        }
    }

    private fun service(vararg replies: String): Pair<ConsultService, Script> {
        val script = Script(ArrayDeque(replies.toList()))
        val sessions = ConsultSessions(tmp.root)
        val service = ConsultService(
            sessions = sessions,
            skill = { id -> listOf(doctor, chef, gameMaker).firstOrNull { it.id == id } },
            agentFor = { skill ->
                MasalaAgent(
                    model = script,
                    tools = AgentToolRunner { _, _ -> ToolResult.Failure("no tools") },
                    definitions = emptyList(),
                    scope = ToolScope.command(emptyList()),
                    systemPrompt = { skill.persona?.systemPrompt.orEmpty() },
                    maxSteps = 4,
                )
            },
        )
        return service to script
    }

    @Test
    fun aFirstQuestionStartsAConsult() = runBlocking<Unit> {
        val (service, _) = service("Ask about the pain first.")
        val answer = service.consult("chat-1", "doctor", "headache for 3 days", null)
        val replied = answer as ConsultService.Outcome.Answered
        assertEquals("chat-1-c1", replied.session.id)
        assertEquals(1, replied.exchange)
        assertTrue(replied.text.contains("exchange 1 of 10"))
        assertTrue(replied.text.contains("Doctor"))
        assertTrue(replied.text.contains("Ask about the pain first."))
        // The main agent must be able to pass the persona's own disclaimer on.
        assertTrue(replied.text.contains("Disclaimer to pass on: Not a diagnosis."))
    }

    @Test
    fun aFollowUpReachesTheSameExpertWithItsHistory() = runBlocking<Unit> {
        val (service, script) = service("Ask about the pain.", "Then it is a different picture.")
        val first = service.consult("chat-1", "doctor", "headache for 3 days", null) as ConsultService.Outcome.Answered
        val second = service.consult("chat-1", "doctor", "it is behind one eye", first.session.id) as ConsultService.Outcome.Answered

        assertEquals(2, second.exchange)
        assertEquals("chat-1-c1", second.session.id)
        // The expert's second turn carries the first question and answer.
        val secondRequest = script.requests[1].map { it.textContent }
        assertTrue(secondRequest.contains("headache for 3 days"))
        assertTrue(secondRequest.contains("Ask about the pain."))
        assertTrue(secondRequest.contains("it is behind one eye"))
        assertTrue(second.text.contains("Earlier in this consult"))
    }

    @Test
    fun anUnknownConsultIdIsRefused() = runBlocking<Unit> {
        val (service, _) = service("never")
        val refused = service.consult("chat-1", "doctor", "anything", "chat-1-c9")
        assertTrue(refused is ConsultService.Outcome.Refused)
    }

    @Test
    fun anotherExpertsConsultIdIsRefused() = runBlocking<Unit> {
        val (service, _) = service("Ask about the pain.")
        val doctorConsult = service.consult("chat-1", "doctor", "headache", null) as ConsultService.Outcome.Answered
        val refused = service.consult("chat-1", "chef", "dinner", doctorConsult.session.id)
        assertTrue(refused is ConsultService.Outcome.Refused)
        assertTrue((refused as ConsultService.Outcome.Refused).reason.contains("Doctor"))
    }

    @Test
    fun aSkillWithoutAPersonaIsRefused() = runBlocking<Unit> {
        val (service, _) = service("never")
        val refused = service.consult("chat-1", "game_maker", "make a game", null)
        assertTrue(refused is ConsultService.Outcome.Refused)
        assertTrue((refused as ConsultService.Outcome.Refused).reason.contains("run_skill"))
    }

    @Test
    fun anUnknownSkillIsRefused() = runBlocking<Unit> {
        val (service, _) = service("never")
        assertTrue(service.consult("chat-1", "nobody", "hello", null) is ConsultService.Outcome.Refused)
    }

    @Test
    fun theEleventhQuestionIsRefused() = runBlocking<Unit> {
        val replies = (1..10).map { "answer $it" }.toTypedArray()
        val (service, script) = service(*replies)
        var consultId: String? = null
        repeat(10) { n ->
            val reply = service.consult("chat-1", "doctor", "question $n", consultId)
            assertTrue(reply is ConsultService.Outcome.Answered)
            consultId = (reply as ConsultService.Outcome.Answered).session.id
        }
        assertEquals(10, script.requests.size)
        val refused = service.consult("chat-1", "doctor", "question 10", consultId)
        assertTrue(refused is ConsultService.Outcome.Refused)
        // Nothing reached the model, and nothing was written.
        assertEquals(10, script.requests.size)
        val sessions = ConsultSessions(tmp.root)
        assertEquals(10, sessions.list("chat-1").single().exchanges)
    }

    @Test
    fun aFullConsultIsReplacedWhenNoIdIsGiven() = runBlocking<Unit> {
        val replies = (1..11).map { "answer $it" }.toTypedArray()
        val (service, _) = service(*replies)
        var consulted = service.consult("chat-1", "doctor", "q0", null) as ConsultService.Outcome.Answered
        repeat(9) { n ->
            consulted = service.consult("chat-1", "doctor", "q${n + 1}", consulted.session.id) as ConsultService.Outcome.Answered
        }
        assertEquals(10, consulted.exchange)
        // No id: the full consult is left alone and a fresh one starts in the next slot.
        val fresh = service.consult("chat-1", "doctor", "new question", null) as ConsultService.Outcome.Answered
        assertEquals("chat-1-c2", fresh.session.id)
        assertEquals(1, fresh.exchange)
        // With the full id it is refused rather than silently continuing past the cap.
        val refused = service.consult("chat-1", "doctor", "one more", consulted.session.id)
        assertTrue(refused is ConsultService.Outcome.Refused)
    }

    @Test
    fun aMissingPermissionDoesNotStopTheExpertAnswering() = runBlocking<Unit> {
        var request = 0
        val model = AgentModel { _, _ ->
            request += 1
            if (request == 1) {
                ChatMessage(
                    "assistant",
                    JsonPrimitive(""),
                    toolCalls = listOf(ToolCall("1", function = FunctionCall("health", "{}"))),
                )
            } else {
                ChatMessage("assistant", JsonPrimitive("I can't read your steps without Health Connect access."))
            }
        }
        val service = ConsultService(
            sessions = ConsultSessions(tmp.root),
            skill = { doctor },
            agentFor = {
                MasalaAgent(
                    model = model,
                    tools = AgentToolRunner { _, _ ->
                        ToolResult.NeedsCapability("special:health_connect", "Health Connect access")
                    },
                    definitions = listOf(ToolDefinition("health", "", JsonObject(emptyMap()))),
                    scope = ToolScope.command(listOf(ToolInfo("health", ToolSensitivity.READ_ONLY))),
                    systemPrompt = { "s" },
                    maxSteps = 4,
                )
            },
        )
        val answered = service.consult("chat-1", "doctor", "how many steps did I take", null)
            as ConsultService.Outcome.Answered
        // The expert finished around the missing access instead of the turn failing.
        assertTrue(answered.answer.contains("without Health Connect access"))
        assertTrue(answered.text.contains("special:health_connect"))
    }

    @Test
    fun whatTheExpertMadeComesBackWithItsAnswer() = runBlocking<Unit> {
        var request = 0
        val model = AgentModel { _, _ ->
            request += 1
            if (request == 1) {
                ChatMessage(
                    "assistant",
                    JsonPrimitive(""),
                    toolCalls = listOf(ToolCall("1", function = FunctionCall("build", "{}"))),
                )
            } else {
                ChatMessage("assistant", JsonPrimitive("I made Snake and put it on your home screen."))
            }
        }
        val service = ConsultService(
            sessions = ConsultSessions(tmp.root),
            skill = { doctor },
            agentFor = {
                MasalaAgent(
                    model = model,
                    tools = AgentToolRunner { _, _ ->
                        ToolResult.Success(
                            "Mini app 'a1' created and pinned to home.",
                            produced = listOf(com.samosaai.masala.core.tool.Produced("mini_app", "Snake", "a1")),
                        )
                    },
                    definitions = listOf(ToolDefinition("build", "", JsonObject(emptyMap()))),
                    scope = ToolScope.command(listOf(ToolInfo("build", ToolSensitivity.CHANGES_SOMETHING))),
                    systemPrompt = { "s" },
                    maxSteps = 4,
                )
            },
        )
        val answered = service.consult("chat-1", "doctor", "make me a snake game", null)
            as ConsultService.Outcome.Answered
        assertEquals(listOf(com.samosaai.masala.core.tool.Produced("mini_app", "Snake", "a1")), answered.produced)
    }

    @Test
    fun tenExpertsIsTheChatsLimit() = runBlocking<Unit> {
        val (service, _) = service(*Array(10) { "answer" })
        repeat(10) { n ->
            val skill = MasalaSkill(
                id = "expert$n",
                title = "Expert $n",
                instructions = "i",
                persona = Persona(systemPrompt = "you are expert $n"),
            )
            val sessions = ConsultSessions(tmp.root)
            ConsultService(
                sessions = sessions,
                skill = { skill },
                agentFor = { _ ->
                    MasalaAgent(
                        model = Script(ArrayDeque(listOf("answer"))),
                        tools = AgentToolRunner { _, _ -> ToolResult.Failure("no") },
                        definitions = emptyList(),
                        scope = ToolScope.command(emptyList()),
                        systemPrompt = { "s" },
                        maxSteps = 2,
                    )
                },
            ).consult("chat-1", "expert$n", "q", null)
        }
        val refused = service.consult("chat-1", "doctor", "q", null)
        assertTrue(refused is ConsultService.Outcome.Refused)
        assertEquals(10, ConsultSessions(tmp.root).list("chat-1").size)
    }

    @Test
    fun aModelFailureIsRefusedAndRecordedHonestly() = runBlocking<Unit> {
        val script = Script(ArrayDeque(emptyList()))
        val sessions = ConsultSessions(tmp.root)
        val service = ConsultService(
            sessions = sessions,
            skill = { doctor },
            agentFor = {
                MasalaAgent(
                    model = script,
                    tools = AgentToolRunner { _, _ -> ToolResult.Failure("no") },
                    definitions = emptyList(),
                    scope = ToolScope.command(emptyList()),
                    systemPrompt = { "s" },
                    maxSteps = 2,
                )
            },
        )
        val refused = service.consult("chat-1", "doctor", "headache", null)
        assertTrue(refused is ConsultService.Outcome.Refused)
        val recorded = ConsultSessions(tmp.root).list("chat-1").single()
        assertEquals(TurnRole.ERROR, recorded.turns.last().role)
        assertEquals("headache", recorded.turns.first().text)
    }

    @Test
    fun theTextNamesTheConsultListSoItCanBeRecalled() = runBlocking<Unit> {
        val (service, _) = service("Ask about the pain.", "Use what you have.")
        service.consult("chat-1", "doctor", "headache", null)
        val chef = service.consult("chat-1", "chef", "dinner", null) as ConsultService.Outcome.Answered
        assertTrue(chef.text.contains("chat-1-c1 Doctor (1/10)"))
        assertTrue(chef.text.contains("chat-1-c2 Chef (1/10)"))
        assertFalse(chef.text.contains("Earlier in this consult"))
    }
}
