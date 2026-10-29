package com.samosaai.masala

import android.Manifest
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.permissions.CapabilityPrompt
import com.samosaai.masala.tools.ActionLog
import com.samosaai.masala.tools.CalendarTool
import com.samosaai.masala.tools.MasalaToolDefinitions
import com.samosaai.masala.tools.MasalaToolExecutor
import com.samosaai.masala.tools.MasalaToolRegistry
import com.samosaai.masala.tools.ToolGate
import com.samosaai.masala.files.AppStorageFileAccess
import com.samosaai.masala.core.tool.FunctionTool
import com.samosaai.masala.core.tool.ToolSensitivity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The failure the phone showed: writing an event reads the calendar list first, which needs
 * READ_CALENDAR — a different permission from WRITE_CALENDAR. Both must be asked for, and the
 * call must then succeed on its own.
 */
@RunWith(AndroidJUnit4::class)
class CalendarCreateTest {
    private val app = ApplicationProvider.getApplicationContext<MasalaApp>()
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val created = mutableListOf<Long>()

    @After
    fun cleanUp() {
        // Never leave test events in the user's calendar.
        created.forEach { id ->
            runCatching {
                app.contentResolver.delete(
                    CalendarContract.Events.CONTENT_URI, "${CalendarContract.Events._ID} = ?", arrayOf(id.toString()),
                )
            }
        }
    }

    private fun executor(asked: MutableList<String>): MasalaToolExecutor {
        val registry = MasalaToolRegistry(AppStorageFileAccess(app))
        val calendar = CalendarTool(app)
        registry.register(
            FunctionTool(MasalaToolDefinitions.createCalendarEvent, ToolSensitivity.CHANGES_SOMETHING) {
                calendar.createEvent(
                    title = it["title"].toString().trim('"'),
                    start = it["start"].toString().trim('"'),
                )
            },
        )
        val prompt = CapabilityPrompt { marker ->
            asked += marker
            // Stands in for the user tapping Allow.
            automation.grantRuntimePermission(app.packageName, marker)
            true
        }
        return MasalaToolExecutor(app, registry, ActionLog(app), ToolGate { _, _ -> null }, prompt)
    }

    @Test
    fun `creating an event succeeds once read and write are held`() = runBlocking {
        // Emulator images have no calendar account; nothing to write to there.
        Automation.assumeCalendar(app)

        // Revoking a runtime permission kills this process, so grant instead of revoking: the bug
        // was that only WRITE was ever asked for, and the list query then failed on READ.
        automation.grantRuntimePermission(app.packageName, Manifest.permission.READ_CALENDAR)
        automation.grantRuntimePermission(app.packageName, Manifest.permission.WRITE_CALENDAR)
        val asked = mutableListOf<String>()
        val args = buildJsonObject {
            put("title", "Masala test event")
            put("start", "2026-11-02T09:00")
        }
        val result = executor(asked).execute("create_calendar_event", args)
        assertTrue("expected success, got $result (asked=$asked)", result is ToolResult.Success)
        assertTrue("nothing to ask for, but asked: $asked", asked.isEmpty())

        // Find it, so the cleanup above can remove exactly this event.
        app.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.TITLE} = ?",
            arrayOf("Masala test event"),
            null,
        )?.use { c -> while (c.moveToNext()) created += c.getLong(0) }
        assertTrue("the event is not in the calendar", created.isNotEmpty())
    }
}