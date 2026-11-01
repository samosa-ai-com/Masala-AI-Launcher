package com.samosaai.masala.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The conversation store: persistence, ordering, titling and the bounds that keep it small. */
class ChatSessionsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000L
    private fun store(onDeleted: (String) -> Unit = {}) =
        ChatSessions(tmp.root, clock = { now++ }, newId = { "id-${now}" }, onDeleted = onDeleted)

    private fun user(text: String) = ChatTurn(TurnRole.USER, text)
    private fun masala(text: String) = ChatTurn(TurnRole.MASALA, text)

    @Test
    fun `a turn is written and comes back after a reload`() = runBlocking<Unit> {
        val s = store()
        val chat = s.create()
        s.append(chat.id, user("open youtube"))
        s.append(chat.id, masala("Opening YouTube"))

        val reopened = store()
        reopened.load()
        val restored = reopened.get(chat.id)
        assertNotNull(restored)
        assertEquals(2, restored!!.turns.size)
        assertEquals("Opening YouTube", restored.turns[1].text)
        assertEquals(TurnRole.MASALA, restored.turns[1].role)
    }

    @Test
    fun `a turn keeps its experts through a reload`() = runBlocking<Unit> {
        val s = store()
        val chat = s.create()
        s.append(
            chat.id,
            ChatTurn(TurnRole.MASALA, "the doctor said so", experts = listOf(ExpertCredit("doctor", "Doctor"))),
        )
        val restored = store()
        restored.load()
        assertEquals(
            listOf(ExpertCredit("doctor", "Doctor")),
            restored.get(chat.id)?.turns?.single()?.experts,
        )
    }

    @Test
    fun `the first user turn titles the session`() = runBlocking<Unit> {
        val s = store()
        val chat = s.create()
        s.append(chat.id, user("  what is the weather in Indore tomorrow  "))
        s.append(chat.id, masala("Sunny"))
        assertEquals("what is the weather in Indore tomorrow", s.summaries.value.single().title)
    }

    @Test
    fun `sessions are listed newest first`() = runBlocking<Unit> {
        val s = store()
        val first = s.create()
        s.append(first.id, user("one"))
        val second = s.create()
        s.append(second.id, user("two"))
        assertEquals(listOf(second.id, first.id), s.summaries.value.map { it.id })
    }

    @Test
    fun `an empty session stays out of the list until it has a turn`() = runBlocking<Unit> {
        val s = store()
        s.create()
        assertTrue(s.summaries.value.isEmpty())
    }

    @Test
    fun `deleting removes the session and its file`() = runBlocking<Unit> {
        val s = store()
        val chat = s.create()
        s.append(chat.id, user("hello"))
        s.delete(chat.id)
        assertTrue(s.summaries.value.isEmpty())
        assertNull(s.get(chat.id))
        assertTrue(tmp.root.listFiles().orEmpty().none { it.name == "${chat.id}.json" })
    }

    @Test
    fun `deleting a session reports it, so its consults can go`() = runBlocking<Unit> {
        val dropped = mutableListOf<String>()
        val s = store { dropped += it }
        val chat = s.create()
        s.append(chat.id, user("hello"))
        s.delete(chat.id)
        assertEquals(listOf(chat.id), dropped)
    }

    @Test
    fun `pruning reports every dropped session`() = runBlocking<Unit> {
        val dropped = mutableListOf<String>()
        val s = store { dropped += it }
        val ids = mutableListOf<String>()
        repeat(ChatSessions.MAX_SESSIONS + 3) { i ->
            val c = s.create()
            ids += c.id
            s.append(c.id, user("chat $i"))
        }
        // The three oldest are pruned, and each is reported once.
        assertEquals(ids.take(3).toSet(), dropped.toSet())
        assertEquals(3, dropped.size)
    }

    @Test
    fun `turns are capped per session`() = runBlocking<Unit> {
        val s = store()
        val chat = s.create()
        repeat(ChatSessions.MAX_TURNS + 5) { s.append(chat.id, user("line $it")) }
        assertEquals(ChatSessions.MAX_TURNS, s.get(chat.id)!!.turns.size)
        // The newest line survives the trim, the oldest does not.
        assertEquals("line ${ChatSessions.MAX_TURNS + 4}", s.get(chat.id)!!.turns.last().text)
    }

    @Test
    fun `old sessions beyond the cap are pruned`() = runBlocking<Unit> {
        val s = store()
        repeat(ChatSessions.MAX_SESSIONS + 3) { i ->
            val c = s.create()
            s.append(c.id, user("chat $i"))
        }
        assertEquals(ChatSessions.MAX_SESSIONS, s.summaries.value.size)
        // The oldest three are gone; the newest is kept.
        assertEquals("chat ${ChatSessions.MAX_SESSIONS + 2}", s.summaries.value.first().title)
    }

    @Test
    fun `renaming persists and survives a reload`() = runBlocking<Unit> {
        val s = store()
        val chat = s.create()
        s.append(chat.id, user("two plus two"))
        s.rename(chat.id, "Arithmetic")
        assertEquals("Arithmetic", s.summaries.value.single().title)
        val reopened = store()
        reopened.load()
        assertEquals("Arithmetic", reopened.summaries.value.single().title)
    }

    @Test
    fun `the summary line stays searchable`() = runBlocking<Unit> {
        val s = store()
        val chat = s.create()
        s.append(chat.id, user("build me a timer app"))
        s.append(chat.id, masala("Created the Timer mini app."))
        val summary = s.summaries.value.single()
        assertTrue(summary.search.contains("timer", ignoreCase = true))
        assertTrue(summary.search.contains("Created", ignoreCase = true))
    }

    @Test
    fun `a corrupt file is ignored rather than crashing`() = runBlocking<Unit> {
        tmp.root.mkdirs()
        java.io.File(tmp.root, "broken.json").writeText("{not json")
        val s = store()
        assertTrue(s.load().isEmpty())
    }

    @Test
    fun `deleting one turn keeps the rest in order`() = runBlocking<Unit> {
        val s = store()
        val chat = s.create()
        s.append(chat.id, user("one"))
        s.append(chat.id, masala("two"))
        s.append(chat.id, user("three"))

        val next = s.deleteTurn(chat.id, 1)
        assertNotNull(next)
        assertEquals(listOf("one", "three"), next!!.turns.map { it.text })

        val reopened = s.get(chat.id)
        assertNotNull(reopened)
        assertEquals(listOf("one", "three"), reopened!!.turns.map { it.text })
    }

    @Test
    fun `deleting an unknown turn changes nothing`() = runBlocking<Unit> {
        val s = store()
        val chat = s.create()
        s.append(chat.id, user("one"))

        assertNull(s.deleteTurn(chat.id, 5))
        assertNull(s.deleteTurn("missing", 0))
        assertEquals(1, s.get(chat.id)!!.turns.size)
    }
}
