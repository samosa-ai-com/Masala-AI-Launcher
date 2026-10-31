package com.samosaai.masala.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.samosaai.masala.core.contract.MasalaAction
import kotlin.test.Test
import kotlin.test.assertEquals

class LauncherSettingsTest {
    @Test
    fun `every setting survives a write and a read`() {
        val changed = LauncherSettings(
            theme = ThemeMode.LIGHT,
            textScale = 1.3f,
            autoLaunch = true,
            homeLocked = true,
            swipeUp = MasalaAction.AskMasala(""),
            swipeDown = MasalaAction.OpenDrawer,
            readRepliesAloud = true,
            drawerKeyboard = true,
            accent = Accent.WALLPAPER,
            font = FontChoice.FIGTREE,
            iconShape = IconShape.CIRCLE,
            iconStyle = IconStyle.TINTED,
            motion = MotionLevel.REDUCED,
            haptics = false,
            character = Character.ORB,
            drawerLayout = DrawerLayout.GLOBE,
            welcomeDone = true,
            usageAsked = true,
            briefPlace = com.samosaai.masala.core.weather.Place("Bengaluru", 12.97, 77.59, "India"),
            briefNotify = true,
        )
        val prefs = mutablePreferencesOf()
        prefs.write(changed)
        assertEquals(changed, prefs.toSettings())
    }

    @Test
    fun `nothing saved gives the defaults, with Masala Glass dark and the grid drawer`() {
        val read = mutablePreferencesOf().toSettings()
        assertEquals(LauncherSettings(), read)
        assertEquals(ThemeMode.DARK, read.theme)
        assertEquals(DrawerLayout.GRID, read.drawerLayout)
        assertEquals(Character.SAM, read.character)
    }

    @Test
    fun `an unknown saved value falls back to its default`() {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("accent") to "NEON",
            stringPreferencesKey("icon_shape") to "",
            stringPreferencesKey("swipe_up") to "{broken",
        )
        val read = prefs.toSettings()
        assertEquals(Accent.GOLD, read.accent)
        assertEquals(IconShape.SQUIRCLE, read.iconShape)
        assertEquals(MasalaAction.OpenDrawer, read.swipeUp)
    }

    @Test
    fun `text scale is kept within its bounds`() {
        val prefs = mutablePreferencesOf()
        prefs.write(LauncherSettings(textScale = 9f))
        assertEquals(LauncherSettings.MAX_TEXT_SCALE, prefs.toSettings().textScale)
    }

    @Test
    fun `clearing the brief's place removes it`() {
        val prefs = mutablePreferencesOf()
        prefs.write(LauncherSettings(briefPlace = com.samosaai.masala.core.weather.Place("Pune", 18.5, 73.8)))
        prefs.write(LauncherSettings(briefPlace = null))
        assertEquals(null, prefs.toSettings().briefPlace)
    }
}
