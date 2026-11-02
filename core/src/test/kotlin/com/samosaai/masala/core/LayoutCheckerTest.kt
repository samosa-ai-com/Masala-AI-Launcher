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
        assertTrue(KnownSymbols.all.all { Regex("[a-z_]+").matches(it) })
        // The model may only name the smaller promptable set, and that set must stay drawable.
        assertTrue(KnownSymbols.promptable.all { Regex("[a-z_]+").matches(it) })
        assertTrue(KnownSymbols.all.containsAll(KnownSymbols.promptable))
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

    @Test
    fun `a folder in a widget button is a problem`() {
        val button = WidgetComponent.Button("Open", MasalaAction.OpenFolder(listOf(item("a"))))
        val l = layout(com.samosaai.masala.core.contract.WidgetSection("ws", WidgetSpec("w", components = listOf(button))))
        assertEquals(listOf("$.pages[0].sections[0].widget.components[0].action"), paths(l))
    }

    @Test
    fun `an html section needs a known app, sane rows, and at most two per page`() {        val rich = env.copy(miniAppIds = setOf("dice"))
        assertEquals(emptyList(), LayoutChecker.check(layout(com.samosaai.masala.core.contract.HtmlSection("h", "dice", rows = 3)), rich))
        assertEquals(
            listOf("$.pages[0].sections[0].appId"),
            LayoutChecker.check(layout(com.samosaai.masala.core.contract.HtmlSection("h", "gone")), rich).map { it.path },
        )
        assertEquals(
            listOf("$.pages[0].sections[0].appId"),
            LayoutChecker.check(layout(com.samosaai.masala.core.contract.HtmlSection("h", "")), rich).map { it.path },
        )
        assertEquals(
            listOf("$.pages[0].sections", "$.pages[0].sections[0].rows"),
            LayoutChecker.check(layout(com.samosaai.masala.core.contract.HtmlSection("h", "dice", rows = 9)), rich).map { it.path },
        )
        val three = layout(
            com.samosaai.masala.core.contract.HtmlSection("h1", "dice"),
            com.samosaai.masala.core.contract.HtmlSection("h2", "dice"),
            com.samosaai.masala.core.contract.HtmlSection("h3", "dice"),
        )
        assertEquals(listOf("$.pages[0].sections"), LayoutChecker.check(three, rich).map { it.path })
    }

    @Test
    fun `a page holds at most eight declared widget rows`() {
        fun widget(sectionId: String, id: String, default: Int) = com.samosaai.masala.core.contract.WidgetSection(
            sectionId,
            com.samosaai.masala.core.contract.WidgetSpec(id, components = listOf(com.samosaai.masala.core.contract.WidgetComponent.Text("x")), size = com.samosaai.masala.core.contract.SizeRange(1, default, 8)),
        )
        val full = layout(widget("sa", "a", 4), widget("sb", "b", 4))
        assertEquals(emptyList(), LayoutChecker.check(full, env))
        val over = layout(widget("sa", "a", 5), widget("sb", "b", 4))
        assertEquals(listOf("$.pages[0].sections"), LayoutChecker.check(over, env).map { it.path })
    }

    private fun cell(id: String, x: Int, y: Int, w: Int = 1, h: Int = 1, content: com.samosaai.masala.core.contract.CellContent =
        com.samosaai.masala.core.contract.CellContent.ItemCell(item("i$id"))) =
        com.samosaai.masala.core.contract.Cell(id, x, y, w, h, content)

    private fun cells(vararg c: com.samosaai.masala.core.contract.Cell) =
        layout(com.samosaai.masala.core.contract.CellGridSection("g", cells = c.toList()))

    @Test
    fun `a cell grid with icons and a skill widget side by side passes`() {
        val skill = com.samosaai.masala.core.contract.CellContent.SkillCell("chef")
        assertEquals(emptyList(), paths(cells(cell("a", 0, 0), cell("b", 1, 0), cell("s", 2, 0, w = 2, h = 2, content = skill))))
    }

    @Test
    fun `overlapping, out of bounds and stretched icon cells are rejected`() {
        assertEquals(listOf("$.pages[0].sections[0].cells[1]"), paths(cells(cell("a", 0, 0), cell("b", 0, 0))))
        assertEquals(listOf("$.pages[0].sections[0].cells[0]"), paths(cells(cell("a", 3, 0, w = 2, content = com.samosaai.masala.core.contract.CellContent.SkillCell("chef")))))
        assertEquals(listOf("$.pages[0].sections[0].cells[0]"), paths(cells(cell("a", 0, 0, w = 2))))
    }

    @Test
    fun `a widget cell must fit its width range`() {
        val spec = WidgetSpec("w", components = listOf(com.samosaai.masala.core.contract.WidgetComponent.Text(text = "hi")), size = SizeRange(maxW = 2, defaultW = 2))
        val withLib = env.copy(widgets = mapOf("w" to spec))
        val wide = cells(cell("a", 0, 0, w = 4, content = com.samosaai.masala.core.contract.CellContent.WidgetCell(com.samosaai.masala.core.contract.WidgetRef("w"))))
        assertEquals(listOf("$.pages[0].sections[0].cells[0].w"), LayoutChecker.check(wide, withLib).map { it.path })
    }

    @Test
    fun `HTML cells count toward the per-page WebView cap`() {
        val html = { app: String -> com.samosaai.masala.core.contract.CellContent.HtmlCell(app) }
        assertEquals(emptyList(), paths(cells(cell("a", 0, 0, content = html("x")), cell("b", 1, 0, content = html("y")))))
        val withSection = layout(
            com.samosaai.masala.core.contract.HtmlSection("h", appId = "z"),
            com.samosaai.masala.core.contract.CellGridSection("g", cells = listOf(cell("a", 0, 0, content = html("x")), cell("b", 1, 0, content = html("y")))),
        )
        assertEquals(listOf("$.pages[0].sections"), paths(withSection))
    }

    @Test
    fun `a cell past the grid's row limit is rejected`() {
        val far = cell("a", 0, LayoutChecker.MAX_GRID_ROWS, content = com.samosaai.masala.core.contract.CellContent.SkillCell("chef"))
        assertEquals(listOf("$.pages[0].sections[0].cells[0]"), paths(cells(far)))
    }
}
