package com.samosaai.masala.pro.media

import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.pro.termux.TermuxBridge
import com.samosaai.masala.core.tool.ToolFiles
import android.os.Environment
import java.io.File

/**
 * From the private prototype's `MediaConvertTool`: takes [ToolFiles] and a [TermuxBridge] instead of a Context and
 * the private prototype's `TermuxTool`, and returns [ActionResult]. Audio format conversion, run through Termux's ffmpeg.
 *
 * This exists because of a hard platform limit rather than a missing feature.
 * Android ships an MP3 decoder but no encoder, and [MediaEditTool]'s muxer
 * writes only AAC, so MP3 — the format people ask for by name more than any
 * other — is unreachable on-device through the media3 pipeline at any effort.
 * Termux's ffmpeg carries libmp3lame and the rest, and [TermuxBridge] is already
 * wired up, so the conversion still happens entirely on the phone.
 *
 * Kept a separate tool from [MediaEditTool] rather than an eighth operation on
 * it, because their availability differs: media_edit works with no setup, this
 * needs Termux installed and its ffmpeg package present. Being separate lets
 * [Capability.TERMUX] hide it when Termux is absent, so the model is never shown
 * a tool it cannot run.
 *
 * Writes files, so it asks first (CHANGES_SOMETHING).
 */
