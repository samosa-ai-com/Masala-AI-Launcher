package com.samosaai.masala.pro.termux

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.samosaai.masala.core.tool.ActionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * From the private prototype's `TermuxTool`: the part that runs one command in Termux through its official
 * RUN_COMMAND plugin API and collects stdout, stderr and the exit code. Left out: the `run_termux_command`
 * tool itself (Masala has no general shell tool, arch §3.1), the deny-list for commands (the only caller
 * builds its command from a fixed table, see [com.samosaai.masala.pro.media.FfmpegCommand]), the
 * package-manager handling (non-interactive frontend, wake lock, lock-contention messages) and the
 * guided-setup state.
 *
 * Stock Android has no usable user-space. Termux ships one, but it runs under its own uid with its own
 * rootfs at `/data/data/com.termux/files`, so Masala can neither exec its binaries nor read its files.
 * What Termux does expose is `com.termux.app.RunCommandService`: we send it a command, it runs it in the
 * background (no terminal pop-up) and returns stdout/stderr/exit code through a [PendingIntent].
 *
 * Two things must be true before that works:
 *  1. `com.termux.permission.RUN_COMMAND` is granted. It is a **dangerous** permission that Termux itself
 *     declares, so listing it in the manifest is not enough; it needs a runtime grant, raised through
 *     [TERMUX_ACCESS]. A custom dangerous permission whose defining app was installed *after* Masala may
 *     not be grantable until Masala is updated or reinstalled.
 *  2. `allow-external-apps=true` is set in Termux's `~/.termux/termux.properties`. That file is in
 *     Termux's private storage so we can't read it ahead of time, but we don't need to: running without
 *     it comes back promptly as `err = ERRNO_FAILED` with an `errmsg` naming the property, which
 *     [formatResult] passes on.
 */
