package com.samosaai.masala

import com.samosaai.masala.actions.QuestionGate
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QuestionGateTest {
    @Test fun `an option tap answers with its text`() { runBlocking {
        val gate = QuestionGate()
        val asked = async { gate.ask("Tea or coffee?", listOf("Tea", "Coffee")) }
        delay(20)
        gate.answer(gate.pending.value!!, 1)
        assertEquals(QuestionGate.Answer.Option(1, "Coffee"), asked.await())
        assertEquals(null, gate.pending.value)
    }
    }

    @Test fun `free text answers in the user's own words`() { runBlocking {
        val gate = QuestionGate()
        val asked = async { gate.ask("Which?", listOf("A")) }
        delay(20)
        gate.answer(gate.pending.value!!, "Something else entirely")
        assertEquals(QuestionGate.Answer.Custom("Something else entirely"), asked.await())
    }
    }

    @Test fun `a dismissal carries on without an answer`() { runBlocking {
        val gate = QuestionGate()
        val asked = async { gate.ask("Which?", listOf("A")) }
        delay(20)
        gate.dismiss(gate.pending.value!!)
        assertIs<QuestionGate.Answer.Dismissed>(asked.await())
    }
    }

    @Test fun `a timeout counts as unanswered`() { runBlocking {
        val asked = async { QuestionGate(timeoutMs = 40).ask("Which?", listOf("A")) }
        assertIs<QuestionGate.Answer.Dismissed>(asked.await())
    }
    }

    @Test fun `out-of-range taps and blank text are ignored`() { runBlocking {
        val gate = QuestionGate(timeoutMs = 200)
        val asked = async { gate.ask("Which?", listOf("A")) }
        delay(20)
        gate.answer(gate.pending.value!!, 7)
        gate.answer(gate.pending.value!!, "   ")
        delay(20)
        // Still pending: neither stray input resolved it.
        assertTrue(gate.pending.value != null)
        gate.answer(gate.pending.value!!, 0)
        assertEquals(QuestionGate.Answer.Option(0, "A"), asked.await())
    }
    }

    @Test fun `a stale sheet cannot answer the next question`() = runBlocking {
        val gate = QuestionGate(timeoutMs = 300)
        val asked = async { gate.ask("Which?", listOf("A")) }
        delay(20)
        val stale = QuestionGate.Request("old", listOf("Z"))
        gate.answer(stale, 0)
        gate.answer(stale, "stale text")
        gate.dismiss(stale)
        delay(20)
        // Still pending: none of the stale calls resolved it.
        assertTrue(gate.pending.value != null)
        gate.answer(gate.pending.value!!, 0)
        assertEquals(QuestionGate.Answer.Option(0, "A"), asked.await())
    }

    @Test fun `results tell the model what was picked`() {
        val picked = QuestionGate.actionResultOf(QuestionGate.Answer.Option(0, "Tea"))
        assertTrue(picked.success && "Tea" in picked.message)
        val custom = QuestionGate.actionResultOf(QuestionGate.Answer.Custom("decaf"))
        assertTrue(custom.success && "decaf" in custom.message)
        val missed = QuestionGate.actionResultOf(QuestionGate.Answer.Dismissed)
        assertTrue(!missed.success)
    }
}