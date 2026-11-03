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

    /**
     * A coin flip or a dice roll, resolved on the card: the result is written to the widget's
     * storage under [key], so the card shows it instead of opening chat. [kind] is `coin` or
     * `dice`.
     */
    @Serializable
    @SerialName("flip")
    data class Flip(val key: String, val kind: String) : MasalaAction

    /**
     * Opens one page of Masala's settings (Daily brief for weather/place, Widgets for cameras and
     * web hosts, Permissions for calendar/health). A widget's "set this up" note uses it, so a card
     * that needs a permission can point at the exact screen.
     */
    @Serializable
    @SerialName("open_settings")
    data class OpenSettings(val page: String) : MasalaAction

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
    /**
     * Starts a meeting recording. The phone opens the meeting screen when the app is not in front:
     * Android 14+ refuses to start a microphone foreground service from a widget's background tap,
     * and that is the only place the microphone may be opened.
     */
    @Serializable
    @SerialName("meeting_start")
    data object MeetingStart : MasalaAction

    /** Pause a running meeting, or resume a paused one. One widget button, one decision. */
    @Serializable
    @SerialName("meeting_toggle")
    data object MeetingToggle : MasalaAction

    @Serializable
    @SerialName("meeting_stop")
    data object MeetingStop : MasalaAction

    /** Opens the meeting app, on [id] when one is named and on the list otherwise. */
    @Serializable
    @SerialName("open_meeting")
    data class OpenMeeting(val id: String? = null) : MasalaAction

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
    is MasalaAction.SetMode,
    MasalaAction.MeetingStart,
    MasalaAction.MeetingToggle,
    MasalaAction.MeetingStop -> ToolSensitivity.CHANGES_SOMETHING
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
    is MasalaAction.OpenSettings,
    is MasalaAction.Flip,
    is MasalaAction.OpenMeeting,
    is MasalaAction.OpenFolder -> ToolSensitivity.READ_ONLY
}
