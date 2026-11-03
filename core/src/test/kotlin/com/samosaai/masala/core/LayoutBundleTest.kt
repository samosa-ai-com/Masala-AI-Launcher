package com.samosaai.masala.core

import com.samosaai.masala.core.apps.Visibility
import com.samosaai.masala.core.layout.LayoutBundle
import com.samosaai.masala.core.layout.LayoutBundle.AppOverrideExport
import com.samosaai.masala.core.layout.LayoutChecker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoutBundleTest {
    private val classic = Fixtures.layout("classic")
    private val apps = Regex("""packageName=([\w.]+)""").findAll(classic.toString()).map { it.groupValues[1] }.toSet()
    private val env = LayoutChecker.Environment(apps.associateWith { setOf(0L) }, skillIds = emptySet())

    @Test
    fun `a bundle round trips with its overrides`() {
        val overrides = listOf(AppOverrideExport("com.a/.Main#0", "Mail", Visibility.SEARCH_ONLY, "symbol:email"))
        val imported = LayoutBundle.import(LayoutBundle.of(classic, overrides).encode(), env)
        assertEquals(classic, imported.layout)
        assertEquals(overrides, imported.appOverrides)
        assertEquals(emptyList(), imported.problems)
    }

    @Test
    fun `an override without an icon still imports`() {
        // Bundles written before icons existed carry no icon field.
        val withIcon = Json.parseToJsonElement(
            LayoutBundle.of(classic, listOf(AppOverrideExport("com.a/.Main#0", "Mail", Visibility.SEARCH_ONLY, "symbol:email"))).encode(),
        ).jsonObject
        val old = JsonObject(
            withIcon + mapOf("appOverrides" to JsonArray(withIcon["appOverrides"]!!.jsonArray.map { JsonObject(it.jsonObject - "icon") })),
        ).toString()
        val imported = LayoutBundle.import(old, env)
        assertEquals(listOf(AppOverrideExport("com.a/.Main#0", "Mail", Visibility.SEARCH_ONLY, null)), imported.appOverrides)
    }

    @Test
    fun `a bare layout file imports too`() {
        val imported = LayoutBundle.import(Fixtures.text("examples/classic.json"), env)
        assertEquals(classic, imported.layout)
        assertEquals(emptyList(), imported.overridesOrEmpty())
    }

    @Test
    fun `apps this phone does not have come back as problems`() {
        val other = env.copy(installedApps = emptyMap())
        val imported = LayoutBundle.import(LayoutBundle.of(classic, emptyList()).encode(), other)
        assertTrue(imported.problems.isNotEmpty())
        assertTrue(imported.problems.all { "not installed" in it.message })
    }

    @Test
    fun `a broken file gives a path and no layout`() {
        val imported = LayoutBundle.import("""{"bundleVersion":1,"layout":{"schemaVersion":1,"pages":"x"}}""", env)
        assertNull(imported.layout)
        assertTrue(imported.problems.single().path.startsWith("$.pages"), imported.problems.toString())
    }

    private fun LayoutBundle.Companion.Imported.overridesOrEmpty() = appOverrides
}
