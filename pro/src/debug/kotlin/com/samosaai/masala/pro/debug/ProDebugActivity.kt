package com.samosaai.masala.pro.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.samosaai.masala.pro.data.ExportedApp
import com.samosaai.masala.pro.data.ProDatabase
import com.samosaai.masala.pro.install.ApkInstaller
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug builds only. `:app` may call nothing in `:pro` but `ProTools` (plan 0.3), so this drives the
 * pieces that have no tool yet from adb, logging to tag `ProDebug`:
 *
 *     am start -n com.samosaai.masala/com.samosaai.masala.pro.debug.ProDebugActivity --es cmd install --es apk <path in app storage>
 *     am start -n com.samosaai.masala/com.samosaai.masala.pro.debug.ProDebugActivity --es cmd db
 */
class ProDebugActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scope = MainScope()
        when (intent.getStringExtra("cmd")) {
            "install" -> {
                val installer = ApkInstaller(this)
                Log.i(TAG, "canInstall=${installer.canInstall()}")
                scope.launch { Log.i(TAG, "install: ${installer.install(File(intent.getStringExtra("apk").orEmpty()))}") }
            }
            "db" -> scope.launch {
                val db = ProDatabase.get(this@ProDebugActivity)
                // The real index: add one test row and remove only that row afterwards.
                db.fileIndex().insert("/sdcard/Documents/tax-2025.txt", "tax-2025.txt", "income tax return for 2025", 1L)
                Log.i(TAG, "fts match 'tax*': ${db.fileIndex().search("tax*").map { it.path }}; match 'nothing': ${db.fileIndex().search("nothing").size}")
                db.fileIndex().deletePath("/sdcard/Documents/tax-2025.txt")
                db.exportedApps().upsert(ExportedApp("com.samosaai.masala.apps.snake", "snake", "Snake", 1, 2L))
                Log.i(TAG, "exported: ${db.exportedApps().all()}")
                db.exportedApps().delete("com.samosaai.masala.apps.snake")
                Log.i(TAG, "after delete: index=${db.fileIndex().count()} apps=${db.exportedApps().all().size}")
                db.close()
            }
            else -> Toast.makeText(this, "cmd: install | db", Toast.LENGTH_SHORT).show()
        }
        // Stay up for install: the system's confirm dialog opens over this activity.
    }

    private companion object {
        const val TAG = "ProDebug"
    }
}
