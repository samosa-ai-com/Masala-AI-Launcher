package com.samosaai.masala.scheduled

import android.content.Context
import com.samosaai.masala.core.scheduled.TaskStorage

/**
 * Keeps the scheduled tasks in one SharedPreferences string. the private prototype uses encrypted preferences
 * (`SafeEncryptedSharedPreferences`); these are plain app-private preferences, which Android already
 * encrypts at rest, and that library is deprecated.
 */
class PrefsTaskStorage(context: Context) : TaskStorage {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY_TASKS, null)

    // commit, not apply: the alarm receiver writes here just before its process may go.
    override fun write(json: String) {
        prefs.edit().putString(KEY_TASKS, json).commit()
    }

    private companion object {
        const val PREFS_FILE = "masala_scheduled_tasks"
        const val KEY_TASKS = "tasks"
    }
}
