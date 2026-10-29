package com.samosaai.masala.skills

import com.samosaai.masala.agent.AgentModel
import com.samosaai.masala.agent.MasalaAgent
import com.samosaai.masala.core.agent.ToolInfo
import com.samosaai.masala.core.agent.ToolScope
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.contract.SkillInput
import com.samosaai.masala.core.tool.Produced
import com.samosaai.masala.core.tool.ToolDefinition
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.core.tool.ToolSensitivity
import com.samosaai.masala.data.SkillArtifactDao
import com.samosaai.masala.data.SkillArtifactEntity
import com.samosaai.masala.data.SkillRunDao
import com.samosaai.masala.data.SkillRunEntity
import com.samosaai.masala.llm.ChatMessage
import com.samosaai.masala.llm.FunctionCall
import com.samosaai.masala.llm.ToolCall
import com.samosaai.masala.llm.attachmentsUserMessage
import com.samosaai.masala.core.contract.MasalaJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SkillRunnerTest {
    private class FakeRuns : SkillRunDao {
        val rows = MutableStateFlow<List<SkillRunEntity>>(emptyList())
        override suspend fun insert(run: SkillRunEntity): Long {
            val id = rows.value.size + 1L
            rows.value = rows.value + run.copy(id = id)
            return id
        }
        override suspend fun update(run: SkillRunEntity) { rows.value = rows.value.map { if (it.id == run.id) run else it } }
        override suspend fun get(id: Long) = rows.value.firstOrNull { it.id == id }
        override fun latestFor(skillId: String): Flow<SkillRunEntity?> = rows.map { r -> r.lastOrNull { it.skillId == skillId } }
        override fun runsFor(skillId: String): Flow<List<SkillRunEntity>> = rows.map { r -> r.filter { it.skillId == skillId } }
    }

    private class FakeArtifacts : SkillArtifactDao {
        val rows = mutableListOf<SkillArtifactEntity>()
        override suspend fun insert(artifact: SkillArtifactEntity): Long { rows += artifact.copy(id = rows.size + 1L); return rows.size.toLong() }
        override fun forRun(runId: Long): Flow<List<SkillArtifactEntity>> = MutableStateFlow(rows.filter { it.runId == runId })
        override suspend fun deleteSame(runId: Long, kind: String, ref: String) { rows.removeAll { it.runId == runId && it.kind == kind && it.ref == ref } }
    }

    private val infos = listOf(
        ToolInfo("web_search", ToolSensitivity.READ_ONLY),
        ToolInfo("set_alarm", ToolSensitivity.CHANGES_SOMETHING),
    )
    private val definitions = infos.map { ToolDefinition(it.name, "", JsonObject(emptyMap())) }
    private val chef = MasalaSkill(id = "chef", title = "Chef", instructions = "Cook.", requiresTools = listOf("web_search"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runs = FakeRuns()
    private val artifacts = FakeArtifacts()
    private val transcripts = TranscriptFiles(kotlin.io.path.createTempDirectory("runs").toFile().apply { deleteOnExit() })
    private val scopesSeen = mutableListOf<Set<String>>()

    private fun reply(text: String, vararg calls: ToolCall) =
        ChatMessage("assistant", JsonPrimitive(text), toolCalls = calls.toList().ifEmpty { null })
    private fun call(id: String, name: String) = ToolCall(id, function = FunctionCall(name, "{}"))
    private fun user(text: String) = ChatMessage("user", JsonPrimitive(text))

    private fun runner(vararg replies: ChatMessage, tool: (String) -> ToolResult = { ToolResult.Success("ok") }): SkillRunner {
        val queue = ArrayDeque(replies.toList())
        return SkillRunner(scope, runs, artifacts, transcripts, { if (it == "chef") chef else null }, { skill, unattended ->
            val toolScope = ToolScope.skill(skill, infos).let { if (unattended) ToolScope.unattended(it, infos) else it }
            scopesSeen += toolScope.names
            MasalaAgent(AgentModel { _, _ -> queue.removeFirst() }, { name, _ -> tool(name) }, definitions, toolScope, { "system" })
        })
    }

    private suspend fun SkillRunner.finished(id: Long) = withTimeout(5_000) { open(id)!!.first { !it.running && it.events.any { e -> e is SkillRunEvent.Done || e is SkillRunEvent.Failed } } }

    @Test fun transcriptStatusAndAnswerAreSaved() = runBlocking {
        val r = runner(reply("Try an omelette"))
        val id = r.start("chef", user("eggs"))!!
        val state = r.finished(id)
        assertEquals(
            listOf(SkillRunEvent.Message(SkillRunEvent.Message.Role.USER, "eggs"), SkillRunEvent.Message(SkillRunEvent.Message.Role.ASSISTANT, "Try an omelette"), SkillRunEvent.Done),
            state.events,
        )
        val row = runs.get(id)!!
        assertEquals("done", row.status)
        assertNotNull(row.finishedAt)
        assertEquals("Try an omelette", SkillRunner.lastAnswer(row.transcriptJson))
    }

    @Test fun unknownSkillStartsNothing() = runBlocking {
        assertEquals(null, runner().start("nope", user("x")))
        assertTrue(runs.rows.value.isEmpty())
    }

    @Test fun skillScopeIsAppliedToTheAgent() = runBlocking {
        val r = runner(reply("hi"))
        r.finished(r.start("chef", user("x"))!!)
        assertEquals(setOf("web_search"), scopesSeen.single())
    }

    @Test fun toolStepsAreMarkedDoneAndFailed() = runBlocking {
        val r = runner(reply("", call("1", "web_search")), reply("done"), tool = { ToolResult.Failure("offline") })
        val state = r.finished(r.start("chef", user("x"))!!)
        assertEquals(SkillRunEvent.ToolStep("web_search", false), state.events.filterIsInstance<SkillRunEvent.ToolStep>().single())
    }

    @Test fun resultFilesBecomeArtifacts() = runBlocking {
        val r = runner(reply("", call("1", "web_search")), reply("saved")) { ToolResult.Success("ok", listOf("/data/out/menu.pdf")) }
        val id = r.start("chef", user("x"))!!
        val state = r.finished(id)
        assertTrue(state.events.contains(SkillRunEvent.Result("file", "menu.pdf", "/data/out/menu.pdf")))
        assertEquals(listOf("menu.pdf"), artifacts.rows.map { it.title })
        assertEquals(id, artifacts.rows.single().runId)
    }

    @Test fun aMiniAppResultIsKeptAndItsUpdateReplacesTheCard() = runBlocking {
        val app = Produced("mini_app", "Snake", "ab12cd34")
        val r = runner(reply("", call("1", "web_search")), reply("", call("2", "web_search")), reply("Made it")) {
            ToolResult.Success("ok", produced = listOf(app))
        }
        val id = r.start("chef", user("snake"))!!
        val state = r.finished(id)
        assertEquals(listOf(SkillRunEvent.Result("mini_app", "Snake", "ab12cd34")), state.events.filterIsInstance<SkillRunEvent.Result>())
        assertEquals(1, artifacts.rows.size)
        // After a restart the card is rebuilt from storage.
        assertEquals(listOf(SkillRunEvent.Result("mini_app", "Snake", "ab12cd34")), runner().open(id)!!.value.events.filterIsInstance<SkillRunEvent.Result>())
    }

    @Test fun failedModelMarksTheRunFailed() = runBlocking {
        val r = SkillRunner(scope, runs, artifacts, transcripts, { chef }, { _, _ -> MasalaAgent(AgentModel { _, _ -> error("offline") }, { _, _ -> ToolResult.Success("") }, definitions, ToolScope.command(infos), { "s" }) })
        val id = r.start("chef", user("x"))!!
        val state = r.finished(id)
        assertEquals(SkillRunEvent.Failed("offline"), state.events.last())
        assertEquals("failed", runs.get(id)!!.status)
    }

    @Test fun followUpContinuesTheRun() = runBlocking {
        val r = runner(reply("one"), reply("two"))
        val id = r.start("chef", user("a"))!!
        r.finished(id)
        r.send(id, user("b"))
        val state = withTimeout(5_000) { r.open(id)!!.first { !it.running && it.events.count { e -> e is SkillRunEvent.Done } == 2 } }
        assertEquals(listOf("a", "one", "b", "two"), state.events.filterIsInstance<SkillRunEvent.Message>().map { it.text })
    }

    @Test fun unattendedRunsRefuseToolsThatChangeSomething() = runBlocking<Unit> {
        val ran = mutableListOf<String>()
        val skill = chef.copy(requiresTools = listOf("web_search", "set_alarm"))
        val queue = ArrayDeque(listOf(reply("", call("1", "set_alarm")), reply("I couldn't set it")))
        val r = SkillRunner(scope, runs, artifacts, transcripts, { skill }, { s, unattended ->
            val toolScope = ToolScope.skill(s, infos).let { if (unattended) ToolScope.unattended(it, infos) else it }
            MasalaAgent(AgentModel { _, _ -> queue.removeFirst() }, { name, _ -> ran += name; ToolResult.Success("ok") }, definitions, toolScope, { "system" })
        })
        val id = r.start("chef", user("wake me"), unattended = true)!!
        val state = r.finished(id)
        assertEquals(emptyList(), ran)
        // A refused call never starts a step, so the sheet shows no tool ran.
        assertTrue(state.events.none { it is SkillRunEvent.ToolStep })
        assertEquals("done", r.outcome(id)!!.first)
    }

    @Test fun attendedRunsKeepTheirChangingTools() = runBlocking<Unit> {
        val ran = mutableListOf<String>()
        val skill = chef.copy(requiresTools = listOf("web_search", "set_alarm"))
        val queue = ArrayDeque(listOf(reply("", call("1", "set_alarm")), reply("Set")))
        val r = SkillRunner(scope, runs, artifacts, transcripts, { skill }, { s, _ ->
            MasalaAgent(AgentModel { _, _ -> queue.removeFirst() }, { name, _ -> ran += name; ToolResult.Success("ok") }, definitions, ToolScope.skill(s, infos), { "system" })
        })
        r.finished(r.start("chef", user("wake me"))!!)
        assertEquals(listOf("set_alarm"), ran)
    }

    @Test fun outcomeReportsFailuresWithTheirError() = runBlocking<Unit> {
        val r = SkillRunner(scope, runs, artifacts, transcripts, { chef }, { _, _ -> MasalaAgent(AgentModel { _, _ -> error("offline") }, { _, _ -> ToolResult.Success("") }, definitions, ToolScope.command(infos), { "s" }) })
        val id = r.start("chef", user("x"))!!
        r.finished(id)
        assertEquals("failed" to "offline", r.outcome(id))
    }

    @Test fun savedTranscriptKeepsEveryExchange() = runBlocking {
        val r = runner(*Array(7) { reply("a$it") })
        val id = r.start("chef", user("q0"))!!
        r.finished(id)
        for (n in 1 until 7) {
            r.send(id, user("q$n"))
            withTimeout(5_000) { r.open(id)!!.first { !it.running && it.events.count { e -> e is SkillRunEvent.Done } == n + 1 } }
        }
        val saved = transcripts.read(id)!!
        assertEquals((0 until 7).flatMap { listOf("q$it", "a$it") }, saved.map { it.textContent })
        // Reopened after the app was killed, the sheet shows the whole conversation too.
        assertEquals(14, runner().open(id)!!.value.events.filterIsInstance<SkillRunEvent.Message>().size)
    }

    @Test fun theRowKeepsOnlyTheLastAnswerHoweverLongTheRunGets() = runBlocking {
        val big = "x".repeat(200_000)
        val r = runner(reply("", call("1", "web_search")), reply("the answer")) { ToolResult.Success(big) }
        val id = r.start("chef", user("q"))!!
        r.finished(id)
        val row = runs.get(id)!!.transcriptJson
        assertTrue(row.length < 1_000, "row is ${row.length} chars")
        assertEquals("the answer", SkillRunner.lastAnswer(row))
        assertTrue(transcripts.read(id)!!.any { it.role == "tool" && it.textContent == big })
    }

    @Test fun runsSavedInTheRowBeforeFilesStillOpen() = runBlocking {
        val legacy = MasalaJson.strict.encodeToString(ListSerializer(ChatMessage.serializer()), listOf(user("eggs"), reply("Omelette")))
        val id = runs.insert(SkillRunEntity(skillId = "chef", prompt = "eggs", status = "done", transcriptJson = legacy, startedAt = 0))
        assertEquals(listOf("eggs", "Omelette"), runner().open(id)!!.value.events.filterIsInstance<SkillRunEvent.Message>().map { it.text })
    }

    @Test fun reopenedRunsShowTheirResultFiles() = runBlocking {
        val r = runner(reply("", call("1", "web_search")), reply("saved")) { ToolResult.Success("ok", listOf("/data/out/song.mp3")) }
        val id = r.start("chef", user("x"))!!
        r.finished(id)
        // A new runner stands for the app after it was killed.
        val events = runner().open(id)!!.value.events
        assertEquals(listOf(SkillRunEvent.Result("audio", "song.mp3", "/data/out/song.mp3")), events.filterIsInstance<SkillRunEvent.Result>())
    }

    @Test fun aFollowUpOnAnUnattendedRunGetsTheSkillsChangingTools() = runBlocking<Unit> {
        val ran = mutableListOf<String>()
        val skill = chef.copy(requiresTools = listOf("web_search", "set_alarm"))
        val queue = ArrayDeque(listOf(reply("Morning brief"), reply("", call("1", "set_alarm")), reply("Set")))
        val r = SkillRunner(scope, runs, artifacts, transcripts, { skill }, { s, unattended ->
            val toolScope = ToolScope.skill(s, infos).let { if (unattended) ToolScope.unattended(it, infos) else it }
            MasalaAgent(AgentModel { _, _ -> queue.removeFirst() }, { name, _ -> ran += name; ToolResult.Success("ok") }, definitions, toolScope, { "system" })
        })
        val id = r.start("chef", user("brief"), unattended = true)!!
        r.finished(id)
        r.send(id, user("set an alarm for 7"))
        val state = withTimeout(5_000) { r.open(id)!!.first { !it.running && it.events.count { e -> e is SkillRunEvent.Done } == 2 } }
        assertEquals(listOf("set_alarm"), ran)
        assertEquals(listOf("brief", "Morning brief", "set an alarm for 7", "Set"), state.events.filterIsInstance<SkillRunEvent.Message>().map { it.text })
    }

    @Test fun finishedRunsAreLetGoAndRestoredWhenOpened() = runBlocking {
        val r = runner(*Array(SkillRunner.KEEP_IDLE + 3) { reply("a$it") })
        val ids = (0 until SkillRunner.KEEP_IDLE + 3).map { n -> r.start("chef", user("q$n"))!!.also { r.finished(it) } }
        // The oldest run was let go; opening it reads it back.
        withTimeout(5_000) { while (r.holds(ids.first())) kotlinx.coroutines.delay(10) }
        assertTrue(r.holds(ids.last()))
        val first = r.open(ids.first())!!
        assertEquals(listOf("q0", "a0"), first.value.events.filterIsInstance<SkillRunEvent.Message>().map { it.text })
    }

    @Test fun photosAreNotSavedButTheirTextIs() {
        val withPhoto = attachmentsUserMessage("what is this?", emptyList(), listOf("QUJD"))
        val stored = SkillRunner.withoutImages(listOf(withPhoto, user("plain")))
        assertEquals(0, stored[0].imageCount)
        assertEquals("what is this?", stored[0].textContent)
        assertEquals(user("plain"), stored[1])
    }

    @Test fun reopeningRestoresFromTheDatabase() = runBlocking {
        val first = runner(reply("Try an omelette"))
        val id = first.start("chef", user("eggs"))!!
        first.finished(id)
        // A new runner stands for the app after it was killed.
        val second = runner(reply("again"))
        val state = second.open(id)!!.value
        assertEquals(
            listOf("eggs", "Try an omelette"),
            state.events.filterIsInstance<SkillRunEvent.Message>().map { it.text },
        )
        assertEquals(false, state.running)
    }
}

class SkillMessageTest {
    private val photo = Attachment(SkillInput.PHOTO, "/a/p.jpg", "p.jpg")
    private val doc = Attachment(SkillInput.DOCUMENT, "/a/notes.pdf", "notes.pdf")

    @Test fun photosBecomeImagePartsAndFilesBecomePathNotes() {
        val m = SkillMessage.build("look", listOf(photo, doc)) { "BASE64" }
        assertEquals(1, m.imageCount)
        assertEquals("look\n\n[Attached file: /a/notes.pdf]", m.textContent)
    }

    @Test fun aFolderIsNamedAsAFolder() {
        val folder = Attachment(SkillInput.FOLDER, "/storage/emulated/0/Download/Taxes", "Taxes")
        assertEquals("sort these\n\n[Attached folder: /storage/emulated/0/Download/Taxes]", SkillMessage.build("sort these", listOf(folder)) { null }.textContent)
    }

    @Test fun pickerIdsBecomeStoragePaths() {
        val path = { id: String -> com.samosaai.masala.ui.skills.SkillInputs.storagePath(id, "/storage/emulated/0") }
        assertEquals("/storage/emulated/0/Download/Taxes", path("primary:Download/Taxes"))
        assertEquals("/storage/emulated/0", path("primary:"))
        assertEquals("/storage/emulated/0/Download/a.pdf", path("raw:/storage/emulated/0/Download/a.pdf"))
        assertEquals(null, path("1A2B-3C4D:Music"))
        assertEquals(null, path("msf:42"))
        assertEquals("/storage/emulated/10/Music", com.samosaai.masala.ui.skills.SkillInputs.storagePath("primary:Music", "/storage/emulated/10"))
    }

    @Test fun unreadablePhotoIsLeftOut() {
        assertEquals(0, SkillMessage.build("look", listOf(photo)) { null }.imageCount)
    }

    @Test fun textOnly() {
        val m = SkillMessage.build("  hi  ", emptyList()) { null }
        assertEquals("hi", m.textContent)
        assertEquals(0, m.imageCount)
    }
}
