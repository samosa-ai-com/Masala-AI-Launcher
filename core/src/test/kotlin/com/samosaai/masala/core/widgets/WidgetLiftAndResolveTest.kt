package com.samosaai.masala.core.widgets

import com.samosaai.masala.core.Fixtures
import com.samosaai.masala.core.Fixtures.at
import com.samosaai.masala.core.contract.AddSection
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.contract.ModePatch
import com.samosaai.masala.core.contract.NextEvent
import com.samosaai.masala.core.contract.Page
import com.samosaai.masala.core.contract.SizeRange
import com.samosaai.masala.core.contract.StackSection
import com.samosaai.masala.core.contract.WidgetComponent
import com.samosaai.masala.core.contract.WidgetRef
import com.samosaai.masala.core.contract.WidgetSection
import com.samosaai.masala.core.contract.WidgetSpec
import com.samosaai.masala.core.layout.LayoutChecker
import com.samosaai.masala.core.layout.LayoutResolver
import com.samosaai.masala.core.layout.ResolveCache
import com.samosaai.masala.core.migration.LayoutMigrations
import com.samosaai.masala.core.migration.WidgetLift
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetLiftAndResolveTest {
    private val workday = Fixtures.layout("workday")

    @Test
    fun `old layouts migrate to the current schema with their inline widgets intact`() {
        assertEquals(LayoutDoc.CURRENT_SCHEMA_VERSION, workday.schemaVersion)
        assertEquals(4, LayoutDoc.CURRENT_SCHEMA_VERSION)
        assertTrue(WidgetLift.hasInline(workday))
    }

    @Test
    fun `lifting moves every inline widget into the library and keeps ids`() {
        val lifted = WidgetLift.lift(workday)
        assertEquals(listOf("next-event", "water", "steps"), lifted.widgets.map { it.id })
        assertEquals(emptyMap(), lifted.renamed)
        assertFalse(WidgetLift.hasInline(lifted.value))
        val sections = lifted.value.pages[0].sections
        assertEquals(WidgetRef("next-event"), sections.filterIsInstance<WidgetSection>().single().ref)
        assertEquals(listOf("water", "steps"), sections.filterIsInstance<StackSection>().single().refs.map { it.widgetId })

        // The lifted layout passes the checker against its new library, and round-trips as JSON.
        val library = lifted.widgets.associateBy { it.id }
        val apps = lifted.value.toString().let { Regex("""packageName=([\w.]+)""").findAll(it).map { m -> m.groupValues[1] }.toSet() }
        val env = LayoutChecker.Environment(apps.associateWith { setOf(0L) }, Fixtures.skillExamples.toSet(), widgets = library)
        assertEquals(emptyList(), LayoutChecker.check(lifted.value, env))
        val json = MasalaJson.strict.encodeToString(LayoutDoc.serializer(), lifted.value)
        assertEquals(lifted.value, LayoutMigrations.decode(MasalaJson.strict.parseToJsonElement(json)))
    }

    @Test
    fun `stripRefs drops a deleted widget from a mode patch`() {
        val patch = ModePatch(
            id = "m", name = "M", order = 1,
            addSections = listOf(
                AddSection("home", WidgetSection("w", ref = WidgetRef("gone"))),
                AddSection("home", WidgetSection("keep", ref = WidgetRef("here"))),
            ),
        )
        assertTrue(WidgetLift.hasInline(patch.copy(addSections = listOf(
            AddSection("home", WidgetSection("i", widget = WidgetSpec("i", components = emptyList()))),
        ))))
        assertFalse(WidgetLift.hasInline(patch))
        val stripped = assertNotNull(WidgetLift.stripRefs(patch, "gone"))
        assertEquals(listOf("keep"), stripped.addSections.map { it.section.id })
        assertNull(WidgetLift.stripRefs(patch, "missing"))
    }

    @Test
    fun `lifting reuses an identical library widget and renames a clashing one`() {
        val first = WidgetLift.lift(workday)
        val library = first.widgets.associateBy { it.id }
        val again = WidgetLift.lift(workday, library)
        assertEquals(emptyList(), again.widgets)
        assertEquals(first.value, again.value)

        val other = WidgetSpec("water", components = listOf(WidgetComponent.Text("not water")))
        val clash = WidgetLift.lift(workday, mapOf("water" to other, "water-2" to other))
        assertEquals(mapOf("water" to "water-3"), clash.renamed)
        assertEquals("water-3", clash.widgets.single { it.title == "Water" }.id)
        assertEquals(
            listOf("water-3", "steps"),
            clash.value.pages[0].sections.filterIsInstance<StackSection>().single().refs.map { it.widgetId },
        )
    }

    @Test
    fun `mode patches lift too`() {
        val mode = ModePatch(
            "m", "M",
            addSections = listOf(AddSection("today", WidgetSection("extra", WidgetSpec("x", components = listOf(WidgetComponent.Text("hi")))))),
        )
        val lifted = WidgetLift.lift(mode)
        assertEquals(listOf("x"), lifted.widgets.map { it.id })
        assertEquals(WidgetRef("x"), (lifted.value.addSections.single().section as WidgetSection).ref)
    }

    @Test
    fun `refs resolve from the library exactly like inline widgets`() {
        val lifted = WidgetLift.lift(workday)
        val library = lifted.widgets.associateBy { it.id }
        val ctx = at("09:30").copy(nextEvent = NextEvent("Standup", at("09:40").now))
        val inline = LayoutResolver.resolve(workday, emptyList(), ctx)
        val refs = LayoutResolver.resolve(lifted.value, emptyList(), ctx, library)
        assertEquals(inline.widgetRows, refs.widgetRows)
        assertEquals(emptyList(), refs.missingWidgets)
        val section = refs.layout.pages[0].sections.filterIsInstance<WidgetSection>().single()
        assertEquals(library["next-event"], section.widget) // drawn inline...
        assertEquals(WidgetRef("next-event"), section.ref) // ...and still knows its library id

        fun order(t: String) = LayoutResolver.resolve(lifted.value, emptyList(), at(t), library).layout
            .pages[0].sections.filterIsInstance<StackSection>().single()
        assertEquals(listOf("steps", "water"), order("18:00").widgets.map { it.id })
        assertEquals(listOf("steps", "water"), order("18:00").refs.map { it.widgetId })
    }

    @Test
    fun `a missing library widget drops its section and is reported`() {
        val doc = LayoutDoc(pages = listOf(Page("p", sections = listOf(WidgetSection("s", ref = WidgetRef("gone"))))))
        val r = LayoutResolver.resolve(doc, emptyList(), at("10:00"))
        assertEquals(emptyList(), r.layout.pages[0].sections)
        assertEquals(listOf("gone"), r.missingWidgets)
    }

    @Test
    fun `rowsOverride sets the default, inside the range, and a size rule still wins`() {
        val spec = WidgetSpec(
            "w",
            components = listOf(WidgetComponent.Text("x")),
            size = SizeRange(1, 1, 3),
            rules = listOf(
                com.samosaai.masala.core.contract.Rule(
                    com.samosaai.masala.core.contract.Condition.Charging(),
                    com.samosaai.masala.core.contract.Effect.Size(1),
                ),
            ),
        )
        fun rows(ref: WidgetRef, charging: Boolean) = LayoutResolver.resolve(
            LayoutDoc(pages = listOf(Page("p", sections = listOf(WidgetSection("s", ref = ref))))),
            emptyList(),
            at("10:00").copy(charging = charging),
            mapOf("w" to spec),
        ).widgetRows["s"]
        assertEquals(1, rows(WidgetRef("w"), charging = false))
        assertEquals(3, rows(WidgetRef("w", 3), charging = false))
        assertEquals(3, rows(WidgetRef("w", 9), charging = false))
        assertEquals(1, rows(WidgetRef("w", 3), charging = true))
    }

    @Test
    fun `the resolve cache follows the library version`() {
        val lifted = WidgetLift.lift(workday)
        val library = lifted.widgets.associateBy { it.id }
        val cache = ResolveCache()
        val ctx = at("10:00")
        cache.resolve(1, lifted.value, emptyList(), ctx, widgetsVersion = 1, widgets = library)
        cache.resolve(1, lifted.value, emptyList(), ctx, widgetsVersion = 1, widgets = library)
        assertEquals(1, cache.misses)
        val edited = library + ("water" to library.getValue("water").copy(title = "H2O"))
        val r = cache.resolve(1, lifted.value, emptyList(), ctx, widgetsVersion = 2, widgets = edited)
        assertEquals(2, cache.misses)
        assertEquals("H2O", r.layout.pages[0].sections.filterIsInstance<StackSection>().single().widgets.first { it.id == "water" }.title)
    }
}
