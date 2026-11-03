package com.samosaai.masala.skills

import android.content.Context
import android.util.Log
import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.tool.isAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/**
 * From the private prototype's `SkillRegistry`, on `MasalaSkill`. the private prototype's is a singleton behind `init(context)`;
 * this is an instance that `MasalaApp` owns, so tests can make their own and nothing is global.
 * Bundled skills come from `assets/skills/`, community ones from [CommunitySkillStore].
 */
class SkillRegistry(
    private val context: Context,
    private val importerFactory: (Set<String>) -> SkillImporter = { hosts -> SkillImporter(hosts) },
) {
    private var bundledSkills = emptyList<MasalaSkill>()
    @Volatile
    private var communitySkills = emptyList<MasalaSkill>()

    /** Cached union of bundled and community skills, keyed by id. */
    @Volatile
    private var merged: List<MasalaSkill> = emptyList()

    init {
        reload()
    }

    /** Re-read bundled assets and the community store, refreshing the merged view. */
    @Synchronized
    fun reload() {
        bundledSkills = try {
            loadSkillsFromAssets("skills")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bundled skills", e)
            emptyList()
        }
        communitySkills = try {
            CommunitySkillStore(context).readAll().map { (_, skill) -> skill }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load community skills", e)
            emptyList()
        }
        // Bundled wins on id collision (security: never let a community skill override a built-in one).
        val byId = linkedMapOf<String, MasalaSkill>()
        bundledSkills.forEach { byId[it.id] = it }
        communitySkills.forEach { skill ->
            if (!byId.containsKey(skill.id)) {
                byId[skill.id] = skill
            } else {
                Log.w(TAG, "Community skill id '${skill.id}' is shadowed by a bundled skill")
            }
        }
        merged = byId.values.toList()
    }

    private fun loadSkillsFromAssets(path: String): List<MasalaSkill> {
        val assets = context.assets
        val out = mutableListOf<MasalaSkill>()
        for (item in assets.list(path) ?: return out) {
            val fullPath = "$path/$item"
            if (item.endsWith(".json")) {
                try {
                    val text = assets.open(fullPath).bufferedReader().use { it.readText() }
                    out += MasalaJson.lenient.decodeFromString<MasalaSkill>(text)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing $fullPath", e)
                }
            } else if (!item.contains(".")) {
                // Heuristic for directories: no extension.
                out += loadSkillsFromAssets(fullPath)
            }
        }
        return out
    }

    fun getAllSkills(): List<MasalaSkill> = merged

    /** Returns only community-imported skills (no bundled ones). */
    fun getCommunitySkills(): List<MasalaSkill> = communitySkills

    fun getSkillById(id: String): MasalaSkill? = merged.find { it.id == id }

    /** The bundled skills that have a persona (the private prototype's `Persona.kt` list). */
    fun getPersonas(): List<MasalaSkill> = merged.filter { it.persona != null }

    /**
     * Skills targeting [packageName], minus any whose tools are all withheld this turn
     * (see `MasalaSkill.requiresTools`). [hiddenTools] defaults to empty, so callers that don't gate
     * get every matching skill.
     */
    fun getSkillsForPackage(packageName: String, hiddenTools: Set<String> = emptySet()): List<MasalaSkill> =
        merged.filter { skill ->
            (skill.targetPackageNames.contains(packageName) || skill.targetPackageNames.contains("*")) &&
                skill.isAvailable(hiddenTools)
        }

    /** Same gating as [getSkillsForPackage] — a hidden skill is unreachable by both channels. */
    fun searchSkills(query: String, hiddenTools: Set<String> = emptySet()): List<MasalaSkill> {
        val q = query.lowercase()
        return merged.filter { skill ->
            (
                skill.id.lowercase().contains(q) ||
                    skill.description.lowercase().contains(q) ||
                    skill.title.lowercase().contains(q) ||
                    skill.targetPackageNames.any { it.lowercase().contains(q) }
                ) && skill.isAvailable(hiddenTools)
        }
    }

    /**
     * Fetch a community skill from a URL, validate it, and persist it. The network fetch and the
     * on-disk write both run on [Dispatchers.IO].
     */
    suspend fun importCommunityFromUrl(url: String, allowedHosts: Set<String>): MasalaSkill =
        withContext(Dispatchers.IO) {
            val preview = importerFactory(allowedHosts).fetchPreview(url)
            CommunitySkillStore(context).save(preview.rawJson)
            reload()
            preview.skill
        }

    /** Writes an expert or skill the user made, so it lands in the merged list like an imported one. */
    suspend fun saveCommunity(skill: MasalaSkill): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching {
            CommunitySkillStore(context).save(MasalaJson.lenient.encodeToString(skill))
        }.isSuccess
        if (ok) reload()
        ok
    }

    /** Persist a community skill from a raw JSON string, validated on [Dispatchers.IO]. */
    suspend fun importCommunity(rawJson: String): MasalaSkill = withContext(Dispatchers.IO) {
        val skill = SkillImporter.parseAndValidate(rawJson)
        CommunitySkillStore(context).save(rawJson)
        reload()
        skill
    }

    /** Remove a community skill from disk and reload the registry. */
    suspend fun removeCommunity(id: String): Boolean = withContext(Dispatchers.IO) {
        val removed = CommunitySkillStore(context).remove(id)
        if (removed) reload()
        removed
    }

    private companion object {
        const val TAG = "SkillRegistry"
    }
}
