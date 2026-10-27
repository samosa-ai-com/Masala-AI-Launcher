package com.samosaai.masala.core.tool

import com.samosaai.masala.core.contract.MasalaSkill

/**
 * From the private prototype's `SkillImportValidator`, on [MasalaSkill], plus checks for Masala's fields: the persona
 * prompt and starters, the widget and the schedule. the private prototype's rules are kept as they are: reject
 * anything that could escape the skills directory, inflate the prompt, or inject control characters
 * into the system prompt. The persona prompt goes into the system prompt too, so it gets the same
 * treatment as the instructions.
 */
@Suppress("ThrowsCount")
object SkillImportValidator {

    private val ID_REGEX = Regex("^[a-z][a-z0-9_]{2,63}$")
    private val PACKAGE_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
    private val TOOL_NAME_REGEX = Regex("^[a-z][a-z0-9_]{1,63}$")
    private val PLATFORM_REGEX = Regex("^[a-z][a-z0-9_]{1,15}$")

    /** An icon or tint name the widget renderer looks up, never a path or a URL. */
    private val NAME_REGEX = Regex("^[a-z][a-z0-9_]{0,31}$")

    /**
     * `daily 07:30`, `weekly mon 07:30` or `monthly 15 07:30` (arch §3.9). Phase 4's runner turns it
     * into a scheduled task; the validator only makes sure it will parse.
     */
    private val SCHEDULE_REGEX = Regex(
        "^(daily|weekly (mon|tue|wed|thu|fri|sat|sun)|monthly ([1-9]|[12][0-9]|3[01])) ([01][0-9]|2[0-3]):[0-5][0-9]$"
    )

    const val MAX_ID_LENGTH = 64
    const val MAX_TITLE_LENGTH = 120
    const val MAX_DESCRIPTION_LENGTH = 1024
    const val MAX_INSTRUCTIONS_LENGTH = 32 * 1024
    const val MAX_TARGET_PACKAGES = 32
    const val MAX_REQUIRED_TOOLS = 32
    const val MAX_TOTAL_INSTRUCTIONS = 64 * 1024

    // Masala's fields
    const val MAX_PLATFORMS = 8
    const val MAX_PERSONA_PROMPT_LENGTH = 8 * 1024
    const val MAX_STARTERS = 12
    const val MAX_STARTER_LENGTH = 200
    const val MAX_DISCLAIMER_LENGTH = 512
    const val MAX_WIDGET_INPUTS = 8

    fun validate(skill: MasalaSkill) {
        if (skill.id.isBlank()) throw SkillValidationException("id is empty")
        if (skill.id.length > MAX_ID_LENGTH) {
            throw SkillValidationException("id exceeds $MAX_ID_LENGTH characters")
        }
        if (!ID_REGEX.matches(skill.id)) {
            throw SkillValidationException(
                "id must match $ID_REGEX (lowercase, starts with a letter, 3-64 chars)"
            )
        }
        if (skill.instructions.isBlank()) {
            throw SkillValidationException("instructions is empty")
        }
        if (skill.instructions.length > MAX_INSTRUCTIONS_LENGTH) {
            throw SkillValidationException(
                "instructions exceed $MAX_INSTRUCTIONS_LENGTH characters"
            )
        }
        if (skill.description.length > MAX_DESCRIPTION_LENGTH) {
            throw SkillValidationException("description exceeds $MAX_DESCRIPTION_LENGTH characters")
        }
        if (skill.title.length > MAX_TITLE_LENGTH) {
            throw SkillValidationException("title exceeds $MAX_TITLE_LENGTH characters")
        }
        if (skill.targetPackageNames.size > MAX_TARGET_PACKAGES) {
            throw SkillValidationException("targetPackageNames exceeds $MAX_TARGET_PACKAGES entries")
        }
        skill.targetPackageNames.forEach { pkg ->
            if (pkg != "*" && !PACKAGE_REGEX.matches(pkg)) {
                throw SkillValidationException("invalid target package name: $pkg")
            }
        }
        if (skill.requiresTools.size > MAX_REQUIRED_TOOLS) {
            throw SkillValidationException("requiresTools exceeds $MAX_REQUIRED_TOOLS entries")
        }
        // Names only — an unknown name simply never matches, which fails closed
        // (the skill stays hidden) rather than exposing anything.
        skill.requiresTools.forEach { tool ->
            if (!TOOL_NAME_REGEX.matches(tool)) {
                throw SkillValidationException("invalid tool name in requiresTools: $tool")
            }
        }
        if (containsControlChars(skill.instructions)) {
            throw SkillValidationException("instructions contains control characters")
        }
        if (containsControlChars(skill.description)) {
            throw SkillValidationException("description contains control characters")
        }
        validateMasalaFields(skill)
    }

