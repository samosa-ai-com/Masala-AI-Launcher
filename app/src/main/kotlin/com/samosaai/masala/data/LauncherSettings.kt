package com.samosaai.masala.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.MasalaJson
import com.samosaai.masala.core.weather.Place
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Masala Glass (dark) is the default; Paper is the light scheme (plan UI refresh, decision 4). */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** The one warm accent for actions. [WALLPAPER] picks it from the wallpaper's colours. */
enum class Accent { GOLD, INDIGO, CARDAMOM, CHILI, LILAC, WALLPAPER }

/** Typeface for text; the clock and big numbers stay in Space Grotesk unless [SYSTEM]. */
enum class FontChoice { INTER, FIGTREE, SYSTEM }

/** The mask every icon is drawn in: apps, shortcuts, mini apps and symbols alike. */
enum class IconShape { SQUIRCLE, CIRCLE, SQUARE, AS_SHIPPED }

/** [TINTED] uses the app's monochrome layer (Android 13+), else the original icon. */
enum class IconStyle { ORIGINAL, TINTED }

/** [REDUCED] turns movement into crossfades; the phone's own animation setting is honoured too. */
enum class MotionLevel { FULL, REDUCED }

/** Who shows Masala's state: Sam, the minimal orb, or only the grinding logo mark. */
enum class Character { SAM, ORB, HIDDEN }

enum class DrawerLayout { GRID, GLOBE, LIST }

data class LauncherSettings(
    val theme: ThemeMode = ThemeMode.DARK,
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
    /** Open the keyboard as soon as the drawer opens; off, it opens when the search field is tapped. */
    val drawerKeyboard: Boolean = false,
    val accent: Accent = Accent.GOLD,
    val font: FontChoice = FontChoice.INTER,
    val iconShape: IconShape = IconShape.SQUIRCLE,
    val iconStyle: IconStyle = IconStyle.ORIGINAL,
    val motion: MotionLevel = MotionLevel.FULL,
    val haptics: Boolean = true,
    val character: Character = Character.SAM,
    val drawerLayout: DrawerLayout = DrawerLayout.GRID,
    /** The first-run welcome was finished or skipped. */
    val welcomeDone: Boolean = false,
    /** Usage access was offered once (first run, or the first time the globe opens), granted or not. */
    val usageAsked: Boolean = false,
    /** Where the daily brief's weather is for; null until the user picks a place or their location. */
    val briefPlace: Place? = null,
    /** Post a quiet notification when the daily brief is ready. */
    val briefNotify: Boolean = false,
    /** Notify when a reply lands while Masala isn't visible. */
    val replyNotify: Boolean = true,
    /** `salt:hash` for the Hidden apps page; null leaves the page open. See [HiddenPin]. */
    val hiddenPin: String? = null,
) {
    companion object {
        const val MIN_TEXT_SCALE = 0.5f
        const val MAX_TEXT_SCALE = 2f
    }
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    val settings: Flow<LauncherSettings> = context.dataStore.data.map { it.toSettings() }

    /** The settings as they are now, for code that runs once (a tool call, a background run). */
    suspend fun current(): LauncherSettings = settings.first()

    suspend fun update(change: (LauncherSettings) -> LauncherSettings) {
        context.dataStore.edit { prefs -> prefs.write(change(prefs.toSettings())) }
    }
}

private object Keys {
    val theme = stringPreferencesKey("theme")
    val textScale = floatPreferencesKey("text_scale")
    val autoLaunch = booleanPreferencesKey("auto_launch")
    val homeLocked = booleanPreferencesKey("home_locked")
    val swipeUp = stringPreferencesKey("swipe_up")
    val swipeDown = stringPreferencesKey("swipe_down")
    val readRepliesAloud = booleanPreferencesKey("read_replies_aloud")
    val drawerKeyboard = booleanPreferencesKey("drawer_keyboard")
    val accent = stringPreferencesKey("accent")
    val font = stringPreferencesKey("font")
    val iconShape = stringPreferencesKey("icon_shape")
    val iconStyle = stringPreferencesKey("icon_style")
    val motion = stringPreferencesKey("motion")
    val haptics = booleanPreferencesKey("haptics")
    val character = stringPreferencesKey("character")
    val drawerLayout = stringPreferencesKey("drawer_layout")
    val welcomeDone = booleanPreferencesKey("welcome_done")
    val usageAsked = booleanPreferencesKey("usage_asked")
    val briefPlace = stringPreferencesKey("brief_place")
    val briefNotify = booleanPreferencesKey("brief_notify")
    val replyNotify = booleanPreferencesKey("reply_notify")
    val hiddenPin = stringPreferencesKey("hidden_pin")
}

