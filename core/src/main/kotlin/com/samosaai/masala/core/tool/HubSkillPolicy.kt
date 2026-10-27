package com.samosaai.masala.core.tool

import com.samosaai.masala.core.contract.MasalaSkill

/**
 * Whether a skill from the Skill Hub may be installed (arch §2.4). A hub skill can't add tools, but
 * it can combine tools Masala has: one that reads private data and one that reaches the network could
 * send that data out in a URL, and reads never trigger the confirmation gate.
 */
object HubSkillPolicy {
    sealed interface Decision {
        /** The agent may install it on its own. */
        data object Install : Decision

        /** Only the user may install it, from the Skills screen. */
        data class UserOnly(val reason: String) : Decision

        data class Reject(val reason: String) : Decision
    }

    /** the private prototype's Accessibility UI tools. Masala has none of them. */
    val uiToolNames = setOf(
        "tap", "swipe", "long_press", "read_screen", "input_text", "navigate_app", "press_back",
        "press_home",
    )

    private val uiToolPattern = Regex("""\b(${uiToolNames.joinToString("|")})\b""")

    fun decide(skill: MasalaSkill, registry: ToolRegistry): Decision {
        if (skill.platforms.isNotEmpty() && "masala" !in skill.platforms) {
            return Decision.Reject("Not made for Masala (platforms: ${skill.platforms.joinToString()})")
        }
        val tools = skill.requiresTools.map { name ->
            registry[name] ?: return Decision.Reject("Needs $name, which Masala doesn't have")
        }
        tools.firstOrNull { it.origin == ToolOrigin.PRO }?.let {
            return Decision.Reject("Needs ${it.name}, which hub skills may not use")
        }
        if (skill.platforms.isEmpty()) {
            // Skills from before the platforms field: the private prototype skills that teach UI steps.
            uiToolPattern.find(skill.instructions)?.let {
                return Decision.Reject("Instructions use the UI tool ${it.value}")
            }
        }
        val reader = tools.firstOrNull { it.readsPrivateData }
        val sender = tools.firstOrNull { it.usesNetwork }
        if (reader != null && sender != null) {
            return Decision.UserOnly("Combines ${reader.name} (private data) with ${sender.name} (network)")
        }
        return Decision.Install
    }
}
