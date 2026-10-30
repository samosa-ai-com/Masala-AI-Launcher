package com.samosaai.masala.llm

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.samosaai.masala.audio.SpeechConfig
import com.samosaai.masala.core.ai.AiProfile
import com.samosaai.masala.core.ai.Endpoint
import com.samosaai.masala.core.ai.ProviderPresets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.aiStore by preferencesDataStore(name = "ai_settings")

/**
 * The AI and voice choices (plan UI refresh U2): an [AiProfile] in DataStore and one API key per
 * provider, each sealed with [KeyCipher]. Settings, the Debug screen and every model call read the same
 * store, so they never disagree. On the first run of any build it seeds once from what the phone used
 * before: the Debug screen's saved endpoint if any, else `local.properties` through BuildConfig (keys
 * stay compiled in, decision 8).
 */
class AiSettingsStore(private val context: Context, private val cipher: KeyCipher) {
    private object Keys {
        val profile = stringPreferencesKey("profile")
        val keys = stringPreferencesKey("keys")
        val seeded = booleanPreferencesKey("seeded")
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val keyMap = MapSerializer(String.serializer(), String.serializer())

    private val _profile: MutableStateFlow<AiProfile>
    private val sealed: MutableMap<String, String>

    /** Decrypted keys, kept in memory only. */
    private val plain = HashMap<String, String>()

    init {
        // One small file, read once at start-up so the synchronous LlmConfig.default works right away.
        val prefs = runBlocking { context.aiStore.data.first() }
        val stored = prefs[Keys.profile]?.let { runCatching { json.decodeFromString(AiProfile.serializer(), it) }.getOrNull() }
        sealed = prefs[Keys.keys]?.let { runCatching { json.decodeFromString(keyMap, it) }.getOrNull() }?.toMutableMap() ?: mutableMapOf()
        _profile = MutableStateFlow(stored ?: seedProfile())
        if (prefs[Keys.seeded] != true || stored == null) runBlocking { seed() }
    }

    val profile: StateFlow<AiProfile> = _profile.asStateFlow()

    /** The key saved for [presetId], or blank. */
    fun key(presetId: String): String = synchronized(plain) {
        plain.getOrPut(presetId) { sealed[presetId]?.let(cipher::decrypt).orEmpty() }
    }

    fun hasKey(presetId: String): Boolean = key(presetId).isNotBlank()

    /** Decrypts every saved key now (call off the main thread), so screens never wait on the Keystore. */
    fun warmKeys() {
        synchronized(plain) { sealed.keys.toList() }.forEach(::key)
    }

    suspend fun setProfile(next: AiProfile) {
        _profile.value = next
        context.aiStore.edit { it[Keys.profile] = json.encodeToString(AiProfile.serializer(), next) }
    }

    suspend fun update(change: (AiProfile) -> AiProfile) = setProfile(change(_profile.value))

    /** Saves [key] for [presetId] (blank removes it). The plain text never touches storage. */
    suspend fun setKey(presetId: String, key: String) {
        val trimmed = key.trim()
        synchronized(plain) {
            if (trimmed.isEmpty()) {
                plain.remove(presetId)
                sealed.remove(presetId)
            } else {
                plain[presetId] = trimmed
                sealed[presetId] = cipher.encrypt(trimmed)
            }
        }
        val snapshot = synchronized(plain) { sealed.toMap() }
        context.aiStore.edit { it[Keys.keys] = json.encodeToString(keyMap, snapshot) }
    }

    /** What chat calls use now. */
    fun chatConfig(): LlmConfig = _profile.value.chat.let { LlmConfig(it.baseUrl, keyFor(it), it.model) }

    /** What voice input uses now; a blank URL means "on this phone". */
    fun speechConfig(): SpeechConfig {
        val p = _profile.value
        val e = p.voiceIn ?: return SpeechConfig("", "", "", p.voiceLanguage)
        return SpeechConfig(e.baseUrl, keyFor(e), e.model, p.voiceLanguage)
    }

    fun keyFor(e: Endpoint): String = key(e.presetId)

    private fun seedProfile(): AiProfile {
        val debug = context.getSharedPreferences("debug_llm", Context.MODE_PRIVATE)
        val llm = LlmConfig.fromBuild.let { b ->
            LlmConfig(debug.getString("base_url", null) ?: b.baseUrl, debug.getString("api_key", null) ?: b.apiKey, debug.getString("model", null) ?: b.model)
        }
        val stt = SpeechConfig.fromBuild
        val chat = Endpoint(ProviderPresets.detect(llm.baseUrl).id, llm.baseUrl, llm.model)
        val voiceIn = stt.baseUrl.takeIf { it.isNotBlank() }?.let { Endpoint(ProviderPresets.detect(it).id, it, stt.sttModel) }
        return AiProfile(chat = chat, voiceIn = voiceIn)
    }

    private suspend fun seed() {
        val p = _profile.value
        val debug = context.getSharedPreferences("debug_llm", Context.MODE_PRIVATE)
        val llmKey = debug.getString("api_key", null) ?: LlmConfig.fromBuild.apiKey
        if (llmKey.isNotBlank() && !hasKey(p.chat.presetId)) setKey(p.chat.presetId, llmKey)
        val sttKey = SpeechConfig.fromBuild.apiKey
        p.voiceIn?.let { if (sttKey.isNotBlank() && !hasKey(it.presetId)) setKey(it.presetId, sttKey) }
        context.aiStore.edit {
            it[Keys.profile] = json.encodeToString(AiProfile.serializer(), p)
            it[Keys.seeded] = true
        }
    }
}
