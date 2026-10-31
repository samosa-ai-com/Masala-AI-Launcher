package com.samosaai.masala.ui.drawer

import android.content.pm.ShortcutInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samosaai.masala.R
import com.samosaai.masala.core.apps.AppEntry
import com.samosaai.masala.core.apps.AppSearch
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.ui.components.IconTile
import com.samosaai.masala.ui.theme.MasalaTheme

/** A settings page search can open (UI refresh U3): its title, words it answers to, and its page id. */
data class SettingsEntry(val title: String, val keywords: List<String>, val page: String)

/** A file the on-phone index found: path, a short location and the matching passage. */
data class FileHit(val path: String, val where: String, val passage: String)

/** Everything search looks in besides apps. Implemented by the home screen; empty in previews and tests. */
interface SearchSources {
    suspend fun shortcuts(query: String): List<ShortcutInfo> = emptyList()
    fun skills(query: String): List<MasalaSkill> = emptyList()
    fun settings(query: String): List<SettingsEntry> = emptyList()
    suspend fun files(query: String): List<FileHit> = emptyList()
    fun openShortcut(shortcut: ShortcutInfo) {}
    fun openSkill(skill: MasalaSkill) {}
    fun openSetting(entry: SettingsEntry) {}
    fun openFile(hit: FileHit) {}
    fun webSearch(query: String) {}

    object None : SearchSources
}

/** What search shows, grouped; built by [SearchGroups.build] so the order rules are testable. */
data class SearchGroups(
    val askFirst: Boolean,
    val apps: List<AppEntry>,
    val shortcuts: List<ShortcutInfo>,
    val skills: List<MasalaSkill>,
    val settings: List<SettingsEntry>,
    val files: List<FileHit>,
    val suggestions: List<String>,
) {
    companion object {
        /**
         * "Ask Masala" goes first when nothing matches well: no app whose name starts with the query or one
         * of its words, and nothing in the other groups.
         */
        fun build(
            query: String,
            apps: List<AppEntry>,
            shortcuts: List<ShortcutInfo> = emptyList(),
            skills: List<MasalaSkill> = emptyList(),
            settings: List<SettingsEntry> = emptyList(),
            files: List<FileHit> = emptyList(),
            ask: (String) -> List<String> = { listOf(it) },
        ): SearchGroups {
            val strong = apps.any { a -> listOf(a.label, a.originalLabel).any { (AppSearch.match(query, it)?.tier ?: -1) >= AppSearch.WORD_PREFIX } }
            val askFirst = !strong && shortcuts.isEmpty() && skills.isEmpty() && settings.isEmpty()
            return SearchGroups(askFirst, apps.take(12), shortcuts.take(4), skills.take(3), settings.take(3), files.take(3), ask(query.trim()))
        }

        /** Settings entries whose title or words start with the query. */
        fun matchSettings(query: String, all: List<SettingsEntry>): List<SettingsEntry> {
            val q = AppSearch.normalize(query)
            if (q.length < 2) return emptyList()
            return all.filter { e -> (listOf(e.title) + e.keywords).any { AppSearch.normalize(it).split(' ').any { w -> w.startsWith(q) } || AppSearch.normalize(it).startsWith(q) } }
        }
    }
}

/** The grouped results as list items (search page board): Ask, apps (by the caller), app actions, skills, settings, files, web. */
internal fun LazyListScope.searchGroups(
    groups: SearchGroups,
    query: String,
    sources: SearchSources,
    onAsk: (String) -> Unit,
    apps: LazyListScope.() -> Unit,
) {
    if (groups.askFirst) item(key = "ask") { AskCard(query, groups.suggestions, onAsk) }
    apps()
    if (groups.shortcuts.isNotEmpty()) {
        item(key = "h-actions") { GroupHeader(stringResource(R.string.search_app_actions)) }
        items(groups.shortcuts, key = { "s:" + it.`package` + "/" + it.id }) { s ->
            ResultRow(Icons.Rounded.Widgets, (s.shortLabel ?: s.longLabel ?: s.id).toString(), stringResource(R.string.search_shortcut_of, s.`package`)) { sources.openShortcut(s) }
        }
    }
    if (groups.skills.isNotEmpty()) {
        item(key = "h-skills") { GroupHeader(stringResource(R.string.search_skills)) }
        items(groups.skills, key = { "k:" + it.id }) { k -> ResultRow(Icons.Rounded.AutoAwesome, k.title.ifBlank { k.id }, k.description) { sources.openSkill(k) } }
    }
    if (groups.settings.isNotEmpty()) {
        item(key = "h-settings") { GroupHeader(stringResource(R.string.search_settings)) }
        items(groups.settings, key = { "p:" + it.page }) { e -> ResultRow(Icons.Rounded.Settings, e.title, null) { sources.openSetting(e) } }
    }
    if (groups.files.isNotEmpty()) {
        item(key = "h-files") { GroupHeader(stringResource(R.string.search_files), stringResource(R.string.search_files_note)) }
        items(groups.files, key = { "f:" + it.path }) { f -> ResultRow(Icons.Rounded.Description, f.path.substringAfterLast('/'), listOf(f.where, f.passage).filter { it.isNotBlank() }.joinToString(" · ")) { sources.openFile(f) } }
    }
    if (!groups.askFirst) item(key = "ask") { AskCard(query, groups.suggestions, onAsk) }
    item(key = "web") { ResultRow(Icons.Rounded.Language, stringResource(R.string.search_web, query.trim()), null) { sources.webSearch(query.trim()) } }
}

@Composable
internal fun GroupHeader(title: String, note: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MasalaTheme.tokens.accentText, modifier = Modifier.weight(1f).semantics { heading() })
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ResultRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(icon, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** "Ask Masala" with the query and two suggested questions (search page board). */
@Composable
private fun AskCard(query: String, suggestions: List<String>, onAsk: (String) -> Unit) {
    val tokens = MasalaTheme.tokens
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(shape).background(tokens.accent.copy(alpha = 0.10f))
            .border(1.dp, tokens.accent.copy(alpha = 0.35f), shape),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { onAsk(query.trim()) }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconTile(Icons.Rounded.AutoAwesome, size = 40.dp, filled = true)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.search_ask), style = MaterialTheme.typography.titleSmall)
                Text("“${query.trim()}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.AutoAwesome, null, tint = tokens.accentText, modifier = Modifier.size(18.dp))
        }
        Row(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.take(2).forEach { s ->
                Text(
                    s, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(50)).border(1.dp, tokens.hairline, RoundedCornerShape(50))
                        .clickable(role = Role.Button) { onAsk(s) }.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}
