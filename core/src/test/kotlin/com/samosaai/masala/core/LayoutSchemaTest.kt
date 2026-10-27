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
}
