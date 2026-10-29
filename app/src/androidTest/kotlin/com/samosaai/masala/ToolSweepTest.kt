package com.samosaai.masala

import android.Manifest
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.provider.AlarmClock
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs every registered tool once with minimal valid arguments and prints one machine-readable
 * line per tool (`SWEEP|status|name|detail`), so a whole sweep can be reviewed without a phone.
 *
 * A tool that cannot work on this device — no clock app, no dialer, no Health Connect — is
 * reported as SKIP with that reason, never as a failure. Tools that depend on another tool's
 * output (the mini-app set, the file set) run as a sequence.
 *
 * The result of this test is its output; it asserts only that every tool was accounted for.
 */
@RunWith(AndroidJUnit4::class)
class ToolSweepTest {
    private val app = ApplicationProvider.getApplicationContext<MasalaApp>()
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation

    private val results = linkedMapOf<String, Pair<String, String>>()
    private val reported = mutableSetOf<String>()

    /** Tools that need hardware, another app or a network key this image doesn't have. */
    private fun skipReason(name: String): String? {
        val clock = canHandle(Intent(AlarmClock.ACTION_SET_ALARM))
        val dialer = canHandle(Intent(Intent.ACTION_DIAL))
        return when (name) {
            "media_convert", "media_edit" -> "needs Termux + ffmpeg"
            "transcribe_file" -> "needs an audio file on the device; covered by ToolFollowupsTest"
            "get_health_summary", "get_health_records" -> "needs Health Connect"
            "uninstall_app" -> "would remove an app; tested on a phone"
            "set_wallpaper" -> "changes the wallpaper; tested on a phone"
            "pdf_edit" -> "needs an input PDF; the file tools cover the storage path"
            "create_calendar_event" -> if (hasWritableCalendar()) null else "no calendar account on this image"
            "media_control" -> if (mediaSessionLikely()) null else "no media session on this image"
            "set_alarm", "set_timer", "show_alarms", "snooze_alarm",
            "dismiss_timer", "dismiss_alarm", "next_alarm" -> if (clock) null else "no clock app on this image"
            "dial_number" -> if (dialer) null else "no dialer app on this image"
            "toggle_wifi" -> if (canHandle(Intent("android.settings.panel.action.WIFI"))) null
                else "no Wi-Fi panel on this image"
            "read_notifications", "dismiss_notifications", "media_control", "get_now_playing" ->
                if (listenerEnabled()) null else "notification access can't be granted on this image"
            else -> null
        }
    }

    private fun hasWritableCalendar(): Boolean = runCatching {
        app.contentResolver.query(
            android.provider.CalendarContract.Calendars.CONTENT_URI,
            arrayOf(android.provider.CalendarContract.Calendars._ID), null, null, null,
        )?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    /** A loaded image has no music app running, so media controls can only be refused there. */
    private fun mediaSessionLikely(): Boolean = false

    private fun canHandle(intent: Intent): Boolean =
        app.packageManager.resolveActivity(intent, 0) != null

    private fun listenerEnabled(): Boolean = runCatching {
        android.app.NotificationManager::class.java.let {
            androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(app)
                .contains(app.packageName)
        }
    }.getOrDefault(false)

    private fun record(name: String, status: String, detail: String) {
        results[name] = status to detail
        reported += name
    }

    private fun shell(command: String) {
        runCatching {
            automation.executeShellCommand(command).use { pfd: ParcelFileDescriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(pfd).readBytes()
            }
        }
    }

    private fun grantEnvironment() {
        listOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ).forEach { runCatching { automation.grantRuntimePermission(app.packageName, it) } }
        shell("appops set ${app.packageName} GET_USAGE_STATS allow")
        shell("appops set ${app.packageName} MANAGE_EXTERNAL_STORAGE allow")
        shell("cmd notification allow_listener ${app.packageName}/com.samosaai.masala.notifications.MasalaNotificationListener")
    }

    private fun executor(): MasalaToolExecutor {
        val prompt = CapabilityPrompt { marker ->
            runCatching { automation.grantRuntimePermission(app.packageName, marker) }.isSuccess
        }
        return MasalaToolExecutor(app, app.tools, ActionLog(app), ToolGate { _, _ -> null }, prompt)
    }

    private fun args(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)

