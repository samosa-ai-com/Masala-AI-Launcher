package com.samosaai.masala.layout

import com.samosaai.masala.core.apps.AppEntry
import com.samosaai.masala.core.apps.AppKey
import com.samosaai.masala.core.contract.ItemSection
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.SkillWidgetSection
import com.samosaai.masala.core.contract.WidgetSection
import com.samosaai.masala.core.layout.KnownSymbols

/** An installed skill the model may place as a widget. Titles only; instructions stay out. */
data class PromptSkill(val id: String, val title: String)

/** A stored mini-app the model may place on home. */
data class PromptMiniApp(val id: String, val title: String)

/**
 * Everything the layout prompt is grounded in (plan Phase 3, lane C): installed apps, the current
 * layout, today's calendar, the icon catalog, installed skills and wiki topics. Plain data in, one
 * string out — no Android types, so the budget rule is unit-testable.
 */
data class PromptContextInput(
    val apps: List<AppEntry>,
    /** Lower wins; apps without a rank sort after ranked ones, by label. Null turns ranking off. */
    val usageRank: Map<AppKey, Int>? = null,
    val layout: LayoutDoc? = null,
    /** Pre-rendered calendar lines, e.g. "Next: Standup in 12 min". Null omits the section. */
    val calendar: String? = null,
    val skills: List<PromptSkill> = emptyList(),
    /** Stored mini-apps (id: title), so layouts can place "open_mini_app" items. */
    val miniApps: List<PromptMiniApp> = emptyList(),
    /** Page titles the Second Brain holds; shown as "(Second Brain empty)" when there are none. */
    val wikiTopics: List<String> = emptyList(),
    val symbols: Set<String> = KnownSymbols.promptable,
)

object PromptContext {

    /** Rough token guard (~4 chars per token): the app list is trimmed to fit, low rank first. */
    const val MAX_PROMPT_CHARS = 24_000

    fun build(input: PromptContextInput): String {
        val out = StringBuilder()
        out.append("Installed apps (label | package/activity#user):\n")
        val ranked = input.usageRank
        val ordered = if (ranked == null) {
            input.apps.sortedBy { it.label.lowercase() }
        } else {
            input.apps.sortedWith(
                compareBy<AppEntry> { ranked[it.key] ?: Int.MAX_VALUE }
                    .thenBy { it.label.lowercase() }
                    .thenBy { it.key.id }
            )
        }
        val lines = ordered.map { "${it.label} | ${it.key.id}" }
        val kept = trimToFit(lines, out.length + sectionsSize(input))
        out.append(kept.joinToString("\n"))
        if (kept.size < lines.size) out.append("\n… ${lines.size - kept.size} low-ranked apps omitted")
        out.append("\n\nCurrent layout:\n")
        out.append(if (input.layout == null) "(none yet)" else summarizeLayout(input.layout))
        if (!input.calendar.isNullOrBlank()) out.append("\n\nCalendar:\n").append(input.calendar)
        out.append("\n\nInstalled skills (id: title):\n")
        out.append(if (input.skills.isEmpty()) "(none)" else input.skills.joinToString("\n") { "${it.id}: ${it.title}" })
        out.append("\n\nMini apps (id: title):\n")
        out.append(if (input.miniApps.isEmpty()) "(none)" else input.miniApps.joinToString("\n") { "${it.id}: ${it.title}" })
        out.append("\n\nSecond Brain topics:\n")
        out.append(if (input.wikiTopics.isEmpty()) "(Second Brain empty)" else input.wikiTopics.joinToString(", "))
        out.append("\n\nIcons allowed (symbol names):\n")
        out.append(input.symbols.sorted().joinToString(", "))
        val text = out.toString()
        // The fixed sections are small by construction; the app list carries the trim.
        return if (text.length <= MAX_PROMPT_CHARS) text else text.take(MAX_PROMPT_CHARS)
    }

    /** One line per page: sections with kinds, item counts and the first labels. */
    fun summarizeLayout(layout: LayoutDoc): String = layout.pages.joinToString("\n") { page ->
        val sections = page.sections.joinToString("; ") { section ->
            val kind = section::class.simpleName?.removeSuffix("Section")?.lowercase() ?: "section"
            val detail = when (section) {
                is ItemSection -> "${section.items.size} items" +
                    section.items.take(3).joinToString(", ", prefix = " [", postfix = "]") { it.label }
                is WidgetSection -> "widget ${section.widgetId}"
                is SkillWidgetSection -> "skill ${section.skillId}"
                else -> ""
            }
            "$kind($detail)"
        }
        "Page '${page.title ?: page.id}': $sections"
    }

    /** Size of everything after the app list, so trimming leaves room for it. Exact, not estimated:
     * an underestimate trims too little and the hard cut below eats the trailing icon catalog. */
    private fun sectionsSize(input: PromptContextInput): Int {
        val layoutSummary = if (input.layout == null) "(none yet)" else summarizeLayout(input.layout)
        val wikiSummary = if (input.wikiTopics.isEmpty()) "(Second Brain empty)" else input.wikiTopics.joinToString(", ")
        // Omission notice appended when apps are trimmed.
        val omissionBuffer = 50
        var n = "\n\nCurrent layout:\n".length + layoutSummary.length + omissionBuffer
        if (!input.calendar.isNullOrBlank()) n += "\n\nCalendar:\n".length + input.calendar.length
        n += "\n\nInstalled skills (id: title):\n".length +
            input.skills.sumOf { it.id.length + it.title.length + 3 }.coerceAtLeast("(none)".length)
        n += "\n\nMini apps (id: title):\n".length +
            input.miniApps.sumOf { it.id.length + it.title.length + 3 }.coerceAtLeast("(none)".length)
        n += "\n\nSecond Brain topics:\n".length + wikiSummary.length
        n += "\n\nIcons allowed (symbol names):\n".length + input.symbols.sumOf { it.length + 2 }
        return n
    }

    private fun trimToFit(lines: List<String>, reserved: Int): List<String> {
        val budget = (MAX_PROMPT_CHARS - reserved).coerceAtLeast(0)
        var used = 0
        val kept = mutableListOf<String>()
        for (line in lines) {
            if (used + line.length + 1 > budget) break
            kept += line
            used += line.length + 1
        }
        return kept
    }
}