internal fun MutablePreferences.write(next: LauncherSettings) {
    this[Keys.theme] = next.theme.name
    this[Keys.textScale] = next.textScale.coerceIn(LauncherSettings.MIN_TEXT_SCALE, LauncherSettings.MAX_TEXT_SCALE)
    this[Keys.autoLaunch] = next.autoLaunch
    this[Keys.homeLocked] = next.homeLocked
    this[Keys.swipeUp] = MasalaJson.strict.encodeToString(MasalaAction.serializer(), next.swipeUp)
    this[Keys.swipeDown] = MasalaJson.strict.encodeToString(MasalaAction.serializer(), next.swipeDown)
    this[Keys.readRepliesAloud] = next.readRepliesAloud
    this[Keys.drawerKeyboard] = next.drawerKeyboard
    this[Keys.accent] = next.accent.name
    this[Keys.font] = next.font.name
    this[Keys.iconShape] = next.iconShape.name
    this[Keys.iconStyle] = next.iconStyle.name
    this[Keys.motion] = next.motion.name
    this[Keys.haptics] = next.haptics
    this[Keys.character] = next.character.name
    this[Keys.drawerLayout] = next.drawerLayout.name
    this[Keys.welcomeDone] = next.welcomeDone
    this[Keys.usageAsked] = next.usageAsked
    if (next.briefPlace != null) this[Keys.briefPlace] = MasalaJson.strict.encodeToString(Place.serializer(), next.briefPlace) else remove(Keys.briefPlace)
    this[Keys.briefNotify] = next.briefNotify
    this[Keys.replyNotify] = next.replyNotify
    if (next.hiddenPin != null) this[Keys.hiddenPin] = next.hiddenPin else remove(Keys.hiddenPin)
}

internal fun Preferences.toSettings(): LauncherSettings {
    val defaults = LauncherSettings()
    return LauncherSettings(
        theme = enumOr(this[Keys.theme], defaults.theme),
        textScale = this[Keys.textScale] ?: defaults.textScale,
        autoLaunch = this[Keys.autoLaunch] ?: defaults.autoLaunch,
        homeLocked = this[Keys.homeLocked] ?: defaults.homeLocked,
        swipeUp = this[Keys.swipeUp].toAction(defaults.swipeUp),
        swipeDown = this[Keys.swipeDown].toAction(defaults.swipeDown),
        readRepliesAloud = this[Keys.readRepliesAloud] ?: defaults.readRepliesAloud,
        drawerKeyboard = this[Keys.drawerKeyboard] ?: defaults.drawerKeyboard,
        accent = enumOr(this[Keys.accent], defaults.accent),
        font = enumOr(this[Keys.font], defaults.font),
        iconShape = enumOr(this[Keys.iconShape], defaults.iconShape),
        iconStyle = enumOr(this[Keys.iconStyle], defaults.iconStyle),
        motion = enumOr(this[Keys.motion], defaults.motion),
        haptics = this[Keys.haptics] ?: defaults.haptics,
        character = enumOr(this[Keys.character], defaults.character),
        drawerLayout = enumOr(this[Keys.drawerLayout], defaults.drawerLayout),
        welcomeDone = this[Keys.welcomeDone] ?: defaults.welcomeDone,
        usageAsked = this[Keys.usageAsked] ?: defaults.usageAsked,
        briefPlace = this[Keys.briefPlace]?.let { runCatching { MasalaJson.lenient.decodeFromString(Place.serializer(), it) }.getOrNull() },
        briefNotify = this[Keys.briefNotify] ?: defaults.briefNotify,
        replyNotify = this[Keys.replyNotify] ?: defaults.replyNotify,
        hiddenPin = this[Keys.hiddenPin],
    )
}

// A value saved by a newer version, or damaged, falls back to its default.
private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

private fun String?.toAction(default: MasalaAction): MasalaAction =
    this?.let { runCatching { MasalaJson.lenient.decodeFromString(MasalaAction.serializer(), it) }.getOrNull() } ?: default
