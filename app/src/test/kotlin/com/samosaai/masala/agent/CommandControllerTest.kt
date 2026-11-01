package com.samosaai.masala.agent

import com.samosaai.masala.actions.ActionDispatcher
import com.samosaai.masala.audio.PttEngine
import com.samosaai.masala.core.agent.ToolInfo
import com.samosaai.masala.core.agent.ToolScope
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.contract.Persona
import com.samosaai.masala.core.contract.SkillOutput
import com.samosaai.masala.core.contract.SkillWidget
import com.samosaai.masala.core.tool.ToolDefinition
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.core.tool.ToolSensitivity
import com.samosaai.masala.llm.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ran = mutableListOf<MasalaAction>()
    private val requests = mutableListOf<List<ChatMessage>>()
    private var agentsBuilt = 0
    private val replies = ArrayDeque<ChatMessage>()
    private val chef = MasalaSkill(id = "chef", title = "Chef", instructions = "i", keywords = listOf("cook"), persona = Persona("chef"))
    private val gameMaker = MasalaSkill(
        id = "game_maker", title = "Game maker", instructions = "i", keywords = listOf("game"),
        widget = SkillWidget("sports_esports", output = SkillOutput.MINI_APP),
    )
    private val spotify = MasalaAction.LaunchApp("com.spotify")

    private fun reply(text: String) = ChatMessage("assistant", JsonPrimitive(text))

    private fun controller(
        speech: SpeechInput? = null,
        summarize: (suspend (String) -> String?)? = null,
    ) = CommandController(
        scope = scope,
        resolveApp = { if (it == "spotify") spotify else null },
        names = { listOf("Spotify") },
        run = { ran += it; ActionDispatcher.Outcome.Done("ok: $it") },
        skills = { listOf(chef, gameMaker) },
        newAgent = {
            agentsBuilt++
            MasalaAgent(
                AgentModel { messages, _ -> requests += messages; replies.removeFirst() },
                { _, _ -> ToolResult.Success("") },
                listOf(ToolDefinition("x", "", JsonObject(emptyMap()))),
                ToolScope.command(listOf(ToolInfo("x", ToolSensitivity.READ_ONLY))),
                { "system" },
            )
        },
        speech = speech,
        summarize = summarize,
    )

    private suspend fun CommandController.settled() = withTimeout(5_000) { state.first { it is CommandState.Reply || it is CommandState.Error } }

    @Test fun aGameRequestOpensTheGameMaker() = runBlocking<Unit> {
        val c = controller()
        c.ask("make me a snake game")
        assertEquals(CommandState.Reply("make me a snake game", "Opened Game maker."), c.settled())
        assertEquals(listOf<MasalaAction>(MasalaAction.RunSkill("game_maker", "make me a snake game")), ran)
        assertEquals(0, agentsBuilt)
    }

    @Test fun fastPathBypassesTheModel() = runBlocking<Unit> {
        val c = controller()
        c.ask("set a timer for 2 minutes")
        assertIs<CommandState.Reply>(c.settled())
        assertEquals(listOf<MasalaAction>(MasalaAction.StartTimer(120)), ran)
        assertEquals(0, agentsBuilt)
    }

    @Test fun personaRequestOffersAChipWithoutCallingTheModel() = runBlocking<Unit> {
        val c = controller()
        c.ask("what can I cook with eggs")
        val reply = assertIs<CommandState.Reply>(c.settled())
        assertEquals(SkillChip("chef", "Chef"), reply.skill)
        assertEquals(0, agentsBuilt)
    }

    @Test fun aCommandWithAPersonaKeywordStillRuns() = runBlocking<Unit> {
        replies += reply("Added to your calendar")
        val c = controller()
        c.ask("add cook dinner with Anna to my calendar")
        val reply = assertIs<CommandState.Reply>(c.settled())
        assertEquals("Added to your calendar", reply.text)
        assertEquals(1, agentsBuilt)
        // The persona is still offered next to the answer.
        assertEquals(SkillChip("chef", "Chef"), reply.skill)
    }

    @Test fun eachReplyIsReadAloudOnce() = runBlocking<Unit> {
        replies += reply("one")
        replies += reply("one")
        val c = controller()
        c.ask("q")
        val first = assertIs<CommandState.Reply>(c.settled())
        assertTrue(c.claimReadAloud(first))
        // The card composed again (drawer closed, assist opened) shows the same reply.
        assertEquals(false, c.claimReadAloud(first))
        c.ask("q")
        val second = withTimeout(5_000) { c.state.first { it is CommandState.Reply && it !== first } as CommandState.Reply }
        // An equal reply to a new command is still new.
        assertTrue(c.claimReadAloud(second))
    }

    @Test fun otherRequestsGoToTheAgent() = runBlocking<Unit> {
        replies += reply("It is sunny")
        val c = controller()
        c.ask("how is the weather")
        assertEquals("It is sunny", assertIs<CommandState.Reply>(c.settled()).text)
        assertEquals(1, agentsBuilt)
    }

    @Test fun followUpCarriesHistory() = runBlocking<Unit> {
        replies += reply("one")
        replies += reply("two")
        val c = controller()
        c.ask("first question")
        c.settled()
        c.ask("and then?")
        withTimeout(5_000) { c.state.first { it is CommandState.Reply && it.text == "two" } }
        assertTrue(requests[1].any { it.textContent == "first question" })
        assertTrue(requests[1].any { it.textContent == "one" })
    }

    @Test fun modelFailureIsShownAsAnError() = runBlocking<Unit> {
        val c = CommandController(scope, { null }, { emptyList() }, { ActionDispatcher.Outcome.Done() }, { emptyList() }, {
            MasalaAgent(AgentModel { _, _ -> error("offline") }, { _, _ -> ToolResult.Success("") }, emptyList(), ToolScope.command(emptyList()), { "s" })
        })
        c.ask("hello")
        assertEquals("offline", assertIs<CommandState.Error>(c.settled()).message)
    }

    @Test fun aToolThatNeedsAPermissionOffersToGrantIt() = runBlocking<Unit> {
        val calls = ArrayDeque(listOf(
            ChatMessage("assistant", JsonPrimitive(""), toolCalls = listOf(com.samosaai.masala.llm.ToolCall("1", function = com.samosaai.masala.llm.FunctionCall("x", "{}")))),
        ))
        val c = CommandController(scope, { null }, { emptyList() }, { ActionDispatcher.Outcome.Done() }, { emptyList() }, {
            MasalaAgent(
                AgentModel { _, _ -> calls.removeFirst() },
                { _, _ -> ToolResult.NeedsCapability("android.permission.READ_CALENDAR", "Calendar access is needed") },
                listOf(ToolDefinition("x", "", JsonObject(emptyMap()))),
                ToolScope.command(listOf(ToolInfo("x", ToolSensitivity.READ_ONLY))),
                { "s" },
            )
        })
        c.ask("what is on my calendar")
        val error = assertIs<CommandState.Error>(c.settled())
        assertEquals("android.permission.READ_CALENDAR", error.capability)
        assertEquals("Calendar access is needed", error.message)
    }

    @Test fun spokenCommandsGetNamesCorrected() = runBlocking<Unit> {
        val c = controller()
        c.ask("open spotfy", spoken = true)
        c.settled()
        assertEquals(listOf<MasalaAction>(spotify), ran)
    }

    @Test fun typedCommandsAreLeftAsTyped() = runBlocking<Unit> {
        replies += reply("no such app")
        val c = controller()
        c.ask("open spotfy")
        c.settled()
        assertEquals(emptyList(), ran)
    }

    @Test fun pushToTalkTurnsTheTranscriptIntoACommand() = runBlocking<Unit> {
        val speech = object : SpeechInput {
            var cancelled = false
            override fun start(onFailure: (String) -> Unit) = true
            override suspend fun stop() = Result.success("set a timer for 1 minute")
            override fun cancel() { cancelled = true }
        }
        val c = controller(speech)
        assertTrue(c.startListening())
        assertEquals(CommandState.Listening, c.state.value)
        c.stopListening()
        c.settled()
        assertEquals(listOf<MasalaAction>(MasalaAction.StartTimer(60)), ran)
    }

    @Test fun engineFailureEndsTheTakeWithItsMessage() = runBlocking<Unit> {
        var listener: ((PttEngine.TakeAutoEnd) -> Unit)? = null
        val speech = object : SpeechInput {
            override fun start(onFailure: (String) -> Unit) = true
            override suspend fun stop() = Result.failure<String>(Exception("unused"))
            override fun cancel() = Unit
            override fun setTakeAutoEndListener(l: ((PttEngine.TakeAutoEnd) -> Unit)?) { listener = l }
        }
        val c = controller(speech)
        assertTrue(c.startListening())
        // A dead capture ends the take at once; the UI never sits on "Listening…".
        listener?.invoke(PttEngine.TakeAutoEnd.Failed("Recording captured no audio (microphone returned silence)"))
        val error = assertIs<CommandState.Error>(c.state.value)
        assertEquals("Recording captured no audio (microphone returned silence)", error.message)
    }

    @Test fun capReachedSendsWhatWasCaptured() = runBlocking<Unit> {
        var listener: ((PttEngine.TakeAutoEnd) -> Unit)? = null
        val speech = object : SpeechInput {
            override fun start(onFailure: (String) -> Unit) = true
            override suspend fun stop() = Result.success("set a timer for 1 minute")
            override fun cancel() = Unit
            override fun setTakeAutoEndListener(l: ((PttEngine.TakeAutoEnd) -> Unit)?) { listener = l }
        }
        val c = controller(speech)
        assertTrue(c.startListening())
        // The 90 s cap ends the take by sending what was heard; nothing is lost.
        listener?.invoke(PttEngine.TakeAutoEnd.CapReached)
        assertIs<CommandState.Reply>(c.settled())
        assertEquals(listOf<MasalaAction>(MasalaAction.StartTimer(60)), ran)
    }

    @Test fun sendNowStopsTheTake() = runBlocking<Unit> {
        var stopped = 0
        val speech = object : SpeechInput {
            override fun start(onFailure: (String) -> Unit) = true
            override suspend fun stop(): Result<String> {
                stopped++
                return Result.failure(Exception("stopped"))
            }
            override fun cancel() = Unit
        }
        val c = controller(speech)
        // A tap opens Sam; Send ends the take. Nothing ends it by itself.
        assertTrue(c.startListening())
        c.sendNow()
        c.settled()
        assertEquals(1, stopped)
    }

    @Test fun aLongConversationIsSummarisedBeforeTheRun() = runBlocking<Unit> {
        val recorded = mutableListOf<com.samosaai.masala.chat.ChatTurn>()
        var asked = 0
        val c = controller(summarize = { asked++; "SUMMARY: user asked for a timer app" })
        c.onTurn = { recorded += it }
        // Well past the 30k-character compaction threshold.
        c.restoreSession(
            List(60) { i ->
                com.samosaai.masala.chat.ChatTurn(
                    if (i % 2 == 0) com.samosaai.masala.chat.TurnRole.USER else com.samosaai.masala.chat.TurnRole.MASALA,
                    "message $i " + "x".repeat(600),
                )
            },
        )
        c.ask("what did we decide?")
        c.settled()
        assertEquals(1, asked)
        // The model sees the summary, not the transcript.
        val sent = requests.last().joinToString("\n") { it.textContent }
        assertTrue(sent.contains("SUMMARY: user asked for a timer app"))
        // And the transcript says so, as a system note the UI can show.
        assertTrue(recorded.any { it.role == com.samosaai.masala.chat.TurnRole.SYSTEM })
    }

    @Test fun listeningWithoutAMicrophoneIsAnError() = runBlocking<Unit> {
        val speech = object : SpeechInput {
            override fun start(onFailure: (String) -> Unit) = false
            override suspend fun stop() = Result.failure<String>(Exception())
            override fun cancel() = Unit
        }
        val c = controller(speech)
        assertEquals(false, c.startListening())
        assertIs<CommandState.Error>(c.state.value)
    }

    @Test fun startFailureKeepsTheSpecificReason() = runBlocking<Unit> {
        val speech = object : SpeechInput {
            override fun start(onFailure: (String) -> Unit): Boolean {
                onFailure("no speech service")
                return false
            }
            override suspend fun stop() = Result.failure<String>(Exception())
            override fun cancel() = Unit
        }
        val c = controller(speech)
        assertEquals(false, c.startListening())
        assertEquals("no speech service", (c.state.value as CommandState.Error).message)
    }

    @Test fun asyncFailureDuringListeningUpdatesTheCard() = runBlocking<Unit> {
        var fail: ((String) -> Unit)? = null
        val speech = object : SpeechInput {
            override fun start(onFailure: (String) -> Unit): Boolean {
                fail = onFailure
                return true
            }
            override suspend fun stop() = Result.failure<String>(Exception())
            override fun cancel() = Unit
        }
        val c = controller(speech)
        assertEquals(true, c.startListening())
        assertEquals(CommandState.Listening, c.state.value)
        fail!!("recognizer died")
        assertEquals("recognizer died", (c.state.value as CommandState.Error).message)
    }

    @Test fun emptyTextOpensTheInput() = runBlocking<Unit> {
        val c = controller()
        c.ask("  ")
        assertEquals(CommandState.Input, c.state.value)
        c.dismiss()
        assertEquals(CommandState.Idle, c.state.value)
    }

    @Test fun aConsultCallNamesTheExpertAndCreditsIt() = runBlocking<Unit> {
        val args = kotlinx.serialization.json.buildJsonObject {
            put("skill_id", JsonPrimitive("chef"))
            put("question", JsonPrimitive("dinner"))
        }
        assertEquals(com.samosaai.masala.chat.ExpertCredit("chef", "Chef"), consultCredit("consult_expert", args, listOf(chef)))
        // An id with no installed skill still names something rather than blank.
        assertEquals(
            com.samosaai.masala.chat.ExpertCredit("gone", "gone"),
            consultCredit("consult_expert", kotlinx.serialization.json.buildJsonObject { put("skill_id", JsonPrimitive("gone")) }, listOf(chef)),
        )
        assertEquals(null, consultCredit("get_time", args, listOf(chef)))
    }

    @Test fun theReplyCarriesTheExpertItConsulted() = runBlocking<Unit> {
        val scripts = ArrayDeque(
            listOf(
                ChatMessage(
                    "assistant",
                    JsonPrimitive(""),
                    toolCalls = listOf(
                        com.samosaai.masala.llm.ToolCall(
                            "1",
                            function = com.samosaai.masala.llm.FunctionCall(
                                "consult_expert",
                                """{"skill_id":"chef","question":"dinner"}""",
                            ),
                        ),
                    ),
                ),
                reply("The chef suggests risotto."),
            ),
        )
        val c = CommandController(
            scope = scope,
            resolveApp = { null },
            names = { emptyList() },
            run = { ActionDispatcher.Outcome.Done() },
            skills = { listOf(chef) },
            newAgent = {
                MasalaAgent(
                    AgentModel { _, _ -> scripts.removeFirst() },
                    { _, _ -> ToolResult.Success("Chef: risotto.") },
                    listOf(ToolDefinition("consult_expert", "", JsonObject(emptyMap()))),
                    ToolScope.command(listOf(ToolInfo("consult_expert", ToolSensitivity.READ_ONLY, readsPrivateData = true))),
                    { "system" },
                )
            },
        )
        c.ask("risotto idea")
        val settled = assertIs<CommandState.Reply>(c.settled())
        assertEquals("The chef suggests risotto.", settled.text)
        assertEquals(listOf(com.samosaai.masala.chat.ExpertCredit("chef", "Chef")), settled.experts)
        assertTrue(settled.steps.contains("Consulting Chef"))
    }
}
