package com.samosaai.masala.core.tool

import com.samosaai.masala.core.contract.MasalaSkill

/**
 * From the private prototype's `Skill.isAvailable`: true when nothing this skill teaches is currently withheld from
 * the model. An empty `requiresTools` means always eligible; otherwise at least one of the tools it
 * teaches must be callable, since advice for a tool the agent can't reach costs tokens and invites a
 * doomed tool call.
 */
fun MasalaSkill.isAvailable(hiddenTools: Set<String>): Boolean =
    requiresTools.isEmpty() || requiresTools.any { it !in hiddenTools }

/**
 * From the private prototype's `Skill.matchesIntent`: true when keywords are empty, or when at least one keyword
 * matches a word boundary in [text] (case-insensitive). Word-boundary matching prevents false
 * positives like keyword "call" matching "recall" or "vocaller".
 */
fun MasalaSkill.matchesIntent(text: String): Boolean {
    if (keywords.isEmpty() || text.isBlank()) return true
    return keywords.any { kw -> Regex("""\b${Regex.escape(kw)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) }
}
