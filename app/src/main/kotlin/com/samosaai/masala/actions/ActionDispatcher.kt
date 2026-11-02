package com.samosaai.masala.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.samosaai.masala.core.apps.AppEntry
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.sensitivity
import com.samosaai.masala.core.tool.ToolRegistry
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.core.tool.ToolSensitivity
import com.samosaai.masala.launcher.AppActions
import com.samosaai.masala.launcher.LauncherModel
import com.samosaai.masala.layout.ModeStore
import com.samosaai.masala.miniapps.openMiniApp
import com.samosaai.masala.skills.SkillRegistry
import com.samosaai.masala.tools.MasalaToolExecutor
import com.samosaai.masala.ui.skills.SkillSheetActivity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Turns a [MasalaAction] into what it does (arch §3.6, plan Phase 2): an intent, a tool call or a skill
 * run. Taps, gestures, widget buttons and (later) the agent all come through here. Whether to ask first
 * is decided per tool, not per action type: a tool-backed action goes to the executor, whose gate asks
 * when the tool isn't read-only; an action with no tool (`SetMode`) asks the [gate] itself.
 */
class ActionDispatcher(
    private val context: Context,
    private val actions: AppActions,
    private val launcher: LauncherModel,
    private val modes: ModeStore,
    private val gate: ConfirmationGate,
    private val registry: ToolRegistry,
    private val skills: () -> SkillRegistry,
    private val executor: () -> MasalaToolExecutor,
    /**
     * Reloads one widget's approved web values in place; null where widgets don't live (tests and
     * headless callers). False means unknown, unapproved or unreachable — the tap reports it.
     */
    private val refreshWidget: (suspend (widgetId: String) -> Boolean)? = null,
) {
    /** What the screen has to do for actions that are about the screen. */
    interface Screen {
        fun openDrawer(query: String? = null)
        fun ask(prompt: String)
    }

    sealed interface Outcome {
        data class Done(val message: String? = null) : Outcome
        data object Declined : Outcome
        data class Failed(val message: String) : Outcome

        /** A tool stopped because a permission is missing; the caller can ask for it. */
        data class NeedsCapability(val marker: String, val reason: String) : Outcome
    }

    suspend fun dispatch(action: MasalaAction, screen: Screen): Outcome = try {
        run(action, screen)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Action failed: $action", e)
        Outcome.Failed(e.message ?: e::class.java.simpleName)
    }

    private suspend fun run(action: MasalaAction, screen: Screen): Outcome = when (action) {
        is MasalaAction.LaunchApp -> {
            val entry = findApp(action.packageName, action.activity, action.user)
            if (entry != null && actions.launch(entry.key)) Outcome.Done() else Outcome.Failed("Can't open ${action.packageName}")
        }
        is MasalaAction.LaunchShortcut ->
            if (actions.startShortcut(action.packageName, action.shortcutId, action.user)) Outcome.Done() else Outcome.Failed("Can't open that shortcut")
        is MasalaAction.OpenUrl -> start(Intent(Intent.ACTION_VIEW, Uri.parse(action.url)))
        is MasalaAction.ComposeMessage -> start(
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${action.to.orEmpty()}")).apply {
                action.body?.let { putExtra("sms_body", it) }
                action.packageName?.let { setPackage(it) }
            },
        )
        is MasalaAction.Dial -> tool("dial_number", buildJsonObject { put("number", action.number) })
        is MasalaAction.SetAlarm -> tool(
            "set_alarm",
            buildJsonObject {
                put("hour", action.hour)
                put("minute", action.minute)
                action.label?.let { put("message", it) }
            },
        )
        is MasalaAction.StartTimer -> tool(
            "set_timer",
            buildJsonObject {
                put("seconds", action.seconds)
                action.label?.let { put("message", it) }
            },
        )
        MasalaAction.OpenDrawer -> Outcome.Done().also { screen.openDrawer() }
        is MasalaAction.Search -> Outcome.Done().also { screen.openDrawer(action.query) }
        MasalaAction.ExpandNotifications -> expandNotifications()
        is MasalaAction.AskMasala -> Outcome.Done().also { screen.ask(action.prompt) }
        is MasalaAction.SetMode -> setMode(action)
        is MasalaAction.RunTool -> tool(action.name, action.args)
        is MasalaAction.OpenMiniApp -> openMiniApp(action)
        is MasalaAction.OpenFolder -> Outcome.Failed("Folders open on the home screen")
        is MasalaAction.RunSkill ->
            if (skills().getSkillById(action.skillId) == null) Outcome.Failed("There is no skill '${action.skillId}'")
            else start(SkillSheetActivity.intent(context, action.skillId, action.prompt))
        is MasalaAction.RefreshWidget -> {
            val refresh = refreshWidget ?: return Outcome.Failed("Nothing to refresh here")
            if (action.widgetId.isBlank()) Outcome.Failed("That button doesn't name a widget")
            else if (refresh(action.widgetId)) Outcome.Done()
            else Outcome.Failed("Couldn't refresh that widget yet")
        }
        is MasalaAction.PlaySound -> playClick(action)
    }

    private fun playClick(action: MasalaAction.PlaySound): Outcome {
        if (action.kind != "click") return Outcome.Failed("Unknown sound '${action.kind}'")
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: return Outcome.Failed("No audio on this device")
        audio.playSoundEffect(android.media.AudioManager.FX_KEY_CLICK)
        return Outcome.Done()
    }

    private suspend fun setMode(action: MasalaAction.SetMode): Outcome {
        val level = action.sensitivity { registry[it]?.sensitivity }
        if (level != ToolSensitivity.READ_ONLY) {
            val verb = if (action.on) "Turn on" else "Turn off"
            when (gate.confirm("$verb the mode “${action.id}”", subject = "set_mode")) {
                ConfirmationGate.Decision.ALLOWED -> Unit
                else -> return Outcome.Declined
            }
        }
        modes.setActive(action.id, action.on)
        return Outcome.Done()
    }

    private suspend fun tool(name: String, args: JsonObject): Outcome = when (val r = executor().execute(name, args)) {
        is ToolResult.Success -> Outcome.Done(r.output)
        is ToolResult.NeedsCapability -> Outcome.NeedsCapability(r.capability, r.reason)
        is ToolResult.Failure -> if (r.message.startsWith("The user declined")) Outcome.Declined else Outcome.Failed(r.message)
    }

    private fun openMiniApp(action: MasalaAction.OpenMiniApp): Outcome {
        // Existence is checked in the activity, which shows a "gone" state for unknown ids.
        context.openMiniApp(action.id)
        return Outcome.Done()
    }

    private fun start(intent: Intent): Outcome = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Outcome.Done()
    } catch (e: android.content.ActivityNotFoundException) {
        Outcome.Failed("No app can open that")
    }

    /** `StatusBarManager.expandNotificationsPanel`, which needs `EXPAND_STATUS_BAR` and isn't in the SDK. */
    private fun expandNotifications(): Outcome = try {
        val bar = context.getSystemService("statusbar")
        bar.javaClass.getMethod("expandNotificationsPanel").invoke(bar)
        Outcome.Done()
    } catch (e: Exception) {
        Log.w(TAG, "Can't expand notifications", e)
        Outcome.Failed("Can't open the notification shade on this phone")
    }

    private fun findApp(packageName: String, activity: String?, user: Long): AppEntry? =
        launcher.apps.value.firstOrNull {
            it.key.packageName == packageName && it.key.user == user && (activity == null || it.key.activity == activity)
        }

    private companion object {
        const val TAG = "ActionDispatcher"
    }
}
