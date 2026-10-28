package com.samosaai.masala.core.agent

import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.tool.matchesIntent

/**
 * Picks the persona a typed or spoken command belongs to, so Masala can offer "Continue in Chef"
 * instead of answering in general. Only skills with a persona and a keyword list take part: a skill
 * without keywords would match everything.
 */
object SkillRouter {
    fun route(text: String, skills: List<MasalaSkill>): MasalaSkill? =
        skills.firstOrNull { it.persona != null && it.keywords.isNotEmpty() && it.matchesIntent(text) }
}
