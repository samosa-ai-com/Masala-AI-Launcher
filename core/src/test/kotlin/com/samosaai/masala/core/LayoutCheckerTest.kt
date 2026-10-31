package com.samosaai.masala.core

import com.samosaai.masala.core.contract.Condition
import com.samosaai.masala.core.contract.DockSection
import com.samosaai.masala.core.contract.Effect
import com.samosaai.masala.core.contract.GridSection
import com.samosaai.masala.core.contract.IconRef
import com.samosaai.masala.core.contract.Item
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.Page
import com.samosaai.masala.core.contract.Rule
import com.samosaai.masala.core.contract.SizeRange
import com.samosaai.masala.core.contract.SkillWidgetSection
import com.samosaai.masala.core.contract.StackSection
import com.samosaai.masala.core.contract.WidgetComponent
import com.samosaai.masala.core.contract.WidgetSpec
import com.samosaai.masala.core.layout.KnownSymbols
import com.samosaai.masala.core.layout.LayoutChecker
import com.samosaai.masala.core.layout.LayoutChecker.Environment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoutCheckerTest {
    private val env = Environment(
        installedApps = mapOf("com.a" to setOf(0L), "com.b" to setOf(0L, 10L)),
        skillIds = setOf("chef"),
        toolNames = setOf("set_alarm"),
        modeIds = setOf("focus"),
    )

    private fun item(id: String, action: MasalaAction = MasalaAction.OpenDrawer, icon: IconRef = IconRef.Symbol("apps"), rules: List<Rule> = emptyList()) =
        Item(id, "Label $id", icon, action, rules)

    private fun layout(vararg sections: com.samosaai.masala.core.contract.Section) =
        LayoutDoc(pages = listOf(Page("home", sections = sections.toList())))

    private fun paths(l: LayoutDoc) = LayoutChecker.check(l, env).map { it.path }

    @Test
    fun `the shipped examples pass when their apps are installed`() {
        for (name in listOf("classic", "workday")) {
            val doc = Fixtures.layout(name)
            val apps = doc.toString().let { Regex("""packageName=([\w.]+)""").findAll(it).map { m -> m.groupValues[1] }.toSet() }
            val full = env.copy(
                installedApps = apps.associateWith { setOf(0L) },
                skillIds = Fixtures.skillExamples.toSet(),
                toolNames = null,
                modeIds = null,
            )
            assertEquals(emptyList(), LayoutChecker.check(doc, full), name)
        }
    }

    @Test
    fun `an app that is not installed for that user is reported at its path`() {
        val l = layout(GridSection("g", items = listOf(item("x", MasalaAction.LaunchApp("com.missing"), IconRef.AppIcon("com.a")))))
        assertEquals(listOf("$.pages[0].sections[0].items[0].action.packageName"), paths(l))
        val work = layout(GridSection("g", items = listOf(item("x", MasalaAction.LaunchApp("com.a", user = 10)))))
        assertEquals(listOf("$.pages[0].sections[0].items[0].action.packageName"), paths(work))
        assertEquals(emptyList(), paths(layout(GridSection("g", items = listOf(item("x", MasalaAction.LaunchApp("com.b", user = 10)))))))
    }

    @Test
    fun `duplicate ids are reported across pages sections items and widgets`() {
        val l = LayoutDoc(
            pages = listOf(
                Page("home", sections = listOf(GridSection("g", items = listOf(item("a"), item("a"))))),
                Page("g", sections = emptyList()),
            ),
        )
        val problems = LayoutChecker.check(l, env)
        assertTrue(problems.any { it.path == "$.pages[0].sections[0].items[1].id" && "already used" in it.message })
        assertTrue(problems.any { it.path == "$.pages[1].id" })
    }

    @Test
    fun `item and section limits`() {
        val many = List(LayoutChecker.MAX_ITEMS_PER_SECTION + 1) { item("i$it") }
        assertEquals(listOf("$.pages[0].sections[0].items"), paths(layout(GridSection("g", items = many))))
        val dock = List(LayoutChecker.MAX_DOCK_ITEMS + 1) { item("d$it") }
        assertEquals(listOf("$.pages[0].sections[0].items"), paths(layout(DockSection("dock", dock))))
        assertEquals(listOf("$.pages[0].sections[0].columns"), paths(layout(GridSection("g", columns = 9, items = emptyList()))))
    }

    @Test
    fun `unknown icon and skill`() {
        val l = layout(
            GridSection("g", items = listOf(item("x", icon = IconRef.Symbol("not_a_symbol")))),
            SkillWidgetSection("s", "nope"),
        )
        assertEquals(listOf("$.pages[0].sections[0].items[0].icon.name", "$.pages[0].sections[1].skillId"), paths(l))
        assertTrue(KnownSymbols.names.all { Regex("[a-z_]+").matches(it) })
    }

    @Test
    fun `bad action arguments`() {
        fun problem(action: MasalaAction) = paths(layout(GridSection("g", items = listOf(item("x", action))))).singleOrNull()
        assertNull(problem(MasalaAction.SetAlarm(7, 30)))
        assertNotNull(problem(MasalaAction.SetAlarm(24, 0)))
        assertNotNull(problem(MasalaAction.SetAlarm(7, 60)))
        assertNotNull(problem(MasalaAction.StartTimer(0)))
        assertNull(problem(MasalaAction.OpenUrl("https://example.com")))
        assertNotNull(problem(MasalaAction.OpenUrl("javascript:alert(1)")))
        assertNotNull(problem(MasalaAction.OpenUrl("file:///sdcard/x")))
        assertNull(problem(MasalaAction.Dial("+49 30 1234")))
        assertNotNull(problem(MasalaAction.Dial("call mum")))
        assertNotNull(problem(MasalaAction.ComposeMessage()))
        assertNull(problem(MasalaAction.RunTool("set_alarm")))
        assertNotNull(problem(MasalaAction.RunTool("rm_rf")))
        assertNull(problem(MasalaAction.SetMode("focus", true)))
        assertNotNull(problem(MasalaAction.SetMode("gone", true)))
        assertNull(problem(MasalaAction.RunSkill("chef")))
        assertNotNull(problem(MasalaAction.RunSkill("nope")))
    }

    private fun widget(size: SizeRange = SizeRange(), rules: List<Rule> = emptyList()) =
        WidgetSpec("w", components = listOf(WidgetComponent.Text("hi")), size = size, rules = rules)

    @Test
    fun `sizes must be in range and a size effect must fit the widget`() {
        fun p(w: WidgetSpec) = paths(layout(StackSection("st", listOf(w))))
        assertEquals(emptyList(), p(widget()))
        assertEquals(listOf("$.pages[0].sections[0].widgets[0].size"), p(widget(SizeRange(3, 2, 4))))
        assertEquals(listOf("$.pages[0].sections[0].widgets[0].size"), p(widget(SizeRange(1, 2, 20))))
        val tooBig = Rule(Condition.Charging(), Effect.Size(6))
        assertEquals(listOf("$.pages[0].sections[0].widgets[0].rules[0].effect.rows"), p(widget(rules = listOf(tooBig))))
        val onItem = item("x", rules = listOf(tooBig))
        assertEquals(listOf("$.pages[0].sections[0].items[0].rules[0].effect"), paths(layout(GridSection("g", items = listOf(onItem)))))
    }

    @Test
    fun `contradictory conditions are reported`() {
        val rule = Rule(Condition.AllOf(listOf(Condition.MinutesToNextEvent(maxMinutes = 5, minMinutes = 30))), Effect.Show)
        assertEquals(
            listOf("$.pages[0].sections[0].items[0].rules[0].condition.conditions[0]"),
            paths(layout(GridSection("g", items = listOf(item("x", rules = listOf(rule)))))),
        )
    }

    @Test
    fun `parse reports the path where decoding stopped`() {
        val bad = """{"schemaVersion":1,"pages":[{"id":"home","sections":[{"type":"grid","id":"g","items":[{"id":"a","label":"A","icon":{"type":"symbol","name":"apps"},"action":{"type":"open_drawer"},"extra":1}]}]}]}"""
        val parsed = LayoutChecker.parse(bad)
        assertNull(parsed.layout)
        assertTrue(parsed.problems.single().path.startsWith("$.pages[0].sections[0].items[0]"), parsed.problems.toString())
        assertEquals("$", LayoutChecker.parse("[1]").problems.single().path)
        assertNull(LayoutChecker.parse("not json").layout)
        assertNotNull(LayoutChecker.parse(Fixtures.text("examples/classic.json")).layout)
    }

    @Test
    fun `a newer schema is refused with a message`() {
        val parsed = LayoutChecker.parse("""{"schemaVersion":99,"pages":[]}""")
        assertNull(parsed.layout)
        assertTrue("newer" in parsed.problems.single().message)
    }

    private fun folder(id: String, children: List<Item>) = item(id, MasalaAction.OpenFolder(children))

    private fun folderProblems(vararg items: Item) =
        LayoutChecker.check(layout(GridSection("g", items = items.toList())), env)

    @Test
    fun `a valid folder has no problems`() {
        assertEquals(emptyList(), folderProblems(folder("f", listOf(item("a"), item("b")))))
    }

    @Test
    fun `a folder holds at most 16 items`() {
        val problems = folderProblems(folder("f", (1..17).map { item("c$it") }))
        assertEquals(listOf("$.pages[0].sections[0].items[0].action.items"), problems.map { it.path })
    }

    @Test
    fun `folders can't contain folders`() {
        val problems = folderProblems(folder("f", listOf(item("a"), folder("inner", listOf(item("b"))))))
        assertEquals(listOf("$.pages[0].sections[0].items[0].action.items[1].action"), problems.map { it.path })
    }

    @Test
    fun `items in a folder can't have rules`() {
        val ruled = item("a", rules = listOf(Rule(Condition.Charging(), Effect.Show)))
        val problems = folderProblems(folder("f", listOf(ruled)))
        assertEquals(listOf("$.pages[0].sections[0].items[0].action.items[0].rules"), problems.map { it.path })
    }

    @Test
    fun `a folder child's id must not repeat a section item's id`() {
        val problems = folderProblems(item("a"), folder("f", listOf(item("a"))))
        assertEquals(listOf("$.pages[0].sections[0].items[1].action.items[0].id"), problems.map { it.path })
    }

    @Test
    fun `a folder name over the limit is a problem`() {
        val long = folder("f", listOf(item("a"))).copy(label = "x".repeat(LayoutChecker.MAX_FOLDER_LABEL + 1))
        assertEquals(listOf("$.pages[0].sections[0].items[0].label"), folderProblems(long).map { it.path })
    }
}
