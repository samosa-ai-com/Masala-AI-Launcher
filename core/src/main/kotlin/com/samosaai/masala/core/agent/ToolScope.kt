package com.samosaai.masala.core.agent

import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.tool.ToolSensitivity

/** What [ToolScope] needs to know about a tool, so it stays free of Android and the registry. */
data class ToolInfo(
    val name: String,
    val sensitivity: ToolSensitivity,
    val readsPrivateData: Boolean = false,
)

/**
 * Which tools one agent run may see and call (plan §6). The run is shown only these definitions,
 * and a call to anything else is refused even if the model invents it.
 */
class ToolScope private constructor(val names: Set<String>) {
    fun allows(name: String) = name in names

    companion object {
        /** Wiki lookups are allowed in every skill run: they are how a persona knows the user. */
        const val WIKI_READ = "wiki_read"

        /** A typed or spoken command: every tool there is. */
        fun command(all: List<ToolInfo>) = ToolScope(all.mapTo(HashSet()) { it.name })

        /**
         * A skill run: the tools the skill asks for, plus read-only tools that don't touch private
         * data. A skill can't gain a changing tool by not listing it.
         */
        fun skill(skill: MasalaSkill, all: List<ToolInfo>): ToolScope {
            val basics = all.filter { it.sensitivity == ToolSensitivity.READ_ONLY && !it.readsPrivateData }.map { it.name }
            val wiki = all.filter { it.name == WIKI_READ }.map { it.name }
            val required = skill.requiresTools.filter { name -> all.any { it.name == name } }
            return ToolScope((basics + wiki + required).toSet())
        }

        /** A run nobody is watching: nothing that would need a confirmation. */
        fun unattended(scope: ToolScope, all: List<ToolInfo>): ToolScope {
            val readOnly = all.filter { it.sensitivity == ToolSensitivity.READ_ONLY }.mapTo(HashSet()) { it.name }
            return ToolScope(scope.names.filterTo(HashSet()) { it in readOnly })
        }
    }
}
