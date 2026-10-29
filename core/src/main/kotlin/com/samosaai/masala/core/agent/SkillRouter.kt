package com.samosaai.masala.core.agent

import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.contract.SkillOutput
import com.samosaai.masala.core.tool.matchesIntent

/**
 * Picks the persona a typed or spoken command belongs to, so Masala can offer "Continue in Chef"
 * instead of answering in general. Only skills with a persona and a keyword list take part: a skill
 * without keywords would match everything.
 */
object SkillRouter {
    /** Words that ask the phone to do something, so the command runs even when a persona keyword is in it too. */
    private val ACTION_WORDS = setOf(
        "add", "remind", "reminder", "set", "schedule", "book", "create", "make", "save", "delete", "remove",
        "cancel", "call", "dial", "text", "message", "send", "email", "open", "launch", "start", "stop", "run",
        "play", "pause", "turn", "switch", "enable", "disable", "alarm", "timer", "calendar", "navigate",
    )

    fun route(text: String, skills: List<MasalaSkill>): MasalaSkill? =
        skills.firstOrNull { it.persona != null && it.keywords.isNotEmpty() && it.matchesIntent(text) }

    /**
     * A skill that makes a mini app and whose keyword is in [text] ("make me a snake game" → the game
     * maker): the request goes straight to its sheet, since building the app is that skill's whole job.
     */
    fun handoff(text: String, skills: List<MasalaSkill>): MasalaSkill? =
        skills.firstOrNull { it.widget?.output == SkillOutput.MINI_APP && it.keywords.isNotEmpty() && it.matchesIntent(text) }

    /**
     * True when [text] asks the phone to act ("add dinner with Anna to my calendar"), not only for
     * advice. Such a request goes to the agent even if a persona matches; the persona is offered beside the answer.
     */
    fun isCommand(text: String): Boolean =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).any { it in ACTION_WORDS }
}
