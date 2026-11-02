package com.samosaai.masala.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.samosaai.masala.core.contract.MasalaJson
import kotlin.test.Test
import kotlin.test.assertTrue

/** The hand-kept schema (sent to the model) and the Kotlin classes must accept the same files. */
class LayoutSchemaTest {
    private val mapper = ObjectMapper()
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    private val schemaJson = mapper.readTree(Fixtures.text("layout.schema.json")) as ObjectNode
    private val layoutSchema: JsonSchema = factory.getSchema(schemaJson)

    /** The same `$defs` with `ModePatch` as the root. */
    private val modeSchema: JsonSchema = factory.getSchema(
        schemaJson.deepCopy().apply {
            listOf("properties", "required", "additionalProperties", "type").forEach { remove(it) }
            put("\$ref", "#/\$defs/ModePatch")
        },
    )

    @Test
    fun `layout examples pass the schema`() {
        for (name in Fixtures.layoutExamples) {
            val errors = layoutSchema.validate(mapper.readTree(Fixtures.text("examples/$name.json")))
            assertTrue(errors.isEmpty(), "$name: $errors")
        }
    }

    @Test
    fun `mode examples pass the schema`() {
        for (name in Fixtures.modeExamples) {
            val errors = modeSchema.validate(mapper.readTree(Fixtures.text("examples/$name.json")))
            assertTrue(errors.isEmpty(), "$name: $errors")
        }
    }

    @Test
    fun `what Kotlin writes passes the schema`() {
        for (name in Fixtures.layoutExamples) {
            val written = MasalaJson.strict.encodeToString(Fixtures.layout(name))
            val errors = layoutSchema.validate(mapper.readTree(written))
            assertTrue(errors.isEmpty(), "$name: $errors")
        }
        for (name in Fixtures.modeExamples) {
            val written = MasalaJson.strict.encodeToString(Fixtures.mode(name))
            val errors = modeSchema.validate(mapper.readTree(written))
            assertTrue(errors.isEmpty(), "$name: $errors")
        }
    }

    /** The same `$defs` with `WidgetSpec` as the root: what the widget library stores. */
    private val widgetSchema: JsonSchema = factory.getSchema(
        schemaJson.deepCopy().apply {
            listOf("properties", "required", "additionalProperties", "type").forEach { remove(it) }
            put("\$ref", "#/\$defs/WidgetSpec")
        },
    )

    @Test
    fun `widget templates and what Kotlin writes for them pass the schema`() {
        for (template in com.samosaai.masala.core.widgets.WidgetTemplates.all()) {
            val raw = mapper.readTree(com.samosaai.masala.core.widgets.WidgetTemplates.text(template.id)).get("widget")
            assertTrue(widgetSchema.validate(raw).isEmpty(), "${template.id}: ${widgetSchema.validate(raw)}")
            val written = MasalaJson.strict.encodeToString(template.widget)
            assertTrue(widgetSchema.validate(mapper.readTree(written)).isEmpty(), template.id)
        }
    }

    @Test
    fun `schema 3 sections take a ref or an inline widget, not both`() {
        val ref = """{"schemaVersion":3,"pages":[{"id":"p","sections":[{"type":"widget","id":"w","ref":{"widgetId":"water","rowsOverride":2}},
            {"type":"stack","id":"s","refs":[{"widgetId":"a"},{"widgetId":"b"}]}]}]}"""
        assertTrue(layoutSchema.validate(mapper.readTree(ref)).isEmpty(), layoutSchema.validate(mapper.readTree(ref)).toString())
        val both = """{"pages":[{"id":"p","sections":[{"type":"widget","id":"w","ref":{"widgetId":"x"},
            "widget":{"id":"x","components":[{"type":"text","text":"hi"}]}}]}]}"""
        val neither = """{"pages":[{"id":"p","sections":[{"type":"widget","id":"w"}]}]}"""
        for (json in listOf(both, neither)) assertTrue(layoutSchema.validate(mapper.readTree(json)).isNotEmpty(), json)
    }

    @Test
    fun `schema rejects unknown fields and types`() {
        val unknownField = """{"pages":[{"id":"p","sections":[{"type":"header","id":"h","colour":"red"}]}]}"""
        val unknownType = """{"pages":[{"id":"p","sections":[{"type":"carousel","id":"c"}]}]}"""
        val badTime = """{"pages":[{"id":"p","sections":[{"type":"header","id":"h","rules":[
            {"condition":{"type":"time_window","start":"25:00","end":"07:00"},"effect":{"type":"hide"}}]}]}]}"""
        for (json in listOf(unknownField, unknownType, badTime)) {
            assertTrue(layoutSchema.validate(mapper.readTree(json)).isNotEmpty(), json)
        }
    }

    @Test
    fun `a cell grid Kotlin writes passes the schema`() {
        val item = com.samosaai.masala.core.contract.Item("i", "Mail", com.samosaai.masala.core.contract.IconRef.Symbol("mail"), com.samosaai.masala.core.contract.MasalaAction.OpenDrawer)
        val grid = com.samosaai.masala.core.contract.CellGridSection(
            "g",
            cells = listOf(
                com.samosaai.masala.core.contract.Cell("a", 0, 0, 2, 2, com.samosaai.masala.core.contract.CellContent.WidgetCell(com.samosaai.masala.core.contract.WidgetRef("water"))),
                com.samosaai.masala.core.contract.Cell("b", 2, 0, content = com.samosaai.masala.core.contract.CellContent.ItemCell(item)),
                com.samosaai.masala.core.contract.Cell("c", 3, 0, content = com.samosaai.masala.core.contract.CellContent.HtmlCell("dice")),
            ),
        )
        val doc = com.samosaai.masala.core.contract.LayoutDoc(pages = listOf(com.samosaai.masala.core.contract.Page("home", sections = listOf(grid))))
        val json = com.samosaai.masala.core.contract.MasalaJson.strict.encodeToString(com.samosaai.masala.core.contract.LayoutDoc.serializer(), doc)
        val errors = layoutSchema.validate(mapper.readTree(json))
        assertTrue(errors.isEmpty(), "$errors")
    }
}
