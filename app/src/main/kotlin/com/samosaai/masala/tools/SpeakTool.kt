package com.samosaai.masala.tools

import android.content.Context
import com.samosaai.masala.audio.AudioApi
import com.samosaai.masala.audio.AudioProvider
import com.samosaai.masala.audio.TtsEngine
import com.samosaai.masala.core.ai.ProviderPresets
import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.core.tool.Produced
import com.samosaai.masala.llm.AiSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Simple text-to-speech: synthesizes with the
 * configured voice output, saves the audio file, and plays it aloud.
 * Uses the same `voiceOut` + key the reply Read-aloud button uses; with no API
 * voice configured it speaks on-device and reports that no file was kept.
 */
class SpeakTool(
    private val context: Context,
    private val settings: AiSettingsStore,
    private val tts: TtsEngine,
) {
    suspend fun speak(text: String, voice: String? = null): ActionResult =
        withContext(Dispatchers.IO) {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return@withContext ActionResult.error("Say what to speak first.")
            if (trimmed.length > MAX_CHARS) return@withContext ActionResult.error("Keep it under $MAX_CHARS characters.")
            val profile = settings.profile.value
            val out = profile.voiceOut
            if (out == null) {
                val spoke = tts.speak(trimmed, language = voiceLanguage(), androidVoice = profile.voice, rate = profile.speechRate)
                return@withContext if (spoke) ActionResult.ok("Spoke on this phone (no API voice, so no file was kept).")
                else ActionResult.error("No voice could speak that.")
            }
            val preset = ProviderPresets.byId(out.presetId)
            val model = out.model.ifBlank { preset.defaultTtsModel }
            val voiceId = voice?.trim().orEmpty().ifEmpty { profile.voice.ifBlank { preset.fallbackVoices.firstOrNull().orEmpty() } }
            if (model.isBlank() || voiceId.isBlank()) {
                return@withContext ActionResult.error("Pick an API voice in Settings → Masala AI first.")
            }
            val api = AudioApi(out.baseUrl, settings.keyFor(out))
            val bytes = api.synthesize(trimmed, model, voiceId, profile.speechRate).getOrElse {
                return@withContext ActionResult.error("Couldn't synthesize speech (${it.message?.take(160)}).")
            }
            val dir = File(context.filesDir, "audio").apply { mkdirs() }
            val file = File(dir, "speak-${System.currentTimeMillis()}.wav")
            runCatching { file.writeBytes(bytes) }.onFailure {
                return@withContext ActionResult.error("Couldn't save the audio.")
            }
            prune(dir)
            tts.speak(trimmed, AudioProvider.API, apiModel = model, voice = voiceId, language = voiceLanguage(), rate = profile.speechRate)
            ActionResult.ok(
                "Spoke and saved the audio.",
                produced = listOf(Produced("audio", trimmed.take(80), file.absolutePath)),
            )
        }

    private fun voiceLanguage(): com.samosaai.masala.i18n.Language =
        runCatching {
            com.samosaai.masala.i18n.Language.valueOf(settings.profile.value.voiceLanguage.ifBlank { "ENGLISH" })
        }.getOrDefault(com.samosaai.masala.i18n.Language.ENGLISH)

    private fun prune(dir: File) {
        dir.listFiles { f -> f.name.startsWith("speak-") }
            .orEmpty().sortedByDescending { it.lastModified() }
            .drop(KEEP)
            .forEach { runCatching { it.delete() } }
    }

    companion object {
        private const val KEEP = 20
        const val MAX_CHARS = 2_000
    }
}
