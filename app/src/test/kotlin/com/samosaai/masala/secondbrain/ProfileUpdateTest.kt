package com.samosaai.masala.secondbrain

import com.samosaai.masala.agent.MemoryTools
import com.samosaai.masala.core.tool.name
import com.samosaai.masala.data.WikiDao
import com.samosaai.masala.data.WikiPageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `update_user_profile`: caps, replace-not-append, and clearing, over a real store in a temp dir. */
class ProfileUpdateTest {
    private class FakeDao : WikiDao {
        val rows = MutableStateFlow<List<WikiPageEntity>>(emptyList())
        override fun all() = rows.map { r -> r.sortedBy { it.title.lowercase() } }
        override suspend fun get(id: String) = rows.value.firstOrNull { it.id == id }
        override suspend fun upsert(page: WikiPageEntity) { rows.value = rows.value.filterNot { it.id == page.id } + page }
        override suspend fun delete(id: String) { rows.value = rows.value.filterNot { it.id == id } }
    }

    private lateinit var brain: SecondBrainRepository

    private fun fresh() {
        val dir = Files.createTempDirectory("sb-profile").toFile()
        brain = SecondBrainRepository(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            SecondBrainStore(dir, today = { "2026-10-29" }),
            FakeDao(),
            audit = { _, _ -> },
        )
        runBlocking { brain.ensure() }
    }

    private fun update(occupation: String? = null, background: String? = null) = runBlocking {
        val tool = MemoryTools.create(brain).first { it.name == "update_user_profile" }
        tool.execute(buildJsonObject {
            occupation?.let { put("occupation", JsonPrimitive(it)) }
            background?.let { put("background", JsonPrimitive(it)) }
        })
    }

    @Test fun `occupation and background land in the profile`() = runBlocking {
        fresh()
        val result = update(occupation = "nurse on night shifts", background = "early riser")
        assertTrue(result is com.samosaai.masala.core.tool.ToolResult.Success, result.toString())
        val profile = brain.profile()
        assertEquals("nurse on night shifts", profile["occupation"])
        assertEquals("early riser", profile["background"])
    }

    @Test fun `values replace and empty clears one field only`() = runBlocking {
        fresh()
        update(occupation = "nurse", background = "loves tea")
        update(occupation = "doctor")
        val profile = brain.profile()
        assertEquals("doctor", profile["occupation"])
        assertEquals("loves tea", profile["background"])
        update(background = "")
        val cleared = brain.profile()
        assertEquals("doctor", cleared["occupation"])
        assertEquals(null, cleared["background"])
    }

    @Test fun `word caps are enforced`() = runBlocking {
        fresh()
        val long = List(101) { "word" }.joinToString(" ")
        val result = update(occupation = long)
        assertTrue(result is com.samosaai.masala.core.tool.ToolResult.Failure)
        assertTrue(brain.profile()["occupation"] == null)
    }

    @Test fun `with no fields the tool says what to pass`() {
        fresh()
        val tool = MemoryTools.create(brain).first { it.name == "update_user_profile" }
        val result: com.samosaai.masala.core.tool.ToolResult = runBlocking { tool.execute(JsonObject(emptyMap())) }
        assertTrue(result is com.samosaai.masala.core.tool.ToolResult.Failure)
    }

    @Test fun `profile updates ask first`() {
        fresh()
        val tool = MemoryTools.create(brain).first { it.name == "update_user_profile" }
        val args = Json.parseToJsonElement("""{"occupation":"nurse"}""") as JsonObject
        assertTrue(tool.needsConfirmation(args))
        assertFalse(MemoryTools.create(brain).first { it.name == "memory_read" }.needsConfirmation(args))
    }
}
