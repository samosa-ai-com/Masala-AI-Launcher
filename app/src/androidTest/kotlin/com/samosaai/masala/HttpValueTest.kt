package com.samosaai.masala

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samosaai.masala.core.contract.HttpBinding
import com.samosaai.masala.core.contract.SizeRange
import com.samosaai.masala.core.contract.WidgetComponent
import com.samosaai.masala.core.contract.WidgetSpec
import com.samosaai.masala.core.layout.LayoutEdits
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.widgets.HttpApproval
import com.samosaai.masala.widgets.HttpFetch
import com.samosaai.masala.widgets.WidgetTools
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plan `docs/plans/widget-maker.md` W5 on the device: a local JSON server on the dev machine
 * (10.0.2.2) feeds a web value through fetch, store, checker, approval and placement.
 */
@RunWith(AndroidJUnit4::class)
class HttpValueTest {
    private val app = ApplicationProvider.getApplicationContext<MasalaApp>()

    @Test
    fun webValueEndToEnd() = runBlocking {
        val http = HttpBinding("http://10.0.2.2:8092/price.json", "$.price", every = 30)
        assertEquals("42.5", HttpFetch.fetch(http.url, http.jsonPath))
        assertTrue(HttpFetch.fetch(http.url, "$.missing").isNullOrEmpty() ?: true)

        val spec = WidgetSpec(
            id = "lanprice",
            title = "Price",
            components = listOf(WidgetComponent.WebValue("Price", http, suffix = "€")),
            size = SizeRange(1, 1, 2),
        )
        assertTrue(app.layouts.environment().let { com.samosaai.masala.core.widgets.WidgetChecker.check(spec, it).isEmpty() })

        val pending = HttpApproval.needingApproval(spec, app.httpApproval.approved("lanprice"))
        assertEquals(listOf("10.0.2.2"), pending.map { it.host })
        assertTrue(HttpApproval.describe(pending).contains("10.0.2.2"))

        val tools = WidgetTools.create(app).associateBy { it.definition.name }
        fun call(name: String, vararg args: Pair<String, String>): ToolResult =
            runBlocking { tools.getValue(name).execute(buildJsonObject { args.forEach { (k, v) -> put(k, v) } }) }
        val specJson = com.samosaai.masala.core.contract.MasalaJson.strict.encodeToString(WidgetSpec.serializer(), spec)
        assertTrue(call("create_widget", "spec" to specJson) is ToolResult.Success)

        val refused = call("place_widget", "id" to "lanprice")
        assertTrue(refused is ToolResult.Failure && (refused as ToolResult.Failure).message.contains("10.0.2.2"))

        val placed = call("place_widget", "id" to "lanprice", "approved" to "true")
        assertTrue(placed is ToolResult.Success)
        assertTrue(app.httpApproval.approved("lanprice").contains("10.0.2.2"))
        val base = withTimeout(15_000) { app.layouts.base.first { it != null } }!!
        assertTrue(LayoutEdits.widgetPlacements(base, "lanprice").isNotEmpty())

        assertTrue(app.httpValues.refresh("lanprice", http))
        assertEquals("42.5", app.httpValues.entry("lanprice", http)?.value)

        assertTrue(call("remove_widget", "id" to "lanprice") is ToolResult.Success)
        assertTrue(call("delete_widget", "id" to "lanprice") is ToolResult.Success)
        assertTrue(app.httpValues.entry("lanprice", http) == null)
    }
}
