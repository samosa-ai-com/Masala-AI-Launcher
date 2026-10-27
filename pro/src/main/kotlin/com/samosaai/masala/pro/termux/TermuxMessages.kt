package com.samosaai.masala.pro.termux

import com.samosaai.masala.core.tool.ActionResult

/**
 * From the private prototype's `TermuxMessages`: what the model is told when a Termux command cannot run, or ran but
 * told us little. Every one is the model's only clue about a device state it cannot otherwise see.
 * Reworded where the private prototype's pointed at tools Masala doesn't have (`run_command`, tap, global_action,
 * sleep, `search_skills('termux_*')`) and trimmed of the package-manager cases.
 */
internal object TermuxMessages {

    fun notInstalled() = ActionResult.error(
        "Termux is not installed, so there is no Linux user-space to run this in. Ask the user to install " +
            "Termux from F-Droid (the Play Store build is unmaintained), with its ffmpeg package."
    )

    /**
     * Termux is installed but has no RunCommandService. In practice this means the Google Play build,
     * which removes the plugin API wholesale to satisfy Play policy: no amount of permission granting
     * or property setting will make it work.
     */
    fun pluginApiMissing(versionName: String?) = ActionResult.error(
        "Termux is installed (version ${versionName ?: "unknown"}) but this build has no RUN_COMMAND " +
            "service, so it cannot run commands for other apps. The Google Play build of Termux removes " +
            "that API. Tell the user to replace it with the F-Droid or GitHub build of Termux if they want " +
            "this; nothing can be granted to fix the Play build. Do not retry."
    )

    fun permissionNeeded() = ActionResult.permissionNeeded(
        TermuxBridge.TERMUX_ACCESS,
        "Converting files needs Termux's \"Run commands\" permission, which has not been granted. " +
            "I have asked for it. Please allow it, then in Termux run these two lines, and ask again." +
            externalAppsFix() +
            "\n(If no permission dialog appeared, Termux was installed after Masala: reinstall or update " +
            "Masala so Android can grant it.)"
    )

    /**
     * The commands that set `allow-external-apps`, as a fenced block with the instruction to keep them
     * in one when passing them on: the user types them into Termux, and a fenced block is what the chat
     * shows with a Copy button (the private prototype issue #109).
     */
    fun externalAppsFix(): String =
        "\n\nThe user runs this in Termux:\n\n```bash\n${TermuxBridge.SETUP_COMMANDS}\n```\n\n" +
            "When you tell the user, give them exactly this fenced block, not the commands inline."

    /**
     * The start itself was refused. The one expected cause: from Android 12 an app in the background
     * may not start a foreground service, and Termux's RunCommandService is one
     * (ForegroundServiceStartNotAllowedException).
     */
    fun startFailed(cause: Throwable) = ActionResult.error(
        "Could not reach Termux's RUN_COMMAND service: ${cause.message}. If that says Masala is in the " +
            "background, Android is refusing the start: from Android 12 a backgrounded app cannot launch " +
            "another app's foreground service. Ask the user to bring Masala to the foreground and try " +
            "again. Otherwise Termux may have been disabled or force-stopped."
    )

    /**
     * `startService` returning null rather than throwing. Distinct from [startFailed]: nothing refused
     * us, the service simply was not there to start, usually Termux force-stopped, or uninstalled
     * between the availability probe and the call.
     */
    fun serviceDidNotStart() = ActionResult.error(
        "Termux did not start the command: its RUN_COMMAND service could not be reached. Termux has most " +
            "likely been force-stopped (by the user or a battery manager), or was uninstalled just now. Ask " +
            "the user to open Termux once so it is running again, then retry. No command was started, so " +
            "nothing is pending."
    )

    fun tooManyInFlight(limit: Int) = ActionResult.error(
        "$limit Termux commands are already running and none has finished yet. Earlier commands keep running " +
            "even after they time out. Wait for them to finish rather than starting another; if they are " +
            "stuck, ask the user to tap Exit on the Termux notification, which ends every Termux session."
    )

    /**
     * A timeout means the command was accepted and is simply taking too long (a *rejected* command comes
     * back promptly with an errno instead). Notably `allow-external-apps` being unset is **not** a cause
     * here: on-device it returns ERRNO_FAILED with an explanatory message within a second.
     */
    fun timedOut(command: String, timeout: Int) = ActionResult.error(
        "No result from Termux after ${timeout}s for: $command. Termux accepted the command, so it is " +
            "running; it is just not finished. It keeps running either way, so check before starting " +
            "again. A conversion of a long file can need more time than this tool allows: trim the input " +
            "first. To stop a stuck one, ask the user to tap Exit on the Termux notification."
    )

    fun emptyCommand() = ActionResult.error("Empty command.")

    fun tooLarge(total: Int, limitChars: Int) = ActionResult.error(
        "Command plus stdin is $total characters, over Termux's ~${limitChars / 1024}KB limit for a single request."
    )

    /** Termux's errno is not a plain "0 means fine" code, so spell it out. */
    fun errnoLabel(err: Int): String = when (err) {
        TermuxBridge.ERRNO_CANCELLED -> "cancelled"
        TermuxBridge.ERRNO_MINOR_FAILURES -> "completed with minor failures"
        TermuxBridge.ERRNO_FAILED -> "failed"
        else -> "error code $err"
    }
}