class MediaConvertTool(
    private val files: ToolFiles,
    private val termux: TermuxBridge,
) {


    /**
     * Shared storage as this device reports it. Read from [Environment] rather
     * than assumed, because a secondary Android user's storage is
     * `/storage/emulated/<id>`, and the boundary decides both which paths Termux
     * can reach and how they are rewritten for it.
     */
    private val sharedRoot: String
        get() = runCatching { Environment.getExternalStorageDirectory().absolutePath }
            .getOrDefault(FfmpegCommand.DEFAULT_SHARED_STORAGE_ROOT)

    companion object {
        /**
         * Per-file input cap. Lower than [MediaEditTool.MAX_MEDIA_BYTES] because
         * every byte crosses the Masala/Termux uid boundary through FUSE, which
         * the private prototype's `termux_filesystem` skill notes is slow above ~100MB — and unlike a
         * transmux, this decodes and re-encodes the whole stream.
         */
        const val MAX_INPUT_BYTES = 512L * 1024 * 1024

        /**
         * Ceiling for one conversion. Audio re-encoding is roughly realtime-over-N
         * on a phone, so this covers a long album side while staying under
         * [TermuxBridge]'s own 600s hard limit.
         */
        const val TIMEOUT_SECONDS = 480

        /**
         * [TermuxBridge.formatResult] always opens with this. Its presence is how a
         * command that *ran and failed* is told apart from Termux itself being
         * unavailable — the latter carries its own well-written message from
         * [TermuxMessages], which should reach the model unaltered rather than be
         * rewritten in ffmpeg's terms.
         */
        private const val SHELL_RESULT_PREFIX = "exit code:"
    }

    /**
     * Converts [input] to [output], choosing the codec from [output]'s extension.
     *
     * @param bitrate optional `-b:a` value ("192k", or a bare kbps number).
     *   Ignored for the lossless targets, where it has no meaning.
     */
    suspend fun convert(
        input: String,
        output: String,
        bitrate: String? = null,
        overwrite: Boolean = false
    ): ActionResult {
        val target = targetFor(output) ?: return unsupportedTarget(output)
        val normalisedBitrate = resolveBitrate(bitrate).unwrapString { return it }

        val source = resolveInput(input).unwrap { return it }
        val destination = resolveOutput(output, source, overwrite).unwrap { return it }

        val command = FfmpegCommand.buildAudioConversion(
            inputCanonical = source.canonicalPath,
            outputCanonical = destination.canonicalPath,
            target = target,
            bitrate = normalisedBitrate,
            sharedRoot = sharedRoot
        )

        val result = termux.runCommand(command = command, timeoutSeconds = TIMEOUT_SECONDS)
        if (!result.success) return interpretFailure(result, source, destination)

        // ffmpeg reports success on some malformed inputs after writing nothing.
        if (!destination.exists() || destination.length() == 0L) {
            return ActionResult.error(
                "ffmpeg reported success but '${destination.canonicalPath}' is missing or empty. " +
                    "The input may not contain a decodable audio track — check it with media_edit " +
                    "operation='info'."
            )
        }
        val quality = if (target.isLossless) {
            "lossless ${target.extension.uppercase()}"
        } else {
            "${normalisedBitrate ?: target.bitrate} ${target.extension.uppercase()}"
        }
        return ActionResult.ok(
            "Converted '${source.name}' to $quality at '${destination.canonicalPath}' — " +
                "${files.formatSize(source.length())} became ${files.formatSize(destination.length())}. " +
                "This ran through Termux's ffmpeg on the device; the file was never uploaded anywhere."
        )
    }

    // ---- validation ----

    private fun targetFor(output: String): FfmpegCommand.Target? =
        FfmpegCommand.audioTargetFor(File(output).extension)

    private fun unsupportedTarget(output: String): ActionResult {
        val extension = File(output).extension.ifEmpty { "no extension" }
        return ActionResult.error(
            "Output '$output' has $extension, which this tool does not convert to. Supported audio targets: " +
                "${FfmpegCommand.describeAudioTargets()}. For video — trimming, muting, compressing, joining — " +
                "use media_edit instead; converting a video container is not something this tool does yet."
        )
    }

    private fun resolveBitrate(bitrate: String?): Result<String?> {
        if (bitrate.isNullOrBlank()) return Result.success(null)
        val normalised = FfmpegCommand.normaliseBitrate(bitrate)
            ?: return Result.failure(
                ResultCarrier(
                    ActionResult.error(
                        "bitrate '$bitrate' is not a bitrate. Write it as kbps, e.g. '192k' or '320k', between " +
                            "${FfmpegCommand.MIN_BITRATE_KBPS} and ${FfmpegCommand.MAX_BITRATE_KBPS}."
                    )
                )
            )
        return Result.success(normalised)
    }

    private fun resolveInput(path: String): Result<File> {
        val file = when (val resolved = files.resolveForRead(path)) {
            is ToolFiles.ResolveResult.PermissionNeeded -> return Result.failure(ResultCarrier(resolved.result))
            is ToolFiles.ResolveResult.Error -> return fail(resolved.message)
            is ToolFiles.ResolveResult.Ok -> resolved.file
        }
        files.checkReadPermission(file)?.let { return Result.failure(ResultCarrier(it)) }
        if (!file.exists()) {
            return fail(
                "'$path' does not exist (resolved: ${file.canonicalPath}). You may use list_files or glob to find it."
            )
        }
        if (!file.isFile) return fail("'$path' is not a regular file.")
        if (file.length() > MAX_INPUT_BYTES) {
            return fail(
                "'${file.name}' is ${files.formatSize(file.length())}; the limit for conversion is " +
                    "${files.formatSize(MAX_INPUT_BYTES)}, because every byte crosses between Masala and " +
                    "Termux. Trim it with media_edit first if only part of it is wanted."
            )
        }
        unreachableFromTermux(file, "Input")?.let { return Result.failure(ResultCarrier(it)) }
        return Result.success(file)
    }

    private fun resolveOutput(path: String, input: File, overwrite: Boolean): Result<File> {
        val file = when (val resolved = files.resolveForWrite(path)) {
            is ToolFiles.ResolveResult.PermissionNeeded -> return Result.failure(ResultCarrier(resolved.result))
            is ToolFiles.ResolveResult.Error -> return fail(resolved.message)
            is ToolFiles.ResolveResult.Ok -> resolved.file
        }
        files.checkWritePermission(file)?.let { return Result.failure(ResultCarrier(it)) }
        if (file.isDirectory) {
            return fail("Output '$path' is a directory. Give the full path of the file to write.")
        }
        if (file.canonicalPath == input.canonicalPath) {
            return fail(
                "Output cannot be the same file as the input — ffmpeg would truncate it before reading it. " +
                    "Write to a new path such as '${input.nameWithoutExtension}.${File(path).extension}'."
            )
        }
        if (file.exists() && !overwrite) {
            return fail(
                "'${file.canonicalPath}' already exists. Pass overwrite=true to replace it, " +
                    "or choose a different output path."
            )
        }
        file.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                return fail("Could not create the output directory '${parent.canonicalPath}'.")
            }
        }
        unreachableFromTermux(file, "Output")?.let { return Result.failure(ResultCarrier(it)) }
        return Result.success(file)
    }

    /**
     * Masala's own sandbox is invisible to Termux — different uid, private
     * directory — so a path there cannot be converted however valid it looks to
     * Masala. Caught here rather than left to ffmpeg, which would report a
     * missing file and send the model hunting for a typo.
     */
    private fun unreachableFromTermux(file: File, role: String): ActionResult? {
        if (FfmpegCommand.isReachableFromTermux(file.canonicalPath, sharedRoot)) return null
        return ActionResult.error(
            "$role path '${file.canonicalPath}' is inside Masala's private storage, which Termux cannot see — " +
                "they run as different Android users. Use a shared-storage path instead, under " +
                "$sharedRoot/Music, /Download or /Documents, which both can reach."
        )
    }

    // ---- failure interpretation ----

    /**
     * Turns a failed run into something the model can act on.
     *
     * A result that never reached the shell — Termux missing, the plugin API
     * absent, the RUN_COMMAND permission ungranted — already carries a message
     * written for exactly that situation, and a permission result carries the
     * grant flow with it. Those pass through untouched; only ffmpeg's own
     * failures are rewritten.
     */
    private fun interpretFailure(result: ActionResult, input: File, output: File): ActionResult {
        if (result.needsPermission != null) return result
        if (!result.message.trimStart().startsWith(SHELL_RESULT_PREFIX)) return result

        val text = result.message
        val detail = text.substringAfter("stderr:", "").trim().ifEmpty { text }
        return when {
            text.contains("CANNOT LINK EXECUTABLE", ignoreCase = true) ||
                text.contains("cannot locate symbol", ignoreCase = true) -> ActionResult.error(
                "Termux's ffmpeg is installed but links against out-of-date libraries — the usual case is " +
                    "a libplacebo that needs a newer libc++. Ask the user to run 'apt-get install -y libc++' in " +
                    "Termux — a small package that reconfigures ffmpeg automatically. If that does not clear " +
                    "it, then 'pkg upgrade -y'. They should never delete lock files or kill -9 dpkg."
            )
            text.contains("not found", ignoreCase = true) -> ActionResult.error(
                "Termux has no ffmpeg installed, which is what does the conversion. Ask the user to run " +
                    "'pkg install ffmpeg -y' in Termux (a large download, 5-15 minutes). If that reports a " +
                    "package lock held by another process, they should wait for it or tap Exit on the Termux " +
                    "notification; never delete the lock files or kill -9 the process."
            )
            text.contains("No such file or directory", ignoreCase = true) -> ActionResult.error(
                "ffmpeg could not see '${input.canonicalPath}', although Masala can. This almost always means the " +
                    "user has never run 'termux-setup-storage' in Termux, without which Termux sees " +
                    "$sharedRoot as empty. Ask them to open Termux once, run " +
                    "'termux-setup-storage' and grant the storage prompt, then try again."
            )
            text.contains("Unknown encoder", ignoreCase = true) ||
                text.contains("Unknown decoder", ignoreCase = true) -> ActionResult.error(
                "This build of Termux's ffmpeg does not carry the codec needed for " +
                    "'${output.extension}'. Ask the user to run 'pkg upgrade ffmpeg', or pick another format — " +
                    "${FfmpegCommand.describeAudioTargets()}. Detail: $detail"
            )
            text.contains("Invalid data found", ignoreCase = true) -> ActionResult.error(
                "ffmpeg could not decode '${input.name}' — it is corrupt, incomplete, or not actually audio. " +
                    "Check it with media_edit operation='info'."
            )
            text.contains("Permission denied", ignoreCase = true) -> ActionResult.error(
                "Termux was refused access to '${output.canonicalPath}'. If the user has run " +
                    "'termux-setup-storage', the folder may still be one Android does not let other apps write. " +
                    "Try $sharedRoot/Download instead."
            )
            else -> ActionResult.error("The conversion failed. ffmpeg said: $detail")
        }
    }

    // ---- shared plumbing ----

    /** Carries an already-formed [ActionResult] (permission or validation) out of a helper. */
    private class ResultCarrier(val result: ActionResult) : Exception(result.message)

    private fun fail(message: String): Result<File> = Result.failure(ResultCarrier(ActionResult.error(message)))

    private inline fun Result<File>.unwrap(onFailure: (ActionResult) -> Nothing): File =
        fold(
            onSuccess = { it },
            onFailure = { e -> onFailure((e as? ResultCarrier)?.result ?: ActionResult.error(e.message.orEmpty())) }
        )

    private inline fun Result<String?>.unwrapString(onFailure: (ActionResult) -> Nothing): String? =
        fold(
            onSuccess = { it },
            onFailure = { e -> onFailure((e as? ResultCarrier)?.result ?: ActionResult.error(e.message.orEmpty())) }
        )
}
