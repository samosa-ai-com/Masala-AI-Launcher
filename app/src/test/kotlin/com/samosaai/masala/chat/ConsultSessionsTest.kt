package com.samosaai.masala.chat

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsultSessionsTest {
    private val root: File = createTempDirectory("consults").toFile()
    private val sessions = ConsultSessions(root)

    private fun question(text: String) = ConsultTurn(TurnRole.USER, text)
    private fun answer(text: String) = ConsultTurn(TurnRole.MASALA, text)

    @Test
    fun idsCountUpFromOne() = runBlocking {
        val first = sessions.create("chat-1", "doctor", "Doctor")
        val second = sessions.create("chat-1", "chef", "Chef")
        assertEquals("chat-1-c1", first?.id)
        assertEquals("chat-1-c2", second?.id)
    }

    @Test
    fun aChatGetsTenConsultsAndNoMore() = runBlocking {
        repeat(10) { assertNotNull(sessions.create("chat-1", "skill-$it", "Skill $it")) }
        assertNull(sessions.create("chat-1", "one-too-many", "Nope"))
        assertEquals(10, sessions.list("chat-1").size)
    }

    @Test
    fun aConsultHoldsTenExchanges() = runBlocking {
        val consult = assertNotNull(sessions.create("chat-1", "doctor", "Doctor"))
        repeat(10) { n ->
            assertNotNull(sessions.append("chat-1", consult.id, question("q$n")))
            assertNotNull(sessions.append("chat-1", consult.id, answer("a$n")))
        }
        // The eleventh question is refused and nothing is written.
        assertNull(sessions.append("chat-1", consult.id, question("q10")))
        assertEquals(10, sessions.get("chat-1", consult.id)?.exchanges)
        assertEquals("a9", sessions.get("chat-1", consult.id)?.turns?.last()?.text)
    }

    @Test
    fun deletingAChatRemovesOnlyItsConsults() = runBlocking {
        val kept = assertNotNull(sessions.create("chat-2", "doctor", "Doctor"))
        sessions.create("chat-1", "doctor", "Doctor")

        sessions.deleteChat("chat-1")

        assertEquals(emptyList(), sessions.list("chat-1"))
        assertTrue(root.listFiles().orEmpty().none { it.name == "chat-1" })
        assertEquals(kept.id, sessions.get("chat-2", kept.id)?.id)
    }

    @Test
    fun aConsultOfAnotherChatIsNotReadable() = runBlocking {
        val consult = assertNotNull(sessions.create("chat-1", "doctor", "Doctor"))
        // The id carries its chat; asking for it under another one finds nothing.
        assertNull(sessions.get("chat-2", consult.id))
        assertEquals(emptyList(), sessions.list("chat-2"))
    }

    @Test
    fun aPathLikeIdIsRefused() = runBlocking {
        assertNull(sessions.get("chat-1", "../chat-1-c1"))
        assertNull(sessions.get("..", "chat-1-c1"))
    }

    @Test
    fun aCorruptFileIsSkipped() = runBlocking {
        val consult = assertNotNull(sessions.create("chat-1", "doctor", "Doctor"))
        File(root, "chat-1/${consult.id}.json").writeText("{ not json")
        assertEquals(emptyList(), sessions.list("chat-1"))
    }

    @Test
    fun turnsAndPairsRoundTrip() = runBlocking {
        val consult = assertNotNull(sessions.create("chat-1", "doctor", "Doctor"))
        sessions.append("chat-1", consult.id, question("headache for 3 days"))
        sessions.append("chat-1", consult.id, answer("ask about the pain"))
        sessions.append("chat-1", consult.id, question("behind one eye"))
        // No answer yet: the open question is not a pair.
        val reopened = assertNotNull(sessions.get("chat-1", consult.id))
        assertEquals(2, reopened.exchanges)
        assertEquals(listOf("headache for 3 days" to "ask about the pain"), reopened.pairs())
    }

    @Test
    fun newestPicksTheLastConsultWithThatSkill() = runBlocking {
        assertNotNull(sessions.create("chat-1", "doctor", "Doctor"))
        assertNotNull(sessions.create("chat-1", "chef", "Chef"))
        Thread.sleep(2)
        val second = assertNotNull(sessions.create("chat-1", "doctor", "Doctor"))
        assertEquals(3, sessions.list("chat-1").size)
        assertEquals(second.id, sessions.list("chat-1").last().id)
        assertEquals(second.id, sessions.newest("chat-1", "doctor")?.id)
        assertNull(sessions.newest("chat-1", "driver"))
    }
}
