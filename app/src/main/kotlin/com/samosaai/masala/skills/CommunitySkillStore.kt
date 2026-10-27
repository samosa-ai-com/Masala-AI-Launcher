package com.samosaai.masala.skills

import android.content.Context
import android.util.Log
import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.tool.SkillImportValidator
import java.io.File

/**
 * From the private prototype's `CommunitySkillStore`, on `MasalaSkill`. Persists community-imported skill JSON files under `filesDir/skills/community/`.
 *
 * The registry reads these on every [SkillRegistry.reload] /
 * `reload()`; the Settings UI lists and removes them through the same API.
 *
 * Filenames are derived from the skill's id so the user can re-import the same id
 * later and overwrite the file. We sanitize the id so no skill can escape the
 * dedicated directory.
 */
class CommunitySkillStore(context: Context) {

    private val rootDir: File = File(context.filesDir, DIR_NAME).apply {
        if (!exists()) mkdirs()
    }

    fun listFiles(): List<File> =
        rootDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.toList().orEmpty()

    fun readAll(): List<Pair<File, MasalaSkill>> {
        val out = mutableListOf<Pair<File, MasalaSkill>>()
        val json = MasalaJson.lenient
        listFiles().forEach { file ->
            try {
                val text = file.readText(Charsets.UTF_8)
                val skill = json.decodeFromString<MasalaSkill>(text)
                out.add(file to skill)
            } catch (e: Exception) {
                Log.w(TAG, "Skipping invalid community skill file: ${file.name}", e)
            }
        }
        return out
    }

    fun save(rawJson: String): File {
        val skill = parseAndValidate(rawJson)
        val file = File(rootDir, "${safeFilename(skill.id)}.json")
        file.writeText(rawJson, Charsets.UTF_8)
        return file
    }

    fun remove(id: String): Boolean {
        val f = File(rootDir, "${safeFilename(id)}.json")
        return f.exists() && f.delete()
    }

    fun exists(id: String): Boolean = File(rootDir, "${safeFilename(id)}.json").exists()

    private fun parseAndValidate(rawJson: String): MasalaSkill {
        // Defer strict validation to SkillImportValidator; here we just parse.
        val json = MasalaJson.lenient
        val skill = json.decodeFromString<MasalaSkill>(rawJson)
        SkillImportValidator.validate(skill)
        return skill
    }

    companion object {
        private const val TAG = "CommunitySkillStore"
        private const val DIR_NAME = "skills/community"

        /** Lowercase id, only letters/digits/underscore, dropped to 64 chars. */
        fun safeFilename(id: String): String {
            val cleaned = id.lowercase()
                .filter { it.isLetterOrDigit() || it == '_' }
                .take(64)
            return if (cleaned.isEmpty()) "skill" else cleaned
        }
    }
}
