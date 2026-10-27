package com.samosaai.masala.debug

import android.content.Context
import com.samosaai.masala.audio.AudioProvider
import com.samosaai.masala.audio.SpeechConfig
import com.samosaai.masala.audio.SttEngine
import com.samosaai.masala.audio.TtsEngine
import com.samosaai.masala.core.scheduled.ScheduledTask
import com.samosaai.masala.core.scheduled.ScheduledTaskPlanner
import com.samosaai.masala.core.scheduled.TaskRunStatus
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.i18n.Language
import com.samosaai.masala.masala
import com.samosaai.masala.scheduled.ScheduledStart
import com.samosaai.masala.scheduled.ScheduledTaskRuns
import com.samosaai.masala.scheduled.ScheduledTaskScheduler
import com.samosaai.masala.scheduled.TaskRunner
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Debug-only commands for lane B3, typed on the debug screen like a tool call (`/debug_tts {...}`):
 * skills, speech and scheduled tasks have no screen of their own until Phases 3 and 4.
 *
 * - `/debug_skills` lists them; `{"import": "<json>"}`, `{"importFile": "<name in files/>"}` and `{"remove": "<id>"}` use the community store.
 * - `/debug_tts {"text": "...", "language": "English"}` speaks through [TtsEngine] (Android voice).
 * - `/debug_listen {"provider": "android" | "api"}` listens once through [SttEngine].
 * - `/debug_schedule {"name": "...", "inMinutes": 2, "repeat": ...}` adds a one-time task and arms the alarm.
 * - `/debug_tasks` lists the tasks with their history; `{"fire": "<id>"}` runs one now,
 *   `{"delete": "<id>"}` removes it.
 *
 * Fired tasks go to [DebugRunner], which only writes them to the action log; Phase 4 replaces it.
 */
object DebugCommands {
    val names = setOf("debug_skills", "debug_tts", "debug_listen", "debug_schedule", "debug_tasks")

    private object DebugRunner : TaskRunner {
        override suspend fun start(task: ScheduledTask) = ScheduledStart.Started("debug-${System.currentTimeMillis()}")
    }

    suspend fun run(context: Context, name: String, args: JsonObject): ToolResult = try {
        val app = context.masala
        when (name) {
            "debug_skills" -> skills(context, args)
            "debug_tts" -> {
                val language = Language.fromLabel(args["language"]?.jsonPrimitive?.contentOrNull)
                val engine = TtsEngine(context)
                val ok = engine.speak(args["text"]?.jsonPrimitive?.content ?: "Hello from Masala", language = language)
                if (ok) ToolResult.Success("Spoke it in ${language.label}.")
                else ToolResult.Failure(engine.lastLanguageUnavailable?.let { "No installed ${it.label} voice." } ?: "Speech failed.")
            }
            "debug_listen" -> {
                val config = SpeechConfig.default
                val provider = if (args["provider"]?.jsonPrimitive?.contentOrNull == "api") AudioProvider.entries.first { it != AudioProvider.ANDROID } else AudioProvider.ANDROID
                val engine = SttEngine(context, config.baseUrl, config.apiKey)
                engine.listenForUtterance(provider, config.sttModel, silenceTimeoutMs = 1500)
                    .fold({ ToolResult.Success("Heard: $it") }, { ToolResult.Failure("${it.message} ${engine.androidFailure.orEmpty()}") })
            }
            "debug_schedule" -> {
                app.taskRunner = DebugRunner
                val zone = ZoneId.systemDefault()
                val now = System.currentTimeMillis()
                val minutes = args["inMinutes"]?.jsonPrimitive?.intOrNull ?: 2
                val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(now + minutes * 60_000L), zone).withSecond(0).withNano(0)
                val task = ScheduledTaskPlanner.armed(
                    ScheduledTask(
                        id = "debug-${now}", name = args["name"]?.jsonPrimitive?.contentOrNull ?: "Debug task",
                        prompt = "debug", startLocal = start.toString(),
                    ),
                    now, zone,
                )
                app.scheduledTasks.put(task)
                ScheduledTaskScheduler.reschedule(context)
                ToolResult.Success("Task ${task.id} due at ${Instant.ofEpochMilli(task.nextRunAt ?: 0)} (alarm armed).")
            }
            "debug_tasks" -> {
                args["delete"]?.jsonPrimitive?.contentOrNull?.let { app.scheduledTasks.delete(it); ScheduledTaskScheduler.reschedule(context) }
                args["fire"]?.jsonPrimitive?.contentOrNull?.let { app.taskRunner = DebugRunner; ScheduledTaskRuns(app).runNow(it) }
                val tasks = app.scheduledTasks.all()
                ToolResult.Success(
                    tasks.joinToString("\n").ifEmpty { "(no tasks)" } + "\nstatuses: " + TaskRunStatus.entries.joinToString(),
                )
            }
            else -> ToolResult.Failure("Unknown debug command $name")
        }
    } catch (e: Exception) {
        ToolResult.Failure("${e::class.simpleName}: ${e.message}")
    }

    private suspend fun skills(context: Context, args: JsonObject): ToolResult {
        val registry = context.masala.skills
        args["import"]?.jsonPrimitive?.contentOrNull?.let { registry.importCommunity(it) }
        // Typing JSON through adb is fragile: `adb shell run-as ... tee files/x.json`, then {"importFile":"x.json"}.
        args["importFile"]?.jsonPrimitive?.contentOrNull?.let { registry.importCommunity(java.io.File(context.filesDir, it).readText()) }
        args["remove"]?.jsonPrimitive?.contentOrNull?.let { registry.removeCommunity(it) }
        return ToolResult.Success(
            registry.getAllSkills().joinToString("\n") {
                "${it.id}${if (it.persona != null) " [persona, ${it.persona!!.starters.size} starters]" else ""}" +
                    (if (it in registry.getCommunitySkills()) " (community)" else "")
            }
        )
    }
}
