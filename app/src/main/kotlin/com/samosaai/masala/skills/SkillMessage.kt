package com.samosaai.masala.skills

import com.samosaai.masala.core.contract.SkillInput
import com.samosaai.masala.llm.ChatMessage
import com.samosaai.masala.llm.attachmentsUserMessage

/** A picked file or folder, as a path tools can reach (a copy in app storage, or the real path in shared storage). */
data class Attachment(val kind: SkillInput, val path: String, val name: String)

/** Builds the user message for a skill run from the typed text and what was attached. */
object SkillMessage {
    /** [photoBase64] returns a downscaled JPEG as base64, or null if the file can't be read. */
    fun build(text: String, attachments: List<Attachment>, photoBase64: (String) -> String?): ChatMessage {
        val photos = attachments.filter { it.kind == SkillInput.PHOTO }.mapNotNull { photoBase64(it.path) }
        val files = attachments.filter { it.kind != SkillInput.PHOTO }
        val fileNote = files.joinToString("\n") {
            if (it.kind == SkillInput.FOLDER) "[Attached folder: ${it.path}]" else "[Attached file: ${it.path}]"
        }
        val prompt = listOf(text.trim(), fileNote).filter { it.isNotBlank() }.joinToString("\n\n")
        return attachmentsUserMessage(prompt, emptyList(), photos)
    }
}
