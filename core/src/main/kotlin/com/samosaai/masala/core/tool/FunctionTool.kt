package com.samosaai.masala.core.tool

import kotlinx.serialization.json.JsonObject

/**
 * A [Tool] made of a definition and a function: how the tool classes copied from the private prototype (methods
 * returning [ActionResult]) join the registry, replacing the private prototype's one big `when` in ToolExecutor.
 */
class FunctionTool(
    override val definition: ToolDefinition,
    override val sensitivity: ToolSensitivity,
    override val readsPrivateData: Boolean = false,
    override val usesNetwork: Boolean = false,
    override val origin: ToolOrigin = ToolOrigin.CORE,
    private val run: suspend (JsonObject) -> ActionResult,
) : Tool {
    override suspend fun execute(args: JsonObject): ToolResult = run(args).toToolResult()
}
