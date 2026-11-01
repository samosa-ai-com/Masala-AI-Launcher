package com.samosaai.masala.core.agent

import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.tool.ToolSensitivity

/** What [ToolScope] needs to know about a tool, so it stays free of Android and the registry. */
data class ToolInfo(
    val name: String,
    val sensitivity: ToolSensitivity,
    val readsPrivateData: Boolean = false,
    val opensScreen: Boolean = false,
)

/**
 * Which tools one agent run may see and call (plan §6). The run is shown only these definitions,
 * and a call to anything else is refused even if the model invents it.
 */
class ToolScope private constructor(val names: Set<String>) {
    fun allows(name: String) = name in names

    companion object {
        /** Memory lookups are allowed in every skill run: they are how a persona knows the user. */
        const val MEMORY_READ = "memory_read"
        const val MEMORY_SEARCH = "memory_search"
        private val MEMORY_LOOKUPS = setOf(MEMORY_READ, MEMORY_SEARCH)

        /** A typed or spoken command: every tool there is. */
        fun command(all: List<ToolInfo>) = ToolScope(all.mapTo(HashSet()) { it.name })

        /**
         * A skill run: the tools the skill asks for, plus read-only tools that don't touch private
         * data. A skill can't gain a changing tool by not listing it.
         */
        fun skill(skill: MasalaSkill, all: List<ToolInfo>): ToolScope {
            val basics = all.filter { it.sensitivity == ToolSensitivity.READ_ONLY && !it.readsPrivateData }.map { it.name }
            val memory = all.filter { it.name in MEMORY_LOOKUPS }.map { it.name }
            val required = skill.requiresTools.filter { name -> all.any { it.name == name } }
            return ToolScope((basics + memory + required).toSet())
        }

        /**
         * A consulted expert: the main agent's tools, minus the ones that open a screen (an expert
         * answering inside a chat must not take over the phone) and minus anything the user switched
         * off for it. Recursion is the caller's exclusion, since only it knows the consult tool's
         * name. Names that don't exist in [all] are ignored, so a stale override can't invent a tool.
         */
        fun consult(all: List<ToolInfo>, excluded: Set<String>): ToolScope =
            ToolScope(all.filter { !it.opensScreen }.mapTo(HashSet()) { it.name }.apply { removeAll(excluded) })

        /** A run nobody is watching: nothing that would need a confirmation or bring up a screen. */
        fun unattended(scope: ToolScope, all: List<ToolInfo>): ToolScope {
            val safe = all.filter { it.sensitivity == ToolSensitivity.READ_ONLY && !it.opensScreen }.mapTo(HashSet()) { it.name }
            return ToolScope(scope.names.filterTo(HashSet()) { it in safe })
        }
    }
}
