package com.samosaai.masala.layout

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayoutSchemaTest {

    @Test
    fun `schema is strict-valid`() {
        val violations = LayoutSchema.strictViolations(LayoutSchema.schema(setOf("call", "home")))
        assertEquals(emptyList(), violations, violations.joinToString("\n"))
    }

    @Test
    fun `symbol enum carries the catalog`() {
        val text = LayoutSchema.schema(setOf("call", "home")).toString()
        assertTrue(text.contains("\"call\"") && text.contains("\"home\""))
    }

    @Test
    fun `sections and actions are unions, root is an object`() {
        val schema = LayoutSchema.schema(setOf("call"))
        val sectionTypes = sectionVariants(schema)
        assertTrue(sectionTypes.containsAll(listOf("header", "grid", "list", "dock", "group", "skill_widget", "widget", "stack", "html")))
    }

    @Test
    fun `every property value is a schema object with a type`() {
        val problems = mutableListOf<String>()
        fun check(node: JsonObject, path: String) {
            val props = (node["properties"] as? JsonObject) ?: return
            props.forEach { (name, child) ->
                if (child !is JsonObject) {
                    problems += "$path.properties.$name is not an object"
                    return@forEach
                }
                if ((child["type"] as? JsonPrimitive)?.content == null) {
                    problems += "$path.properties.$name has no type"
                }
                check(child, "$path.properties.$name")
            }
            ((node["items"] as? JsonObject))?.let { check(it, "$path.items") }
            (node["anyOf"] as? JsonArray)?.forEachIndexed { i, variant ->
                if (variant is JsonObject) check(variant, "$path.anyOf[$i]")
            }
        }
        check(LayoutSchema.schema(setOf("call", "home")), "$")
        assertEquals(emptyList(), problems, problems.joinToString("\n"))
    }

    @Test
    fun `item lists are array schemas, not bare arrays`() {
        val schema = LayoutSchema.schema(setOf("call"))
        val grid = sectionVariantsByKind(schema)["grid"]!!
        val gridProps = (grid["properties"] as JsonObject)
        val items = gridProps["items"] as JsonObject
        assertEquals("array", (items["type"] as JsonPrimitive).content)
        assertTrue((items["items"] as JsonObject).containsKey("properties"))
    }

    private fun sectionVariants(schema: JsonObject): Set<String> = sectionVariantsByKind(schema).keys

    private fun sectionVariantsByKind(schema: JsonObject): Map<String, JsonObject> {
        val pages = (schema["properties"] as JsonObject)["pages"] as JsonObject
        val sections = (((pages["items"] as JsonObject)["properties"] as JsonObject)["sections"] as JsonObject)
        val items = (sections["items"] as JsonObject)["anyOf"] as JsonArray
        return items.mapNotNull { variant ->
            val obj = variant as JsonObject
            val props = (obj["properties"] as JsonObject)
            val kind = ((props["type"] as JsonObject)["const"] as? JsonPrimitive)?.content
            if (kind == null) null else kind to obj
        }.toMap()
    }
}
