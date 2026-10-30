package com.samosaai.masala.secondbrain

import com.samosaai.masala.agent.AgentEvent
import com.samosaai.masala.agent.AgentModel
import com.samosaai.masala.agent.AgentPrompt
import com.samosaai.masala.agent.AgentToolRunner
import com.samosaai.masala.agent.MasalaAgent
import com.samosaai.masala.agent.MemoryTools
import com.samosaai.masala.core.agent.ToolInfo
import com.samosaai.masala.core.agent.ToolScope
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.core.tool.ToolSensitivity
import com.samosaai.masala.core.tool.name
import com.samosaai.masala.data.WikiDao
import com.samosaai.masala.data.WikiPageEntity
import com.samosaai.masala.llm.ChatMessage
import com.samosaai.masala.llm.FunctionCall
import com.samosaai.masala.llm.ToolCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P2: the file-backed repository behaves like the wiki did (propose → approve → file). */
class SecondBrainRepositoryTest {
    private class FakeDao : WikiDao {
        val rows = MutableStateFlow<List<WikiPageEntity>>(emptyList())
        override fun all() = rows.map { r -> r.sortedBy { it.title.lowercase() } }
        override suspend fun get(id: String) = rows.value.firstOrNull { it.id == id }
        override suspend fun upsert(page: WikiPageEntity) { rows.value = rows.value.filterNot { it.id == page.id } + page }
        override suspend fun delete(id: String) { rows.value = rows.value.filterNot { it.id == id } }
    }

    private lateinit var dir: File
    private lateinit var dao: FakeDao
    private lateinit var audited: MutableList<Pair<String, String>>
    private lateinit var brain: SecondBrainRepository

    private fun fresh() {
        dir = Files.createTempDirectory("sb-repo").toFile()
        dao = FakeDao()
        audited = mutableListOf()
        brain = SecondBrainRepository(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            SecondBrainStore(dir, today = { "2026-10-29" }),
            dao,
            audit = { a, d -> audited += a to d },
        )
        runBlocking { brain.ensure() }
    }

    private suspend fun titles() = brain.titles.first { it.isNotEmpty() }

    private fun note(title: String, body: String = "Body.") =
        "---\ntitle: $title\narea: Personal\ntype: note\nupdated: 2026-10-29\n---\n\n$body\n"