class TermuxBridge(
    private val context: Context,
    private val defaultTimeoutSeconds: Int = 120,
    private val maxOutputBytes: Int = 32 * 1024,
) {

    /** What the model needs to know about Termux. */
    data class TermuxStatus(
        val installed: Boolean,
        /**
         * Whether this Termux build actually exposes `RunCommandService`. The Google Play build strips
         * the whole plugin API, so it declares no `RUN_COMMAND` permission and registers no services at
         * all. Without this check we would ask for a permission the device cannot define, and the user
         * would face a dialog that never appears.
         */
        val pluginApiAvailable: Boolean,
        val permissionGranted: Boolean,
        val versionName: String?,
    ) {
        /** Termux can actually run something for us (modulo the grant). */
        val usable: Boolean get() = installed && pluginApiAvailable
    }

    fun status(): TermuxStatus {
        val info = runCatching { context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0) }.getOrNull()
        val granted = runCatching {
            ContextCompat.checkSelfPermission(context, PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return TermuxStatus(
            installed = info != null,
            pluginApiAvailable = info != null && runCatching { runCommandServiceExists() }.getOrDefault(false),
            permissionGranted = granted,
            versionName = info?.versionName,
        )
    }

    /**
     * Termux's rootfs, taken from the installed package rather than assumed. `/data/data/com.termux` is
     * only the primary user's path; under a secondary user or a work profile the same package lives at
     * `/data/user/<id>/com.termux`, where the hardcoded path names a `sh` that does not exist.
     */
    internal fun termuxFiles(): String {
        val dataDir = runCatching { context.packageManager.getApplicationInfo(TERMUX_PACKAGE, 0).dataDir }.getOrNull()
        return if (dataDir.isNullOrBlank()) DEFAULT_TERMUX_FILES else "$dataDir/files"
    }

    /** Termux's `$HOME`, the default working directory for a command. */
    internal fun termuxHome(): String = "${termuxFiles()}/home"

    /** Whether `com.termux.RUN_COMMAND` resolves to a service: false on the Play build. */
    private fun runCommandServiceExists(): Boolean =
        context.packageManager.resolveService(Intent(ACTION_RUN_COMMAND).setPackage(TERMUX_PACKAGE), 0) != null

    /** Run [command] through Termux's `sh`, returning stdout/stderr/exit code. */
    suspend fun runCommand(
        command: String,
        workingDir: String? = null,
        timeoutSeconds: Int? = null,
        stdin: String? = null,
    ): ActionResult {
        val trimmed = command.trim()
        validate(trimmed, stdin)?.let { return it }

        val status = status()
        if (!status.installed) return TermuxMessages.notInstalled()
        if (!status.pluginApiAvailable) return TermuxMessages.pluginApiMissing(status.versionName)
        if (!status.permissionGranted) return TermuxMessages.permissionNeeded()

        val timeout = (timeoutSeconds ?: defaultTimeoutSeconds).coerceIn(1, MAX_TIMEOUT_SECONDS)
        // A timed-out command is not cancelled: it keeps running under Termux's uid, out of our reach.
        // Without a ceiling, retries of a slow command would leave a growing pile of live processes.
        if (!inFlight.tryAcquire()) return TermuxMessages.tooManyInFlight(MAX_CONCURRENT_COMMANDS)
        val requestCode = nextRequestCode()
        val deferred = CompletableDeferred<Bundle>()
        pendingResults[requestCode] = deferred
        try {
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                TermuxResultReceiver.resultIntent(context, requestCode),
                pendingIntentFlags(),
            )
            // startForegroundService, not startService. Termux targets SDK 28, so a plain startService
            // into it is refused ("app is in background uid null") whenever Termux has no process, however
            // much Masala is on screen. RunCommandService calls startForeground() as it starts, which is
            // what this call needs; Masala may make it while visible.
            val started = runCatching {
                context.startForegroundService(commandIntent(trimmed, workingDir, stdin, pendingIntent))
            }
            started.exceptionOrNull()?.let { return TermuxMessages.startFailed(it) }
            // A start reports "no such service" by returning null, not by throwing. Android also excludes
            // force-stopped packages from intent resolution. Waiting on the deferred would burn the whole
            // timeout and then claim the command was accepted and still running.
            if (started.getOrNull() == null) return TermuxMessages.serviceDidNotStart()

            val bundle = withTimeoutOrNull(timeout * 1000L) { deferred.await() }
                ?: return TermuxMessages.timedOut(trimmed, timeout)
            return formatResult(bundle)
        } finally {
            pendingResults.remove(requestCode)
            inFlight.release()
        }
    }

    /** Turn Termux's result bundle into an [ActionResult]. Split out so it is testable without Termux. */
    internal fun formatResult(bundle: Bundle): ActionResult {
        val err = bundle.numeric(RESULT_ERR) ?: ERRNO_SUCCESS
        val errmsg = bundle.getString(RESULT_ERRMSG)?.trim().orEmpty()
        if (err != ERRNO_SUCCESS) {
            return ActionResult.error(
                "Termux refused or could not run the command (${TermuxMessages.errnoLabel(err)})" +
                    (if (errmsg.isEmpty()) "." else ": ${cap(errmsg)}") +
                    if ("allow-external-apps" in errmsg) TermuxMessages.externalAppsFix() else ""
            )
        }
        val exit = bundle.numeric(RESULT_EXIT_CODE) ?: -1
        val stdout = bundle.getString(RESULT_STDOUT).orEmpty()
        val stderr = bundle.getString(RESULT_STDERR).orEmpty()
        val message = buildString {
            append("exit code: $exit")
            if (stdout.isNotEmpty()) append("\nstdout:\n${cap(stdout)}")
            if (stderr.isNotEmpty()) append("\nstderr:\n${cap(stderr)}")
            if (stdout.isEmpty() && stderr.isEmpty()) append("\n(no output)")
            if (wasTruncatedByTermux(bundle, RESULT_STDOUT_ORIGINAL_LENGTH, stdout) ||
                wasTruncatedByTermux(bundle, RESULT_STDERR_ORIGINAL_LENGTH, stderr)
            ) {
                append("\n…(Termux truncated the output at its ~100KB result limit)")
            }
            if (errmsg.isNotEmpty()) append("\nTermux note: ${cap(errmsg)}")
        }
        return ActionResult(success = exit == 0, message = message)
    }

    /** Cheap checks that need neither Termux nor a permission, so they report the real problem first. */
    private fun validate(trimmed: String, stdin: String?): ActionResult? {
        if (trimmed.isEmpty()) return TermuxMessages.emptyCommand()
        // Command and stdin ride in the same binder transaction, so they share the budget.
        val total = trimmed.length + (stdin?.length ?: 0)
        if (total > MAX_COMMAND_CHARS) return TermuxMessages.tooLarge(total, MAX_COMMAND_CHARS)
        return null
    }

    /** Builds the intent; [result] is null when only the extras are wanted (tests). */
    internal fun commandIntent(command: String, workingDir: String?, stdin: String?, result: PendingIntent?): Intent =
        Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, "${termuxFiles()}/usr/bin/sh")
            putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
            putExtra(EXTRA_WORKDIR, workingDir?.trim()?.takeIf { it.isNotEmpty() } ?: termuxHome())
            // Headless: run without opening a terminal session in front of the user.
            putExtra(EXTRA_BACKGROUND, true)
            // Without this, anything that reads stdin blocks until our timeout and reports a hang rather
            // than the prompt it is actually stuck on. Termux 0.109+.
            if (stdin != null) putExtra(EXTRA_STDIN, stdin)
            if (result != null) putExtra(EXTRA_PENDING_INTENT, result)
        }

    /**
     * Termux writes the result bundle into the intent it sends back, so the PendingIntent has to be
     * mutable. It is explicit (it names our own receiver), so this is not the implicit-mutable pattern
     * lint warns about.
     */
    private fun pendingIntentFlags(): Int {
        var flags = PendingIntent.FLAG_ONE_SHOT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        return flags
    }

    private fun cap(text: String): String =
        if (text.length <= maxOutputBytes) text else text.take(maxOutputBytes) + "\n…(output capped)"

    private fun wasTruncatedByTermux(bundle: Bundle, lengthKey: String, received: String): Boolean =
        (bundle.numeric(lengthKey) ?: received.length) > received.length

    /**
     * Termux sends lengths as strings and codes as ints; tolerate either for every numeric field. Reads
     * the raw value rather than probing with `getString`/`getInt`, because a typed getter on the wrong
     * type logs a `ClassCastException` stack trace to logcat on every call.
     */
    @Suppress("DEPRECATION")
    private fun Bundle.numeric(key: String): Int? = when (val value = get(key)) {
        is Int -> value
        is Long -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"

        /** The [ActionResult] marker for the RUN_COMMAND grant; it is requested like a runtime permission. */
        const val TERMUX_ACCESS = PERMISSION_RUN_COMMAND

        /** Play-store-free source for Termux. The Play build strips the RUN_COMMAND plugin API. */
        const val TERMUX_FDROID_URL = "https://f-droid.org/en/packages/com.termux/"

        /** The two lines the user runs in Termux to let other apps send it commands. */
        const val SETUP_COMMANDS =
            "echo 'allow-external-apps=true' >> ~/.termux/termux.properties\n" +
                "termux-reload-settings"

        private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        internal const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        internal const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        internal const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        internal const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
        internal const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        /**
         * Only correct for the primary Android user. A secondary user or work profile puts the same
         * package at `/data/user/<id>/com.termux`, so this is a last-resort fallback: [termuxFiles] asks
         * the package manager for the real path.
         */
        private const val DEFAULT_TERMUX_FILES = "/data/data/com.termux/files"

        // Result-bundle keys, from termux-shared's TermuxConstants.
        internal const val RESULT_BUNDLE = "result"
        internal const val RESULT_STDOUT = "stdout"
        internal const val RESULT_STDOUT_ORIGINAL_LENGTH = "stdout_original_length"
        internal const val RESULT_STDERR = "stderr"
        internal const val RESULT_STDERR_ORIGINAL_LENGTH = "stderr_original_length"
        internal const val RESULT_EXIT_CODE = "exitCode"
        internal const val RESULT_ERR = "err"
        internal const val RESULT_ERRMSG = "errmsg"

        /**
         * Termux's `Errno` codes are built on `android.app.Activity`'s result constants, so success is
         * **-1** (`RESULT_OK`) and zero means *cancelled*: the opposite of the usual "0 is fine" shell
         * convention. Verified on-device by the private prototype: a successful `uname -a` comes back with `err = -1`.
         */
        internal const val ERRNO_SUCCESS = -1 // Activity.RESULT_OK
        internal const val ERRNO_CANCELLED = 0 // Activity.RESULT_CANCELED
        internal const val ERRNO_MINOR_FAILURES = 1 // Activity.RESULT_FIRST_USER
        internal const val ERRNO_FAILED = 2 // Activity.RESULT_FIRST_USER + 1

        /** Termux caps a single command near 128KB; leave headroom for the rest of the intent. */
        private const val MAX_COMMAND_CHARS = 100 * 1024

        private const val MAX_TIMEOUT_SECONDS = 600

        /**
         * Ceiling on commands running at once. Process-wide rather than per instance: Termux is a single
         * shared resource regardless of who asked.
         */
        private const val MAX_CONCURRENT_COMMANDS = 4

        private val inFlight = Semaphore(MAX_CONCURRENT_COMMANDS)

        /**
         * Seeded per process, not from 1. A PendingIntent outlives us: if Masala dies while Termux still
         * holds an unfired one, the next process starting from 1 would ask for the same request code, and
         * `getBroadcast` matches on request code and intent (extras are not part of the identity), so it
         * would hand back the *stale* PendingIntent and Termux would fire the old command's result into
         * the new command's deferred. A random start makes the two code spaces disjoint in practice.
         * Masked to stay non-negative, which the receiver relies on.
         */
        private val nextRequestCode = AtomicInteger(Random.nextInt(1, 1 shl 20))

        private fun nextRequestCode(): Int = nextRequestCode.getAndIncrement() and Int.MAX_VALUE

        /**
         * In-flight commands, keyed by PendingIntent request code so concurrent calls cannot collide.
         * Held in memory only: if Masala's process dies the awaiting coroutine dies with it.
         */
        private val pendingResults = ConcurrentHashMap<Int, CompletableDeferred<Bundle>>()

        /** Hands a result from [TermuxResultReceiver] to the coroutine waiting on it. */
        internal fun completeResult(requestCode: Int, bundle: Bundle) {
            pendingResults.remove(requestCode)?.complete(bundle)
        }
    }
}
