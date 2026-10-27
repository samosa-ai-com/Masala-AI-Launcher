package com.samosaai.masala.launcher

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Becoming the default home app through `RoleManager.ROLE_HOME`. Android stops showing that dialog
 * after the user turns it down twice; then the only way is the system's default home app screen.
 *
 * Olauncher's FakeHomeActivity fallback (§2.15: enable a second HOME activity so Android forgets the
 * current choice and shows a picker) only works up to Android 9. Since Android 10 the default home is
 * a role, and on the API 36 emulator the HOME intent went straight to the role holder, so it's left out.
 */
object HomeRole {
    fun isDefault(context: Context): Boolean {
        val roles = context.getSystemService(RoleManager::class.java)
        return roles.isRoleAvailable(RoleManager.ROLE_HOME) && roles.isRoleHeld(RoleManager.ROLE_HOME)
    }

    /** The dialog asking to make Masala the home app, or null if it can't be shown. */
    fun requestIntent(context: Context): Intent? {
        val roles = context.getSystemService(RoleManager::class.java)
        if (!roles.isRoleAvailable(RoleManager.ROLE_HOME) || roles.isRoleHeld(RoleManager.ROLE_HOME)) return null
        return roles.createRequestRoleIntent(RoleManager.ROLE_HOME)
    }

    /** The system's default home app screen. */
    fun openSettings(context: Context) {
        val home = Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(home)
        } catch (e: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
