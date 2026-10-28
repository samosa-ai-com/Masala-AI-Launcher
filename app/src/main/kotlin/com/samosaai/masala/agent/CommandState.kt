package com.samosaai.masala.agent

/** The skill a command was routed to, offered as "Continue in …". */
data class SkillChip(val id: String, val title: String)

/** What the reply card shows for a typed or spoken command. */
sealed interface CommandState {
    data object Idle : CommandState

    /** The card with just its input field (the Ask gesture, the assist gesture). */
    data object Input : CommandState
    data object Listening : CommandState
    data class Thinking(val prompt: String, val steps: List<String> = emptyList()) : CommandState
    data class Reply(val prompt: String, val text: String, val steps: List<String> = emptyList(), val skill: SkillChip? = null, val spoken: Boolean = false) : CommandState
    data class Error(val prompt: String, val message: String) : CommandState
}
