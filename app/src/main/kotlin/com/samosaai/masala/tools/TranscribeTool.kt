package com.samosaai.masala.tools

import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.tool.ToolFiles
import com.samosaai.masala.audio.AndroidTranscriber
import com.samosaai.masala.audio.AudioApi
import com.samosaai.masala.audio.AudioModel
import com.samosaai.masala.audio.ModelCategory
import com.samosaai.masala.audio.SpeechConfig
import java.io.File

/**
 * Transcribes an audio file already on disk through the configured STT API. From the private prototype's
 * TranscribeTool, reading the endpoint from [SpeechConfig] instead of the private prototype's speech settings.
 *
 * This is the missing middle of the voice-memo-to-podcast flow:
 * `start_audio_recording(output_path=…)` puts a recording on disk,
 * `synthesize_podcast` wants a script, and nothing in between could read a
 * saved file back as text — the private prototype's SttEngine only transcribes
 * recordings it made itself, and its `transcribeApi` *deletes its input* after
 * upload (its files are its own throwaways). This tool wraps
 * [AudioApi.transcribe] directly, which reads the file and touches nothing.
 *
 * Requires an API-based STT provider: Android's `SpeechRecognizer` listens to
 * the microphone and has no file input. Only available to Operator — the file
 * content leaves the device for the configured STT endpoint.
 */
