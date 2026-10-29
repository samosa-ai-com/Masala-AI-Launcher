package com.samosaai.masala.wiki

import com.samosaai.masala.agent.AgentModel
import com.samosaai.masala.agent.AgentToolRunner
import com.samosaai.masala.agent.MasalaAgent
import com.samosaai.masala.agent.WikiTools
import com.samosaai.masala.core.agent.ToolInfo
import com.samosaai.masala.core.agent.ToolScope
import com.samosaai.masala.core.tool.name
import com.samosaai.masala.core.tool.ToolSensitivity
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WikiTest {
    private class FakeDao : WikiDao {
        val rows = MutableStateFlow<List<WikiPageEntity>>(emptyList())
        override fun all() = rows.map { r -> r.sortedBy { it.title.lowercase() } }
        override suspend fun get(id: String) = rows.value.firstOrNull { it.id == id }
        override suspend fun upsert(page: WikiPageEntity) { rows.value = rows.value.filterNot { it.id == page.id } + page }
        override suspend fun delete(id: String) { rows.value = rows.value.filterNot { it.id == id } }
    }

    private val dao = FakeDao()
    private val audited = mutableListOf<Pair<String, String>>()
    private var ids = 0
    private val wiki = WikiRepository(
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), dao,
        audit = { a, d -> audited += a to d },
        clock = { 1L },
        newId = { "id${++ids}" },
    )

    @Test fun proposalWritesNothingUntilApproved() = runBlocking<Unit> {
        val proposal = assertNotNull(wiki.propose("Morning routine", "Coffee, then run.", "user said so"))
        assertTrue(dao.rows.value.isEmpty())
        assertEquals(listOf(proposal), wiki.proposals.value)
        val page = assertNotNull(wiki.approve(proposal.id))
        assertEquals("agent", page.source)
        assertEquals(listOf("Morning routine"), dao.rows.value.map { it.title })
        assertTrue(wiki.proposals.value.isEmpty())
        assertEquals(listOf("wiki_approve" to "Morning routine"), audited)
    }

    @Test fun dismissedProposalIsNeverWritten() = runBlocking<Unit> {
        val p = assertNotNull(wiki.propose("Gym", "Mon, Wed", ""))
        wiki.dismiss(p.id)
        assertNull(wiki.approve(p.id))
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test fun approvingATitleThatExistsUpdatesThePage() = runBlocking<Unit> {
        val mine = wiki.save(null, "Gym", "Mon")
        val p = assertNotNull(wiki.propose("gym", "Mon, Wed", ""))
        wiki.approve(p.id)
        assertEquals(1, dao.rows.value.size)
        assertEquals(mine.id, dao.rows.value.single().id)
        assertEquals("Mon, Wed", dao.rows.value.single().markdown)
    }

    @Test fun emptyAndTooManyProposalsAreLimited() {
        assertNull(wiki.propose("  ", "x", ""))
        assertNull(wiki.propose("t", " ", ""))
        repeat(8) { wiki.propose("page $it", "x", "") }
        assertEquals(5, wiki.proposals.value.size)
        assertEquals("page 7", wiki.proposals.value.last().title)
    }

    @Test fun titlesFollowThePages() = runBlocking<Unit> {
        wiki.save(null, "Zebra", "z")
        wiki.save(null, "apple", "a")
        assertEquals(listOf("apple", "Zebra"), wiki.titles.value)
        wiki.delete(dao.rows.value.first { it.title == "Zebra" }.id)
        assertEquals(listOf("apple"), wiki.titles.value)
    }

    @Test fun readRanksTitleMatchesFirst() = runBlocking<Unit> {
        wiki.save(null, "Mum", "Birthday 3 May. Likes tulips.")
        wiki.save(null, "Garden", "Plant tulips in autumn.")
        wiki.save(null, "Work", "Standup at 9.")
        assertEquals(listOf("Mum", "Garden"), wiki.read("mum tulips").map { it.title })
        assertEquals(listOf("Garden"), wiki.read("garden").map { it.title })
        assertTrue(wiki.read("nothing relevant xyz").isEmpty())
        assertTrue(wiki.read("   ").isEmpty())
    }

    @Test fun exportHasEveryPage() = runBlocking<Unit> {
        wiki.save(null, "A", "one")
        wiki.save(null, "B", "two")
        assertEquals("# A\n\none\n\n# B\n\ntwo", wiki.exportMarkdown())
    }

    @Test fun agentProposesAndNothingIsWrittenUntilApprove() = runBlocking<Unit> {
        val tools = WikiTools.create(wiki)
        val call = ToolCall("1", function = FunctionCall("wiki_propose", """{"title":"Coffee","markdown":"Oat flat white","reason":"user said so"}"""))
        val replies = ArrayDeque(listOf(ChatMessage("assistant", JsonPrimitive(""), toolCalls = listOf(call)), ChatMessage("assistant", JsonPrimitive("Want me to remember that?"))))
        val agent = MasalaAgent(
            AgentModel { _, _ -> replies.removeFirst() },
            AgentToolRunner { name, args -> tools.first { it.name == name }.execute(args) },
            tools.map { it.definition },
            ToolScope.command(tools.map { ToolInfo(it.name, ToolSensitivity.READ_ONLY) }),
            { "s" },
        )
        agent.run(ChatMessage("user", JsonPrimitive("I like oat flat whites"))).first { it is com.samosaai.masala.agent.AgentEvent.Done }
        assertTrue(dao.rows.value.isEmpty())
        assertEquals(listOf("Coffee"), wiki.proposals.value.map { it.title })
    }

    @Test fun wikiReadToolReturnsPageText() = runBlocking<Unit> {
        wiki.save(null, "Mum", "Likes tulips.")
        val read = WikiTools.create(wiki).first { it.name == "wiki_read" }
        val result = read.execute(Json.parseToJsonElement("""{"query":"mum"}""") as JsonObject)
        assertTrue(result.toString().contains("Likes tulips."))
    }
}
