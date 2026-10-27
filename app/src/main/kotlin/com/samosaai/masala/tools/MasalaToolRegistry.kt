package com.samosaai.masala.tools

import com.samosaai.masala.core.tool.FileAccess
import com.samosaai.masala.core.tool.Tool
import com.samosaai.masala.core.tool.ToolRegistry
import com.samosaai.masala.core.tool.name

/**
 * Every tool the agent can call: `:app`'s, then whatever `ProTools.register` adds, plus the private prototype's
 * dynamic-tool slot for tools that come and go at run time (MCP servers, Phase 4), grouped by owner.
 * Sensitivity lives on each [Tool], replacing the private prototype's sensitive and destructive name sets.
 */
class MasalaToolRegistry(override var fileAccess: FileAccess) : ToolRegistry {
    private val fixed = LinkedHashMap<String, Tool>()

    @Volatile private var dynamic: Map<String, List<Tool>> = emptyMap()

    override fun register(tool: Tool) {
        require(tool.name !in fixed) { "Tool ${tool.name} is already registered" }
        fixed[tool.name] = tool
    }

    /** Replaces [owner]'s dynamic tools. A dynamic tool can't take a fixed tool's name. */
    @Synchronized
    fun setDynamic(owner: String, tools: List<Tool>) {
        tools.firstOrNull { it.name in fixed }?.let { error("Dynamic tool ${it.name} clashes with a fixed tool") }
        dynamic = if (tools.isEmpty()) dynamic - owner else dynamic + (owner to tools)
    }

    override fun get(name: String): Tool? =
        fixed[name] ?: dynamic.values.firstNotNullOfOrNull { tools -> tools.firstOrNull { it.name == name } }

    override val tools: List<Tool> get() = fixed.values + dynamic.values.flatten()
}