    private fun validateMasalaFields(skill: MasalaSkill) {
        if (skill.platforms.size > MAX_PLATFORMS) {
            throw SkillValidationException("platforms exceeds $MAX_PLATFORMS entries")
        }
        skill.platforms.forEach { platform ->
            if (!PLATFORM_REGEX.matches(platform)) {
                throw SkillValidationException("invalid platform: $platform")
            }
        }
        skill.persona?.let { persona ->
            if (persona.systemPrompt.isBlank()) throw SkillValidationException("persona.systemPrompt is empty")
            if (persona.systemPrompt.length > MAX_PERSONA_PROMPT_LENGTH) {
                throw SkillValidationException("persona.systemPrompt exceeds $MAX_PERSONA_PROMPT_LENGTH characters")
            }
            if (containsControlChars(persona.systemPrompt)) {
                throw SkillValidationException("persona.systemPrompt contains control characters")
            }
            if (persona.starters.size > MAX_STARTERS) {
                throw SkillValidationException("persona.starters exceeds $MAX_STARTERS entries")
            }
            persona.starters.forEach { starter ->
                if (starter.isBlank()) throw SkillValidationException("persona.starters has an empty entry")
                if (starter.length > MAX_STARTER_LENGTH) {
                    throw SkillValidationException("a persona starter exceeds $MAX_STARTER_LENGTH characters")
                }
                if (containsControlChars(starter)) {
                    throw SkillValidationException("persona.starters contains control characters")
                }
            }
            persona.disclaimer?.let { disclaimer ->
                if (disclaimer.length > MAX_DISCLAIMER_LENGTH) {
                    throw SkillValidationException("persona.disclaimer exceeds $MAX_DISCLAIMER_LENGTH characters")
                }
                if (containsControlChars(disclaimer)) {
                    throw SkillValidationException("persona.disclaimer contains control characters")
                }
            }
        }
        skill.widget?.let { widget ->
            if (!NAME_REGEX.matches(widget.icon)) throw SkillValidationException("invalid widget.icon: ${widget.icon}")
            widget.tint?.let { tint ->
                if (!NAME_REGEX.matches(tint)) throw SkillValidationException("invalid widget.tint: $tint")
            }
            if (widget.inputs.size > MAX_WIDGET_INPUTS) {
                throw SkillValidationException("widget.inputs exceeds $MAX_WIDGET_INPUTS entries")
            }
        }
        skill.schedule?.let { schedule ->
            if (!SCHEDULE_REGEX.matches(schedule.default)) {
                throw SkillValidationException(
                    "schedule.default must look like 'daily 07:30', 'weekly mon 07:30' or 'monthly 15 07:30'"
                )
            }
        }
    }

    /** Strip control characters and zero-width characters from text fields. */
    fun sanitize(text: String): String =
        text.filter { c -> !isControlChar(c) }

    private fun containsControlChars(text: String): Boolean =
        text.any { c -> isControlChar(c) }

    /**
     * Returns true for ASCII control characters (incl. CR/LF/TAB), other Cc/Cf
     * code points, and zero-width / BOM characters. Skips plain whitespace.
     */
    private fun isControlChar(c: Char): Boolean {
        val cp = c.code
        if (cp == 0x09 || cp == 0x0A || cp == 0x0D) return false
        if (cp < 0x20) return true
        if (cp in 0x7F..0x9F) return true
        if (cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0xFEFF) return true
        return false
    }
}

class SkillValidationException(message: String) : IllegalArgumentException(message)
