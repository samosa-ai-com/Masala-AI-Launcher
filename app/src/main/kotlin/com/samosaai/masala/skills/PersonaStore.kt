package com.samosaai.masala.skills

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What the user changed about one expert: its own instructions, and which tools it may use.
 * Null means "as shipped", so a fresh install and a reset look exactly the same.
 */
@Serializable
data class PersonaOverride(
    val systemPrompt: String? = null,
    /** Null means every tool the consult scope allows; an empty list means none of them. */
    val allowTools: List<String>? = null,
    /** When the main agent should call this expert. Null keeps the one the skill ships. */
    val whenToUse: String? = null,
)

/**
 * Per-expert edits, one small JSON file. Applied only when an expert is consulted, which is the
 * only way a persona runs. A corrupt file reads as "nothing changed" rather than failing a chat.
 */
class PersonaStore(private val root: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file = File(root, FILE)

    @Synchronized
    fun read(): Map<String, PersonaOverride> = runCatching {
        if (!file.isFile) emptyMap() else json.decodeFromString<Map<String, PersonaOverride>>(file.readText())
    }.getOrDefault(emptyMap())

    @Synchronized
    fun override(id: String): PersonaOverride? = read()[id]

    /** The prompt to use, or null to keep the skill's own. */
    fun promptFor(id: String): String? = override(id)?.systemPrompt?.takeIf { it.isNotBlank() }

    /** When to call this expert, or null to keep the skill's own wording. */
    fun whenToUseFor(id: String): String? = override(id)?.whenToUse?.takeIf { it.isNotBlank() }

    /** Tools to leave out, empty for "all of them". */
    fun toolsFor(id: String): Set<String> = override(id)?.allowTools?.toSet().orEmpty()

    @Synchronized
    fun save(id: String, override: PersonaOverride) {
        val next = read().toMutableMap()
        val untouched = override.systemPrompt == null && override.allowTools == null && override.whenToUse == null
        if (untouched) next.remove(id) else next[id] = override
        write(next)
    }

    @Synchronized
    fun reset(id: String) {
        val next = read().toMutableMap()
        if (next.remove(id) != null) write(next)
    }

    private fun write(all: Map<String, PersonaOverride>) {
        runCatching {
            root.mkdirs()
            val temp = File(root, "$FILE.tmp")
            temp.writeText(json.encodeToString(all))
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    companion object {
        private const val FILE = "overrides.json"
    }
}
