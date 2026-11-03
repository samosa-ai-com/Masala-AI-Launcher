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
    private val spotify = MasalaAction.LaunchApp("com.spotify")

    private fun reply(text: String) = ChatMessage("assistant", JsonPrimitive(text))

    private fun controller(
        speech: SpeechInput? = null,
        summarize: (suspend (String) -> String?)? = null,
        cleaner: TranscriptCleaner? = null,
        maxContextTokens: Int = MasalaAgent.DEFAULT_MAX_CONTEXT_TOKENS,
    ) = CommandController(
        scope = scope,
        resolveApp = { if (it == "spotify") spotify else null },
        names = { listOf("Spotify") },
        run = { ran += it; ActionDispatcher.Outcome.Done("ok: $it") },
        skills = { listOf(chef) },
        newAgent = {
            agentsBuilt++
            MasalaAgent(
                AgentModel { messages, _ -> requests += messages; replies.removeFirst() },
                { _, _ -> ToolResult.Success("") },
                listOf(ToolDefinition("x", "", JsonObject(emptyMap()))),
                ToolScope.command(listOf(ToolInfo("x", ToolSensitivity.READ_ONLY))),
                { _ -> "system" },
            )
        },
        speech = speech,
        summarize = summarize,
        cleaner = cleaner,
        maxContextTokens = maxContextTokens,
    )

    // A ceiling for a hang, not a stopwatch: the turn runs on Dispatchers.Default, which also feeds
    // lint and the second test JVM, so a loaded machine can take seconds to publish a reply.
    private suspend fun CommandController.settled() = withTimeout(15_000) { state.first { it is CommandState.Reply || it is CommandState.Error } }

    @Test fun aGameRequestIsAnsweredInTheChat() = runBlocking<Unit> {
        replies += reply("I made Snake and put it on your home screen.")
        val c = controller()
        c.ask("make me a snake game")
        val answer = assertIs<CommandState.Reply>(c.settled())
        assertEquals("I made Snake and put it on your home screen.", answer.text)
        // No builder opens and no skill is started from the turn.
        assertEquals(emptyList<MasalaAction>(), ran)
        assertEquals(1, agentsBuilt)
    }

    @Test fun fastPathBypassesTheModel() = runBlocking<Unit> {
        val c = controller()
        c.ask("set a timer for 2 minutes")
        assertIs<CommandState.Reply>(c.settled())
        assertEquals(listOf<MasalaAction>(MasalaAction.StartTimer(120)), ran)
        assertEquals(0, agentsBuilt)
    }

    @Test fun aPersonaRequestRunsTheAgentInsteadOfOfferingAChip() = runBlocking<Unit> {
        replies += reply("The chef suggests an omelette.")
        val c = controller()
        c.ask("what can I cook with eggs")
        val reply = assertIs<CommandState.Reply>(c.settled())
        // Advice is answered in the chat: no hand-off chip, and the agent is the one that answers.
        assertEquals("The chef suggests an omelette.", reply.text)
        assertEquals(1, agentsBuilt)
    }

    @Test fun aCommandWithAPersonaKeywordStillRuns() = runBlocking<Unit> {
        replies += reply("Added to your calendar")
        val c = controller()
        c.ask("add cook dinner with Anna to my calendar")
        val reply = assertIs<CommandState.Reply>(c.settled())
        assertEquals("Added to your calendar", reply.text)
        assertEquals(1, agentsBuilt)
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
        withTimeout(15_000) { c.state.first { it is CommandState.Reply && it.text == "two" } }
        assertTrue(requests[1].any { it.textContent == "first question" })
        assertTrue(requests[1].any { it.textContent == "one" })
    }

    @Test fun modelFailureIsShownAsAnError() = runBlocking<Unit> {
        val c = CommandController(scope, { null }, { emptyList() }, { ActionDispatcher.Outcome.Done() }, { emptyList() }, {
            MasalaAgent(AgentModel { _, _ -> error("offline") }, { _, _ -> ToolResult.Success("") }, emptyList(), ToolScope.command(emptyList()), { _ -> "s" })
        })
        c.ask("hello")
        assertEquals("offline", assertIs<CommandState.Error>(c.settled()).message)
    }

    @Test fun aToolThatNeedsAPermissionStillYieldsAnAnswerAndAnOfferToGrantIt() = runBlocking<Unit> {
        val calls = ArrayDeque(listOf(
            ChatMessage("assistant", JsonPrimitive(""), toolCalls = listOf(com.samosaai.masala.llm.ToolCall("1", function = com.samosaai.masala.llm.FunctionCall("x", "{}")))),
            reply("I can't see your calendar without calendar access."),
        ))
        val c = CommandController(scope, { null }, { emptyList() }, { ActionDispatcher.Outcome.Done() }, { emptyList() }, {
            MasalaAgent(
                AgentModel { _, _ -> calls.removeFirst() },
                { _, _ -> ToolResult.NeedsCapability("android.permission.READ_CALENDAR", "Calendar access is needed") },
                listOf(ToolDefinition("x", "", JsonObject(emptyMap()))),
                ToolScope.command(listOf(ToolInfo("x", ToolSensitivity.READ_ONLY))),
                { _ -> "s" },
            )
        })
        c.ask("what is on my calendar")
        val answer = assertIs<CommandState.Reply>(c.settled())
        assertEquals("I can't see your calendar without calendar access.", answer.text)
        // The answer arrives, and the missing access is carried so the card can offer Grant.
        assertEquals(listOf("android.permission.READ_CALENDAR"), answer.capabilities)
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

    @Test fun theSpokenTranscriptIsCleanedBeforeItIsSent() = runBlocking<Unit> {
        val speech = object : SpeechInput {
            override fun start(onFailure: (String) -> Unit) = true
            override suspend fun stop() = Result.success("whats the weather like today")
            override fun cancel() = Unit
        }
        val cleaned = mutableListOf<String>()
        val recorded = mutableListOf<com.samosaai.masala.chat.ChatTurn>()
        replies += reply("Sunny.")
        val c = controller(speech, cleaner = { cleaned += it; "What's the weather like today?" })
        c.onTurn = { recorded += it }
        assertTrue(c.startListening())
        c.stopListening()
        assertIs<CommandState.Reply>(c.settled())
        assertEquals(listOf("whats the weather like today"), cleaned)
        val user = recorded.first { it.role == com.samosaai.masala.chat.TurnRole.USER }
        assertEquals("What's the weather like today?", user.text)
        assertEquals("What's the weather like today?", requests.last().last().textContent)
    }

    @Test fun aFastPathCommandSkipsTheCleaner() = runBlocking<Unit> {
        val speech = object : SpeechInput {
            override fun start(onFailure: (String) -> Unit) = true
            override suspend fun stop() = Result.success("open spotify")
            override fun cancel() = Unit
        }
        var cleaned = false
        val c = controller(speech, cleaner = { cleaned = true; it })
        assertTrue(c.startListening())
        c.stopListening()
        c.settled()
        assertTrue(!cleaned)
        assertEquals(listOf<MasalaAction>(spotify), ran)
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

    @Test fun capReachedPausesInsteadOfSending() = runBlocking<Unit> {
        var listener: ((PttEngine.TakeAutoEnd) -> Unit)? = null
        var paused = 0
        val speech = object : SpeechInput {
            override fun start(onFailure: (String) -> Unit) = true
            override suspend fun stop() = Result.success("set a timer for 1 minute")
            override fun cancel() = Unit
            override suspend fun pause(): Boolean {
                paused++
                return true
            }
            override fun setTakeAutoEndListener(l: ((PttEngine.TakeAutoEnd) -> Unit)?) { listener = l }
        }
        val c = controller(speech)
        assertTrue(c.startListening())
        // The 5-minute cap pauses with the audio kept; nothing is sent by itself.
        listener?.invoke(PttEngine.TakeAutoEnd.CapReached)
        // Paused is advertised only after the engine stop settles; wait for both.
        withTimeout(5_000) { while (paused == 0) kotlinx.coroutines.delay(50) }
        assertEquals(1, paused)
        withTimeout(5_000) { while (c.state.value != CommandState.ListeningPaused) kotlinx.coroutines.delay(50) }
        assertEquals(emptyList(), ran)
    }

    @Test fun continueDuringASettlingPauseIsIgnored() = runBlocking<Unit> {
        var listener: ((PttEngine.TakeAutoEnd) -> Unit)? = null
        val releasePause = kotlinx.coroutines.CompletableDeferred<Boolean>()
        var resumed = 0
        val speech = object : SpeechInput {
            override fun start(onFailure: (String) -> Unit) = true
            override suspend fun stop() = Result.success("set a timer for 1 minute")
            override fun cancel() = Unit
            override suspend fun pause() = releasePause.await()
            override fun resume(): Boolean {
                resumed++
                return true
            }
            override fun setTakeAutoEndListener(l: ((PttEngine.TakeAutoEnd) -> Unit)?) { listener = l }
        }
        val c = controller(speech)
        assertTrue(c.startListening())
        listener?.invoke(PttEngine.TakeAutoEnd.CapReached)
        // The pause hasn't settled: Continue must not resume into an auto-send.
        kotlinx.coroutines.delay(100)
        assertEquals(CommandState.Listening, c.state.value)
        c.continueListening()
        kotlinx.coroutines.delay(100)
        assertEquals(CommandState.Listening, c.state.value)
        assertEquals(0, resumed)
        assertEquals(emptyList(), ran)
        // The pause settles, and only then does Continue resume the take.
        releasePause.complete(true)
        withTimeout(5_000) { while (c.state.value != CommandState.ListeningPaused) kotlinx.coroutines.delay(50) }
        c.continueListening()
        assertEquals(CommandState.Listening, c.state.value)
        withTimeout(5_000) { while (resumed == 0) kotlinx.coroutines.delay(50) }
        assertEquals(1, resumed)
    }

    @Test fun continueListeningResumesAPausedTake() = runBlocking<Unit> {
        var listener: ((PttEngine.TakeAutoEnd) -> Unit)? = null
        var resumed = 0
        val speech = object : SpeechInput {
            override fun start(onFailure: (String) -> Unit) = true
            override suspend fun stop() = Result.success("set a timer for 1 minute")
            override fun cancel() = Unit
            override suspend fun pause() = true
            override fun resume(): Boolean {
                resumed++
                return true
            }
            override fun setTakeAutoEndListener(l: ((PttEngine.TakeAutoEnd) -> Unit)?) { listener = l }
        }
        val c = controller(speech)
        assertTrue(c.startListening())
        listener?.invoke(PttEngine.TakeAutoEnd.CapReached)
        // The pause settles on its own launch; wait for Paused before continuing.
        withTimeout(5_000) { while (c.state.value != CommandState.ListeningPaused) kotlinx.coroutines.delay(50) }
        c.continueListening()
        assertEquals(CommandState.Listening, c.state.value)
        withTimeout(5_000) { while (resumed == 0) kotlinx.coroutines.delay(50) }
        assertEquals(1, resumed)
        c.stopListening()
        assertIs<CommandState.Reply>(c.settled())
        assertEquals(listOf<MasalaAction>(MasalaAction.StartTimer(60)), ran)
    }

    @Test fun sendFromPausedTranscribesWhatWasKept() = runBlocking<Unit> {
        var listener: ((PttEngine.TakeAutoEnd) -> Unit)? = null
        val speech = object : SpeechInput {
            override fun start(onFailure: (String) -> Unit) = true
            override suspend fun stop() = Result.success("set a timer for 1 minute")
            override fun cancel() = Unit
            override suspend fun pause() = true
            override fun setTakeAutoEndListener(l: ((PttEngine.TakeAutoEnd) -> Unit)?) { listener = l }
        }
        val c = controller(speech)
        assertTrue(c.startListening())
        listener?.invoke(PttEngine.TakeAutoEnd.CapReached)
        withTimeout(5_000) { while (c.state.value != CommandState.ListeningPaused) kotlinx.coroutines.delay(50) }
        c.stopListening()
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
        val c = controller(summarize = { asked++; "SUMMARY: user asked for a timer app" }, maxContextTokens = 1_000)
        c.onTurn = { recorded += it }
        // Well past the compaction line for this test's deliberately small window.
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

    @Test fun newChatsStartFromTheGlobalAllowAll() {
        val c = controller()
        c.setAllowAllDefault(true)
        assertTrue(c.allowAll.value)
        c.restoreSession(emptyList())
        assertTrue(c.allowAll.value)
        // A chat's own switch wins over the default, even when the default moves.
        c.setAllowAll(false)
        c.setAllowAllDefault(true)
        assertEquals(false, c.allowAll.value)
        // Coming back to the same chat keeps its switch; another chat starts from the default again.
        c.restoreSession(emptyList(), sameChat = true)
        assertEquals(false, c.allowAll.value)
        c.restoreSession(emptyList())
        assertTrue(c.allowAll.value)
        c.setAllowAllDefault(false)
        assertEquals(false, c.allowAll.value)
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

    @Test fun aMadeAppIsCarriedOnTheReplyAndTheStoredTurn() = runBlocking<Unit> {
        val scripts = ArrayDeque(
            listOf(
                ChatMessage(
                    "assistant",
                    JsonPrimitive(""),
                    toolCalls = listOf(
                        com.samosaai.masala.llm.ToolCall(
                            "1",
                            function = com.samosaai.masala.llm.FunctionCall("build", "{}"),
                        ),
                    ),
                ),
                reply("I made Snake and put it on your home screen."),
            ),
        )
        val c = CommandController(
            scope = scope,
            resolveApp = { null },
            names = { emptyList() },
            run = { ActionDispatcher.Outcome.Done() },
            skills = { emptyList() },
            newAgent = {
                MasalaAgent(
                    AgentModel { _, _ -> scripts.removeFirst() },
                    { _, _ ->
                        ToolResult.Success(
                            "Mini app 'a1' created and pinned to home.",
                            produced = listOf(com.samosaai.masala.core.tool.Produced("mini_app", "Snake", "a1")),
                        )
                    },
                    listOf(ToolDefinition("build", "", JsonObject(emptyMap()))),
                    ToolScope.command(listOf(ToolInfo("build", ToolSensitivity.CHANGES_SOMETHING))),
                    { _ -> "system" },
                )
            },
        )
        val stored = mutableListOf<com.samosaai.masala.chat.ChatTurn>()
        c.onTurn = { stored += it }
        c.ask("make me a snake game")
        val settled = assertIs<CommandState.Reply>(c.settled())
        val expected = listOf(com.samosaai.masala.chat.ChatResult("mini_app", "Snake", "a1"))
        assertEquals(expected, settled.results)
        // The card has to survive a reload, so the turn keeps it too.
        assertEquals(expected, stored.last().results)
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
                    { _ -> "system" },
                )
            },
        )
        val stored = mutableListOf<com.samosaai.masala.chat.ChatTurn>()
        c.onTurn = { stored += it }
        c.ask("risotto idea")
        val settled = assertIs<CommandState.Reply>(c.settled())
        assertEquals("The chef suggests risotto.", settled.text)
        // What lands in the session keeps the credit, so the badge survives a reload.
        assertEquals(listOf(com.samosaai.masala.chat.ExpertCredit("chef", "Chef")), stored.last().experts)
        assertEquals(listOf(com.samosaai.masala.chat.ExpertCredit("chef", "Chef")), settled.experts)
        assertTrue(settled.steps.contains("Consulting Chef"))
    }

    @Test fun minimizeNeverCancelsOnlyDismissDoes() = runBlocking<Unit> {
        val c = controller()
        assertFalse(c.minimized.value)
        c.minimize()
        assertTrue(c.minimized.value)
        c.focus()
        assertFalse(c.minimized.value)
        c.minimize()
        c.dismiss()
        assertFalse(c.minimized.value)
        assertIs<CommandState.Idle>(c.state.value)
    }

    @Test fun imageAskSendsVisionPartsWithPlaceholderTurn() = runBlocking<Unit> {
        replies += reply("A red bicycle.")
        val c = controller()
        val stored = mutableListOf<com.samosaai.masala.chat.ChatTurn>()
        c.onTurn = { stored += it }
        c.ask("", images = listOf("QUJD"))
        val answer = assertIs<CommandState.Reply>(c.settled())
        assertEquals("A red bicycle.", answer.text)
        // The stored turn keeps the marker; the model got the bytes.
        assertEquals(com.samosaai.masala.chat.IMAGE_PLACEHOLDER, stored.first().text)
        val sent = requests.flatten().last().content.toString()
        assertTrue(sent.contains("image_url"), "vision parts missing: $sent")
        assertTrue(sent.contains("data:image/jpeg;base64,QUJD"), "image bytes missing: $sent")
    }
}
