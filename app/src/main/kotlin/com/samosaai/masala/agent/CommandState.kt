package com.samosaai.masala.agent

/** What the reply card shows for a typed or spoken command. */
sealed interface CommandState {
    data object Idle : CommandState

    /** The card with just its input field (the Ask gesture, the assist gesture). */
    data object Input : CommandState
    data object Listening : CommandState
    /** A take paused by itself (5-minute cap) or by the clock: audio/transcript kept, resumable. */
    data object ListeningPaused : CommandState
    data class Thinking(val prompt: String, val steps: List<String> = emptyList()) : CommandState
    data class Reply(
        val prompt: String,
        val text: String,
        val steps: List<String> = emptyList(),
        val spoken: Boolean = false,
        /** The experts whose answers shaped this reply, for the badges on it. */
        val experts: List<com.samosaai.masala.chat.ExpertCredit> = emptyList(),
        /** Access a step needed but could not use, for the Grant affordance under the answer. */
        val capabilities: List<String> = emptyList(),
        /** What the turn made (a mini app, a file), for the card under the answer. */
        val results: List<com.samosaai.masala.chat.ChatResult> = emptyList(),
    ) : CommandState
    /** [capability] is the marker of a permission or special access that would fix it, for a Grant button. */
    data class Error(val prompt: String, val message: String, val capability: String? = null) : CommandState
}
