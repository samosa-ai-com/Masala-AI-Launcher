package com.samosaai.masala.launcher

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Process
import com.samosaai.masala.MasalaApp
import com.samosaai.masala.R
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.ui.drawer.FileHit
import com.samosaai.masala.ui.drawer.SearchGroups
import com.samosaai.masala.ui.drawer.SearchSources
import com.samosaai.masala.ui.drawer.SettingsEntry
import com.samosaai.masala.ui.settings.SettingsActivity
import com.samosaai.masala.ui.skills.SkillSheetActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What drawer search looks in besides apps (plan UI refresh U3): app shortcuts, skills, settings pages
 * and, with `:pro`'s file index, `file_search` (read-only, so it runs without the confirmation sheet).
 */
class HomeSearchSources(private val context: Context, private val app: MasalaApp) : SearchSources {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)

    /** Every shortcut apps publish, read once per drawer session (only the default home app may). */
    private var allShortcuts: List<ShortcutInfo>? = null

    fun reset() {
        allShortcuts = null
    }

    override suspend fun shortcuts(query: String): List<ShortcutInfo> = withContext(Dispatchers.IO) {
        val all = allShortcuts ?: runCatching {
            launcherApps.getShortcuts(
                LauncherApps.ShortcutQuery().setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST),
                Process.myUserHandle(),
            ).orEmpty()
        }.getOrDefault(emptyList()).also { allShortcuts = it }
        val q = query.trim().lowercase()
        if (q.length < 2) return@withContext emptyList()
        all.filter { s -> listOfNotNull(s.shortLabel, s.longLabel).any { it.toString().lowercase().split(' ').any { w -> w.startsWith(q) } } }
    }

    override fun skills(query: String): List<MasalaSkill> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        return app.skills.getAllSkills()
            // A removed expert is gone from search too; a job skill has no hide switch.
            .filterNot { it.persona != null && app.personas.isHidden(it.id) }
            .filter { it.title.lowercase().split(' ').any { w -> w.startsWith(q) } }
    }

    override fun settings(query: String): List<SettingsEntry> = SearchGroups.matchSettings(query, entries)

    override suspend fun files(query: String): List<FileHit> {
        if (query.trim().length < 3) return emptyList()
        val tool = app.tools.get("file_search") ?: return emptyList()
        val result = runCatching { tool.execute(buildJsonObject { put("query", query.trim()); put("limit", 3) }) }.getOrNull()
        val text = (result as? ToolResult.Success)?.output ?: return emptyList()
        return parseFileHits(text)
    }

    override fun openShortcut(shortcut: ShortcutInfo) {
        app.actions.startShortcut(shortcut)
    }

    override fun openSkill(skill: MasalaSkill) {
        if (skill.persona != null) {
            // Experts are consulted inside the chat: there is no screen of their own to open.
            if (!app.commands.openInput(context.getString(R.string.search_ask_expert, skill.title.ifBlank { skill.id }))) {
                busyToast()
            }
            return
        }
        context.startActivity(SkillSheetActivity.intent(context, skill.id, "").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun openSetting(entry: SettingsEntry) {
        context.startActivity(Intent(context, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, entry.page))
    }

    override fun openFile(hit: FileHit) {
        // The model opens and reads files through its tools; asking keeps one path for every file type.
        if (!app.commands.ask(context.getString(R.string.search_open_file, hit.path))) busyToast()
    }

    /** One chat at a time: the search entry points say so instead of replacing a running turn. */
    private fun busyToast() {
        android.widget.Toast.makeText(context, R.string.chat_busy, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun webSearch(query: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private val entries by lazy {
        listOf(
            SettingsEntry(context.getString(R.string.ai_models_title), listOf("model", "key", "provider", "api", "openai", "meta", "lm studio"), "AI_MODELS"),
            SettingsEntry(context.getString(R.string.voice_title), listOf("voice", "mic", "speech", "read aloud", "language"), "VOICE"),
            SettingsEntry(context.getString(R.string.settings_hidden_apps), listOf("hidden", "hide", "apps"), "HIDDEN_APPS"),
            SettingsEntry(context.getString(R.string.settings_home_layout), listOf("home", "layout", "undo", "gesture", "lock"), "LAYOUT"),
            SettingsEntry(context.getString(R.string.settings_files), listOf("files", "index", "storage"), "FILES"),
            SettingsEntry(context.getString(R.string.settings_skills), listOf("skills", "expert", "experts"), "SKILLS"),
            SettingsEntry(context.getString(R.string.brief_title), listOf("brief", "morning", "weather", "topics", "news"), "BRIEF"),
            SettingsEntry(context.getString(R.string.wallpaper_title), listOf("wallpaper", "background", "gallery", "generate"), "WALLPAPERS"),
        )
    }

    companion object {
        /** `file_search` answers "1. /path (modified date)\n   passage" per hit. */
        fun parseFileHits(text: String): List<FileHit> {
            val hit = Regex("""^\d+\.\s+(.+?) \(modified [^)]*\)$""")
            val out = mutableListOf<FileHit>()
            text.lines().forEach { line ->
                val m = hit.find(line.trim())
                if (m != null) {
                    val path = m.groupValues[1]
                    out += FileHit(path, path.substringBeforeLast('/').removePrefix("/storage/emulated/0/").removePrefix("/sdcard/"), "")
                } else if (line.startsWith("   ") && out.isNotEmpty()) {
                    out[out.lastIndex] = out.last().copy(passage = line.trim())
                }
            }
            return out
        }
    }
}
