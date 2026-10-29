package com.samosaai.masala.secondbrain

/** One file in the vault, content-capped for display. Binary files carry no content. */
data class BrainFile(
    val name: String,
    val content: String,
    val truncated: Boolean,
    val sizeBytes: Long = content.length.toLong(),
    val absolutePath: String = "",
) {
    val extension get() = name.substringAfterLast('.', "").lowercase()
    val isMarkdown: Boolean get() = extension == "md" || extension == "markdown" || extension.isEmpty()
}

/** Everything the viewer, graph and health checks read. */
data class SecondBrainSnapshot(
    val available: Boolean,
    val soul: BrainFile?,
    val memoryIndex: BrainFile?,
    val userCard: BrainFile?,
    val areas: List<BrainFile>,
    val people: List<BrainFile>,
    val notes: List<BrainFile>,
    val raw: List<BrainFile> = emptyList(),
) {
    fun allMarkdown(): List<BrainFile> = listOfNotNull(soul, memoryIndex, userCard) + areas + people + notes
    fun isEmpty(): Boolean = soul == null && memoryIndex == null && userCard == null &&
        areas.isEmpty() && people.isEmpty() && notes.isEmpty() && raw.isEmpty()
}

data class BrainNode(val id: String, val label: String, val kind: String)
data class BrainEdge(val fromId: String, val toId: String)
data class BrainGraph(val nodes: List<BrainNode>, val edges: List<BrainEdge>)

    /** A wiki-style link that points nowhere. Self-links resolve (to their own file) and never dangle. */
data class DanglingLink(val fromId: String, val target: String)

/** One detail record that fails the schema, with its reasons. */
data class RecordProblem(val fileId: String, val errors: List<String>)

/** One note whose `task_ref` names a task id no scheduled task carries. */
data class TaskNoteRef(val fileId: String, val taskId: String)

/** One scheduled task for the health check: id, display name and linked note path. */
data class TaskLink(val id: String, val name: String, val notePath: String?)

/** One note whose `task_ref` names a task that points at a different note (or nowhere). */
data class TaskMismatch(val fileId: String, val taskId: String, val linkedPath: String?)

/** One scheduled task whose linked note no longer exists in the vault. */
data class StaleTaskLink(val taskName: String, val path: String)

/** One scheduled task pointing at a real note that does not name it back in `task_ref`. */
data class MissingBacklink(val fileId: String, val taskName: String)

/** Orphan dashboard: everything that keeps the Second Brain from hanging together. */
data class BrainHealth(
    val unlinked: List<String>,
    val dangling: List<DanglingLink>,
    val invalid: List<RecordProblem>,
    val unknownTasks: List<TaskNoteRef>,
    val mismatchedTasks: List<TaskMismatch> = emptyList(),
    val staleTaskLinks: List<StaleTaskLink> = emptyList(),
    val unscanned: List<String> = emptyList(),
    val missingBacklinks: List<MissingBacklink> = emptyList(),
) {
    val isClean: Boolean
        get() = unlinked.isEmpty() && dangling.isEmpty() && invalid.isEmpty() &&
            unknownTasks.isEmpty() && mismatchedTasks.isEmpty() &&
            staleTaskLinks.isEmpty() && unscanned.isEmpty() && missingBacklinks.isEmpty()
    val issueCount: Int
        get() = unlinked.size + dangling.size + invalid.size + unknownTasks.size +
            mismatchedTasks.size + staleTaskLinks.size + unscanned.size + missingBacklinks.size
}

data class RecordValidation(val valid: Boolean, val errors: List<String>)

/** The agent prompt block: capped SOUL.md + MEMORY.md plus entry titles. Null texts mean absent. */
data class SecondBrainPrompt(
    val soul: String?,
    val memory: String?,
    val soulTruncated: Boolean,
    val memoryTruncated: Boolean,
    val titles: List<String>,
)
