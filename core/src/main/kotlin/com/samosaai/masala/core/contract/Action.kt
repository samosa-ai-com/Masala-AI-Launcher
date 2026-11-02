package com.samosaai.masala.core.contract

import com.samosaai.masala.core.tool.ToolSensitivity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Everything a tap, gesture, widget button, mode or voice command can do (arch §3.6).
 * Masala never sends, posts or calls on its own: [Dial] and [ComposeMessage] only prefill.
 */
@Serializable
sealed interface MasalaAction {
    @Serializable
    @SerialName("launch_app")
    data class LaunchApp(
        val packageName: String,
        val activity: String? = null,
        val user: Long = 0,
    ) : MasalaAction

    @Serializable
    @SerialName("launch_shortcut")
    data class LaunchShortcut(
        val packageName: String,
        val shortcutId: String,
        val user: Long = 0,
    ) : MasalaAction

    @Serializable
    @SerialName("open_url")
    data class OpenUrl(val url: String) : MasalaAction

    /** Opens the dialer with the number filled in. The user places the call. */
    @Serializable
    @SerialName("dial")
    data class Dial(val number: String) : MasalaAction

    /** Opens a messaging app with the text filled in. The user sends it. */
    @Serializable
    @SerialName("compose_message")
    data class ComposeMessage(
        val to: String? = null,
        val body: String? = null,
        val packageName: String? = null,
    ) : MasalaAction

    @Serializable
    @SerialName("set_alarm")
    data class SetAlarm(val hour: Int, val minute: Int, val label: String? = null) : MasalaAction

    @Serializable
    @SerialName("start_timer")
    data class StartTimer(val seconds: Int, val label: String? = null) : MasalaAction

    @Serializable
    @SerialName("open_drawer")
    data object OpenDrawer : MasalaAction

    @Serializable
    @SerialName("expand_notifications")
    data object ExpandNotifications : MasalaAction

    @Serializable
    @SerialName("search")
    data class Search(val query: String? = null) : MasalaAction

    @Serializable
    @SerialName("ask_masala")
    data class AskMasala(val prompt: String) : MasalaAction

    @Serializable
    @SerialName("set_mode")
    data class SetMode(val id: String, val on: Boolean) : MasalaAction

    @Serializable
    @SerialName("run_skill")
    data class RunSkill(val skillId: String, val prompt: String? = null) : MasalaAction

    /**
     * Reloads the widget's own web values in place. The phone fills in a blank [widgetId] with the
     * widget holding the button; only hosts the user already approved are fetched, so it never asks.
     */
    @Serializable
    @SerialName("refresh_widget")
    data class RefreshWidget(val widgetId: String = "") : MasalaAction

    /** Click feedback on a widget button. Only "click" exists; unknown kinds are rejected. */
    @Serializable
    @SerialName("play_sound")
    data class PlaySound(val kind: String = "click") : MasalaAction

    @Serializable
    @SerialName("open_mini_app")
    data class OpenMiniApp(val id: String) : MasalaAction

    @Serializable
    @SerialName("run_tool")
    data class RunTool(val name: String, val args: JsonObject = JsonObject(emptyMap())) : MasalaAction

    /** A folder on home: tapping it shows [items]. Opened by the home screen, never dispatched. */
    @Serializable
    @SerialName("open_folder")
    data class OpenFolder(val items: List<Item>) : MasalaAction
}

/**
 * Whether this action must go through the confirmation gate, decided per tool, not per action type
 * (arch §3.6). [toolSensitivity] looks a tool up in the registry; an unknown tool counts as
 * [ToolSensitivity.DESTRUCTIVE] so it is never run unconfirmed.
 *
 * [MasalaAction.RunSkill] only starts an agent run; each tool call inside that run is gated on its own.
 */
fun MasalaAction.sensitivity(toolSensitivity: (String) -> ToolSensitivity?): ToolSensitivity = when (this) {
    is MasalaAction.RunTool -> toolSensitivity(name) ?: ToolSensitivity.DESTRUCTIVE
    is MasalaAction.SetAlarm,
    is MasalaAction.StartTimer,
    is MasalaAction.SetMode -> ToolSensitivity.CHANGES_SOMETHING
    is MasalaAction.LaunchApp,
    is MasalaAction.LaunchShortcut,
    is MasalaAction.OpenUrl,
    is MasalaAction.Dial,
    is MasalaAction.ComposeMessage,
    MasalaAction.OpenDrawer,
    MasalaAction.ExpandNotifications,
    is MasalaAction.Search,
    is MasalaAction.AskMasala,
    is MasalaAction.RunSkill,
    is MasalaAction.RefreshWidget,
    is MasalaAction.PlaySound,
    is MasalaAction.OpenMiniApp,
    is MasalaAction.OpenFolder -> ToolSensitivity.READ_ONLY
}