    private fun args(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test fun agentWritesShowAsRememberedWithUndo() = runBlocking<Unit> {
        fresh()
        val written = brain.write("notes/morning-routine.md", note("Morning routine", "Coffee, then run."))
        assertTrue(written.ok)
        assertTrue(brain.pages.first().any { brain.titleOf(it) == "Morning routine" })
        val card = brain.remembered.value.single()
        assertEquals("notes/morning-routine.md", card.id)
        assertTrue(brain.undo(card.undoId))
        assertTrue(brain.remembered.value.isEmpty())
        assertTrue(brain.pages.first().none { brain.titleOf(it) == "Morning routine" })
    }

    @Test fun dismissedCardKeepsTheChange() = runBlocking<Unit> {
        fresh()
        brain.write("notes/gym.md", note("Gym", "Mon, Wed"))
        brain.dismissRemembered(brain.remembered.value.single().undoId)
        assertTrue(brain.remembered.value.isEmpty())
        assertTrue(brain.pages.first().any { brain.titleOf(it) == "Gym" })
    }

    @Test fun refusedWritesLeaveNoCardAndCardsAreCapped() = runBlocking<Unit> {
        fresh()
        assertTrue(!brain.write("notes/x.md", "no frontmatter").ok)
        assertTrue(brain.remembered.value.isEmpty())
        repeat(8) { brain.write("notes/page-$it.md", note("Page $it")) }
        assertEquals(SecondBrainRepository.MAX_REMEMBERED, brain.remembered.value.size)
        assertEquals("notes/page-7.md", brain.remembered.value.first().id)
    }

    @Test fun datedNoteEditsAskFirstOtherWritesDoNot() = runBlocking<Unit> {
        fresh()
        val tools = MemoryTools.create(brain).associateBy { it.name }
        assertTrue(!tools.getValue("memory_write").needsConfirmation(args("""{"path":"notes/2026-10-29-x.md","content":"c"}""")))
        assertTrue(!tools.getValue("memory_edit").needsConfirmation(args("""{"path":"people/asha.md","old_text":"a","new_text":"b"}""")))
        assertTrue(tools.getValue("memory_edit").needsConfirmation(args("""{"path":"notes/2026-10-29-x.md","old_text":"a","new_text":"b"}""")))
        assertTrue(tools.getValue("memory_delete").needsConfirmation(args("""{"path":"notes/gym.md"}""")))
        assertTrue(!tools.getValue("memory_read").needsConfirmation(args("""{"path":"MEMORY.md"}""")))
    }

    @Test fun titlesFollowTheEntries() = runBlocking<Unit> {
        fresh()
        brain.save(null, "Zebra", "z")
        brain.save(null, "apple", "a")
        val expected = listOf("Health", "People", "Personal", "Work", "apple", "Zebra")
        withTimeout(5_000) { brain.titles.first { it == expected } }
        val zebra = brain.pages.first().first { brain.titleOf(it) == "Zebra" }
        brain.delete(brain.idOf(zebra))
        withTimeout(5_000) { brain.titles.first { it == expected - "Zebra" } }
    }

    @Test fun readRanksTitleMatchesFirst() = runBlocking<Unit> {
        fresh()
        brain.save(null, "Mum", "Birthday 3 May. Likes tulips.")
        brain.save(null, "Garden", "Plant tulips in autumn.")
        brain.save(null, "Work", "Standup at 9.")
        assertEquals(listOf("Mum", "Garden"), brain.read("mum tulips").map { brain.titleOf(it) })
        assertEquals(listOf("Garden"), brain.read("garden").map { brain.titleOf(it) })
        assertTrue(brain.read("nothing relevant xyz").isEmpty())
        assertTrue(brain.read("   ").isEmpty())
    }

    @Test fun exportHasEveryEntryWithoutFrontmatter() = runBlocking<Unit> {
        fresh()
        brain.save(null, "A", "one")
        brain.save(null, "B", "two")
        val exported = brain.exportMarkdown()
        assertTrue(exported.contains("# A\n\none"))
        assertTrue(exported.contains("# B\n\ntwo"))
    }

    @Test fun legacyWikiRowsMigrateOnce() = runBlocking<Unit> {
        fresh()
        // Fresh vault is empty of notes; add legacy rows directly to the dao.
        dao.upsert(WikiPageEntity("1", "Old note", "old body", "user", 0))
        // Second ensure migrates (first ensure ran on empty dao).
        brain.ensure()
        val expected = listOf("Health", "People", "Personal", "Work", "Old note")
        withTimeout(5_000) { brain.titles.first { it == expected } }
        assertTrue(dao.rows.value.isEmpty())
        // A further ensure with new legacy rows does not duplicate (notes exist now).
        dao.upsert(WikiPageEntity("2", "Old note", "changed", "user", 0))
        brain.ensure()
        withTimeout(5_000) { brain.titles.first { it == expected } }
        assertEquals(1, brain.pages.first().count { it.name.startsWith("old-note") })
    }

    @Test fun agentWritesThroughTheTool() = runBlocking<Unit> {
        fresh()
        val tools = MemoryTools.create(brain)
        val content = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(note("Coffee", "Oat flat white.")))
        val call = ToolCall("1", function = FunctionCall("memory_write", """{"path":"notes/coffee.md","content":$content}"""))
        val replies = ArrayDeque(listOf(ChatMessage("assistant", JsonPrimitive(""), toolCalls = listOf(call)), ChatMessage("assistant", JsonPrimitive("Remembered."))))
        val agent = MasalaAgent(
            AgentModel { _, _ -> replies.removeAt(0) },
            AgentToolRunner { name, args -> tools.first { it.name == name }.execute(args) },
            tools.map { it.definition },
            ToolScope.command(tools.map { ToolInfo(it.name, it.sensitivity) }),
            { "s" },
        )
        agent.run(ChatMessage("user", JsonPrimitive("I like oat flat whites"))).first { it is AgentEvent.Done }
        assertTrue(brain.pages.first().any { brain.titleOf(it) == "Coffee" })
        assertEquals(listOf("notes/coffee.md"), brain.remembered.value.map { it.id })
    }

    @Test fun memoryReadToolReturnsEntryText() = runBlocking<Unit> {
        fresh()
        brain.save(null, "Mum", "Likes tulips.")
        val read = MemoryTools.create(brain).first { it.name == "memory_read" }
        val result = read.execute(Json.parseToJsonElement("""{"query":"mum"}""") as JsonObject)
        assertTrue(result.toString().contains("Likes tulips."))
    }

    @Test fun memoryToolsExplainRefusalsAndFindByPath() = runBlocking<Unit> {
        fresh()
        val tools = MemoryTools.create(brain).associateBy { it.name }
        val refused = tools.getValue("memory_write").execute(args("""{"path":"people/ravi.md","content":"Ravi"}"""))
        assertTrue(refused is ToolResult.Failure && refused.message.contains("missing frontmatter") && refused.message.contains("PROTOCOL"))
        assertTrue(brain.remembered.value.isEmpty())
        brain.write("notes/gym.md", note("Gym", "Mondays."))
        val found = tools.getValue("memory_search").execute(args("""{"query":"mondays"}"""))
        assertTrue(found.toString().contains("notes/gym.md:8: Mondays."))
        val read = tools.getValue("memory_read").execute(args("""{"path":"notes/gym.md"}"""))
        assertTrue(read.toString().contains("Mondays."))
        val protocol = tools.getValue("memory_read").execute(args("""{"path":"PROTOCOL"}"""))
        assertTrue(protocol.toString().contains("Second Brain protocol"))
        assertTrue(tools.getValue("memory_read").execute(args("{}")) is ToolResult.Failure)
    }