class TranscribeTool(
    private val context: android.content.Context,
    private val resolver: ToolFiles,
    private val loadSettings: () -> SpeechConfig = { SpeechConfig.default },
    private val onUnauthorized: (() -> Unit)? = null,
    private val sttBackendFactory: (baseUrl: String, apiKey: String) -> SttBackend = { url, key ->
        ApiSttBackend(AudioApi(url, key, timeoutSeconds = STT_TIMEOUT_SECONDS, onUnauthorized = onUnauthorized))
    }
) {

    /** The two calls transcription needs from the STT API, extracted for tests. */
    interface SttBackend {
        fun listModels(): List<AudioModel>
        fun transcribe(file: File, model: String, language: String?, contentType: String): Result<String>
    }

    private class ApiSttBackend(private val api: AudioApi) : SttBackend {
        override fun listModels(): List<AudioModel> = api.listAudioModels()
        override fun transcribe(file: File, model: String, language: String?, contentType: String): Result<String> =
            api.transcribeWav(file, model, language)
    }


    companion object {
        /**
         * Upload ceiling. Matches the common OpenAI-compatible transcription
         * limit; a bigger file should be trimmed or split with media_edit
         * first rather than bounced by the server after a slow upload.
         */
        const val MAX_INPUT_BYTES = 25L * 1024 * 1024

        /** Transcripts are pasted into the conversation; past this they drown it. */
        const val MAX_TRANSCRIPT_CHARS = 30_000

        private const val STT_TIMEOUT_SECONDS = 300L

        /** Set once this endpoint proves it has no transcription route, for the rest of the process. */
        @Volatile
        private var apiUnsupported = false
        private const val ERROR_DETAIL_CHARS = 200

        /** Extension → MIME type for the multipart upload. Also the supported-format list. */
        private val CONTENT_TYPES = mapOf(
            "m4a" to "audio/m4a",
            "mp4" to "audio/mp4",
            "aac" to "audio/aac",
            "mp3" to "audio/mpeg",
            "wav" to "audio/wav",
            "ogg" to "audio/ogg",
            "opus" to "audio/ogg",
            "flac" to "audio/flac",
            "webm" to "audio/webm"
        )
    }

    @Suppress("ReturnCount")
    suspend fun transcribe(path: String, model: String? = null, language: String? = null): ActionResult {
        val settings = loadSettings()

        val file = when (val r = resolver.resolveForRead(path)) {
            is ToolFiles.ResolveResult.PermissionNeeded -> return r.result
            is ToolFiles.ResolveResult.Error -> return ActionResult.error(r.message)
            is ToolFiles.ResolveResult.Ok -> r.file
        }
        resolver.checkReadPermission(file)?.let { return it }
        fileError(file, path)?.let { return it }
        val contentType = CONTENT_TYPES[file.extension.lowercase()]
            ?: return ActionResult.error(
                "'.${file.extension}' is not a format this tool can send for transcription. Supported: " +
                    CONTENT_TYPES.keys.sorted().joinToString(", ") { ".$it" } +
                    ". Convert it first with media_convert."
            )

        // No endpoint, or none with a transcription model: use Android's own speech service,
        // which can read a file through RecognizerIntent.EXTRA_AUDIO_SOURCE (API 33+).
        val backend = if (settings.baseUrl.isBlank() || apiUnsupported) null
        else sttBackendFactory(settings.baseUrl, settings.apiKey)
        val resolvedModel = backend?.let { resolveModel(model, settings, it) }
        if (backend == null || resolvedModel == null) {
            val local = AndroidTranscriber.transcribe(context, file, language ?: settings.sttLanguage)
            if (local != null) {
                return local.fold(
                    onSuccess = { text -> transcriptResult(file, text, engine = "Android's built-in speech service") },
                    onFailure = { e ->
                        ActionResult.error(
                            "Android's built-in transcription failed (${e.message?.take(ERROR_DETAIL_CHARS)}). " +
                                "The file was not modified.",
                        )
                    },
                )
            }
            return ActionResult.error(
                "No transcription is available. Configure a working STT endpoint with " +
                    "MASALA_STT_BASE_URL and MASALA_STT_API_KEY (the endpoint must serve " +
                    "POST <base>/asr/transcribe, e.g. /v1/asr/transcribe), or use a device whose speech service can read a file " +
                    "(Android 13+; this one can't).",
            )
        }
        val resolvedLanguage = (language ?: settings.sttLanguage).trim().ifBlank { null }

        // The endpoint accepts only mono 16-bit PCM WAV; recordings here are AAC/m4a.
        val wav = File(context.cacheDir, "asr-${file.nameWithoutExtension}.wav")
        // The WAV is only for the upload: deleted however conversion or upload ends.
        val apiResult = try {
            if (!AndroidTranscriber.toWav(file, wav)) return notConverted(file, resolvedLanguage)
            backend.transcribe(wav, resolvedModel, resolvedLanguage, "audio/wav")
        } finally {
            runCatching { wav.delete() }
        }
        val transcript = apiResult.getOrElse { e ->
            // A proxy can list a model and not serve the route (this one answers 404). Remember
            // that, so the next call doesn't spend a round-trip and a scary 404 on it.
            val message = e.message.orEmpty()
            if (message.contains("404") || message.contains("405") || message.contains("not found", true)) {
                apiUnsupported = true
            }
            // The endpoint can list a transcription model and not serve the route at all (this
            // one returns 404). Android's own speech service reads the file instead, so a
            // configured-but-unusable endpoint does not leave transcription broken.
            val local = AndroidTranscriber.transcribe(context, file, resolvedLanguage)
            if (local != null) {
                return local.fold(
                    onSuccess = { text -> transcriptResult(file, text, "Android's built-in speech service") },
                    onFailure = { localError ->
                        ActionResult.error(
                            "Transcription failed both ways. The API said: " +
                                "${e.message?.take(ERROR_DETAIL_CHARS) ?: "no detail"}; Android's speech " +
                                "service said: ${localError.message?.take(ERROR_DETAIL_CHARS)}. " +
                                "The file was not modified.",
                        )
                    },
                )
            }
            return ActionResult.error(
                "Transcription failed: ${e.message?.take(ERROR_DETAIL_CHARS) ?: "no detail"}. " +
                    "Android's speech service on this phone can't read a file, and the endpoint " +
                    "doesn't transcribe. Set MASALA_STT_BASE_URL and MASALA_STT_API_KEY to an " +
                    "endpoint that serves POST <base>/asr/transcribe (e.g. /v1/asr/transcribe). The file was not modified.",
            )
        }
        if (transcript.isBlank()) {
            return ActionResult.error(
                "The STT endpoint returned an empty transcript for '${file.name}' — the file may contain " +
                    "no speech, or the model/language may not match it."
            )
        }
        val clipped = transcript.length > MAX_TRANSCRIPT_CHARS
        val text = if (clipped) transcript.take(MAX_TRANSCRIPT_CHARS) else transcript
        val truncationNote = if (clipped) {
            "\n\n[Transcript truncated at $MAX_TRANSCRIPT_CHARS characters — the audio continues beyond " +
                "this point. Trim the file with media_edit and transcribe the rest separately if needed.]"
        } else {
            ""
        }
        return ActionResult.ok(
            "Transcript of '${file.name}' (${resolver.formatSize(file.length())}, spoken content sent to " +
                "the configured STT API; the file itself was not modified):\n\n$text$truncationNote"
        )
    }

    /** The file couldn't become the WAV the endpoint takes: Android's own speech service is the last try. */
    private suspend fun notConverted(file: File, language: String?): ActionResult {
        val local = AndroidTranscriber.transcribe(context, file, language)
        return if (local != null) {
            local.fold(
                onSuccess = { text -> transcriptResult(file, text, "Android's built-in speech service") },
                onFailure = { e ->
                    ActionResult.error(
                        "Couldn't convert '${file.name}' to WAV for the transcription endpoint " +
                            "(${e.message?.take(ERROR_DETAIL_CHARS)}).",
                    )
                },
            )
        } else {
            ActionResult.error(
                "Couldn't convert '${file.name}' to the WAV the endpoint needs, and this device " +
                    "can't transcribe a file itself.",
            )
        }
    }

    private fun transcriptResult(file: File, transcript: String, engine: String): ActionResult {
        if (transcript.isBlank()) {
            return ActionResult.error("No speech was recognised in '${file.name}'.")
        }
        val clipped = transcript.length > MAX_TRANSCRIPT_CHARS
        val text = if (clipped) transcript.take(MAX_TRANSCRIPT_CHARS) else transcript
        val truncationNote = if (clipped) {
            "\n\n[Transcript truncated at $MAX_TRANSCRIPT_CHARS characters.]"
        } else {
            ""
        }
        return ActionResult.ok(
            "Transcript of '${file.name}' via $engine (the file itself was not modified):\n\n$text$truncationNote",
        )
    }


    private fun fileError(file: File, path: String): ActionResult? = when {
        !file.exists() -> ActionResult.error(
            "'$path' does not exist (resolved: ${file.canonicalPath}). You may use list_files or glob to find it."
        )
        !file.isFile -> ActionResult.error("'$path' is not a regular file.")
        file.length() == 0L -> ActionResult.error("'${file.name}' is empty — there is nothing to transcribe.")
        file.length() > MAX_INPUT_BYTES -> ActionResult.error(
            "'${file.name}' is ${resolver.formatSize(file.length())}; the transcription limit is " +
                "${resolver.formatSize(MAX_INPUT_BYTES)}. Trim it into parts with media_edit and " +
                "transcribe each part."
        )
        else -> null
    }

    private fun resolveModel(modelArg: String?, settings: SpeechConfig, backend: SttBackend): String? {
        val explicit = (modelArg ?: settings.sttModel).trim()
        if (explicit.isNotBlank()) return explicit
        return runCatching { backend.listModels() }.getOrDefault(emptyList())
            .firstOrNull { it.category == ModelCategory.STT }?.id
    }
}
