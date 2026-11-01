package com.samosaai.masala

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samosaai.masala.core.contract.WidgetSection
import com.samosaai.masala.core.layout.LayoutEdits
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.widgets.WidgetTools
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plan `docs/plans/widget-maker.md` W3 on the device: the tools create a widget from a spec,
 * place it on home, take it off, and delete it — through the real library and layout repository.
 * The sentence path (the model call) is covered without a model in `WidgetGeneratorTest`.
 */
@RunWith(AndroidJUnit4::class)
class WidgetToolsTest {
    private val app = ApplicationProvider.getApplicationContext<MasalaApp>()
    private val tools by lazy { WidgetTools.create(app).associateBy { it.definition.name } }

    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    private fun success(name: String, args: kotlinx.serialization.json.JsonObject): ToolResult.Success {
        val result = runBlocking { tools.getValue(name).execute(args) }
        assertTrue("$name failed: $result", result is ToolResult.Success)
        return result as ToolResult.Success
    }

    @Test
    fun widgetToolCycle() {
        val spec = """{"id":"cycle","title":"Cycle","components":[{"type":"counter","key":"n","label":"N"}],"size":{"min":1,"default":1,"max":2}}"""
        val created = success("create_widget", args("spec" to spec))
        assertEquals(1, created.produced.size)
        val id = created.produced.single().ref
        assertTrue(id, app.widgetLibrary.current().containsKey(id))

        success("place_widget", args("id" to id))
        val base = runBlocking { withTimeout(15_000) { app.layouts.base.first { it != null } } }!!
        assertTrue(id, LayoutEdits.widgetPlacements(base, id).isNotEmpty())

        // Placing twice doesn't duplicate: one placement, and the library still holds one widget.
        success("place_widget", args("id" to id))
        val same = runBlocking { withTimeout(15_000) { app.layouts.base.first { it != null } } }!!
        assertEquals(1, LayoutEdits.widgetPlacements(same, id).size)

        success("remove_widget", args("id" to id))
        val off = runBlocking { withTimeout(15_000) { app.layouts.base.first { it != null && LayoutEdits.widgetPlacements(it, id).isEmpty() } } }
        assertTrue(id, LayoutEdits.widgetPlacements(off!!, id).isEmpty())

        success("delete_widget", args("id" to id))
        assertTrue(id, !app.widgetLibrary.current().containsKey(id))
    }

    @Test
    fun widgetGateFootprint() {
        // Plan W3: the gate shows on placement, not on library writes. Making or revising a widget
        // only lands in the versioned library (the chat shows a preview card); home placement,
        // removal, deletion and new stream sources still ask.
        assertFalse(tools.getValue("create_widget").needsConfirmation(args("sentence" to "a water counter")))
        assertFalse(tools.getValue("update_widget").needsConfirmation(args("id" to "water")))
        assertTrue(tools.getValue("place_widget").needsConfirmation(args("id" to "water")))
        assertTrue(tools.getValue("remove_widget").needsConfirmation(args("id" to "water")))
        assertTrue(tools.getValue("delete_widget").needsConfirmation(args("id" to "water")))
        assertTrue(tools.getValue("add_stream_source").needsConfirmation(args("name" to "Garage", "url" to "rtsp://x")))
    }

    @Test
    fun listAndReadAndSchema() {
        val listed = success("list_widgets", args())
        assertTrue(listed.output.isNotBlank())
        val schema = success("widget_schema", args())
        assertTrue(schema.output.contains("counter"))
        // Unknown ids fail instead of inventing rows.
        val missing = runBlocking { tools.getValue("read_widget").execute(args("id" to "no-such-widget")) }
        assertTrue(missing is ToolResult.Failure)
        val bad = runBlocking { tools.getValue("create_widget").execute(args("spec" to """{"id":"x"}""")) }
        assertTrue(bad is ToolResult.Failure)
    }
}
