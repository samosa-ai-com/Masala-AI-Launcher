package com.samosaai.masala.skills

/**
 * What a skill run shows, in order: the contract between [SkillRunner] and the skill sheet. A run's
 * results from other lanes (mini apps, recordings) arrive as [Result] with kind `mini_app` or `audio`.
 */
sealed interface SkillRunEvent {
    data class Message(val role: Role, val text: String) : SkillRunEvent {
        enum class Role { USER, ASSISTANT }
    }

    /** One tool call: [done] is null while it runs, then true or false for ok or failed. */
    data class ToolStep(val name: String, val done: Boolean?) : SkillRunEvent

    data class Progress(val label: String, val fraction: Float? = null) : SkillRunEvent

    /** A result the user can open: a file, audio, video, a mini app or an installed app. */
    data class Result(val kind: String, val title: String, val ref: String) : SkillRunEvent

    /** The tool [capability] must be granted before the run can go on. */
    data class NeedsCapability(val capability: String, val reason: String) : SkillRunEvent

    data object Done : SkillRunEvent
    data class Failed(val message: String) : SkillRunEvent
}

/** Everything the sheet draws for one run. */
data class SkillRunState(
    val runId: Long,
    val skillId: String,
    val events: List<SkillRunEvent> = emptyList(),
    val running: Boolean = false,
)
