package com.samosaai.masala.core.widgets

import com.samosaai.masala.core.Fixtures
import com.samosaai.masala.core.contract.HttpBinding
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.contract.Page
import com.samosaai.masala.core.contract.SizeRange
import com.samosaai.masala.core.contract.StackSection
import com.samosaai.masala.core.contract.StreamRef
import com.samosaai.masala.core.contract.WidgetComponent
import com.samosaai.masala.core.contract.WidgetRef
import com.samosaai.masala.core.contract.WidgetSection
import com.samosaai.masala.core.contract.WidgetSpec
import com.samosaai.masala.core.layout.LayoutChecker
import com.samosaai.masala.core.layout.LayoutChecker.Environment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetCheckerTest {
    private val env = Environment(installedApps = emptyMap(), skillIds = setOf("chef"))

    @Test
    fun `every template decodes and passes`() {
        assertEquals(WidgetTemplates.ids.size, WidgetTemplates.ids.toSet().size)
        for (template in WidgetTemplates.all()) {
            assertEquals(emptyList(), WidgetChecker.check(template.widget, env), template.id)
            assertTrue(template.description.isNotBlank(), template.id)
        }
    }

    @Test
    fun `every bad fixture fails at the right path, and only there`() {
        val cases = MasalaJson.strict.parseToJsonElement(Fixtures.text("widgets-bad.json")) as JsonArray
        assertTrue(cases.size >= 20)
        for (case in cases) {
            val obj = case.jsonObject
            val name = obj.getValue("name").jsonPrimitive.content
            val parsed = WidgetChecker.parse(obj.getValue("widget").toString())
            val widget = assertNotNull(parsed.widget, "$name: ${parsed.problems}")
            val problems = WidgetChecker.check(widget, env)
            assertEquals(listOf(obj.getValue("path").jsonPrimitive.content), problems.map { it.path }, "$name: $problems")
        }
    }

    @Test
    fun `parse reports where decoding stopped`() {
        val parsed = WidgetChecker.parse("""{"id":"w","components":[{"type":"counter","key":"k","label":"x","colour":"red"}]}""")
        assertNull(parsed.widget)
        assertEquals("$.components[0]", parsed.problems.single().path)
    }

    @Test
    fun `stream sources and skills are checked when known`() {
        val known = env.copy(streamSourceIds = setOf("front-door"))
        val video = WidgetSpec("w", components = listOf(WidgetComponent.Video(StreamRef("garage"))))
        assertEquals(listOf("$.components[0].stream.sourceId"), WidgetChecker.check(video, known).map { it.path })
        assertEquals(emptyList(), WidgetChecker.check(video, env)) // unknown registry: skipped

        val skill = WidgetSpec(
            "w",
            components = listOf(
                WidgetComponent.Text(binding = com.samosaai.masala.core.contract.Binding(com.samosaai.masala.core.contract.BindingSource.SKILL_RESULT, "nope")),
            ),
        )
        assertEquals(listOf("$.components[0].binding.key"), WidgetChecker.check(skill, env).map { it.path })
    }

    @Test
    fun `a refresh button passes with or without naming its widget`() {
        val blank = WidgetSpec(
            "w",
            components = listOf(
                WidgetComponent.Button("Refresh", com.samosaai.masala.core.contract.MasalaAction.RefreshWidget()),
            ),
        )
        assertEquals(emptyList(), WidgetChecker.check(blank, env))
        val named = blank.copy(
            components = listOf(
                WidgetComponent.Button("Refresh", com.samosaai.masala.core.contract.MasalaAction.RefreshWidget("w")),
            ),
        )
        assertEquals(emptyList(), WidgetChecker.check(named, env))
    }

    @Test
    fun `url rules`() {
        assertNull(WidgetChecker.urlProblem("https://api.example.com/v1/price?symbol=BTC"))
        assertNull(WidgetChecker.urlProblem("http://192.168.1.20:8080/status.json"))
        assertNotNull(WidgetChecker.urlProblem("rtsp://cam/stream"))
        assertNotNull(WidgetChecker.urlProblem("https://u:p@cam/stream"))
        assertNotNull(WidgetChecker.urlProblem("https://cam/x?token=abc"))
        assertNotNull(WidgetChecker.urlProblem("https://cam/x?a=1&API_KEY=abc"))
        assertNotNull(WidgetChecker.urlProblem("https:///nohost"))
        assertNotNull(WidgetChecker.urlProblem("https://a.example/\${city}"))
    }

    // Layouts with refs (schema 3)

    private fun layout(vararg sections: com.samosaai.masala.core.contract.Section) =
        LayoutDoc(pages = listOf(Page("home", sections = sections.toList())))

    private val water = WidgetSpec("water", components = listOf(WidgetComponent.Counter("n", "Glasses")), size = SizeRange(1, 1, 2))

    @Test
    fun `a ref must point at a library widget and stay in its range`() {
        val lib = env.copy(widgets = mapOf("water" to water))
        assertEquals(emptyList(), LayoutChecker.check(layout(WidgetSection("s", ref = WidgetRef("water", 2))), lib))
        assertEquals(
            listOf("$.pages[0].sections[0].ref.widgetId"),
            LayoutChecker.check(layout(WidgetSection("s", ref = WidgetRef("nope"))), lib).map { it.path },
        )
        assertEquals(
            listOf("$.pages[0].sections[0].ref.rowsOverride"),
            LayoutChecker.check(layout(WidgetSection("s", ref = WidgetRef("water", 3))), lib).map { it.path },
        )
        // Without a library the ref isn't looked up, but rows are still bounded.
        assertEquals(emptyList(), LayoutChecker.check(layout(WidgetSection("s", ref = WidgetRef("nope"))), env))
        assertEquals(
            listOf("$.pages[0].sections", "$.pages[0].sections[0].ref.rowsOverride"),
            LayoutChecker.check(layout(WidgetSection("s", ref = WidgetRef("x", 99))), env).map { it.path },
        )
    }

    @Test
    fun `the same library widget may sit in two places, but not twice in one stack`() {
        val lib = env.copy(widgets = mapOf("water" to water))
        val twice = layout(WidgetSection("a", ref = WidgetRef("water")), WidgetSection("b", ref = WidgetRef("water")))
        assertEquals(emptyList(), LayoutChecker.check(twice, lib))
        val stack = layout(StackSection("st", refs = listOf(WidgetRef("water"), WidgetRef("water"))))
        assertEquals(listOf("$.pages[0].sections[0].refs[1].widgetId"), LayoutChecker.check(stack, lib).map { it.path })
    }

    @Test
    fun `a section needs exactly one of ref and widget`() {
        assertEquals(listOf("$.pages[0].sections[0].ref"), LayoutChecker.check(layout(WidgetSection("s")), env).map { it.path })
        assertEquals(
            listOf("$.pages[0].sections[0]"),
            LayoutChecker.check(layout(WidgetSection("s", widget = water, ref = WidgetRef("water"))), env).map { it.path },
        )
        assertEquals(
            listOf("$.pages[0].sections[0]"),
            LayoutChecker.check(layout(StackSection("s", widgets = listOf(water), refs = listOf(WidgetRef("water")))), env).map { it.path },
        )
        assertEquals(listOf("$.pages[0].sections[0].refs"), LayoutChecker.check(layout(StackSection("s")), env).map { it.path })
    }

    @Test
    fun `inline widgets in a layout are checked by the widget checker`() {
        val bad = WidgetSpec("w", components = listOf(WidgetComponent.WebValue("x", HttpBinding("https://u:p@a.example/"))))
        assertEquals(
            listOf("$.pages[0].sections[0].widget.components[0].http.url"),
            LayoutChecker.check(layout(WidgetSection("s", widget = bad)), env).map { it.path },
        )
    }

    @Test
    fun `widget buttons allow only safe run_tool names and click sounds`() {
        fun button(action: com.samosaai.masala.core.contract.MasalaAction) =
            WidgetSpec("w", components = listOf(WidgetComponent.Button("Go", action)))
        val tools = com.samosaai.masala.core.layout.LayoutChecker.Environment(
            installedApps = emptyMap(), skillIds = emptySet(), toolNames = setOf("toggle_torch", "toggle_wifi", "set_timer", "read_file"),
        )
        assertEquals(emptyList(), WidgetChecker.check(button(com.samosaai.masala.core.contract.MasalaAction.RunTool("toggle_torch")), tools))
        assertEquals(emptyList(), WidgetChecker.check(button(com.samosaai.masala.core.contract.MasalaAction.PlaySound("click")), env))
        assertEquals(
            listOf("$.components[0].action.name"),
            WidgetChecker.check(button(com.samosaai.masala.core.contract.MasalaAction.RunTool("read_file")), tools).map { it.path },
        )
        assertEquals(
            listOf("$.components[0].action.kind"),
            WidgetChecker.check(button(com.samosaai.masala.core.contract.MasalaAction.PlaySound("boop")), env).map { it.path },
        )
    }
}
