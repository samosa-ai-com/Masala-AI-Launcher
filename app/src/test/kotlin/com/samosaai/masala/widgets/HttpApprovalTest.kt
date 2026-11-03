package com.samosaai.masala.widgets

import com.samosaai.masala.core.contract.Binding
import com.samosaai.masala.core.contract.BindingSource
import com.samosaai.masala.core.contract.HttpBinding
import com.samosaai.masala.core.contract.SizeRange
import com.samosaai.masala.core.contract.WidgetComponent
import com.samosaai.masala.core.contract.WidgetSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Host approval without Android: which fetches a spec needs, and what stays approved. */
class HttpApprovalTest {

    private fun spec() = WidgetSpec(
        id = "price",
        components = listOf(
            WidgetComponent.WebValue("Price", HttpBinding("https://api.example.com/p", "$.price", every = 900, background = true)),
            WidgetComponent.Row(
                listOf(
                    WidgetComponent.Text(binding = Binding(BindingSource.HTTP, http = HttpBinding("https://api.example.com/q", "$.q"))),
                    WidgetComponent.Counter("n", "N"),
                ),
            ),
        ),
        size = SizeRange(1, 1, 2),
    )

    @Test
    fun fetchesWalkRowsAndColumns() {
        val fetches = HttpApproval.fetchesOf(spec())
        assertEquals(listOf("api.example.com", "api.example.com"), fetches.map { it.host })
        assertTrue(fetches.first().background)
    }

    @Test
    fun approvalCoversHostsOnce() {
        val spec = spec()
        assertEquals(1, HttpApproval.needingApproval(spec, emptySet()).distinctBy { it.host }.size)
        assertTrue(HttpApproval.needingApproval(spec, setOf("api.example.com")).isEmpty())
        // A changed host asks again.
        val moved = spec.copy(
            components = listOf(WidgetComponent.WebValue("Price", HttpBinding("https://other.example/p", "$"))),
        )
        assertEquals(listOf("other.example"), HttpApproval.needingApproval(moved, setOf("api.example.com")).map { it.host })
    }

    @Test
    fun describeNamesHostAndCadence() {
        val line = HttpApproval.describe(HttpApproval.fetchesOf(spec()).take(1))
        assertTrue(line.contains("api.example.com") && line.contains("900"), line)
    }

    @Test
    fun hostsParse() {
        assertEquals("api.example.com", HttpApproval.hostOf("https://API.example.com:8080/p?q=1"))
        assertEquals(null, HttpApproval.hostOf("not a url"))
    }
}