    @Test fun promptBlockHasSoulMemoryAndRules() = runBlocking<Unit> {
        // Cold: a repository that never refreshed yields an empty block, never a crash.
        val coldRepo = SecondBrainRepository(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            SecondBrainStore(Files.createTempDirectory("sb-cold").toFile()),
        )
        assertTrue(coldRepo.promptNow().titles.isEmpty())
        fresh()
        brain.save(null, "Mum", "Likes tulips.")
        val prompt = brain.promptNow()
        assertTrue(prompt.titles.contains("Mum"))
        assertNotNull(prompt.soul)
        assertNotNull(prompt.memory)
        val text = AgentPrompt.build("now", emptyList(), prompt, null)
        assertTrue(text.contains("<soul_md>"))
        assertTrue(text.contains("<memory_md>"))
        assertTrue(text.contains("memory_write"))
        assertTrue(!text.contains("memory_propose"))
        assertTrue(text.contains("Mum"))
    }

    @Test fun theSavedProfileReachesTheAgent() = runBlocking<Unit> {
        fresh()
        brain.ensure()
        // An empty profile adds nothing to the prompt.
        assertEquals(null, brain.promptNow().user)
        brain.syncUser(mapOf("name" to "Asha", "location" to "Pune"))
        val text = AgentPrompt.build("now", emptyList(), brain.promptNow(), null)
        assertTrue(text.contains("<user_md>"))
        assertTrue(text.contains("- name: Asha"))
        assertTrue(text.contains("- location: Pune"))
        // memory_read finds the card too.
        val read = MemoryTools.create(brain).first { it.name == "memory_read" }
        val found = read.execute(Json.parseToJsonElement("""{"query":"user.md"}""") as JsonObject)
        assertTrue(found is ToolResult.Success && found.output.contains("Asha"), found.toString())
    }

    @Test fun migrationSkipsUnusableRowsButKeepsThem() = runBlocking<Unit> {
        fresh()
        dao.upsert(WikiPageEntity("1", "Good", "keep me", "user", 0))
        dao.upsert(WikiPageEntity("2", "", "no title", "user", 0))
        dao.upsert(WikiPageEntity("3", "Empty", "   ", "user", 0))
        brain.ensure()
        assertEquals(listOf("Health", "People", "Personal", "Work", "Good"), brain.titles.first { it.contains("Good") })
        // Only the good row was imported and deleted; unusable rows stay for later.
        assertEquals(listOf("2", "3"), dao.rows.value.map { it.id }.sorted())
        // A later ensure with a fixed row imports just that one.
        dao.upsert(WikiPageEntity("2", "Fixed", "now fine", "user", 1))
        brain.ensure()
        assertTrue(brain.titles.first { it.contains("Fixed") }.contains("Fixed"))
        assertEquals(listOf("3"), dao.rows.value.map { it.id })
    }

    @Test fun writeEditUndoAreLoggedAndRefreshTheTitles() = runBlocking<Unit> {
        fresh()
        val card = "---\ntitle: Asha Sharma\narea: Health\ntype: person\nupdated: 2026-10-29\n---\n\nPhysio, Tuesdays.\n"
        val written = brain.write("people/asha-sharma.md", card)
        assertTrue(written.ok, written.errors.toString())
        assertTrue("Asha Sharma" in brain.titles.first { "Asha Sharma" in it })
        val edited = brain.edit("people/asha-sharma.md", "Tuesdays", "Wednesdays")
        assertTrue(edited.ok)
        assertTrue(brain.undo(edited.undoId!!))
        assertTrue(File(dir, "people/asha-sharma.md").readText().contains("Tuesdays"))
        val refused = brain.write("people/ravi.md", "no frontmatter")
        assertTrue(!refused.ok)
        assertEquals(
            listOf("memory_write" to "people/asha-sharma.md", "memory_edit" to "people/asha-sharma.md", "memory_undo" to "people/asha-sharma.md"),
            audited.filter { it.first.startsWith("memory_") && it.first != "memory_approve" },
        )
    }

    @Test fun oneCardPerFileAndUndoStepsBack() = runBlocking<Unit> {
        fresh()
        brain.write("notes/gym.md", note("Gym", "Mondays."))
        brain.edit("notes/gym.md", "Mondays", "Tuesdays")
        brain.write("notes/tea.md", note("Tea", "Green."))
        // Newest first, one per file: the gym card is its edit, not the create.
        assertEquals(listOf("notes/tea.md" to "memory_write", "notes/gym.md" to "memory_edit"), brain.remembered.value.map { it.id to it.action })
        assertTrue(brain.undo(brain.remembered.value.first { it.id == "notes/gym.md" }.undoId))
        assertTrue(File(dir, "notes/gym.md").readText().contains("Mondays."))
        // The create is the gym card now; undoing it removes the file.
        val create = brain.remembered.value.first { it.id == "notes/gym.md" }
        assertEquals("memory_write", create.action)
        assertTrue(brain.undo(create.undoId))
        assertTrue(!File(dir, "notes/gym.md").exists())
        assertEquals(listOf("notes/tea.md"), brain.remembered.value.map { it.id })
        // Dismiss hides every change to that file.
        brain.edit("notes/tea.md", "Green", "Black")
        brain.dismissRemembered(brain.remembered.value.single().undoId)
        assertTrue(brain.remembered.value.isEmpty())
    }
}
