package com.samosaai.masala.agent

/** What the reply card shows for a typed or spoken command. */
sealed interface CommandState {
    data object Idle : CommandState

    /** The card with just its input field (the Ask gesture, the assist gesture). */
    data object Input : CommandState
    data object Listening : CommandState
    data class Thinking(val prompt: String, val steps: List<String> = emptyList()) : CommandState
    data class Reply(
        val prompt: String,
        val text: String,
        val steps: List<String> = emptyList(),
        val spoken: Boolean = false,
        /** The experts whose answers shaped this reply, for the badges on it. */
        val experts: List<com.samosaai.masala.chat.ExpertCredit> = emptyList(),
    ) : CommandState
    /** [capability] is the marker of a permission or special access that would fix it, for a Grant button. */
    data class Error(val prompt: String, val message: String, val capability: String? = null) : CommandState
}