    private suspend fun run(
        executor: MasalaToolExecutor,
        name: String,
        arguments: JsonObject = JsonObject(emptyMap()),
    ): ToolResult {
        val skip = skipReason(name)
        if (skip != null) {
            record(name, "SKIP", skip)
            return ToolResult.Success("skipped")
        }
        val result = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(45_000) { executor.execute(name, arguments) }
                ?: ToolResult.Failure("timed out after 45000ms")
        }.getOrElse { e -> ToolResult.Failure("${e::class.simpleName}: ${e.message}") }
        val (status, detail) = when (result) {
            is ToolResult.Success -> "OK" to result.output.replace('\n', ' ').take(150)
            is ToolResult.Failure -> "FAIL" to result.message.replace('\n', ' ').take(150)
            is ToolResult.NeedsCapability -> "NEEDS" to result.capability
        }
        record(name, status, detail)
        return result
    }

    @Test
    fun sweepEveryTool() = runBlocking {
        grantEnvironment()
        val executor = executor()

        // Storage sequence: write, read back, search inside, list.
        val file = "/sdcard/Download/masala-sweep.txt"
        run(executor, "write_file", args {
            put("path", file)
            put("content", "masala sweep content 42")
        })
        run(executor, "read_file", args { put("path", file) })
        run(executor, "grep", args {
            put("path", "/sdcard/Download")
            put("pattern", "sweep content")
        })
        run(executor, "glob", args {
            put("path", "/sdcard/Download")
            put("pattern", "masala-*.txt")
        })

        // Mini-app sequence: create, then use the id it returns for everything else.
        val created = run(executor, "create_mini_app", args {
            put("title", "Sweep app")
            put("html", "<!doctype html><html><body><h1>sweep</h1></body></html>")
        })
        val id = Regex("'([0-9a-f]{8})'").find((created as? ToolResult.Success)?.output.orEmpty())?.groupValues?.get(1)
        if (id == null) {
            listOf("update_mini_app", "open_mini_app", "pin_mini_app", "delete_mini_app").forEach {
                record(it, "FAIL", "no id from create_mini_app: ${(created as? ToolResult.Failure)?.message}")
            }
        } else {
            run(executor, "update_mini_app", args {
                put("id", id)
                put("html", "<!doctype html><html><body><h1>sweep v2</h1></body></html>")
            })
            run(executor, "open_mini_app", args { put("id", id) })
            run(executor, "pin_mini_app", args { put("id", id) })
            run(executor, "delete_mini_app", args { put("id", id) })
        }

        // Everything else, once each.
        val all = app.tools.tools.map { it.definition.name }.sorted()
        val recipes = mapOf(
            "dial_number" to args { put("number", "+15550100100") },
            "open_app" to args { put("package_name", "com.android.stk") },
            "list_calendar_events" to args { put("days_ahead", 7) },
            "create_calendar_event" to args {
                put("title", "Masala sweep event")
                put("start", "2026-11-02T09:00")
            },
            "search_contacts" to args { put("query", "a") },
            "set_alarm" to args {
                put("hour", 7)
                put("minute", 30)
            },
            "set_timer" to args { put("seconds", 60) },
            "toggle_torch" to args { put("on", true) },
            "set_volume" to args {
                put("stream", "music")
                put("percent", 50)
            },
            "get_volume" to args { put("stream", "music") },
            "set_ringer_mode" to args { put("mode", "normal") },
            "vibrate" to args { put("duration_ms", 200) },
            "set_dnd" to args { put("enabled", false) },
            "get_app_usage" to args { put("days", 1) },
            "set_clipboard" to args { put("text", "masala sweep") },
            "websearch" to args { put("query", "android launcher") },
            "webfetch" to args { put("url", "https://example.com") },
            "toggle_wifi" to args { put("enabled", true) },
            "open_url" to args { put("url", "https://example.com") },
            "read_notifications" to args { put("limit", 5) },
            "media_control" to args { put("action", "play") },
            "list_files" to args { put("path", "/sdcard/Download") },
        )
        // Recording is a sequence too: status, start, pause, resume, stop.
        run(executor, "get_audio_recording_status")
        run(executor, "start_audio_recording", args {
            put("max_duration_seconds", 5)
        })
        run(executor, "get_audio_recording_status")
        run(executor, "pause_audio_recording")
        run(executor, "resume_audio_recording")
        run(executor, "stop_audio_recording")

        for (name in all) {
            if (name in reported) continue
            run(executor, name, recipes[name] ?: JsonObject(emptyMap()))
        }

        println("SWEEP-BEGIN tools=${all.size}")
        all.forEach { name ->
            val (status, detail) = results[name] ?: ("MISSING" to "never ran")
            println("SWEEP|$status|$name|$detail")
        }
        println(
            "SWEEP-END ok=${results.count { it.value.first == "OK" }} " +
                "fail=${results.count { it.value.first == "FAIL" }} " +
                "needs=${results.count { it.value.first == "NEEDS" }} " +
                "skip=${results.count { it.value.first == "SKIP" }}",
        )

        assertTrue("sweep did not cover every tool", results.keys.containsAll(all))
    }
}
