package com.samosaai.masala.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.MasalaJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class LauncherSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** On top of the system font size (Olauncher §2.8). */
    val textScale: Float = 1f,
    /** Open the app as soon as the search leaves one result. */
    val autoLaunch: Boolean = false,
    /** Olauncher's HOME_LOCKED: no pinning, unpinning or other layout edits from the home screen. */
    val homeLocked: Boolean = false,
    /** Each gesture is a [MasalaAction] the user can change in settings (plan Phase 2). */
    val swipeUp: MasalaAction = MasalaAction.OpenDrawer,
    val swipeDown: MasalaAction = MasalaAction.ExpandNotifications,
    /** Speak every reply from the reply card (plan Phase 4 M3). */
    val readRepliesAloud: Boolean = false,
) {
    companion object {
        const val MIN_TEXT_SCALE = 0.5f
        const val MAX_TEXT_SCALE = 2f
    }
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val textScale = floatPreferencesKey("text_scale")
        val autoLaunch = booleanPreferencesKey("auto_launch")
        val homeLocked = booleanPreferencesKey("home_locked")
        val swipeUp = stringPreferencesKey("swipe_up")
        val swipeDown = stringPreferencesKey("swipe_down")
        val readRepliesAloud = booleanPreferencesKey("read_replies_aloud")
    }

    val settings: Flow<LauncherSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun update(change: (LauncherSettings) -> LauncherSettings) {
        context.dataStore.edit { prefs ->
            val next = change(prefs.toSettings())
            prefs[Keys.theme] = next.theme.name
            prefs[Keys.textScale] = next.textScale.coerceIn(LauncherSettings.MIN_TEXT_SCALE, LauncherSettings.MAX_TEXT_SCALE)
            prefs[Keys.autoLaunch] = next.autoLaunch
            prefs[Keys.homeLocked] = next.homeLocked
            prefs[Keys.swipeUp] = MasalaJson.strict.encodeToString(MasalaAction.serializer(), next.swipeUp)
            prefs[Keys.swipeDown] = MasalaJson.strict.encodeToString(MasalaAction.serializer(), next.swipeDown)
            prefs[Keys.readRepliesAloud] = next.readRepliesAloud
        }
    }

    private fun Preferences.toSettings() = LauncherSettings(
        theme = this[Keys.theme]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.SYSTEM,
        textScale = this[Keys.textScale] ?: 1f,
        autoLaunch = this[Keys.autoLaunch] ?: false,
        homeLocked = this[Keys.homeLocked] ?: false,
        swipeUp = this[Keys.swipeUp].toAction(MasalaAction.OpenDrawer),
        swipeDown = this[Keys.swipeDown].toAction(MasalaAction.ExpandNotifications),
        readRepliesAloud = this[Keys.readRepliesAloud] ?: false,
    )

    // A gesture saved by a newer version, or damaged, falls back to its default.
    private fun String?.toAction(default: MasalaAction): MasalaAction =
        this?.let { runCatching { MasalaJson.lenient.decodeFromString(MasalaAction.serializer(), it) }.getOrNull() } ?: default
}
