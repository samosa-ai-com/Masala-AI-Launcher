package com.samosaai.masala.core

import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.tool.isAvailable
import com.samosaai.masala.core.tool.matchesIntent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** From the private prototype's `SkillGatingTest` and the keyword tests, on [MasalaSkill]. */
class SkillMatchingTest {
    private val skill = MasalaSkill(id = "x", instructions = "do a thing")

    @Test
    fun `a skill with no requiresTools is always eligible`() {
        assertTrue(skill.isAvailable(emptySet()))
        assertTrue(skill.isAvailable(setOf("anything")))
    }

    @Test
    fun `a skill is hidden only when every tool it teaches is hidden`() {
        val s = skill.copy(requiresTools = listOf("web_search", "web_fetch"))
        assertTrue(s.isAvailable(setOf("web_fetch")), "one tool left is enough")
        assertFalse(s.isAvailable(setOf("web_search", "web_fetch")))
    }

    @Test
    fun `keywords match whole words only`() {
        val s = skill.copy(keywords = listOf("call"))
        assertTrue(s.matchesIntent("please call mum"))
        assertFalse(s.matchesIntent("do you recall"))
    }

    @Test
    fun `no keywords or blank text matches`() {
        assertTrue(skill.matchesIntent("anything"))
        assertTrue(skill.copy(keywords = listOf("call")).matchesIntent(""))
    }
}
