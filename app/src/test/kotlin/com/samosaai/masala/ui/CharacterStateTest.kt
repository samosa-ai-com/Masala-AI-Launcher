package com.samosaai.masala.ui

import androidx.compose.ui.graphics.vector.PathParser
import com.samosaai.masala.agent.CommandState
import com.samosaai.masala.ui.character.CharacterState
import com.samosaai.masala.ui.character.Expression
import com.samosaai.masala.ui.character.SamArt
import com.samosaai.masala.ui.character.SamFaces
import com.samosaai.masala.ui.character.characterStateFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CharacterStateTest {
    @Test
    fun `each command state has its Sam`() {
        assertEquals(CharacterState.HELLO, characterStateFor(CommandState.Idle, speaking = false, night = false))
        assertEquals(CharacterState.SLEEPING, characterStateFor(CommandState.Idle, speaking = false, night = true))
        assertEquals(CharacterState.LISTENING, characterStateFor(CommandState.Listening, speaking = false, night = true))
        assertEquals(CharacterState.THINKING, characterStateFor(CommandState.Thinking("hi"), speaking = false, night = false))
        assertEquals(CharacterState.THINKING, characterStateFor(CommandState.Thinking("x", listOf("set_timer")), speaking = false, night = false))
        assertEquals(CharacterState.SEARCHING, characterStateFor(CommandState.Thinking("x", listOf("set_timer", "file_search")), speaking = false, night = false))
        assertEquals(CharacterState.SEARCHING, characterStateFor(CommandState.Thinking("x", listOf("web_fetch")), speaking = false, night = false))
        assertEquals(CharacterState.DONE, characterStateFor(CommandState.Reply("p", "ok"), speaking = false, night = false))
        assertEquals(CharacterState.SPEAKING, characterStateFor(CommandState.Reply("p", "ok"), speaking = true, night = false))
        assertEquals(CharacterState.SORRY, characterStateFor(CommandState.Error("p", "no network"), speaking = false, night = false))
    }

    @Test
    fun `every face and body path parses`() {
        val paths = Expression.entries.map(SamFaces::of).flatMap { f -> listOf(f.leaf, f.whites, f.pupils, f.shine, f.lids, f.brows, f.mouth, f.extra) } +
            listOf(SamArt.BODY, SamArt.FACET, SamArt.SHINE, SamArt.LEAF, SamFaces.CHEEKS, SamFaces.DROOP)
        paths.filter { it.isNotBlank() }.forEach { d -> assertTrue(PathParser().parsePathString(d).toNodes().isNotEmpty(), d) }
    }

    @Test
    fun `the ellipse helper matches the design's`() {
        assertEquals("M67 104a13 16 0 1 0 26 0a13 16 0 1 0 -26 0", SamFaces.ell(80f, 104f, 13f, 16f))
        assertEquals(12, Expression.entries.size)
        assertEquals(8, CharacterState.entries.size)
    }
}
