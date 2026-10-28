package com.samosaai.masala.layout

import com.samosaai.masala.core.apps.AppEntry
import com.samosaai.masala.core.apps.AppKey
import com.samosaai.masala.core.apps.ProfileKind
import com.samosaai.masala.core.apps.Visibility
import com.samosaai.masala.core.contract.DockSection
import com.samosaai.masala.core.contract.GridSection
import com.samosaai.masala.core.contract.HeaderSection
import com.samosaai.masala.core.contract.IconRef
import com.samosaai.masala.core.contract.Item
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.Page
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PromptContextTest {

    private fun entry(label: String, pkg: String = "com.example." + label.lowercase()) =
        AppEntry(AppKey(pkg, "$pkg.Main", 0), label, label, ProfileKind.PERSONAL, Visibility.DEFAULT, false)

    private fun input(apps: List<AppEntry>, usageRank: Map<AppKey, Int>? = null) = PromptContextInput(
        apps = apps,
        usageRank = usageRank,
        layout = LayoutDoc(
            pages = listOf(
                Page(
                    id = "home",
                    title = "Home",
                    sections = listOf(
                        HeaderSection(id = "h"),
                        GridSection(
                            id = "g",
                            columns = 4,
                            title = "Apps",
                            items = listOf(
                                Item("i1", "Phone", IconRef.Symbol("call"), MasalaAction.OpenDrawer),
                            ),
                        ),
                        DockSection(id = "d", items = emptyList()),
                    ),
                ),
            ),
        ),
        calendar = "Next: Standup in 12 min",
        skills = listOf(PromptSkill("meeting_notes", "Meeting notes")),
        symbols = setOf("call", "home"),
    )

    @Test
    fun `grounding sections are all present`() {
        val text = PromptContext.build(input(listOf(entry("Phone"), entry("Maps"))))
        assertTrue(text.contains("Phone | com.example.phone/com.example.phone.Main#0"))
        assertTrue(text.contains("Page 'Home'"))
        assertTrue(text.contains("Next: Standup in 12 min"))
        assertTrue(text.contains("meeting_notes: Meeting notes"))
        assertTrue(text.contains("(wiki empty)"))
        assertTrue(text.contains("call, home"))
    }

    @Test
    fun `low-ranked apps are trimmed first under the budget`() {
        val apps = (1..2000).map { entry("App$it", "com.example.app$it") }
        val rank = apps.take(3).mapIndexed { i, app -> app.key to i }.toMap()
        val text = PromptContext.build(input(apps, rank))
        assertTrue(text.length <= PromptContext.MAX_PROMPT_CHARS, "over budget: ${text.length}")
        assertTrue(text.contains("App1 |"), "top-ranked app dropped")
        assertTrue(text.contains("omitted"), "no omission marker")
        assertTrue(!text.contains("App2000 |"), "lowest-ranked app kept")
    }

    @Test
    fun `empty catalog and wiki stay explicit`() {
        val text = PromptContext.build(input(emptyList()).copy(skills = emptyList(), layout = null, calendar = null))
        assertTrue(text.contains("(none yet)"))
        assertTrue(text.contains("(wiki empty)"))
    }

    @Test
    fun `layout summary names kinds and counts`() {
        val summary = PromptContext.summarizeLayout(input(listOf()).layout!!)
        assertEquals("Page 'Home': header(); grid(1 items [Phone]); dock(0 items [])", summary)
    }

    @Test
    fun `large layout and wiki do not eat the icon catalog`() {
        val manyPages = (1..60).map { n ->
            com.samosaai.masala.core.contract.Page(
                id = "p$n",
                title = "Page number $n with a fairly long title for size",
                sections = listOf(
                    HeaderSection(id = "h$n"),
                    GridSection(
                        id = "g$n",
                        columns = 4,
                        title = "Lots of apps go here on this page",
                        items = (1..20).map { i ->
                            Item(
                                "i$n-$i",
                                "Application number $i on page $n",
                                IconRef.Symbol("call"),
                                MasalaAction.OpenDrawer,
                            )
                        },
                    ),
                ),
            )
        }
        val big = input(listOf(entry("Phone"))).copy(
            layout = LayoutDoc(pages = manyPages),
            wikiTopics = (1..40).map { "Wiki topic number $it about the user's routines and preferences" },
        )
        val text = PromptContext.build(big)
        assertTrue(text.length <= PromptContext.MAX_PROMPT_CHARS, "over budget: ${text.length}")
        assertTrue(text.contains("Icons allowed"), "icon catalog was cut")
        assertTrue(text.contains("meeting_notes"))
    }
}
