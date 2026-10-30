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

    @Test fun proposalWritesNothingUntilApproved() = runBlocking<Unit> {
        fresh()
        val proposal = assertNotNull(brain.propose("Morning routine", "Coffee, then run.", "user said so"))
        assertTrue(brain.pages.first().none { brain.titleOf(it) == "Morning routine" })
        assertEquals(listOf(proposal), brain.proposals.value)
        val file = assertNotNull(brain.approve(proposal.id))
        assertEquals("Morning routine", brain.titleOf(file))
        assertTrue(brain.proposals.value.isEmpty())
        assertEquals(listOf("memory_approve" to "Morning routine"), audited)
    }

    @Test fun dismissedProposalIsNeverWritten() = runBlocking<Unit> {
        fresh()
        val p = assertNotNull(brain.propose("Gym", "Mon, Wed", ""))
        brain.dismiss(p.id)
        assertNull(brain.approve(p.id))
        assertTrue(brain.pages.first().none { brain.titleOf(it) == "Gym" })
    }

    @Test fun approvingATitleThatExistsUpdatesTheFile() = runBlocking<Unit> {
        fresh()
        val first = assertNotNull(brain.save(null, "Gym", "Mon"))
        val p = assertNotNull(brain.propose("gym", "Mon, Wed", ""))
        brain.approve(p.id)
        val gyms = brain.pages.first().filter { brain.titleOf(it).equals("Gym", ignoreCase = true) }
        assertEquals(1, gyms.size)
        assertEquals(brain.idOf(first), brain.idOf(gyms.single()))
        assertTrue(gyms.single().content.contains("Mon, Wed"))
    }

    @Test fun emptyAndTooManyProposalsAreLimited() {
        fresh()
        assertNull(brain.propose("  ", "x", ""))
        assertNull(brain.propose("t", " ", ""))
        repeat(8) { brain.propose("page $it", "x", "") }
        assertEquals(5, brain.proposals.value.size)
        assertEquals("page 7", brain.proposals.value.last().title)
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

    @Test fun agentProposesAndNothingIsWrittenUntilApprove() = runBlocking<Unit> {
        fresh()
        val tools = MemoryTools.create(brain)
        val call = ToolCall("1", function = FunctionCall("memory_propose", """{"title":"Coffee","markdown":"Oat flat white","reason":"user said so"}"""))
        val replies = ArrayDeque(listOf(ChatMessage("assistant", JsonPrimitive(""), toolCalls = listOf(call)), ChatMessage("assistant", JsonPrimitive("Want me to remember that?"))))
        val agent = MasalaAgent(
            AgentModel { _, _ -> replies.removeFirst() },
            AgentToolRunner { name, args -> tools.first { it.name == name }.execute(args) },
            tools.map { it.definition },
            ToolScope.command(tools.map { ToolInfo(it.name, ToolSensitivity.READ_ONLY) }),
            { "s" },
        )
        agent.run(ChatMessage("user", JsonPrimitive("I like oat flat whites"))).first { it is AgentEvent.Done }
        assertTrue(brain.pages.first().none { brain.titleOf(it) == "Coffee" })
        assertEquals(listOf("Coffee"), brain.proposals.value.map { it.title })
    }

    @Test fun memoryReadToolReturnsEntryText() = runBlocking<Unit> {
        fresh()
        brain.save(null, "Mum", "Likes tulips.")
        val read = MemoryTools.create(brain).first { it.name == "memory_read" }
        val result = read.execute(Json.parseToJsonElement("""{"query":"mum"}""") as JsonObject)
        assertTrue(result.toString().contains("Likes tulips."))
    }

    @Test fun memoryProposeRejectsUnusableInput() = runBlocking<Unit> {
        fresh()
        val propose = MemoryTools.create(brain).first { it.name == "memory_propose" }
        val missing = propose.execute(Json.parseToJsonElement("""{"title":"","markdown":"x","reason":"y"}""") as JsonObject)
        assertTrue(missing is ToolResult.Failure)
        assertEquals(0, brain.proposals.value.size)
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
        assertTrue(text.contains("memory_propose"))
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
}
