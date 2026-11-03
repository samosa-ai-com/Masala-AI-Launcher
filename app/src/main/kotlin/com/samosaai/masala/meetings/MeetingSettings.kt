package com.samosaai.masala.meetings

import android.content.Context
import com.samosaai.masala.audio.SpeechConfig
import com.samosaai.masala.llm.AiSettingsStore

/**
 * Where meeting transcription goes. Voice input is the obvious choice, but it is "On this phone"
 * (blank URL, no endpoint) for some users — and the phone's own speech service cannot label speakers.
 * So the order is: the meeting model on the voice endpoint, then the chat endpoint (same host and
 * key; its `/models` list advertises the ASR model), then nothing.
 */
object MeetingSpeech {

    fun resolve(ai: AiSettingsStore, meetingModel: String, language: String): SpeechConfig {
        val voice = ai.speechConfig()
        if (voice.baseUrl.isNotBlank()) {
            return voice.copy(
                sttModel = meetingModel.ifBlank { voice.sttModel },
                sttLanguage = language.ifBlank { voice.sttLanguage },
            )
        }
        val chat = ai.chatConfig()
        return SpeechConfig(chat.baseUrl, chat.apiKey, meetingModel, language)
    }
}

/**
 * The meeting preferences that outlive a session: how it transcribes and what it keeps. A plain
 * SharedPreferences file — these are five values, not a store.
 */
class MeetingSettings(context: Context) {
    private val prefs = context.getSharedPreferences("meeting_settings", Context.MODE_PRIVATE)

    /** The upload is ~1.9 MB per minute, so the default waits for Wi-Fi. */
    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI, true)
        set(value) = prefs.edit().putBoolean(KEY_WIFI, value).apply()

    /** Sky is the new default: a room full of mixed accents is better served by the endpoint. */
    var language: String
        get() = prefs.getString(KEY_LANGUAGE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value.trim()).apply()

    /** Blank means "ask the server for its first transcription model". */
    var model: String
        get() = prefs.getString(KEY_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MODEL, value.trim()).apply()

    var template: String
        get() = prefs.getString(KEY_TEMPLATE, com.samosaai.masala.core.meeting.MeetingTemplate.General.id)
            ?: com.samosaai.masala.core.meeting.MeetingTemplate.General.id
        set(value) = prefs.edit().putString(KEY_TEMPLATE, value).apply()

    /** How much of the previous chunk is re-uploaded so speaker labels can be matched. */
    val overlapMs: Long = 12_000L

    /** Keep the audio for playback and re-transcription. */
    var keepAudio: Boolean
        get() = prefs.getBoolean(KEY_KEEP_AUDIO, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_AUDIO, value).apply()

    private companion object {
        const val KEY_WIFI = "wifi_only"
        const val KEY_LANGUAGE = "language"
        const val KEY_MODEL = "model"
        const val KEY_TEMPLATE = "template"
        const val KEY_KEEP_AUDIO = "keep_audio"
    }
}
