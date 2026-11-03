package com.samosaai.masala

import android.Manifest
import android.os.ParcelFileDescriptor
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.permissions.CapabilityPrompt
import com.samosaai.masala.tools.ActionLog
import com.samosaai.masala.tools.MasalaToolExecutor
import com.samosaai.masala.tools.ToolGate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Batch C on the real phone: calendar edit/delete and scheduled-task CRUD round-trips, plus a
 * profile write that restores what it found. Read-only Batch C tools ride the general sweep
 * (ToolSweepTest). Anything created here is deleted before the test ends.
 */
@RunWith(AndroidJUnit4::class)
class BatchCToolsTest {
    private val app = ApplicationProvider.getApplicationContext<MasalaApp>()
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation

    private val calendarTitles = mutableListOf("Masala batch-C event", "Masala batch-C event renamed")
    private var taskId: String? = null

    @After
    fun cleanUp() {
        runBlocking {
            runCatching {
                val store = app.scheduledTasks
                taskId?.let { store.delete(it) }
                com.samosaai.masala.scheduled.ScheduledTaskScheduler.reschedule(app)
            }
            runCatching {
                calendarTitles.forEach { title ->
                    app.contentResolver.delete(
                        CalendarContract.Events.CONTENT_URI,
                        "${CalendarContract.Events.TITLE} = ?",
                        arrayOf(title),
                    )
                }
            }
        }
    }

    private fun executor(): MasalaToolExecutor {
        listOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ).forEach { runCatching { automation.grantRuntimePermission(app.packageName, it) } }
        val prompt = CapabilityPrompt { marker ->
            runCatching { automation.grantRuntimePermission(app.packageName, marker) }.isSuccess
        }
        return MasalaToolExecutor(app, app.tools, ActionLog(app), ToolGate { _, _ -> null }, prompt)
    }

    private suspend fun run(name: String, arguments: JsonObject = JsonObject(emptyMap())): ToolResult =
        kotlinx.coroutines.withTimeoutOrNull(45_000) { executor().execute(name, arguments) }
            ?: ToolResult.Failure("timed out")

    private fun eventId(title: String): Long? =
        app.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.TITLE} = ?",
            arrayOf(title),
            null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    @Test
    fun calendarCreateEditDeleteRoundTrip() = runBlocking {
        Automation.assumeCalendar(app)

        val created = run("create_calendar_event", buildJsonObject {
            put("title", "Masala batch-C event")
            put("start", "2026-11-05T09:00")
            put("end", "2026-11-05T10:00")
            put("recurrence", "daily")
            put("recurrence_count", 3)
            put("reminder_minutes", 30)
            put("attendees", "sweep@example.com")
        })
        assertTrue("create failed: $created", created is ToolResult.Success)
        val id = eventId("Masala batch-C event")
        assertTrue("event not in provider after create", id != null)

        val edited = run("edit_calendar_event", buildJsonObject {
            put("event_id", id!!)
            put("title", "Masala batch-C event renamed")
            put("clear_reminder", true)
        })
        assertTrue("edit failed: $edited", edited is ToolResult.Success)
        assertTrue("renamed event not found", eventId("Masala batch-C event renamed") == id)

        val deleted = run("delete_calendar_event", buildJsonObject { put("event_id", id) })
        assertTrue("delete failed: $deleted", deleted is ToolResult.Success)
        assertTrue("event still present after delete", eventId("Masala batch-C event renamed") == null)
    }

    @Test
    fun scheduledTaskScheduleEditDeleteRoundTrip() = runBlocking {
        val scheduled = run("schedule_agent_task", buildJsonObject {
            put("name", "Masala batch-C task")
            put("prompt", "Say hello")
            put("start", "2099-06-01T08:00")
        })
        assertTrue("schedule failed: $scheduled", scheduled is ToolResult.Success)

        val listed = run("list_scheduled_tasks")
        assertTrue("list failed: $listed", listed is ToolResult.Success)
        val id = Regex("\\[([0-9a-f\\-]{8,})\\]").find((listed as ToolResult.Success).output)?.groupValues?.get(1)
        assertTrue("task id not in list: ${listed.output}", id != null)
        taskId = id

        val edited = run("edit_scheduled_task", buildJsonObject {
            put("task_id", id!!)
            put("name", "Masala batch-C task renamed")
            put("enabled", false)
        })
        assertTrue("edit failed: $edited", edited is ToolResult.Success)
        val relisted = run("list_scheduled_tasks") as ToolResult.Success
        assertTrue("renamed task not listed: ${relisted.output}", "Masala batch-C task renamed" in relisted.output)

        val deleted = run("delete_scheduled_task", buildJsonObject { put("task_id", id) })
        assertTrue("delete failed: $deleted", deleted is ToolResult.Success)
        taskId = null
        val gone = run("list_scheduled_tasks") as ToolResult.Success
        assertTrue("task still listed: ${gone.output}", "batch-C" !in gone.output)
    }

    @Test
    fun profileUpdateRestoresWhatItFound() = runBlocking {
        val before = app.secondBrain.profile()
        val hadOccupation = before["occupation"]

        val updated = run("update_user_profile", buildJsonObject {
            put("occupation", "Masala batch-C tester")
        })
        assertTrue("update failed: $updated", updated is ToolResult.Success)
        assertTrue("profile not updated", app.secondBrain.profile()["occupation"] == "Masala batch-C tester")

        // Restore exactly what was there (empty clears the field back).
        run("update_user_profile", buildJsonObject {
            put("occupation", hadOccupation.orEmpty())
        })
        assertTrue("profile not restored", app.secondBrain.profile()["occupation"] == hadOccupation)
    }

    @Test
    fun searchSkillsFindsBundledHelpers() = runBlocking {
        val result = run("search_skills", buildJsonObject { put("query", "chef") })
        assertTrue("search failed: $result", result is ToolResult.Success)
        assertTrue("chef skill missing: ${(result as ToolResult.Success).output}", "chef" in result.output)
    }

    @Test
    fun llmCacheSurvivesARoundTripThroughDisk() {
        // The JVM tests cover the memory path; only the phone exercises the disk entries.
        val cache = com.samosaai.masala.llm.LLMCache(app)
        val response = com.samosaai.masala.llm.ChatResponse(
            choices = listOf(
                com.samosaai.masala.llm.Choice(
                    com.samosaai.masala.llm.ChatMessage(
                        "assistant",
                        kotlinx.serialization.json.JsonPrimitive("cached hi"),
                    ),
                    "stop",
                ),
            ),
        )
        cache.put("batch-c-probe", response)
        // A fresh instance reads what the first one wrote.
        val reread = com.samosaai.masala.llm.LLMCache(app).get("batch-c-probe")
        assertTrue("disk cache miss", reread?.choices?.singleOrNull()?.message?.textContent == "cached hi")
        cache.clear()
        assertTrue(
            "clear did not wipe disk",
            com.samosaai.masala.llm.LLMCache(app).get("batch-c-probe") == null,
        )
    }
}
